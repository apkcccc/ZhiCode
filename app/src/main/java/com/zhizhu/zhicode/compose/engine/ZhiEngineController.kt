package com.zhizhu.zhicode.compose.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zhizhu.zhicode.compose.model.ChatImage
import com.zhizhu.zhicode.compose.model.AgentTask
import com.zhizhu.zhicode.compose.model.EffortLevel
import com.zhizhu.zhicode.compose.model.McpServerStatus
import com.zhizhu.zhicode.compose.model.McpToolInfo
import com.zhizhu.zhicode.compose.model.PermissionMode
import com.zhizhu.zhicode.compose.model.TaskState
import com.termux.app.zhicode.core.ZhiCodeEngine
import org.json.JSONArray
import com.termux.app.zhicode.core.PermissionGate
import com.termux.app.zhicode.core.PermissionModePolicy
import com.termux.app.zhicode.core.PlanApprovalGate
import com.termux.app.zhicode.core.QuestionGate
import com.termux.app.zhicode.core.RiskClassifier
import com.termux.app.zhicode.tools.WebFetchTool
import com.termux.app.zhicode.tools.WebSearchTool
import com.termux.app.zhicode.model.PlanWorkflowState
import com.termux.app.zhicode.model.SessionConfig
import com.termux.app.zhicode.model.ToolCall
import com.termux.app.zhicode.model.ToolExecutionResult
import com.zhizhu.zhicode.compose.model.PlanApproval
import com.zhizhu.zhicode.compose.model.readChatImageBlocks
import com.termux.app.zhicode.storage.ApiSettingsStore
import com.termux.app.zhicode.tasks.TaskStore
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 界面侧覆盖项。
 *
 * 语义：`null` = **不覆盖**，沿用 [ApiSettingsStore] 里读出来的值。
 * 这样"设置页还没接线"的字段不会被这里悄悄写成默认值，
 * 造成"设置里明明开着、实际却关着"这种最难查的不一致。
 */
internal data class EngineOverrides(
    val permissionMode: String? = null,
    val effort: String? = null,
    val model: String? = null,
    val contextWindow: Int? = null,
    val projectDirectory: String? = null,
    val visionEnabled: Boolean? = null,
    val customSystemPrompt: String? = null,
    /** 角色卡内容。引擎把它作为 &lt;role_card&gt; 块注入系统提示词（见 SystemPromptBuilder）。 */
    val roleCard: String? = null,
    val autoCompact: Boolean? = null,
    val autoCompactPercent: Int? = null,
    val webSearchEnabled: Boolean? = null,
    val webSearchProvider: String? = null,
    val webSearchMaxResults: Int? = null,
    val webTimeoutSec: Int? = null,
    /** 密钥制搜索服务（Tavily/Exa/Brave）的 Key；只在选中的服务需要时非空。 */
    val webSearchApiKey: String? = null,
    /** SearXNG 实例地址；只有 searxng 服务用到。 */
    val webSearchBaseUrl: String? = null,
    /** 当前生效搜索服务的每服务选项（JSON，如 depth/topic/language）。 */
    val webSearchServiceConfig: String? = null,
    val rootExecutionEnabled: Boolean? = null,
    /** Shizuku 工具开关。与 Root 独立：它的前提是「用过 adb 启动过 Shizuku」，不是「设备 root」。 */
    val shizukuExecutionEnabled: Boolean? = null,
    val sandboxAgentFullAccess: Boolean? = null,
    val forcedKeepAliveEnabled: Boolean? = null,
)

/**
 * 一次计划审批请求在界面侧需要的全部内容。
 *
 * 公开可见性是被迫的：[EngineEvents] 是 public 接口，它的参数类型不能是 internal。
 */
data class EnginePlanApproval(
    val requestId: String,
    val title: String,
    val body: String,
    val revision: Int,
    val path: String,
    val permissionNote: String,
)

/**
 * 引擎事件。**保证在主线程回调**，实现方可以直接改界面状态。
 *
 * 之所以不让 ViewModel 直接实现 `ZhiCodeEngine.Listener`：
 * 引擎回调来自后台线程、参数是引擎内部的 Java 类型（`ToolCall` / `ToolExecutionResult` …），
 * 直接暴露会让状态机里到处散落 `post { }` 与字段解引用，也没法单独测。
 *
 * 公开可见性是被迫的：[WorkspaceViewModel] 是 public 类，它的父类型不能是 internal。
 */
interface EngineEvents {
    fun onEngineSessionStarted(model: String)
    fun onEngineText(delta: String)
    fun onEngineThinking(delta: String)
    fun onEngineToolBatchStarted(toolIds: List<String>)
    fun onEngineToolUse(id: String, name: String, input: JSONObject?)
    fun onEngineToolProgress(id: String, chunk: String, stderr: Boolean, elapsedMs: Long)
    fun onEngineToolResult(
        id: String,
        name: String,
        isError: Boolean,
        exitCode: Int,
        content: String,
        diff: String,
        addedLines: Int,
        deletedLines: Int,
        command: String,
        /**
         * 工具带回来的**富内容预览**（`ToolExecutionResult.additionalContent`）。
         *
         * 目前只有一个来源：`Sandbox` 工具的 `screenshot` 会把沙箱截图以
         * `{type:image, source:{type:base64,…}}` 的形式挂在这里。
         *
         * 这个参数**曾经不存在**，而引擎一直在把 additionalContent 发给模型
         * （`ZhiCodeEngine` 里放进 toolResults 当 user 消息）—— 于是截图是
         * 「模型看得到、用户看不到」。发布前它必须在签名里，否则又会被某一层丢掉。
         */
        previews: List<ChatImage>,
    )
    fun onEngineToolBatchCompleted(toolIds: List<String>)
    fun onEnginePermissionRequest(requestId: String, tool: String, summary: String, highRisk: Boolean)
    fun onEngineQuestionRequest(requestId: String, questionsJson: String)
    fun onEnginePlanApprovalRequest(request: EnginePlanApproval)
    fun onEngineTasksChanged(tasks: List<AgentTask>)
    fun onEngineProjectDirectoryChanged(directory: String)
    fun onEngineInterruptedBySteering()
    fun onEngineResponseRetry()
    fun onEngineQueuedPromptApplied()
    fun onEngineUsage(inputTokens: Long, outputTokens: Long)
    fun onEngineStatus(status: String)
    fun onEngineTurnComplete(stopReason: String)
    fun onEngineError(message: String, error: Throwable?)
}

