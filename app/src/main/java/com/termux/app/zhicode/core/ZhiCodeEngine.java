package com.termux.app.zhicode.core;

import android.content.Context;
import android.os.PowerManager;

import com.termux.app.zhicode.api.ModelProvider;
import com.termux.app.zhicode.api.ModelProviders;
import com.termux.app.zhicode.api.StreamFailure;
import com.termux.app.zhicode.api.StreamListener;
import com.termux.app.zhicode.agents.AgentDefinition;
import com.termux.app.zhicode.agents.AgentTask;
import com.termux.app.zhicode.agents.SubagentManager;
import com.termux.app.zhicode.json.JsonItems;
import com.termux.app.zhicode.model.AssistantTurn;
import com.termux.app.zhicode.model.PlanWorkflowState;
import com.termux.app.zhicode.model.SessionConfig;
import com.termux.app.zhicode.model.ToolCall;
import com.termux.app.zhicode.model.ToolExecutionResult;
import com.termux.app.zhicode.storage.PlanStore;
import com.termux.app.zhicode.storage.ProjectPaths;
import com.termux.app.zhicode.storage.SessionStore;
import com.termux.app.zhicode.tasks.TaskStore;
import com.termux.app.zhicode.tools.ToolRegistry;
import com.termux.app.zhicode.tools.ZhiTool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环。
 *
 * <h3>它负责的只有一件事：把「模型说要做什么」变成「历史里发生了什么」</h3>
 * 一轮会话是这样推进的：发一条 user 消息 → 请模型回一条 assistant 消息 →
 * 如果它要调工具就执行、把结果作为 user 消息写回去 → 再来一轮，直到模型在没有工具调用
 * 的情况下收尾。循环次数上限是 {@link SessionConfig#maxAgentTurns}，
 * 达到上限会抛错而不是静默停止（「任务没跑完」必须让人看见，而不是看起来像正常结束）。
 *
 * <h3>历史是唯一的真相</h3>
 * {@link #messages} 是发给提供方的上下文，同时逐行落盘到 {@link SessionStore}。
 * 每次请求前都会调 {@link #repairToolHistory} 自愈一次：协议要求每个
 * {@code tool_use} 都有配对的 {@code tool_result}，而中断、取消、崩溃都会留下半截。
 * 修复只动「发给模型的快照」，不删人类对话。
 *
 * <h3>三种「开新会话」的边界</h3>
 * <ul>
 *   <li>{@link #resetConversation()} 清空历史与 workflow，换一个新的 workflow id；</li>
 *   <li>{@link #resumeConversation} 用磁盘上的历史替换内存里的，并恢复 workflow 与计划状态；</li>
 *   <li>{@link #compactContext} 保留近期消息，把较早的交给模型压成摘要。</li>
 * </ul>
 * 三件事都必须让「测量到的上下文用量」失效 —— 那个数字对应的是旧的消息条数，
 * 留着它会让界面上的上下文占用显示成一个没有依据的值。
 *
 * <h3>并发</h3>
 * 同一时刻只允许一次会话在跑（{@link #busy}）。所有状态写入都发生在
 * {@code zhi-java-agent} 这一条线程上，只有 {@code messageLock} 保护的
 * {@link #messages} 会被界面线程读（{@code estimateContextTokens}）。
 * 取消走线程中断，并在收尾时把所有残留状态清干净 —— 取消后再开一次会话必须是一切如初。
 */
public final class ZhiCodeEngine {

    /**
     * 引擎向界面回调的事件。
     *
     * <p>全部方法都在 agent 线程上被调用；实现方（Compose 控制器）负责切回主线程。
     * 带 {@code default} 的实现体是后来加的，老调用方不需要实现它们。
     */
    public interface Listener {
        void onSessionStarted(SessionConfig config);

        void onTextDelta(String text);

        void onThinkingDelta(String thinking);

        default void onToolBatchStarted(ToolBatch batch) {}

        void onToolUse(ToolCall call);

        default void onToolBatchCompleted(ToolBatch batch) {}

        default void onToolProgress(ToolCall call, String chunk, boolean stderr, long elapsedMs) {}

        void onToolResult(ToolCall call, ToolExecutionResult result);

        void onPermissionRequest(PermissionGate.PermissionRequest request);

        default void onQuestionRequest(QuestionGate.QuestionRequest request) {}

        default void onPlanStateChanged(PlanWorkflowState state) {}

        default void onPlanApprovalRequest(PlanApprovalGate.ApprovalRequest request) {}

        default void onTasksChanged(TaskStore.Snapshot snapshot) {}

        default void onProjectDirectoryChanged(String projectDirectory) {}

        default void onResponseInterruptedBySteering() {}

        default void onResponseRetry() {}

        default void onQueuedPromptApplied() {}

        void onUsage(long inputTokens, long outputTokens);

        void onStatus(String status);

        void onTurnComplete(String stopReason);

        void onError(String message, Throwable error);
    }

    /**
     * 一批工具调用。
     *
     * <p>一次 assistant 回复里的工具调用<b>不</b>一定都属于同一批：回复里可能夹着
     * 说明文字的段落，那表示「先解释一下，再动手」。{@link #breakBeforeToolIds} 记下的
     * 就是这些分界之后的工具 —— 界面据此把它们显示成两组。
     */
    public static final class ToolBatch {
        public final long batchId;
        public final List<ToolCall> toolCalls;
        public final Set<String> breakBeforeToolIds;

        private ToolBatch(long batchId, List<ToolCall> toolCalls, Set<String> breakBeforeToolIds) {
            this.batchId = batchId;
            this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls));
            this.breakBeforeToolIds = Collections.unmodifiableSet(new LinkedHashSet<>(breakBeforeToolIds));
        }
    }

    // ------------------------------------------------------------ 常量

    private static final String AGENT_THREAD_NAME = "zhi-java-agent";
    private static final String WAKELOCK_TAG = "ZhiCode:AgentTask";
    /** 唤醒锁上限。一次会话可以很长，但不能无限期占着电池。 */
    private static final long WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L;
    /** 连续多少次空回复之后放弃自动重试。再试下去只会得到同样的空回复。 */
    private static final int MAX_EMPTY_RESPONSE_RECOVERIES = 2;
    /** 传输失败后重放请求之前的等待。给中间设备一点恢复时间，又不至于让人等太久。 */
    private static final long TRANSPORT_RETRY_DELAY_MS = 250L;
    private static final int MIN_CONTEXT_WINDOW_TOKENS = 16_000;
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;
    /** Debug 工具的默认 scope。缺省即沙箱：真机调试必须显式写 host。 */
    private static final String DEFAULT_DEBUG_SCOPE = "sandbox";
    /** 人类发言的 origin 标记。工具结果也是 user 角色，靠这个字段区分。 */
    private static final String ORIGIN_HUMAN = "human";
    private static final String ORIGIN_INTERNAL = "internal";

    private static final String CANCEL_TOOL_REASON =
            "Tool execution was cancelled before a result was recorded. Result unavailable; do not assume success.";
    private static final String FAILED_TOOL_REASON =
            "Tool execution ended before a result was recorded because the turn failed. Result unavailable; do not assume success.";
    private static final String REPAIR_BEFORE_MESSAGE =
            "Recovered an incomplete tool call before sending the next message.";
    private static final String REPAIR_BEFORE_REQUEST =
            "Recovered an incomplete tool call before an API request.";
    private static final String REPAIR_BEFORE_COMPACT =
            "Recovered an incomplete tool call before context compaction.";
    private static final String REPAIR_BEFORE_AUTO_COMPACT =
            "Recovered an incomplete tool call before automatic context compaction.";
    private static final String REPAIR_REJECTED_CHAIN =
            "Recovered a malformed tool-call chain rejected by the API.";

    private static final String STALE_RESPONSE_REASON =
            "Not executed because the user sent a correction while this response was in flight.";
    private static final String TOOL_NOT_STARTED_REASON =
            "Not executed because the user sent a correction before this tool started.";
    private static final String TOOL_INTERRUPTED_REASON =
            "Not executed because the user sent a correction while another tool was running.";

    /** 摘要请求的系统提示词。它只影响摘要质量，不参与主对话。 */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are ZhiCode's context compactor. Produce a faithful continuation summary."
                    + " Respond with text only and never call tools.";

    /**
     * 实际使用的摘要系统提示：用户填过就用他的，空串用默认。
     *
     * <p>空串表示"没改过"这条约定与 `model/PromptSlots.kt` 一致 ——
     * 那里是唯一给出默认文案的地方，这里只是把它取出来。
     */
    private static String summarySystemPrompt(SessionConfig config) {
        String custom = config == null ? "" : config.summaryPrompt;
        String value = custom == null ? "" : custom.trim();
        return value.isEmpty() ? SUMMARY_SYSTEM_PROMPT : value;
    }

    /**
     * 可以安全重放的失败码，取值来自 {@link StreamFailure#code}。
     *
     * <p>这些都是「请求根本没被处理、或者处理到一半被掐断」的情形。真正被拒绝的
     * （400/401/422 之类）不在其中 —— 重放它们只会失败第二次。
     */
    private static final Set<String> REPLAYABLE_FAILURE_CODES = new LinkedHashSet<>(Arrays.asList(
            "request_timeout", "stream_read_error", "server_error", "service_unavailable",
            "temporarily_unavailable", "connection_reset", "unexpected_eof"));

    /**
     * 只出现在异常文案里的重放信号。
     *
     * <p>提供方没给出结构化 code 时才靠这些子串判断。文案来自各家 API 与底层
     * socket 实现，属于外部契约，<b>不能</b>跟着界面文案一起翻译。
     */
    private static final String[] REPLAYABLE_FAILURE_MARKERS = {
            "request_timeout", "stream_read_error", "unexpected end of stream", "connection reset",
            "connection closed", "socket closed", "http 408", "http 502", "http 503", "http 504"};

    /** 提供方报出的「工具调用链对不上」。同样来自 API 文案。 */
    private static final String[] TOOL_CHAIN_MISMATCH_MARKERS = {
            "no tool output found for function call",
            "no tool call found for function call output"};

    /** 摘要是内部动作，不需要把流式输出转发给界面。 */
    private static final StreamListener SILENT_STREAM = new StreamListener() {
        @Override public void onTextDelta(String text) { }

        @Override public void onThinkingDelta(String thinking) { }

        @Override public void onToolInputDelta(String id, String name, String partialJson) { }

        @Override public void onUsage(long inputTokens, long outputTokens) { }
    };

    // ------------------------------------------------------------ 依赖与状态

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, AGENT_THREAD_NAME));
    private final Context appContext;
    private final ToolRegistry tools;
    private final boolean subagentMode;
    private final Set<String> allowedTools;
    private final String additionalSystemPrompt;
    private final SubagentManager subagents;
    private final ModelProvider providerOverride;

    private final PermissionGate permissionGate = new PermissionGate();
    private final QuestionGate questionGate = new QuestionGate();
    private final PlanApprovalGate planApprovalGate = new PlanApprovalGate();
    private final SteeringQueue steering = new SteeringQueue();

    /** 发给提供方的上下文。写入都在 agent 线程，读取（估算用量）在界面线程。 */
    private final JSONArray messages = new JSONArray();
    private volatile int messageRevision;
    /**
     * 配置版本。每次 {@code configure()} 与历史失效都会 +1。
     *
     * <p>存在的理由：{@link #estimateContextTokens} 的结果依赖配置（系统提示词、
     * 工具 schema、是否启用视觉输入），但它没法便宜地比较两个 {@code SessionConfig}。
     * 用一个只增不减的计数器代替比较，既准确又不用为估算加一次深比较。
     */
    private volatile long configRevision;
    /** {@link #estimateContextTokens} 的结果缓存；key 见 {@link #estimateCacheKey()}。 */
    private volatile String cachedEstimateKey;
    private volatile int cachedEstimateTokens = -1;
    private volatile String cachedSystemPrompt;
    private volatile String cachedSystemPromptKey;
    private volatile JSONArray cachedToolSchemas;
    private volatile String cachedToolSchemasKey;
    private final Object messageLock = new Object();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile EngineTaskState taskState = EngineTaskState.IDLE;
    private long nextToolBatchId;

    private volatile Listener listener;
    private volatile Future<?> activeRun;
    private volatile SessionConfig config;
    private volatile SessionStore sessionStore;
    private volatile TaskStore.Subscription taskSubscription;
    private volatile TaskStore.Snapshot taskSnapshot;
    private volatile String boundTaskKey = "";
    private volatile long taskBindingGeneration;

    private volatile Thread agentThread;
    private volatile boolean executingTool;
    private volatile boolean executingModelRequest;
    private volatile ModelProvider activeProvider;
    private volatile SessionConfig activeTurnConfig;
    private volatile String activeTurnId = "";
    private volatile PowerManager.WakeLock taskWakeLock;
    private volatile Thread manualCompactionThread;

    /** 最近一次主循环 API 响应报出的真实上下文用量；-1 表示还没有测量值。 */
    private volatile long lastMeasuredContextTokens = -1;
    /** {@link #lastMeasuredContextTokens} 对应的、发给提供方的消息条数。 */
    private volatile int lastMeasuredMessageCount = -1;
    private volatile int consecutiveAutoCompactFailures;

    public ZhiCodeEngine(Context context, Listener listener) {
        this(context, listener, null);
    }

    public ZhiCodeEngine(Context context, Listener listener, ModelProvider provider) {
        this(context, listener, provider, false, null, "");
    }

    private ZhiCodeEngine(Context context, Listener listener, ModelProvider provider, boolean subagentMode,
                          Set<String> allowedTools, String additionalSystemPrompt) {
        this.appContext = context.getApplicationContext();
        this.tools = new ToolRegistry(this.appContext);
        this.listener = listener;
        this.providerOverride = provider;
        this.subagentMode = subagentMode;
        this.allowedTools = allowedTools == null ? null : new LinkedHashSet<>(allowedTools);
        this.additionalSystemPrompt = additionalSystemPrompt == null ? "" : additionalSystemPrompt;
        this.subagents = subagentMode ? null : new SubagentManager(this.appContext, provider,
                request -> forwardPermissionRequest(request),
                request -> forwardQuestionRequest(request));
    }

    /**
     * 造一个孤立的子引擎。
     *
     * <p>子代理<b>不能</b>再生成子代理：权限只能收紧不能放宽，而没有上限的嵌套会让
     * 「谁批准了这次操作」变得无法回答。所以这里直接置上 {@code subagentMode}，
     * 之后 {@code Agent}/{@code Task} 类工具在子引擎里一律拒绝。
     */
    public static ZhiCodeEngine createSubagent(Context context, Listener listener, ModelProvider provider,
                                              Set<String> allowedTools, String additionalSystemPrompt) {
        return new ZhiCodeEngine(context, listener, provider, true, allowedTools, additionalSystemPrompt);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public EngineTaskState taskState() {
        return taskState;
    }

    private void setTaskState(EngineTaskState next) {
        if (next != null && next != taskState) taskState = next;
    }

    public boolean isExecutingTool() {
        return executingTool;
    }

    public boolean isExecutingModelRequest() {
        return executingModelRequest;
    }

    private void forwardPermissionRequest(PermissionGate.PermissionRequest request) {
        Listener current = listener;
        if (current != null) current.onPermissionRequest(request);
    }

    private void forwardQuestionRequest(QuestionGate.QuestionRequest request) {
        Listener current = listener;
        if (current != null) current.onQuestionRequest(request);
    }

    private void reportStatus(String status) {
        Listener current = listener;
        if (current != null) current.onStatus(status);
    }

    // ------------------------------------------------------------ 配置

    /**
     * 应用一份新设置。
     *
     * <p>三处「不能跟着改」的字段：
     * <ul>
     *   <li>{@code workflowId} 属于这次会话，不属于设置；</li>
     *   <li>正在计划中（或等审批）的计划状态不能被一次设置改写；</li>
     *   <li>会话正在跑的时候，权限模式沿用<b>本次会话开始时</b>的值 ——
     *       用户中途改全局设置，不应该让正在执行的那一轮突然换了权限级别。</li>
     * </ul>
     */
    public synchronized void configure(SessionConfig config) {
        SessionConfig next = config.copy();
        next.permissionMode = PermissionModePolicy.normalize(next.permissionMode);

        SessionConfig current = this.config;
        if (current != null) {
            if (sessionStore != null) {
                next.workflowId = current.workflowId;
                PlanWorkflowState state = current.planWorkflowState;
                if (state != null && (state.isPlanning() || state.isAwaitingApproval())) {
                    next.planWorkflowState = state.copy();
                }
            }
            if (busy.get()) {
                next.permissionMode = PermissionModePolicy.normalize(current.permissionMode);
            }
        }
        this.config = next;
        cachedSystemPrompt = null;
        cachedSystemPromptKey = null;
        cachedToolSchemas = null;
        cachedToolSchemasKey = null;
        // 估算结果依赖配置（系统提示词 / 工具 schema / 视觉开关），换了配置就得重算。
        configRevision++;
        bindTaskStore();
        invalidateMeasuredUsage();
    }

    /** 只改「下一次模型请求用的推理档位」，不动其它设置。 */
    public synchronized void updateReasoningEffort(String effort) {
        String next = effort == null ? "auto" : effort.trim();
        if (next.isEmpty()) next = "auto";
        if (config != null) config.effort = next;
        if (activeTurnConfig != null) activeTurnConfig.effort = next;
    }

    public String getUserPermissionMode() {
        SessionConfig current = config;
        return current == null
                ? PermissionModePolicy.DEFAULT
                : PermissionModePolicy.normalize(current.permissionMode);
    }

    /** 当前真正生效的权限模式：计划模式会让它降级成只读。 */
    public String getEffectivePermissionMode() {
        SessionConfig current = config;
        return current == null
                ? PermissionModePolicy.DEFAULT
                : PermissionModePolicy.effective(current.permissionMode, current.planWorkflowState);
    }

    // ------------------------------------------------------------ 会话存储

    private synchronized void ensureSessionStore() {
        if (sessionStore != null || config == null) return;
        sessionStore = new SessionStore(config.projectDirectory);
        if (config.workflowId == null || config.workflowId.trim().isEmpty()) {
            config.workflowId = UUID.randomUUID().toString();
        }
        bindTaskStore();
        appendConfigEvent();
    }

    /**
     * 当前会话的标识。引擎尚未配置或还没写过内容时为 null。
     *
     * <p>它以前叫 {@code getSessionFile()} 并返回一个 {@code File} ——
     * 会话已经不再按文件存放，所以这里返回的是 {@code sessions.db} 里的会话标识。
     */
    public synchronized String getSessionId() {
        return sessionStore == null ? null : sessionStore.getSessionId();
    }

    private void appendMessage(String role, JSONArray content) throws Exception {
        appendMessage(role, content, "", activeTurnId, ORIGIN_INTERNAL);
    }

    /**
     * 写一条消息：先写内存里的上下文，再落盘。
     *
     * <p>顺序不能反。落盘失败（磁盘满、目录被删）只影响「下次能不能恢复这次会话」，
     * 而内存里的上下文必须完整，否则本次请求自己就不自洽了。
     */
    private void appendMessage(String role, JSONArray content, String messageId, String turnId, String origin)
            throws Exception {
        JSONObject message = new JSONObject().put("role", role).put("content", content);
        synchronized (messageLock) {
            messages.put(message);
            messageRevision++;
        }
        SessionStore store = sessionStore;
        if (store != null) store.appendMessage(role, content, messageId, turnId, origin);
    }

    /**
     * 本地历史的快照。发请求用的是快照，之后历史怎么改都不影响已经发出去的那次。
     *
     * <h3>为什么不再走"序列化成一根字符串"</h3>
     *
     * <p>早先的实现是：把整份 {@link #messages} {@code toString()} 成一根长字符串缓存起来，
     * 每次用的时候再 {@code new JSONArray(那根字符串)} 解析回来。它同时带来三笔开销：
     *
     * <ol>
     *   <li>常驻一根与历史等长的字符串（Java 的 {@code String} 是 2 字节/字符，
     *       所以是历史字节数的<span>两倍</span>量级）；</li>
     *   <li>历史一变就重新序列化一次整份；</li>
     *   <li><b>每次读取都全量重新解析一遍</b> —— 而它被读得很勤（发请求、上下文估算）。</li>
     * </ol>
     *
     * <p>现在只在锁内逐条拷一层外壳：<b>消息对象</b>各新建一个（提供方与
     * {@code VisionMessageFilter} 会按需改写它自己的那一层的 {@code content} 字段），
     * 而 {@code content} 数组与里面的块按原引用共享 —— 两者都只被读。
     * 这样既保住了"快照"这条语义，又不产生第二棵完整对象树。
     */
    private JSONArray snapshotMessages() {
        synchronized (messageLock) {
            return copyMessageShells(messages, 0, messages.length());
        }
    }

    /**
     * 把 {@code [from, to)} 这一段消息拷成新的外壳，内的 {@code content} 按原引用共享。
     *
     * <p>键的个数是每条消息 2~4 个，所以每条消息只有几个小对象，
     * 与"整份 JSON 文本 + 重新解析"不是一个量级。
     */
    private static JSONArray copyMessageShells(JSONArray source, int from, int to) {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        int start = Math.max(0, from);
        int end = Math.min(source.length(), Math.max(start, to));
        for (int i = start; i < end; i++) {
            JSONObject message = source.optJSONObject(i);
            if (message == null) continue;
            JSONObject shell = new JSONObject();
            java.util.Iterator<String> keys = message.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    shell.put(key, message.opt(key));
                } catch (Exception ignored) {
                    // 单个键写不进去就跳过：结构与类型不符时宁可少一个键，也不要整体失败。
                }
            }
            out.put(shell);
        }
        return out;
    }

    /**
     * 发给提供方的消息。
     *
     * <p>与本地历史的区别只有一个：设置里关掉视觉输入时，图片块会被替换成文字占位。
     * 关掉视觉的场合（模型不支持图片、或者用户不想传图）继续发图片块会被直接拒绝。
     */
    private JSONArray providerMessages() throws Exception {
        JSONArray snapshot = snapshotMessages();
        SessionConfig current = config;
        boolean visionEnabled = current == null || current.visionEnabled;
        return VisionMessageFilter.apply(snapshot, visionEnabled);
    }

    private void appendConfigEvent() {
        try {
            SessionStore store = sessionStore;
            SessionConfig current = config;
            if (store == null || current == null) return;
            store.appendEvent("config", new JSONObject()
                    .put("profile_id", current.profileId)
                    .put("profile_revision", current.profileRevision)
                    .put("credential_revision", current.credentialRevision)
                    .put("model", current.model)
                    .put("protocol", current.protocol)
                    .put("effort", current.effort)
                    .put("vision_enabled", current.visionEnabled)
                    .put("permission_mode", current.permissionMode)
                    .put("sandbox_agent_full_access", current.sandboxAgentFullAccess)
                    .put("root_execution_enabled", current.rootExecutionEnabled)
                    .put("shizuku_execution_enabled", current.shizukuExecutionEnabled)
                    .put("project", current.projectDirectory)
                    .put("workflow_id", current.workflowId));
        } catch (Exception ignored) {
            // config 事件只是让会话「可解释」，写不进去不影响会话本身。
        }
    }

    // ------------------------------------------------------------ 任务订阅

    /**
     * 把任务订阅切到「当前项目 + 当前 workflow」。
     *
     * <p>三道保护缺一不可，因为订阅回调可能比绑定动作晚到：
     * <ul>
     *   <li>用 {@code generation} 而不是只比 key：同一个 key 可能被解绑又立刻绑回来，
     *       那时旧订阅的回调仍在路上；</li>
     *   <li>版本号只允许前进：一次延迟到达的旧快照不能把新快照顶掉；</li>
     *   <li>workflow 不再匹配（会话已经换了）就直接丢弃。</li>
     * </ul>
     */
    private synchronized void bindTaskStore() {
        SessionConfig current = config;
        if (current == null || current.workflowId == null || current.workflowId.trim().isEmpty()) return;

        String key = ProjectPaths.canonicalProject(current.projectDirectory) + "\n" + current.workflowId;
        if (key.equals(boundTaskKey) && taskSubscription != null) return;

        TaskStore.Subscription previous = taskSubscription;
        if (previous != null) previous.unsubscribe();
        taskSubscription = null;
        taskSnapshot = null;
        boundTaskKey = key;
        long generation = ++taskBindingGeneration;

        TaskStore store = new TaskStore(current.projectDirectory, current.workflowId);
        taskSubscription = TaskStore.subscribe(current.projectDirectory, current.workflowId, snapshot -> {
            if (generation != taskBindingGeneration) return;
            if (!key.equals(boundTaskKey)) return;
            if (!current.workflowId.equals(snapshot.workflowId)) return;
            TaskStore.Snapshot latest = taskSnapshot;
            if (latest != null && snapshot.version < latest.version) return;
            publishTaskSnapshot(snapshot);
        });

        TaskStore.Snapshot initial = store.snapshot();
        TaskStore.Snapshot latest = taskSnapshot;
        if (generation == taskBindingGeneration && (latest == null || initial.version >= latest.version)) {
            publishTaskSnapshot(initial);
        }
    }

    private void publishTaskSnapshot(TaskStore.Snapshot snapshot) {
        taskSnapshot = snapshot;
        SessionStore store = sessionStore;
        if (store != null) {
            try {
                store.appendEvent("task_snapshot", taskSnapshotPayload(snapshot));
            } catch (Exception ignored) {
                // 任务快照写不进历史只影响「回放」，界面那边仍然要收到它。
            }
        }
        Listener current = listener;
        if (current != null) current.onTasksChanged(snapshot);
    }

    private static JSONObject taskSnapshotPayload(TaskStore.Snapshot snapshot) throws Exception {
        JSONArray tasks = new JSONArray();
        for (JSONObject task : snapshot.tasks) tasks.put(new JSONObject(task.toString()));
        return new JSONObject()
                .put("workflow_id", snapshot.workflowId)
                .put("version", snapshot.version)
                .put("tasks", tasks);
    }

    public TaskStore.Snapshot getTaskSnapshot() {
        return taskSnapshot;
    }

    // ------------------------------------------------------------ 会话生命周期

    /** 开一次全新的会话：新的 workflow，新的历史文件，界面上的对话随之清空。 */
    public void resetConversation() {
        cancel();
        steering.clear();
        synchronized (messageLock) {
            clearMessagesLocked();
            messageRevision++;
        }
        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        if (config == null) return;
        config.workflowId = UUID.randomUUID().toString();
        config.planWorkflowState = PlanWorkflowState.idle();
        taskState = EngineTaskState.IDLE;
        sessionStore = null;
        bindTaskStore();
    }

    /**
     * 用库里的历史替换内存里的历史。
     *
     * <p>先把「上一次运行」的残留全部清干净再读盘：被取消的那一轮可能留下一个
     * 已经作废的传输会话或执行快照，让它漏进新历史会得到一份自相矛盾的上下文。
     *
     * @param sessionId {@code sessions.db} 里的会话标识（不再是文件路径）
     */
    public void resumeConversation(String sessionId) throws Exception {
        cancel();
        steering.clear();
        activeProvider = null;
        activeTurnConfig = null;
        activeTurnId = "";
        executingTool = false;
        executingModelRequest = false;

        JSONArray restored = SessionStore.loadMessages(sessionId);
        synchronized (messageLock) {
            clearMessagesLocked();
            for (int i = 0; i < restored.length(); i++) messages.put(restored.getJSONObject(i));
            messageRevision++;
        }
        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        sessionStore = SessionStore.resume(sessionId);
        // 恢复出来的历史可能有半截工具链（上次是崩掉的），先自愈一次再给人看。
        repairToolHistory(REPAIR_BEFORE_MESSAGE, true);

        if (config != null) {
            SessionStore.SessionSummary summary = SessionStore.summarize(sessionId);
            if (summary.project != null && !summary.project.trim().isEmpty()
                    && new File(summary.project).isDirectory()) {
                config.projectDirectory = summary.project;
            }
            config.workflowId = SessionStore.loadWorkflowId(sessionId);
            config.planWorkflowState = SessionStore.loadPlanState(sessionId);
            if (config.planWorkflowState.isAwaitingApproval()) {
                // 审批是一次「当下」的对话。应用重启之后没有人还在等这个答复，
                // 把状态停在 AWAITING_APPROVAL 会让界面显示一个永远等不到回应的按钮。
                config.planWorkflowState = config.planWorkflowState
                        .keepPlanning("审批在应用中断时失效，请检查计划并重新提交审批。");
                appendPlanEvent("plan_revision_requested", config.planWorkflowState);
            }
            config.renewTransportSession();
            bindTaskStore();
            Listener current = listener;
            if (current != null) current.onPlanStateChanged(config.planWorkflowState.copy());
        }
        appendConfigEvent();
    }

    /**
     * 取消当前会话。
     *
     * <p>四件事都要做：放掉所有等待中的询问（否则界面会留着一个永远不响应的对话框）、
     * 打断 agent 线程、让提供方关掉正在读的流、打断可能正在跑的压缩线程。
     * 只做其中一部分的话，表现是「点了停止但还在跑」或者「停止后卡住不动」。
     */
    public void cancel() {
        steering.clear();
        permissionGate.cancelAll();
        questionGate.cancelAll();
        planApprovalGate.cancelAll();

        if (busy.get()) setTaskState(EngineTaskState.CANCELLING);
        Thread worker = agentThread;
        ModelProvider provider = activeProvider;
        Future<?> running = activeRun;
        if (running != null) running.cancel(true);
        if (worker != null) worker.interrupt();
        if (provider != null) provider.cancelRequest(worker);

        Thread compacting = manualCompactionThread;
        if (compacting != null) compacting.interrupt();
    }

    public synchronized void shutdown() {
        cancel();
        taskBindingGeneration++;
        boundTaskKey = "";
        if (taskSubscription != null) {
            taskSubscription.unsubscribe();
            taskSubscription = null;
        }
        executor.shutdownNow();
    }

    /** org.json 的 JSONArray 没有 clear()。 */
    private void clearMessagesLocked() {
        while (messages.length() > 0) messages.remove(messages.length() - 1);
    }

    /** 整体替换本地历史。调用方必须持有 {@link #messageLock}。 */
    private void replaceMessagesLocked(JSONArray replacement) throws Exception {
        clearMessagesLocked();
        for (int i = 0; i < replacement.length(); i++) messages.put(replacement.getJSONObject(i));
        messageRevision++;
    }

    // ------------------------------------------------------------ 上下文用量

    /**
     * 估算当前上下文占多少 token。
     *
     * <p>优先用真实测量值：最近一次 API 响应报出的 input tokens 已经把系统提示词与
     * 工具 schema 算进去了 —— 那两块恰恰是纯按字数估算永远算不准的。所以有了测量值之后，
     * 只对「那次响应之后新增的消息」做估算，再把它加到测量值上。
     *
     * <h3>为什么读的是"活的"历史，而不是一份快照</h3>
     *
     * <p>这个函数是**只读**的。早先它却先 {@code messages.toString()}、再
     * {@code new JSONArray(...)} 解析一整套回来，纯属白付两次全量开销 ——
     * 而它被读得很勤（打开会话、每轮开始/结束都会读），在 256MB 堆的设备上
     * 单次就能触发 OOM。现在直接在 {@link #messageLock} 内读 {@link #messages}：
     * 为了拿到一致的长度与内容，读写都在同一把锁下完成。
     *
     * <h3>为什么结果要按 key 缓存</h3>
     * 即便不再重建整棵树，估算仍要遍历一遍相关消息（{@code roughTokens}）。
     * 而一次都没变的历史会被四处调用点反复重算。所以按「影响结果的每一项」做 key：
     * 历史版本、是否启用视觉输入、测量值及其对应的消息条数、以及配置版本。
     * 任一变化才重算，否则直接返回上次结果。这也是**纯缓存**：估算口径与缓存前逐字一致。
     */
    public int estimateContextTokens() {
        String key = estimateCacheKey();
        if (key.equals(cachedEstimateKey)) {
            int cached = cachedEstimateTokens;
            if (cached >= 0) return cached;
        }
        int value = computeContextTokens();
        cachedEstimateTokens = value;
        cachedEstimateKey = key;
        return value;
    }

    /** 估算结果的全部输入。小字符串，构造它是廉价的（相比重算整份历史）。 */
    private String estimateCacheKey() {
        SessionConfig current = config;
        return messageRevision
                + "|" + (current == null || current.visionEnabled)
                + "|" + lastMeasuredContextTokens
                + "|" + lastMeasuredMessageCount
                + "|" + configRevision;
    }

    private int computeContextTokens() {
        long measuredTokens = lastMeasuredContextTokens;
        int measuredMessages = lastMeasuredMessageCount;
        SessionConfig current = config;
        boolean visionEnabled = current == null || current.visionEnabled;

        int total;
        int appended = 0;
        int everything = 0;
        boolean measurementUsable;
        synchronized (messageLock) {
            total = messages.length();
            measurementUsable = measuredTokens >= 0 && measuredMessages >= 0 && measuredMessages <= total;
            if (measurementUsable) {
                // 只算「测量那次之后新增的」——这一段通常只有几条，与整份历史无关。
                appended = ContextCompactor.roughTokens(messages, measuredMessages, total);
            } else {
                everything = ContextCompactor.roughTokens(messages);
            }
        }

        if (!visionEnabled) {
            // 关掉视觉时，实际发出去的报文里图片块会被换成一行文字占位，而本地历史里
            // 还是图片块（每张按固定的 IMAGE_TOKENS 计）。不换成同一套口径，
            // 估算会与真实请求差出「每张图 1200 token」那一块 —— 而估算正是自动压缩阈值的输入。
            //
            // 只对**增量那一段**做替换：测量值覆盖的部分是提供方按它自己收到的报文算的，
            // 已经是"换过占位"的口径了。代价与新增消息条数成正比。
            appended += visionPlaceholderDelta(measuredMessages, total, measurementUsable);
        }

        if (measurementUsable) return clampTokens(measuredTokens + appended);
        return clampTokens(estimateWithoutMeasurement(everything));
    }

    /**
     * 「关掉视觉」之后那一段的口径差：占位文本的 token 数 − 图片块按 {@code IMAGE_TOKENS} 计的固定值。
     *
     * <p>只拷**那一段**消息的外壳，且 {@code VisionMessageFilter} 只会替换含图片的那几条的
     * content 数组（不含图片的整条按原引用共享）—— 所以不会像整份拷贝那样把图片 base64 又抄一遍。
     *
     * <p>同一份拷出来的数据算两次：换占位之前、之后。两者之差就是这段的口径差。
     * 这也正是"不去动整份历史"的原因 —— 前面已经按测量值算过的部分不该被重算。
     */
    private int visionPlaceholderDelta(int from, int total, boolean onlyTail) {
        int start = onlyTail ? Math.max(0, from) : 0;
        JSONArray tail;
        try {
            synchronized (messageLock) {
                tail = copyMessageShells(messages, start, total);
            }
        } catch (Exception ignored) {
            return 0;
        }
        int before = ContextCompactor.roughTokens(tail);
        try {
            VisionMessageFilter.apply(tail, false);
        } catch (Exception ignored) {
            return 0;
        }
        return ContextCompactor.roughTokens(tail) - before;
    }

    /** 没有测量值时的兜底估算：消息 + 系统提示词 + 工具 schema。 */
    private long estimateWithoutMeasurement(int messageTokens) {
        long estimated = messageTokens;
        SessionConfig current = config;
        if (current == null) return estimated;
        try {
            estimated += ContextCompactor.roughTextTokens(buildSystemPrompt(current));
            estimated += Math.max(0, effectiveToolSchemas().toString().length() / 2L);
        } catch (Exception ignored) {
            // 提示词或 schema 取不到时只算消息部分：估算偏小远好过抛异常。
        }
        return estimated;
    }

    private static int clampTokens(long tokens) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, tokens));
    }

    /** 上下文占用百分比。上限 999 而不是 100：超过窗口这件事本身就值得显示出来。 */
    public int contextPercent() {
        SessionConfig current = config;
        int window = current == null
                ? DEFAULT_CONTEXT_WINDOW_TOKENS
                : Math.max(MIN_CONTEXT_WINDOW_TOKENS, current.contextWindowTokens);
        return Math.min(999, (int) Math.round(estimateContextTokens() * 100.0 / window));
    }

    public int autoCompactThresholdTokens() {
        return ContextCompactor.autoCompactThreshold(config);
    }

    /**
     * 列出已启用的 MCP 服务器及其工具清单（界面「测试连接」用）。
     *
     * <p>⚠️ 这是**阻塞且昂贵**的：每台服务器都要真的起一次子进程或发一轮 HTTP，
     * 单台超时 30 秒。调用方必须放在后台线程，且只能由用户动作触发 ——
     * 打开设置页就自动跑一遍，等于把"看配置"变成"把每台服务器都启动一次"。
     *
     * <p>不会因为某台失败就整体失败：那一行会带 {@code ok:false} 与原因
     * （见 {@code McpRuntime.listServers}）。
     */
    public JSONArray mcpServers() {
        return tools.mcpRuntime().listServers();
    }

    public int effectiveContextWindowTokens() {
        return ContextCompactor.effectiveContextWindow(config);
    }

    /** 有没有可用的真实测量值。界面据此决定显示「约」还是精确值。 */
    public boolean hasMeasuredContextUsage() {
        synchronized (messageLock) {
            return lastMeasuredContextTokens >= 0 && lastMeasuredMessageCount >= 0
                    && lastMeasuredMessageCount <= messages.length();
        }
    }

    /**
     * 内存里这份历史的规模 —— **只给诊断日志用**。
     *
     * <p>{@link #historyChars} 报的是"如果把整份历史序列化成 JSON 会有多长"，
     * 也就是常驻那棵树的最直观的量级。它**会走一次全量 {@code toString()}**，
     * 所以只能由人在关键节点上调（打开/恢复会话），绝不可以放进任何循环或逐帧路径 ——
     * 那正是本类这轮要消灭的那个开销。
     */
    public int messageCount() {
        synchronized (messageLock) {
            return messages.length();
        }
    }

    /** 见 {@link #messageCount()} 的说明：这是一次全量序列化，只给诊断用。 */
    public long historyChars() {
        try {
            synchronized (messageLock) {
                return messages.toString().length();
            }
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * 记下一次 API 报出的用量。
     *
     * <p>顺手写一条 {@code context_usage} 事件：界面重新打开会话时靠它恢复「当前上下文」
     * 那一行，而不必把它跟累计计费、或者本次输出 token 数混起来。
     * {@code source} 字段说明这个数字是测量来的还是估算来的 —— 两者看起来一样，
     * 但只有前者能用来判断「是不是快满了」。
     */
    private void recordMeasuredUsage(long inputTokens, long outputTokens) {
        boolean apiMeasured = inputTokens > 0;
        if (apiMeasured) {
            lastMeasuredContextTokens = Math.max(0L, inputTokens + Math.max(0L, outputTokens));
            synchronized (messageLock) {
                lastMeasuredMessageCount = messages.length();
            }
        }

        SessionStore store = sessionStore;
        SessionConfig current = config;
        if (store == null || current == null) return;
        try {
            store.appendEvent("context_usage", new JSONObject()
                    .put("context_tokens", estimateContextTokens())
                    .put("context_window_tokens", Math.max(MIN_CONTEXT_WINDOW_TOKENS, current.contextWindowTokens))
                    .put("input_tokens", Math.max(0L, inputTokens))
                    .put("output_tokens", Math.max(0L, outputTokens))
                    .put("source", apiMeasured ? "api" : "estimate")
                    .put("model", current.model == null ? "" : current.model));
        } catch (Exception ignored) {
            // 用量标记纯属显示用。
        }
    }

    /** 测量值失效。任何改动过历史的动作之后都必须调用它。 */
    private void invalidateMeasuredUsage() {
        lastMeasuredContextTokens = -1;
        lastMeasuredMessageCount = -1;
    }

    // ------------------------------------------------------------ 预输入

    public int pendingSteeringCount() {
        return steering.size();
    }

    public boolean steerPrompt(String prompt, JSONArray extraContent) {
        return steerPrompt(prompt, extraContent, "");
    }

    public boolean steerPrompt(String prompt) {
        return steerPrompt(prompt, null, "");
    }

    /**
     * 排队一条预输入，等下一个协议安全边界再写进历史。
     *
     * <p>空闲时返回 false 而不是直接发送：那样会让「打字」和「发送」两种动作混在一起，
     * 调用方（界面）反而没法决定该显示成排队还是正常发送。
     */
    public boolean steerPrompt(String prompt, JSONArray extraContent, String messageId) {
        boolean empty = (prompt == null || prompt.trim().isEmpty())
                && (extraContent == null || extraContent.length() == 0);
        if (empty || !busy.get()) return false;

        SteeringQueue.Pending pending = new SteeringQueue.Pending(prompt, extraContent, messageId);
        steering.enqueue(pending);

        SessionStore store = sessionStore;
        if (store != null) {
            try {
                store.appendEvent("user_steer_queued", new JSONObject()
                        .put("queued_at", pending.queuedAt)
                        .put("text_length", pending.prompt.length())
                        .put("attachment_blocks", pending.extraContent.length()));
            } catch (Throwable ignored) {
                // 排队事件只是留痕。
            }
        }
        reportStatus(executingTool
                ? "已预输入，将在当前工具完成后处理…"
                : executingModelRequest
                        ? "已预输入，将在当前回复完成后处理…"
                        : "已预输入，将在当前步骤完成后处理…");
        return true;
    }

    private boolean hasPendingSteering() {
        return !steering.isEmpty();
    }

    /**
     * 把排队的预输入写进历史。
     *
     * <p>{@code boundary} 只写进事件里，用来解释「这条输入是在哪一步被应用的」——
     * 排查「为什么我的纠正在两个工具之间才生效」时，这个字段就是答案。
     */
    private int drainSteeringPrompts(String boundary) throws Exception {
        List<SteeringQueue.Pending> drained = steering.drain();
        for (SteeringQueue.Pending pending : drained) {
            appendMessage("user", buildUserContent(pending.prompt, pending.extraContent),
                    pending.messageId, activeTurnId, ORIGIN_HUMAN);
            SessionStore store = sessionStore;
            if (store == null) continue;
            try {
                store.appendEvent("user_steer_applied", new JSONObject()
                        .put("queued_at", pending.queuedAt)
                        .put("applied_at", System.currentTimeMillis())
                        .put("boundary", boundary));
            } catch (Throwable ignored) {
                // 应用事件只是留痕。
            }
        }
        if (drained.isEmpty()) return 0;
        Listener current = listener;
        if (current != null) {
            current.onQueuedPromptApplied();
            current.onStatus("已加载 " + drained.size() + " 条预输入，正在继续…");
        }
        return drained.size();
    }

    // ------------------------------------------------------------ 主循环入口

    public void sendPrompt(String prompt) {
        sendPrompt(prompt, null, "");
    }

    /** 发送一条带额外内容块（例如图片）的输入。内容块要先按提供方格式归一化好。 */
    public void sendPrompt(String prompt, JSONArray extraContent) {
        sendPrompt(prompt, extraContent, "");
    }

    public void sendPrompt(String prompt, JSONArray extraContent, String messageId) {
        boolean empty = (prompt == null || prompt.trim().isEmpty())
                && (extraContent == null || extraContent.length() == 0);
        if (empty) return;
        if (config == null) throw new IllegalStateException("Engine is not configured");
        ensureSessionStore();

        JSONArray extras = extraContent == null ? new JSONArray() : cloneArray(extraContent);
        if (!busy.compareAndSet(false, true)) {
            // 已经在跑：这不算错误，用户就是在打字。排队，等边界。
            if (steerPrompt(prompt, extras)) return;
            throw new IllegalStateException("ZhiCode is already working");
        }

        SessionConfig turnConfig = config.copy();
        String turnId = UUID.randomUUID().toString();
        activeTurnConfig = turnConfig;
        activeTurnId = turnId;
        String rootMessageId = messageId == null || messageId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : messageId;
        setTaskState(EngineTaskState.MODEL);
        activeRun = executor.submit(() ->
                runAgent(prompt == null ? "" : prompt, extras, turnConfig, turnId, rootMessageId));
    }

    /** 主循环里跨轮次保留的计数。 */
    private static final class TurnState {
        final SessionConfig turnConfig;
        final String systemPrompt;
        int turn;
        int emptyResponses;

        TurnState(SessionConfig turnConfig, String systemPrompt) {
            this.turnConfig = turnConfig;
            this.systemPrompt = systemPrompt;
        }
    }

    /**
     * 一次会话的入口。异常处理是这个方法的重点：
     * <ul>
     *   <li>取消（线程中断）不是错误 —— 不报 {@code onError}，只报「已取消」；</li>
     *   <li>被打断包装过的异常同样按取消处理，否则取消会弹一个吓人的错误；</li>
     *   <li>其它任何失败都必须报出去，并且先修一次历史，让会话仍然可继续。</li>
     * </ul>
     */
    private void runAgent(String prompt, JSONArray extraContent, SessionConfig turnConfig, String turnId,
                          String rootMessageId) {
        long startedAt = System.currentTimeMillis();
        agentThread = Thread.currentThread();
        acquireTaskWakeLock();
        try {
            Listener current = listener;
            if (current != null) current.onSessionStarted(turnConfig.copy());

            // 先修历史再追加本轮发言：合成出来的「结果不可用」要留在原来那条调用旁边，
            // 否则它会跨过半条人类发言，看起来像是新问题引起的。
            repairToolHistory(REPAIR_BEFORE_MESSAGE, true);
            ensureSessionStore();
            appendTurnHeader(turnConfig, turnId);
            appendMessage("user", buildUserContent(prompt, extraContent), rootMessageId, turnId, ORIGIN_HUMAN);

            maybeAutoCompact();
            drainSteeringPrompts("before-first-model");
            boolean finished = runTurnLoop(turnConfig);
            appendEventSafely("turn_started", new JSONObject().put("turn_id", turnId)
                    .put("prompt_chars", prompt == null ? 0 : prompt.length()));
            if (!finished) {
                throw new IllegalStateException("Maximum agent turn count reached (" + turnConfig.maxAgentTurns
                        + "). The task did not reach a stable completion state; use /compact or continue the task.");
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            reportCancelledTurn();
        } catch (Throwable failure) {
            if (isInterruptedFailure(failure)) {
                reportCancelledTurn();
                return;
            }
            repairToolHistory(FAILED_TOOL_REASON, true);
            setTaskState(EngineTaskState.FAILED);
            appendFailureEvent(failure);
            Listener current = listener;
            if (current != null) {
                current.onError(failure.getMessage() == null ? failure.toString() : failure.getMessage(), failure);
            }
        } finally {
            appendEventSafely("turn_finished", safeTimingPayload(turnId, startedAt));
            finishTurn();
        }
    }

    private JSONObject safeTimingPayload(String turnId, long startedAt) {
        try {
            return new JSONObject().put("turn_id", turnId == null ? "" : turnId)
                    .put("duration_ms", Math.max(0L, System.currentTimeMillis() - startedAt))
                    .put("state", taskState.name());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void appendTurnHeader(SessionConfig turnConfig, String turnId) {
        SessionStore store = sessionStore;
        if (store == null) return;
        store.appendProfileBinding(turnConfig);
        store.appendTurnConfig(turnConfig, turnId);
    }

    private void appendFailureEvent(Throwable failure) {
        try {
            JSONObject payload = new JSONObject()
                    .put("turn_id", activeTurnId)
                    .put("error_type", failure == null ? "Unknown" : failure.getClass().getSimpleName())
                    .put("error", failure == null || failure.getMessage() == null
                            ? String.valueOf(failure) : failure.getMessage());
            appendEventSafely("turn_failed", payload);
        } catch (Throwable ignored) {
            // 诊断记录不能遮盖原始失败。
        }
    }

    private void appendEventSafely(String type, JSONObject payload) {
        SessionStore store = sessionStore;
        if (store == null || type == null || payload == null) return;
        try {
            store.appendEvent(type, payload);
        } catch (Throwable ignored) {
            // 诊断事件不能遮盖原始失败，也不能让错误恢复路径再次失败。
        }
    }

    private void reportCancelledTurn() {
        repairToolHistory(CANCEL_TOOL_REASON, true);
        try {
            appendEventSafely("turn_cancelled", new JSONObject().put("turn_id", activeTurnId)
                    .put("pending_steering", steering.size()));
        } catch (Throwable ignored) {
            // 取消本身不能因诊断事件失败而失败。
        }
        Listener current = listener;
        if (current == null) return;
        current.onStatus("Cancelled");
        current.onTurnComplete("cancelled");
    }

    /** 一轮结束后的收尾。任何残留状态都必须在这里清掉：取消后再开一次会话要一切如初。 */
    private void finishTurn() {
        executingTool = false;
        executingModelRequest = false;

        ModelProvider provider = activeProvider;
        Thread worker = agentThread;
        activeProvider = null;
        if (provider != null) provider.cancelRequest(worker);
        agentThread = null;

        steering.clearInterrupts();
        // 中断位可能被「取消」留下。留着它会让下一次会话的第一个动作立刻抛 InterruptedException。
        Thread.interrupted();
        releaseTaskWakeLock();
        activeTurnConfig = null;
        activeTurnId = "";
        busy.set(false);
        taskState = EngineTaskState.IDLE;
        activeRun = null;
    }

    /**
     * 主循环。
     *
     * @return true 表示模型正常收尾；false 表示用满了 {@code maxAgentTurns}
     */
    private boolean runTurnLoop(SessionConfig turnConfig) throws Exception {
        TurnState state = new TurnState(turnConfig, buildSystemPrompt(turnConfig));
        while (state.turn < turnConfig.maxAgentTurns) {
            if (runOneTurn(state)) return true;
            state.turn++;
        }
        return false;
    }

    /**
     * 走一轮：请模型回一条消息，然后要么收尾，要么执行它要的工具。
     *
     * @return true 表示这次会话结束了
     */
    private boolean runOneTurn(TurnState state) throws Exception {
        if (steering.takeToolInterrupt()) Thread.interrupted();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
        drainSteeringPrompts("before-model");
        reportStatus(state.turn == 0 ? "Thinking…" : "Continuing after tool results…");

        SessionConfig requestConfig = state.turnConfig.copy();
        ModelProvider provider = providerOverride != null
                ? providerOverride
                : ModelProviders.forConfig(requestConfig);
        repairToolHistory(REPAIR_BEFORE_REQUEST, true);

        StringBuilder streamedText = new StringBuilder();
        AssistantTurn assistant;
        executingModelRequest = true;
        setTaskState(EngineTaskState.MODEL);
        activeProvider = provider;
        try {
            assistant = requestModelWithRetry(requestConfig, provider, state.systemPrompt,
                    streamForwarder(streamedText), streamedText);
        } catch (Exception requestFailure) {
            if (steering.takeModelInterrupt() && hasPendingSteering()) {
                // 这一轮的回复作废，但已经流出来的文字要留住 —— 用户看到过它。
                Thread.interrupted();
                appendInterruptedAssistantText(streamedText);
                Listener current = listener;
                if (current != null) current.onResponseInterruptedBySteering();
                drainSteeringPrompts("model-interrupted");
                maybeAutoCompact();
                return false;
            }
            if (Thread.currentThread().isInterrupted() || isInterruptedFailure(requestFailure)) {
                throw new InterruptedException("Cancelled");
            }
            throw requestFailure;
        } finally {
            activeProvider = null;
            executingModelRequest = false;
        }

        if (steering.takeModelInterrupt()) Thread.interrupted();
        appendMessage("assistant", assistant.content);
        recordMeasuredUsage(assistant.inputTokens, assistant.outputTokens);

        if (assistant.toolCalls.isEmpty()) return finishAssistantTurn(state, assistant);
        return runToolPhase(assistant);
    }

    /**
     * 模型这一轮没有要工具调用。可能是正常收尾，也可能是被截断、空回复、或者用户
     * 在回复过程中发来了纠正 —— 后三种都要继续跑，不能就此结束。
     */
    private boolean finishAssistantTurn(TurnState state, AssistantTurn assistant) throws Exception {
        if (hasPendingSteering()) {
            drainSteeringPrompts("after-model");
            maybeAutoCompact();
            return false;
        }
        if (shouldContinueAfterStopReason(assistant.stopReason)) {
            appendInternalContinuation(assistant.stopReason);
            reportStatus("模型输出被截断，正在自动续接…");
            return false;
        }
        if (!hasMeaningfulAssistantContent(assistant.content)) {
            state.emptyResponses++;
            if (state.emptyResponses <= MAX_EMPTY_RESPONSE_RECOVERIES) {
                appendInternalContinuation("empty_end_turn");
                reportStatus("模型提前空结束，正在自动恢复…");
                return false;
            }
            throw new IllegalStateException(
                    "模型连续 3 次空响应，已停止自动重试以避免无休止循环。请检查 API/模型状态后继续。 ");
        }
        state.emptyResponses = 0;

        // 正常收尾前再查一次预输入：用户的补充可能刚好落在这一瞬间。
        // 不查的话，那条输入会被当成下一轮的开头，而这一轮已经报了结束。
        if (hasPendingSteering()) {
            drainSteeringPrompts("completion-race");
            return false;
        }
        finishPlanExecutionIfNeeded();
        Listener current = listener;
        if (current != null) {
            current.onTurnComplete(assistant.stopReason == null ? "end_turn" : assistant.stopReason);
        }
        return true;
    }

    /**
     * 执行这一轮的全部工具调用。
     *
     * <p>{@code onToolBatchCompleted} 放在 {@code finally} 里：界面用它收起那一批的进度显示。
     * 某条工具抛异常时，若不收起来，那个进度条会一直转下去。
     */
    private boolean runToolPhase(AssistantTurn assistant) throws Exception {
        ToolBatch batch = buildToolBatch(assistant);
        Listener batchListener = listener;
        if (batchListener != null) batchListener.onToolBatchStarted(batch);
        try {
            // 用户可能在模型流式输出期间就发来了纠正。那份「已经过期」的回复里的工具调用
            // 仍然必须各自收到配对的 tool_result（协议要求），但绝不能真的执行。
            if (hasPendingSteering()) {
                appendSkippedToolResults(assistant.toolCalls, STALE_RESPONSE_REASON);
                drainSteeringPrompts("after-model");
                maybeAutoCompact();
                return false;
            }

            runToolCalls(assistant.toolCalls);

            if (steering.takeToolInterrupt()) Thread.interrupted();
            if (hasPendingSteering()) {
                drainSteeringPrompts("after-tool");
                maybeAutoCompact();
                return false;
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
            maybeAutoCompact();
            return false;
        } finally {
            if (batchListener != null) batchListener.onToolBatchCompleted(batch);
        }
    }

    /**
     * 依次执行工具，并把结果拼成一条 user 消息写回历史。
     *
     * <p>两个顺序是协议要求，不能动：
     * <ol>
     *   <li>工具结果排在前面，额外内容（例如模型专用的大段输出）排在后面 —— Anthropic 要求如此；</li>
     *   <li>即使后面的工具被取消，已经跑完的结果也必须写回去。丢掉它们等于让模型以为
     *       那些工具从没被调用过，它会重新做一遍（可能是有副作用的操作）。</li>
     * </ol>
     */
    private void runToolCalls(List<ToolCall> calls) throws Exception {
        JSONArray toolResults = new JSONArray();
        JSONArray additionalToolContent = new JSONArray();
        try {
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                if (steering.takeToolInterrupt()) Thread.interrupted();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                if (hasPendingSteering()) {
                    appendSkippedToolResults(toolResults, calls, index, TOOL_NOT_STARTED_REASON);
                    break;
                }

                Listener current = listener;
                if (current != null) current.onToolUse(call);
                ToolExecutionResult result;
                executingTool = true;
                setTaskState(EngineTaskState.TOOL);
                try {
                    result = executeTool(call);
                } finally {
                    executingTool = false;
                }
                persistToolDiff(call, result);
                if (current != null) current.onToolResult(call, result);

                JSONObject block = new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", call.id)
                        .put("content", result.content);
                if (result.isError) block.put("is_error", true);
                toolResults.put(block);

                JSONArray extra = result.additionalContent();
                for (int i = 0; i < extra.length(); i++) additionalToolContent.put(extra.get(i));

                if (hasPendingSteering()) {
                    appendSkippedToolResults(toolResults, calls, index + 1, TOOL_INTERRUPTED_REASON);
                    break;
                }
            }
        } finally {
            executingTool = false;
            if (busy.get() && !Thread.currentThread().isInterrupted()) setTaskState(EngineTaskState.MODEL);
            if (toolResults.length() > 0) {
                for (int i = 0; i < additionalToolContent.length(); i++) {
                    toolResults.put(additionalToolContent.get(i));
                }
                appendMessage("user", toolResults);
            }
        }
    }

    /** 把一次回复切成「批」：夹在工具调用之间的正文表示分批。 */
    private ToolBatch buildToolBatch(AssistantTurn assistant) {
        Set<String> breakBefore = new LinkedHashSet<>();
        boolean pendingBoundary = false;
        for (JSONObject block : JsonItems.of(assistant.content)) {
            if ("tool_use".equals(block.optString("type"))) {
                String id = block.optString("id", "");
                if (pendingBoundary && !id.isEmpty()) breakBefore.add(id);
                pendingBoundary = false;
                continue;
            }
            if (isToolBatchBoundary(block)) pendingBoundary = true;
        }
        return new ToolBatch(++nextToolBatchId, assistant.toolCalls, breakBefore);
    }

    /** 这个块是不是「一批工具的分界」。空正文不算分界，否则会被空段落切出无意义的批。 */
    private static boolean isToolBatchBoundary(JSONObject block) {
        String type = block.optString("type", "");
        if (type.isEmpty()) return false;
        if ("text".equals(type) || "output_text".equals(type)) {
            return !block.optString("text", "").trim().isEmpty();
        }
        if ("thinking".equals(type) || "reasoning".equals(type)) {
            String value = block.optString("thinking", block.optString("text", ""));
            return !value.trim().isEmpty();
        }
        return true;
    }

    // ------------------------------------------------------------ 模型请求

    /**
     * 发一次模型请求，并处理两种「值得重试一次」的失败。
     *
     * <p>两种重试各只允许一次，而且原因不同：
     * <ul>
     *   <li><b>工具链被拒</b>：上下文里有一条比对不上的调用/结果。先自愈再重发一次；
     *       自愈没改动任何东西就说明不是这个原因，直接抛。</li>
     *   <li><b>传输被掐断</b>：连接断了、网关 5xx。同一个请求原样重发一次是安全的，
     *       但仅限「可以安全重放」的配置 —— 有些提供方的原生工具模式重放会重复计费或重复执行。</li>
     * </ul>
     * 重发之前必须清空已经流出来的文字：那半截内容属于失败的那次请求，留着会和新响应拼在一起。
     */
    private AssistantTurn requestModelWithRetry(SessionConfig requestConfig, ModelProvider provider, String system,
                                                StreamListener streamListener, StringBuilder streamedText)
            throws Exception {
        boolean chainRepaired = false;
        boolean transportRetried = false;
        while (true) {
            try {
                return provider.createMessage(requestConfig, system, providerMessages(), effectiveToolSchemas(),
                        streamListener);
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted() || isInterruptedFailure(failure)) throw failure;

                if (!chainRepaired && isToolHistoryMismatch(failure)
                        && repairToolHistory(REPAIR_REJECTED_CHAIN, true) > 0) {
                    chainRepaired = true;
                    reportStatus("修复工具调用上下文后重试…");
                    continue;
                }
                if (!transportRetried && canReplayModelRequest(requestConfig)
                        && isRetryableTransportFailure(failure)) {
                    transportRetried = true;
                    streamedText.setLength(0);
                    Listener current = listener;
                    if (current != null) {
                        current.onResponseRetry();
                        current.onStatus("连接中断，正在重试模型请求…");
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                    try {
                        Thread.sleep(TRANSPORT_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    continue;
                }
                throw failure;
            }
        }
    }

    /**
     * 这次请求能不能原样重放。
     *
     * <p>原生工具模式（{@code toolMode=native}）下，重放可能让服务端把同一批工具调用
     * 执行第二遍；只有那种「请求根本没落地」的失败才敢重放。这里按协议保守判断。
     */
    private static boolean canReplayModelRequest(SessionConfig config) {
        if (config == null) return true;
        return !"codex-responses".equals(config.protocol) || !"native".equals(config.toolMode);
    }

    /** 一次网络失败是否值得原样重放：连接被掐断、网关临时不可用。 */
    private static boolean isRetryableTransportFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StreamFailure) {
                String code = ((StreamFailure) current).code.toLowerCase(Locale.US);
                if (REPLAYABLE_FAILURE_CODES.contains(code)) return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.US);
            for (String marker : REPLAYABLE_FAILURE_MARKERS) {
                if (message.contains(marker)) return true;
            }
        }
        return false;
    }

    /**
     * 提供方是不是在说「工具调用链对不上」。
     *
     * <p>这条判定必须只看文案：各家返回的是 400 加一句英文说明，没有结构化错误码。
     * 所以这些子串属于外部契约，不能跟着界面文案一起翻译。
     */
    private static boolean isToolHistoryMismatch(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(Locale.US);
        for (String marker : TOOL_CHAIN_MISMATCH_MARKERS) {
            if (message.contains(marker)) return true;
        }
        return message.contains("function_call_output") && message.contains("call_id");
    }

    /**
     * 这次失败是不是「被打断」的换皮说法。
     *
     * <p>取消操作会让底层库抛出一堆与中断无关的异常（socket 关闭、读被中止）。
     * 不识别它们的话，用户点「停止」会看到一个红色错误。
     */
    private static boolean isInterruptedFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) return true;
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.US);
            if (message.contains("thread interrupted") || message.equals("interrupted")
                    || message.contains("operation interrupted")) {
                return true;
            }
        }
        return false;
    }

    /** 把流式输出转发给界面的同时累积成完整文本（重发之前要清空重来）。 */
    private StreamListener streamForwarder(StringBuilder streamedText) {
        return new StreamListener() {
            @Override public void onTextDelta(String text) {
                if (text != null) streamedText.append(text);
                Listener current = listener;
                if (current != null) current.onTextDelta(text);
            }

            @Override public void onThinkingDelta(String thinking) {
                Listener current = listener;
                if (current != null) current.onThinkingDelta(thinking);
            }

            @Override public void onToolInputDelta(String id, String name, String partialJson) {
                // 工具入参的分片对界面没有意义：完整的入参要等这一轮的解析结果。
            }

            @Override public void onUsage(long inputTokens, long outputTokens) {
                Listener current = listener;
                if (current != null) current.onUsage(inputTokens, outputTokens);
            }
        };
    }

    private JSONArray buildUserContent(String prompt, JSONArray extraContent) throws Exception {
        JSONArray content = new JSONArray();
        if (prompt != null && !prompt.isEmpty()) {
            content.put(new JSONObject().put("type", "text").put("text", prompt));
        }
        if (extraContent != null) {
            for (int i = 0; i < extraContent.length(); i++) content.put(extraContent.get(i));
        }
        return content;
    }

    /**
     * 模型被截断时补一条「接着做」的内部指令。
     *
     * <p>这段文本会写进持久化历史，所以标记名取自 {@link SessionStore} 的单一来源：
     * 那边靠它把续跑指令从「人类发言」里排除掉（否则它会变成会话标题，
     * 还会出现在可编辑的用户消息列表里）。两处各写一份字面量，改一处就会出现
     * 只在旧会话上复现的问题。
     */
    private void appendInternalContinuation(String stopReason) throws Exception {
        String reason = stopReason == null ? "unknown" : stopReason;
        JSONArray content = new JSONArray().put(new JSONObject()
                .put("type", "text")
                .put("text", SessionStore.internalContinuationText(reason)));
        appendMessage("user", content);
    }

    /** 被用户补话打断时，把已经流出来的半截回复留下 —— 用户看到过它，不能凭空消失。 */
    private void appendInterruptedAssistantText(StringBuilder streamedText) throws Exception {
        if (streamedText == null) return;
        String partial = streamedText.toString();
        if (partial.trim().isEmpty()) return;
        appendMessage("assistant", new JSONArray().put(new JSONObject()
                .put("type", "text")
                .put("text", partial)));
    }

    private static boolean hasMeaningfulAssistantContent(JSONArray content) {
        if (content == null || content.length() == 0) return false;
        for (JSONObject block : JsonItems.of(content)) {
            String type = block.optString("type", "");
            if ("tool_use".equals(type)) return true;
            if ("text".equals(type) && !block.optString("text", "").trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 这个结束原因是不是「输出被截断了」。
     *
     * <p>不同提供方用不同名字表示同一件事，所以这里是一张名单而不是一次比较。
     * 漏掉任何一个，表现都是「话说了一半就结束了」，而模型自己不会说它没说完。
     */
    private static boolean shouldContinueAfterStopReason(String stopReason) {
        String reason = stopReason == null ? "" : stopReason.trim().toLowerCase(Locale.US);
        return reason.equals("max_tokens")
                || reason.equals("max_output_tokens")
                || reason.equals("length")
                || reason.equals("incomplete")
                || reason.equals("output_limit")
                || reason.equals("token_limit");
    }

    /** 补写「因为用户发来纠正而未执行」的结果块，让工具调用仍有配对。 */
    private void appendSkippedToolResults(List<ToolCall> calls, String reason) throws Exception {
        JSONArray results = new JSONArray();
        appendSkippedToolResults(results, calls, 0, reason);
        if (results.length() > 0) appendMessage("user", results);
    }

    private void appendSkippedToolResults(JSONArray target, List<ToolCall> calls, int start, String reason)
            throws Exception {
        for (int i = Math.max(0, start); i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            ToolExecutionResult result = ToolExecutionResult.error(reason);
            Listener current = listener;
            if (current != null) {
                current.onToolUse(call);
                current.onToolResult(call, result);
            }
            target.put(new JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", call.id)
                    .put("content", result.content)
                    .put("is_error", true));
        }
    }

    // ------------------------------------------------------------ 工具执行

    /**
     * 执行一次工具调用。
     *
     * <p>方法里的检查有固定顺序，因为它决定了错误信息：先说「这个工具在这里不可用」、
     * 再说「参数坏了」、最后才是权限与真正的执行。顺序反了会得到一些误导性的提示，
     * 比如把「子引擎不许派生」说成「权限被拒」。
     */
    private ToolExecutionResult executeTool(ToolCall call) {
        try {
            SessionConfig executionConfig = activeTurnConfig == null ? config : activeTurnConfig;
            if (call.input.has("_raw_invalid_json")) {
                return ToolExecutionResult.error("Tool input JSON was incomplete or invalid: "
                        + call.input.optString("_raw_invalid_json"));
            }
            if (isSubagentTool(call.name)) return runSubagentTool(call, executionConfig);
            if ("AskUserQuestion".equals(call.name)) return askUserQuestion(call);
            if ("EnterPlanMode".equals(call.name)) return enterPlanMode();
            if ("ExitPlanMode".equals(call.name)) return requestPlanApproval(call);
            if (!isToolAllowed(call.name)) {
                return ToolExecutionResult.error("Tool " + call.name + " is not available to this subagent");
            }
            if ("Root".equals(call.name)
                    && (executionConfig == null || !executionConfig.rootExecutionEnabled)) {
                return ToolExecutionResult.error("Root tool is disabled in ZhiCode settings");
            }
            if ("Shizuku".equals(call.name)
                    && (executionConfig == null || !executionConfig.shizukuExecutionEnabled)) {
                return ToolExecutionResult.error("Shizuku tool is disabled in ZhiCode settings");
            }
            ZhiTool tool = tools.get(call.name);
            if (tool == null) return ToolExecutionResult.error("Unknown tool: " + call.name);

            String effectivePermissionMode = getEffectivePermissionMode();
            if (!allowedToRun(tool, call, executionConfig, effectivePermissionMode)) {
                return ToolExecutionResult.error(
                        "Permission denied for " + call.name + " in mode " + effectivePermissionMode);
            }
            return runTool(executionConfig, call);
        } catch (InterruptedException interrupted) {
            if (steering.toolInterruptRequested()) {
                Thread.interrupted();
                return ToolExecutionResult.error("Tool execution interrupted because the user sent a correction."
                        + " Re-evaluate the task using the newest user instruction.");
            }
            // 取消：把中断位还回去，让上层看到的仍然是「被取消」而不是普通失败。
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error("Tool execution cancelled");
        } catch (Throwable failure) {
            try {
                appendEventSafely("tool_failed", new JSONObject()
                        .put("tool", call == null ? "" : call.name)
                        .put("error_type", failure.getClass().getSimpleName())
                        .put("error", failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            } catch (Throwable ignored) {
                // 诊断记录不能遮盖工具原始失败。
            }
            return ToolExecutionResult.error(failure.getClass().getSimpleName() + ": "
                    + (failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        }
    }

    private static boolean isSubagentTool(String name) {
        return "Agent".equals(name) || "Task".equals(name)
                || "TaskOutput".equals(name) || "TaskStop".equals(name);
    }

    private ToolExecutionResult runSubagentTool(ToolCall call, SessionConfig executionConfig) throws Exception {
        if (subagentMode || subagents == null) {
            return ToolExecutionResult.error("Subagents cannot spawn other subagents");
        }
        return subagents.execute(executionConfig, getEffectivePermissionMode(), call);
    }

    private ToolExecutionResult askUserQuestion(ToolCall call) throws Exception {
        setTaskState(EngineTaskState.WAITING_QUESTION);
        if (!isToolAllowed("AskUserQuestion")) {
            return ToolExecutionResult.error("AskUserQuestion is not available to this subagent");
        }
        JSONArray questions = call.input.optJSONArray("questions");
        if (questions == null) questions = new JSONArray().put(call.input);
        JSONObject answers = questionGate.ask(questions, request -> forwardQuestionRequest(request));
        if (busy.get()) setTaskState(EngineTaskState.TOOL);
        return ToolExecutionResult.ok(new JSONObject().put("answers", answers).toString());
    }

    private boolean isToolAllowed(String name) {
        return allowedTools == null || allowedTools.contains(name);
    }

    /**
     * 这次调用是否可以免询问地执行。
     *
     * <p>沙箱全权模式（设置里的 sandboxAgentFullAccess）只对<b>沙箱内的操作</b>免去询问：
     * 见 {@link #sandboxFullAccessCoversCall}。这一点是安全边界，不是体验优化：
     * 沙箱里的进程与真机上的进程完全不是一回事。
     */
    private boolean allowedToRun(ZhiTool tool, ToolCall call, SessionConfig executionConfig,
                                 String effectivePermissionMode) throws InterruptedException {
        boolean sandboxFullAccess = sandboxFullAccessCoversCall(call, executionConfig, effectivePermissionMode);
        if (sandboxFullAccess) return true;
        setTaskState(EngineTaskState.WAITING_PERMISSION);
        try {
            return permissionGate.require(effectivePermissionMode, tool, call,
                    request -> forwardPermissionRequest(request));
        } finally {
            if (busy.get() && !Thread.currentThread().isInterrupted()) setTaskState(EngineTaskState.TOOL);
        }
    }

    /**
     * 沙箱全权是否覆盖这次调用。
     *
     * <p>两道排除：
     * <ul>
     *   <li>{@code plan} 是只读契约。全权模式也不能绕过它 —— 否则「先给方案再动手」
     *       就失去了意义，而那正是用户开计划模式的唯一理由；</li>
     *   <li>scope 为 {@code host} 的 {@code Debug} 动的是这台真手机上的进程，
     *       它不属于沙箱，必须照常询问。</li>
     * </ul>
     */
    private static boolean sandboxFullAccessCoversCall(ToolCall call, SessionConfig executionConfig,
                                                       String effectivePermissionMode) {
        if (executionConfig == null || !executionConfig.sandboxAgentFullAccess) return false;
        if (!"plan".equals(effectivePermissionMode)) return isSandboxScopedCall(call);
        return false;
    }

    /** Sandbox 工具本身，以及 scope 不是 host 的 Debug，才算「沙箱内的操作」。 */
    private static boolean isSandboxScopedCall(ToolCall call) {
        if ("Sandbox".equals(call.name)) return true;
        if (!"Debug".equals(call.name)) return false;
        return !"host".equalsIgnoreCase(call.input.optString("scope", DEFAULT_DEBUG_SCOPE));
    }

    /**
     * 真正跑工具，并在它换了项目目录时通知界面。
     *
     * <p>比的是「跑之前」与「跑之后」的目录：工具（例如切工作树）改了配置里的项目目录，
     * 界面必须跟着换，否则它显示的文件树和实际的项目对不上。
     */
    private ToolExecutionResult runTool(SessionConfig executionConfig, ToolCall call) throws Exception {
        String previousProject = executionConfig.projectDirectory;
        ToolExecutionResult result = tools.execute(executionConfig, call.name, call.input,
                (chunk, stderr, elapsedMs) -> {
                    Listener current = listener;
                    if (current != null) current.onToolProgress(call, chunk, stderr, elapsedMs);
                });
        if (previousProject != null && !previousProject.equals(executionConfig.projectDirectory)) {
            Listener current = listener;
            if (current != null) current.onProjectDirectoryChanged(executionConfig.projectDirectory);
        }
        return result;
    }

    /** 工具的改动摘要单独落盘：界面刷新后要靠它重建「这次改了哪些文件」。 */
    private void persistToolDiff(ToolCall call, ToolExecutionResult result) {
        if (call == null || result == null || result.diff.isEmpty()) return;
        SessionStore store = sessionStore;
        if (store == null) return;
        try {
            store.appendEvent("tool_diff", new JSONObject()
                    .put("tool_use_id", call.id == null ? "" : call.id)
                    .put("tool_name", call.name == null ? "" : call.name)
                    .put("diff", result.diff)
                    .put("additions", result.addedLines)
                    .put("deletions", result.deletedLines));
        } catch (Exception ignored) {
            // 改动摘要只是展示用。
        }
    }

    // ------------------------------------------------------------ 历史自愈

    /** 一段历史里出现过的工具调用与结果 id。 */
    private static final class ToolIdSets {
        final Set<String> calls = new LinkedHashSet<>();
        final Set<String> results = new LinkedHashSet<>();
    }

    /** 重建过程中的跨消息状态。 */
    private static final class RepairState {
        /** 已经配上对（调用在前、结果在后）的调用 id。 */
        final Set<String> paired;
        /** 重建过程中已经见过的调用 id。顺序有意义：结果在调用之前出现是非法的。 */
        final Set<String> encountered = new LinkedHashSet<>();
        /** 本次已经补过结果的调用 id，避免同一条补两次。 */
        final Set<String> synthesized = new LinkedHashSet<>();
        int changes;

        RepairState(Set<String> paired) {
            this.paired = paired;
        }
    }

    /**
     * 自愈本地历史，让协议层面的工具调用与结果严格配对。
     *
     * <p>要做两件事，以及一件同样重要的「不做」：
     * <ul>
     *   <li>每个没配到结果的 {@code tool_use} 补一条失败结果（内容说明「结果不可用，
     *       不要假定成功」）；</li>
     *   <li>孤儿 {@code tool_result}（结果在前，或者没有 id）<b>降级成普通文本</b>而不是删掉 ——
     *       里面的信息对模型仍然有用；</li>
     *   <li>人类对话一个字都不删。修的是「发给模型的上下文」，不是聊天记录。</li>
     * </ul>
     *
     * <p>提供方那边还有一层防御性映射，但那是在出站时兜底；这里修的是本地历史，
     * 因此是持久的 —— 修好一次，后续每次请求都不会再带着坏链去撞 API。
     *
     * @return 改了几处（0 表示这条链条本来就是干净的）
     */
    private int repairToolHistory(String missingResultReason, boolean persistSnapshot) {
        try {
            synchronized (messageLock) {
                RepairState state = new RepairState(findPairedToolCallIds());
                JSONArray rebuilt = new JSONArray();
                for (JSONObject message : JsonItems.of(messages)) {
                    repairSingleMessage(message, state, missingResultReason, rebuilt);
                }
                if (state.changes == 0) return 0;

                replaceMessagesLocked(rebuilt);
                invalidateMeasuredUsage();
                if (persistSnapshot && sessionStore != null) sessionStore.appendContextSnapshot(messages);
                return state.changes;
            }
        } catch (Throwable ignored) {
            // 自愈失败绝不能变成「这次会话打不开了」：原样保留历史，让上层继续跑。
            return 0;
        }
    }

    /**
     * 找出「已经有配对结果」的调用 id。
     *
     * <p>按出现顺序判定：只有先出现 {@code tool_use}、之后才出现同 id 的
     * {@code tool_result}，才算配上。反过来的顺序在 Responses 协议里是非法的，
     * 要当成孤儿处理。
     */
    private Set<String> findPairedToolCallIds() {
        Set<String> callsSeen = new LinkedHashSet<>();
        Set<String> paired = new LinkedHashSet<>();
        for (JSONObject message : JsonItems.of(messages)) {
            JSONArray content = contentOf(message);
            if (content == null) continue;
            for (JSONObject block : JsonItems.of(content)) {
                String type = block.optString("type", "");
                if ("tool_use".equals(type)) {
                    String id = block.optString("id", "").trim();
                    if (!id.isEmpty()) callsSeen.add(id);
                } else if ("tool_result".equals(type)) {
                    String id = block.optString("tool_use_id", "").trim();
                    if (!id.isEmpty() && callsSeen.contains(id)) paired.add(id);
                }
            }
        }
        return paired;
    }

    /** 修一条消息的内容块，结果追加到 {@code rebuilt}。 */
    private static void repairSingleMessage(JSONObject original, RepairState state, String missingResultReason,
                                            JSONArray rebuilt) throws Exception {
        if (original == null) return;
        String role = original.optString("role", "user");
        JSONArray content = original.optJSONArray("content");
        if (content == null) {
            rebuilt.put(copyOf(original));
            return;
        }

        JSONArray kept = new JSONArray();
        JSONArray synthesizedResults = new JSONArray();
        for (JSONObject block : JsonItems.of(content)) {
            String type = block.optString("type", "");

            if ("tool_use".equals(type)) {
                kept.put(copyOf(block));
                String id = block.optString("id", "").trim();
                if (id.isEmpty()) continue;
                state.encountered.add(id);
                if (state.paired.contains(id) || state.synthesized.contains(id)) continue;
                synthesizedResults.put(new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", id)
                        .put("content", missingResultReason == null ? "Tool result unavailable." : missingResultReason)
                        .put("is_error", true));
                state.synthesized.add(id);
                state.changes++;
                continue;
            }

            if ("tool_result".equals(type)) {
                String id = block.optString("tool_use_id", "").trim();
                if (!id.isEmpty() && state.encountered.contains(id)) {
                    kept.put(copyOf(block));
                    continue;
                }
                // 孤儿结果：调用没出现过（或没 id）。在 Responses 协议里这个块非法，
                // 但它的内容仍然有价值，所以降级成普通文本而不是丢掉。
                String label = id.isEmpty() ? "unknown" : id;
                kept.put(new JSONObject()
                        .put("type", "text")
                        .put("text", "[Recovered orphan tool result " + label + "]\n"
                                + String.valueOf(block.opt("content"))));
                state.changes++;
                continue;
            }

            kept.put(copyOf(block));
        }

        if (kept.length() > 0) {
            rebuilt.put(new JSONObject().put("role", role).put("content", kept));
        }
        // 补出来的结果单独放在这条调用之后，而不是塞进原来的消息里：
        // 有些提供方要求 assistant 消息里不能混入 tool_result。
        if (synthesizedResults.length() > 0) {
            rebuilt.put(new JSONObject().put("role", "user").put("content", synthesizedResults));
        }
    }

    private static JSONArray contentOf(JSONObject message) {
        return message == null ? null : message.optJSONArray("content");
    }

    private static JSONObject copyOf(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }

    /**
     * 把压缩的切点往前挪，直到「保留下来的那一段」不会以一个找不到对应调用的
     * {@code tool_result} 开头。
     *
     * <p>Responses 协议要求每个 {@code function_call_output} 前面都有同 call_id 的
     * {@code function_call}。压缩只切一刀，这一刀很容易落在一次调用的中间，
     * 于是保留下来的结果就成了孤儿，下一次请求直接被拒。
     */
    private int safeCompactionCut(int requestedCut) {
        int total = messages.length();
        int cut = Math.max(0, Math.min(requestedCut, total));
        boolean moved = cut > 0;
        while (moved) {
            moved = false;
            ToolIdSets retained = collectToolIds(cut, total);
            for (String resultId : retained.results) {
                if (retained.calls.contains(resultId)) continue;
                // 这个结果对应的调用被切掉了，把切点挪到那条调用之前。
                int matchingCall = findToolUseIndex(resultId, cut);
                if (matchingCall < 0) continue;
                cut = matchingCall;
                moved = true;
                break;
            }
        }
        return cut;
    }

    /** 收集 {@code [from, to)} 这段历史里出现过的调用与结果 id。 */
    private ToolIdSets collectToolIds(int from, int to) {
        ToolIdSets ids = new ToolIdSets();
        for (int i = Math.max(0, from); i < to; i++) {
            JSONArray content = contentOf(messages.optJSONObject(i));
            if (content == null) continue;
            for (JSONObject block : JsonItems.of(content)) {
                String type = block.optString("type", "");
                if ("tool_use".equals(type)) {
                    String id = block.optString("id", "").trim();
                    if (!id.isEmpty()) ids.calls.add(id);
                } else if ("tool_result".equals(type)) {
                    String id = block.optString("tool_use_id", "").trim();
                    if (!id.isEmpty()) ids.results.add(id);
                }
            }
        }
        return ids;
    }

    /** 从 {@code beforeExclusive} 往前找某次工具调用的位置；找不到返回 -1。 */
    private int findToolUseIndex(String id, int beforeExclusive) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = Math.min(beforeExclusive, messages.length()) - 1; i >= 0; i--) {
            JSONArray content = contentOf(messages.optJSONObject(i));
            if (content == null) continue;
            for (JSONObject block : JsonItems.of(content)) {
                if (!"tool_use".equals(block.optString("type", ""))) continue;
                if (id.equals(block.optString("id", ""))) return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ 上下文压缩

    public String compactContext() throws Exception {
        return compactContext("");
    }

    /**
     * 手动压缩上下文。可选文字会附在摘要要求后面（「重点保留什么」）。
     *
     * <p>手动压缩也必须占住 {@link #busy}：它与主循环会同时改历史，
     * 而且改的方式是「整体替换」—— 两个一起跑必然丢一边。
     */
    public String compactContext(String customInstructions) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("请等待当前任务结束后再压缩上下文");
        }
        manualCompactionThread = Thread.currentThread();
        setTaskState(EngineTaskState.COMPACTING);
        try {
            reportStatus("正在调用模型整理上下文…");
            return compactContextSemantic(customInstructions, false);
        } finally {
            manualCompactionThread = null;
            busy.set(false);
            taskState = EngineTaskState.IDLE;
            Listener current = listener;
            if (current != null) current.onTurnComplete("compacted");
        }
    }

    /**
     * 达到阈值时自动压缩。
     *
     * <p>连续失败要计数，而且要有一个上限：压缩失败通常意味着「请求本身有问题」
     * （模型不支持、窗口设得太小），每轮工具调用都重试一次会让整场会话慢得无法使用。
     *
     * <p>「已经很精简」也算一次失败：那说明压力来自系统提示词或工具 schema 这些
     * 压不掉的部分，再触发一次结果还是同一句话。
     */
    private void maybeAutoCompact() throws Exception {
        SessionConfig current = config;
        if (current == null || !current.autoCompact) return;
        if (consecutiveAutoCompactFailures >= ContextCompactor.MAX_CONSECUTIVE_AUTO_FAILURES) return;
        if (estimateContextTokens() < ContextCompactor.autoCompactThreshold(current)) return;

        reportStatus("正在调用模型自动压缩上下文…");
        setTaskState(EngineTaskState.COMPACTING);
        try {
            String outcome = compactContextSemantic("", true);
            if (busy.get() && !Thread.currentThread().isInterrupted()) setTaskState(EngineTaskState.MODEL);
            if (outcome.startsWith("上下文已经足够精简")) {
                consecutiveAutoCompactFailures++;
            } else {
                consecutiveAutoCompactFailures = 0;
            }
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            if (busy.get() && !Thread.currentThread().isInterrupted()) setTaskState(EngineTaskState.MODEL);
            consecutiveAutoCompactFailures++;
            SessionStore store = sessionStore;
            if (store != null) {
                try {
                    store.appendEvent("context_compaction_failed", new JSONObject()
                            .put("trigger", "auto")
                            .put("attempt", consecutiveAutoCompactFailures)
                            .put("error", failure.getMessage() == null
                                    ? failure.toString()
                                    : failure.getMessage()));
                } catch (Exception ignored) {
                    // 失败事件写不进去不影响主流程。
                }
            }
            if (consecutiveAutoCompactFailures >= ContextCompactor.MAX_CONSECUTIVE_AUTO_FAILURES) {
                reportStatus("自动压缩连续失败 3 次，本会话已停止重试；继续当前请求…");
            } else {
                reportStatus("自动压缩失败（" + consecutiveAutoCompactFailures + "/3），继续当前请求…");
            }
        }
    }

    /**
     * 用模型生成摘要，替换掉较早的那部分历史。
     *
     * <p>关键在最后一步的<b>提交校验</b>：调用模型期间没有持有 {@code messageLock}
     * （网络请求可能几十秒），这中间用户完全可能重置或恢复会话。所以提交前把当前历史
     * 和「生成计划时的历史」比一次，不一致就放弃这次压缩 ——
     * 覆盖掉用户刚恢复出来的会话是这里最严重的可能故障。
     */
    private String compactContextSemantic(String customInstructions, boolean automatic) throws Exception {
        repairToolHistory(automatic ? REPAIR_BEFORE_AUTO_COMPACT : REPAIR_BEFORE_COMPACT, true);

        ContextCompactor.Plan plan;
        synchronized (messageLock) {
            int requestedCut = ContextCompactor.suggestedCut(messages, config, !automatic);
            int cut = safeCompactionCut(requestedCut);
            if (cut <= 0) return "上下文已经足够精简（约 " + estimateContextTokens() + " tokens）";
            plan = ContextCompactor.createPlan(messages, cut);
        }

        SessionConfig summaryConfig = summaryRequestConfig();
        ModelProvider provider = providerOverride != null
                ? providerOverride
                : ModelProviders.forConfig(summaryConfig);
        AssistantTurn summaryTurn = summarizeWithRetries(provider, summaryConfig, plan, customInstructions);
        String summary = ContextCompactor.extractSummary(summaryTurn);
        JSONArray compacted = ContextCompactor.buildCompactedMessages(plan, summary);

        synchronized (messageLock) {
            if (!messages.toString().equals(plan.originalJson)) {
                throw new IllegalStateException("压缩期间会话已发生变化，未覆盖新的上下文");
            }
            replaceMessagesLocked(compacted);
        }

        invalidateMeasuredUsage();
        consecutiveAutoCompactFailures = 0;
        int afterTokens = estimateContextTokens();
        SessionStore store = sessionStore;
        if (store != null) {
            store.appendCompaction(summary, plan.removedMessages, plan.retainedMessages,
                    plan.roughBeforeTokens, afterTokens, automatic ? "auto" : "manual", config.model);
            store.appendContextSnapshot(compacted);
        }
        return "模型语义压缩完成：整理 " + plan.removedMessages + " 条较早消息，原样保留 "
                + plan.retainedMessages + " 条近期消息；当前约 " + afterTokens + " tokens";
    }

    /**
     * 摘要请求专用配置。
     *
     * <p>四件事都要做，缺一个都会让摘要本身出问题：把输出上限压到
     * {@link ContextCompactor#SUMMARY_OUTPUT_RESERVE_TOKENS} 之内（摘要不该把剩下的窗口吃光）、
     * 关掉流式思考与推理状态保持（它们对摘要没有用，只增加延迟与费用）、
     * 换一个新的传输会话（这是与主请求无关的另一条流）。
     */
    private SessionConfig summaryRequestConfig() {
        SessionConfig summaryConfig = config.copy();
        summaryConfig.maxTokens = Math.max(1_024,
                Math.min(ContextCompactor.SUMMARY_OUTPUT_RESERVE_TOKENS,
                        Math.max(1_024, summaryConfig.maxTokens)));
        summaryConfig.streamThinking = false;
        summaryConfig.reasoningSummary = "off";
        summaryConfig.preserveReasoningState = false;
        summaryConfig.renewTransportSession();
        return summaryConfig;
    }

    /**
     * 请模型生成摘要，遇到「提示词超出窗口」就按 API 回合往回缩再试。
     *
     * <p>重试必须有上限。压缩的整个目的就是减小上下文；如果缩到最小仍然超窗口，
     * 再试下去只是在重复同一件不会成功的事，而每次都要花一次请求的时间。
     */
    private AssistantTurn summarizeWithRetries(ModelProvider provider, SessionConfig summaryConfig,
                                               ContextCompactor.Plan plan, String customInstructions)
            throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= ContextCompactor.MAX_PROMPT_TOO_LONG_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Context compaction cancelled");
            }
            try {
                if (attempt > 0) {
                    reportStatus("摘要请求超过模型窗口，正在按 API 回合缩减后重试 " + attempt + "/2…");
                }
                return provider.createMessage(summaryConfig, summarySystemPrompt(summaryConfig),
                        ContextCompactor.buildSummaryMessages(plan, customInstructions, attempt,
                                summaryConfig.compactPromptHead),
                        new JSONArray(), SILENT_STREAM);
            } catch (Exception failure) {
                lastFailure = failure;
                boolean lastAttempt = attempt >= ContextCompactor.MAX_PROMPT_TOO_LONG_RETRIES;
                if (!ContextCompactor.isPromptTooLong(failure) || lastAttempt) throw failure;
            }
        }
        throw lastFailure;
    }

    // ------------------------------------------------------------ 计划工作流

    private ToolExecutionResult enterPlanMode() throws Exception {
        if (subagentMode) return ToolExecutionResult.error("Subagents cannot control the parent plan workflow");
        ensureSessionStore();

        PlanWorkflowState current = config.planWorkflowState;
        if (current != null && (current.isPlanning() || current.isAwaitingApproval())) {
            return ToolExecutionResult.ok("Already in plan mode. Continue read-only research"
                    + " and submit the complete plan with ExitPlanMode.");
        }
        String previous = PermissionModePolicy.normalize(config.permissionMode);
        PlanStore plans = new PlanStore(config.projectDirectory);
        PlanWorkflowState state = PlanWorkflowState.planning(config.workflowId, previous,
                plans.getPlanFile(config.workflowId).getAbsolutePath());
        config.planWorkflowState = state;
        appendPlanEvent("plan_entered", state);
        notifyPlanState(state);

        return ToolExecutionResult.ok("Entered plan mode. Read-only exploration is allowed."
                + " Build a task list, write a complete plan, then call ExitPlanMode for user approval."
                + "\nPlan file: " + state.planFile);
    }

    /**
     * 把计划交给人审批。
     *
     * <p>等待审批的这段时间里，计划状态可能被别处改掉（用户取消、应用重启、另一次调用）。
     * 所以拿到答复之后必须重新读一次当前状态，确认它还是「我们提交的那一份」——
     * 用 {@code revision + workflowId} 一起校验，只看 revision 会在换过 workflow 之后误判。
     */
    private ToolExecutionResult requestPlanApproval(ToolCall call) throws Exception {
        if (subagentMode) {
            return ToolExecutionResult.error("Subagents cannot request approval for the parent plan workflow");
        }
        PlanWorkflowState current = config.planWorkflowState;
        if (current == null || !current.isPlanning()) {
            return ToolExecutionResult.error("ExitPlanMode requires an active planning workflow");
        }
        String planText = call.input.optString("plan", "").trim();
        if (planText.isEmpty()) {
            return ToolExecutionResult.error("ExitPlanMode requires the complete implementation plan");
        }

        PlanStore plans = new PlanStore(config.projectDirectory);
        File planFile = plans.write(config.workflowId, planText);
        PlanWorkflowState proposed = current.withPlan(planFile.getAbsolutePath(), planText).awaitingApproval();
        config.planWorkflowState = proposed;
        appendPlanEvent("plan_proposed", proposed);
        notifyPlanState(proposed);

        if (listener == null) {
            return ToolExecutionResult.error("Plan approval UI is unavailable; remain in plan mode");
        }
        setTaskState(EngineTaskState.WAITING_PLAN_APPROVAL);
        PlanApprovalGate.ApprovalResponse response = planApprovalGate.request(proposed,
                request -> {
                    Listener activeListener = listener;
                    if (activeListener != null) activeListener.onPlanApprovalRequest(request);
                });

        if (busy.get()) setTaskState(EngineTaskState.TOOL);
        PlanWorkflowState live = config.planWorkflowState;
        boolean stillOurs = live != null
                && live.isAwaitingApproval()
                && live.revision == proposed.revision
                && live.workflowId.equals(proposed.workflowId);
        if (!stillOurs) return ToolExecutionResult.error("The plan approval request is no longer active");

        if (response.decision == PlanApprovalGate.Decision.KEEP_PLANNING) {
            PlanWorkflowState planning = proposed.keepPlanning(response.feedback);
            config.planWorkflowState = planning;
            appendPlanEvent("plan_revision_requested", planning);
            notifyPlanState(planning);
            return ToolExecutionResult.ok("The user requested more planning. Remain read-only,"
                    + " revise the complete plan, and call ExitPlanMode again.\nFeedback: " + response.feedback);
        }
        if (response.isApproved()) {
            PlanWorkflowState executing = proposed.executing();
            config.planWorkflowState = executing;
            appendPlanEvent("plan_approved", executing);
            notifyPlanState(executing);
            return ToolExecutionResult.ok("The user approved the plan. Continue implementation"
                    + " using the user's existing permission mode "
                    + PermissionModePolicy.normalize(config.permissionMode)
                    + ".\nPlan file: " + planFile + "\n\n" + proposed.planText);
        }

        PlanWorkflowState cancelled = proposed.cancelled(response.feedback);
        config.planWorkflowState = cancelled;
        appendPlanEvent("plan_cancelled", cancelled);
        notifyPlanState(cancelled);
        return ToolExecutionResult.error("The user cancelled the plan workflow."
                + (response.feedback.isEmpty() ? "" : "\nFeedback: " + response.feedback));
    }

    /** 一轮正常收尾时，把「正在执行」的计划标记成已完成。 */
    private void finishPlanExecutionIfNeeded() {
        PlanWorkflowState state = config == null ? null : config.planWorkflowState;
        if (state == null || !state.isExecuting()) return;
        PlanWorkflowState finished = state.finished();
        config.planWorkflowState = finished;
        appendPlanEvent("plan_finished", finished);
        notifyPlanState(finished);
    }

    private void notifyPlanState(PlanWorkflowState state) {
        Listener current = listener;
        if (current == null || state == null) return;
        current.onPlanStateChanged(state.copy());
    }

    private void appendPlanEvent(String type, PlanWorkflowState state) {
        SessionStore store = sessionStore;
        if (store == null || state == null) return;
        try {
            store.appendEvent(type, planStatePayload(state));
        } catch (Exception ignored) {
            // 计划事件写不进去只影响「回放」，不影响当前状态机。
        }
    }

    private static JSONObject planStatePayload(PlanWorkflowState state) throws Exception {
        return new JSONObject()
                .put("status", state.status.name())
                .put("workflow_id", state.workflowId)
                .put("revision", state.revision)
                .put("previous_permission_mode", state.previousPermissionMode)
                .put("approved_permission_mode", state.approvedPermissionMode)
                .put("plan_file", state.planFile)
                .put("plan_text", state.planText)
                .put("feedback", state.feedback)
                .put("updated_at", state.updatedAt);
    }

    public boolean respondPlanApproval(String requestId, PlanApprovalGate.Decision decision, String feedback) {
        if (!busy.get()) return false;
        return planApprovalGate.respond(requestId, decision, feedback);
    }

    public ToolExecutionResult enterPlanModeFromUi() {
        try {
            return enterPlanMode();
        } catch (Exception failure) {
            return ToolExecutionResult.error(
                    failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    /** 界面上的「取消计划」。与工具调用触发的取消走同一套状态迁移。 */
    public ToolExecutionResult cancelPlanModeFromUi(String feedback) {
        SessionConfig current = config;
        PlanWorkflowState state = current == null ? null : current.planWorkflowState;
        if (state == null || state.isIdle()) return ToolExecutionResult.error("No active plan workflow");
        planApprovalGate.cancelAll();
        PlanWorkflowState cancelled = state.cancelled(feedback == null ? "" : feedback);
        current.planWorkflowState = cancelled;
        appendPlanEvent("plan_cancelled", cancelled);
        notifyPlanState(cancelled);
        return ToolExecutionResult.ok("Plan workflow cancelled");
    }

    public PlanWorkflowState getPlanWorkflowState() {
        SessionConfig current = config;
        PlanWorkflowState state = current == null ? null : current.planWorkflowState;
        return state == null ? PlanWorkflowState.idle() : state.copy();
    }

    // ------------------------------------------------------------ 子代理

    public List<AgentDefinition> listAgentDefinitions() {
        if (subagents == null) return new ArrayList<>();
        SessionConfig current = config;
        return subagents.definitions(current == null ? null : current.projectDirectory);
    }

    public List<AgentTask> listAgentTasks() {
        return subagents == null ? new ArrayList<>() : subagents.tasks();
    }

    public boolean stopAgentTask(String id) {
        return subagents != null && subagents.stop(id);
    }

    /** 供工作树/权限相关的界面直接发起一次子代理调用（不经过模型）。 */
    public ToolExecutionResult invokeSubagent(JSONObject input) throws Exception {
        if (subagents == null || subagentMode) {
            return ToolExecutionResult.error("Subagents are unavailable in this engine");
        }
        ToolCall call = new ToolCall("direct-agent-" + System.currentTimeMillis(), "Agent", input);
        return subagents.execute(config, getEffectivePermissionMode(), call);
    }

    public boolean respondQuestion(String requestId, JSONObject answers) {
        if (!busy.get()) return false;
        if (questionGate.respond(requestId, answers)) return true;
        return subagents != null && subagents.respondQuestion(requestId, answers);
    }

    public boolean respondPermission(String requestId, boolean allow) {
        if (!busy.get()) return false;
        if (permissionGate.respond(requestId, allow)) return true;
        return subagents != null && subagents.respondPermission(requestId, allow);
    }

    /** 父引擎用来回答子引擎的权限询问的通道。只回答自己的，不转发给别人的子代理。 */
    public boolean respondPermissionLocal(String requestId, boolean allow) {
        return permissionGate.respond(requestId, allow);
    }

    // ------------------------------------------------------------ 工具清单

    /**
     * 本次请求带给模型的工具清单。
     *
     * <p>三处过滤都是「不该给模型看到它现在用不了的工具」：关掉的网页工具、没开 Root
     * 时的 Root 工具、以及子代理能用的子集。给出用不了的工具，模型会去调用它，
     * 然后收到一条权限错误 —— 那一步是白花的。
     *
     * <p>{@code AskUserQuestion} 额外追加：它由引擎自己实现（不注册在
     * {@link ToolRegistry} 里），因为它的语义是「停下来等人」，与普通工具不同。
     */
    private JSONArray effectiveToolSchemas() {
        SessionConfig current = config;
        String key = current == null ? "" : current.permissionMode + "\u0000"
                + current.webSearchEnabled + "\u0000" + current.rootExecutionEnabled
                + "\u0000" + current.shizukuExecutionEnabled
                + "\u0000" + (allowedTools == null ? "*" : allowedTools.toString())
                + "\u0000" + subagentMode;
        if (cachedToolSchemas != null && key.equals(cachedToolSchemasKey)) {
            return cloneArray(cachedToolSchemas);
        }
        JSONArray base = tools.apiSchemas();
        JSONArray schemas = new JSONArray();
        for (JSONObject schema : JsonItems.of(base)) {
            String name = schema.optString("name", "");
            if (isSchemaExcluded(name)) continue;
            schemas.put(schema);
        }
        if (isToolAllowed("AskUserQuestion")) schemas.put(askUserQuestionSchema());
        if (!subagentMode && subagents != null) {
            JSONArray agentSchemas = subagents.apiSchemas();
            for (JSONObject schema : JsonItems.of(agentSchemas)) schemas.put(schema);
        }
        cachedToolSchemas = cloneArray(schemas);
        cachedToolSchemasKey = key;
        return schemas;
    }

    private boolean isSchemaExcluded(String name) {
        SessionConfig current = config;
        boolean webTool = "WebSearch".equals(name) || "WebFetch".equals(name);
        if (webTool && current != null && !current.webSearchEnabled) return true;
        if ("Root".equals(name) && (current == null || !current.rootExecutionEnabled)) return true;
        if ("Shizuku".equals(name) && (current == null || !current.shizukuExecutionEnabled)) return true;
        return !isToolAllowed(name);
    }

    /**
     * {@code AskUserQuestion} 的入参 schema。
     *
     * <p>它不注册在工具注册表里，所以 schema 也只能写在这里。结构跟着
     * {@link QuestionGate} 期望的字段走：{@code questions[]} 里每项有
     * {@code question} / {@code header} / {@code multiSelect} / {@code options[]}。
     */
    private static JSONObject askUserQuestionSchema() {
        try {
            JSONObject option = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("label", new JSONObject().put("type", "string"))
                            .put("description", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("label"));
            JSONObject question = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("question", new JSONObject().put("type", "string"))
                            .put("header", new JSONObject().put("type", "string"))
                            .put("multiSelect", new JSONObject().put("type", "boolean"))
                            .put("options", new JSONObject().put("type", "array").put("items", option)))
                    .put("required", new JSONArray().put("question"));
            JSONObject input = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("questions", new JSONObject().put("type", "array")
                                    .put("items", question)
                                    .put("minItems", 1)))
                    .put("required", new JSONArray().put("questions"));
            return new JSONObject()
                    .put("name", "AskUserQuestion")
                    .put("description",
                            "Ask the user one or more clarifying questions and wait for their answers.")
                    .put("input_schema", input);
        } catch (Exception impossible) {
            // 上面的字面量都是固定结构，出错只可能是运行环境坏了。
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------ 其它

    private String buildSystemPrompt(SessionConfig promptConfig) {
        SessionConfig current = promptConfig == null ? config : promptConfig;
        String key = current == null ? additionalSystemPrompt
                : current.model + "\u0000" + current.projectDirectory + "\u0000"
                + current.customSystemPrompt + "\u0000" + current.roleCard
                + "\u0000" + current.permissionMode + "\u0000" + additionalSystemPrompt;
        if (cachedSystemPrompt != null && key.equals(cachedSystemPromptKey)) return cachedSystemPrompt;
        String result = SystemPromptBuilder.build(current, additionalSystemPrompt);
        cachedSystemPrompt = result;
        cachedSystemPromptKey = key;
        return result;
    }

    private static JSONArray cloneArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try {
            return new JSONArray(source.toString());
        } catch (Exception unreadable) {
            return new JSONArray();
        }
    }

    /**
     * 拿一把唤醒锁。
     *
     * <p>一次会话可能跑几十分钟，屏幕熄灭会让 CPU 睡下去，正在读的流就此卡住 ——
     * 表现是「一锁屏就断」。熄屏锁必须带超时：万一收尾逻辑没跑到，也不该无限期占着电池。
     * 时长的选择是「比任何合理会话都长，但仍然是有限的」。
     */
    private void acquireTaskWakeLock() {
        try {
            PowerManager manager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (manager == null) return;
            PowerManager.WakeLock lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            lock.setReferenceCounted(false);
            lock.acquire(WAKELOCK_TIMEOUT_MS);
            taskWakeLock = lock;
        } catch (Throwable ignored) {
            // 拿不到锁不致命：只是熄屏时可能中断，不该因此让整个会话失败。
        }
    }

    private void releaseTaskWakeLock() {
        PowerManager.WakeLock lock = taskWakeLock;
        taskWakeLock = null;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
            // 见 acquireTaskWakeLock。
        }
    }
}
