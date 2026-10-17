package com.termux.app.zhicode.agents;

import android.content.Context;

import com.termux.app.zhicode.api.ModelProvider;
import com.termux.app.zhicode.core.PermissionGate;
import com.termux.app.zhicode.core.PermissionModePolicy;
import com.termux.app.zhicode.core.QuestionGate;
import com.termux.app.zhicode.core.ZhiCodeEngine;
import com.termux.app.zhicode.model.SessionConfig;
import com.termux.app.zhicode.model.ToolCall;
import com.termux.app.zhicode.model.ToolExecutionResult;
import com.termux.app.zhicode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 子代理与后台任务的运行时。
 *
 * <h3>子代理为什么是「另一个引擎」而不是「另一段提示词」</h3>
 * 一次子代理调用要能独立地跑几十轮工具、有自己的上下文窗口、自己的模型与权限级别。
 * 这些东西都长在 {@link ZhiCodeEngine} 上，所以子代理就是另造一个引擎实例，
 * 并把它限定在 {@code subagentMode}（不能再派生下一层）。
 *
 * <h3>权限只收紧、不放开</h3>
 * 子代理的权限来自 {@link PermissionModePolicy#capSubagent}：它以<b>父引擎的当前有效模式</b>
 * 为上界。这样「父代理在计划模式（只读）」不可能通过派一个子代理绕过限制 ——
 * 而这条恰恰是最容易写错的地方：写错不会报错，只会让只读保证悄悄失效。
 *
 * <h3>前台与后台是同一条路径</h3>
 * 两者都走 {@link #runTask}，区别只在调用方要不要等 {@code task.done}：
 * 前台等，后台立刻返回任务 id。这样「前台调用的实现」和「后台调用的实现」不会
 * 各自演化成一套逻辑（那会导致同一次子代理在前台和后台行为不同）。
 *
 * <h3>落盘与节流</h3>
 * 每个任务写自己的 {@code agent-tasks/<id>.json}，供界面在重启后列出历史。
 * 写入按任务节流：进度更新非常频繁（每个流式分片都会触发一次），而这里的调用方
 * 是模型循环 —— 每次都 fsync 会把整个会话拖慢。
 */
public final class SubagentManager {

    /** 父引擎用来回答子代理权限询问的通道。 */
    public interface PermissionRelay {
        void onPermissionRequest(PermissionGate.PermissionRequest request);
    }

    /** 同上，用于「问用户一个问题」。 */
    public interface QuestionRelay {
        void onQuestionRequest(QuestionGate.QuestionRequest request);
    }

    // ------------------------------------------------------------ 常量

    /** 子代理线程名。用缓存线程池：后台任务的持续时间差得很远，固定大小会互相挤。 */
    private static final String THREAD_NAME_PREFIX = "zhi-subagent";
    private static final String TASKS_DIR_NAME = "agent-tasks";
    private static final String SKILLS_DIR_NAME = "skills";
    private static final String MEMORY_DIR_NAME = "agent-memory";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String WORKTREES_DIR_NAME = "worktrees";

    /** 默认的子代理类型。与工具 schema 里写的可选值一致。 */
    private static final String DEFAULT_AGENT_TYPE = "general-purpose";

    /** 进度落盘的最小间隔。见类注释。 */
    private static final long PERSIST_INTERVAL_MS = 500L;

    /** 工作树操作各自允许多久。单位毫秒，与 {@link TermuxShellExecutor} 的入参类型一致。 */
    private static final int WORKTREE_ADD_TIMEOUT_MS = 120_000;
    private static final int WORKTREE_STATUS_TIMEOUT_MS = 30_000;
    private static final int WORKTREE_REMOVE_TIMEOUT_MS = 60_000;

    /** {@code TaskOutput} 的默认与最大等待时间。 */
    private static final int DEFAULT_OUTPUT_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_TIMEOUT_MS = 600_000;

    /** 任务 id 的可见长度。它出现在工具结果与界面里，太长反而不好抄。 */
    private static final int TASK_ID_CHARS = 8;

    /**
     * 子代理可用的全部工具。
     *
     * <p>这是一份<b>白名单</b>：不在其中的工具名一律忽略，而不是报错。
     * 理由是这样一份清单会随版本变化，而用户写的代理定义不会 ——
     * 一个已经删掉的工具名不该让整份定义失效。
     */
    private static final String[] SUBAGENT_TOOLS = {
            "Bash", "Root", "Shizuku", "Sandbox", "GitStatus", "TermuxDoctor", "TermuxRepair",
            "EnterWorktree", "ExitWorktree", "Read", "ReadMany", "Stat", "Tree",
            "Write", "Copy", "Edit", "MultiEdit", "Mkdir", "Move", "Delete",
            "Glob", "Grep", "LS", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "Skill", "Sleep", "TodoWrite", "AskUserQuestion"};

    /** 别名：这些是别的 agent 生态里的工具名，用户定义里可能写的是它们。 */
    private static final Map<String, String> TOOL_ALIASES = new LinkedHashMap<>();

    static {
        TOOL_ALIASES.put("fileread", "Read");
        TOOL_ALIASES.put("filewrite", "Write");
        TOOL_ALIASES.put("fileedit", "Edit");
        TOOL_ALIASES.put("list", "LS");
    }

    /** 这三个不是真的模型名，而是「档位」——意思是「用父代理的模型」。 */
    private static final Set<String> MODEL_TIERS =
            new LinkedHashSet<>(Arrays.asList("haiku", "sonnet", "opus"));

    // ------------------------------------------------------------ 状态

    private final Context context;
    private final ModelProvider providerOverride;
    private final PermissionRelay permissionRelay;
    private final QuestionRelay questionRelay;

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME_PREFIX);
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    /** 每个任务各自的上次落盘时间。见类注释：不能共用一个字段。 */
    private final Map<String, Long> lastPersistAt = new ConcurrentHashMap<>();

    public SubagentManager(Context context, ModelProvider providerOverride,
                           PermissionRelay permissionRelay, QuestionRelay questionRelay) {
        this.context = context.getApplicationContext();
        this.providerOverride = providerOverride;
        this.permissionRelay = permissionRelay;
        this.questionRelay = questionRelay;
    }

    // ------------------------------------------------------------ 对外接口

    /**
     * 发给模型的三个子代理工具。它们不注册在 {@code ToolRegistry} 里，由本类自己实现。
     *
     * <p>schema 的构造搬到 {@link SubagentToolSchemas}：那段是纯 JSON 组装，
     * 放在这里只能靠"发一次真请求"验证，而 schema 写坏不一定每次都被服务端指出来
     * （曾经就这样漏掉过最外层的 {@code type:object}，见那个类的注释）。
     */
    public JSONArray apiSchemas() {
        return SubagentToolSchemas.apiSchemas();
    }

    public ToolExecutionResult execute(SessionConfig parent, String parentEffectiveMode, ToolCall call)
            throws Exception {
        String name = call.name == null ? "" : call.name;
        if ("Agent".equals(name) || "Task".equals(name)) {
            return launch(parent, parentEffectiveMode, call.input);
        }
        if ("TaskOutput".equals(name)) return readOutput(call.input);
        if ("TaskStop".equals(name)) return stopTask(call.input);
        return ToolExecutionResult.error("Unknown subagent tool: " + name);
    }

    public List<AgentDefinition> definitions(String project) {
        return AgentDefinitionLoader.loadAll(project);
    }

    /** 最近启动的任务排在前面。 */
    public List<AgentTask> tasks() {
        List<AgentTask> snapshot = new ArrayList<>(tasks.values());
        Collections.sort(snapshot, (a, b) -> Long.compare(b.startedAt, a.startedAt));
        return snapshot;
    }

    // ------------------------------------------------------------ 启动一个子代理

    /**
     * 启动子代理。
     *
     * <p>参数校验的顺序是有意的：先看「有没有事要做」，再看「要派给谁」，
     * 最后才是「怎么派」。校验顺序反了会得到误导性的提示 ——
     * 例如把一个拼错的 agent 类型报成「cwd 与 isolation 互斥」。
     */
    private ToolExecutionResult launch(SessionConfig parent, String parentEffectiveMode, JSONObject input)
            throws Exception {
        String prompt = input.optString("prompt", "").trim();
        if (prompt.isEmpty()) return ToolExecutionResult.error("Agent prompt is required");

        String requestedType = input.optString("subagent_type", DEFAULT_AGENT_TYPE).trim();
        String type = requestedType.isEmpty() ? DEFAULT_AGENT_TYPE : requestedType;
        AgentDefinition definition = AgentDefinitionLoader.resolve(parent.projectDirectory, type);
        if (definition == null) {
            return ToolExecutionResult.error("Unknown subagent type '" + type + "'. Available: "
                    + joinAgentNames(parent.projectDirectory));
        }

        String cwd = input.optString("cwd", "").trim();
        String isolation = input.optString("isolation", definition.isolation).trim();
        if (!cwd.isEmpty() && !isolation.isEmpty()) {
            // 两个都是「去哪儿干活」的答案，同时给出说明调用方自己也没想清楚。
            return ToolExecutionResult.error("cwd and isolation are mutually exclusive");
        }
        boolean background = input.has("run_in_background")
                ? input.optBoolean("run_in_background", false)
                : definition.background;

        String id = UUID.randomUUID().toString().substring(0, TASK_ID_CHARS);
        String description = input.optString("description", definition.name).trim();
        if (description.isEmpty()) description = definition.name;

        AgentTask task = new AgentTask(id, description, definition.name);
        tasks.put(id, task);
        persist(task);

        final String finalCwd = cwd;
        final String finalIsolation = isolation;
        task.future = executor.submit(() ->
                runTask(parent, parentEffectiveMode, input, definition, task, prompt, finalCwd, finalIsolation));

        if (background) {
            return ToolExecutionResult.ok("Background agent started.\ntask_id: " + id
                    + "\nagent: " + definition.name + "\ndescription: " + description
                    + "\nUse TaskOutput with task_id='" + id
                    + "' to read progress/result, or TaskStop to cancel it.");
        }
        return awaitForegroundResult(task, definition);
    }

    /**
     * 前台调用：等任务结束，然后把它的结果（或失败原因）作为工具结果返回。
     *
     * <h3>为什么被中断时一定要顺手取消任务</h3>
     *
     * <p>等在这里的是**父代理的 agent 线程**。用户按「停止」时被打断的正是这个线程
     * （{@code ZhiCodeEngine.cancel()} → {@code worker.interrupt()}），
     * 于是 {@code done.await()} 抛出 {@code InterruptedException}，这个方法就返回了 ——
     * 而<b>子代理自己还在另一个线程上跑</b>，继续执行它的 Bash / 编辑文件。
     *
     * <p>旧实现就是这样：父代理显示"已取消"，子代理的命令照跑不误，界面上那张
     * 工具卡一直在转，用户只能把应用杀掉。所以这里先把任务真正取消掉，再把中断抛上去。
     */
    private ToolExecutionResult awaitForegroundResult(AgentTask task, AgentDefinition definition)
            throws InterruptedException {
        try {
            task.done.await();
        } catch (InterruptedException cancelled) {
            if (AgentTask.STATUS_RUNNING.equals(task.status)) cancelTask(task);
            throw cancelled;
        }
        if (AgentTask.STATUS_FAILED.equals(task.status)) {
            String detail = task.error == null ? task.output.toString() : task.error.toString();
            return ToolExecutionResult.error("Agent " + definition.name + " failed: " + detail);
        }
        if (AgentTask.STATUS_CANCELLED.equals(task.status)) {
            return ToolExecutionResult.error("Agent " + definition.name + " was cancelled.\n" + task.output);
        }
        return ToolExecutionResult.ok("Agent " + definition.name + " completed.\ntask_id: " + task.id
                + "\n\n" + task.output.toString().trim() + worktreeNotice(task));
    }

    /**
     * 任务主体。
     *
     * <p>三层 {@code catch} 的划分不是随手写的：
     * <ul>
     *   <li>{@link InterruptedException}：这是「被取消」，不是失败。要恢复中断位并
     *       顺手取消子引擎，否则它会在后台继续跑；</li>
     *   <li>其它任何异常：任务失败，把原因留在任务上（前台调用会看到它）；</li>
     *   <li>{@code finally}：任务已经不在跑的时候关掉子引擎，回收它的线程池与订阅。</li>
     * </ul>
     */
    private void runTask(SessionConfig parent, String parentEffectiveMode, JSONObject invocation,
                         AgentDefinition definition, AgentTask task, String prompt, String cwd, String isolation) {
        String worktree = "";
        try {
            SessionConfig child = prepareChildConfig(parent, parentEffectiveMode, definition, invocation,
                    task, cwd, isolation);
            worktree = task.worktreePath;

            ZhiCodeEngine childEngine = ZhiCodeEngine.createSubagent(context,
                    listenerFor(task), providerOverride, resolveAllowedTools(definition),
                    buildSystemSuffix(definition, prompt));
            task.engine = childEngine;
            childEngine.configure(child);
            childEngine.sendPrompt(prompt);

            task.done.await();
            if (isWorktreeIsolation(isolation)) cleanupWorktreeIfClean(parent.projectDirectory, worktree);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            if (task.engine != null) task.engine.cancel();
            finish(task, AgentTask.STATUS_CANCELLED, "Cancelled", null);
        } catch (Throwable failure) {
            finish(task, AgentTask.STATUS_FAILED,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(), failure);
        } finally {
            if (task.engine != null && !AgentTask.STATUS_RUNNING.equals(task.status)) task.engine.shutdown();
        }
    }

    /**
     * 装配子引擎的配置。
     *
     * <p>{@code renewTransportSession()} 是必需的：子代理与父代理共享同一份配置副本，
     * 但它们是两条独立的传输流。不换会话 id 会让两个引擎在服务端看起来是同一个会话，
     * 表现是「子代理的回答接在父代理的上下文后面」。
     */
    private SessionConfig prepareChildConfig(SessionConfig parent, String parentEffectiveMode,
                                             AgentDefinition definition, JSONObject invocation,
                                             AgentTask task, String cwd, String isolation) throws Exception {
        SessionConfig child = parent.copy();
        child.renewTransportSession();

        if (!cwd.isEmpty()) {
            child.projectDirectory = canonicalDirectory(cwd);
        } else if (isWorktreeIsolation(isolation)) {
            String worktree = createWorktree(parent.projectDirectory, task.id);
            child.projectDirectory = worktree;
            task.worktreePath = worktree;
        }

        applyModelOverride(parent, child, definition, invocation.optString("model", ""));
        if (!definition.effort.isEmpty()) child.effort = definition.effort;
        child.permissionMode = PermissionModePolicy.capSubagent(parentEffectiveMode, definition.permissionMode);
        if (definition.maxTurns > 0) child.maxAgentTurns = definition.maxTurns;
        return child;
    }

    /**
     * 子代理的事件回调。
     *
     * <p>它是一个「把引擎事件翻译成任务状态」的适配器：所有分支都只做两件事 ——
     * 更新 {@code task.progress} 并落盘（节流）。任务的输出文本是要写进工具结果的，
     * 所以每次追加都要加锁：流式分片与 {@code onTurnComplete} 可能不在同一时刻，
     * 而 StringBuilder 不是线程安全的。
     */
    private ZhiCodeEngine.Listener listenerFor(AgentTask task) {
        final StringBuilder answer = task.output;
        return new ZhiCodeEngine.Listener() {
            @Override public void onSessionStarted(SessionConfig config) {
                setProgress(task, "Thinking…");
            }

            @Override public void onTextDelta(String text) {
                synchronized (answer) {
                    answer.append(text);
                }
                task.progress = "Responding…";
                persistThrottled(task);
            }

            @Override public void onThinkingDelta(String thinking) {
                task.progress = "Thinking…";
            }

            @Override public void onResponseRetry() {
                // 重试意味着上一次的流式内容作废：留着它会把两次回答拼在一起。
                synchronized (answer) {
                    answer.setLength(0);
                }
                setProgress(task, "连接中断，正在重试…");
            }

            @Override public void onToolUse(ToolCall call) {
                task.progress = "Using " + call.name + "…";
                persistThrottled(task);
            }

            @Override public void onToolResult(ToolCall call, ToolExecutionResult result) {
                task.progress = (result.isError ? "Tool failed: " : "Used ") + call.name;
                persistThrottled(task);
            }

            @Override public void onPermissionRequest(PermissionGate.PermissionRequest request) {
                // 子代理没有自己的界面。权限询问必须转发给父引擎，由父引擎的界面来答。
                if (permissionRelay != null) permissionRelay.onPermissionRequest(request);
            }

            @Override public void onQuestionRequest(QuestionGate.QuestionRequest request) {
                if (questionRelay != null) questionRelay.onQuestionRequest(request);
            }

            @Override public void onUsage(long inputTokens, long outputTokens) {
                // 子代理的用量不计入父会话的上下文占用：它们是两个独立的上下文窗口。
            }

            @Override public void onStatus(String status) {
                setProgress(task, status);
            }

            @Override public void onTurnComplete(String reason) {
                boolean cancelled = "cancelled".equals(reason);
                finish(task, cancelled ? AgentTask.STATUS_CANCELLED : AgentTask.STATUS_COMPLETED, reason, null);
                task.sessionId = task.engine == null || task.engine.getSessionId() == null
                        ? "" : task.engine.getSessionId();
                persist(task);
            }

            @Override public void onError(String message, Throwable error) {
                finish(task, AgentTask.STATUS_FAILED, message, error);
            }
        };
    }

    private static void setProgress(AgentTask task, String progress) {
        task.progress = progress;
    }

    // ------------------------------------------------------------ 任务状态迁移

    /**
     * 把任务落到终态。
     *
     * <p>{@code done.countDown()} 只做一次：重复调用是幂等的，但重复落盘会让
     * 「完成时间」被后一次覆盖 —— 对一个已经结束的任务改完成时间没有意义。
     */
    private void finish(AgentTask task, String status, String progress, Throwable error) {
        synchronized (task) {
            if (!AgentTask.STATUS_RUNNING.equals(task.status)) return;
            task.status = status;
            task.progress = progress == null ? "" : progress;
            task.error = error;
            task.finishedAt = System.currentTimeMillis();
        }
        persist(task);
        task.done.countDown();
    }

    // ------------------------------------------------------------ TaskOutput / TaskStop

    private ToolExecutionResult readOutput(JSONObject input) throws Exception {
        String id = taskIdOf(input);
        AgentTask task = tasks.get(id);
        if (task == null) return ToolExecutionResult.error("Unknown agent task: " + id);

        boolean block = input.optBoolean("block", true);
        int timeout = Math.max(0, input.optInt("timeout", DEFAULT_OUTPUT_TIMEOUT_MS));
        if (block && AgentTask.STATUS_RUNNING.equals(task.status)) {
            task.done.await(Math.min(timeout, MAX_OUTPUT_TIMEOUT_MS), TimeUnit.MILLISECONDS);
        }

        StringBuilder report = new StringBuilder()
                .append("task_id: ").append(task.id)
                .append("\nstatus: ").append(task.status)
                .append("\nagent: ").append(task.agentType)
                .append("\nprogress: ").append(task.progress);
        synchronized (task.output) {
            if (task.output.length() > 0) report.append("\n\n").append(task.output);
        }
        if (task.error != null) report.append("\n\nerror: ").append(task.error);

        return AgentTask.STATUS_FAILED.equals(task.status)
                ? ToolExecutionResult.error(report.toString())
                : ToolExecutionResult.ok(report.toString());
    }

    private ToolExecutionResult stopTask(JSONObject input) throws Exception {
        String id = taskIdOf(input);
        AgentTask task = tasks.get(id);
        if (task == null) return ToolExecutionResult.error("Unknown agent task: " + id);
        if (!AgentTask.STATUS_RUNNING.equals(task.status)) {
            return ToolExecutionResult.ok("Task " + id + " is already " + task.status);
        }
        cancelTask(task);
        return ToolExecutionResult.ok("Stopped agent task " + id);
    }

    /** 界面用的按 id 停止。返回是否找到了这个任务。 */
    public boolean stop(String id) {
        AgentTask task = tasks.get(id);
        if (task == null) return false;
        cancelTask(task);
        return true;
    }

    /**
     * 停止一个任务。
     *
     * <p>两条取消路径都要走：{@code engine.cancel()} 让正在读的流断开，
     * {@code future.cancel(true)} 打断可能正卡在等待里的线程。只做一条的话，
     * 有的状态能停、有的停不下来。
     */
    private void cancelTask(AgentTask task) {
        if (task.engine != null) task.engine.cancel();
        if (task.future != null) task.future.cancel(true);
        finish(task, AgentTask.STATUS_CANCELLED, "Cancelled", null);
    }

    /** 工具入参里同时接受 {@code task_id} 与 {@code taskId} 两种写法。 */
    private static String taskIdOf(JSONObject input) {
        return input.optString("task_id", input.optString("taskId", "")).trim();
    }

    // ------------------------------------------------------------ 权限与问答转发

    public boolean respondPermission(String requestId, boolean allow) {
        for (AgentTask task : tasks.values()) {
            ZhiCodeEngine engine = task.engine;
            if (engine != null && engine.respondPermissionLocal(requestId, allow)) return true;
        }
        return false;
    }

    public boolean respondQuestion(String requestId, JSONObject answers) {
        for (AgentTask task : tasks.values()) {
            ZhiCodeEngine engine = task.engine;
            if (engine != null && engine.respondQuestion(requestId, answers)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 工具白名单

    /**
     * 由代理定义算出子引擎能用的工具集合。
     *
     * <p>{@code inheritsAllTools()} 为真时给整份白名单；否则按名字取交集，
     * 最后统一减去 {@code disallowedTools}。顺序是「先加后减」而不是反过来：
     * 减号必须最后生效，否则「继承全部但禁用 Bash」这种写法会失效。
     */
    private LinkedHashSet<String> resolveAllowedTools(AgentDefinition definition) {
        LinkedHashSet<String> available = new LinkedHashSet<>(Arrays.asList(SUBAGENT_TOOLS));
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (definition.inheritsAllTools()) {
            allowed.addAll(available);
        } else {
            for (String tool : definition.tools) {
                String normalized = normalizeToolName(tool);
                if (available.contains(normalized)) allowed.add(normalized);
            }
        }
        for (String tool : definition.disallowedTools) allowed.remove(normalizeToolName(tool));
        return allowed;
    }

    private static String normalizeToolName(String name) {
        if (name == null) return "";
        String value = name.trim();
        String alias = TOOL_ALIASES.get(value.toLowerCase(Locale.US));
        return alias == null ? value : alias;
    }

    // ------------------------------------------------------------ 子代理提示词

    /**
     * 追加在系统提示词后面的「子代理说明」。
     *
     * <p>这几段是模型实际会读到的内容，因此每一段都有具体用途：
     * 说明自己在独立上下文里、不能再派生（否则它会试着派）；带上代理定义里的指令；
     * 预加载技能正文（不然它得先花一轮去读文件）；以及「记忆写到哪儿」——
     * 没有这一句，需要长期记忆的代理不知道往哪里写。
     */
    private String buildSystemSuffix(AgentDefinition definition, String prompt) {
        StringBuilder suffix = new StringBuilder()
                .append("\n\n# Subagent mode\nYou are running as the `").append(definition.name)
                .append("` subagent in a separate context window. Subagents cannot spawn other subagents.")
                .append(" Return only the result needed by the parent agent.\n");

        if (!definition.prompt.isEmpty()) {
            suffix.append("\n# Subagent instructions\n").append(definition.prompt).append('\n');
        }
        for (String skill : definition.skills) {
            String body = readSkill(skill);
            if (!body.isEmpty()) {
                suffix.append("\n# Preloaded skill: ").append(skill).append("\n").append(body).append('\n');
            }
        }
        boolean wantsMemory = !definition.memory.isEmpty() && !"none".equalsIgnoreCase(definition.memory);
        if (wantsMemory) {
            suffix.append("\nPersistent memory scope requested: ").append(definition.memory)
                    .append(". Store concise durable findings under ~/")
                    .append(TermuxConstants.DATA_DIR_NAME)
                    .append("/").append(MEMORY_DIR_NAME).append("/").append(definition.name)
                    .append(" when useful.\n");
        }
        return suffix.toString();
    }

    /** 读一份技能的正文。技能只有 HOME 下的数据目录这一个位置。 */
    private String readSkill(String name) {
        File file = new File(new File(TermuxConstants.dataDir(), SKILLS_DIR_NAME),
                name + "/" + SKILL_FILE_NAME);
        try {
            return readFileText(file);
        } catch (Exception unreadable) {
            // 技能读不到就让子代理按没有技能跑；这比让整次调用失败合理。
            return "";
        }
    }

    // ------------------------------------------------------------ 模型选择

    /**
     * 处理模型覆盖。
     *
     * <p>{@code inherit} / 空值表示「什么都不改」。
     * {@code haiku}/{@code sonnet}/{@code opus} 是<b>档位</b>而不是模型 id：
     * 本工程不内置任何厂商的模型名（那是厂商与用户之间的事），所以这三个词统一理解成
     * 「用父代理的模型」，而不是把它们当作 id 直接发出去。
     */
    private static void applyModelOverride(SessionConfig parent, SessionConfig child,
                                           AgentDefinition definition, String invocationModel) {
        String requested = invocationModel == null || invocationModel.isEmpty()
                ? definition.model
                : invocationModel;
        if (requested == null || requested.isEmpty() || "inherit".equalsIgnoreCase(requested)) return;
        child.model = MODEL_TIERS.contains(requested.trim().toLowerCase(Locale.US))
                ? parent.model
                : requested;
    }

    // ------------------------------------------------------------ 工作树

    private static boolean isWorktreeIsolation(String isolation) {
        return "worktree".equalsIgnoreCase(isolation);
    }

    /** 校验并规范化子代理的工作目录。目录不存在时直接拒绝，而不是让它跑起来再莫名失败。 */
    private static String canonicalDirectory(String cwd) throws Exception {
        File directory = new File(cwd).getCanonicalFile();
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("Subagent cwd does not exist: " + cwd);
        }
        return directory.getAbsolutePath();
    }

    /** 为子代理造一个 detached 工作树，让它在一个干净的副本上改代码。 */
    private String createWorktree(String project, String id) throws Exception {
        File root = new File(new File(TermuxConstants.dataDir(), WORKTREES_DIR_NAME), id);
        String rootPath = root.getAbsolutePath();
        String command = "git -C " + shellQuote(project) + " rev-parse --is-inside-work-tree >/dev/null"
                + " && mkdir -p " + shellQuote(root.getParent())
                + " && git -C " + shellQuote(project) + " worktree add --detach " + shellQuote(rootPath)
                + " HEAD";
        TermuxShellExecutor.Result result =
                new TermuxShellExecutor(context).execute(command, project, WORKTREE_ADD_TIMEOUT_MS);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Failed to create agent worktree: " + result.combined());
        }
        return rootPath;
    }

    /**
     * 子代理跑完之后收拾工作树。
     *
     * <p>只在工作树<b>干净</b>时才删：有未提交的改动说明子代理的成果还没被取走，
     * 删掉等于丢掉它的工作。清理失败也不报错 —— 它是一个收尾动作，
     * 不该把一个已经成功的任务变成失败。
     */
    private void cleanupWorktreeIfClean(String project, String worktree) {
        if (worktree == null || worktree.isEmpty()) return;
        try {
            TermuxShellExecutor shell = new TermuxShellExecutor(context);
            TermuxShellExecutor.Result dirty =
                    shell.execute("git status --porcelain", worktree, WORKTREE_STATUS_TIMEOUT_MS);
            if (dirty.exitCode != 0 || !dirty.combined().trim().isEmpty()) return;
            shell.execute("git -C " + shellQuote(project) + " worktree remove --force "
                    + shellQuote(worktree), project, WORKTREE_REMOVE_TIMEOUT_MS);
        } catch (Exception ignored) {
            // 见方法注释。
        }
    }

    /** 单引号包裹并转义内部单引号。shell 参数必须这样处理，不能用双引号。 */
    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    private static String worktreeNotice(AgentTask task) {
        return task.worktreePath == null || task.worktreePath.isEmpty()
                ? ""
                : "\n\nworktree: " + task.worktreePath;
    }

    private String joinAgentNames(String project) {
        StringBuilder names = new StringBuilder();
        for (AgentDefinition definition : definitions(project)) {
            if (names.length() > 0) names.append(", ");
            names.append(definition.name);
        }
        return names.toString();
    }

    // ------------------------------------------------------------ 落盘

    private void persist(AgentTask task) {
        try {
            File directory = new File(TermuxConstants.dataDir(), TASKS_DIR_NAME);
            directory.mkdirs();
            File file = new File(directory, task.id + ".json");
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(task.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 任务落盘只影响「重启后能不能列出历史任务」，不能影响正在跑的任务。
        }
    }

    /**
     * 按任务节流的落盘。
     *
     * <p>节流记的是<b>每个任务各自</b>的上次时间。共用一个字段的话，并行跑几个子代理时
     * 只有第一个能写盘，其余的进度会一直停在启动那一刻 —— 而界面正是靠它显示进度的。
     */
    private void persistThrottled(AgentTask task) {
        long now = System.currentTimeMillis();
        Long last = lastPersistAt.get(task.id);
        if (last != null && now - last <= PERSIST_INTERVAL_MS) return;
        lastPersistAt.put(task.id, now);
        persist(task);
    }

    /**
     * 读一个 UTF-8 文本文件。
     *
     * <p>按声明长度一次读满并循环补读：{@code read()} 可能只返回一部分
     * （管道、网络文件系统都会这样），少了一次补读会静默拿到半份技能正文。
     */
    private static String readFileText(File file) throws Exception {
        if (file == null || !file.isFile()) return "";
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            int count;
            while (offset < buffer.length && (count = input.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += count;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------ 工具 schema
}