/**
 * 真实 ZhiCode 引擎的接线层。
 *
 * 职责边界（刻意收窄，避免和 ViewModel 抢状态）：
 * - **引擎生命周期**：按需创建、配置、关闭；
 * - **线程搬运**：把后台回调全部投递到主线程；
 * - **事件节流**：流式文本按 32ms 合并成块（原版 `queueStreamingDelta` 的做法）；
 * - **类型翻译**：引擎的 Java 类型 → 界面用的轻量类型。
 *
 * 它**不**持有界面状态、不碰 `ChatItem`，那些都是 ViewModel 的事。
 */
internal class ZhiEngineController(
    context: Context,
    private val events: EngineEvents,
) : ZhiCodeEngine.Listener {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** 待合并的流式文本。引擎线程写、主线程读，必须加锁。 */
    private val deltaLock = Any()
    private val pendingText = StringBuilder()
    private var flushPosted = false
    private var pendingTextGeneration = 0L
    private val thinkingLock = Any()
    private val pendingThinking = StringBuilder()
    private var thinkingFlushPosted = false
    private var thinkingGeneration = 0L
    private val toolProgressLock = Any()
    private val pendingToolProgress = LinkedHashMap<String, ToolProgressBuffer>()
    private var toolProgressFlushPosted = false

    /** 回合代际。取消/重置后自增，用来静默丢弃在途的旧回调。 */
    @Volatile
    private var generation = 0L

    private var engine: ZhiCodeEngine? = null
    private var apiStore: ApiSettingsStore? = null

    /** 最近一次实际生效的配置，供界面查询（只读）。 */
    @Volatile
    var sessionConfig: SessionConfig? = null
        private set
    private var lastConfiguredFingerprint: String? = null

    private val flushDelta = Runnable { flushPendingText() }
    private val flushThinking = Runnable { flushPendingThinking() }
    private val flushToolProgress = Runnable { flushPendingToolProgress() }

    private data class ToolProgressBuffer(
        val id: String,
        val stderr: Boolean,
        val chunk: StringBuilder,
        var elapsedMs: Long,
    )

    /**
     * 引擎**生命周期**调用的串行后台线程。
     *
     * `cancel()` / `resetConversation()` 原本在主线程同步调引擎：前者要逐个放掉三个
     * 询问闩、打断两条线程、通知提供方掐断在读的流，后者还要清空历史 —— 重负载下
     * 能把主线程卡住几十毫秒，用户侧的感受是"点哪儿都慢半拍"（切会话、新会话、
     * 停止按钮都在这条路上）。
     *
     * 单线程 executor 而不是各开各的协程，是因为这些调用**必须按提交顺序执行**：
     * 「取消当前回合」后面跟着「载入历史会话」时，乱序会让载入被取消掉。
     * `sendPrompt` 仍留在调用线程（错误要当场报给发送方，且引擎内部自己有单线程
     * 队列），排队的取消靠 [generation] 守卫失效 —— 见 [cancel] 的说明。
     */
    private val engineOps = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zhi-engine-ops").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 拿到引擎，需要时创建并配置。 */
    fun engine(): ZhiCodeEngine {
        engine?.let { return it }
        val created = ZhiCodeEngine(appContext, this)
        engine = created
        return created
    }

    /**
     * 用「设置里读出来的配置」叠加界面侧的覆盖项，然后下发到引擎。
     *
     * 每次发消息前都调用一次：用户在设置里改了权限模式/模型后，
     * 不需要重启应用就能在下一轮生效。
     */
    fun configure(overrides: EngineOverrides): SessionConfig {
        val store = apiStore ?: ApiSettingsStore(appContext).also { apiStore = it }
        val base = runCatching { store.load() }.getOrElse { SessionConfig() }
        applyEngineOverrides(base, overrides)
        val fingerprint = configurationFingerprint(base)
        val changed = fingerprint != lastConfiguredFingerprint
        sessionConfig = base
        engine().configure(base)
        if (changed) lastConfiguredFingerprint = fingerprint
        // ⚠️ 这里必须落盘。原先只把值写进内存与引擎，`ApiSettingsStore.save()` 在
        // 整个工程里**零调用方** —— 于是权限模式、推理档、上下文窗口、项目目录、
        // 联网搜索那一整套、自动压缩、自定义提示词，全都重启即丢。它们的存储键与
        // 读取路径一直齐备，缺的就是这一次写入。
        //
        // 不需要去抖或线程转发：`save()` 内部只做 SharedPreferences.apply()
        // （内存立即生效、磁盘异步写），唯一的重活——密钥加密——只在密钥**真的变了**
        // 时发生，而这里的 base 来自 store.load()，密钥与库里一致，不会触发。
        // 调用频率是「每条消息一次」，不是热路径。
        if (changed) runCatching { store.save(base) }
        return base
    }

    private fun configurationFingerprint(config: SessionConfig): String = listOf(
        config.profileId, config.profileRevision.toString(), config.credentialRevision.toString(),
        config.protocol, config.baseUrl, config.extraHeaders, config.model, config.effort,
        config.permissionMode, config.projectDirectory, config.customSystemPrompt,
        config.roleCard, config.toolMode, config.visionEnabled.toString(),
        config.autoCompact.toString(), config.contextWindowTokens.toString(),
        config.webSearchEnabled.toString(), config.webSearchProvider,
        config.webSearchMaxResults.toString(), config.webTimeoutMs.toString(),
        config.rootExecutionEnabled.toString(), config.sandboxAgentFullAccess.toString(),
        config.shizukuExecutionEnabled.toString(),
        config.forcedKeepAliveEnabled.toString(), config.workflowId,
    ).joinToString("\u0000")

    fun isBusy(): Boolean = engine?.isBusy ?: false

    /** 当前回合已经开始（用于区分「该排队」还是「该直接发」）。 */
    fun hasEngine(): Boolean = engine != null

    /**
     * 发送一轮请求。
     *
     * [extraContent] 是 Anthropic 风格的附加内容块（当前只有图片：base64 + media_type）。
     * 引擎侧会自己按 `visionEnabled` 决定是否真的发出去（见 `VisionMessageFilter`），
     * 所以这里不做二次判断——两处都判会出现"界面拦了但引擎其实允许"这类不一致。
     */
    fun sendPrompt(prompt: String, extraContent: JSONArray? = null) {
        // 新回合开一代：让排队的旧取消（cancel() 的代际守卫）自行动失效，
        // 否则"停止 → 立刻再发"时，排在后台队列里的那次取消会杀掉这条新回合。
        generation++
        if (extraContent == null || extraContent.length() == 0) engine().sendPrompt(prompt)
        else engine().sendPrompt(prompt, extraContent)
    }

    /** 排队一条预输入。返回 false 表示引擎当时并不忙（调用方应改走 [sendPrompt]）。 */
    fun steer(prompt: String, messageId: String, extraContent: JSONArray? = null): Boolean =
        engine?.steerPrompt(prompt, extraContent ?: JSONArray(), messageId) ?: false

    /**
     * 停止当前回合。
     *
     * 自增代际：引擎的 `cancel()` 之后仍可能有一两个在途回调，
     * 不丢弃的话会在新一轮对话里插入上一轮的尾巴。
     *
     * 主线程只做簿记（代际、缓冲、回调清理）；引擎调用排进 [engineOps]。
     * 提交时记下代际，执行时若代际已经变了（期间发了新回合），就**跳过**这次取消
     * —— 否则排在队列里的旧取消会把用户新发出的回合杀掉。
     */
    fun cancel() {
        generation++
        synchronized(deltaLock) {
            pendingText.setLength(0)
            flushPosted = false
            pendingTextGeneration = generation
        }
        main.removeCallbacks(flushDelta)
        main.removeCallbacks(flushThinking)
        main.removeCallbacks(flushToolProgress)
        synchronized(thinkingLock) {
            pendingThinking.setLength(0)
            thinkingFlushPosted = false
            thinkingGeneration = generation
        }
        synchronized(toolProgressLock) {
            pendingToolProgress.clear()
            toolProgressFlushPosted = false
        }
        val genAtSubmit = generation
        engineOps.execute {
            if (generation != genAtSubmit) return@execute
            runCatching { engine?.cancel() }
        }
    }

    fun resumeConversation(sessionId: String) {
        require(sessionId.isNotBlank()) { "会话标识为空" }
        generation++
        synchronized(deltaLock) {
            pendingText.setLength(0)
            flushPosted = false
            pendingTextGeneration = generation
        }
        main.removeCallbacks(flushDelta)
        main.removeCallbacks(flushThinking)
        main.removeCallbacks(flushToolProgress)
        synchronized(thinkingLock) {
            pendingThinking.setLength(0)
            thinkingFlushPosted = false
            thinkingGeneration = generation
        }
        synchronized(toolProgressLock) {
            pendingToolProgress.clear()
            toolProgressFlushPosted = false
        }
        val genAtSubmit = generation
        engineOps.execute {
            if (generation != genAtSubmit) return@execute
            runCatching { engine().resumeConversation(sessionId) }
                .onFailure { error ->
                    onMainForGeneration(genAtSubmit) {
                        events.onEngineError("恢复会话失败：${error.message ?: error}", error)
                    }
                }
        }
    }

    fun resetConversation() {
        generation++
        synchronized(deltaLock) {
            pendingText.setLength(0)
            flushPosted = false
            pendingTextGeneration = generation
        }
        main.removeCallbacks(flushDelta)
        main.removeCallbacks(flushThinking)
        main.removeCallbacks(flushToolProgress)
        synchronized(thinkingLock) {
            pendingThinking.setLength(0)
            thinkingFlushPosted = false
            thinkingGeneration = generation
        }
        synchronized(toolProgressLock) {
            pendingToolProgress.clear()
            toolProgressFlushPosted = false
        }
        // 与 cancel() 同样的代际守卫：排队的重置在执行时若已来了新回合，就让它过去
        // （引擎自己的 resetConversation() 开头也会再 cancel 一次，语义不会丢）。
        val genAtSubmit = generation
        engineOps.execute {
            if (generation != genAtSubmit) return@execute
            runCatching { engine?.resetConversation() }
        }
    }

    fun shutdown() {
        generation++
        main.removeCallbacks(flushDelta)
        main.removeCallbacks(flushThinking)
        main.removeCallbacks(flushToolProgress)
        runCatching { engine?.shutdown() }
        engineOps.shutdownNow()
    }

    // ------------------------------------------------------------------
    // 界面回执
    // ------------------------------------------------------------------

    fun respondPermission(requestId: String, allow: Boolean): Boolean =
        runCatching { engine?.respondPermission(requestId, allow) ?: false }.getOrDefault(false)

    fun respondQuestion(requestId: String, answers: JSONObject): Boolean =
        runCatching { engine?.respondQuestion(requestId, answers) ?: false }.getOrDefault(false)

    fun respondPlanApproval(requestId: String, decision: PlanApprovalGate.Decision, feedback: String): Boolean =
        runCatching { engine?.respondPlanApproval(requestId, decision, feedback) ?: false }.getOrDefault(false)

    fun planWorkflowState(): PlanWorkflowState = engine?.planWorkflowState ?: PlanWorkflowState.idle()

    /** `/plan`：进入只读计划模式。 */
    fun enterPlanModeFromUi(): ToolExecutionResult = engine().enterPlanModeFromUi()

    /** `/plan off`：退出计划模式并附带驳回理由。 */
    fun cancelPlanModeFromUi(feedback: String): ToolExecutionResult = engine().cancelPlanModeFromUi(feedback)

    // ------------------------------------------------------------------
    // 上下文用量
    // ------------------------------------------------------------------

    fun estimateContextTokens(): Int = runCatching { engine?.estimateContextTokens() ?: 0 }.getOrDefault(0)

    /** 当前会话的标识（`sessions.db` 里的 id）。引擎尚未配置或还没写过内容时为 null。 */
    fun sessionId(): String? = runCatching { engine?.sessionId }.getOrNull()

    /**
     * 内存里这份引擎历史的规模 —— **只给诊断日志用**。
     *
     * <p>{@code historyChars} 会走一次全量序列化，所以这里刻意不把它放进任何
     * 逐帧/循环路径：调用点只有"打开并恢复了一条会话"，一次一个数。
     * 它回答的是这轮排查最关键的那个问题：**常驻到底是引擎的历史，还是界面那 200 条**。
     */
    fun memoryFootprint(): Pair<Int, Long> = runCatching {
        val current = engine ?: return@runCatching 0 to -1L
        current.messageCount() to current.historyChars()
    }.getOrDefault(0 to -1L)

    fun hasMeasuredContextUsage(): Boolean =
        runCatching { engine?.hasMeasuredContextUsage() ?: false }.getOrDefault(false)

    fun contextWindowTokens(): Int = sessionConfig?.contextWindowTokens ?: 0

    /**
     * 引擎当前实际使用的模型名。
     *
     * 存在的理由：底栏那个模型标签是**界面自己**算出来的（可能带会话级覆盖），
     * 光看标签无法区分"界面以为换了"和"引擎真的收到了"。排查"模型切了但请求还是旧的"
     * 这类问题时需要一个直接读引擎配置的口子。
     */
    fun configuredModel(): String = sessionConfig?.model.orEmpty().ifEmpty { "（引擎未配置）" }

    /**
     * 引擎当前的系统提示词。
     *
     * 与 [configuredModel] 同样是为了"直接问引擎"：设置页里的输入框只证明界面存下了
     * 这段文字，不证明它被送进了请求。这一项尤其需要对照——它属于"改了看起来没事，
     * 但可能整轮都没生效"的那类设置。
     */
    fun configuredSystemPrompt(): String = sessionConfig?.customSystemPrompt.orEmpty()

    /**
     * 引擎侧当前是否允许联网搜索。
     *
     * 设置页那个开关改的是界面状态与 `SessionConfig`，中间隔了一次 `configure`，
     * 而 `/web on|off` 最容易出的问题正是"开关变了但没下发"。
     * 有这个读取口就能直接对照，而不是靠界面自己声明。
     */
    fun configuredWebSearchEnabled(): Boolean = sessionConfig?.webSearchEnabled == true

    /**
     * 引擎当前的动态角色卡内容。
     *
     * 角色卡最容易出的问题是"列表里勾着但请求里没有"（清单与生效内容两份数据没同步），
     * 所以需要一个直接读引擎配置的口子来对照，而不是相信界面的勾选态。
     */
    fun configuredRoleCard(): String = sessionConfig?.roleCard.orEmpty()

    /**
     * 手动触发一次上下文压缩（`/compact`）。
     *
     * 引擎的 [ZhiCodeEngine.compactContext] 是**同步阻塞**的：它要调用模型生成语义摘要，
     * 期间会走网络。所以调用方必须放在 IO 线程，并且注意它返回的是摘要文本，
     * 不是"成功/失败"。
     *
     * 忙碌时直接拒绝：压缩会改写会话历史，和正在跑的回合抢同一份上下文，
     * 两边同时动必然出错。
     */
    fun compactContext(instructions: String): Result<String> = runCatching {
        check(!isBusy()) { "任务正在运行，完成后再压缩上下文" }
        val engine = engine ?: error("引擎尚未初始化")
        engine.compactContext(instructions.trim())
    }

    /**
     * 列出已启用的 MCP 服务器及其工具清单（界面「测试连接」用）。
     *
     * ⚠️ **阻塞且昂贵**：每台服务器都要真的起一次子进程或发一轮 HTTP，单台超时 30 秒。
     * 所以必须放 IO 线程，且**只能由用户点「测试连接」触发** ——
     * 在打开 MCP 页时自动跑一遍，等于把"看配置"变成"把每台服务器都启动一次"，
     * 而且在用户还没决定要改什么之前就产生了副作用。
     *
     * 解析在这里做（而不是把 JSON 交给界面）：界面模型应当由一处产出，
     * 两处各自解析同一份协议 JSON 迟早会分叉。单个服务器失败**不**影响整体，
     * 那一行会带上 `ok=false` 与错误原因 —— 让一个配错的服务器挡住其它可用的，
     * 是最难查的一种"功能没了"。
     */
    fun mcpStatus(): Result<List<McpServerStatus>> = runCatching {
        val engine = engine ?: error("引擎尚未初始化")
        parseMcpStatus(engine.mcpServers())
    }

    private fun parseMcpStatus(rows: JSONArray): List<McpServerStatus> = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val name = row.optString("server", "")
            if (name.isEmpty()) continue
            val ok = row.optBoolean("ok", false)
            add(
                McpServerStatus(
                    name = name,
                    connected = ok,
                    error = if (ok) "" else row.optString("error", "未知原因"),
                    tools = McpToolInfo.parse(row.optJSONArray("tools")),
                ),
            )
        }
    }

    /**
     * 手动联网搜索（`/web 关键词`）。
     *
     * 直接执行 [WebSearchTool]，**不经过模型**：原版 `runManualWebSearch` 就是这么做的，
     * 好处是用户输入后立刻拿到结果，不需要一次模型往返（也就不会消耗上下文）。
     *
     * 同步阻塞（走网络），调用方需放 IO 线程。
     */
    fun manualWebSearch(query: String): Result<String> = runCatching {
        val config = currentConfig()
        check(config.webSearchEnabled) { "联网搜索已关闭，可输入 /web on 开启" }
        val result = WebSearchTool().execute(config, JSONObject().put("query", query.trim()))
        if (result.isError) throw IllegalStateException(result.content)
        result.content
    }

    /**
     * 手动读取网页（`/web fetch <url>`）。
     *
     * 与 [manualWebSearch] 同理，直接执行 [WebFetchTool]。
     */
    fun manualWebFetch(url: String): Result<String> = runCatching {
        val config = currentConfig()
        check(config.webSearchEnabled) { "联网搜索已关闭，可输入 /web on 开启" }
        val result = WebFetchTool().execute(config, JSONObject().put("url", url.trim()))
        if (result.isError) throw IllegalStateException(result.content)
        result.content
    }

    /**
     * 取当前生效的会话配置。
     *
     * 优先用已经 `configure` 过的那份（它带着界面覆盖项，例如界面里刚改的搜索条数）；
     * 没有则直接从存储读——手动搜索可能在还没发过任何消息时就触发。
     */
    private fun currentConfig(): SessionConfig =
        sessionConfig ?: ApiSettingsStore(appContext).load()

    fun contextPercent(): Int = runCatching { engine?.contextPercent() ?: 0 }.getOrDefault(0)

    // ------------------------------------------------------------------
    // 流式文本合并（原版 queueStreamingDelta：32ms 合并成块）
    // ------------------------------------------------------------------

    private fun queueText(delta: String) {
        if (delta.isEmpty()) return
        synchronized(deltaLock) {
            val gen = currentGeneration()
            if (pendingTextGeneration != gen) {
                pendingText.setLength(0)
                pendingTextGeneration = gen
            }
            pendingText.append(delta)
            if (flushPosted) return
            flushPosted = true
        }
        main.postDelayed(flushDelta, DELTA_MERGE_MS)
    }

    /** 主线程：把合并好的块交给界面。 */
    private fun flushPendingText() {
        val chunk: String
        val gen: Long
        synchronized(deltaLock) {
            chunk = pendingText.toString()
            pendingText.setLength(0)
            flushPosted = false
            gen = pendingTextGeneration
        }
        if (chunk.isEmpty() || gen != generation) return
        events.onEngineText(chunk)
    }

    /**
     * 立即把缓冲里的文本交给界面。
     *
     * 工具/结束/错误事件之前**必须**调用，否则工具卡会插到还没显示的文本前面（顺序错乱）。
     * 只能在主线程调用。
     */
    private fun flushTextNow() {
        main.removeCallbacks(flushDelta)
        flushPendingText()
    }

    private fun currentGeneration(): Long = generation

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    /** 投递到主线程并校验代际；代际不符则静默丢弃（同原版 `postRuntimeUi`）。 */
    private fun onMainForGeneration(gen: Long, action: () -> Unit) {
        main.post {
            if (gen != generation) return@post
            action()
        }
    }

    // ------------------------------------------------------------------
    // ZhiCodeEngine.Listener —— 21 个回调
    // ------------------------------------------------------------------

    override fun onSessionStarted(config: SessionConfig) {
        val gen = generation
        onMainForGeneration(gen) {
            events.onEngineSessionStarted(config.model ?: "")
        }
    }

    override fun onTextDelta(text: String) {
        if (text.isNullOrEmpty()) return
        queueText(text)
    }

    override fun onThinkingDelta(thinking: String) {
        if (thinking.isNullOrEmpty()) return
        synchronized(thinkingLock) {
            if (thinkingGeneration != generation) {
                pendingThinking.setLength(0)
                thinkingGeneration = generation
            }
            pendingThinking.append(thinking)
            if (thinkingFlushPosted) return
            thinkingFlushPosted = true
        }
        main.postDelayed(flushThinking, DELTA_MERGE_MS)
    }

    override fun onToolBatchStarted(batch: ZhiCodeEngine.ToolBatch?) {
        val ids = batch?.toolCalls?.filterNotNull()?.map { it.id ?: "" } ?: return
        if (ids.isEmpty()) return
        val gen = generation
        onMainForGeneration(gen) { events.onEngineToolBatchStarted(ids) }
    }

    override fun onToolBatchCompleted(batch: ZhiCodeEngine.ToolBatch?) {
        val ids = batch?.toolCalls?.filterNotNull()?.map { it.id ?: "" } ?: return
        if (ids.isEmpty()) return
        val gen = generation
        onMainForGeneration(gen) { events.onEngineToolBatchCompleted(ids) }
    }

    override fun onToolUse(call: ToolCall?) {
        if (call == null) return
        val gen = generation
        onMainForGeneration(gen) {
            // 引擎保证工具卡插在已有文本之后，所以这里先落盘再发工具事件。
            flushTextNow()
            events.onEngineToolUse(call.id ?: "", call.name ?: "工具", call.input)
        }
    }

    override fun onToolProgress(call: ToolCall?, chunk: String?, stderr: Boolean, elapsedMs: Long) {
        val id = call?.id ?: return
        if (chunk.isNullOrEmpty()) return
        synchronized(toolProgressLock) {
            val existing = pendingToolProgress[id]
            if (existing == null || existing.stderr != stderr) {
                pendingToolProgress[id] = ToolProgressBuffer(id, stderr, StringBuilder(chunk), elapsedMs)
            } else {
                existing.chunk.append(chunk)
                existing.elapsedMs = maxOf(existing.elapsedMs, elapsedMs)
            }
            if (toolProgressFlushPosted) return
            toolProgressFlushPosted = true
        }
        main.postDelayed(flushToolProgress, TOOL_PROGRESS_MERGE_MS)
    }

    private fun flushPendingThinking() {
        val text: String
        val gen: Long
        synchronized(thinkingLock) {
            text = pendingThinking.toString()
            pendingThinking.setLength(0)
            thinkingFlushPosted = false
            gen = thinkingGeneration
        }
        if (text.isNotEmpty() && gen == generation) events.onEngineThinking(text)
    }

    private fun flushPendingToolProgress() {
        val rows: List<ToolProgressBuffer>
        synchronized(toolProgressLock) {
            rows = pendingToolProgress.values.toList()
            pendingToolProgress.clear()
            toolProgressFlushPosted = false
        }
        if (rows.isEmpty()) return
        rows.forEach { row -> events.onEngineToolProgress(row.id, row.chunk.toString(), row.stderr, row.elapsedMs) }
    }

    override fun onToolResult(call: ToolCall?, result: ToolExecutionResult?) {
        if (call == null || result == null) return
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineToolResult(
                id = call.id ?: "",
                name = call.name ?: "工具",
                isError = result.isError,
                exitCode = result.exitCode,
                content = result.content ?: "",
                diff = result.diff ?: "",
                addedLines = result.addedLines,
                deletedLines = result.deletedLines,
                command = call.input?.optString("command", "") ?: "",
                // 解析与解码是**分开**的：这里只把 base64 字符串搬进 UI 模型，
                // 真正的 BitmapFactory 解码在界面上跑 Dispatchers.Default
                // （见 ZhiImage.rememberDecodeState），所以这一步在主线程上是廉价的。
                previews = result.additionalContent()?.let { readChatImageBlocks(it) } ?: emptyList(),
            )
        }
    }

    override fun onPermissionRequest(request: PermissionGate.PermissionRequest?) {
        if (request == null) return
        val gen = generation
        val call = request.call
        val name = call?.name ?: "工具"
        // 「高风险」按**命令内容**判，不按工具种类判。
        //
        // 原先这里是 `kind == SYSTEM || kind == SHELL` —— 等于凡是跑 shell 的都挂红标，
        // 于是 `ls` 和 `rm -rf /` 长得一模一样。红标天天出现就不再是信息，用户学会的
        // 是忽略它。判定表在 RiskClassifier（纯 JVM，有单测）。
        val highRisk = runCatching { RiskClassifier.isHighRisk(name, call?.input) }
            .getOrDefault(false)
        val summary = ToolText.summary(name, call?.input) ?: ""
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEnginePermissionRequest(request.requestId, name, summary, highRisk)
        }
    }

    override fun onQuestionRequest(request: QuestionGate.QuestionRequest?) {
        if (request == null) return
        val gen = generation
        val json = request.questions?.toString() ?: "[]"
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineQuestionRequest(request.requestId, json)
        }
    }

    override fun onPlanStateChanged(state: PlanWorkflowState?) {
        // 计划状态本身由引擎持有，界面按需查询；这里不需要推事件。
    }

    override fun onPlanApprovalRequest(request: PlanApprovalGate.ApprovalRequest?) {
        if (request == null) return
        val plan = request.plan
        val payload = EnginePlanApproval(
            requestId = request.requestId,
            title = "实现计划 · revision ${plan.revision}",
            body = plan.planText ?: "",
            revision = plan.revision.toInt(),
            path = plan.planFile ?: "",
            permissionNote = "批准只确认计划；后续操作仍按你的权限模式",
        )
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEnginePlanApprovalRequest(payload)
        }
    }

    override fun onTasksChanged(snapshot: TaskStore.Snapshot?) {
        if (snapshot == null) return
        val tasks = snapshot.tasks.mapNotNull { toAgentTask(it) }
        val gen = generation
        onMainForGeneration(gen) { events.onEngineTasksChanged(tasks) }
    }

    override fun onProjectDirectoryChanged(projectDirectory: String?) {
        if (projectDirectory.isNullOrBlank()) return
        val gen = generation
        onMainForGeneration(gen) { events.onEngineProjectDirectoryChanged(projectDirectory) }
    }

    override fun onResponseInterruptedBySteering() {
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineInterruptedBySteering()
        }
    }

    override fun onResponseRetry() {
        val gen = generation
        onMainForGeneration(gen) {
            // 重试会重发整段回复：缓冲里属于上一次失败尝试的残片必须丢掉，
            // 否则界面上会留下"半句话 + 重新开始的完整回复"。
            synchronized(deltaLock) { pendingText.setLength(0) }
            main.removeCallbacks(flushDelta)
            events.onEngineResponseRetry()
        }
    }

    override fun onQueuedPromptApplied() {
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineQueuedPromptApplied()
        }
    }

    override fun onUsage(inputTokens: Long, outputTokens: Long) {
        val gen = generation
        onMainForGeneration(gen) { events.onEngineUsage(inputTokens, outputTokens) }
    }

    override fun onStatus(status: String?) {
        val gen = generation
        onMainForGeneration(gen) { events.onEngineStatus(status ?: "") }
    }

    override fun onTurnComplete(stopReason: String?) {
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineTurnComplete(stopReason ?: "end_turn")
        }
    }

    override fun onError(message: String?, error: Throwable?) {
        val gen = generation
        onMainForGeneration(gen) {
            flushTextNow()
            events.onEngineError(message ?: error?.message ?: "未知错误", error)
        }
    }

    // ------------------------------------------------------------------

    /** TaskStore 的任务 JSON → 界面条目。键名见 [TaskStore] 的写入处。 */
    private fun toAgentTask(json: JSONObject): AgentTask? {
        val subject = json.optString("subject", "")
        if (subject.isBlank()) return null
        val detail = json.optString("description", "")
        val activeForm = json.optString("activeForm", "")
        val state = when (json.optString("status", "pending")) {
            "completed" -> TaskState.DONE
            "in_progress" -> TaskState.RUNNING
            else -> TaskState.PENDING
        }
        return AgentTask(
            title = subject,
            detail = if (state == TaskState.RUNNING && activeForm.isNotBlank()) activeForm else detail,
            state = state,
        )
    }

    private companion object {
        /** 流式文本合并窗口。原版 `queueStreamingDelta` 用的是 32ms。 */
        const val DELTA_MERGE_MS = 32L
        const val TOOL_PROGRESS_MERGE_MS = 48L
    }
}

