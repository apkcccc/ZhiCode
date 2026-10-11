package com.termux.app.zhicode.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 单会话归档：导出成 {@code .jsonl.gz}，也能读回来。
 *
 * <h3>为什么又回到 jsonl</h3>
 *
 * <p>运行时的存储是 {@code sessions.db}，那是对的：会话需要随机访问、需要原地改一条消息、
 * 需要按 seq 开窗。而**归档**要的是完全相反的一组性质 —— 自包含、可读、可 diff、
 * 能用一个文本编辑器打开抢救、任何工具都能解析。这两组要求没有一个格式能同时满足，
 * 所以分两层：库负责运行时，jsonl 负责搬运。
 *
 * <p>旧的错误不是"用了 jsonl"，而是**让 jsonl 当了运行时**：于是每次打开会话都要全量重放、
 * 改一条消息要整文件重写、删除要小心地整目录递归。现在这条边界是明确的：
 * 除本类与 {@link LegacySessionImporter}（一次性导入旧数据）之外，
 * 没有任何代码路径读写 jsonl。
 *
 * <h3>格式</h3>
 *
 * <p>gzip 压缩的 JSONL，一行一个对象，第一行必须是 {@code archive_header}：
 *
 * <pre>
 * {"type":"archive_header","format":1,"exported_at":…,"session_id":…,"project":…,
 *  "title":…,"note":…,"title_override":…,"workflow_id":…,"created_at":…,"updated_at":…,
 *  "message_count":…}
 * {"type":"row","seq":1,"type_":"message","role":"user","timestamp":…,"origin":"human",
 *  "content":[…内容块内联…]}
 * {"type":"row","seq":2,"row_type":"tool_diff","timestamp":…,"payload":{…}}
 * {"type":"snapshot","seq":5,"created_at":…,"messages":[…]}
 * </pre>
 *
 * <p><b>内容块内联</b>，所以归档是自包含的 —— 不需要并列一个 {@code blobs/} 目录。
 * 代价是文本变大，而 gzip 正好抵掉它（工具输出、base64 图片都是高压缩比的内容）。
 * 用一个自包含文件换"能直接发给别人 / 丢进网盘 / 用 cat 看"这件事是值得的。
 *
 * <h3>为什么边读边写</h3>
 *
 * <p>导出走 {@link SessionStore#readRowChunk} 分批读（每批 {@link #EXPORT_CHUNK_ROWS} 行），
 * 读到一批就写一批。整个会话一次装进内存的话，一个上万条消息的会话就是几百 MB ——
 * 与"打开会话 OOM"是同一个毛病，不该在导出这条路上重新长出来。
 *
 * <h3>导入为什么也算安全</h3>
 *
 * <p>导入走的是**正常写入路径**（{@link SessionStore#appendMessage} /
 * {@link SessionStore#appendEvent}），所以超 8 KB 的块会被重新外置回 {@code blobs/}、
 * 归属表会正常维护、seq 会重新分配。导入完成后比对消息条数，不符就把这次导入整个丢掉
 * （{@link SessionStore#discardPartialImport}）—— 宁可什么都没有，也不要半份历史。
 */
final class SessionArchive {

    /** 归档格式版本。读不认识的版本时直接拒绝，不要去猜字段。 */
    static final int FORMAT = 1;

    private static final String TYPE_HEADER = "archive_header";
    private static final String TYPE_ROW = "row";
    private static final String TYPE_SNAPSHOT = "snapshot";

    private static final String KEY_TYPE = "type";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_SEQ = "seq";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_ROLE = "role";
    private static final String KEY_ROW_TYPE = "row_type";

    /**
     * 消息行的行类型字面量。
     *
     * <p>与库里 {@code events.row_type} 的取值一致（{@code SessionStore.RowType.MESSAGE}）。
     * 用的是归档自己的常量而不是引用那个类的私有内部类：归档格式是**对外的契约**，
     * 它的字面量不该跟着内部重构一起改 —— 改一个就读不懂已经导出的文件。
     */
    private static final String ROW_TYPE_MESSAGE = "message";

    private static boolean isMessageRow(String rowType) {
        return ROW_TYPE_MESSAGE.equals(rowType);
    }

    /**
     * 导出时一批读多少行。
     *
     * <p>取值不敏感（只是内存与查询次数的平衡），但**必须有**：一次读完全部行等于
     * 把长会话整个装进内存。200 行与界面分页同一量级，一批的内存是可忽略的。
     */
    private static final int EXPORT_CHUNK_ROWS = 200;

    /** 导入时单行长度上限。一行超过它说明文件不是我们导出的（或已损坏）。 */
    private static final int MAX_LINE_CHARS = 64 * 1024 * 1024;

    private SessionArchive() {}

    // ------------------------------------------------------------ 导出

    /**
     * 把一个会话导出成 {@code .jsonl.gz}。
     *
     * @param target 目标文件；父目录不存在时会被创建
     * @return 目标文件
     */
    static File export(String sessionId, File target) throws Exception {
        JSONObject header = SessionStore.readSessionHeader(sessionId);
        if (header == null) throw new IOException("会话不存在：" + sessionId);

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建导出目录");
        }

        int rows = 0;
        try (GZIPOutputStream gzip = new GZIPOutputStream(
                new FileOutputStream(target), 64 * 1024);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
            writer.write(headerLine(header).toString());
            writer.newLine();

            long after = -1L;
            while (true) {
                JSONArray chunk = SessionStore.readRowChunk(sessionId, after, EXPORT_CHUNK_ROWS);
                if (chunk.length() == 0) break;
                for (int i = 0; i < chunk.length(); i++) {
                    JSONObject row = chunk.optJSONObject(i);
                    if (row == null) continue;
                    writer.write(rowLine(row).toString());
                    writer.newLine();
                    after = Math.max(after, row.optLong(KEY_SEQ, after));
                    rows++;
                }
            }

            JSONArray snapshots = SessionStore.readSnapshots(sessionId);
            for (int i = 0; i < snapshots.length(); i++) {
                JSONObject snapshot = snapshots.optJSONObject(i);
                if (snapshot == null) continue;
                writer.write(snapshotLine(snapshot).toString());
                writer.newLine();
            }
        } catch (Exception failed) {
            // 导出失败时不要留半个文件：下次导出的判断是"文件在不在"，
            // 半个文件会被当成一次成功的导出。
            target.delete();
            throw failed;
        }

        ZhiMemory.log("exportArchive session=" + sessionId + " rows=" + rows
                + " bytes=" + ZhiMemory.size(target.length() / 2));
        return target;
    }

    private static JSONObject headerLine(JSONObject session) throws Exception {
        return new JSONObject()
                .put(KEY_TYPE, TYPE_HEADER)
                .put(KEY_FORMAT, FORMAT)
                .put("exported_at", System.currentTimeMillis())
                .put("session_id", session.optString("id", ""))
                .put("project", session.optString("project", ""))
                .put("title", session.optString("title", ""))
                .put("note", session.optString("note", ""))
                .put("title_override", session.optString("title_override", ""))
                .put("workflow_id", session.optString("workflow_id", ""))
                .put("created_at", session.optLong("created_at", 0L))
                .put("updated_at", session.optLong("updated_at", 0L))
                .put("message_count", session.optLong("message_count", 0L));
    }

    /**
     * 一行事件。
     *
     * <p>字段名与库里一致（{@code type} / {@code role} / {@code content} / {@code payload}），
     * 只有"行类型"另起一个名字（{@code row_type}）—— 因为 {@code type} 已经被
     * "这一行是 message 还是某个事件"占用了（库里的 {@code row_type} 列就是它）。
     * 名字撞车会让导入端必须靠上下文猜，那是出错的地方。
     */
    private static JSONObject rowLine(JSONObject row) throws Exception {
        JSONObject out = new JSONObject();
        out.put(KEY_TYPE, TYPE_ROW);
        out.put(KEY_SEQ, row.optLong(KEY_SEQ, 0L));
        out.put("timestamp", row.optLong("timestamp", 0L));
        String rowType = row.optString("type", "");
        if (isMessageRow(rowType)) {
            out.put(KEY_ROW_TYPE, ROW_TYPE_MESSAGE);
            out.put(KEY_ROLE, row.optString(KEY_ROLE, "user"));
            out.put(KEY_CONTENT, row.optJSONArray(KEY_CONTENT) == null
                    ? new JSONArray() : row.optJSONArray(KEY_CONTENT));
            putIfNotEmpty(out, "message_id", row.optString("message_id", ""));
            putIfNotEmpty(out, "turn_id", row.optString("turn_id", ""));
            putIfNotEmpty(out, "origin", row.optString("origin", ""));
        } else {
            out.put(KEY_ROW_TYPE, rowType);
            JSONObject payload = row.optJSONObject(KEY_PAYLOAD);
            out.put(KEY_PAYLOAD, payload == null ? new JSONObject() : payload);
        }
        return out;
    }

    private static JSONObject snapshotLine(JSONObject snapshot) throws Exception {
        JSONArray messages = snapshot.optJSONArray(KEY_MESSAGES);
        return new JSONObject()
                .put(KEY_TYPE, TYPE_SNAPSHOT)
                .put(KEY_SEQ, snapshot.optLong(KEY_SEQ, 0L))
                .put("created_at", snapshot.optLong("created_at", 0L))
                .put(KEY_MESSAGES, messages == null ? new JSONArray() : messages);
    }

    private static void putIfNotEmpty(JSONObject json, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) json.put(key, value);
    }

    // ------------------------------------------------------------ 导入

    /**
     * 把一个归档导入成一条**新会话**。
     *
     * <p>刻意不覆盖同 id 的会话：覆盖的语义是"用文件里的版本替换掉现在的"，
     * 而用户点导入时想要的是"把这份历史拿回来"。要覆盖得由用户先删掉旧的 ——
     * 那是显式动作，不会被一次误点触发。
     *
     * @return 新会话的标识
     */
    static String importArchive(File source) throws Exception {
        if (source == null || !source.isFile()) throw new IOException("归档文件不存在");
        String sessionId = UUID.randomUUID().toString();
        String project = null;
        int writtenRows = 0;
        long expectedMessages = -1L;
        int importedMessages = 0;

        try (GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(source), 64 * 1024);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 64 * 1024)) {

            String first = reader.readLine();
            if (first == null) throw new IOException("归档是空文件");
            JSONObject header = new JSONObject(first);
            if (!TYPE_HEADER.equals(header.optString(KEY_TYPE, ""))) {
                throw new IOException("不是 ZhiCode 会话归档（第一行不是 archive_header）");
            }
            int format = header.optInt(KEY_FORMAT, 0);
            if (format != FORMAT) {
                throw new IOException("归档格式版本 " + format + " 不受支持（当前 " + FORMAT + "）");
            }
            project = header.optString("project", "");
            expectedMessages = header.optLong("message_count", -1L);

            SessionStore store = SessionStore.forImport(sessionId, project);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.length() > MAX_LINE_CHARS) throw new IOException("归档里有一行过长，已拒绝");
                JSONObject object = new JSONObject(line);
                String type = object.optString(KEY_TYPE, "");
                if (TYPE_ROW.equals(type)) {
                    if (appendRow(store, object)) importedMessages++;
                    writtenRows++;
                } else if (TYPE_SNAPSHOT.equals(type)) {
                    JSONArray messages = object.optJSONArray(KEY_MESSAGES);
                    if (messages != null) store.appendContextSnapshot(messages);
                }
                // 其它类型：不认识的类型一律跳过，不猜。
            }
            store.flush();

            SessionStore.applyImportedMetadata(sessionId,
                    header.optString("title", ""), header.optString("note", ""),
                    header.optString("title_override", ""),
                    header.optLong("created_at", System.currentTimeMillis()),
                    header.optLong("updated_at", System.currentTimeMillis()),
                    header.optString("workflow_id", ""));

            verify(sessionId, expectedMessages, importedMessages);
        } catch (Exception failed) {
            // 半份历史比没有历史更糟：它看起来是完整的，只是少了一段。
            SessionStore.discardPartialImport(sessionId);
            ZhiMemory.log("importArchive failed error=" + failed);
            throw failed;
        }

        ZhiMemory.log("importArchive session=" + sessionId + " rows=" + writtenRows
                + " messages=" + importedMessages);
        return sessionId;
    }

    /** @return 这一行是不是一条消息 */
    private static boolean appendRow(SessionStore store, JSONObject row) {
        String rowType = row.optString("row_type", "");
        if (isMessageRow(rowType)) {
            JSONArray content = row.optJSONArray(KEY_CONTENT);
            store.appendMessage(row.optString(KEY_ROLE, "user"),
                    content == null ? new JSONArray() : content,
                    row.optString("message_id", ""), row.optString("turn_id", ""),
                    row.optString("origin", ""));
            return true;
        }
        JSONObject payload = row.optJSONObject(KEY_PAYLOAD);
        store.appendEvent(rowType, payload == null ? new JSONObject() : payload);
        return false;
    }

    /**
     * 导入后校验。
     *
     * <p>只比一个数（消息条数）就够：它是唯一能被独立复核的量，而且任何"写到一半失败"
     * 都会让这个数偏小。归档头里的数缺失（{@code < 0}）时跳过比对 ——
     * 那是别人手工拼的归档，不该因为缺一个字段就拒绝导入。
     */
    private static void verify(String sessionId, long expectedMessages, int importedMessages)
            throws IOException {
        int stored = SessionStore.storedMessageCount(sessionId);
        if (stored != importedMessages) {
            throw new IOException("导入后消息条数不一致：写入 " + importedMessages + " 条，库里 " + stored + " 条");
        }
        if (expectedMessages >= 0 && stored != expectedMessages) {
            throw new IOException("导入后消息条数不一致：归档声明 " + expectedMessages + " 条，实际 " + stored + " 条");
        }
    }
}
