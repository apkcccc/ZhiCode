package com.termux.app.zhicode.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.termux.app.zhicode.json.JsonItems;
import com.termux.app.zhicode.model.PlanWorkflowState;
import com.termux.app.zhicode.model.SessionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话历史的持久化：全部存在 {@code ~/.zhicode/sessions.db} 里。
 *
 * <h3>它现在是什么</h3>
 * 这一版把会话从「一次会话一个目录 + 一个全局索引库」换成了**单一 SQLite 库**。
 * 运行时不再读写 {@code *.jsonl}、{@code segments/*.zlog}、{@code manifest.json}、
 * {@code snapshots/*.snap}、{@code session-index.db}；旧格式只在
 * {@link LegacySessionImporter} 里被读取一次，导入完就删掉。
 *
 * <p>落盘的内容也不是 JSON 文本：引擎与三个协议适配器仍然用 {@code org.json} 的对象
 * 作为内存表示（那是它们的接口，不动），但写进数据库时经 {@link BinaryValueCodec}
 * 编成紧凑二进制，常用标量另外拆成原生列。
 *
 * <h3>消息与事件共用同一个 seq</h3>
 * 一条消息和一个事件是两种东西，但它们在历史里的**顺序**必须是一条线：
 * 界面的 {@code SessionReader} 是按「第几行」回放对话的，工具卡要挂到它前面那条
 * assistant 消息上。分两张表各用各的自增 id 就再也对不上顺序了，
 * 所以这里统一成 {@code events(session_id, seq)}，{@code kind} 区分它是一条约消息
 * 还是一个事件。
 *
 * <h3>快照不是正文</h3>
 * {@code context_snapshots} 存的是**给模型看的**压缩后上下文。它不影响界面：
 * 界面渲染的永远是完整的消息行。{@link #loadMessages} 遇到最新快照就从那里起步，
 * 之后的 {code message} 行续接 —— 这正是压缩之后还能正确恢复的原因。
 *
 * <h3>改一条历史消息为什么要引用校验</h3>
 * 调用方给出的是「用户看到的那一条」，而它的 {@code seq} 会随快照、被删的消息漂移，
 * 中间还可能有别的写入。所以调用方必须给出 {@code messageId}（或旧位置）
 * <b>外加内容摘要</b>；对不上就拒绝修改，而不是猜一条去改。
 *
 * <p>删掉一条消息之后，它<b>之后</b>的 {@code context_*} 事件与快照要一并丢弃：
 * 那些快照是基于「包含这条消息」的上下文生成的，留着会让模型继续看到改之前的版本，
 * 而界面显示的是改之后的 —— 这种不一致极难排查。
 *
 * <h3>写入节奏</h3>
 * 流式回合里消息是高频写入的，每写一条就提交一次事务会让打字明显卡顿。所以消息与
 * 事件先攒在内存里，达到 8 KB 或过了 500 ms 再整批提交；用户消息、快照、
 * 改备注这类「丢不起」的写入则立即提交。
 *
 * <h3>线程</h3>
 * 写路径在 {@link SessionDatabase#lock()} 上串行；读取不取锁（WAL 允许读写并发）。
 */
public final class SessionStore {

    // ------------------------------------------------------------ 表与列

    private static final String C_SESSION_ID = "session_id";
    private static final String C_SEQ = "seq";
    private static final String C_KIND = "kind";
    private static final String C_ROW_TYPE = "row_type";
    private static final String C_TIMESTAMP = "timestamp";
    private static final String C_ROLE = "role";
    private static final String C_MESSAGE_ID = "message_id";
    private static final String C_TURN_ID = "turn_id";
    private static final String C_ORIGIN = "origin";
    private static final String C_PAYLOAD = "payload";
    private static final String C_PAYLOAD_BLOB_REF = "payload_blob_ref";
    private static final String C_PAYLOAD_BLOB_SIZE = "payload_blob_size";
    private static final String C_MESSAGE_SEQ = "message_seq";
    private static final String C_ORDINAL = "ordinal";
    private static final String C_TEXT_VALUE = "text_value";
    private static final String C_TOOL_USE_ID = "tool_use_id";
    private static final String C_TOOL_NAME = "tool_name";
    private static final String C_IS_ERROR = "is_error";
    private static final String C_MEDIA_TYPE = "media_type";
    private static final String C_DATA = "data";
    private static final String C_BLOB_REF = "blob_ref";
    private static final String C_BLOB_SIZE = "blob_size";
    private static final String C_TITLE = "title";
    private static final String C_NOTE = "note";
    private static final String C_TITLE_OVERRIDE = "title_override";
    private static final String C_WORKFLOW_ID = "workflow_id";
    private static final String C_CREATED_AT = "created_at";
    private static final String C_UPDATED_AT = "updated_at";
    private static final String C_MESSAGE_COUNT = "message_count";
    private static final String C_NEXT_SEQ = "next_seq";
    private static final String C_PROJECT = "project";
    private static final String C_ID = "id";

    /** 两类事件行：一条消息，或一个普通事件。 */
    private static final String KIND_MESSAGE = "message";
    private static final String KIND_EVENT = "event";

    /** 备注长度上限。它只用于列表显示，不需要更长。 */
    private static final int MAX_NOTE_CHARS = 500;
    /** 标题覆盖长度上限。 */
    private static final int MAX_TITLE_OVERRIDE_CHARS = 120;
    /** 自动生成标题（取人类首条发言）的长度上限。 */
    private static final int MAX_TITLE_CHARS = 48;

    /** 压缩策略标识。写进历史，便于事后判断某次压缩是谁做的。 */
    private static final String COMPACTION_STRATEGY = "model_semantic_api_round_v2";

    /** 上下文摘要块的起始标记，由 {@code ContextCompactor} 写入。 */
    private static final String CONTEXT_SUMMARY_MARKER = "<context_summary>";

    /**
     * 内部续跑标记。
     *
     * <p>由 {@code ZhiCodeEngine} 在「模型输出被截断、需要继续」时写进历史。它的作用就是让
     * 这段文本<b>不被当成人类发言</b>：否则它会变成会话标题，还会出现在可编辑的用户消息里。
     */
    private static final String INTERNAL_CONTINUE_MARKER = "<zhicode_internal_continue>";

    /** 每个会话只保留最近几个快照。再多也不会有读路径用到，只是白占空间。 */
    private static final int MAX_RETAINED_SNAPSHOTS = 3;

    /** 攒够这么多字节就整批提交。 */
    private static final long PENDING_FLUSH_BYTES = 8L * 1024L;
    /** 攒够这么久就整批提交；保证流式过程中不会长时间一条都不落盘。 */
    private static final long PENDING_FLUSH_MS = 500L;

    // ------------------------------------------------------------ 行类型

    /**
     * 行类型。这些串会写进数据库，属于持久化契约：改一个就等于读不懂旧会话。
     */
    private static final class RowType {
        static final String SESSION_START = "session_start";
        static final String MESSAGE = "message";
        static final String METADATA = "session_metadata";
        static final String SNAPSHOT = "context_snapshot";
        static final String COMPACTION = "context_compaction";
        static final String PROFILE_BINDING = "profile_binding";
        static final String TURN_CONFIG = "turn_config";
        /** 任务清单快照（引擎在每次任务变更时追加，见 `ZhiCodeEngine`）。 */
        static final String TASK_SNAPSHOT = "task_snapshot";

        private RowType() {}
    }

    /** 消息角色。只有这两种会被写进历史。 */
    private static final class Role {
        static final String USER = "user";
        static final String ASSISTANT = "assistant";

        private Role() {}
    }

    /** 内容块类型。{@code thinking} 与 {@code reasoning} 是同一种东西的两种历史写法。 */
    private static final class Block {
        static final String TEXT = "text";
        static final String THINKING = "thinking";
        static final String REASONING = "reasoning";
        static final String IMAGE = "image";
        static final String TOOL_USE = "tool_use";
        static final String TOOL_RESULT = "tool_result";

        private Block() {}
    }

    /**
     * 内容块的整型类型码。
     *
     * <p>用它而不是字符串：块类型是最常被判定的字段，每个块省下几个字节，
     * 而且拼错一个字符串不会再变成「这块静默消失」。
     */
    private static final int BLOCK_UNKNOWN = 0;
    private static final int BLOCK_TEXT = 1;
    private static final int BLOCK_THINKING = 2;
    private static final int BLOCK_REASONING = 3;
    private static final int BLOCK_IMAGE = 4;
    private static final int BLOCK_TOOL_USE = 5;
    private static final int BLOCK_TOOL_RESULT = 6;

    /**
     * JSON 字段名。
     *
     * <p>集中在一处是因为同一个字段名（例如 {@code payload}）在本类里出现十几次，
     * 每处各写一份字面量的话，改字段名时一定会漏掉某一处，而漏掉的那处只会表现为
     * 「某类历史读不出来」。
     */
    private static final class Field {
        static final String TYPE = "type";
        static final String ROLE = "role";
        static final String CONTENT = "content";
        static final String PAYLOAD = "payload";
        static final String TIMESTAMP = "timestamp";
        static final String MESSAGE_ID = "message_id";
        static final String TURN_ID = "turn_id";
        static final String ORIGIN = "origin";
        static final String PROJECT = "project";
        static final String CREATED_AT = "created_at";
        static final String WORKFLOW_ID = "workflow_id";
        static final String TEXT = "text";
        static final String THINKING = "thinking";
        static final String ID = "id";
        static final String NAME = "name";
        static final String INPUT = "input";
        static final String TOOL_USE_ID = "tool_use_id";
        static final String IS_ERROR = "is_error";
        static final String SOURCE = "source";
        static final String MEDIA_TYPE = "media_type";
        static final String DATA = "data";
        static final String PROFILE_ID = "profile_id";
        static final String PROFILE_REVISION = "profile_revision";
        static final String CREDENTIAL_REVISION = "credential_revision";
        static final String PROTOCOL = "protocol";
        static final String BASE_URL = "base_url";
        static final String MODEL = "model";
        static final String MESSAGES = "messages";

        private Field() {}
    }

    // ------------------------------------------------------------ 值对象

    /** 一次会话的备注与标题覆盖。两者都要清理过才落盘。 */
    public static final class SessionMetadata {
        public final String note;
        public final String titleOverride;

        public SessionMetadata(String note, String titleOverride) {
            this.note = sanitizeMetadata(note, MAX_NOTE_CHARS);
            // 标题是单行的：换行会让它在列表里被截成两截，看起来像半个标题。
            this.titleOverride = singleLine(sanitizeMetadata(titleOverride, MAX_TITLE_OVERRIDE_CHARS));
        }
    }

    /**
     * 会话列表里的一行。
     *
     * <p>{@code id} 是**会话标识**（UUID），不再是文件路径 —— 会话已经不按文件存放了。
     * 界面拿它去恢复会话、改备注、删除。
     */
    public static final class SessionSummary {
        public final String id;
        public final String project;
        public final String title;
        public final String note;
        public final String titleOverride;
        public final long createdAt;
        /** 最后一次对话活动的时间。改备注不得改变它。 */
        public final long activityModifiedAt;
        /** 兼容旧调用方的别名，与 {@link #activityModifiedAt} 同值。 */
        public final long modifiedAt;
        public final int messageCount;

        SessionSummary(String id, String project, String title, String note, String titleOverride,
                       long createdAt, long activityModifiedAt, int messageCount) {
            this.id = id == null ? "" : id;
            this.project = orEmpty(project);
            String candidate = orEmpty(title).trim();
            this.title = candidate.isEmpty() ? this.id : candidate;
            this.note = orEmpty(note);
            this.titleOverride = orEmpty(titleOverride);
            this.createdAt = createdAt;
            this.activityModifiedAt = activityModifiedAt;
            this.modifiedAt = activityModifiedAt;
            this.messageCount = messageCount;
        }
    }

    /**
     * 指向一条历史消息的引用。
     *
     * <p>三个字段一起构成校验：{@code messageId}（新格式）或 {@code legacyRowIndex}
     * （旧格式）<b>加上</b> {@code contentHash}。任何一项对不上就拒绝修改。
     */
    public static final class MessageReference {
        public final String messageId;
        public final int legacyRowIndex;
        public final String contentHash;

        public MessageReference(String messageId, int legacyRowIndex, String contentHash) {
            this.messageId = orEmpty(messageId);
            this.legacyRowIndex = legacyRowIndex;
            this.contentHash = orEmpty(contentHash);
        }
    }

    /** 会话创建时用的 API 配置快照。用于事后判断「这次对话是哪个模型跑的」。 */
    public static final class ProfileBinding {
        public final String profileId;
        public final int profileRevision;
        public final int credentialRevision;
        public final String protocol;
        public final String baseUrl;
        public final String model;

        ProfileBinding(JSONObject payload) {
            profileId = payload.optString(Field.PROFILE_ID, "");
            profileRevision = Math.max(1, payload.optInt(Field.PROFILE_REVISION, 1));
            credentialRevision = Math.max(1, payload.optInt(Field.CREDENTIAL_REVISION, 1));
            protocol = payload.optString(Field.PROTOCOL, "");
            baseUrl = payload.optString(Field.BASE_URL, "");
            model = payload.optString(Field.MODEL, "");
        }
    }

    /** 一条行本身（读出来的形态）。{@code json} 为 null 表示这一行没能解出来。 */
    private static final class Row {
        final long seq;
        final JSONObject json;

        Row(long seq, JSONObject json) {
            this.seq = seq;
            this.json = json;
        }
    }

    // ------------------------------------------------------------ 实例状态

    private final String sessionId;
    private final String project;
    private long nextSeq;
    private long messageCount;
    private String workflowId = "";

    private final ArrayList<PendingRow> pending = new ArrayList<>();
    private long pendingBytes;
    private long pendingSince;
    /** 一批待提交里有没有「非元数据」的写入；决定要不要推进 updated_at。 */
    private boolean pendingTouchesActivity;
    /** 待写的第一条人类发言文本，用于给还没有标题的会话生成标题。 */
    private String pendingTitleCandidate = "";

    /** 一个待提交的行：一条消息或一个事件。 */
    private static final class PendingRow {
        final boolean message;
        final String rowType;
        final long timestamp;
        final String role;
        final String messageId;
        final String turnId;
        final String origin;
        final JSONArray content;
        final JSONObject payload;

        PendingRow(boolean message, String rowType, long timestamp, String role, String messageId,
                   String turnId, String origin, JSONArray content, JSONObject payload) {
            this.message = message;
            this.rowType = rowType;
            this.timestamp = timestamp;
            this.role = role;
            this.messageId = messageId;
            this.turnId = turnId;
            this.origin = origin;
            this.content = content;
            this.payload = payload;
        }

        /** 估算这条行占多少字节，用于决定是否该整批提交。 */
        int weigh() {
            if (message) return content == null ? 0 : content.toString().length();
            return payload == null ? 0 : payload.toString().length();
        }
    }

    /** 已经算好编码与 Blob 的一条行，可以直接进事务。 */
    private static final class PreparedRow {
        long seq;
        String kind;
        String rowType;
        long timestamp;
        String role = "";
        String messageId = "";
        String turnId = "";
        String origin = "";
        byte[] payload;
        String payloadBlobRef = "";
        int payloadBlobSize;
        List<ContentValues> blocks = new ArrayList<>();
    }

    // ------------------------------------------------------------ 构造

    /** 在一个项目下新建一次会话。 */
    public SessionStore(String projectDirectory) {
        this.project = ProjectPaths.canonicalProject(projectDirectory);
        this.sessionId = UUID.randomUUID().toString();
        this.nextSeq = 0L;
        this.messageCount = 0;
        long now = System.currentTimeMillis();
        synchronized (SessionDatabase.lock()) {
            try {
                SQLiteDatabase db = SessionDatabase.get().db();
                ContentValues values = new ContentValues();
                values.put(C_ID, sessionId);
                values.put(C_PROJECT, project);
                values.put(C_TITLE, "");
                values.put(C_NOTE, "");
                values.put(C_TITLE_OVERRIDE, "");
                values.put(C_WORKFLOW_ID, "");
                values.put(C_CREATED_AT, now);
                values.put(C_UPDATED_AT, now);
                values.put(C_MESSAGE_COUNT, 0);
                values.put(C_NEXT_SEQ, 0L);
                db.insert(SessionDatabase.TABLE_SESSIONS, null, values);
            } catch (Exception ignored) {
                // 存储不可用时引擎仍然保留内存里的历史；这里不把构造变成失败。
            }
        }
        appendEvent(RowType.SESSION_START, sessionStartPayload());
    }

    private JSONObject sessionStartPayload() {
        JSONObject payload = new JSONObject();
        try {
            payload.put(Field.PROJECT, project);
            payload.put(Field.WORKFLOW_ID, workflowId);
            payload.put(Field.CREATED_AT, System.currentTimeMillis());
        } catch (Exception ignored) {
            // 见 appendEvent 的说明。
        }
        return payload;
    }

    private SessionStore(String sessionId, String project, long nextSeq, long messageCount,
                         String workflowId) {
        this.sessionId = sessionId;
        this.project = project;
        this.nextSeq = nextSeq;
        this.messageCount = messageCount;
        this.workflowId = workflowId == null ? "" : workflowId;
    }

    /** 恢复一次已有会话；不存在时抛 {@link IllegalArgumentException}。 */
    public static SessionStore resume(String sessionId) {
        SessionSummary summary = summarizeOrNull(sessionId);
        if (summary == null) throw new IllegalArgumentException("会话不存在：" + sessionId);
        long nextSeq = nextSequenceOf(sessionId);
        String workflow = readWorkflowId(sessionId);
        return new SessionStore(sessionId, summary.project, nextSeq, summary.messageCount, workflow);
    }

    /** 会话标识。界面与引擎通过它定位一次会话。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 这次会话属于哪个项目目录。 */
    public String getProject() {
        return project;
    }

    /** 立即提交攒下的行。 */
    public synchronized void flush() {
        try {
            commitPending();
        } catch (Exception ignored) {
            // 内存里的历史仍然是权威；提交失败只影响「下次能不能恢复」。
        }
    }

    /** 是否已有这次会话。 */
    public static boolean exists(String sessionId) {
        return summarizeOrNull(sessionId) != null;
    }

    // ------------------------------------------------------------ 写入

    public synchronized String appendMessage(String role, JSONArray content) {
        return appendMessage(role, content, "", "", "");
    }

    /**
     * 追加一条消息。
     *
     * @return 这条消息的 id（调用方给了就用给的），供以后编辑/删除精确定位
     */
    public synchronized String appendMessage(String role, JSONArray content, String messageId,
                                             String turnId, String origin) {
        String id = isBlank(messageId) ? UUID.randomUUID().toString() : messageId;
        PendingRow row = new PendingRow(true, RowType.MESSAGE, System.currentTimeMillis(), role, id,
                turnId, origin, content, null);
        enqueue(row, isBlank(origin) || "human".equals(origin) ? firstHumanText(content) : "");
        return id;
    }

    public synchronized void appendProfileBinding(SessionConfig config) {
        appendEvent(RowType.PROFILE_BINDING, profilePayload(config));
    }

    public synchronized void appendTurnConfig(SessionConfig config, String turnId) {
        JSONObject payload = profilePayload(config);
        try {
            payload.put(Field.TURN_ID, orEmpty(turnId));
        } catch (Exception ignored) {
            // 见 appendEvent 的说明。
        }
        appendEvent(RowType.TURN_CONFIG, payload);
    }

    /**
     * 追加一个事件。
     *
     * <p>事件行是辅助信息。写不进去时静默忽略 —— 「记录一个事件」这种动作
     * 不该把主流程（发消息、跑工具）带崩。
     */
    public synchronized void appendEvent(String type, JSONObject payload) {
        PendingRow row = new PendingRow(false, type == null ? "" : type, System.currentTimeMillis(),
                "", "", "", "", null, payload == null ? new JSONObject() : payload);
        enqueue(row, "");
    }

    public synchronized void appendCompaction(String summary, int removedMessages, int retainedMessages) {
        appendCompaction(summary, removedMessages, retainedMessages, -1, -1, "legacy", "");
    }

    /**
     * 记录一次上下文压缩。
     *
     * <p>token 计数用负数表示「未知」，此时字段不写入 —— 写 0 会被误读成
     * 「压缩后剩 0 个 token」。
     */
    public synchronized void appendCompaction(String summary, int removedMessages, int retainedMessages,
                                             int beforeTokens, int afterTokens, String trigger, String model) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("summary", orEmpty(summary));
            payload.put("removed_messages", removedMessages);
            payload.put("retained_messages", retainedMessages);
            payload.put("strategy", COMPACTION_STRATEGY);
            payload.put("trigger", trigger == null ? "unknown" : trigger);
            payload.put(Field.MODEL, orEmpty(model));
            if (beforeTokens >= 0) payload.put("before_tokens", beforeTokens);
            if (afterTokens >= 0) payload.put("after_tokens", afterTokens);
        } catch (Exception ignored) {
            // 见 appendEvent 的说明。
        }
        appendEvent(RowType.COMPACTION, payload);
    }

    /**
     * 记录面向提供方的压缩后上下文。
     *
     * <p>完整的人类对话仍然留在消息行里；这个快照只影响 {@link #loadMessages}。
     * 落盘前深拷贝一次：调用方之后还会继续改它传进来的那个数组。
     *
     * <p>这里**先 flush 再取 seq**：快照的 seq 必须是「它包含了哪些行」的那个位置，
     * 先记 seq 再 flush 会让快照把自己的起点算晚一步，恢复时漏掉一批消息。
     */
    public synchronized void appendContextSnapshot(JSONArray messages) {
        try {
            JSONArray snapshot = messages == null ? new JSONArray() : copy(messages);
            commitPending();
            long seq = nextSeq;
            byte[] encoded = BinaryValueCodec.encodeArray(snapshot);
            int encodedLength = encoded.length;
            String blobRef = "";
            if (encodedLength >= BlobStore.THRESHOLD_BYTES) {
                blobRef = BlobStore.shared().put(encoded);
                encoded = null;
            }
            synchronized (SessionDatabase.lock()) {
                SQLiteDatabase db = SessionDatabase.get().db();
                // 快照写入 + 归属 + 丢弃旧快照必须在**一个事务**里：分开做的话，
                // 中途失败会留下"旧快照已经删了、新快照的归属还没写"的中间态。
                db.beginTransaction();
                try {
                    ContentValues values = new ContentValues();
                    values.put(C_SESSION_ID, sessionId);
                    values.put(C_SEQ, seq);
                    values.put(C_CREATED_AT, System.currentTimeMillis());
                    if (blobRef.isEmpty()) values.put("messages", encoded);
                    else {
                        values.put(C_BLOB_REF, blobRef);
                        values.put(C_BLOB_SIZE, encodedLength);
                        SessionDatabase.get().registerBlob(blobRef, encodedLength);
                    }
                    db.insertWithOnConflict(SessionDatabase.TABLE_SNAPSHOTS, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    SessionDatabase.get().addBlobRef(db, sessionId, blobRef);
                    pruneSnapshots(db);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                // 被 pruneSnapshots 删掉的旧快照如果外置过大内容，那些 Blob 此刻就没有引用了。
                // 这里**只重建归属**，不做回收：回收要过宽限期、要分批，
                // 而且此刻正有一个 Blob 刚刚登记（见 pruneOrphanBlobs 的说明）。
                SessionDatabase.get().rebuildBlobRefs(sessionId);
            }
        } catch (Exception ignored) {
            // 见 appendEvent 的说明。
        }
    }

    private void pruneSnapshots(SQLiteDatabase db) {
        db.execSQL("DELETE FROM " + SessionDatabase.TABLE_SNAPSHOTS
                + " WHERE " + C_SESSION_ID + " = ? AND " + C_SEQ + " NOT IN ("
                + "  SELECT " + C_SEQ + " FROM " + SessionDatabase.TABLE_SNAPSHOTS
                + "  WHERE " + C_SESSION_ID + " = ? ORDER BY " + C_SEQ + " DESC LIMIT "
                + MAX_RETAINED_SNAPSHOTS + ")", new Object[]{sessionId, sessionId});
    }

    /** 入队一行；同时决定要不要立刻提交。 */
    private void enqueue(PendingRow row, String titleCandidate) {
        pending.add(row);
        pendingBytes += row.weigh();
        if (pendingSince == 0L) pendingSince = System.currentTimeMillis();
        if (!RowType.METADATA.equals(row.rowType)) pendingTouchesActivity = true;
        if (pendingTitleCandidate.isEmpty() && !isBlank(titleCandidate)
                && !isInternalMarkerText(titleCandidate.trim())) {
            pendingTitleCandidate = titleCandidate.trim();
        }

        boolean critical = row.message && ("human".equals(row.origin) || isBlank(row.origin));
        if (critical || pendingBytes >= PENDING_FLUSH_BYTES
                || System.currentTimeMillis() - pendingSince >= PENDING_FLUSH_MS) {
            flush();
        }
    }

    /**
     * 把攒下的行整批提交。
     *
     * <p>顺序是刻意的：**先算出全部 Blob 与编码**（会写文件系统），**再开事务**。
     * 反过来的话，一个长事务会一直持着写锁去写文件，而并发读取要等到它结束。
     */
    private void commitPending() {
        if (pending.isEmpty()) return;
        ArrayList<PendingRow> batch = new ArrayList<>(pending);
        pending.clear();
        pendingBytes = 0L;
        pendingSince = 0L;
        boolean touchesActivity = pendingTouchesActivity;
        pendingTouchesActivity = false;
        String titleCandidate = pendingTitleCandidate;
        pendingTitleCandidate = "";

        ArrayList<PreparedRow> prepared = new ArrayList<>(batch.size());
        try {
            long seq = nextSeq;
            int appendedMessages = 0;
            for (PendingRow row : batch) {
                PreparedRow item = prepare(row, ++seq);
                if (row.message) appendedMessages++;
                prepared.add(item);
            }

            synchronized (SessionDatabase.lock()) {
                SQLiteDatabase db = SessionDatabase.get().db();
                db.beginTransaction();
                try {
                    for (PreparedRow item : prepared) insertRow(db, item);
                    long now = System.currentTimeMillis();
                    long count = messageCount + appendedMessages;
                    ContentValues values = new ContentValues();
                    values.put(C_NEXT_SEQ, seq);
                    if (touchesActivity) values.put(C_UPDATED_AT, now);
                    values.put(C_MESSAGE_COUNT, count);
                    if (!titleCandidate.isEmpty()) values.put(C_TITLE, compactTitle(titleCandidate));
                    if (!workflowId.isEmpty()) values.put(C_WORKFLOW_ID, workflowId);
                    db.update(SessionDatabase.TABLE_SESSIONS, values, C_ID + " = ?",
                            new String[]{sessionId});
                    db.setTransactionSuccessful();
                    nextSeq = seq;
                    messageCount = count;
                } finally {
                    db.endTransaction();
                }
            }
        } catch (Exception ignored) {
            // 编码或提交失败：这批行丢失，但下一次写入仍然继续。
            // 会话是「用户的对话记录」，不是账本 —— 因一次磁盘错误把整个回合带崩更糟。
            logIfOom(ignored);
        }
    }

    /** 把一条待提交行算成可入库的形态：编码 + 必要时外置大内容。 */
    private PreparedRow prepare(PendingRow row, long seq) throws IOException {
        PreparedRow item = new PreparedRow();
        item.seq = seq;
        item.timestamp = row.timestamp;
        if (row.message) {
            item.kind = KIND_MESSAGE;
            item.rowType = RowType.MESSAGE;
            item.role = orEmpty(row.role);
            item.messageId = orEmpty(row.messageId);
            item.turnId = orEmpty(row.turnId);
            item.origin = orEmpty(row.origin);
            JSONArray content = row.content == null ? new JSONArray() : row.content;
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null) continue;
                item.blocks.add(prepareBlock(sessionId, seq, i, block));
            }
        } else {
            item.kind = KIND_EVENT;
            item.rowType = orEmpty(row.rowType);
            byte[] encoded = BinaryValueCodec.encodeObject(row.payload);
            if (encoded.length >= BlobStore.THRESHOLD_BYTES) {
                String hash = BlobStore.shared().put(encoded);
                item.payloadBlobRef = hash;
                item.payloadBlobSize = encoded.length;
                SessionDatabase.get().registerBlob(hash, encoded.length);
            } else {
                item.payload = encoded;
            }
            // config / plan_* 事件里带着 workflow id：顺手记到会话行上，
            // 之后恢复会话就不必再回放整段历史去找它。
            if (row.payload != null) {
                String recorded = row.payload.optString(Field.WORKFLOW_ID, "");
                if (!recorded.isEmpty()) workflowId = recorded;
            }
        }
        return item;
    }

    /**
     * 把一个内容块算成一行。
     *
     * <p>{@code data} 存的是**整块**的二进制编码，它是重建的唯一依据；
     * {@code text_value} / {@code tool_use_id} / {@code tool_name} / {@code is_error}
     * / {@code media_type} 是从块里抽出来给 {@code sqlite3} 直接查的**派生副本**
     * （例如「哪个会话跑过这个工具」），不参与重建。块超过 8 KB 时整块外置到 Blob，
     * 这些派生列此时留空 —— 否则同一份内容要存两遍。
     */
    private static ContentValues prepareBlock(String sessionId, long messageSeq, int ordinal,
                                              JSONObject block) throws IOException {
        ContentValues values = new ContentValues();
        values.put(C_SESSION_ID, sessionId);
        values.put(C_MESSAGE_SEQ, messageSeq);
        values.put(C_ORDINAL, ordinal);
        String type = block.optString(Field.TYPE, "");
        values.put(C_KIND, kindOf(block));

        // 图片的**派生列必须先写**，而且它与其它块不同：图片块几乎一定超过门槛
        // （base64 随便就是几十 KB），会被整块外置。而按窗口读历史时我们**故意不解码
        // 图片块**（见 loadBlocks 的 includeImageData），那时"这是不是一张图、什么
        // 类型、叫什么名字"就只能靠这几列 —— 落在外置分支后面就等于永远读不到。
        //
        // 这与"外置时不重复存内容"的原则不冲突：media_type 与文件名是十几字节的元数据，
        // 不是那份 base64 本体。
        if (Block.IMAGE.equals(type)) {
            JSONObject source = block.optJSONObject(Field.SOURCE);
            if (source != null) values.put(C_MEDIA_TYPE, source.optString(Field.MEDIA_TYPE, ""));
            values.put(C_TEXT_VALUE, block.optString(Field.NAME, ""));
        }

        byte[] encoded = BinaryValueCodec.encodeObject(block);
        if (encoded.length >= BlobStore.THRESHOLD_BYTES) {
            String hash = BlobStore.shared().put(encoded);
            values.put(C_BLOB_REF, hash);
            values.put(C_BLOB_SIZE, encoded.length);
            SessionDatabase.get().registerBlob(hash, encoded.length);
            return values;
        }

        values.put(C_DATA, encoded);
        if (Block.TEXT.equals(type)) values.put(C_TEXT_VALUE, block.optString(Field.TEXT, ""));
        else if (Block.THINKING.equals(type) || Block.REASONING.equals(type)) {
            values.put(C_TEXT_VALUE, block.optString(Field.THINKING, ""));
        } else if (Block.TOOL_USE.equals(type)) {
            values.put(C_TOOL_USE_ID, block.optString(Field.ID, ""));
            values.put(C_TOOL_NAME, block.optString(Field.NAME, ""));
        } else if (Block.TOOL_RESULT.equals(type)) {
            values.put(C_TOOL_USE_ID, block.optString(Field.TOOL_USE_ID, ""));
            values.put(C_IS_ERROR, block.optBoolean(Field.IS_ERROR, false) ? 1 : 0);
            Object content = block.opt(Field.CONTENT);
            if (content instanceof String) values.put(C_TEXT_VALUE, (String) content);
        }
        return values;
    }

    private void insertRow(SQLiteDatabase db, PreparedRow item) {
        ContentValues values = new ContentValues();
        values.put(C_SESSION_ID, sessionId);
        values.put(C_SEQ, item.seq);
        values.put(C_KIND, item.kind);
        values.put(C_ROW_TYPE, item.rowType);
        values.put(C_TIMESTAMP, item.timestamp);
        values.put(C_ROLE, item.role);
        values.put(C_MESSAGE_ID, item.messageId);
        values.put(C_TURN_ID, item.turnId);
        values.put(C_ORIGIN, item.origin);
        if (item.payloadBlobRef.isEmpty()) values.put(C_PAYLOAD, item.payload);
        else {
            values.put(C_PAYLOAD_BLOB_REF, item.payloadBlobRef);
            values.put(C_PAYLOAD_BLOB_SIZE, item.payloadBlobSize);
        }
        db.insertWithOnConflict(SessionDatabase.TABLE_EVENTS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        // 归属必须与引用写进**同一个事务**：分开写的话，中途失败会留下
        // "有引用、没归属"的行 —— 那个 Blob 会因为"没人认领"被回收掉（见 pruneOrphanBlobs）。
        SessionDatabase.get().addBlobRef(db, sessionId, item.payloadBlobRef);
        for (ContentValues block : item.blocks) {
            db.insertWithOnConflict(SessionDatabase.TABLE_BLOCKS, null, block,
                    SQLiteDatabase.CONFLICT_REPLACE);
            SessionDatabase.get().addBlobRef(db, sessionId, block.getAsString(C_BLOB_REF));
        }
    }

    // ------------------------------------------------------------ 读取

    /** 宽容读取：返回能解出来的行。损坏行被跳过。 */
    public static JSONArray readRows(String sessionId) {
        return rows(sessionId, -1L);
    }

    /**
     * 重建给提供方的消息列表。
     *
     * <p>规则：从**最新快照**起步（它记着自己包含了到哪个 seq 为止），
     * 之后的 {@code message} 行再往后续接。这样「压缩之后又聊了几轮」能被正确还原，
     * 而压缩之前的完整对话仍然留在库里给界面用。
     *
     * <h3>这里刻意一次深拷贝都不做</h3>
     *
     * <p>本方法走完之后，返回值是**唯一**持有那棵对象树的地方：快照是
     * {@link #latestSnapshot} 里刚 {@code BinaryValueCodec.decodeArray} 出来的，
     * 消息的块是 {@link #loadBlocks} 里刚 {@code decodeBlock} 出来的，
     * 两者都是本次调用新建的,没有第二个持有者。
     *
     * <p>早先这三处各写了一发 {@code copy()}（它的实现是 {@code toString()} + 重新解析），
     * 于是加载一条长会话时**同时存在两份完整副本**，还要为每条消息把它自己的内容块
     * 序列化再解析一次。这是"打开长会话"那条路径上最大的一笔瞬时内存，
     * 而且它是可以被证明多余的。
     *
     * <p>{@link #mergeAdjacentRoles} 同样改成**就地**合并，理由相同。
     */
    public static JSONArray loadMessages(String sessionId) {
        JSONArray conversation = new JSONArray();
        long afterSeq = -1L;
        try {
            JSONObject snapshot = latestSnapshot(sessionId);
            if (snapshot != null) {
                long seq = snapshot.optLong(C_SEQ, -1L);
                JSONArray messages = snapshot.optJSONArray(Field.MESSAGES);
                if (seq >= 0L && messages != null) {
                    afterSeq = seq;
                    conversation = messages;
                }
            }
        } catch (Exception ignored) {
            afterSeq = -1L;
            conversation = new JSONArray();
            logIfOom(ignored);
        }

        for (Row row : loadRows(sessionId, afterSeq, true)) {
            JSONObject value = row.json;
            if (value == null) continue;
            String type = value.optString(Field.TYPE, "");
            if (RowType.SNAPSHOT.equals(type)) {
                JSONObject payload = value.optJSONObject(Field.PAYLOAD);
                JSONArray snapshot = payload == null ? null : payload.optJSONArray(Field.MESSAGES);
                if (snapshot != null) conversation = snapshot;
                continue;
            }
            if (!RowType.MESSAGE.equals(type)) continue;
            JSONArray content = value.optJSONArray(Field.CONTENT);
            if (content == null) continue;
            try {
                conversation.put(new JSONObject()
                        .put(Field.ROLE, value.optString(Field.ROLE, Role.USER))
                        .put(Field.CONTENT, content));
            } catch (Exception ignored) {
                // 单条拼不进去就跳过它，其余照常。
            }
        }
        mergeAdjacentRoles(conversation);
        return conversation;
    }

    private static JSONArray rows(String sessionId, long afterSeq) {
        JSONArray out = new JSONArray();
        for (Row row : loadRows(sessionId, afterSeq, true)) {
            if (row.json != null) out.put(row.json);
        }
        return out;
    }

    /**
     * 一段「按消息条数开窗」的会话行，供界面分页加载。
     *
     * <p>为什么界面不能一次读完：一次长会话的全部内容块（工具输出、diff、图片）
     * 是几十 MB 量级的，整份读进来再建界面模型，正是「打开一万条的长会话直接
     * `OutOfMemoryError`」那条路径。窗口化之后常驻的只有最近若干条。
     */
    public static final class RowWindow {
        /** 与 {@link #readRows} 同形状的行，按 seq 升序。 */
        public final JSONArray rows;
        /** 本窗口**最早一行**的 seq；空窗口为 -1。翻上一页时把它当上界传回来。 */
        public final long oldestSeq;
        /** 更早还有没有内容。 */
        public final boolean hasMore;

        RowWindow(JSONArray rows, long oldestSeq, boolean hasMore) {
            this.rows = rows;
            this.oldestSeq = oldestSeq;
            this.hasMore = hasMore;
        }
    }

    /**
     * 读最近 {@code maxMessages} 条消息那一段（含它们之间的事件行）。
     *
     * <p>窗口的**下界**落在一个消息行上，并会往前挪到「不会把一次工具调用切成两半」
     * 的位置（见 {@link #alignToTurnStart}）。上界之后的行不读。
     *
     * @param beforeSeq   只读 seq 严格小于它的行；{@code <= 0} 表示从最新开始
     * @param maxMessages 这一次最多带上多少条消息
     */
    public static RowWindow readRowsWindow(String sessionId, long beforeSeq, int maxMessages) {
        if (isBlank(sessionId) || maxMessages <= 0) {
            return new RowWindow(new JSONArray(), -1L, false);
        }
        long upper = beforeSeq <= 0L ? Long.MAX_VALUE : beforeSeq;
        long floor = -1L;
        try {
            SQLiteDatabase db = SessionDatabase.get().db();
            // 多取一条：能取到第 maxMessages+1 条，就说明更早还有内容。
            long[] seqs = new long[maxMessages + 1];
            int found = 0;
            Cursor cursor = null;
            try {
                cursor = db.query(SessionDatabase.TABLE_EVENTS, new String[]{C_SEQ},
                        C_SESSION_ID + " = ? AND " + C_KIND + " = ? AND " + C_SEQ + " < ?",
                        new String[]{sessionId, KIND_MESSAGE, String.valueOf(upper)},
                        null, null, C_SEQ + " DESC", String.valueOf(maxMessages + 1));
                while (found < seqs.length && cursor.moveToNext()) seqs[found++] = cursor.getLong(0);
            } finally {
                if (cursor != null) cursor.close();
            }
            if (found > maxMessages) floor = alignToTurnStart(db, sessionId, seqs[maxMessages - 1]);
        } catch (Exception ignored) {
            logIfOom(ignored);
        }

        JSONArray out = new JSONArray();
        long oldest = -1L;
        // 图片只留占位：窗口化读历史时，把整窗口的图片 base64 常驻等于白读了一趟
        // （见 loadBlocks）。界面画到哪一张就取哪一张。
        for (Row row : loadRows(sessionId, floor < 0L ? -1L : floor - 1L, upper, true, null, false)) {
            if (row.json == null) continue;
            if (oldest < 0L) oldest = row.seq;
            out.put(row.json);
        }
        if (oldest < 0L) return new RowWindow(out, -1L, false);
        return new RowWindow(out, oldest, existsMessageBelow(sessionId, oldest));
    }

    /**
     * 把窗口下界挪到一个「不会把一次工具调用切成两半」的位置。
     *
     * <p>一条工具结果行（`role=user`、带 `tool_result` 块）与它对应的 `tool_use` 块
     * 通常在**两条不同的消息**里。若窗口正好从结果行开始，上面那条 `tool_use` 落在窗口外 ——
     * 结果行找不到自己的工具卡，按 `SessionReader` 的规则被静默丢弃，于是这一整条
     * 工具的输出在界面上凭空消失。
     *
     * <p>所以只要下界落在带工具结果的消息上，就往前再退一条消息。退到 `tool_use`
     * 所在的那条助手消息就会停 —— 那时工具卡与结果都在窗口里。
     *
     * <p>循环有硬上限：数据异常时宁可窗口大一点，也不能在这里停住。
     */
    private static long alignToTurnStart(SQLiteDatabase db, String sessionId, long floor) {
        long current = floor;
        for (int guard = 0; guard < 64; guard++) {
            if (!carriesToolResult(db, sessionId, current)) return current;
            long previous = queryMessageSeq(db, sessionId, current);
            if (previous < 0L) return current;
            current = previous;
        }
        return current;
    }

    /** 这条消息行的内容里有没有工具结果块。 */
    private static boolean carriesToolResult(SQLiteDatabase db, String sessionId, long seq) {
        Cursor cursor = null;
        try {
            cursor = db.query(SessionDatabase.TABLE_BLOCKS, new String[]{C_ORDINAL},
                    C_SESSION_ID + " = ? AND " + C_MESSAGE_SEQ + " = ? AND " + C_KIND + " = ?",
                    new String[]{sessionId, String.valueOf(seq), String.valueOf(BLOCK_TOOL_RESULT)},
                    null, null, null, "1");
            return cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 是否存在严格早于 {@code seq} 的消息行。 */
    private static boolean existsMessageBelow(String sessionId, long seq) {
        try {
            return queryMessageSeq(SessionDatabase.get().db(), sessionId, seq) >= 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 严格早于 {@code seq} 的最近一条消息行的 seq；没有则 -1。 */
    private static long queryMessageSeq(SQLiteDatabase db, String sessionId, long seq) {
        Cursor cursor = null;
        try {
            cursor = db.query(SessionDatabase.TABLE_EVENTS, new String[]{C_SEQ},
                    C_SESSION_ID + " = ? AND " + C_KIND + " = ? AND " + C_SEQ + " < ?",
                    new String[]{sessionId, KIND_MESSAGE, String.valueOf(seq)},
                    null, null, C_SEQ + " DESC", "1");
            if (!cursor.moveToFirst()) return -1L;
            return cursor.getLong(0);
        } catch (Exception ignored) {
            return -1L;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 只读任务快照事件（不含内容块）。
     *
     * <p>任务清单取的是**最后一条** `task_snapshot`，而分页窗口只覆盖最近若干条消息 ——
     * 一条很久没动过任务的会话里，最后那条快照会落在窗口之外。所以单独读一遍：
     * 它只是一个小 JSON，不付内容块的代价。
     */
    public static JSONArray readTaskSnapshotRows(String sessionId) {
        JSONArray out = new JSONArray();
        if (isBlank(sessionId)) return out;
        for (Row row : loadRows(sessionId, -1L, Long.MAX_VALUE, false, RowType.TASK_SNAPSHOT, true)) {
            if (row.json != null) out.put(row.json);
        }
        return out;
    }

    /**
     * 把一次会话的行按 seq 升序读出来。
     *
     * <p>先读事件行，再一次性读出内容块并按 {@code message_seq} 分组 ——
     * 不这样做就是「每条消息一次查询」，一次长会话恢复会变成上千次查询。
     *
     * <p><b>{@code withBlocks} 不能省。</b>内容块是整个会话里最重的那部分
     * （工具输出、图片 base64 都在这），而只看「有哪些事件」的调用方
     * （计划状态、配置绑定、workflow id）根本用不到它。旧写法无条件加载全部块，
     * 于是打开一次会话要按好几遍整份内容付费 —— 在长会话上是直接撑爆堆的量级。
     *
     * @param withBlocks 是否读取并挂上内容块；为 false 时 message 行的 content 是空数组
     */
    private static List<Row> loadRows(String sessionId, long afterSeq, boolean withBlocks) {
        return loadRows(sessionId, afterSeq, Long.MAX_VALUE, withBlocks, null, true);
    }

    /**
     * 按 seq 区间读会话行。
     *
     * @param afterSeq   下界（不含）；负数表示从第一行开始
     * @param beforeSeq  上界（不含）；{@link Long#MAX_VALUE} 表示到最后一行
     * @param withBlocks 是否读取并挂上内容块
     * @param rowType    只要这一种行类型（事件行）；null 表示不筛
     * @param withImageData 内容块里的图片是否连字节一起读；为 false 时只留占位
     *                   （见 {@link #loadBlocks}），供窗口化读历史用。
     */
    private static List<Row> loadRows(String sessionId, long afterSeq, long beforeSeq,
                                      boolean withBlocks, String rowType, boolean withImageData) {
        return loadRows(sessionId, afterSeq, beforeSeq, withBlocks, rowType, withImageData, 0);
    }

    /**
     * 按 seq 区间读会话行。
     *
     * @param afterSeq   下界（不含）；负数表示从第一行开始
     * @param beforeSeq  上界（不含）；{@link Long#MAX_VALUE} 表示到最后一行
     * @param withBlocks 是否读取并挂上内容块
     * @param rowType    只要这一种行类型（事件行）；null 表示不筛
     * @param withImageData 内容块里的图片是否连字节一起读；为 false 时只留占位
     *                   （见 {@link #loadBlocks}），供窗口化读历史用。
     * @param limit      最多读多少行；{@code <= 0} 表示不限。导出用它**分批**读，
     *                   否则一个长会话会把整条历史同时装进内存 ——
     *                   与"窗口化读历史"要避免的是同一件事。
     */
    private static List<Row> loadRows(String sessionId, long afterSeq, long beforeSeq,
                                      boolean withBlocks, String rowType, boolean withImageData,
                                      int limit) {
        ArrayList<Row> rows = new ArrayList<>();
        if (isBlank(sessionId)) return rows;
        SQLiteDatabase db;
        try {
            db = SessionDatabase.get().db();
        } catch (Exception unavailable) {
            return rows;
        }
        boolean boundedBelow = afterSeq >= 0L;
        boolean boundedAbove = beforeSeq != Long.MAX_VALUE;
        StringBuilder where = new StringBuilder(C_SESSION_ID).append(" = ?");
        if (boundedBelow) where.append(" AND ").append(C_SEQ).append(" > ?");
        if (boundedAbove) where.append(" AND ").append(C_SEQ).append(" < ?");
        if (rowType != null) where.append(" AND ").append(C_ROW_TYPE).append(" = ?");
        ArrayList<String> argList = new ArrayList<>(4);
        argList.add(sessionId);
        if (boundedBelow) argList.add(String.valueOf(afterSeq));
        if (boundedAbove) argList.add(String.valueOf(beforeSeq));
        if (rowType != null) argList.add(rowType);
        String[] args = argList.toArray(new String[0]);

        Map<Long, JSONArray> blocks = withBlocks
                ? loadBlocks(sessionId, afterSeq, beforeSeq, withImageData)
                : java.util.Collections.<Long, JSONArray>emptyMap();
        Cursor cursor = null;
        try {
            cursor = db.query(SessionDatabase.TABLE_EVENTS,
                    new String[]{C_SEQ, C_KIND, C_ROW_TYPE, C_TIMESTAMP, C_ROLE, C_MESSAGE_ID,
                            C_TURN_ID, C_ORIGIN, C_PAYLOAD, C_PAYLOAD_BLOB_REF},
                    where.toString(), args, null, null, C_SEQ + " ASC",
                    limit > 0 ? String.valueOf(limit) : null);
            while (cursor.moveToNext()) {
                long seq = cursor.getLong(0);
                String kind = cursor.getString(1);
                JSONObject json = new JSONObject();
                // 行的 seq 也带出去：界面用它做**分页上界**，并让恢复出来的条目 id
                // 由 seq 决定（翻页时前面的条目 id 不变，列表位置也就不会跳）。
                json.put(C_SEQ, seq);
                if (KIND_MESSAGE.equals(kind)) {
                    json.put(Field.TYPE, RowType.MESSAGE);
                    json.put(Field.ROLE, orEmpty(cursor.getString(4)));
                    json.put(Field.CONTENT, blocks.containsKey(seq) ? blocks.get(seq) : new JSONArray());
                    putIfNotEmpty(json, Field.MESSAGE_ID, cursor.getString(5));
                    putIfNotEmpty(json, Field.TURN_ID, cursor.getString(6));
                    putIfNotEmpty(json, Field.ORIGIN, cursor.getString(7));
                } else {
                    json.put(Field.TYPE, orEmpty(cursor.getString(2)));
                    JSONObject payload = decodePayload(cursor.getBlob(8), cursor.getString(9));
                    json.put(Field.PAYLOAD, payload == null ? new JSONObject() : payload);
                }
                json.put(Field.TIMESTAMP, cursor.getLong(3));
                rows.add(new Row(seq, json));
            }
        } catch (Exception ignored) {
            // 读不出来的部分按「没有这些行」处理；界面不该因为一次查询失败而崩。
            logIfOom(ignored);
        } finally {
            if (cursor != null) cursor.close();
        }
        return rows;
    }

    /**
     * 分批读会话行（含内容块），供**导出**用。
     *
     * <p>与 {@link #readRowsWindow} 的区别：那个按"最近 N 条消息"开窗（界面用），
     * 这个按"seq 从哪往后、最多几行"分段（导出用）—— 导出要的是**全部**内容，
     * 只是不能一次装进内存。
     *
     * @param afterSeq 只读 seq 严格大于它的；负数表示从第一行开始
     * @param limit    最多几行；必须为正
     * @return 按 seq 升序的行；空表示读完了
     */
    static JSONArray readRowChunk(String sessionId, long afterSeq, int limit) {
        JSONArray out = new JSONArray();
        for (Row row : loadRows(sessionId, afterSeq, Long.MAX_VALUE, true, null, true,
                Math.max(1, limit))) {
            if (row.json != null) out.put(row.json);
        }
        return out;
    }

    /**
     * 读一个会话的全部上下文快照（含内容），供导出用。
     *
     * <p>快照每个会话只留最近 {@link #MAX_RETAINED_SNAPSHOTS} 个，所以这里不做分批。
     */
    static JSONArray readSnapshots(String sessionId) {
        JSONArray out = new JSONArray();
        if (isBlank(sessionId)) return out;
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SNAPSHOTS,
                    new String[]{C_SEQ, C_CREATED_AT, "messages", C_BLOB_REF},
                    C_SESSION_ID + " = ?", new String[]{sessionId}, null, null, C_SEQ + " ASC");
            while (cursor.moveToNext()) {
                byte[] inline = cursor.getBlob(2);
                String blobRef = cursor.getString(3);
                byte[] data = inline;
                if (data == null || data.length == 0) {
                    if (blobRef == null || blobRef.isEmpty()) continue;
                    data = BlobStore.shared().get(blobRef);
                }
                JSONArray messages = BinaryValueCodec.decodeArray(data);
                out.put(new JSONObject()
                        .put(C_SEQ, cursor.getLong(0))
                        .put(C_CREATED_AT, cursor.getLong(1))
                        .put(Field.MESSAGES, messages));
            }
        } catch (Exception broken) {
            // 快照读不出来就不导出它：它是派生物（正文行才是权威），少一个不影响可读性。
            logIfOom(broken);
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    /** 会话元数据行，供导出写归档头；不存在时返回 null。 */
    static JSONObject readSessionHeader(String sessionId) {
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SESSIONS,
                    new String[]{C_ID, C_PROJECT, C_TITLE, C_NOTE, C_TITLE_OVERRIDE,
                            C_WORKFLOW_ID, C_CREATED_AT, C_UPDATED_AT, C_MESSAGE_COUNT, C_NEXT_SEQ},
                    C_ID + " = ?", new String[]{sessionId}, null, null, null, "1");
            if (!cursor.moveToFirst()) return null;
            return new JSONObject()
                    .put(C_ID, cursor.getString(0))
                    .put(C_PROJECT, cursor.getString(1))
                    .put(C_TITLE, cursor.getString(2))
                    .put(C_NOTE, cursor.getString(3))
                    .put(C_TITLE_OVERRIDE, cursor.getString(4))
                    .put(C_WORKFLOW_ID, cursor.getString(5))
                    .put(C_CREATED_AT, cursor.getLong(6))
                    .put(C_UPDATED_AT, cursor.getLong(7))
                    .put(C_MESSAGE_COUNT, cursor.getLong(8))
                    .put(C_NEXT_SEQ, cursor.getLong(9));
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void putIfNotEmpty(JSONObject json, String key, String value) throws Exception {
        // 空串不写：写空串不只是让数据变多，还会让「这个字段有没有值」没法用存在性判断。
        if (!isBlank(value)) json.put(key, value);
    }

    private static Map<Long, JSONArray> loadBlocks(String sessionId, long afterSeq, long beforeSeq,
                                                  boolean withImageData) {
        HashMap<Long, JSONArray> grouped = new HashMap<>();
        Cursor cursor = null;
        boolean boundedBelow = afterSeq >= 0L;
        boolean boundedAbove = beforeSeq != Long.MAX_VALUE;
        StringBuilder where = new StringBuilder(C_SESSION_ID).append(" = ?");
        if (boundedBelow) where.append(" AND ").append(C_MESSAGE_SEQ).append(" > ?");
        if (boundedAbove) where.append(" AND ").append(C_MESSAGE_SEQ).append(" < ?");
        ArrayList<String> argList = new ArrayList<>(3);
        argList.add(sessionId);
        if (boundedBelow) argList.add(String.valueOf(afterSeq));
        if (boundedAbove) argList.add(String.valueOf(beforeSeq));
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_BLOCKS,
                    new String[]{C_MESSAGE_SEQ, C_DATA, C_BLOB_REF, C_KIND, C_MEDIA_TYPE, C_TEXT_VALUE},
                    where.toString(), argList.toArray(new String[0]),
                    null, null, C_MESSAGE_SEQ + " ASC, " + C_ORDINAL + " ASC");
            while (cursor.moveToNext()) {
                long seq = cursor.getLong(0);
                JSONObject block;
                if (!withImageData && cursor.getInt(3) == BLOCK_IMAGE) {
                    // 图片块**不解码**：它的 base64 是整条会话里最重的一项，而屏幕上一次
                    // 只有几张可见。这里只留一个"这是什么、叫什么"的占位，字节等界面真要
                    // 画那一张时再按 (session, seq, ordinal) 取（见 readImageData）。
                    //
                    // 判据必须落在 `kind` 上：它是写行时就确定的，与块有没有被外置无关。
                    block = placeholderImage(cursor.getString(4), cursor.getString(5));
                } else {
                    block = decodeBlock(cursor.getBlob(1), cursor.getString(2));
                }
                if (block == null) continue;
                JSONArray array = grouped.get(seq);
                if (array == null) {
                    array = new JSONArray();
                    grouped.put(seq, array);
                }
                array.put(block);
            }
        } catch (Exception ignored) {
            // 块读不出来时那几条消息会显示成空内容，而不是让整个列表失败。
            logIfOom(ignored);
        } finally {
            if (cursor != null) cursor.close();
        }
        return grouped;
    }

    /** 图片块的占位：形状与真块一致，只是没有字节。 */
    private static JSONObject placeholderImage(String mediaType, String name) throws Exception {
        JSONObject source = new JSONObject();
        source.put(Field.TYPE, "base64");
        source.put(Field.MEDIA_TYPE, mediaType == null ? "" : mediaType);
        source.put(Field.DATA, "");
        JSONObject block = new JSONObject();
        block.put(Field.TYPE, Block.IMAGE);
        block.put(Field.SOURCE, source);
        block.put(Field.NAME, name == null ? "" : name);
        return block;
    }

    /**
     * 取一张图的实际字节（base64），按主键定位。
     *
     * <p>这是窗口化读历史的配套：列表里那份图只是占位（见 {@link #placeholderImage}），
     * 界面在真正要画那一张时调这里。
     *
     * <p>读不出来（行被删、blob 丢了、块损坏）返回空串 —— 界面按"解码失败"显示文件名，
     * 而不是崩掉或者留一块空白。
     *
     * @param messageSeq 图片所在**消息**的 seq
     * @param ordinal    图片块在那条消息的内容块里的下标（写入时就是这么记的）
     */
    public static String readImageData(String sessionId, long messageSeq, int ordinal) {
        if (isBlank(sessionId) || messageSeq < 0 || ordinal < 0) return "";
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_BLOCKS,
                    new String[]{C_DATA, C_BLOB_REF},
                    C_SESSION_ID + " = ? AND " + C_MESSAGE_SEQ + " = ? AND " + C_ORDINAL + " = ?",
                    new String[]{sessionId, String.valueOf(messageSeq), String.valueOf(ordinal)},
                    null, null, null, "1");
            if (!cursor.moveToFirst()) return "";
            JSONObject block = decodeBlock(cursor.getBlob(0), cursor.getString(1));
            if (block == null) return "";
            JSONObject source = block.optJSONObject(Field.SOURCE);
            return source == null ? "" : source.optString(Field.DATA, "");
        } catch (Exception broken) {
            logIfOom(broken);
            return "";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 解码一个内容块；整块外置过时从 Blob 取回。 */
    private static JSONObject decodeBlock(byte[] inline, String blobRef) {
        try {
            byte[] data = inline;
            if (data == null || data.length == 0) {
                if (blobRef == null || blobRef.isEmpty()) return null;
                data = BlobStore.shared().get(blobRef);
            }
            return BinaryValueCodec.decodeObject(data);
        } catch (Exception broken) {
            return null;
        }
    }

    /** 解码一个事件的 payload；大 payload 从 Blob 取回。 */
    private static JSONObject decodePayload(byte[] inline, String blobRef) {
        try {
            byte[] data = inline;
            if (data == null || data.length == 0) {
                if (blobRef == null || blobRef.isEmpty()) return new JSONObject();
                data = BlobStore.shared().get(blobRef);
            }
            return BinaryValueCodec.decodeObject(data);
        } catch (Exception broken) {
            return new JSONObject();
        }
    }

    private static JSONObject latestSnapshot(String sessionId) {
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SNAPSHOTS,
                    new String[]{C_SEQ, "messages", C_BLOB_REF},
                    C_SESSION_ID + " = ?", new String[]{sessionId}, null, null, C_SEQ + " DESC", "1");
            if (!cursor.moveToFirst()) return null;
            long seq = cursor.getLong(0);
            byte[] inline = cursor.getBlob(1);
            String blobRef = cursor.getString(2);
            byte[] data = inline;
            if (data == null || data.length == 0) {
                if (blobRef == null || blobRef.isEmpty()) return null;
                data = BlobStore.shared().get(blobRef);
            }
            JSONArray messages = BinaryValueCodec.decodeArray(data);
            return new JSONObject().put(C_SEQ, seq).put(Field.MESSAGES, messages);
        } catch (Exception broken) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 把相邻的同角色消息合并成一条。
     *
     * <p>提供方（OpenAI / Anthropic）要求角色交替出现；而我们的历史里
     * 「人类发言」与「工具结果」都是 user 角色，不合并就会被拒。
     *
     * <p>合并方式是<b>直接往后拼接内容块</b>，不重排。这一点很重要：
     * {@code tool_use} 与 {@code tool_result} 的配对靠的是相对顺序，
     * 一旦重排（例如按块类型分组），提供方就会报「找不到对应的工具调用」。
     */
    /**
     * 把相邻的同角色消息合并成一条，**就地**写回传入的数组。
     *
     * <p>提供方（OpenAI / Anthropic）要求角色交替出现；而我们的历史里
     * 「人类发言」与「工具结果」都是 user 角色，不合并就会被拒。
     *
     * <p>合并方式是<b>直接往后拼接内容块</b>，不重排。这一点很重要：
     * {@code tool_use} 与 {@code tool_result} 的配对靠的是相对顺序，
     * 一旦重排（例如按块类型分组），提供方就会报「找不到对应的工具调用」。
     *
     * <h3>为什么是就地（而不是建一个新数组）</h3>
     *
     * <p>建新数组意味着加载一条长会话时**同时存在两份完整的消息树** ——
     * 而这一份只是把相邻的几条并起来，blocks 本身一个字节都不用改。
     * 就地做的代价是"删除尾部多余元素"，比重建便宜一个数量级。
     *
     * <p>就地还有一个前提：调用方传进来的必须是**本次新建**的数组，且块对象没有
     * 第二个持有者。{@link #loadMessages} 满足这一点（见那里的说明）。
     */
    private static void mergeAdjacentRoles(JSONArray conversation) {
        if (conversation == null) return;
        int write = 0;
        for (int read = 0; read < conversation.length(); read++) {
            JSONObject message = conversation.optJSONObject(read);
            if (message == null) continue;
            JSONArray content = message.optJSONArray(Field.CONTENT);
            if (content == null) continue;
            String role = message.optString(Field.ROLE, Role.USER);
            try {
                JSONObject tail = write == 0 ? null : conversation.optJSONObject(write - 1);
                if (tail != null && role.equals(tail.optString(Field.ROLE))) {
                    appendBlocks(tail.optJSONArray(Field.CONTENT), content);
                } else {
                    if (write != read) conversation.put(write, message);
                    write++;
                }
            } catch (Exception ignored) {
                // 单条合并失败就跳过它。
            }
        }
        // 从尾部删掉被合并掉的那些位置。倒着删，避免下标整体前移。
        for (int index = conversation.length() - 1; index >= write; index--) {
            conversation.remove(index);
        }
    }

    /** 把 {@code blocks} 原样接到 {@code target} 后面（见 mergeAdjacentRoles 的说明）。 */
    private static void appendBlocks(JSONArray target, JSONArray blocks) {
        if (target == null || blocks == null) return;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null) target.put(block);
        }
    }

    // ------------------------------------------------------------ 单条工具结果

    /**
     * 按 {@code tool_use_id} 读回一条工具结果的完整文本。
     *
     * <p>为什么需要它：界面渲染历史时，一条工具输出可能有几 MB（`Bash` 的完整 stdout、
     * `Read` 的文件正文），而**一次会话里可能有过几百条**。全部塞进 {@code transcript}
     * 就等于把整个会话的工具输出常驻内存 —— 长会话上这是压垮堆的主因之一。
     *
     * <p>所以列表里只留一份有界预览（见 {@code SessionReader}），用户真正展开某一条时
     * 才走这个查询取全文。它命中的是 {@code content_blocks(session_id, tool_use_id)}
     * 索引，只读一行；整块外置过大内容时再读那一个 Blob。
     *
     * @return 工具结果文本；找不到时返回空串（不是 null：调用方一律按「没有结果」处理）
     */
    public static String readToolResult(String sessionId, String toolUseId) {
        if (isBlank(sessionId) || isBlank(toolUseId)) return "";
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_BLOCKS,
                    new String[]{C_DATA, C_BLOB_REF},
                    C_SESSION_ID + " = ? AND " + C_TOOL_USE_ID + " = ? AND " + C_KIND + " = ?",
                    new String[]{sessionId, toolUseId, String.valueOf(BLOCK_TOOL_RESULT)},
                    null, null, C_MESSAGE_SEQ + " DESC", "1");
            if (!cursor.moveToFirst()) return "";
            JSONObject block = decodeBlock(cursor.getBlob(0), cursor.getString(1));
            if (block == null) return "";
            // 工具结果的负载有两种写法：字符串（绝大多数）与内容块数组（带图片的那种）。
            Object content = block.opt(Field.CONTENT);
            if (content instanceof String) return (String) content;
            if (content instanceof JSONArray) {
                StringBuilder out = new StringBuilder();
                JSONArray blocks = (JSONArray) content;
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject part = blocks.optJSONObject(i);
                    if (part == null) continue;
                    if ("text".equals(part.optString(Field.TYPE, ""))) out.append(part.optString(Field.TEXT, ""));
                }
                return out.toString();
            }
            return "";
        } catch (Exception unreadable) {
            return "";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ------------------------------------------------------------ 列表与摘要

    /** 全部会话（各项目），按活跃时间倒序。 */
    public static List<SessionSummary> listSessions() {
        LegacySessionImporter.ensureImported();
        return querySessions(null);
    }

    /** 只列某个项目的会话。 */
    public static List<SessionSummary> listSessions(String projectDirectory) {
        LegacySessionImporter.ensureImported();
        return querySessions(ProjectPaths.canonicalProject(projectDirectory));
    }

    private static List<SessionSummary> querySessions(String project) {
        ArrayList<SessionSummary> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SESSIONS,
                    new String[]{C_ID, C_PROJECT, C_TITLE, C_NOTE, C_TITLE_OVERRIDE,
                            C_CREATED_AT, C_UPDATED_AT, C_MESSAGE_COUNT},
                    project == null ? null : C_PROJECT + " = ?",
                    project == null ? null : new String[]{project},
                    null, null, C_UPDATED_AT + " DESC");
            while (cursor.moveToNext()) out.add(summaryFromCursor(cursor));
        } catch (Exception ignored) {
            out.clear();
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    private static SessionSummary summaryFromCursor(Cursor cursor) {
        String id = orEmpty(cursor.getString(0));
        String project = orEmpty(cursor.getString(1));
        String title = orEmpty(cursor.getString(2));
        String titleOverride = orEmpty(cursor.getString(4));
        // 覆盖值优先：用户明确改过的名字不该被自动标题盖掉。
        String chosen = titleOverride.isEmpty() ? title : titleOverride;
        if (chosen.isEmpty()) chosen = fallbackTitle(project);
        return new SessionSummary(id, project, compactTitle(singleLine(chosen)), orEmpty(cursor.getString(3)),
                titleOverride, cursor.getLong(5), cursor.getLong(6), cursor.getInt(7));
    }

    /** 读一次会话的摘要；不存在时返回 null。 */
    public static SessionSummary summarize(String sessionId) throws Exception {
        SessionSummary summary = summarizeOrNull(sessionId);
        if (summary == null) throw new IllegalArgumentException("会话不存在：" + sessionId);
        return summary;
    }

    private static SessionSummary summarizeOrNull(String sessionId) {
        if (isBlank(sessionId)) return null;
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SESSIONS,
                    new String[]{C_ID, C_PROJECT, C_TITLE, C_NOTE, C_TITLE_OVERRIDE,
                            C_CREATED_AT, C_UPDATED_AT, C_MESSAGE_COUNT},
                    C_ID + " = ?", new String[]{sessionId}, null, null, null, "1");
            return cursor.moveToFirst() ? summaryFromCursor(cursor) : null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static long nextSequenceOf(String sessionId) {
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().rawQuery(
                    "SELECT COALESCE(MAX(" + C_SEQ + "), 0) FROM " + SessionDatabase.TABLE_EVENTS
                            + " WHERE " + C_SESSION_ID + " = ?", new String[]{sessionId});
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Exception unavailable) {
            return 0L;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 没有可用发言时退回项目目录名；连项目都不知道就叫 Session。 */
    private static String fallbackTitle(String project) {
        String folder = project.isEmpty() ? "" : new File(project).getName();
        return isBlank(folder) ? "Session" : folder;
    }

    // ------------------------------------------------------------ 元数据

    /**
     * 更新会话的备注与标题覆盖。
     *
     * <p><b>刻意不动 {@code updated_at}</b>：会话列表按活跃时间排序，
     * 而「改个备注」不是一次对话活动，让它跳到列表顶部会让人困惑。
     */
    public static void updateSessionMetadata(String sessionId, String note, String titleOverride) {
        if (isBlank(sessionId)) throw new IllegalArgumentException("会话不存在");
        SessionMetadata metadata = new SessionMetadata(note, titleOverride);
        synchronized (SessionDatabase.lock()) {
            ContentValues values = new ContentValues();
            values.put(C_NOTE, metadata.note);
            values.put(C_TITLE_OVERRIDE, metadata.titleOverride);
            SessionDatabase.get().db().update(SessionDatabase.TABLE_SESSIONS, values,
                    C_ID + " = ?", new String[]{sessionId});
        }
    }

    /**
     * 删除一次会话：连同它的消息、内容块、快照，以及**只有它引用的那些大内容**。
     *
     * <h3>为什么大内容要最后单独处理</h3>
     *
     * <p>Blob 是内容寻址的，**两个会话可能引用同一份**（同一个命令跑了两遍、
     * 同一张图发过两次）。所以不能按会话直接删文件 —— 那会把另一个会话的内容一起删掉。
     *
     * <p>这里走 {@code blob_refs}：先取出这个会话引用的 hash 集（走主键），
     * 删掉它的归属行，再只对「已经没有任何会话引用」的 hash 动文件。
     * 比起"扫三张表找孤儿"，这是**按需**的：一个会话占了多少内容，
     * 就只检查多少个 hash。
     */
    public static boolean deleteSession(String sessionId) {
        if (isBlank(sessionId)) return false;
        List<String> candidates;
        synchronized (SessionDatabase.lock()) {
            SQLiteDatabase db;
            try {
                db = SessionDatabase.get().db();
            } catch (Exception unavailable) {
                return false;
            }
            candidates = SessionDatabase.get().blobRefsOf(sessionId);
            db.beginTransaction();
            try {
                String[] args = {sessionId};
                db.delete(SessionDatabase.TABLE_BLOCKS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_SNAPSHOTS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_EVENTS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_BLOB_REFS, C_SESSION_ID + " = ?", args);
                int removed = db.delete(SessionDatabase.TABLE_SESSIONS, C_ID + " = ?", args);
                db.setTransactionSuccessful();
                if (removed == 0) return false;
            } catch (Exception failed) {
                return false;
            } finally {
                db.endTransaction();
            }
        }
        // 事务外做文件删除与登记清理：文件系统操作不该占着写锁。
        // 宽限期不在这里生效是**有意**的：归属行已经随事务提交地删掉了，
        // 所以"这个 hash 还有没有归属"是确定答案，不存在"刚登记还没写引用"的中间态。
        // （那个中间态由 pruneOrphanBlobs 的宽限期兜住，它处理的是另一条路径。）
        int released = SessionDatabase.get().releaseUnreferenced(candidates);
        if (released > 0) {
            ZhiMemory.log("deleteSession id=" + sessionId + " freedBlobs=" + released);
        }
        return true;
    }

    // ------------------------------------------------------------ 派生信息

    /** 会话的 workflow id；库里没有就按会话标识派生一个稳定的。 */
    public static String loadWorkflowId(String sessionId) {
        String recorded = readWorkflowId(sessionId);
        if (!recorded.trim().isEmpty()) return recorded;
        return "session-" + ProjectPaths.shortHash(orEmpty(sessionId));
    }

    private static String readWorkflowId(String sessionId) {
        Cursor cursor = null;
        try {
            cursor = SessionDatabase.get().db().query(SessionDatabase.TABLE_SESSIONS,
                    new String[]{C_WORKFLOW_ID}, C_ID + " = ?", new String[]{sessionId},
                    null, null, null, "1");
            if (cursor.moveToFirst()) return orEmpty(cursor.getString(0));
        } catch (Exception ignored) {
            // 读不出来就退回「按会话标识派生」。
        } finally {
            if (cursor != null) cursor.close();
        }
        return "";
    }

    /** 最后一次记录下来的 API 配置绑定；没有就返回 null。 */
    public static ProfileBinding loadProfileBinding(String sessionId) {
        ProfileBinding binding = null;
        try {
            for (Row row : loadRows(sessionId, -1L, false)) {
                JSONObject value = row.json;
                if (value == null) continue;
                String type = value.optString(Field.TYPE, "");
                if (!RowType.PROFILE_BINDING.equals(type) && !RowType.TURN_CONFIG.equals(type)) continue;
                JSONObject payload = value.optJSONObject(Field.PAYLOAD);
                // 取最后一条有效绑定：中途换过配置时以最新那次为准。
                if (payload != null && !payload.optString(Field.PROFILE_ID, "").isEmpty()) {
                    binding = new ProfileBinding(payload);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return binding;
    }

    /** 计划流程状态：取最后一条 {@code plan_*} 事件。 */
    public static PlanWorkflowState loadPlanState(String sessionId) {
        String workflow = readWorkflowId(sessionId);
        PlanWorkflowState state = PlanWorkflowState.idle();
        try {
            for (Row row : loadRows(sessionId, -1L, false)) {
                JSONObject value = row.json;
                if (value == null) continue;
                String type = value.optString(Field.TYPE, "");
                if (!type.startsWith("plan_")) continue;
                JSONObject payload = value.optJSONObject(Field.PAYLOAD);
                if (payload == null) continue;

                PlanWorkflowState.Status status;
                try {
                    status = PlanWorkflowState.Status.valueOf(payload.optString("status", ""));
                } catch (Exception unknownStatus) {
                    // 早期记录里可能没有 status 字段，按事件类型推断。
                    status = statusForEventType(type);
                }
                state = PlanWorkflowState.restore(status,
                        payload.optString(Field.WORKFLOW_ID, workflow),
                        payload.optLong("revision", state.revision),
                        payload.optString("previous_permission_mode", state.previousPermissionMode),
                        payload.optString("approved_permission_mode", state.approvedPermissionMode),
                        payload.optString("plan_file", state.planFile),
                        payload.optString("plan_text", state.planText),
                        payload.optString("feedback", state.feedback),
                        payload.optLong("updated_at", value.optLong(Field.TIMESTAMP, System.currentTimeMillis())));
            }
        } catch (Exception ignored) {
            // 读不出来就当这次会话还没进过计划模式。
        }
        return state;
    }

    private static PlanWorkflowState.Status statusForEventType(String type) {
        if ("plan_entered".equals(type)) return PlanWorkflowState.PLANNING;
        if ("plan_proposed".equals(type)) return PlanWorkflowState.AWAITING_APPROVAL;
        if ("plan_approved".equals(type)) return PlanWorkflowState.EXECUTING;
        if ("plan_cancelled".equals(type)) return PlanWorkflowState.CANCELLED;
        return PlanWorkflowState.PLANNING;
    }

    // ------------------------------------------------------------ 消息引用与编辑

    /** 内容块的稳定摘要，用于校验「要改的确实是这一条」。 */
    public static String messageContentHash(JSONArray content) {
        return ProjectPaths.shortHash(content == null ? "[]" : content.toString());
    }

    /**
     * 判断一行能不能作为可编辑的目标。
     *
     * <p>只有 user / assistant 两种角色的消息行算数，且必须含可编辑内容：
     * <ul>
     *   <li>user：{@code origin} 必须为空或 {@code human} —— 工具结果也是 user 角色，
     *       但它们不是人写的，不该出现在可编辑列表里；另外必须含真实人类内容；</li>
     *   <li>assistant：必须含 text 或 thinking 正文。</li>
     * </ul>
     */
    public static MessageReference messageReference(JSONObject row, int rowIndex) {
        if (row == null || !RowType.MESSAGE.equals(row.optString(Field.TYPE, ""))) return null;
        String role = row.optString(Field.ROLE, "");
        boolean human = Role.USER.equals(role);
        if (!human && !Role.ASSISTANT.equals(role)) return null;

        if (human) {
            String origin = row.optString(Field.ORIGIN, "");
            if (!origin.isEmpty() && !"human".equals(origin)) return null;
        }
        JSONArray content = row.optJSONArray(Field.CONTENT);
        if (!hasEditableContent(content, role)) return null;

        return new MessageReference(row.optString(Field.MESSAGE_ID, ""), rowIndex, messageContentHash(content));
    }

    public static void editHumanMessage(String sessionId, MessageReference target, String newText)
            throws Exception {
        rewrite(sessionId, target, requireText(newText, "消息不能为空"), false);
    }

    public static void deleteHumanMessage(String sessionId, MessageReference target) throws Exception {
        rewrite(sessionId, target, "", true);
    }

    /** 编辑用户或助手的正文，或助手的思考内容。 */
    public static void editMessage(String sessionId, MessageReference target, String replacement)
            throws Exception {
        rewrite(sessionId, target, requireText(replacement, "消息内容不能为空"), false);
    }

    public static void deleteMessage(String sessionId, MessageReference target) throws Exception {
        rewrite(sessionId, target, "", true);
    }

    /** 空内容写进去等于把这条消息删了，却在界面上显示成一条空消息 —— 所以直接拒绝。 */
    private static String requireText(String value, String message) {
        String trimmed = orEmpty(value).trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(message);
        return trimmed;
    }

    /**
     * 改写或删除一条消息。
     *
     * <p>两个关键决定：
     * <ol>
     *   <li><b>只动目标那一条</b>，其余行不重写 —— 旧实现是整文件重写，
     *       而整文件重写一旦中途失败就会丢掉整段历史。</li>
     *   <li><b>目标之后的所有 {@code context_*} 行与快照一律丢弃</b>。见类注释：
     *       留着它们会让模型继续看到改之前的版本。</li>
     * </ol>
     */
    private static void rewrite(String sessionId, MessageReference target, String replacement,
                                boolean delete) throws Exception {
        if (isBlank(sessionId) || target == null) throw new IllegalArgumentException("无效的会话消息");

        synchronized (SessionDatabase.lock()) {
            SQLiteDatabase db = SessionDatabase.get().db();
            List<Row> rows = loadRows(sessionId, -1L, true);

            if (!target.messageId.trim().isEmpty()) {
                int occurrences = 0;
                for (Row row : rows) {
                    if (row.json == null) continue;
                    if (!RowType.MESSAGE.equals(row.json.optString(Field.TYPE, ""))) continue;
                    if (target.messageId.equals(row.json.optString(Field.MESSAGE_ID, ""))) occurrences++;
                }
                // 命中重复一律拒绝，而不是取第一个：出现重复说明这个引用本身已经不可信。
                if (occurrences > 1) throw new IllegalStateException("消息标识出现重复，修改已拒绝");
            }

            long targetSeq = -1L;
            JSONObject targetRow = null;
            for (Row row : rows) {
                if (row.json == null) continue;
                MessageReference candidate = messageReference(row.json, (int) row.seq);
                if (candidate == null) continue;
                boolean matches = !target.messageId.trim().isEmpty()
                        ? target.messageId.equals(candidate.messageId)
                                && target.contentHash.equals(candidate.contentHash)
                        : candidate.messageId.isEmpty()
                                && target.legacyRowIndex == candidate.legacyRowIndex
                                && target.contentHash.equals(candidate.contentHash);
                if (!matches) continue;
                if (targetSeq >= 0L) throw new IllegalStateException("消息标识出现重复，修改已拒绝");
                targetSeq = row.seq;
                targetRow = row.json;
            }
            if (targetSeq < 0L || targetRow == null) {
                throw new IllegalStateException("要修改的历史消息已变化，请重新打开会话");
            }

            db.beginTransaction();
            try {
                if (delete) {
                    db.delete(SessionDatabase.TABLE_BLOCKS,
                            C_SESSION_ID + " = ? AND " + C_MESSAGE_SEQ + " = ?",
                            new String[]{sessionId, String.valueOf(targetSeq)});
                    db.delete(SessionDatabase.TABLE_EVENTS,
                            C_SESSION_ID + " = ? AND " + C_SEQ + " = ?",
                            new String[]{sessionId, String.valueOf(targetSeq)});
                } else {
                    JSONArray rewritten = rewriteTextBlock(targetRow, replacement);
                    // 先把这一条的内容块整体替掉：块数可能变了（原来没有可编辑块时会补一个）。
                    db.delete(SessionDatabase.TABLE_BLOCKS,
                            C_SESSION_ID + " = ? AND " + C_MESSAGE_SEQ + " = ?",
                            new String[]{sessionId, String.valueOf(targetSeq)});
                    for (int i = 0; i < rewritten.length(); i++) {
                        JSONObject block = rewritten.optJSONObject(i);
                        if (block == null) continue;
                        db.insertWithOnConflict(SessionDatabase.TABLE_BLOCKS, null,
                                prepareBlock(sessionId, targetSeq, i, block),
                                SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }

                // 目标之后的派生数据一律失效。
                String[] after = {sessionId, String.valueOf(targetSeq)};
                db.delete(SessionDatabase.TABLE_SNAPSHOTS,
                        C_SESSION_ID + " = ? AND " + C_SEQ + " > ?", after);
                for (Row row : rows) {
                    if (row.seq <= targetSeq || row.json == null) continue;
                    if (!isContextEvent(row.json.optString(Field.TYPE, ""))) continue;
                    db.delete(SessionDatabase.TABLE_EVENTS,
                            C_SESSION_ID + " = ? AND " + C_SEQ + " = ?",
                            new String[]{sessionId, String.valueOf(row.seq)});
                }

                long count = countMessages(db, sessionId);
                ContentValues values = new ContentValues();
                values.put(C_MESSAGE_COUNT, count);
                // updated_at 不动：改历史不是一次新的对话活动。
                db.update(SessionDatabase.TABLE_SESSIONS, values, C_ID + " = ?", new String[]{sessionId});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        // 上面按 (session_id, message_seq) 删过块与事件 —— 那会让某些 Blob 失去最后一个引用，
        // 而它的归属行还在（变成"看着有人用、其实没人用"的僵尸）。重建一次比逐个推演
        // 哪些行受影响更简单，也不会错。
        //
        // 这里**不直接回收**：回收交给 maintain()（要过宽限期、要分批）。
        SessionDatabase.get().rebuildBlobRefs(sessionId);
    }

    private static long countMessages(SQLiteDatabase db, String sessionId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + SessionDatabase.TABLE_EVENTS
                    + " WHERE " + C_SESSION_ID + " = ? AND " + C_ROW_TYPE + " = ?",
                    new String[]{sessionId, RowType.MESSAGE});
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 替换消息里的第一个可编辑文本块。
     *
     * <p>只改第一个：一条消息可能同时含正文与思考，两者都算「可编辑」，
     * 但用户编辑的是他看到的那个正文。改第一个可编辑块与界面语义一致。
     * 若一个可编辑块都没有（例如只有图片），就补一个新的文本块，
     * 而不是静默地什么都没改。
     */
    private static JSONArray rewriteTextBlock(JSONObject original, String replacement) {
        JSONArray before = original.optJSONArray(Field.CONTENT);
        if (before == null) throw new IllegalStateException("消息内容无效");

        JSONArray after = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < before.length(); i++) {
            JSONObject block = before.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString(Field.TYPE, "");
            if (!isEditableBlock(block, type) || replaced) {
                // 同一消息里的第二个及之后的文本/思考块直接丢弃：它们与用户看到的正文
                // 是同一段内容的重复表述。
                if (!isEditableBlock(block, type)) after.put(deepCopy(block));
                continue;
            }
            JSONObject edited = deepCopy(block);
            try {
                edited.put(Block.TEXT.equals(type) ? Field.TEXT : Field.THINKING, replacement);
            } catch (Exception ignored) {
                continue;
            }
            after.put(edited);
            replaced = true;
        }
        if (!replaced) {
            JSONObject text = new JSONObject();
            try {
                text.put(Field.TYPE, Block.TEXT).put(Field.TEXT, replacement);
            } catch (Exception ignored) {
                // 上面的 put 不可能失败；这是为了不让编译器要求 catch。
            }
            after.put(text);
        }
        JSONObject rewritten = deepCopy(original);
        try {
            rewritten.put(Field.CONTENT, after);
        } catch (Exception ignored) {
            // 同上。
        }
        return after;
    }

    /** 一个内容块是否能被用户编辑。内部注入的块（上下文摘要、续跑指令）不算。 */
    private static boolean isEditableBlock(JSONObject block, String type) {
        if (Block.TEXT.equals(type)) return !isInternalMarkerText(block.optString(Field.TEXT, "").trim());
        if (Block.THINKING.equals(type) || Block.REASONING.equals(type)) {
            return !block.optString(Field.THINKING, "").trim().isEmpty();
        }
        return false;
    }

    /** 这段文本是不是我们自己注入的内部标记。 */
    private static boolean isInternalMarkerText(String text) {
        return text.startsWith(CONTEXT_SUMMARY_MARKER)
                || text.startsWith(INTERNAL_CONTINUE_MARKER);
    }

    /**
     * 完整的内部续跑文本。
     *
     * <p>放在这里而不是让 {@code ZhiCodeEngine} 自己拼字面量：这个串会写进历史，
     * 而识别它的逻辑就在本类。两处各写一份的话，哪天改了一处就会出现
     * 「续跑消息被当成人话」这种只在旧会话上复现的问题。
     */
    public static String internalContinuationText(String stopReason) {
        String reason = stopReason == null ? "unknown" : stopReason;
        return INTERNAL_CONTINUE_MARKER
                + "Previous model output stopped because of " + reason
                + ". Continue the same task from exactly where it stopped."
                + " Do not repeat completed work; inspect the latest tool/results and continue"
                + " until the user's requested task is actually complete."
                + "</zhicode_internal_continue>";
    }

    /** 哪些派生事件会在历史被改动后失效 —— 它们必须一起丢弃。 */
    private static boolean isContextEvent(String type) {
        if (type == null) return false;
        return RowType.SNAPSHOT.equals(type)
                || RowType.COMPACTION.equals(type)
                || type.startsWith("context_");
    }

    // ------------------------------------------------------------ 可编辑判定

    /**
     * 一个内容块数组里有没有「真实内容」。
     *
     * <p>user 的图片算内容（只发图也是发言）；assistant 的思考算内容
     * （有些模型只产出思考）。其余情况要求文本非空。
     */
    private static boolean hasEditableContent(JSONArray content, String role) {
        if (content == null) return false;
        boolean human = Role.USER.equals(role);
        boolean assistant = Role.ASSISTANT.equals(role);

        for (JSONObject block : JsonItems.of(content)) {
            String type = block.optString(Field.TYPE, "");
            if (human && Block.IMAGE.equals(type)) return true;

            boolean textLike = Block.TEXT.equals(type)
                    || (assistant && (Block.THINKING.equals(type) || Block.REASONING.equals(type)));
            if (!textLike) continue;
            String body = block.optString(Block.TEXT.equals(type) ? Field.TEXT : Field.THINKING, "");
            if (!body.trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 取第一条真正的人类发言，用作会话标题。
     *
     * <p>跳过内部注入的文本（上下文摘要、续跑指令）：拿它们当标题只会得到
     * 一串没有信息量的标记。
     */
    private static String firstHumanText(JSONArray content) {
        if (content == null) return "";
        for (JSONObject block : JsonItems.of(content)) {
            if (!Block.TEXT.equals(block.optString(Field.TYPE))) continue;
            String text = block.optString(Field.TEXT, "").trim();
            if (!text.isEmpty() && !isInternalMarkerText(text)) return text;
        }
        return "";
    }

    // ------------------------------------------------------------ 块类型

    private static int kindOf(JSONObject block) {
        String type = block.optString(Field.TYPE, "");
        switch (type) {
            case Block.TEXT: return BLOCK_TEXT;
            case Block.THINKING: return BLOCK_THINKING;
            case Block.REASONING: return BLOCK_REASONING;
            case Block.IMAGE: return BLOCK_IMAGE;
            case Block.TOOL_USE: return BLOCK_TOOL_USE;
            case Block.TOOL_RESULT: return BLOCK_TOOL_RESULT;
            default: return BLOCK_UNKNOWN;
        }
    }

    // ------------------------------------------------------------ 小工具

    /** 一份 profile 绑定的 payload。字段名与 {@link ProfileBinding} 的读取端一一对应。 */
    private static JSONObject profilePayload(SessionConfig config) {
        JSONObject payload = new JSONObject();
        if (config == null) return payload;
        try {
            payload.put(Field.PROFILE_ID, orEmpty(config.profileId));
            payload.put(Field.PROFILE_REVISION, config.profileRevision);
            payload.put(Field.CREDENTIAL_REVISION, config.credentialRevision);
            payload.put(Field.PROTOCOL, orEmpty(config.protocol));
            payload.put(Field.BASE_URL, orEmpty(config.baseUrl));
            payload.put(Field.MODEL, orEmpty(config.model));
        } catch (Exception ignored) {
            // 组装失败时返回已放进去的字段；调用方按缺字段处理。
        }
        return payload;
    }

    /** 深拷贝。JSONObject/JSONArray 都没有拷贝构造，只能走一次序列化。 */
    private static JSONObject deepCopy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (Exception impossible) {
            return value;
        }
    }

    private static JSONArray copy(JSONArray value) {
        try {
            return new JSONArray(value.toString());
        } catch (Exception impossible) {
            return new JSONArray();
        }
    }

    /** 标题压成单行并截断。用省略号而不是硬切，让人知道后面还有内容。 */
    private static String compactTitle(String value) {
        String text = singleLine(orEmpty(value)).trim();
        if (text.length() <= MAX_TITLE_CHARS) return text;
        return text.substring(0, MAX_TITLE_CHARS - 1) + "…";
    }

    /** 把可能的换行（含 CRLF、单独 CR）统一成 LF。 */
    private static String singleLine(String value) {
        return orEmpty(value).replace('\n', ' ');
    }

    /**
     * 清理元数据文本。
     *
     * <p>保留换行与制表符，其余控制字符删掉 —— 它们会在列表渲染时变成乱码方块。
     * 按字符数而不是字节数截断：这里的上限是给人看的宽度。
     */
    private static String sanitizeMetadata(String value, int maxChars) {
        String input = orEmpty(value).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder clean = new StringBuilder(Math.min(input.length(), maxChars));
        for (int i = 0; i < input.length() && clean.length() < maxChars; i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) clean.append(c);
        }
        return clean.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 把被吞掉的 {@link OutOfMemoryError} 记下来。
     *
     * <h3>为什么必须有这个</h3>
     * 会话读写的每一处都包着 {@code catch (Exception ignored)} —— 那是对的，
     * 一次磁盘或编码错误不该把整个回合带崩。但这些 catch 是**按 Exception 写的**，
     * 而 OOM 是 Error；更糟的是有些地方写的是 {@code catch (Throwable ignored)}，
     * 于是 `OutOfMemoryError` 也被一起吞了，连一条日志都不留。
     *
     * <p>真机上出现过一次事故：会话加载把堆打满，进程在 binder 线程上 FATAL，
     * 而日志里**没有任何应用栈**（崩溃栈只有 `Parcel.obtain` / `Binder.execTransact`
     * 两帧系统代码）。定位只能靠读代码推断，因为真正的分配点被静默吃掉了。
     *
     * <p>所以这里**只加日志、不改控制流**：捕获行为不变，但 OOM 会在 logcat 里
     * 留下带栈的一条记录，下次能直接看到「哪个方法分配失败」。
     * 其他异常仍然静默 —— 它们的频率远高于 OOM，打日志会淹没输出。
     */
    private static void logIfOom(Throwable failure) {
        if (!(failure instanceof OutOfMemoryError)) return;
        try {
            android.util.Log.e("ZhiCodeSession", "会话存储期间内存不足", failure);
        } catch (Throwable ignored) {
            // 连日志都写不了时（堆已经彻底满了）只能放弃。
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------ 导入支持

    /**
     * 导入器专用：以**指定 id** 建立一次会话（已存在则沿用原行）。
     *
     * <p>仅供 {@link LegacySessionImporter} 调用。正常路径必须走
     * {@link #SessionStore(String)}，由库自己分配标识 —— 允许外部指定 id 就等于允许
     * 两次写入撞进同一条会话。
     */
    static SessionStore forImport(String sessionId, String project) {
        long now = System.currentTimeMillis();
        synchronized (SessionDatabase.lock()) {
            try {
                ContentValues values = new ContentValues();
                values.put(C_ID, sessionId);
                values.put(C_PROJECT, orEmpty(project));
                values.put(C_TITLE, "");
                values.put(C_NOTE, "");
                values.put(C_TITLE_OVERRIDE, "");
                values.put(C_WORKFLOW_ID, "");
                values.put(C_CREATED_AT, now);
                values.put(C_UPDATED_AT, now);
                values.put(C_MESSAGE_COUNT, 0);
                values.put(C_NEXT_SEQ, 0L);
                SessionDatabase.get().db().insertWithOnConflict(SessionDatabase.TABLE_SESSIONS,
                        null, values, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Exception ignored) {
                // 建不出来时下面的读写都会各自失败，这里不额外抛。
            }
        }
        return new SessionStore(sessionId, orEmpty(project), nextSequenceOf(sessionId),
                countMessages(SessionDatabase.get().db(), sessionId), "");
    }

    /**
     * 导入完成后写回原始的元数据与时间。
     *
     * <p>必须放在最后一步：导入过程中 {@code updated_at} 会被每次提交推成「现在」，
     * 而那会让所有导入的会话在侧栏里挤成同一时刻、并顶到列表最前面。
     *
     * <p>{@code note} 是**必须**搬过来的一项。它是用户在侧栏里手写的那句话，
     * 丢了不会报错、不会影响对话，只是安静地消失 —— 属于最容易在迁移里漏掉的字段。
     * 旧数据里它有两个来源：v1 在 {@code session_metadata} 事件里，
     * v2 在 {@code manifest.json} 的 {@code note} 字段里。
     */
    static void applyImportedMetadata(String sessionId, String title, String note, String titleOverride,
                                      long createdAt, long updatedAt, String workflowId) {
        synchronized (SessionDatabase.lock()) {
            try {
                SQLiteDatabase db = SessionDatabase.get().db();
                ContentValues values = new ContentValues();
                values.put(C_TITLE, compactTitle(orEmpty(title)));
                values.put(C_NOTE, sanitizeMetadata(note, MAX_NOTE_CHARS));
                values.put(C_TITLE_OVERRIDE, singleLine(sanitizeMetadata(titleOverride, MAX_TITLE_OVERRIDE_CHARS)));
                if (createdAt > 0L) values.put(C_CREATED_AT, createdAt);
                if (updatedAt > 0L) values.put(C_UPDATED_AT, updatedAt);
                values.put(C_WORKFLOW_ID, orEmpty(workflowId));
                values.put(C_MESSAGE_COUNT, countMessages(db, sessionId));
                values.put(C_NEXT_SEQ, nextSequenceOf(sessionId));
                db.update(SessionDatabase.TABLE_SESSIONS, values, C_ID + " = ?", new String[]{sessionId});
            } catch (Exception ignored) {
                // 元数据补写失败不影响已经导入的内容。
            }
        }
    }

    /** 导入校验用：一次会话在库里有多少条消息行。 */
    static int storedMessageCount(String sessionId) {
        return (int) countMessages(SessionDatabase.get().db(), sessionId);
    }

    /**
     * 丢掉一次**没导完**的导入留下的半成品。
     *
     * <p>上一次失败的导入会在这里留下一条「建好了但只填了一部分」的会话。
     * 第二次启动如果只看到「这个 id 已存在」就当作已导入，就会把还没搬完的旧目录删掉 ——
     * 那是最糟的一种失败，因为数据两边都不完整。所以重试之前必须先清干净。
     *
     * <p>不复用 {@link #deleteSession}：那个方法会在最后顺手回收大内容，
     * 而此时库里可能还残留着别处（另一次已成功的导入）登记的 Blob，
     * 回收留到整套导入结束之后再做更安全。
     */
    static void discardPartialImport(String sessionId) {
        if (isBlank(sessionId)) return;
        synchronized (SessionDatabase.lock()) {
            SQLiteDatabase db;
            try {
                db = SessionDatabase.get().db();
            } catch (Exception unavailable) {
                return;
            }
            db.beginTransaction();
            try {
                String[] args = {sessionId};
                db.delete(SessionDatabase.TABLE_BLOCKS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_SNAPSHOTS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_EVENTS, C_SESSION_ID + " = ?", args);
                db.delete(SessionDatabase.TABLE_SESSIONS, C_ID + " = ?", args);
                db.setTransactionSuccessful();
            } catch (Exception failed) {
                // 清不掉时后面的 forImport 会沿用那一条，导入校验会把它判成失败。
            } finally {
                db.endTransaction();
            }
        }
    }

    /**
     * 把一个会话导出成自包含的 {@code .jsonl.gz} 归档。
     *
     * <p>实现在 {@link SessionArchive}；这里只做对外门面，让界面不必知道归档类的存在。
     * **必须在非主线程调用**：它会读完整条会话。
     */
    public static File exportSession(String sessionId, File target) throws Exception {
        return SessionArchive.export(sessionId, target);
    }

    /**
     * 把一个归档导入成一条**新会话**，返回新会话的标识。
     *
     * <p>校验不通过时会把这次导入整个丢掉并抛异常 —— 不会留下一份"看起来完整、
     * 其实少了一段"的历史。**必须在非主线程调用**。
     */
    public static String importSession(File source) throws Exception {
        return SessionArchive.importArchive(source);
    }

    /**
     * 会话归档的默认文件名：{@code <标题>-<短标识>.jsonl.gz}。
     *
     * <p>带短标识是为了同一个标题的两条会话不互相覆盖；标题里的路径分隔符等
     * 会破坏文件名的字符全部替换掉。
     */
    public static String suggestedArchiveName(String sessionId) {
        String title = "";
        try {
            SessionSummary summary = summarize(sessionId);
            title = summary == null || summary.title == null ? "" : summary.title.trim();
        } catch (Exception ignored) {
            // 取不到标题就用默认名：文件名不值得为它失败。
        }
        if (title.isEmpty()) title = "会话";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < title.length() && safe.length() < MAX_TITLE_CHARS; i++) {
            char c = title.charAt(i);
            // 只留"任何文件系统都接受"的字符：中文、字母、数字、少数几个标点。
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ';
            safe.append(ok ? c : '_');
        }
        String trimmed = safe.toString().trim().replace(' ', '-');
        if (trimmed.isEmpty()) trimmed = "会话";
        String shortId = isBlank(sessionId) ? "unknown"
                : sessionId.replace("-", "").substring(0, Math.min(8, sessionId.replace("-", "").length()));
        return trimmed + "-" + shortId + ".jsonl.gz";
    }

    // ------------------------------------------------------------ 维护与诊断

    /**
     * 存储维护：回收孤儿大内容 + 把自由页还给文件系统。
     *
     * <h3>为什么要有这样一个入口</h3>
     *
     * <p>回收以前是**顺手**做的（每次写快照、每次删会话都跑一遍）。那有两个问题：
     *
     * <ol>
     *   <li>它的判据是"三张引用列里都找不到"，也就是**全表扫**。挂在删会话上意味着
     *       一次 UI 操作要扫完整个库 —— 越用越慢；</li>
     *   <li>它跟"刚写完 Blob、引用还没提交"的窗口直接重叠，会删掉马上要用的内容
     *       （见 {@code SessionDatabase.pruneOrphanBlobs} 的说明）。</li>
     * </ol>
     *
     * <p>现在改成集中式：由界面在**低频时机**调用（见 `WorkspaceViewModel.maybeRunMaintenance`）
     * 或用户在设置页手动触发。它不阻塞界面，且可以反复调用（幂等）。
     *
     * @return 人可读的结果摘要
     */
    public static String maintain() {
        try {
            long before = SessionDatabase.get().databaseSizeBytes();
            int blobsRemoved = SessionDatabase.get().pruneOrphanBlobs();
            SessionDatabase.get().incrementalVacuum();
            int markersRemoved = LegacySessionImporter.cleanOrphanMarkers();
            long after = SessionDatabase.get().databaseSizeBytes();
            String summary = "回收大内容 " + blobsRemoved + " 个，清理遗留标记 " + markersRemoved
                    + " 个，库 " + ZhiMemory.size(before / 2) + " → " + ZhiMemory.size(after / 2);
            ZhiMemory.log("maintain " + summary);
            return summary;
        } catch (Exception failed) {
            ZhiMemory.log("maintain failed error=" + failed);
            return "维护失败：" + failed;
        }
    }

    /** 存储统计：行数 + 库文件 + 大内容目录的真实字节数。全部来自文件系统与 SQL 计数。 */
    public static JSONObject describeStorage() {
        JSONObject out = new JSONObject();
        try {
            SQLiteDatabase db = SessionDatabase.get().db();
            out.put("database", SessionDatabase.file().getAbsolutePath());
            out.put("database_bytes", SessionDatabase.get().databaseSizeBytes());
            out.put("blobs_dir", BlobStore.root().getAbsolutePath());
            out.put("blobs_bytes", directorySize(BlobStore.root()));
            out.put("sessions", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_SESSIONS));
            out.put("events", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_EVENTS));
            out.put("blocks", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_BLOCKS));
            out.put("snapshots", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_SNAPSHOTS));
            out.put("blobs", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_BLOBS));
            out.put("blob_refs", scalar(db, "SELECT COUNT(*) FROM " + SessionDatabase.TABLE_BLOB_REFS));
            out.put("migrated", SessionDatabase.get().countMigrationRecords("imported"));
            out.put("migration_failures", SessionDatabase.get().countMigrationRecords("failed"));
        } catch (Exception ignored) {
            // 诊断信息拿不到时返回已有的部分。
        }
        return out;
    }

    /**
     * 一个目录树的总字节数。
     *
     * <p>有上限，因为它遍历文件系统：`blobs/` 是两级哈希分桶、每个桶里文件数可控，
     * 但总量没有上限。超过 {@code MAX_SIZE_SCAN_FILES} 个文件时**提前返回已经数到的部分**
     * —— 这个数只用来显示与判断"要不要清理"，不精确没有后果。
     */
    private static long directorySize(File root) {
        long total = 0L;
        int visited = 0;
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            File current = queue.poll();
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory()) {
                    queue.add(child);
                } else {
                    total += child.length();
                    if (++visited >= MAX_SIZE_SCAN_FILES) return total;
                }
            }
        }
        return total;
    }

    /** 见 {@link #directorySize}。 */
    private static final int MAX_SIZE_SCAN_FILES = 20_000;

    private static long scalar(SQLiteDatabase db, String sql) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, null);
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