// ----------------------------------------------------------------------
// 界面枚举 → 引擎字符串
// ----------------------------------------------------------------------

internal fun PermissionMode.toEngineMode(): String = when (this) {
    PermissionMode.ASK -> PermissionModePolicy.DEFAULT
    PermissionMode.ACCEPT_EDITS -> PermissionModePolicy.ACCEPT_EDITS
    PermissionMode.PLAN -> PermissionModePolicy.PLAN
    PermissionMode.AUTO -> PermissionModePolicy.AUTO
    PermissionMode.DONT_ASK -> PermissionModePolicy.DONT_ASK
    PermissionMode.BYPASS -> PermissionModePolicy.BYPASS
}

internal fun EffortLevel.toEngineEffort(): String = when (this) {
    EffortLevel.LOW -> "low"
    EffortLevel.MEDIUM -> "medium"
    EffortLevel.HIGH -> "high"
    EffortLevel.MAX -> "max"
    EffortLevel.AUTO -> "auto"
}

/**
 * 引擎推理档 → 界面枚举。
 *
 * 与 [engineModeToUi] 成对：启动时要把盘上存的值读回界面状态，
 * 而盘上存的是**引擎字符串**（`low`/`medium`/…）。
 *
 * 认不出来时回落到 [EffortLevel.AUTO]（界面的默认档），
 * 而不是抛异常 —— 用户手动改过 prefs、或将来引擎加了新档位时，
 * 正确的反应是"用默认值"，不是崩在启动路径上。
 */
