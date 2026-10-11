package com.termux.app.zhicode.tasks;

import com.termux.app.zhicode.storage.ProjectPaths;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件形态的任务列表。
 *
 * <h3>存储布局</h3>
 * <pre>
 *   ~/.zhicode/tasks/&lt;项目键&gt;/
 *       1.json  2.json …   ← 一个任务一个文件
 *       .highwatermark     ← 用过的最大 id
 *       .version           ← 每次修改 +1
 *       .lock              ← 文件锁
 * </pre>
 *
 * <h3>为什么一个任务一个文件，而不是一个 tasks.json</h3>
 * 并发写同一个文件意味着「读整个文件 → 改一处 → 写回」，两个并发写必然丢掉
 * 其中一次修改。一任务一文件让「改任务 A」与「改任务 B」互不冲突，
 * 而它们恰恰是最常见的并发形态（主循环与子代理各改各的）。
 * 代价是列表要扫目录，但任务数是几十量级，扫一次可以忽略。
 *
 * <h3>为什么 id 要有一个高水位文件</h3>
 * 只用「现有最大 id + 1」的话，删掉最大的那个任务之后新任务会复用它的 id。
 * 而 id 会出现在对话历史与 {@code addBlockedBy} 之类的引用里 ——
 * 复用会让一条旧引用指到一个完全不同的任务上。高水位只增不减，正是为了避免这件事。
 *
 * <h3>两把锁</h3>
 * 进程内用「按目录建的对象锁」，跨进程用文件锁。两者都需要：文件锁在同一个 JVM
 * 里对同一进程的重复加锁是重入的（{@code FileLock} 是进程级的），
 * 所以光靠它挡不住两个线程。
 */
public final class TaskStore {

    // ---------------------------------------------------------------- 契约常量

    /** 单个任务文件的 JSON 键。它们会被写进磁盘，改名等于让旧任务读不回来。 */
    private static final String KEY_ID = "id";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_STATUS = "status";
    private static final String KEY_BLOCKS = "blocks";
    private static final String KEY_BLOCKED_BY = "blockedBy";
    private static final String KEY_WORKFLOW_ID = "workflow_id";
    private static final String KEY_CREATED_AT = "created_at";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_ACTIVE_FORM = "activeForm";
    private static final String KEY_METADATA = "metadata";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_DELETED = "deleted";

    /** 仅用于文本字段的「原样搬过去」。 */
    private static final String[] COPYABLE_TEXT_KEYS =
        {KEY_SUBJECT, KEY_DESCRIPTION, KEY_ACTIVE_FORM, KEY_OWNER};

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_DELETED = "deleted";

    private static final String TASKS_DIR = "tasks";
    private static final String HIGH_WATER_FILE = ".highwatermark";
    private static final String VERSION_FILE = ".version";
    private static final String LOCK_FILE = ".lock";
    private static final String TASK_SUFFIX = ".json";
    private static final String TEMP_SUFFIX = ".tmp";
    /** 任务文件名必须是纯数字，否则 {@code listFiles} 会捡到 .lock 之类的文件。 */
    private static final String TASK_FILE_PATTERN = "\\d+\\.json";

    private static final int WRITE_INDENT = 2;

    // ---------------------------------------------------------------- 对外类型

    /** 任务变化的订阅者。 */
    public interface Listener {
        void onTasksChanged(Snapshot snapshot);
    }

    /** 一次订阅。关闭即退订，可以放在 try-with-resources 里。 */
    public static final class Subscription implements AutoCloseable {
        private final String key;
        private final Listener listener;
        private boolean active = true;

        private Subscription(String key, Listener listener) {
            this.key = key;
            this.listener = listener;
        }

        public void unsubscribe() {
            synchronized (SUBSCRIBERS_LOCK) {
                if (!active) return;
                active = false;
                // 退订要幂等：重复调用（或 close 之后又 unsubscribe）不得重复移除。
                List<Listener> listeners = SUBSCRIBERS.get(key);
                if (listeners == null) return;
                listeners.remove(listener);
                if (listeners.isEmpty()) SUBSCRIBERS.remove(key);
            }
        }

        @Override public void close() {
            unsubscribe();
        }
    }

