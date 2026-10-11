package com.termux.app.zhicode.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话的唯一数据源：{@code ~/.zhicode/sessions.db}。
 *
 * <h3>为什么把「一个会话一个目录」换成「一个库」</h3>
 * 旧实现里一次会话散在 {@code manifest.json} + {@code segments/*.zlog} + {@code blobs/}
 * + {@code snapshots/*.snap} 四类文件里，另有一个全局的 {@code session-index.db} 只存列表摘要。
 * 后果是：改一条备注要「追加事件 + 重写 manifest + 更新索引」三步，任何一步漏掉就出现
 * 「列表显示的标题和打开后看到的不一样」；删一次会话要递归删目录；而列表索引与正文
 * 之间没有任何机制保证一致。
 *
 * <p>换成单库之后，一次写入就是一个事务：消息、它的内容块、会话的 {@code message_count}
 * 与 {@code updated_at} 一起提交，不存在"部分生效"。列表也不再需要单独的索引表 ——
 * 它就是 {@code sessions} 表上的一次 {@code ORDER BY}。
 *
 * <h3>表</h3>
 * <pre>
 *   sessions            每次会话一行：项目、标题、备注、时间、消息数、workflow、下一个 seq
 *   events              一条消息或一个事件，message 与事件共用同一套递增 seq 以保留时序
 *   content_blocks      message 的内容块，按 ordinal 排序
 *   context_snapshots   压缩后给模型用的上下文快照，每个会话只留最近几个
 *   blobs               大内容登记表，用于回收：没有任何引用时才删文件
 *   migration_log       旧格式导入的结果，失败的会话在这里留痕
 * </pre>
 *
 * <h3>关于外键</h3>
 * 表上**没有**写 {@code FOREIGN KEY}，删除也不依赖级联：
 * {@link SessionStore#deleteSession} 在自己的事务里显式删掉每个子表。原因是 Android 的
 * {@code SQLiteDatabase} 用连接池，而 {@code PRAGMA foreign_keys} 是**每条连接**的开关，
 * 靠它保证级联删除在并发读写下并不可靠。写一个声明出来却不保证生效，
 * 比不写更容易让人误判 —— 所以这里不写，正确性来自显式删除。
 *
 * <h3>线程</h3>
 * 所有写路径在 {@link #lock()} 上串行。读取不取锁（WAL 允许读写并发）。
 */
final class SessionDatabase {

    private static final String DATABASE_NAME = "sessions.db";

    /**
     * schema 版本。加表/加列/加索引时 +1，并在 {@link #migrate} 里补上对应分支。
     *
     * <ul>
     *   <li>1：第一版单库（sessions / events / content_blocks / context_snapshots / blobs / migration_log）</li>
     *   <li>2：加 {@code blob_refs} 归属表与四个缺失索引；开启增量真空</li>
     * </ul>
     */
    private static final int SCHEMA_VERSION = 2;

    /** {@code PRAGMA auto_vacuum} 的「增量」档；见 {@link #enableIncrementalVacuum}。 */
    private static final int AUTO_VACUUM_INCREMENTAL = 2;

    static final String TABLE_SESSIONS = "sessions";
    static final String TABLE_EVENTS = "events";
    static final String TABLE_BLOCKS = "content_blocks";
    static final String TABLE_SNAPSHOTS = "context_snapshots";
    static final String TABLE_BLOBS = "blobs";
    static final String TABLE_MIGRATION = "migration_log";
    /** 大内容的**归属**：哪个会话引用了哪个 Blob。见 {@link #rebuildBlobRefs}。 */
    static final String TABLE_BLOB_REFS = "blob_refs";

    private static final Object LOCK = new Object();
    private static SessionDatabase instance;

    private final SQLiteDatabase database;

    private SessionDatabase(SQLiteDatabase database) {
        this.database = database;
    }

    /** 进程内共享的那一份；路径在第一次用到时才确定（{@link TermuxConstants} 可能后配置）。 */
    static SessionDatabase get() {
        synchronized (LOCK) {
            if (instance == null) instance = open();
            return instance;
        }
    }

    /** 写路径的串行锁。 */
    static Object lock() {
        return LOCK;
    }

    SQLiteDatabase db() {
        return database;
    }

    /** 数据库文件。诊断与备份都要用它。 */
    static File file() {
        return new File(TermuxConstants.dataDir(), DATABASE_NAME);
    }

    // ------------------------------------------------------------ 打开与建表

    private static SessionDatabase open() {
        File target = file();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        boolean empty = !target.isFile() || target.length() == 0L;
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(target, null);
        try {
            // WAL：读写不互相阻塞。会话读取（打开侧栏）与引擎写入会同时发生。
            database.enableWriteAheadLogging();
        } catch (Exception ignored) {
            // 某些文件系统（例如被挂到不支持 WAL 的位置）会拒绝；退回默认日志模式即可。
        }
        if (empty) {
            // ⚠️ auto_vacuum **只在库还是空的时候**设置才生效。老库要走
            // enableIncrementalVacuum() 里的「设上 + VACUUM 重建」。
            try {
                database.execSQL("PRAGMA auto_vacuum = " + AUTO_VACUUM_INCREMENTAL);
            } catch (Exception ignored) {
                // 设不上就退化成"文件不会缩"，功能不受影响。
            }
        }
        try {
            database.execSQL("PRAGMA busy_timeout = 5000");
        } catch (Exception ignored) {
            // 拿不到就靠上面的串行锁。
        }
        SessionDatabase result = new SessionDatabase(database);
        result.migrate();
        return result;
    }

    /**
     * 打开增量真空。
     *
     * <h3>为什么需要它</h3>
     *
     * <p>删掉一万条消息之后，{@code sessions.db} 在文件系统上**一个字节都不会缩** ——
     * 被释放的页留在自由列表里，文件保持历史峰值大小。这对外置了 Blob 的我们尤其难看：
     * 库里本来只剩小行，却因为曾经写过几 MB 的 payload 而一直是个大文件。
     *
     * <p>{@code auto_vacuum} 有代价（每次提交多一次页移动），所以用**增量**档：
     * 平时不做，只有显式调用 {@link #incrementalVacuum} 时才把自由页还给文件系统。
     */
    private void enableIncrementalVacuum() {
        try {
            Cursor cursor = database.rawQuery("PRAGMA auto_vacuum", null);
            int mode = cursor.moveToFirst() ? cursor.getInt(0) : 0;
            cursor.close();
            if (mode == AUTO_VACUUM_INCREMENTAL) return;
            // 老库：先设 pragma，再 VACUUM 让它按新布局重建。VACUUM 需要约 2× 的临时空间，
            // 所以只在库不大的时候做；太大就跳过，等用户手动在设置页触发。
            database.execSQL("PRAGMA auto_vacuum = " + AUTO_VACUUM_INCREMENTAL);
            long size = databaseSizeBytes();
            if (size > 0 && size <= MAX_AUTO_VACUUM_BYTES) {
                database.execSQL("VACUUM");
                ZhiMemory.log("autoVacuum enabled size=" + ZhiMemory.size(size / 2));
            } else {
                // ⚠️ 这一次的 pragma 会在下次 VACUUM 时生效；在它生效之前，
                // 文件仍然不会自动缩 —— 如实记下来，免得以后有人以为已经生效了。
                ZhiMemory.log("autoVacuum pending size=" + ZhiMemory.size(size / 2)
                        + " reason=too-large-or-unknown");
            }
        } catch (Exception failed) {
            ZhiMemory.log("autoVacuum failed error=" + failed);
        }
    }

    /** VACUUM 的体量上限：超过它就不在启动路径上做（需要约 2× 临时空间）。 */
    private static final long MAX_AUTO_VACUUM_BYTES = 256L * 1024L * 1024L;

    /**
     * 把自由页还给文件系统。**只在维护时调用**，不要放在任何热路径上。
     *
     * @return 实际回收的页数；0 表示没什么可回收的
     */
    int incrementalVacuum() {
        synchronized (LOCK) {
            try {
                // 先要一次"尽可能多"的额度，否则默认只还 1 页。
                Cursor cursor = database.rawQuery("PRAGMA page_count", null);
                long pages = cursor.moveToFirst() ? cursor.getLong(0) : 0L;
                cursor.close();
                if (pages <= 0L) return 0;
                long before = databaseSizeBytes();
                database.execSQL("PRAGMA incremental_vacuum(" + pages + ")");
                long after = databaseSizeBytes();
                ZhiMemory.log("incrementalVacuum from=" + ZhiMemory.size(before / 2)
                        + " to=" + ZhiMemory.size(after / 2));
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, pages));
            } catch (Exception failed) {
                // 没开成 auto_vacuum 的库会在这里失败（"cannot vacuum"），那很正常：
                // 它意味着文件不会缩，但数据完好。
                ZhiMemory.log("incrementalVacuum skipped error=" + failed);
                return 0;
            }
        }
    }

    /** 库文件（含 WAL）在磁盘上的实际字节数；库还没建出来时为 0。 */
    long databaseSizeBytes() {
        long total = 0L;
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File part = new File(file().getAbsolutePath() + suffix);
            if (part.isFile()) total += part.length();
        }
        return total;
    }

    private void migrate() {
        int version = database.getVersion();
        if (version >= SCHEMA_VERSION) return;
        boolean needsVacuumMode = version >= 1;
        database.beginTransaction();
        try {
            if (version < 1) createSchema();
            if (version < 2) upgradeToV2(version < 1);
            database.setVersion(SCHEMA_VERSION);
            database.setTransactionSuccessful();
        } catch (Exception failed) {
            // 迁移失败时**不推进版本**，下次启动会重试；老数据一律保持原样
            // （这里全部是 CREATE ... IF NOT EXISTS 与可重复的回填）。
            ZhiMemory.log("schemaMigrate failed from=" + version + " error=" + failed);
            throw failed;
        } finally {
            database.endTransaction();
        }
        // VACUUM 不能在事务里执行；必须等上面的 schema 事务完整提交之后再做。
        if (needsVacuumMode) enableIncrementalVacuum();
    }

    /**
     * v1 → v2：补索引、补归属表、回填归属。
     *
     * <p>{@link #createSchema()} 已经建出全部 v2 结构，所以这里只在**升级老库**时补差量
     *（{@code fromScratch} 为真表示刚建库，什么都不用做）。
     */
    private void upgradeToV2(boolean fromScratch) {
        createBlobRefsSchema();
        createIndexes();
        if (fromScratch) return;
        // 老库：归属表是空的，从三个引用列回填一次。
        rebuildAllBlobRefs();
    }

    private void createSchema() {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SESSIONS + " ("
                + "id TEXT PRIMARY KEY,"
                + "project TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL DEFAULT '',"
                + "note TEXT NOT NULL DEFAULT '',"
                + "title_override TEXT NOT NULL DEFAULT '',"
                + "workflow_id TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "message_count INTEGER NOT NULL DEFAULT 0,"
                + "next_seq INTEGER NOT NULL DEFAULT 0)");

        // 一条消息或一个事件。message 与事件共用同一套 seq：界面按下标回放历史时，
        // 「第几条」与「什么时候发生的」必须是同一个顺序，分两张表就再也对不上了。
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_EVENTS + " ("
                + "session_id TEXT NOT NULL,"
                + "seq INTEGER NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "row_type TEXT NOT NULL,"
                + "timestamp INTEGER NOT NULL DEFAULT 0,"
                + "role TEXT NOT NULL DEFAULT '',"
                + "message_id TEXT NOT NULL DEFAULT '',"
                + "turn_id TEXT NOT NULL DEFAULT '',"
                + "origin TEXT NOT NULL DEFAULT '',"
                + "payload BLOB,"
                + "payload_blob_ref TEXT NOT NULL DEFAULT '',"
                + "payload_blob_size INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (session_id, seq))");

        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOCKS + " ("
                + "session_id TEXT NOT NULL,"
                + "message_seq INTEGER NOT NULL,"
                + "ordinal INTEGER NOT NULL,"
                + "kind INTEGER NOT NULL DEFAULT 0,"
                + "text_value TEXT NOT NULL DEFAULT '',"
                + "tool_use_id TEXT NOT NULL DEFAULT '',"
                + "tool_name TEXT NOT NULL DEFAULT '',"
                + "is_error INTEGER NOT NULL DEFAULT 0,"
                + "media_type TEXT NOT NULL DEFAULT '',"
                + "data BLOB,"
                + "blob_ref TEXT NOT NULL DEFAULT '',"
                + "blob_size INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (session_id, message_seq, ordinal))");

        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SNAPSHOTS + " ("
                + "session_id TEXT NOT NULL,"
                + "seq INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "messages BLOB,"
                + "blob_ref TEXT NOT NULL DEFAULT '',"
                + "blob_size INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (session_id, seq))");

        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOBS + " ("
                + "hash TEXT PRIMARY KEY,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL DEFAULT 0)");

        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MIGRATION + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "legacy_path TEXT NOT NULL DEFAULT '',"
                + "format TEXT NOT NULL DEFAULT '',"
                + "status TEXT NOT NULL DEFAULT '',"
                + "detail TEXT NOT NULL DEFAULT '',"
                + "session_id TEXT NOT NULL DEFAULT '',"
                + "at INTEGER NOT NULL DEFAULT 0)");

        createBlobRefsSchema();
        createIndexes();
    }

    /**
     * 大内容的归属表。
     *
     * <h3>为什么需要它</h3>
     *
     * <p>没有它的时候，"这个 Blob 还有人用吗"只能靠反查三个引用列
     *（{@code content_blocks.blob_ref} / {@code events.payload_blob_ref} /
     * {@code context_snapshots.blob_ref}）。那是**全表扫**，而且没法回答
     * "删掉这个会话会释放多少"。
     *
     * <p>有了它：删会话时先按 {@code session_id} 取出它引用的全部 hash
     *（走主键），删掉归属行，再只对"没有任何会话再引用"的 hash 动文件
     *（走 {@link #IDX_BLOB_REFS_HASH}）。
     *
     * <h3>它只是**快速筛选**，不是权威</h3>
     *
     * <p>真正决定"能不能删"的仍然是那三个引用列（见 {@link #pruneOrphanBlobs}）。
     * 这样即便归属表因为某个 bug 漂了，后果也只是"少回收一点"，
     * 而不会变成"删掉还在用的内容"—— 后者是不可恢复的。
     */
    private void createBlobRefsSchema() {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOB_REFS + " ("
                + "session_id TEXT NOT NULL,"
                + "hash TEXT NOT NULL,"
                + "PRIMARY KEY (session_id, hash))");
    }

    private static final String IDX_BLOB_REFS_HASH = "blob_refs_hash";

    /**
     * 索引。
     *
     * <p>全部是 {@code IF NOT EXISTS}，所以在建库与升级两条路径上都能重复执行。
     *
     * <p>每一个都对应一条**真实存在**的查询，不是"先建着"：
     * <ul>
     *   <li>{@code events_session_seq}——按会话顺序读行（窗口化读历史）；</li>
     *   <li>{@code events_message_id}——按消息标识定位（改/删历史消息的引用校验）；</li>
     *   <li>{@code events_session_row_type}——按行类型筛（任务快照、计划事件）。
     *       没有它就要扫该会话的**全部**事件，而这是打开会话时的必经之路；</li>
     *   <li>{@code events_payload_blob}——回收时确认"这个 Blob 有没有被事件引着"；</li>
     *   <li>{@code blocks_blob} / {@code snapshots_blob}——同上，另两个引用列；</li>
     *   <li>{@code blocks_tool_use}——按 {@code tool_use_id} 取一条工具结果
     *       （展开工具时要全文）。没有它就要扫该会话的**全部**内容块；</li>
     *   <li>{@code migration_log_path}——导入旧会话时判重。没有它是 O(N²) 全表扫；</li>
     *   <li>{@code blob_refs_hash}——按 hash 反查归属（回收与删会话）。</li>
     * </ul>
     */
    private void createIndexes() {
        database.execSQL("CREATE INDEX IF NOT EXISTS events_session_seq"
                + " ON " + TABLE_EVENTS + "(session_id, seq)");
        database.execSQL("CREATE INDEX IF NOT EXISTS events_message_id"
                + " ON " + TABLE_EVENTS + "(session_id, message_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS events_session_row_type"
                + " ON " + TABLE_EVENTS + "(session_id, row_type)");
        database.execSQL("CREATE INDEX IF NOT EXISTS events_payload_blob"
                + " ON " + TABLE_EVENTS + "(payload_blob_ref)");
        database.execSQL("CREATE INDEX IF NOT EXISTS blocks_blob"
                + " ON " + TABLE_BLOCKS + "(blob_ref)");
        database.execSQL("CREATE INDEX IF NOT EXISTS blocks_tool_use"
                + " ON " + TABLE_BLOCKS + "(session_id, tool_use_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS snapshots_blob"
                + " ON " + TABLE_SNAPSHOTS + "(blob_ref)");
        database.execSQL("CREATE INDEX IF NOT EXISTS migration_log_path"
                + " ON " + TABLE_MIGRATION + "(legacy_path, status)");
        database.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_BLOB_REFS_HASH
                + " ON " + TABLE_BLOB_REFS + "(hash)");
    }

    // ------------------------------------------------------------ Blob 登记与回收

    /**
     * 登记一个 Blob（幂等）。
     *
     * <p>必须在写入引用它的事务**之前**调用：这样最坏情况只是"多出一个暂时没人引用的
     * Blob"，而不是"事务提交了、文件还没落盘"。
     *
     * <p>用 {@code INSERT OR IGNORE} 而不是 {@code REPLACE}：{@code created_at} 是
     * {@link #pruneOrphanBlobs} 的宽限期依据（见那里的说明），用 REPLACE 会让
     * 「同一个 Blob 被反复写入」每次都刷新它，从而永远躲过回收 —— 那就是只涨不落。
     * 同一个 hash 的 size 必然相同（内容寻址），所以忽略新值没有代价。
     */
    void registerBlob(String hash, int size) {
        if (hash == null || hash.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("hash", hash);
        values.put("size", size);
        values.put("created_at", System.currentTimeMillis());
        try {
            database.insertWithOnConflict(TABLE_BLOBS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception ignored) {
            // 登记失败只影响回收，不影响这次写入本身。
        }
    }

    /**
     * 记下「这个会话引用了这个 Blob」。**必须在写入引用的同一个事务里调用。**
     *
     * <p>与 {@link #registerBlob} 的顺序相反、时机也相反：登记是"我先占个位置"，
     * 归属是"引用真的写进去了"。两者都在事务里/事务前完成之后，
     * {@link #pruneOrphanBlobs} 才既不会误删、也不会漏收。
     */
    void addBlobRef(android.database.sqlite.SQLiteDatabase db, String sessionId, String hash) {
        if (db == null || sessionId == null || sessionId.isEmpty() || hash == null || hash.isEmpty()) return;
        try {
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_BLOB_REFS + "(session_id, hash) VALUES (?, ?)",
                    new Object[]{sessionId, hash});
        } catch (Exception ignored) {
            // 归属写不进去只会让回收晚一点（见 pruneOrphanBlobs 的"权威是引用列"）。
        }
    }

    /** 撤销一个会话的全部归属行。删会话时调用。 */
    void removeBlobRefs(String sessionId) {
        database.delete(TABLE_BLOB_REFS, "session_id = ?", new String[]{sessionId});
    }

    /** 一个会话引用了哪些 Blob（走主键）。删会话前先取出来用。 */
    List<String> blobRefsOf(String sessionId) {
        ArrayList<String> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = database.query(TABLE_BLOB_REFS, new String[]{"hash"},
                    "session_id = ?", new String[]{sessionId}, null, null, null);
            while (cursor.moveToNext()) out.add(cursor.getString(0));
        } catch (Exception ignored) {
            // 拿不到就退化成"什么都不回收"，绝不冒险。
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    /**
     * 按会话重建归属行。
     *
     * <p>用在「这个会话的内容被局部删除过」之后（改/删一条历史消息、丢弃旧快照）。
     * 那些删除按 `(session_id, message_seq)` 走，可能让某个 Blob 失去最后一个引用 ——
     * 而归属行还在，那个 Blob 就变成"看着有人用、其实没人用"的僵尸。
     * 重建一次比逐个推演哪些行受影响更简单，也不会错。
     */
    void rebuildBlobRefs(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        try {
            database.delete(TABLE_BLOB_REFS, "session_id = ?", new String[]{sessionId});
            insertRefsFor("SELECT session_id, blob_ref FROM " + TABLE_BLOCKS
                    + " WHERE session_id = ? AND blob_ref <> ''", sessionId);
            insertRefsFor("SELECT session_id, payload_blob_ref FROM " + TABLE_EVENTS
                    + " WHERE session_id = ? AND payload_blob_ref <> ''", sessionId);
            insertRefsFor("SELECT session_id, blob_ref FROM " + TABLE_SNAPSHOTS
                    + " WHERE session_id = ? AND blob_ref <> ''", sessionId);
        } catch (Exception ignored) {
            // 重建失败只会让某个 Blob 晚一点被回收。
        }
    }

    /** 一次性把全部会话的归属回填（老库升级用）。 */
    private void rebuildAllBlobRefs() {
        try {
            database.execSQL("DELETE FROM " + TABLE_BLOB_REFS);
            database.execSQL("INSERT OR IGNORE INTO " + TABLE_BLOB_REFS + "(session_id, hash)"
                    + " SELECT session_id, blob_ref FROM " + TABLE_BLOCKS + " WHERE blob_ref <> ''");
            database.execSQL("INSERT OR IGNORE INTO " + TABLE_BLOB_REFS + "(session_id, hash)"
                    + " SELECT session_id, payload_blob_ref FROM " + TABLE_EVENTS + " WHERE payload_blob_ref <> ''");
            database.execSQL("INSERT OR IGNORE INTO " + TABLE_BLOB_REFS + "(session_id, hash)"
                    + " SELECT session_id, blob_ref FROM " + TABLE_SNAPSHOTS + " WHERE blob_ref <> ''");
        } catch (Exception failed) {
            ZhiMemory.log("blobRefsBackfill failed error=" + failed);
        }
    }

    private void insertRefsFor(String sql, String sessionId) {
        try {
            database.execSQL("INSERT OR IGNORE INTO " + TABLE_BLOB_REFS + "(session_id, hash) " + sql,
                    new Object[]{sessionId});
        } catch (Exception ignored) {
            // 单个来源失败不影响另外两个。
        }
    }

    /**
     * 回收没有任何引用的大内容。
     *
     * <p><b>两道闸门，缺一不可。</b>
     *
     * <h3>闸门一：宽限期</h3>
     *
     * <p>写入顺序是「先写 Blob 文件 + 登记 → 再在事务里写引用」（见 {@link #registerBlob}），
     * 而这两步之间有一个窗口：此刻这个 Blob 确实"没有任何引用"。
     * 如果回收正好落在这个窗口里，它会删掉文件、删掉登记，随后写入的引用就**悬空**了 ——
     * 读那一块时解码失败、静默变成 null，也就是**那条工具输出永久消失**。
     *
     * <p>所以只回收「登记满 {@link #ORPHAN_GRACE_MS} 之后仍然没人引用」的。
     * 多留一分钟没有代价，删错一次是不可恢复的。
     *
     * <h3>闸门二：引用列才是权威</h3>
     *
     * <p>{@link #TABLE_BLOB_REFS} 只用来**快速筛出候选**（走 {@code blob_refs_hash} 索引）；
     * 真正决定"能不能删"的是那三个引用列。这样即便归属表因为某个 bug 漂了，
     * 后果也只是"少回收一点"，而不是"删掉还在用的内容"。
     *
     * @return 实际删除的文件数
     */
    int pruneOrphanBlobs() {
        synchronized (LOCK) {
            long cutoff = System.currentTimeMillis() - ORPHAN_GRACE_MS;
            List<String> orphanHashes = new ArrayList<>();
            Cursor cursor = null;
            try {
                cursor = database.rawQuery(
                        "SELECT b.hash FROM " + TABLE_BLOBS + " b"
                                + " WHERE b.created_at < ?"
                                + " AND NOT EXISTS (SELECT 1 FROM " + TABLE_BLOB_REFS
                                + "   r WHERE r.hash = b.hash)"
                                // 下面三条是权威判据，各自走 events_payload_blob /
                                // blocks_blob / snapshots_blob 索引。
                                + " AND NOT EXISTS (SELECT 1 FROM " + TABLE_BLOCKS
                                + "   c WHERE c.blob_ref = b.hash)"
                                + " AND NOT EXISTS (SELECT 1 FROM " + TABLE_EVENTS
                                + "   e WHERE e.payload_blob_ref = b.hash)"
                                + " AND NOT EXISTS (SELECT 1 FROM " + TABLE_SNAPSHOTS
                                + "   s WHERE s.blob_ref = b.hash)"
                                + " LIMIT " + MAX_PRUNE_PER_PASS,
                        new String[]{String.valueOf(cutoff)});
                while (cursor.moveToNext()) orphanHashes.add(cursor.getString(0));
            } catch (Exception ignored) {
                return 0;
            } finally {
                if (cursor != null) cursor.close();
            }

            BlobStore blobs = BlobStore.shared();
            int removed = 0;
            for (String hash : orphanHashes) {
                if (!blobs.delete(hash)) continue;
                database.delete(TABLE_BLOBS, "hash = ?", new String[]{hash});
                removed++;
            }
            return removed;
        }
    }

    /**
     * 一次回收最多处理多少个。
     *
     * <p>分批是为了不长时间占着写锁：一次 import 或一次大清理可能留下几千个待回收项，
     * 一口气删完会让并发的会话读取等很久。剩下的留给下一次维护。
     */
    private static final int MAX_PRUNE_PER_PASS = 512;

    /** 见 {@link #pruneOrphanBlobs}：登记后多久才允许被回收。 */
    private static final long ORPHAN_GRACE_MS = 60_000L;

    /**
     * 把这些 hash 里**已经没有任何归属**的释放掉（删文件 + 删登记）。
     *
     * <p>与 {@link #pruneOrphanBlobs} 的分工：那个是"定期扫一遍找孤儿"（带宽限期、
     * 分批，防的是写入窗口里的误删）；这个是"删会话之后按已知清单收尾"——
     * 候选集是精确的，所以**不需要宽限期**，也**不需要**再去看引用列。
     *
     * <p>仍然保留「有没有归属」这一道判断：那是"另一个会话还在用它"的唯一判据，
     * 也是 Blob 共享能成立的原因。
     *
     * @return 实际删除的文件数
     */
    int releaseUnreferenced(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return 0;
        synchronized (LOCK) {
            int removed = 0;
            BlobStore blobs = BlobStore.shared();
            for (String hash : hashes) {
                if (hash == null || hash.isEmpty()) continue;
                if (isReferenced(hash)) continue;
                if (!blobs.delete(hash)) continue;
                database.delete(TABLE_BLOBS, "hash = ?", new String[]{hash});
                removed++;
            }
            return removed;
        }
    }

    /** 还有没有会话引用这个 Blob（走 {@link #IDX_BLOB_REFS_HASH}）。 */
    private boolean isReferenced(String hash) {
        Cursor cursor = null;
        try {
            cursor = database.query(TABLE_BLOB_REFS, new String[]{"hash"},
                    "hash = ?", new String[]{hash}, null, null, null, "1");
            return cursor.moveToFirst();
        } catch (Exception ignored) {
            // 查不出来时按"还在用"处理：宁可留一个没人用的文件，也不能删掉还在用的内容。
            return true;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ------------------------------------------------------------ 迁移记录

    /** 记一条旧格式导入的结果。失败的会话靠它留痕，不会静默消失。 */
    void recordMigration(String legacyPath, String format, String status, String detail, String sessionId) {
        ContentValues values = new ContentValues();
        values.put("legacy_path", legacyPath == null ? "" : legacyPath);
        values.put("format", format == null ? "" : format);
        values.put("status", status == null ? "" : status);
        values.put("detail", detail == null ? "" : detail);
        values.put("session_id", sessionId == null ? "" : sessionId);
        values.put("at", System.currentTimeMillis());
        try {
            database.insert(TABLE_MIGRATION, null, values);
        } catch (Exception ignored) {
            // 留痕失败不能影响导入本身。
        }
    }

    /** 是否已经导入过某个旧会话文件（避免每次启动重复尝试）。 */
    boolean hasMigrationRecord(String legacyPath) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("SELECT 1 FROM " + TABLE_MIGRATION
                    + " WHERE legacy_path = ? AND status = 'imported' LIMIT 1", new String[]{legacyPath});
            return cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 迁移记录条数，供诊断使用。 */
    int countMigrationRecords(String status) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("SELECT COUNT(*) FROM " + TABLE_MIGRATION
                    + (status == null ? "" : " WHERE status = ?"),
                    status == null ? null : new String[]{status});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