internal fun engineEffortToUi(effort: String?): EffortLevel {
    val key = effort?.trim()?.lowercase().orEmpty()
    return EffortLevel.entries.firstOrNull { it.toEngineEffort() == key } ?: EffortLevel.AUTO
}

/** 引擎权限模式 → 界面枚举（引擎回传的可能是 `default`）。 */
internal fun engineModeToUi(mode: String?): PermissionMode = when (PermissionModePolicy.normalize(mode)) {
    PermissionModePolicy.ACCEPT_EDITS -> PermissionMode.ACCEPT_EDITS
    PermissionModePolicy.PLAN -> PermissionMode.PLAN
    PermissionModePolicy.AUTO -> PermissionMode.AUTO
    PermissionModePolicy.DONT_ASK -> PermissionMode.DONT_ASK
    PermissionModePolicy.BYPASS -> PermissionMode.BYPASS
    else -> PermissionMode.ASK
}

/** 界面用的审批模型（供 `PlanApprovalOverlay` 直接渲染）。 */
internal fun EnginePlanApproval.toUiPlanApproval(): PlanApproval = PlanApproval(
    id = requestId,
    title = title,
    body = body,
    revision = revision,
    path = path,
    permissionNote = permissionNote,
)

/**
 * 把界面侧的覆盖项写进一份 `SessionConfig`。
 *
 * ## 为什么它必须是**顶层函数**而不是控制器里的私有方法
 *
 * 原来的形态是 `ZhiEngineController` 的私有方法，只有 `configure()` 一条路径会用它。
 * 于是"设置页改一项 → 落盘"这件事只能靠**顺带**发生（下一次发消息时 `configure()`）。
 * 用户改完设置直接大退，盘上还是旧值 —— 这就是「自动保存完全没修好」的本体。
 *
 * 提取出来之后，落盘路径（`WorkspaceViewModel.persistEngineSettings`）与发送路径
 * 共用**同一段映射**，两边不会再各自漂移：新增一个设置项只改这一处，
 * 不会出现"界面上能改、发消息时生效、但重启就丢"的字段。
 *
 * ⚠️ 传入的 [config] 必须来自 `ApiSettingsStore.load()`（即盘上那份），
 * 而不是凭空 `SessionConfig()`：`save()` 会整表覆盖，凭空构造会把 API 密钥槽
 * 等本函数不管的字段一起清空。
 */