    /**
     * 一份任务列表的不可变快照。
     *
     * <p>{@link #version} 每次修改 +1，界面据此跳过重复渲染。
     * {@link #tasks} 是深拷贝且不可修改的：订阅者会把它交给界面线程，
     * 而写线程随后还会改磁盘上的内容。
     */
    public static final class Snapshot {
        public final long version;
        public final String workflowId;
        public final List<JSONObject> tasks;

        public Snapshot(long version, String workflowId, List<JSONObject> tasks) {
            this.version = version;
            this.workflowId = normalizeWorkflowId(workflowId);
            this.tasks = Collections.unmodifiableList(deepCopyTasks(tasks));
        }

        public long getVersion() { return version; }

        public String getWorkflowId() { return workflowId; }

        public List<JSONObject> getTasks() { return tasks; }
    }

    /** 拿锁之后才能跑的读取动作 —— 返回类型由调用方决定。 */
    private interface LockedAction<T> {
        T run() throws Exception;
    }

    /** 一次修改的结果：返回值 + 供订阅者用的新快照（可能为 null，表示没有变化）。 */
    private static final class Mutation<T> {
        final T result;
        final Snapshot snapshot;

        Mutation(T result, Snapshot snapshot) {
            this.result = result;
            this.snapshot = snapshot;
        }
    }

    // ---------------------------------------------------------------- 静态状态

    /** 每个任务目录一把进程内锁。 */
    private static final Object DIRECTORY_LOCKS_LOCK = new Object();
    private static final Map<String, Object> DIRECTORY_LOCKS = new HashMap<>();
    /** 订阅者表，键是「目录 + 工作流」。 */
    private static final Object SUBSCRIBERS_LOCK = new Object();
    private static final Map<String, List<Listener>> SUBSCRIBERS = new HashMap<>();

    // ---------------------------------------------------------------- 实例状态

    private final File directory;
    private final File highWaterFile;
    private final File versionFile;
    private final File lockFile;
    private final String directoryKey;
    private final String workflowId;
    private final String subscriberKey;

    public TaskStore(String projectDirectory) {
        this(projectDirectory, "");
    }

    /**
     * @param workflowId 空串表示「不按工作流过滤」。见 {@link #belongsToWorkflow}：
     *        任务本身没有 workflow_id 字段时，只有空串的 store 才看得到它 ——
     *        那是在这个字段存在之前写下的旧任务。
     */
    public TaskStore(String projectDirectory, String workflowId) {
        String canonicalProject = ProjectPaths.canonicalProject(projectDirectory);
        String key = ProjectPaths.projectKey(canonicalProject);
        directory = new File(TermuxConstants.dataDir(), TASKS_DIR + "/" + key);
        highWaterFile = new File(directory, HIGH_WATER_FILE);
        versionFile = new File(directory, VERSION_FILE);
        lockFile = new File(directory, LOCK_FILE);
        directoryKey = canonicalPath(directory);
        this.workflowId = normalizeWorkflowId(workflowId);
        subscriberKey = directoryKey + '\n' + this.workflowId;
    }

    /** 订阅某个项目/工作流的任务变化。 */
    public static Subscription subscribe(String projectDirectory, String workflowId, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener == null");
        TaskStore store = new TaskStore(projectDirectory, workflowId);
        synchronized (SUBSCRIBERS_LOCK) {
            List<Listener> listeners = SUBSCRIBERS.get(store.subscriberKey);
            if (listeners == null) {
                listeners = new ArrayList<>();
                SUBSCRIBERS.put(store.subscriberKey, listeners);
            }
            listeners.add(listener);
        }
        return new Subscription(store.subscriberKey, listener);
    }

    // ---------------------------------------------------------------- 写操作

