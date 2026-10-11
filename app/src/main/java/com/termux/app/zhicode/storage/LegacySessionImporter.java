package com.termux.app.zhicode.storage;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把旧格式的会话一次性导入 {@code sessions.db}，然后删掉旧文件。
 *
 * <h3>为什么要有这个类，而不是让 {@code SessionStore} 继续兼容旧格式</h3>
 * 兼容读取的代价不是「多一个分支」，而是<b>每个写路径都要同时考虑两种格式</b>：
 * 改备注要写元数据事件再重写 manifest 再刷索引；删消息要整文件重写；
 * 列表要合并「索引表 + 目录扫描」两路来源。只要还有一条旧会话在，这套东西就删不掉。
 * 把它收敛到一次导入，之后所有读写都只对一个库，才是这次改动的意义。
 *
 * <h3>旧格式有两种</h3>
 * <ul>
 *   <li><b>v1</b>：{@code ~/.zhicode/projects/<项目键>/&lt;名字&gt;.jsonl}，
 *       一行一个 JSON 对象（可能并列放着 {@code *.jsonl.v2-migrated} 标记文件）。</li>
 *   <li><b>v2</b>：{@code ~/.zhicode/sessions/&lt;uuid&gt;/}，里面有
 *       {@code manifest.json}、{@code segments/*.zlog}（长度分帧 + JSON 负载）、
 *       {@code blobs/}（按 SHA-256 分层）与 {@code snapshots/*.snap}。</li>
 * </ul>
 *
 * <p><b>snapshots 不导入。</b>那些 {@code .snap} 文件只是旧版 {@code loadMessages}
 * 的加速检查点；同一份「压缩后上下文」在事件流里本来就有一条 {@code context_snapshot}
 * 事件，新旧两个读取端都会在遇到它时整体替换上下文。只导事件流，语义完全一致，
 * 而且不必处理「快照 seq 与事件 seq 对不上」这类边界。
 *
 * <h3>只有「校验通过」才删旧数据</h3>
 * 每个会话导入后回读一次，与旧数据比对四项：<b>消息条数</b>、<b>首条人类发言</b>、
 * <b>最后一条消息的角色与内容摘要</b>、以及<b>每一个外置大内容的 SHA-256</b>。
 * 任何一项不一致就整条会话判定为失败：旧文件<b>原地保留</b>，并写一条
 * {@code migration_log}；下次启动会再试。
 *
 * <p>这一条是硬约束，不是防御性编程。删掉旧文件之后，那份数据的唯一副本就是库里
 * 那一行；如果它在导入时就已经残缺（半截 Blob、字段没搬过来），用户会**静默地**
 * 失去一段对话，而我们连"曾经有过"都不知道。宁可留一堆旧文件，也不能删掉还没确认的东西。
 *
 * <h3>失败重试为什么要先清掉半成品</h3>
 * 上一次失败的导入会在 {@code sessions} 表里留下一条<b>建好了但没填完</b>的行。
 * 如果第二次启动只是看到「这个 id 已存在」就当作已导入，就会把还没搬完的旧目录删掉 ——
 * 那正是最糟的一种失败。所以这里对每个会话先判断「是导入完成（有 imported 记录）
 * 还是上次的残留（有行、没记录）」，后者先丢弃再重来。
 *
 * <h3>运行时机</h3>
 * 由 {@link SessionStore#listSessions()} 触发（那个方法本来就在 IO 线程上被调），
 * 进程内只尝试一次：{@link #ATTEMPTED} 置位后不再检查目录。
 */
final class LegacySessionImporter {

    private static final String LEGACY_SESSIONS_DIR = "sessions";
    private static final String LEGACY_MANIFEST = "manifest.json";
    private static final String LEGACY_INDEX_DB = "session-index.db";
    private static final String JSONL_SUFFIX = ".jsonl";
    private static final String MIGRATED_SUFFIX = ".v2-migrated";

    private static final int SEGMENT_MAGIC = 0x5A4C4F47; // ZLOG
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    /** 摘要里每条消息只取前若干字符参与比对：它只需要「能区分」，不需要完整。 */
    private static final int DIGEST_CHARS = 256;

    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private LegacySessionImporter() {}

    /** 进程内最多尝试一次。旧数据不存在时是一次很便宜的目录检查。 */
    static void ensureImported() {
        if (!ATTEMPTED.compareAndSet(false, true)) return;
        try {
            run();
        } catch (Throwable ignored) {
            // 导入是尽力而为的后台动作：失败不应该让会话列表打不开。
        }
    }

    private static void run() {
        File sessionsDir = new File(TermuxConstants.dataDir(), LEGACY_SESSIONS_DIR);
        File projectsDir = ProjectPaths.projectsDirectory();
        List<File> legacyV2 = listDirectories(sessionsDir);
        List<File> legacyV1 = listJsonl(projectsDir);
        if (legacyV2.isEmpty() && legacyV1.isEmpty()) {
            // 没有旧数据也要把历史遗留的索引库清掉，否则它会一直躺在那里。
            deleteLegacyIndex();
            return;
        }

        AtomicInteger failures = new AtomicInteger();
        // v2 先导：**成功**的 v2 会话会把它对应的 jsonl 记进 sourcedFromV2。
        Set<String> sourcedFromV2 = new HashSet<>();
        for (File directory : legacyV2) importV2Directory(directory, failures, sourcedFromV2);
        for (File file : legacyV1) importV1File(file, failures, sourcedFromV2);

        // 索引库只有在「一个都没失败」时才删：还有没导进去的会话时，
        // 那份索引是唯一还记得它们标题与备注的地方。
        if (failures.get() == 0) deleteLegacyIndex();
    }

    /**
     * 已经被某个 v2 会话**用自己的溯源字段声明过**的 jsonl 路径（本次导入内有效）。
     *
     * <h3>为什么必须去重</h3>
     *
     * <p>上一版迁移器把 jsonl 复制进 {@code sessions/<uuid>/} 之后，会在 v2 的
     * {@code manifest.json} 里写下 {@code legacy_source}，并且写一个标记文件
     * {@code X.jsonl.v2-migrated} —— <b>但从来不删那个 jsonl</b>
     * （那一版的 {@code cleanupLegacyAfterMigration} 全项目没有调用点）。
     *
     * <p>于是旧设计留下的结果是：{@code sessions/<uuid>/} 与 {@code X.jsonl} 都在，
     * 而本类先导全部 v2、再导全部 v1 —— 同一场对话被导入两次（一次用目录名当会话标识，
     * 一次分配一个新标识），会话列表里出现标题与条数都相同的两条。
     *
     * <p>标记文件挡不住这件事：本类列 jsonl 用的是 {@code endsWith(".jsonl")}，
     * 而 {@code X.jsonl.v2-migrated} 是另一个条目，只是恰好排在同一目录里。
     *
     * <p>所以去重必须靠 {@code legacy_source} —— 它是**精确的路径**，
     * 而且只在一个 v2 会话**校验通过之后**才会被信任（见 {@link #importV2Directory}）。
     * 放在"目录还在"而不是"校验通过"之后，会让 v2 导入失败时连兜底的 jsonl 也失去。
     */
    private static void rememberLegacySource(Set<String> sourcedFromV2, String legacySource) {
        if (legacySource == null || legacySource.isEmpty()) return;
        try {
            sourcedFromV2.add(new File(legacySource).getCanonicalPath());
        } catch (Exception ignored) {
            sourcedFromV2.add(legacySource);
        }
    }

    /** 与 {@link #rememberLegacySource} 配对：判断这个 jsonl 是否已经被某个 v2 会话收编。 */
    private static boolean alreadySourced(Set<String> sourcedFromV2, File jsonl) {
        if (sourcedFromV2 == null || sourcedFromV2.isEmpty()) return false;
        try {
            return sourcedFromV2.contains(jsonl.getCanonicalPath());
        } catch (Exception ignored) {
            return sourcedFromV2.contains(jsonl.getAbsolutePath());
        }
    }

    private static List<File> listDirectories(File root) {
        File[] entries = root.listFiles(File::isDirectory);
        if (entries == null) return Collections.emptyList();
        ArrayList<File> out = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static List<File> listJsonl(File projectsDir) {
        ArrayList<File> out = new ArrayList<>();
        File[] children = projectsDir.listFiles(File::isDirectory);
        if (children == null) return out;
        for (File child : children) {
            File[] files = child.listFiles((dir, name) -> name.endsWith(JSONL_SUFFIX));
            if (files == null) continue;
            out.addAll(Arrays.asList(files));
        }
        Collections.sort(out, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        return out;
    }

    // ------------------------------------------------------------ v2

    private static void importV2Directory(File directory, AtomicInteger failures, Set<String> sourcedFromV2) {
        File manifest = new File(directory, LEGACY_MANIFEST);
        if (!manifest.isFile()) {
            // 没有 manifest 的目录不是一次会话（可能只是残留），直接跳过而不是删掉。
            return;
        }
        String key = manifest.getAbsolutePath();
        String sessionId = directory.getName();
        SessionDatabase database = SessionDatabase.get();

        if (database.hasMigrationRecord(key)) {
            // 上一次已经确认导完：旧目录可以删。
            // ⚠️ 但**先**把溯源字段记下来：这个 v2 目录可能是上一版迁移器建的，
            // 它对应的 jsonl 还躺在 projects/ 里。漏掉这一步，下面 v1 那轮就会
            // 把同一场对话再导一遍（会话列表里出现两条）。
            rememberLegacySource(sourcedFromV2, readLegacySource(manifest));
            if (deleteTree(directory)) {
                database.recordMigration(key, "v2", "imported", "已导入过，清理旧目录", sessionId);
            }
            return;
        }
        if (SessionStore.exists(sessionId)) {
            // 有会话行、却没有 imported 记录 —— 这是上一次失败留下的半成品。
            // 必须先丢掉它再重来，否则就只能把还没搬完的旧目录删掉了。
            SessionStore.discardPartialImport(sessionId);
        }

        try {
            JSONObject header = readManifest(manifest);
            if (header == null) {
                importV1StyleFile(manifest, sessionId, key, directory, failures, sourcedFromV2);
                return;
            }
            String project = ProjectPaths.canonicalProject(header.optString("project", ""));
            SessionStore store = SessionStore.forImport(sessionId, project);

            JSONArray rows = readSegments(directory);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                appendLegacyRow(store, row);
            }
            store.flush();

            SessionStore.applyImportedMetadata(sessionId,
                    header.optString("title", ""), header.optString("note", ""),
                    header.optString("title_override", ""),
                    header.optLong("created_at", manifest.lastModified()),
                    header.optLong("updated_at", manifest.lastModified()),
                    header.optString("workflow_id", ""));

            verify(sessionId, rows);
            String warning = declaredMismatch(header, rows);

            // ⚠️ 顺序是硬要求：**校验通过之后**才把溯源路径记下来。
            // 放在前面的话，一个导入失败的 v2 目录会把兜底的 jsonl 也一起屏蔽掉 ——
            // 那场对话就再也救不回来了。
            rememberLegacySource(sourcedFromV2, header.optString(LEGACY_SOURCE, ""));

            if (!deleteTree(directory)) {
                throw new IllegalStateException("导入成功但旧目录删不掉：" + directory);
            }
            database.recordMigration(key, "v2", "imported",
                    "消息 " + digestOf(rows).optInt("messages", 0) + " 条" + warning, sessionId);
        } catch (Exception failure) {
            failures.incrementAndGet();
            database.recordMigration(key, "v2", "failed", describe(failure), sessionId);
        }
    }

    /** v2 manifest 里的溯源字段；旧版迁移器写的就是它。 */
    private static final String LEGACY_SOURCE = "legacy_source";

    /**
     * 只读 manifest 里的 {@code legacy_source}，不做格式判定。
     *
     * <p>与 {@link #readManifest} 分开：那个会拒掉 {@code format != 2} 的文件，
     * 而这里恰恰要在"已经导完、准备删目录"的路径上用，不该受格式判定影响。
     */
    private static String readLegacySource(File manifest) {
        try {
            return new JSONObject(AtomicFiles.readText(manifest)).optString(LEGACY_SOURCE, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 读 v2 manifest；不是 v2 格式（或损坏）时返回 null，交给 JSONL 路径处理。 */
    private static JSONObject readManifest(File manifest) {
        try {
            String text = AtomicFiles.readText(manifest);
            if (text.trim().isEmpty()) return null;
            JSONObject header = new JSONObject(text);
            // format != 2 说明这个 manifest.json 其实是被损坏的旧文件，按 JSONL 读。
            return header.optInt("format", 0) == 2 ? header : null;
        } catch (Exception broken) {
            return null;
        }
    }

    /**
     * 读 {@code segments/*.zlog}，还原成与旧 {@code scanV2} 相同的行序列。
     *
     * <p>每帧是「长度 + 类型 + 序号 + 时间戳 + JSON 负载」；负载里超大的
     * {@code content} / {@code payload} 会被外置到 {@code blobs/}，这里按需取回
     * <b>并校验摘要</b>。取不回来时抛错，让整条会话判定为失败 ——
     * 少一块内容的会话不该被当成"导入成功"。
     */
    private static JSONArray readSegments(File directory) throws IOException {
        JSONArray rows = new JSONArray();
        File segmentsDir = new File(directory, "segments");
        File[] segments = segmentsDir.listFiles((dir, name) -> name.endsWith(".zlog"));
        if (segments == null) return rows;
        Arrays.sort(segments, (a, b) -> a.getName().compareTo(b.getName()));
        for (File segment : segments) readSegment(segment, directory, rows);
        return rows;
    }

    private static void readSegment(File segment, File sessionDirectory, JSONArray rows) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(segment)))) {
            if (in.readInt() != SEGMENT_MAGIC || in.readShort() != 1) return;
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException end) {
                    // 进程被杀可能只留下半个长度前缀。
                    break;
                }
                if (length < 0 || length > MAX_FRAME_BYTES) break;
                try {
                    in.readInt();             // 帧类型：本文件只有一种，忽略
                    long sequence = in.readLong();
                    long timestamp = in.readLong();
                    byte[] body = new byte[length];
                    in.readFully(body);
                    JSONObject row = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    restoreBlob(row, "content", sessionDirectory);
                    restoreBlob(row, "payload", sessionDirectory);
                    row.put("timestamp", timestamp);
                    row.put("_seq", sequence);
                    rows.put(row);
                } catch (EOFException truncated) {
                    // 只忽略最后一个不完整的帧。
                    break;
                } catch (Exception broken) {
                    throw new IOException("会话分段读不出来：" + segment.getName(), broken);
                }
            }
        }
    }

    /**
     * 把外置的 content / payload 取回来并**校验摘要**。
     *
     * <p>旧 Blob 路径是 {@code <session>/blobs/aa/bb/<hash>}。这里不图省事直接读文本：
     * 内容寻址的全部价值就是「拿到的就是当初写进去的那一份」，少了校验，
     * 一次局部损坏会变成一段看似正常、实则被改写的对话。
     *
     * <p>引用存在却取不回来 / 对不上摘要时抛 {@link IOException}，而不是留着引用形态
     * 继续导入 —— 后者会得到一个「消息条数对得上、内容却是 {} 」的会话，
     * 而校验只比条数的话根本发现不了。
     */
    private static void restoreBlob(JSONObject row, String key, File sessionDirectory) throws IOException {
        JSONObject reference = row.optJSONObject(key);
        if (reference == null) return;
        String hash = reference.optString("blob_ref", "");
        if (hash.isEmpty()) return;
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("会话大内容标识非法：" + hash);

        File file = new File(new File(new File(new File(sessionDirectory, "blobs"),
                hash.substring(0, 2)), hash.substring(2, 4)), hash);
        byte[] data = AtomicFiles.readBytes(file, BlobStore.MAX_BLOB_BYTES);
        String actual = BlobStore.hashOf(data);
        if (!hash.equals(actual)) throw new IOException("会话大内容校验失败：" + hash);

        try {
            String text = new String(data, StandardCharsets.UTF_8);
            if ("content".equals(key)) row.put(key, new JSONArray(text));
            else if ("payload".equals(key)) row.put(key, new JSONObject(text));
            else row.put(key, text);
        } catch (Exception broken) {
            throw new IOException("会话大内容解不出来：" + hash, broken);
        }
    }

    // ------------------------------------------------------------ 行写入

    /** 把一行（消息或事件）写进新的存储。v1 与 v2 共用这一段。 */
    private static void appendLegacyRow(SessionStore store, JSONObject row) {
        String type = row.optString("type", "");
        if (RowTypeText.SESSION_START.equals(type)) return;
        if (RowTypeText.MESSAGE.equals(type)) {
            JSONArray content = row.optJSONArray("content");
            store.appendMessage(row.optString("role", "user"),
                    content == null ? new JSONArray() : content,
                    row.optString("message_id", ""), row.optString("turn_id", ""),
                    row.optString("origin", ""));
        } else {
            JSONObject payload = row.optJSONObject("payload");
            store.appendEvent(type, payload == null ? new JSONObject() : payload);
        }
    }

    // ------------------------------------------------------------ v1

    private static void importV1File(File file, AtomicInteger failures, Set<String> sourcedFromV2) {
        importV1StyleFile(file, null, file.getAbsolutePath(), null, failures, sourcedFromV2);
    }

    /**
     * 按 JSONL 导入。
     *
     * @param sessionId   指定的会话标识（v2 目录用目录名）；为 null 时新分配一个
     * @param removeAfter 导入成功后要删掉的东西；为 null 表示只删 {@code file} 本身
     * @param sourcedFromV2 已被某个 **校验通过** 的 v2 会话收编的 jsonl 路径
     */
    private static void importV1StyleFile(File file, String sessionId, String key, File removeAfter,
                                          AtomicInteger failures, Set<String> sourcedFromV2) {
        SessionDatabase database = SessionDatabase.get();

        // ⚠️ 这个判断必须在 hasMigrationRecord 之前：一个内容早已被 v2 会话收编的 jsonl，
        // 在 migration_log 里**没有**自己的记录（记录是记在 v2 目录的 manifest 路径上的），
        // 所以只按 hasMigrationRecord 判断它会一路走到下面再导一遍。
        if (alreadySourced(sourcedFromV2, file)) {
            deleteFileAndMarker(file);
            database.recordMigration(key, "v1", "imported",
                    "内容已由 v2 会话导入，清理重复的旧文件", sessionId);
            return;
        }

        if (database.hasMigrationRecord(key)) {
            boolean removed = removeAfter == null || !removeAfter.exists() ? file.delete() : deleteTree(removeAfter);
            if (removed || !file.exists()) {
                database.recordMigration(key, "v1", "imported", "已存在，跳过读取", sessionId);
            }
            return;
        }

        String id = sessionId == null || sessionId.isEmpty() ? UUID.randomUUID().toString() : sessionId;
        if (SessionStore.exists(id)) {
            // 上一次失败留下的半成品，先丢掉再重来（见类注释）。
            SessionStore.discardPartialImport(id);
        }

        try {
            JSONArray rows = readJsonl(file);
            String project = "";
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                if (RowTypeText.SESSION_START.equals(row.optString("type", ""))) {
                    project = row.optString("project", project);
                }
            }
            if (project.isEmpty()) project = TermuxConstants.TERMUX_HOME_DIR_PATH;

            SessionStore store = SessionStore.forImport(id, ProjectPaths.canonicalProject(project));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                appendLegacyRow(store, row);
            }
            store.flush();

            verify(id, rows);
            JSONObject metadata = legacyMetadataOf(rows);
            SessionStore.applyImportedMetadata(id, metadata.optString("title", ""),
                    metadata.optString("note", ""), metadata.optString("title_override", ""),
                    file.lastModified(), file.lastModified(), metadata.optString("workflow_id", ""));

            String base = file.getName().substring(0, file.getName().length() - JSONL_SUFFIX.length());
            // 删除迁移标记要在原文件之前：先删原文件而标记还在的话，
            // 下次启动会看到一个「已经导入过」的标记却找不到源文件。
            new File(file.getParentFile(), base + JSONL_SUFFIX + MIGRATED_SUFFIX).delete();
            boolean removed = removeAfter == null || !removeAfter.exists()
                    ? file.delete()
                    : deleteTree(removeAfter);
            if (!removed) throw new IllegalStateException("导入成功但旧数据删不掉：" + file);
            database.recordMigration(key, "v1", "imported",
                    "消息 " + digestOf(rows).optInt("messages", 0) + " 条", id);
        } catch (Exception failure) {
            failures.incrementAndGet();
            database.recordMigration(key, "v1", "failed", describe(failure), id);
        }
    }

    /**
     * 删掉一个 jsonl 连同它的 {@code .v2-migrated} 标记。
     *
     * <p>标记必须先删：反过来的话，一旦删文件成功而删标记失败，下次启动会看到
     * 一个「已经导入过」的标记却找不到源文件（就是 {@code cleanOrphanMarkers} 要清的那种）。
     */
    private static void deleteFileAndMarker(File jsonl) {
        try {
            String name = jsonl.getName();
            String base = name.endsWith(JSONL_SUFFIX)
                    ? name.substring(0, name.length() - JSONL_SUFFIX.length())
                    : name;
            new File(jsonl.getParentFile(), base + JSONL_SUFFIX + MIGRATED_SUFFIX).delete();
            jsonl.delete();
        } catch (Exception ignored) {
            // 删不掉不影响正确性：下次启动会再走一次这个分支。
        }
    }

    /** 逐行读一个 JSONL；解不出的行跳过（旧格式本来就是「宽容读取」）。 */
    private static JSONArray readJsonl(File file) {
        JSONArray rows = new JSONArray();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    rows.put(new JSONObject(trimmed));
                } catch (Exception broken) {
                    // 半行、损坏行：跳过它，其余照常。
                }
            }
        } catch (Exception ignored) {
            // 读不到就是空。
        }
        return rows;
    }

    /**
     * 旧 v1 里会话级的元数据：备注、标题覆盖、workflow id 与标题都藏在
     * {@code session_metadata} / {@code session_start} 这类事件行里。
     *
     * <p>取**最后一条**元数据事件，与旧实现「取最后一条有效值」的口径一致 ——
     * 用户改过两次备注时，生效的是后一次。
     */
    private static JSONObject legacyMetadataOf(JSONArray rows) {
        JSONObject metadata = new JSONObject();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String type = row.optString("type", "");
                JSONObject payload = row.optJSONObject("payload");
                if (RowTypeText.METADATA.equals(type) && payload != null) {
                    metadata.put("note", payload.optString("note", ""));
                    metadata.put("title_override", payload.optString("title_override", ""));
                } else if (RowTypeText.SESSION_START.equals(type)) {
                    metadata.put("workflow_id", row.optString("workflow_id", ""));
                } else if (payload != null && ("config".equals(type) || type.startsWith("plan_"))) {
                    String workflow = payload.optString("workflow_id", "");
                    if (!workflow.isEmpty()) metadata.put("workflow_id", workflow);
                }
            }
            // 标题沿用旧规则：取第一条人类发言（旧的 V1 读取端就是这么算标题的）。
            metadata.put("title", firstHumanText(rows));
        } catch (Exception ignored) {
            // 元数据取不到时留空，交给库侧的默认标题。
        }
        return metadata;
    }

    // ------------------------------------------------------------ 校验

    /**
     * 导入后与旧数据比对。任何一项不一致都抛错 —— 调用方据此**保留**旧文件。
     *
     * <p>比的是四项：消息条数、首条人类发言、最后一条消息的角色与内容摘要、
     * 以及（在读取阶段已经完成的）外置大内容的摘要。
     */
    private static void verify(String sessionId, JSONArray legacyRows) throws IOException {
        JSONArray storedRows = SessionStore.readRows(sessionId);
        JSONObject expected = digestOf(legacyRows);
        JSONObject actual = digestOf(storedRows);

        int expectedMessages = expected.optInt("messages", 0);
        int actualMessages = actual.optInt("messages", 0);
        if (expectedMessages != actualMessages) {
            throw new IOException("导入后消息条数不一致：旧 " + expectedMessages + " 条，新 " + actualMessages + " 条");
        }
        if (expectedMessages == 0) return;

        if (!expected.optString("first_human", "").equals(actual.optString("first_human", ""))) {
            throw new IOException("导入后首条人类发言不一致");
        }
        if (!expected.optString("last_role", "").equals(actual.optString("last_role", ""))
                || !expected.optString("last_digest", "").equals(actual.optString("last_digest", ""))) {
            throw new IOException("导入后最后一条消息不一致");
        }
        if (SessionStore.loadMessages(sessionId).length() == 0) {
            throw new IOException("导入后对话上下文为空");
        }
    }

    /**
     * 一条会话的「可比对摘要」。
     *
     * <p>刻意只取**字符串**（文本、工具 id / 名字、工具结果文本）而不比 JSON 的完整原文：
     * 二进制编解码往返之后数字的写法（{@code 1} 与 {@code 1.0}）可能变化，
     * 拿它做等值判断会产生假失败。假失败的方向是安全的（旧文件不删），
     * 但会让迁移永远做不完，所以这里选一个既能区分内容、又不受编码细节影响的摘要。
     */
    private static JSONObject digestOf(JSONArray rows) {
        int messages = 0;
        String firstHuman = "";
        String lastRole = "";
        String lastDigest = "";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !RowTypeText.MESSAGE.equals(row.optString("type", ""))) continue;
            messages++;
            String role = row.optString("role", "");
            JSONArray content = row.optJSONArray("content");
            lastRole = role;
            lastDigest = digestOfContent(content);
            if (firstHuman.isEmpty() && RowTypeText.ROLE_USER.equals(role)) {
                String origin = row.optString("origin", "");
                if (origin.isEmpty() || RowTypeText.ORIGIN_HUMAN.equals(origin)) {
                    String text = firstTextBlock(content);
                    if (!text.isEmpty()) firstHuman = text;
                }
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("messages", messages);
            out.put("first_human", clamp(firstHuman));
            out.put("last_role", lastRole);
            out.put("last_digest", lastDigest);
        } catch (Exception ignored) {
            // 上面都是字符串与整数，不会失败。
        }
        return out;
    }

    /** 一个内容块的摘要：类型 + 该类型的关键字符串。 */
    private static String digestOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            out.append(type).append('|');
            switch (type) {
                case "text":
                    out.append(clamp(block.optString("text", "")));
                    break;
                case "thinking":
                case "reasoning":
                    out.append(clamp(block.optString("thinking", "")));
                    break;
                case "tool_use":
                    out.append(block.optString("id", "")).append('|').append(block.optString("name", ""));
                    break;
                case "tool_result":
                    out.append(block.optString("tool_use_id", "")).append('|')
                            .append(block.optBoolean("is_error", false)).append('|')
                            .append(clamp(block.optString("content", "")));
                    break;
                case "image":
                    JSONObject source = block.optJSONObject("source");
                    // 图片只比长度与前缀：比完整 base64 会白白多算几 MB。
                    out.append(source == null ? "" : clamp(source.optString("data", "")));
                    break;
                default:
                    out.append(clamp(block.toString()));
                    break;
            }
            out.append(';');
        }
        return clamp(out.toString());
    }

    private static String clamp(String value) {
        if (value == null) return "";
        if (value.length() <= DIGEST_CHARS) return value;
        return value.length() + ":" + value.substring(0, DIGEST_CHARS);
    }

    /** 取第一条人类文本，与引擎写标题的规则保持一致。 */
    private static String firstHumanText(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !RowTypeText.MESSAGE.equals(row.optString("type", ""))) continue;
            if (!RowTypeText.ROLE_USER.equals(row.optString("role", ""))) continue;
            String origin = row.optString("origin", "");
            if (!origin.isEmpty() && !RowTypeText.ORIGIN_HUMAN.equals(origin)) continue;
            String text = firstTextBlock(row.optJSONArray("content"));
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String firstTextBlock(JSONArray content) {
        if (content == null) return "";
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null || !"text".equals(block.optString("type", ""))) continue;
            String text = block.optString("text", "").trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    /**
     * manifest 声明的条数与实际行数不一致时给出的提示。
     *
     * <p>**不作为失败**：旧 manifest 的 {@code message_count} 是增量维护的，
     * 中断过的会话完全可能少记或多记。真正决定成败的是「回读后的条数」，
     * 但把这个差异记进迁移日志，事后排查看得见。
     */
    private static String declaredMismatch(JSONObject header, JSONArray rows) {
        int declared = header.optInt("message_count", -1);
        int actual = digestOf(rows).optInt("messages", 0);
        if (declared < 0 || declared == actual) return "";
        return "（manifest 声明 " + declared + " 条，与实际的 " + actual + " 条不一致，已按实际导入）";
    }

    // ------------------------------------------------------------ 清理

    private static void deleteLegacyIndex() {
        File index = new File(TermuxConstants.dataDir(), LEGACY_INDEX_DB);
        if (index.isFile()) index.delete();
        new File(index.getAbsolutePath() + "-wal").delete();
        new File(index.getAbsolutePath() + "-shm").delete();
        new File(index.getAbsolutePath() + "-journal").delete();
    }

    /**
     * 清理"只剩标记、源文件已经不在"的遗留标记。
     *
     * <p>{@code X.jsonl.v2-migrated} 是**上一版**迁移器写的（它只会在 jsonl 导入成功之后
     * 把标记删掉，而那条路径在 v2 已迁移过的会话上根本走不到）。结果就是家目录里
     * 攒下一堆指向不存在文件的标记 —— 它们不影响功能，但会让"到底还有没有没导入的旧会话"
     * 这个问题没法一眼看清。
     *
     * <p>由 {@link SessionStore#maintain()} 调用，不是每次启动。
     *
     * @return 删掉的标记文件数
     */
    static int cleanOrphanMarkers() {
        int removed = 0;
        try {
            File projectsDir = ProjectPaths.projectsDirectory();
            File[] children = projectsDir.listFiles(File::isDirectory);
            if (children == null) return 0;
            for (File child : children) {
                File[] markers = child.listFiles((dir, name) -> name.endsWith(MIGRATED_SUFFIX));
                if (markers == null) continue;
                for (File marker : markers) {
                    String name = marker.getName();
                    String sourceName = name.substring(0, name.length() - MIGRATED_SUFFIX.length());
                    // 源文件还在 → 不动（它是"这个 jsonl 已经导过了"的证据）。
                    if (new File(marker.getParentFile(), sourceName).isFile()) continue;
                    if (marker.delete()) removed++;
                }
            }
        } catch (Exception ignored) {
            // 清理失败没有任何功能后果。
        }
        return removed;
    }

    private static boolean deleteTree(File directory) {
        if (directory == null || !directory.exists()) return true;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }
        return directory.delete();
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }

    /** 旧行类型 / 角色字面量。它们只在导入器里出现，日常读写路径不再需要。 */
    private static final class RowTypeText {
        static final String SESSION_START = "session_start";
        static final String MESSAGE = "message";
        static final String METADATA = "session_metadata";
        static final String ROLE_USER = "user";
        static final String ORIGIN_HUMAN = "human";

        private RowTypeText() {}
    }
}