internal fun applyEngineOverrides(config: SessionConfig, o: EngineOverrides) {
    o.permissionMode?.let { config.permissionMode = PermissionModePolicy.normalize(it) }
    o.effort?.let { if (it.isNotBlank()) config.effort = it }
    o.model?.let { if (it.isNotBlank()) config.model = it }
    o.contextWindow?.let { if (it > 0) config.contextWindowTokens = it }
    o.projectDirectory?.let { if (it.isNotBlank()) config.projectDirectory = it }
    o.visionEnabled?.let { config.visionEnabled = it }
    o.customSystemPrompt?.let { config.customSystemPrompt = it }
    o.autoCompact?.let { config.autoCompact = it }
    // 界面用的是百分比整数，引擎用的是倍率；在这里换算，别把两种口径泄进引擎。
    o.autoCompactPercent?.let { config.autoCompactRatio = (it.coerceIn(50, 100)) / 100.0 }
    o.webSearchEnabled?.let { config.webSearchEnabled = it }
    o.webSearchProvider?.let { if (it.isNotBlank()) config.webSearchProvider = it }
    o.webSearchMaxResults?.let { if (it > 0) config.webSearchMaxResults = it }
    o.webTimeoutSec?.let { if (it > 0) config.webTimeoutMs = it * 1000 }
    o.webSearchApiKey?.let { if (it.isNotBlank()) config.webSearchApiKey = it }
    o.webSearchBaseUrl?.let { config.webSearchBaseUrl = it }
    // 空串也要写：用户在设置里清掉地址/选项之后，引擎侧不该继续用旧值。
    o.webSearchServiceConfig?.let { config.webSearchServiceConfig = it }
    o.rootExecutionEnabled?.let { config.rootExecutionEnabled = it }
    o.shizukuExecutionEnabled?.let { config.shizukuExecutionEnabled = it }
    o.sandboxAgentFullAccess?.let { config.sandboxAgentFullAccess = it }
    o.forcedKeepAliveEnabled?.let { config.forcedKeepAliveEnabled = it }
    o.roleCard?.let { config.roleCard = it }
}