    /** 新建一个任务，返回它的 JSON 表示。 */
    public JSONObject create(String subject, String description, String activeForm,
                             JSONObject metadata) throws Exception {
        Mutation<JSONObject> mutation = withDirectoryLock(() -> {
            int id = nextId();
            long now = System.currentTimeMillis();
            JSONObject task = new JSONObject()
                .put(KEY_ID, String.valueOf(id))
                .put(KEY_SUBJECT, subject)
                .put(KEY_DESCRIPTION, description)
                .put(KEY_STATUS, STATUS_PENDING)
                // 两个关系字段一律建成空数组而不是省略：读取方（含界面）可以无条件遍历。
                .put(KEY_BLOCKS, new JSONArray())
                .put(KEY_BLOCKED_BY, new JSONArray())
                .put(KEY_WORKFLOW_ID, workflowId)
                .put(KEY_CREATED_AT, now)
                .put(KEY_UPDATED_AT, now);
            if (activeForm != null && !activeForm.isEmpty()) task.put(KEY_ACTIVE_FORM, activeForm);
            if (metadata != null) task.put(KEY_METADATA, new JSONObject(metadata.toString()));
            writeTask(task);
            writeText(highWaterFile, String.valueOf(id));
            return new Mutation<>(task, snapshotUnlocked(incrementVersion()));
        });
        notifySubscribers(mutation.snapshot);
        return mutation.result;
    }

    /**
     * 按 id 改一个任务。
     *
     * @return 改后的任务；{@code null} 表示这个 id 不存在
     */
    public JSONObject update(String id, JSONObject patch) throws Exception {
        Mutation<JSONObject> mutation = withDirectoryLock(() -> updateUnlocked(id, patch));
        if (mutation == null) return null;
        notifySubscribers(mutation.snapshot);
        return mutation.result;
    }

    private Mutation<JSONObject> updateUnlocked(String id, JSONObject patch) throws Exception {
        JSONObject task = readTask(id);
        if (task == null) return null;

        // 删除走同一个入口（status=deleted）：对调用方来说「任务没了」与
        // 「任务状态变成 deleted」是同一件事，分开会让模型选错。
        if (STATUS_DELETED.equals(patch.optString(KEY_STATUS, ""))) {
            if (!taskFile(id).delete()) throw new IllegalStateException("Failed to delete task #" + id);
            JSONObject result = new JSONObject().put(KEY_ID, id).put(KEY_DELETED, true);
            return new Mutation<>(result, snapshotUnlocked(incrementVersion()));
        }

        String before = task.toString();
        backfillProvenance(task, id);
        for (String key : COPYABLE_TEXT_KEYS) {
            if (patch.has(key) && !patch.isNull(key)) task.put(key, patch.optString(key, ""));
        }
        applyStatus(task, patch);
        addUnique(task, KEY_BLOCKS, patch.optJSONArray("addBlocks"));
        addUnique(task, KEY_BLOCKED_BY, patch.optJSONArray("addBlockedBy"));
        mergeMetadata(task, patch.optJSONObject(KEY_METADATA));

        // 内容没变就不写盘、也不通知订阅者：否则界面会因为一次空更新重画一遍。
        if (before.equals(task.toString())) return new Mutation<>(task, null);

        task.put(KEY_UPDATED_AT, System.currentTimeMillis());
        writeTask(task);
        return new Mutation<>(task, snapshotUnlocked(incrementVersion()));
    }

    /**
     * 补上早期版本没写的两个字段。
     *
     * <p>{@code created_at} 回落到文件的修改时间，而不是「现在」——
     * 现在的时间会让一个很老的任务显示成刚建的。
     */
    private void backfillProvenance(JSONObject task, String id) throws Exception {
        long now = System.currentTimeMillis();
        if (!task.has(KEY_WORKFLOW_ID)) task.put(KEY_WORKFLOW_ID, workflowId);
        if (!task.has(KEY_CREATED_AT)) {
            long fromDisk = taskFile(id).lastModified();
            task.put(KEY_CREATED_AT, fromDisk > 0 ? fromDisk : now);
        }
    }

    /** 状态只接受三个值；{@code deleted} 已在上面单独处理。 */
    private static void applyStatus(JSONObject task, JSONObject patch) throws Exception {
        if (!patch.has(KEY_STATUS)) return;
        String status = patch.optString(KEY_STATUS, "");
        boolean known = STATUS_PENDING.equals(status) || STATUS_IN_PROGRESS.equals(status)
            || STATUS_COMPLETED.equals(status);
        if (!known) throw new IllegalArgumentException("Invalid task status: " + status);
        task.put(KEY_STATUS, status);
    }