/**
 * 反向：把盘上的 `SessionConfig` 读回界面可编辑的那几项。
 *
 * ## 为什么必须有这个方向
 *
 * 启动路径是 `restore* → syncActiveProfile() → configure()`，而 `configure()`
 * 会**落盘**，写下去的却是界面此刻的状态。界面若还是默认值，那一刻盘上刚存的
 * 用户设置就被默认值冲掉了 —— 这正是「大退后恢复成未修改的样子」的第二个成因
 * （第一个是改动根本没写盘，见 [applyEngineOverrides]）。
 *
 * 所以只读回 `permissionMode`/`effort` 两项是不够的：**凡是被 `configure()`
 * 反写的字段，都必须在它之前读回来**，否则每启动一次就被冲一次。
 *
 * 返回 null 表示盘上那份读不出来（例如首次安装），调用方保持界面原值即可。
 */
internal fun readEngineSettings(store: ApiSettingsStore): EngineOverrides? =
    runCatching { store.load() }.getOrNull()?.let { config ->
        EngineOverrides(
            permissionMode = PermissionModePolicy.normalize(config.permissionMode),
            effort = config.effort,
            contextWindow = config.contextWindowTokens,
            projectDirectory = config.projectDirectory,
            customSystemPrompt = config.customSystemPrompt,
            visionEnabled = config.visionEnabled,
            autoCompact = config.autoCompact,
            autoCompactPercent = (config.autoCompactRatio * 100).toInt().coerceIn(50, 100),
            webSearchEnabled = config.webSearchEnabled,
            webSearchProvider = config.webSearchProvider,
            webSearchMaxResults = config.webSearchMaxResults,
            webTimeoutSec = config.webTimeoutMs / 1000,
            webSearchApiKey = config.webSearchApiKey,
            webSearchBaseUrl = config.webSearchBaseUrl,
            webSearchServiceConfig = config.webSearchServiceConfig,
            rootExecutionEnabled = config.rootExecutionEnabled,
            shizukuExecutionEnabled = config.shizukuExecutionEnabled,
            sandboxAgentFullAccess = config.sandboxAgentFullAccess,
            forcedKeepAliveEnabled = config.forcedKeepAliveEnabled,
        )
    }