    /**
     * 合并元数据。三条规则：
     * 值为 {@code null} → 删掉这个键；否则覆盖；原本没有 metadata → 新建一个。
     */
    private static void mergeMetadata(JSONObject task, JSONObject patch) throws Exception {
        if (patch == null) return;
        JSONObject merged = task.optJSONObject(KEY_METADATA);
        if (merged == null) merged = new JSONObject();
        JSONArray names = patch.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (patch.isNull(key)) merged.remove(key);
                else merged.put(key, patch.get(key));
            }
        }
        task.put(KEY_METADATA, merged);
    }

    /** 往数组里追加去重后的新值。已有值跳过，空串跳过。 */
    private static void addUnique(JSONObject task, String key, JSONArray added) throws Exception {
        if (added == null) return;
        JSONArray current = task.optJSONArray(key);
        if (current == null) current = new JSONArray();
        for (int i = 0; i < added.length(); i++) {
            String value = added.optString(i, "");
            if (value.isEmpty() || contains(current, value)) continue;
            current.put(value);
        }
        task.put(key, current);
    }

    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.length(); i++) {
            if (value.equals(values.optString(i))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 读操作

    /** 按 id 读一个任务；不存在或不属于当前工作流时返回 {@code null}。 */
    public JSONObject get(String id) {
        try {
            return withDirectoryLock(() -> readTask(id));
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** 全部任务，按数字 id 升序。读失败返回空列表。 */
    public List<JSONObject> list() {
        try {
            return withDirectoryLock(this::listUnlocked);
        } catch (Exception unreadable) {
            return new ArrayList<>();
        }
    }

    /** 当前快照。读失败时给一个版本 0 的空快照，而不是抛错。 */
    public Snapshot snapshot() {
        try {
            return withDirectoryLock(() -> snapshotUnlocked(readVersion()));
        } catch (Exception unreadable) {
            return new Snapshot(0, workflowId, Collections.emptyList());
        }
    }

    // ---------------------------------------------------------------- 磁盘

    /**
     * 在进程内锁 + 文件锁之下跑一段代码。
     *
     * <p>目录在拿到锁**之后**才创建：先建目录再拿锁会有一个两者之间的窗口，
     * 两个线程可能都以为自己是第一个建的。{@code mkdirs} 在这里是幂等的。
     */
    private <T> T withDirectoryLock(LockedAction<T> action) throws Exception {
        synchronized (lockForDirectory(directoryKey)) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Cannot create task directory " + directory);
            }
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = lockAccess.getChannel();
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        }
    }

    private JSONObject readTask(String id) throws Exception {
        File file = taskFile(id);
        if (!file.isFile()) return null;
        JSONObject task = new JSONObject(readText(file));
        return belongsToWorkflow(task) ? task : null;
    }

    private List<JSONObject> listUnlocked() {
        List<JSONObject> tasks = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.matches(TASK_FILE_PATTERN));
        if (files == null) return tasks;
        for (File file : files) {
            try {
                JSONObject task = new JSONObject(readText(file));
                if (belongsToWorkflow(task)) tasks.add(task);
            } catch (Exception unreadable) {
                // 单个任务文件坏掉不影响整个列表 —— 用户还能看到并处理其它任务。
            }
        }
        tasks.sort(Comparator.comparingInt(task -> parseId(task.optString(KEY_ID, "0"))));
        return tasks;
    }

    /**
     * 这个任务属于当前工作流吗。
     *
     * <p>没有 {@code workflow_id} 的任务只在读取方也是「不按工作流过滤」时可见。
     * 换成「所有人都能看到它们」会让子代理读到主循环的私有任务；
     * 换成「所有人都看不到」会让升级之后旧任务凭空消失。
     */
    private boolean belongsToWorkflow(JSONObject task) {
        if (!task.has(KEY_WORKFLOW_ID) || task.isNull(KEY_WORKFLOW_ID)) return workflowId.isEmpty();
        return workflowId.equals(task.optString(KEY_WORKFLOW_ID, ""));
    }

    /**
     * 原子写一个任务。
     *
     * <p>临时文件 → rename。rename 是原子的，所以读方永远看到完整的 JSON；
     * 直接写目标文件会让一个正在读的线程拿到半截内容并解析失败。
     *
     * <p>不先删目标文件：rename 本身就能覆盖，先删会在两步之间留下一个
     * 「任务不存在」的窗口。
     */
    private void writeTask(JSONObject task) throws Exception {
        File target = taskFile(task.getString(KEY_ID));
        File temporary = new File(target.getAbsolutePath() + TEMP_SUFFIX);
        writeText(temporary, task.toString(WRITE_INDENT));
        if (!temporary.renameTo(target)) {
            // 同一目录内 rename 不该跨设备，但留一条退路总好过丢掉这次修改。
            writeText(target, task.toString(WRITE_INDENT));
            temporary.delete();
        }
    }

    /**
     * 任务文件。
     *
     * <p>id 只保留数字：它会拼进文件名，而 id 来自工具调用（可能是模型编的）。
     * 只留数字比校验后拒绝更宽容 —— {@code "task-3"} 会变成 {@code 3.json}，
     * 这与它应当是同一个任务。
     */
    private File taskFile(String id) {
        String safe = id == null ? "" : id.replaceAll("[^0-9]", "");
        if (safe.isEmpty()) throw new IllegalArgumentException("Invalid task ID: " + id);
        return new File(directory, safe + TASK_SUFFIX);
    }

    /** 下一个可用 id = max(高水位, 现有最大 id) + 1。见类注释。 */
    private int nextId() {
        return Math.max(readHighWater(), highestExistingId()) + 1;
    }

    private int highestExistingId() {
        int max = 0;
        File[] files = directory.listFiles((dir, name) -> name.matches(TASK_FILE_PATTERN));
        if (files != null) {
            for (File file : files) {
                max = Math.max(max, parseId(file.getName().replace(TASK_SUFFIX, "")));
            }
        }
        return max;
    }

    private int readHighWater() {
        try {
            return parseId(readText(highWaterFile).trim());
        } catch (Exception missing) {
            return 0;
        }
    }

    private long readVersion() {
        try {
            return Long.parseLong(readText(versionFile).trim());
        } catch (Exception missing) {
            return 0;
        }
    }

    private long incrementVersion() throws Exception {
        long version = readVersion() + 1;
        writeText(versionFile, String.valueOf(version));
        return version;
    }

    // ---------------------------------------------------------------- 通知

    /**
     * 通知订阅者。
     *
     * <p>先拷一份订阅者列表再通知：监听器的回调里可能退订（界面被销毁），
     * 直接遍历原列表会抛 ConcurrentModificationException。
     *
     * <p>回调里抛的运行时异常被吞掉：一个界面监听器出错不该让一次已经写盘成功的
     * 修改变成失败。
     */
    private void notifySubscribers(Snapshot snapshot) {
        if (snapshot == null) return;
        List<Listener> listeners;
        synchronized (SUBSCRIBERS_LOCK) {
            List<Listener> registered = SUBSCRIBERS.get(subscriberKey);
            if (registered == null || registered.isEmpty()) return;
            listeners = new ArrayList<>(registered);
        }
        for (Listener listener : listeners) {
            try {
                listener.onTasksChanged(snapshot);
            } catch (RuntimeException ignored) {
                // 见上。
            }
        }
    }

    private static Object lockForDirectory(String key) {
        synchronized (DIRECTORY_LOCKS_LOCK) {
            Object lock = DIRECTORY_LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                DIRECTORY_LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private Snapshot snapshotUnlocked(long version) {
        return new Snapshot(version, workflowId, listUnlocked());
    }

    /**
     * 深拷任务列表。
     *
     * <p>快照会被交给界面线程，而写线程随后还会继续改它手上的 JSONObject。
     * 共享引用会让界面读到「正在被改」的对象。
     */
    private static List<JSONObject> deepCopyTasks(List<JSONObject> tasks) {
        List<JSONObject> copy = new ArrayList<>();
        if (tasks == null) return copy;
        for (JSONObject task : tasks) {
            if (task == null) continue;
            try {
                copy.add(new JSONObject(task.toString()));
            } catch (Exception unreadable) {
                throw new IllegalArgumentException("Cannot copy task snapshot", unreadable);
            }
        }
        return copy;
    }

    private static int parseId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception notANumber) {
            return 0;
        }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < buffer.length && (read = in.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception failure) {
            return file.getAbsolutePath();
        }
    }

    private static String normalizeWorkflowId(String workflowId) {
        return workflowId == null ? "" : workflowId;
    }
}
