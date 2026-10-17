package com.zhizhu.zhicode.compose.state

import android.net.Uri
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhizhu.zhicode.compose.data.ApiConfigStore
import com.zhizhu.zhicode.compose.data.AttachmentReader
import com.zhizhu.zhicode.compose.data.Clipboard
import com.zhizhu.zhicode.compose.data.DebugApiProfile
import com.zhizhu.zhicode.compose.data.FileBrowser
import com.zhizhu.zhicode.compose.data.McpStore
import com.zhizhu.zhicode.compose.data.MockWorkspaceRepository
import com.zhizhu.zhicode.compose.data.ModelCatalogStore
import com.zhizhu.zhicode.compose.data.SessionReader
import com.zhizhu.zhicode.compose.data.RoleCardStore
import com.zhizhu.zhicode.compose.data.MemoryStore
import com.zhizhu.zhicode.compose.data.SkillImport
import com.zhizhu.zhicode.compose.data.SkillStore
import com.zhizhu.zhicode.compose.data.WorkspacePaths
import com.zhizhu.zhicode.compose.data.WorkspaceRepository
import com.zhizhu.zhicode.compose.engine.EngineEvents
import com.zhizhu.zhicode.compose.engine.EngineOverrides
import com.zhizhu.zhicode.compose.engine.EnginePlanApproval
import com.zhizhu.zhicode.compose.engine.ZhiEngineController
import com.zhizhu.zhicode.compose.engine.ToolText
// Shizuku 状态查询：斜杠命令与设置页都要报「授权到了哪一步」，
// 两处读的必须是同一个来源（Shizuku 服务的 Binder），否则显示会互相矛盾。
import com.zhizhu.zhicode.shizuku.ShizukuBridge
import com.zhizhu.zhicode.compose.engine.applyEngineOverrides
import com.zhizhu.zhicode.compose.engine.engineEffortToUi
import com.zhizhu.zhicode.compose.engine.engineModeToUi
import com.zhizhu.zhicode.compose.engine.readEngineSettings
import com.zhizhu.zhicode.compose.engine.toEngineEffort
import com.zhizhu.zhicode.compose.engine.toEngineMode
import com.zhizhu.zhicode.compose.engine.toUiPlanApproval
import com.zhizhu.zhicode.compose.runtime.EnvDoctor
import com.termux.shared.termux.TermuxConstants
import com.zhizhu.zhicode.RuntimeInstaller
import com.zhizhu.zhicode.TermuxTerminalPane
import com.zhizhu.zhicode.compose.model.ActionBusy
import com.zhizhu.zhicode.compose.model.AgentTask
import com.zhizhu.zhicode.compose.model.AttachBrowserState
import com.zhizhu.zhicode.compose.model.ApiConfigState
import com.zhizhu.zhicode.compose.model.ApiProfile
import com.zhizhu.zhicode.compose.model.ApiProfileDraft
import com.zhizhu.zhicode.compose.model.Attachment
import com.zhizhu.zhicode.compose.model.ChatImage
import com.zhizhu.zhicode.compose.model.ChatItem
import com.zhizhu.zhicode.compose.model.ChatKind
import com.zhizhu.zhicode.compose.model.ChatNavigation
import com.zhizhu.zhicode.compose.model.ChoiceIntent
import com.zhizhu.zhicode.compose.model.ChoiceOption
import com.zhizhu.zhicode.compose.model.ChoicePickerState
import com.zhizhu.zhicode.compose.model.ExtensionSummary
import com.zhizhu.zhicode.compose.model.EffortLevel
import com.zhizhu.zhicode.compose.model.FileDeletePrompt
import com.zhizhu.zhicode.compose.model.FileEntry
import com.zhizhu.zhicode.compose.model.FileNameForm
import com.zhizhu.zhicode.compose.model.FileRoot
import com.zhizhu.zhicode.compose.model.FileListOptions
import com.zhizhu.zhicode.compose.model.FileSortKey
import com.zhizhu.zhicode.compose.model.LiveOutput
import com.zhizhu.zhicode.compose.model.McpConfigState
import com.zhizhu.zhicode.compose.model.McpScope
import com.zhizhu.zhicode.compose.model.McpServer
import com.zhizhu.zhicode.compose.model.McpServerDraft
import com.zhizhu.zhicode.compose.model.McpServerStatus
import com.zhizhu.zhicode.compose.model.McpType
import com.zhizhu.zhicode.compose.model.MemoryEditor
import com.zhizhu.zhicode.compose.model.MemoryFile
import com.zhizhu.zhicode.compose.model.MemoryScope
import com.zhizhu.zhicode.compose.model.MemoryState
import com.zhizhu.zhicode.compose.model.ModelProfileTab
import com.zhizhu.zhicode.compose.model.ModelPickerState
import com.zhizhu.zhicode.compose.model.PermissionMode
import com.zhizhu.zhicode.compose.model.PermissionRequest
import com.zhizhu.zhicode.compose.model.QueuedPrompt
import com.zhizhu.zhicode.compose.model.PlanApproval
import com.zhizhu.zhicode.compose.model.RiskLevel
import com.zhizhu.zhicode.compose.model.RoleCard
import com.zhizhu.zhicode.compose.model.RoleCardEditor
import com.zhizhu.zhicode.compose.model.RoleCardsState
import com.zhizhu.zhicode.compose.model.SLASH_COMMANDS
import com.zhizhu.zhicode.compose.model.SessionSummary
import com.zhizhu.zhicode.compose.model.SettingsCategory
import com.zhizhu.zhicode.compose.model.SkillCreateDraft
import com.zhizhu.zhicode.compose.model.SkillDetail
import com.zhizhu.zhicode.compose.model.SkillEditTarget
import com.zhizhu.zhicode.compose.model.SkillEntry
import com.zhizhu.zhicode.compose.model.SkillFileDraft
import com.zhizhu.zhicode.compose.model.SkillScope
import com.zhizhu.zhicode.compose.model.SkillUrlDraft
import com.zhizhu.zhicode.compose.model.SkillsState
import com.zhizhu.zhicode.compose.data.SearchServiceStore
import com.zhizhu.zhicode.compose.model.SearchFieldName
import com.zhizhu.zhicode.compose.model.SearchService
import com.zhizhu.zhicode.compose.model.SearchServiceDraft
import com.zhizhu.zhicode.compose.model.SearchServicesState
import com.zhizhu.zhicode.compose.model.FileFormat
import com.zhizhu.zhicode.compose.model.SettingsDraft
import com.zhizhu.zhicode.compose.model.SlashCommand
import com.zhizhu.zhicode.compose.model.ThemeMode
import com.zhizhu.zhicode.compose.model.ToolActivity
import com.zhizhu.zhicode.compose.model.ToolActions
import com.zhizhu.zhicode.compose.model.ToolKind
import com.zhizhu.zhicode.compose.model.WorkspaceTab
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.model.WebSearchProvider
import com.zhizhu.zhicode.compose.ui.debug.ZhiFrameTrace
import com.termux.app.zhicode.core.FileOps
import com.termux.app.zhicode.core.PlanApprovalGate
import com.termux.app.zhicode.core.StorageLinks
import com.termux.app.zhicode.storage.ApiSettingsStore
import com.termux.app.zhicode.storage.SessionStore
import com.termux.app.zhicode.storage.ZhiMemory
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面状态机。
 *
 * 数据来自 [WorkspaceRepository]（现在只剩终端占位横幅项）；对话流、工具执行、会话存储
 * 后续换成真实引擎实现即可，UI 层不用改。
 *
 * 继承 [AndroidViewModel] 而不是 `ViewModel`，是为了拿到 `Application`：
 * 内置 Termux 环境的安装与自检都需要 Context。
 * 这样做还有一个实际好处 —— `viewModel()` 的默认工厂本来就支持 `AndroidViewModel`，
 * 所以 `ZhiCodeApp()` 里那行默认参数不用改、也不用自己写 Factory。
 */
class WorkspaceViewModel(
    application: Application,
    private val repo: WorkspaceRepository = MockWorkspaceRepository(),
) : AndroidViewModel(application), EngineEvents {

    /** 内置 Termux 环境安装器（第一次使用前会解压 32MB 的 bootstrap）。 */
    private val installer = RuntimeInstaller(application)

    /** 真实 ZhiCode 引擎。懒创建，第一次发消息时才初始化。 */
    private val engine = ZhiEngineController(application, this)

    /** 上次自动维护的墙上时间（毫秒）；只在打开侧栏时检查，避免把维护挂到热路径。 */
    private var lastStorageMaintenanceAt = 0L
    private val storageMaintenanceIntervalMs = 24L * 60L * 60L * 1000L

    /**
     * 停止看门狗；每次按停止重建，收到引擎的回合结束确认时取消。
     *
     * <p>用协程而不是 Handler：它跟着 viewModelScope 一起死，
     * ViewModel 清掉之后不会有回调再落到已经销毁的界面上。
     */
    private var stopWatchdog: Job? = null

    /**
     * 这一轮已经按了几次停止。0 = 不在停止流程里。
     *
     * <p>进内存而不是进 StateFlow：界面只用得到"在不在停止中"（`stopping`），
     * 次数只影响状态文案与"要不要清缓冲"，那是这一次调用内部的事。
     */
    private var stopAttempts = 0

    /** 低频维护：打开侧栏时最多每天跑一次，重活始终在 IO 线程。 */
    private fun maybeRunStorageMaintenance() {
        val now = System.currentTimeMillis()
        if (now - lastStorageMaintenanceAt < storageMaintenanceIntervalMs) return
        lastStorageMaintenanceAt = now
        viewModelScope.launch(Dispatchers.IO) {
            SessionReader.maintain()
        }
    }

    // ------------------------------------------------------------------
    // 运行期状态（属于"这一轮正在发生什么"，不是界面状态，因此不进 StateFlow）
    // ------------------------------------------------------------------

    /** 当前正在流式输出的助手气泡 id。为 null 表示还没有任何 delta。 */
    private var streamingAssistantId: String? = null

    /** 当前回合的工具分组 id。一个工具批次 = 一张分组卡。 */
    private var currentGroupId: String? = null

    /** 引擎给的工具 id → 它落在哪个分组卡里。用于把结果/进度写回正确的位置。 */
    private data class LiveTool(val groupId: String, val name: String, val command: String)

    private val liveTools = mutableMapOf<String, LiveTool>()

    /**
     * 工具实时输出的合并缓冲。
     *
     * `onToolProgress` 的粒度是"每次进程写出一点"，Bash 跑一条 `apt install` 能到几千次。
     * 每次都改 StateFlow 会把界面拖垮，所以按 [PROGRESS_FLUSH_MS] 合并后再落盘。
     *
     * 值类型是 [LiveOutput.Buffer] 而不是 `StringBuilder`：除了文本，它还要记住
     * stdout / stderr 各自的字符数，以及**上一块来自哪个流** —— 后者决定要不要插
     * `[stdout]` / `[stderr]` 标记行（只在切换时插一次，见 [LiveOutput.append]）。
     */
    private val progressBuffers = mutableMapOf<String, LiveOutput.Buffer>()
    private val progressLastFlush = mutableMapOf<String, Long>()

    /** 正在等待用户回执的权限请求 id。 */
    private var pendingPermissionId: String? = null

    /**
     * 当前这一轮属于哪个会话（`activeSessionId` 的快照）。
     *
     * 用途只有一个：错误迟到时判断它该不该落到**现在**这个会话里。
     * 用户在等待回复时切走会话，这一轮会被 `engine.cancel()` 打断，但错误回调
     * 仍可能之后到达；没有这个标记就会把错误卡写进新会话（详见 [finishTurnWithError]）。
     *
     * 为 null 表示"当前没有归属待判定的回合"，此时不拦截。
     */
    private var turnSessionId: String? = null

    /**
     * 正在进行的「提问」流程。
     *
     * 引擎的 `AskUserQuestion` 可以一次带多个问题，界面是**分步**展示的，
     * 所以必须把请求 id、问题列表、当前步骤、已收集的答案都留着 ——
     * 只存"当前这一步"的话，走到第二步就丢了前面的答案。
     */
    private class PendingQuestions(
        val requestId: String,
        val questions: JSONArray,
        var index: Int = 0,
        val answers: JSONObject = JSONObject(),
    )

    private var pendingQuestions: PendingQuestions? = null




    /**
     * 会话操作的目标。
     *
     * `showSessionActions(session)` 只负责弹选择器，真正的动作在选择器回调里执行；
     * 那时只能拿到选项文案，拿不到是哪条会话。所以必须把目标记住 ——
     * 否则会出现"想删 A 结果删了当前会话"这类错删。
     */
    private var pendingSessionAction: SessionSummary? = null

    /**
     * 待执行（去抖中）的设置落盘。
     *
     * 见 [scheduleSettingsPersist]：只有自由文本输入才走这条路，
     * 非空表示"有一次改动还躺在去抖窗口里"。
     */
    private var settingsPersistJob: Job? = null
    private var pendingMessageAction: ChatItem? = null

    // ⚠️ 这里**没有** `pendingToolAction`：工具菜单由那一行自己渲染（Miuix 下拉菜单），
    // 目标（组 id + 行 id）与文案一起从界面传进来，不需要在选择器回调里回头找目标。
    // 它曾经存在是因为菜单要经过 `choicePicker` 中转 —— 那条路已经删掉了。

    private var modelCatalogJob: Job? = null

    /**
     * 模型选择的落盘任务。
     *
     * 单独一个（而不是共用 `modelCatalogJob`）：两者生命周期完全不同 —— 拉目录是
     * "开面板时一次、切配置时一次"，而选模型是"每点一行一次"，共用一个句柄会让
     * 选模型把正在进行的目录请求取消掉（列表就此停在加载中）。
     */
    private var modelSaveJob: Job? = null
    private var fileReloadJob: Job? = null

    /**
     * 会话级模型覆盖。
     *
     * 为什么不只写配置记录：选模型与“本轮对话用哪个模型”是两个作用域。
     * 配置记录是跨会话的默认值，而 [modelOverride] 只影响当前这轮 ——
     * 用户临时换一下模型试效果，不应该把他的默认配置也改掉。
     *
     * 所以这里是“两边都写”：持久化那份写进配置记录（下次打开设置能看到），
     * 即时生效靠这个覆盖值再 `engine.configure()`。
     *
     * 切换 API 配置时清空——换了配置就该用那份配置自己的模型。
     */
    private var modelOverride: String? = null

    /**
     * 会话级角色卡覆盖。
     *
     * 与 [modelOverride] 同理：磁盘上的卡片清单与"实际生效内容"是两份数据，
     * 引擎的 SessionConfig 只认后者。空串 ≠ null —— 空串表示"确实停用了"（要覆盖掉
     * 配置里的旧值），null 表示"本轮不覆盖"（会退回旧值，于是停用看起来没生效）。
     */
    private var roleCardOverride: String? = null


    private val _state = MutableStateFlow(
        WorkspaceUiState(
            // 默认项目路径是 $HOME（Termux 约定），不再是开发本应用时用的那个路径。
            // 它由装环境时的 RuntimeInstaller 一并建好，见 [ensureWorkspace]。
            projectName = WorkspacePaths.nameOf(WorkspacePaths.defaultProject()),
            projectPath = WorkspacePaths.defaultProject(),
            // 会话列表 / 对话历史 / 任务清单都来自存储，这里先给空值，
            // 由 [initSessionState] 在 IO 线程读好后填上。
            // 不在构造器里同步读：一次长会话可能有上千条消息，会拖慢启动。
            sessions = emptyList(),
            activeSessionId = "",
            transcript = emptyList(),
            tasks = emptyList(),
            contextTokens = 0,
            contextWindow = 200_000,
            deviceStatus = clockLabel(),
            terminalLines = repo.terminalBanner(WorkspacePaths.defaultProject()),
            // 文件面板走真实文件系统。只列**一层**（不递归）：内置 Termux 环境装好后
            // home 下可能有几千个文件，递归会拖慢启动。
            filePath = WorkspacePaths.defaultProject(),
            fileEntries = emptyList(),
            busySessionIds = emptySet(),
            composerBusy = false,
            workingStatus = null,
            sidebarOpen = false,
            // 真实探测，不再是写死的 true
            runtimeReady = installer.isInstalled(),
        )
    )

    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    /**
     * 页面动作的在飞登记表（见 [ActionBusy]）。
     *
     * 与 [_state] 分开：判定互斥时读它（不产生 Snapshot 快照），界面读
     * `state.inFlight` 那份快照。两者由 [runBusy] 一并维护。
     */
    private val actions = ActionBusy()

    /**
     * 跑一个**有互斥语义**的页面动作。
     *
     * - 同一个 [key] 已在跑时直接返回：用户双击保存 / 粘贴 / 删除不会做两遍；
     * - 动作体在 [Dispatchers.IO] 上跑（保存类动作基本都是文件 IO），
     *   因此 `_state.update` 也允许在动作体里直接调用（它是线程安全的）；
     * - 结束（含异常）一定释放登记，不会留下永远转圈的按钮。
     *
     * 这不替代取消：用户仍可以用"停止"打断 Agent 回合；它只保证**同一次点击不会被放大成两次**。
     */
    private fun runBusy(key: String, block: suspend () -> Unit) {
        if (!actions.acquire(key)) {
            if (com.zhizhu.zhicode.compose.BuildConfig.DEBUG) {
                // 调试构建里留一行：双击被吞掉是**设计**，不是"点了没反应"。
                android.util.Log.d("ZhiCodeBusy", "skip re-entrant action: $key")
            }
            return
        }
        _state.update { it.copy(inFlight = actions.snapshot()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } finally {
                actions.release(key)
                _state.update { it.copy(inFlight = actions.snapshot()) }
            }
        }
    }

    init {
        initSessionState()
        autoInstallRuntimeIfStale()
    }

    // ------------------------------------------------------------ 终端保活

    /**
     * 终端面板实例。**持有它就是为了跨 Tab 保活**。
     *
     * ## 为什么必须挂在这里
     *
     * 原来的写法是 `TerminalPane` 里 `remember { TermuxTerminalPane(...) }` +
     * `onDispose { pane.closeAll() }`，而 `PaneHost` 用的是 `when(tab) { … }`：
     * 切到别的 Tab，终端这段 Composable 直接离开组合 → `onDispose` 触发 →
     * **`closeAll()` 把每个会话（bash 进程 + PTY fd）都 finish 掉**。
     * 于是"切走再切回来"看到的永远是全新的空终端，之前跑的命令、工作目录全没了。
     *
     * 把实例放到 ViewModel 上就解决了：Composable 进出组合只影响"挂到界面上"这一步，
     * 会话本身一直在。
     *
     * ## 为什么放 ViewModel 不会泄漏 Activity
     *
     * `TermuxTerminalPane` 是 `FrameLayout`，构造时用的是 Activity 的 Context。
     * ViewModel 比 Activity 活得久本来是泄漏的经典形态，但本工程的 MainActivity
     * 声明了 `configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"`，
     * **不会因旋转/换主题重建** —— Activity 实例与 ViewModel 同生共死，没有间隙。
     *
     * 真正要释放的时机是 [onCleared]（Activity 真正销毁）：那时必须 `closeAll()`，
     * 否则每开一次应用就漏一批 bash 进程。
     */
    private var terminalPane: TermuxTerminalPane? = null

    /** 取（或首次创建）终端面板实例。同一进程内只会有一个。 */
    fun terminalPane(context: Context): TermuxTerminalPane =
        terminalPane ?: TermuxTerminalPane(context, installer).also { terminalPane = it }

    /**
     * 关掉终端里的所有会话并丢弃实例。
     *
     * 只在两个地方调用：Activity 真正销毁（[onCleared]），以及**重装环境之前** ——
     * 重装会把整个 `usr` 目录 rename 换掉，正在跑的 bash 会指向一个已不存在的前缀。
     */
    fun releaseTerminalPane() {
        val pane = terminalPane ?: return
        terminalPane = null
        // closeAll() 会 finish 每个 session，并释放 WakeLock —— 属于 View 操作，回主线程做。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pane.closeAll()
        } else {
            Handler(Looper.getMainLooper()).post { pane.closeAll() }
        }
    }

    override fun onCleared() {
        releaseTerminalPane()
        super.onCleared()
    }

    /**
     * 启动时检查内置 Termux 环境是否为**当前 APK 内置的那一版**，不是就自动重装。
     *
     * ## 为什么需要它
     *
     * marker 里一直存着"版本三元组"（版本 / sha256 / 来源），最初就是为这件事写的，
     * 但此前**没有任何地方读它**。后果是：换了内置 bootstrap 的 APK 装上去以后，
     * `usr` 永远停在旧的那份 —— `isInstalled()` 只查文件存不存在，照样返回 true，
     * 于是谁都不会去重装，只能手动卸载重来（会连 HOME 一起丢）。
     *
     * ## 与手动触发的区别
     *
     * 不弹「环境自检」窗口、也不问一句：用户装 APK 的意图就是要用新的那版。
     * 进度照常回灌到状态里，打开环境自检就能看到。
     *
     * ## HOME 不受影响
     *
     * [RuntimeInstaller.install] 只换 `usr`（`activatePrefix` 把旧前缀 rename 到
     * `usr-backup` 再删），`home` 从头到尾没动过 —— 会话历史、项目文件都在。
     * 但**用 apt 装过的包会丢**（它们装在 `usr` 里）。
     *
     * ## 为什么每一步都写日志
     *
     * 这条路径是**静默**的：没有弹窗、失败也只进状态。第一版就是这样被反馈
     * "启动后没有自动重装" 而无法判断到底走到了哪一步。现在每次判定与结果都追加到
     * `$HOME/.zhicode/runtime.log`（终端里 `cat ~/.zhicode/runtime.log` 就能看）。
     */
    private fun autoInstallRuntimeIfStale() {
        // IO 线程：读 marker、比对 sha256、写日志都是磁盘操作
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runCatching { installer.installedVersion() }.getOrNull()
            val bundled = runCatching { installer.bundledVersion() }.getOrNull() ?: "?"
            val upToDate = runCatching { installer.isUpToDate() }.getOrElse { error ->
                appendRuntimeLog("检查版本时抛异常：${error.javaClass.simpleName}: ${error.message}")
                false
            }
            appendRuntimeLog(
                "启动检查：已装=${installed ?: "(无)"} 内置=$bundled 一致=$upToDate"
            )
            if (upToDate) return@launch

            val reason = if (installed == null) "正在初始化内置环境…" else "内置环境已更新，正在升级…"
            // "抢占"必须放在同一次 _state.update 里：启动自动装与用户手动点「初始化」
            // 可能同时到达，分开的 check-then-set 会让两边都跑一遍（第二次会把
            // staging 删掉再重建，前一次的进度全乱）。与 turnSessionId 是同一类竞态。
            var claimed = false
            _state.update { s ->
                if (s.runtimeInstalling) {
                    s
                } else {
                    claimed = true
                    s.copy(runtimeInstalling = true, runtimeProgress = 0, runtimeMessage = reason)
                }
            }
            if (!claimed) {
                appendRuntimeLog("已有安装在进行，本次跳过")
                return@launch
            }
            appendRuntimeLog("开始自动安装（$reason）")
            installRuntimeBlocking(reason)
        }
    }

    /**
     * 把一行诊断写进 `$HOME/.zhicode/runtime.log`。
     *
     * 只保留最后 [RUNTIME_LOG_MAX_LINES] 行：这是个排障用的滚动日志，
     * 不是审计记录，无限增长会把用户的家目录塞满。
     *
     * 绝不抛异常 —— 日志写不进去（磁盘满、权限）不该影响安装本身。
     */
    private fun appendRuntimeLog(line: String) {
        runCatching {
            val dir = TermuxConstants.dataDir()
            dir.mkdirs()
            val file = File(dir, "runtime.log")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val kept = if (file.isFile) {
                file.readLines().takeLast(RUNTIME_LOG_MAX_LINES - 1)
            } else {
                emptyList()
            }
            file.writeText((kept + "$stamp  $line").joinToString("\n") + "\n")
        }
    }

    /**
     * 首次载入：列出磁盘上的真实会话，有历史就把最近一条打开。
     *
     * 「自动打开最近一条」是刻意保留的行为：否则用户每次启动都看到空对话流，
     * 会以为历史丢了（历史其实在侧栏里，但要自己去点）。
     */
    private fun initSessionState() {
        viewModelScope.launch(Dispatchers.IO) {
            restoreUiSettings()
            /*
             * ⚠️ 这一步必须在 `syncActiveProfile()`（它会 `configure()` 并**落盘**）之前。
             *
             * 理由见 [restoreRuntimeChoices]：那个 configure 会把界面那份权限模式
             * 写回 SessionConfig。界面此刻还是默认值，于是"先 configure 再读盘"
             * 的顺序等于每次启动都把用户的权限设置冲掉。
             */
            restoreRuntimeChoices()
            // 顶栏的模型名/密钥状态要反映**真实生效**的配置，
            // 否则用户会看到一个跟实际请求无关的模型名。
            syncActiveProfile()
            syncRoleCardFromStore()
            // 共享存储的授权状态要**实测**：文件面板切到「共享存储」时，
            // 没授权只会看到"0 项"，而原因（没给「所有文件访问权限」）必须说出来。
            refreshSharedStoragePermission()
            /*
             * `~/storage` 的六个链接**在每次启动的路径上**补一次。
             *
             * 幂等且只增不删（见 StorageLinks.setup），所以重复调用没有副作用；
             * 而它是"老环境缺这一块"的唯一补救途径 —— 之前只在手动点「修复」时才跑，
             * 结果是早先装好的环境永远没有 ~/storage，用户看到的是 `cd ~/storage/shared`
             * 失败，看不出是"这个版本才加的"。
             *
             * 门控在 isInstalled()：环境还没装出来时 HOME 可能都还不存在，
             * 这时候建链接没有意义（安装流程自己会建）。
             */
            if (runCatching { installer.isInstalled() }.getOrDefault(false)) {
                installer.setupStorageLinks()
            }
            ensureWorkspace()
            val sessions = SessionReader.list(_state.value.projectPath)
            _state.update { it.copy(sessions = sessions) }
            sessions.firstOrNull()?.let { openSession(it) }
        }
    }

    /**
     * 读回**不在 SessionConfig 里**的那几项界面设置（主题、搜索服务密钥）。
     *
     * 其余设置（权限模式、推理档、上下文窗口、项目目录、联网搜索一整套、自动压缩、
     * 自定义提示词、沙箱全权、Root、保活）都是 [SessionConfig] 的字段，
     * 由引擎侧的持久化表负责，这里不重复。
     *
     * ⚠️ 但**每次启动仍要把它们读回界面一次**：引擎存了不等于界面知道，
     * 而界面不知道就会在下一次 `configure()` 里用自己的默认值把盘上的值冲掉。
     * 见 [restoreRuntimeChoices]。
     *
     * 密钥从**加密槽**读回，与 API 配置的密钥同一套保护。
     */
    private suspend fun restoreUiSettings() = withContext(Dispatchers.IO) {
        val stored = runCatching {
            ApiSettingsStore.getThemeMode(getApplication(), ThemeMode.SYSTEM.name.lowercase())
        }.getOrDefault(ThemeMode.SYSTEM.name.lowercase())
        val theme = ThemeMode.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            ?: ThemeMode.SYSTEM
        val keys = WebSearchProvider.entries
            .filter { it.needsKey }
            .associateWith { provider ->
                runCatching {
                    ApiSettingsStore.getWebSearchKey(getApplication(), provider.name.lowercase())
                }.getOrDefault("")
            }
            .filterValues { it.isNotBlank() }
        _state.update {
            it.copy(
                themeMode = theme,
                settings = it.settings.copy(
                    webSearchKeys = keys,
                    terminalCharMode = runCatching {
                        ApiSettingsStore.getTerminalCharMode(getApplication())
                    }.getOrDefault(false),
                ),
                settingsDraft = it.settingsDraft?.copy(
                    themeMode = theme,
                    webSearchKeys = keys,
                    terminalCharMode = runCatching {
                        ApiSettingsStore.getTerminalCharMode(getApplication())
                    }.getOrDefault(false),
                ),
            )
        }
    }

    /**
     * 确保当前项目目录存在。
     *
     * 两个时机都需要：运行环境刚装好（home 此时才出现）、以及每次启动
     * （环境是上次装的）。**必须在列会话之前调用** —— 会话按项目目录分键存储，
     * 目录不存在时会话会落在一个"幽灵项目"键下，之后换了路径就找不回来了。
     */
    private fun ensureWorkspace() {
        val path = _state.value.projectPath
        // 目录已存在：只需按真实文件系统算出列表与说明。
        if (WorkspacePaths.exists(path)) {
            reloadFiles()
            return
        }
        // 目录不存在，且运行环境还没装（home 目录都还没出现）：
        // 此时**不能**建目录（父目录不存在），但也**不能什么都不说** ——
        // 否则文件面板会显示"0 项"却没有任何解释。
        if (!installer.isInstalled()) {
            _state.update {
                it.copy(
                    fileEntries = emptyList(),
                    fileNote = "工作区还不存在：$path\n初始化内置 Termux 环境后会自动建立。",
                )
            }
            return
        }
        WorkspacePaths.ensure(path)
        reloadFiles()
    }

    /** 刷新文件面板当前目录的列表，并同步 [WorkspaceUiState.fileNote]。 */
    private fun reloadFiles() {
        val path = _state.value.filePath
        val options = _state.value.fileListOptions
        val loaded = FileBrowser.children(path)
        val entries = loaded
            .asSequence()
            .filter { options.query.isBlank() || it.name.contains(options.query.trim(), ignoreCase = true) }
            .sortedWith(fileComparator(options))
            .toList()
        val dir = java.io.File(path)
        val note = when {
            entries.isNotEmpty() -> ""
            !dir.isDirectory -> if (dir.exists()) "不是目录：$path" else "目录不存在：$path"
            // 目录存在但一个子项都没列出来 —— 区分「真的是空目录」和「没权限列」。
            // 面包屑可以从 `/` 一路点下来，而 `/`、`/data` 这类系统目录在应用沙箱里
            // 是 `drwx--x--x`（other 只有 x 没有 r），列不出来；这时如果只显示
            // 「0 项」，看起来像目录坏了。`File.list()` 返回 null 就代表列取失败。
            dir.list() == null -> "无法读取（权限不足）：$path"
            else -> ""
        }
        _state.update { state ->
            // 目录读取本身可能无法被底层 API 中断；旧 Job 即使被取消，
            // 也不能在新路径或新搜索词之后把旧结果写回来。
            if (state.filePath != path || state.fileListOptions != options) state
            else state.copy(fileEntries = entries, fileNote = note)
        }
    }

    private var idCounter = 0L

    private fun nextId(prefix: String): String = "$prefix-${++idCounter}"

    // ---------- 输入器 ----------

    // ---------- 任务清单 ----------

    /** 点悬浮任务卡：打开任务详情窗口，查看**全部**任务（卡片只显示当前窗口）。 */
    fun openTaskList() = _state.update { it.copy(taskListOpen = true) }

    fun closeTaskList() = _state.update { it.copy(taskListOpen = false) }

    fun onComposerChange(text: String) {
        val query = if (text.startsWith("/") && !text.contains(' ') && !text.contains('\n')) text else null
        _state.update {
            it.copy(
                composerText = text,
                slashQuery = query,
                slashMatches = if (query == null) emptyList()
                else slashMatchesFor(query),
            )
        }
    }

    private fun slashMatchesFor(query: String): List<SlashCommand> {
        val normalized = query.lowercase(Locale.US)
        return SLASH_COMMANDS.filter { it.name.startsWith(normalized) }
    }

    fun pickSlashCommand(command: SlashCommand) {
        _state.update {
            it.copy(composerText = command.name + " ", slashQuery = null, slashMatches = emptyList())
        }
    }

    fun dismissSlashPalette() {
        _state.update { it.copy(slashQuery = null, slashMatches = emptyList()) }
    }

    /**
     * 附件载荷。
     *
     * **故意不放进 `WorkspaceUiState`**：图片是几 MB 的字节数组，塞进不可变状态里会让
     * 每次 `copy()` 都拖着它，而且 Compose 判断状态是否变化时要对它做 `equals`——
     * 每帧比较几 MB 是纯粹的浪费。界面只需要名字和大小，真正的字节留在这里，
     * 发送时再取。
     *
     * [image] 是 base64（界面显示缩略图用）。**在这里编码一次**而不是每次要用时现编：
     * 一张 10 MB 的图 base64 是 13 MB 的字符串，编码本身要遍历全部字节 ——
     * 缩略图与消息气泡、引擎请求三处都要它，各编一次就是三倍开销。
     * 它也不进 state（同样是不让几 MB 的字符串跟着每次 copy 走），
     * 由 [currentAttachmentImage] 按 id 取。
     */
    private class AttachmentPayload(val bytes: ByteArray?, val mimeType: String, val image: ChatImage?)

    private val attachmentPayloads = mutableMapOf<String, AttachmentPayload>()

    /**
     * 取某个待发附件的图片（给输入器的缩略图用）。
     *
     * 返回值里的大字符串与载荷共享**同一个引用**，不会复制字节；
     * 之所以不做成 `WorkspaceUiState` 的字段，理由见 [AttachmentPayload] 的注释。
     */
    fun currentAttachmentImage(id: String): ChatImage? = attachmentPayloads[id]?.image

    /**
     * 附加一张图片（来自系统选择器）。
     *
     * 读盘与大小校验都在 IO 线程：10 MB 的图片读进来是实打实的耗时。
     */
    fun attachImage(uri: android.net.Uri) = attachImages(listOf(uri))

    /**
     * 一次附加多张图片（多选）。
     *
     * ## 为什么是"全部读完再更新一次状态"而不是每张各更新一次
     *
     * 五张图各发一次 `_state.update` 会让附件条闪四下、每次都重新测量布局
     * （`animateContentSize` 会为每一次变化播一段动画）。攒起来一次性落地，
     * 用户看到的是"五张一起出现"。
     *
     * ## 为什么一张失败不放弃其它张
     *
     * 选五张图时有了一张超大/损坏的，把其余四张一起丢掉是最糟的结果 ——
     * 用户还得重新去相册挑一遍。所以逐张读、逐张记失败原因，
     * **成功的照样进附件条**，失败的在提示里报出来。
     *
     * ## 部分失败为什么只吃一条提示
     *
     * 复用 `message` 那条通道（同一时刻只显示一条），用「N 张失败」汇总而不是
     * 每张各弹一条：连弹五条提示会把真正成功的那几张也淹没掉。
     */
    fun attachImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.map { uri -> AttachmentReader.readImage(getApplication(), uri) }
            }
            val failures = loaded.filter { it.isFailure }
            val added = loaded.mapNotNull { it.getOrNull() }
            if (added.isNotEmpty()) {
                val newAttachments = added.map { image ->
                    val id = nextId("att")
                    // base64 在这里编一次，缩略图 / 气泡 / 引擎请求三处复用同一份。
                    attachmentPayloads[id] = AttachmentPayload(
                        bytes = image.bytes,
                        mimeType = image.mimeType,
                        image = ChatImage(
                            data = android.util.Base64.encodeToString(image.bytes, android.util.Base64.NO_WRAP),
                            mimeType = image.mimeType,
                            name = image.name,
                        ),
                    )
                    Attachment(
                        id = id,
                        label = image.name,
                        // 「PNG · 2.4 MB」两个信息都来自真实读数，不是写死的文案。
                        detail = "${image.formatLabel} · ${image.sizeLabel}",
                        isImage = true,
                    )
                }
                _state.update { s ->
                    s.copy(
                        attachments = s.attachments + newAttachments,
                        message = when {
                            failures.isEmpty() && added.size == 1 -> "已添加图片：${added[0].name}"
                            failures.isEmpty() -> "已添加 ${added.size} 张图片"
                            // 有失败时提示必须偏错误色：否则"已添加 4 张"配一个中性色，
                            // 用户根本不会注意到自己选的第 5 张没进来。
                            else -> "已添加 ${added.size} 张，${failures.size} 张失败：" +
                                (failures[0].exceptionOrNull()?.message ?: "未知原因")
                        },
                        messageIsError = failures.isNotEmpty(),
                    )
                }
            } else {
                val reason = failures[0].exceptionOrNull()?.message ?: "未知原因"
                _state.update {
                    it.copy(
                        message = if (failures.size == 1) "图片读取失败：$reason"
                        else "${failures.size} 张图片都读取失败：$reason",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /**
     * 把待发图片编成引擎要的内容块。
     *
     * 结构对齐原版 `buildImageBlocks()`：`{type:image, source:{type:base64, media_type, data}, name}`。
     * 是否真的发给模型由引擎按 `visionEnabled` 决定（`VisionMessageFilter`），
     * 这里不重复判断——两处都判会出现"界面拦了但引擎其实允许"这类不一致。
     */
    private fun buildImageBlocks(): JSONArray {
        val blocks = JSONArray()
        _state.value.attachments.filter { it.isImage }.forEach { attachment ->
            val payload = attachmentPayloads[attachment.id] ?: return@forEach
            val encoded = payload.image?.data ?: return@forEach
            if (encoded.isEmpty()) return@forEach
            runCatching {
                blocks.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", payload.mimeType)
                                .put("data", encoded),
                        )
                        .put("name", attachment.label),
                )
            }
        }
        return blocks
    }

    /**
     * 把待发图片编成**界面模型**（挂到用户气泡上）。
     *
     * 与 [buildImageBlocks] 读的是同一份 `attachmentPayloads`，但两者不能合并：
     *
     * - [buildImageBlocks] 给**引擎**，结构必须是 Anthropic 的 `source.base64`；
     * - 这里给**界面**，只要 base64 字符串本身。
     *
     * 合并成一个函数再各取所需，就得让界面层认识 `JSONObject` 结构；分开反而是
     * 两个都很小的纯函数。唯一要守住的是**调用时机**：必须在 `clearAttachments()`
     * **之前**调用，两处调用点都写了这条注释。
     */
    private fun currentImages(): List<ChatImage> = _state.value.attachments
        .filter { it.isImage }
        .mapNotNull { currentAttachmentImage(it.id) }

    /** 清空附件：界面条目与载荷必须一起走，否则载荷留在 map 里没人回收。 */
    private fun clearAttachments() {
        attachmentPayloads.clear()
        _state.update { it.copy(attachments = emptyList()) }
    }

    /**
     * 把待发的**文本**附件拼进提示词。
     *
     * 结构照抄原版 `buildPromptWithAttachments`：包成 `<attached_context>` 块，
     * 每个附件一个 `<attachment name="...">`。用 XML 风格的包裹是有意的——
     * 模型对"这段是附带资料、不是用户指令"的分界需要明确标记，
     * 直接把技能内容贴在提示词后面会让它读成用户要求。
     *
     * 只有存在文本附件时才加包裹层；一个都没有就原样返回，
     * 避免每条消息都拖着空标签。
     */
    private fun buildPromptWithTextAttachments(prompt: String): String {
        val texts = _state.value.attachments.filter { !it.isImage && it.textBody != null }
        if (texts.isEmpty()) return prompt
        return buildString {
            append(prompt).append("\n\n<attached_context>")
            texts.forEach { attachment ->
                append("\n<attachment name=\"").append(attachment.label.replace("\"", "'")).append("\">\n")
                val body = attachment.textBody.orEmpty()
                append(if (body.length > TEXT_ATTACHMENT_LIMIT) body.take(TEXT_ATTACHMENT_LIMIT) + "\n[…attachment truncated…]" else body)
                append("\n</attachment>")
            }
            append("\n</attached_context>")
        }
    }

    // ---------- Skill ----------

    /**
     * 导入 SKILL.md 的体积上限。
     *
     * 256 KB 对 SKILL.md 来说已经很宽松（真实技能通常几 KB），但足以挡住
     * "用户误选了一个几十兆的文件、`readText()` 把内存吃光"这条路径。
     */
    private val SKILL_IMPORT_LIMIT_BYTES = 256 * 1024

    fun openSkills() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:skills")
        // 子页永远坐在设置主页之上（rikkahub 的页面栈）：从侧栏入口进来时
        // 也把 hub 带起来，否则返回时子页关掉就直接回工作区，层断了。
        //
        // ⚠️ 顺序是这个函数的全部要点（详见 openApiConfig 的注释）：**先同步推页**，
        // 数据随后在 IO 上读。原来 `SkillStore.list()` 直接写在 `_state.update{}` 里，
        // 而它是 `listFiles()` 加逐目录解析 —— 主线程被它占住，点击那一帧都画不出来，
        // 用户看到的就是「点了要等一会儿才开始动」。
        _state.update {
            it.copy(
                skills = SkillsState(skills = emptyList(), loading = true),
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = _state.value.projectPath
            val loaded = runCatching { SkillStore.list(path) }.getOrDefault(emptyList())
            _state.update { s ->
                // 读盘期间用户可能已经返回：那时载荷已置空，结果直接丢弃。
                val current = s.skills ?: return@update s
                s.copy(skills = current.copy(skills = loaded, loading = false))
            }
        }
    }

    fun closeSkills() {
        _state.update { it.copy(skills = null) }
        // 回到设置主页时刷新「扩展」组的数量：刚从技能页增删过，不刷会显示旧数字。
        refreshExtensions()
    }

    /** 列表页的筛选词。 */
    fun setSkillQuery(query: String) = _state.update {
        it.copy(skills = it.skills?.copy(query = query))
    }

    /**
     * 打开技能详情。
     *
     * 详情页同时承担两件事：看这个技能有哪些文件、以及从这里进编辑。
     * 文件列表每次进入都重读 —— 用户可能刚用 Agent 往目录里加了参考文档。
     */
    fun openSkillDetail(entry: SkillEntry) {
        val s = _state.value
        // 先推页、后读盘：`listTree` 要 walk 整个技能目录并逐文件取大小，
        // 正文读取更是一次完整读文件。放在点击那一帧上会看到明显延迟，
        // 而详情页在数据回来之前已经立起来了（显示"读取中"）。
        _state.update {
            it.copy(
                skills = it.skills?.copy(
                    createForm = null,
                    editing = null,
                    fileDraft = null,
                    urlDraft = null,
                    detail = SkillDetail(
                        entry = entry,
                        tree = SkillStore.listTree(s.projectPath, entry.scope, entry.name),
                    ),
                ),
            )
        }
        // 详情页的只读预览：`SKILL.md` 的正文。
        // 之前只有文件树，想知道这个技能教 Agent 做什么得先点进编辑器。
        viewModelScope.launch(Dispatchers.IO) {
            val body = SkillStore.read(s.projectPath, entry.scope, entry.name).getOrDefault("")
            _state.update { state ->
                val current = state.skills?.detail ?: return@update state
                // 期间用户可能已经关页或换了另一条技能：只更新同一条。
                if (current.entry.name != entry.name || current.entry.scope != entry.scope) return@update state
                state.copy(
                    skills = state.skills.copy(
                        detail = current.copy(
                            body = body,
                            tree = SkillStore.listTree(state.projectPath, entry.scope, entry.name),
                        ),
                    ),
                )
            }
        }
    }

    fun closeSkillDetail() = _state.update {
        it.copy(skills = it.skills?.copy(detail = null))
    }

    /** 打开「新建文件」对话框。 */
    fun newSkillFile() = _state.update {
        it.copy(skills = it.skills?.copy(fileDraft = SkillFileDraft()))
    }

    fun updateSkillFileDraft(transform: (SkillFileDraft) -> SkillFileDraft) = _state.update {
        val skills = it.skills ?: return@update it
        val draft = skills.fileDraft ?: return@update it
        it.copy(skills = skills.copy(fileDraft = transform(draft)))
    }

    fun cancelSkillFileDraft() = _state.update {
        it.copy(skills = it.skills?.copy(fileDraft = null))
    }

    /**
     * 在技能目录里新建一个附加文件。
     *
     * 重名**直接拒绝**而不是覆盖：这里建的是参考文档、脚本这类手写内容，
     * 覆盖等于丢数据。技能本体（SKILL.md）也不允许用这个入口建 ——
     * 那是 [createSkill] 的事，而且空内容过不了名字一致性校验，
     * 所以 `SkillFileDraft.nameError` 在输入阶段就把它挡掉了。
     *
     * 文件名允许带子目录（`reference/api.md`）：技能本来就支持分组，
     * 界面不给出这个入口的话，树只能显示别处建出来的目录。
     */
    fun saveSkillFile() {
        val s = _state.value
        val skills = s.skills ?: return
        val draft = skills.fileDraft ?: return
        val detail = skills.detail ?: return
        val name = draft.fileName.trim()
        if (!draft.saveable) return
        // ⚠️ 落盘与重列目录都**不在主线程**上做（与 `initSessionState` 同一约定）。
        //
        // 用户点「创建」时这一下要写一整份草稿正文，写完还要重新 walk 一遍技能目录；
        // 技能文件是手写的参考文档，几百 KB 很常见 —— 在 Android 8 那类慢闪存设备上
        // 这就是一次肉眼可见的卡顿（点击反馈被冻住）。
        //
        // 存在性检查与写入必须在**同一个** IO 块里连着做：拆成两块就多出一个
        // 「检查过了但还没写」的窗口，双击能建出两份。
        viewModelScope.launch(Dispatchers.IO) {
            if (SkillStore.fileExists(s.projectPath, detail.entry.scope, detail.entry.name, name)) {
                _state.update { it.copy(message = "文件 $name 已存在", messageIsError = true) }
                return@launch
            }
            val result = SkillStore.writeFile(
                s.projectPath, detail.entry.scope, detail.entry.name, name, draft.content,
            )
            _state.update { state ->
                if (result.isFailure) {
                    state.copy(
                        message = "新建失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                        messageIsError = true,
                        skills = skills.copy(fileDraft = null),
                    )
                } else {
                    state.copy(
                        message = "已创建 $name",
                        skills = skills.copy(
                            fileDraft = null,
                            detail = detail.copy(
                                tree = SkillStore.listTree(state.projectPath, detail.entry.scope, detail.entry.name),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 删除技能目录里的一个附加文件。
     *
     * `SKILL.md` 由 `SkillStore.deleteFile` 拒绝（它是技能本体，删掉会留下一个
     * 列表里看得见、点不开的幽灵技能），所以界面不额外挡一遍 —— 一处校验就够，
     * 两处会分叉。
     */
    fun deleteSkillFile(relativePath: String) {
        val s = _state.value
        val detail = s.skills?.detail ?: return
        // 删除本身是元数据操作，但它后面**紧接着**要重列整棵文件树（walk 目录 + 逐文件取大小），
        // 那一步才是真花钱的地方 —— 所以两块一起放到 IO 线程，别只挪一半。
        viewModelScope.launch(Dispatchers.IO) {
            val result = SkillStore.deleteFile(s.projectPath, detail.entry.scope, detail.entry.name, relativePath)
            _state.update { state ->
                state.copy(
                    skills = state.skills?.copy(
                        detail = detail.copy(
                            tree = SkillStore.listTree(state.projectPath, detail.entry.scope, detail.entry.name),
                        ),
                    ),
                    message = if (result.isSuccess) "已删除 $relativePath"
                    else "删除失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                )
            }
        }
    }

    /**
     * 打开发起「手动添加」对话框。
     *
     * 表单**预填一份示例 SKILL.md**（见 `SkillStore.starterTemplate`）：用户改一改就能存，
     * 而不是面对一个空框猜 frontmatter 要写什么。里面的名字是占位符、过不了校验，
     * 所以「保存」一开始是禁用的 —— 必须先换成自己的名字，
     * 否则点两下就会建出一个没打算建的技能。
     *
     * 名字照常由内容解析得出（与 [updateSkillCreateDraft] 同一条规则），
     * 不在这里单独存一份：两份来源必然会在用户改内容时不一致。
     */
    fun newSkill() {
        val content = SkillStore.starterTemplate
        _state.update {
            it.copy(
                skills = it.skills?.copy(
                    createForm = SkillCreateDraft(
                        content = content,
                        name = SkillStore.nameFromContent(content).orEmpty(),
                    ),
                    detail = null,
                    editing = null,
                ),
            )
        }
    }

    fun updateSkillCreateDraft(transform: (SkillCreateDraft) -> SkillCreateDraft) = _state.update {
        val skills = it.skills ?: return@update it
        val form = skills.createForm ?: return@update it
        val next = transform(form)
        // 名字始终由内容**重新解析**，不单独存一份用户输入：
        // 两份来源会在用户改内容时不一致，而目录名只看这一份。
        it.copy(
            skills = skills.copy(
                createForm = next.copy(name = SkillStore.nameFromContent(next.content).orEmpty()),
            ),
        )
    }

    fun cancelSkillCreate() = _state.update {
        it.copy(skills = it.skills?.copy(createForm = null))
    }

    /**
     * 手动添加：把表单内容写进以 frontmatter 里的 `name` 命名的新目录。
     *
     * 名字解析不出来时**不提交**（`saveable` 为假）—— 猜一个目录名会建出
     * 用户没打算建的东西。同名目录不覆盖，直接打开现有内容（`SkillStore.create`
     * 的返回值即此语义）。
     *
     * ⚠️ 创建后只推**一层**页面（详情），**不能**同时设 `editing`：
     * 那样页面栈会一次加两页（列表→详情→编辑器），视觉上就是"出现一个重复的"
     * （用户报过的现象）。要改内容在详情页点 SKILL.md 即可。
     */
    fun createSkill() = runBusy("skills.create") {
        val s = _state.value
        val form = s.skills?.createForm ?: return@runBusy
        if (!form.saveable) return@runBusy
        val name = form.name.trim()
        val result = SkillStore.create(s.projectPath, form.scope, name, form.content)
        result.fold(
            onSuccess = { created ->
                _state.update { state ->
                    val entry = SkillStore.list(state.projectPath)
                        .firstOrNull { it.name == name && it.scope == form.scope }
                        ?: SkillEntry(
                            name = name,
                            scope = form.scope,
                            path = SkillStore.fileOf(state.projectPath, form.scope, name).absolutePath,
                            summary = "未写说明",
                            sizeLabel = "",
                        )
                    state.copy(
                        skills = SkillsState(
                            skills = SkillStore.list(state.projectPath),
                            detail = SkillDetail(
                                entry = entry,
                                tree = SkillStore.listTree(state.projectPath, form.scope, name),
                            ),
                            // 刻意不设 editing：见函数开头那条警告。
                        ),
                        message = if (created) "已创建技能 $name" else "技能 $name 已存在，已为你打开",
                    )
                }
            },
            onFailure = { error ->
                _state.update { it.copy(message = "创建失败：${error.message ?: "未知原因"}", messageIsError = true) }
            },
        )
    }

    /**
     * 从本机文件导入一份 SKILL.md。
     *
     * 读完**不直接建技能**，而是填进「手动添加」表单让用户确认：名字解析出来对不对、
     * 作用域选哪个，都还要用户看一眼。直接建会绕掉这两个决定。
     *
     * 加了一道体积上限：`readText()` 对超大文件会直接把内存吃光（用户完全可能误选一个
     * 几十兆的文件），而 SKILL.md 本身只有几 KB。
     */
    fun importSkillFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(SKILL_IMPORT_LIMIT_BYTES + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val step = input.read(buffer, read, buffer.size - read)
                        if (step <= 0) break
                        read += step
                    }
                    if (read > SKILL_IMPORT_LIMIT_BYTES) {
                        throw IllegalStateException("文件太大（上限 ${SKILL_IMPORT_LIMIT_BYTES / 1024} KB）")
                    }
                    String(buffer, 0, read, Charsets.UTF_8)
                } ?: throw IllegalStateException("读不到这个文件")
            }
            result.fold(
                onSuccess = { text ->
                    // 读进来直接进「手动添加」对话框，名字照常由内容解析（不另外预填字段）：
                    // 表单只有一个内容框，与参考实现一致。
                    val name = SkillStore.nameFromContent(text).orEmpty()
                    _state.update {
                        it.copy(
                            skills = it.skills?.copy(
                                detail = null,
                                editing = null,
                                urlDraft = null,
                                createForm = SkillCreateDraft(content = text, name = name),
                            ),
                            message = if (name.isBlank()) {
                                "已读取文件，但 frontmatter 里没有 name 字段"
                            } else {
                                "已读取文件，确认后点「保存」"
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(message = "读取失败：${error.message ?: "未知原因"}", messageIsError = true)
                    }
                },
            )
        }
    }

    /**
     * 打开「从 URL 导入」对话框。
     */
    fun newSkillUrl() = _state.update {
        it.copy(skills = it.skills?.copy(urlDraft = SkillUrlDraft(), editing = null, createForm = null))
    }

    fun updateSkillUrlDraft(transform: (SkillUrlDraft) -> SkillUrlDraft) = _state.update {
        val skills = it.skills ?: return@update it
        val draft = skills.urlDraft ?: return@update it
        it.copy(skills = skills.copy(urlDraft = transform(draft)))
    }

    fun cancelSkillUrl() = _state.update {
        it.copy(skills = it.skills?.copy(urlDraft = null))
    }

    /**
     * 从 URL 导入。
     *
     * 两条分支（见 `SkillImport`）：
     * - **zip** → 把里面每个 `SKILL.md` 解开逐个建技能（子目录也认），一次可以导入一整包；
     * - **文本** → 不直接建，而是填进「手动添加」对话框让用户确认名字与作用域。
     *
     * 下载在 IO 线程，期间把表单标成 `loading` 并禁用按钮：不标的话用户会以为没反应，
     * 连点几下就是几次并发下载。
     *
     * 所有失败原因都如实上报（不是 http(s) / HTTP 非 200 / 太大 / 既不是文本也不是 zip），
     * 因为这四种的下一步动作完全不同。
     */
    fun importSkillFromUrl() {
        val s = _state.value
        val draft = s.skills?.urlDraft ?: return
        if (!draft.ready) return
        _state.update { it.copy(skills = it.skills?.copy(urlDraft = draft.copy(loading = true))) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { SkillImport.download(draft.url) }
            result.fold(
                onSuccess = { bytes ->
                    when {
                        SkillImport.looksLikeZip(bytes) -> {
                            val parsed = runCatching { SkillImport.skillsFromZip(bytes) }
                            parsed.fold(
                                onSuccess = { imported -> createImportedSkills(imported, draft.scope) },
                                onFailure = { error -> failSkillUrl(error) },
                            )
                        }

                        SkillImport.looksLikeText(bytes) -> {
                            // 文本：进「手动添加」让用户看一眼名字与作用域再落盘。
                            val text = bytes.toString(Charsets.UTF_8)
                            val name = SkillStore.nameFromContent(text).orEmpty()
                            _state.update { state ->
                                state.copy(
                                    skills = state.skills?.copy(
                                        urlDraft = null,
                                        createForm = SkillCreateDraft(
                                            content = text,
                                            scope = draft.scope,
                                            name = name,
                                        ),
                                    ),
                                    message = if (name.isBlank()) {
                                        "已下载，但 frontmatter 里没有 name 字段"
                                    } else {
                                        "已下载，确认后点「保存」"
                                    },
                                )
                            }
                        }

                        else -> _state.update { state ->
                            state.copy(
                                skills = state.skills?.copy(urlDraft = draft.copy(loading = false)),
                                message = "这个地址既不是文本也不是 zip 压缩包",
                                messageIsError = true,
                            )
                        }
                    }
                },
                onFailure = { error -> failSkillUrl(error) },
            )
        }
    }

    private fun failSkillUrl(error: Throwable) = _state.update {
        it.copy(
            skills = it.skills?.copy(urlDraft = it.skills.urlDraft?.copy(loading = false)),
            message = "导入失败：${error.message ?: "未知原因"}",
            messageIsError = true,
        )
    }

    /**
     * 把 zip 里解出来的技能逐个落盘。
     *
     * 同名**不覆盖**（`SkillStore.create` 的既有语义）：用户可能已经在本地改过那份，
     * 静默覆盖等于丢数据。结果里分别报"新建了几个 / 已存在几个"，
     * 而不是笼统说一句"导入完成"——用户要能一眼看出哪个没进来。
     */
    private fun createImportedSkills(imported: List<SkillImport.ImportedSkill>, scope: SkillScope) {
        val path = _state.value.projectPath
        var created = 0
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        imported.distinctBy { it.name }.forEach { skill ->
            SkillStore.create(path, scope, skill.name, skill.content).fold(
                onSuccess = { isNew -> if (isNew) created++ else skipped += skill.name },
                onFailure = { failed += skill.name },
            )
        }
        _state.update { state ->
            state.copy(
                skills = state.skills?.copy(
                    urlDraft = null,
                    skills = SkillStore.list(state.projectPath),
                ),
                message = buildString {
                    append("已导入 $created 个技能")
                    if (skipped.isNotEmpty()) append("，${skipped.size} 个已存在（未覆盖）")
                    if (failed.isNotEmpty()) append("，${failed.size} 个失败：${failed.joinToString("、")}")
                },
                messageIsError = failed.isNotEmpty(),
            )
        }
    }

    /**
     * 打开某个技能文件的编辑器（对话框）。
     *
     * [relativePath] 默认 `SKILL.md`（列表页的快捷「编辑」直接进本体）；
     * 详情页里点某个附加文件时传那条相对路径（可能是 `reference/api.md`）。
     *
     * 还没进过详情时顺手把详情也置上：详情页是编辑对话框的**宿主页**
     * （浮层挂在它的 Scaffold 里，见 `SettingsSubPage` 的 overlay 参数），
     * 不置的话对话框没有宿主、点了也不会出现。
     */
    fun editSkill(entry: SkillEntry, relativePath: String = "SKILL.md") {
        val s = _state.value
        // 打开编辑器要**先把整个文件读进内存**（正文直接进 `SkillEditTarget.body`），
        // 这几百 KB 的读 + 文件名一致性校验都不该压在点击那一帧上。
        viewModelScope.launch(Dispatchers.IO) {
            val body = SkillStore.readFile(s.projectPath, entry.scope, entry.name, relativePath).getOrElse { error ->
                _state.update { it.copy(message = "打开失败：${error.message ?: "未知原因"}", messageIsError = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    skills = it.skills?.copy(
                        createForm = null,
                        urlDraft = null,
                        detail = it.skills.detail ?: SkillDetail(
                            entry = entry,
                            tree = SkillStore.listTree(s.projectPath, entry.scope, entry.name),
                        ),
                        editing = SkillEditTarget(
                            name = entry.name,
                            scope = entry.scope,
                            relativePath = relativePath,
                            body = body,
                            nameError = nameErrorFor(relativePath, entry.name, body),
                        ),
                    ),
                )
            }
        }
    }

    /** 只有 `SKILL.md` 有名字一致性约束；附加文件与目录名无关。 */
    private fun nameErrorFor(relativePath: String, skillName: String, body: String): String? =
        if (relativePath == "SKILL.md") SkillStore.nameConflict(skillName, body) else null

    fun updateSkillBody(body: String) = _state.update {
        val skills = it.skills ?: return@update it
        val editing = skills.editing ?: return@update it
        it.copy(
            skills = skills.copy(
                // 每次改动都重算名字冲突：对话框要能**实时**告诉用户"name 对不上"，
                // 而不是等他点了保存再报错（那时内容可能已经写了几百行）。
                editing = editing.copy(
                    body = body,
                    nameError = nameErrorFor(editing.relativePath, editing.name, body),
                ),
            ),
        )
    }

    fun cancelSkillEdit() = _state.update {
        it.copy(skills = it.skills?.copy(editing = null))
    }

    /**
     * 保存正在编辑的文件。
     *
     * 保存后**回到原来那一页**（详情页还在就回详情页并刷新文件树），
     * 而不是一路弹回列表 —— 那样用户想接着改第二个文件就得重新点进去。
     *
     * ⚠️ 失败时**不关对话框**：`SKILL.md` 的 `name` 与目录名不一致是这里最容易
     * 踩到的错误（`SkillStore.writeFile` 会拒绝），关掉对话框等于让用户重新输入一遍。
     * 保持打开、把原因显示出来，用户改一行就能再存。
     */
    fun saveSkill() = runBusy("skills.save") {
        val s = _state.value
        val editing = s.skills?.editing ?: return@runBusy
        if (!editing.saveable) return@runBusy
        // 与 saveSkillFile 同理：写的是一整份正文 + 写完重列文件树。
        run {
            val result = SkillStore.writeFile(
                s.projectPath, editing.scope, editing.name, editing.relativePath, editing.body,
            )
            _state.update { state ->
                val detail = state.skills?.detail
                state.copy(
                    skills = state.skills?.copy(
                        editing = if (result.isSuccess) null else editing,
                        detail = detail?.copy(
                            tree = SkillStore.listTree(state.projectPath, detail.entry.scope, detail.entry.name),
                        ),
                    ),
                    message = if (result.isSuccess) "${editing.relativePath} 已保存"
                    else "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                    messageIsError = result.isFailure,
                )
            }
        }
    }

    fun deleteSkill(entry: SkillEntry) {
        val result = SkillStore.delete(_state.value.projectPath, entry.scope, entry.name)
        _state.update {
            it.copy(
                // 重新构造整个 SkillsState：正在浏览/编辑的若就是被删的那个，
                // 那些页必须一并收掉，否则界面会停在一个已经不存在的技能上。
                // 只保留列表页的筛选词。
                skills = SkillsState(
                    skills = SkillStore.list(it.projectPath),
                    query = it.skills?.query.orEmpty(),
                ),
                message = if (result.isSuccess) "已删除技能 ${entry.name}"
                else "删除失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
            )
        }
    }

    /**
     * 把技能附加到下一步任务。
     *
     * 走**文本附件**路径而不是直接塞进输入框：输入框是用户的编辑区，
     * 往里面灌几百行技能内容会让人没法继续写自己的话。
     * 附加后内容由 [buildPromptWithTextAttachments] 在发送时包进 `<attached_context>`。
     */
    fun attachSkill(entry: SkillEntry) {
        val body = SkillStore.read(_state.value.projectPath, entry.scope, entry.name).getOrElse { error ->
            _state.update { it.copy(message = "附加失败：${error.message ?: "未知原因"}", messageIsError = true) }
            return
        }
        val id = nextId("skill")
        _state.update { s ->
            // 同名技能只留一条：连着点两次「附加」应该还是那一份，而不是叠两份进提示词。
            val kept = s.attachments.filterNot { it.label == "skill:${entry.name}" }
            s.copy(
                attachments = kept + Attachment(
                    id = id,
                    label = "skill:${entry.name}",
                    detail = "${entry.scope.label} · ${entry.sizeLabel}",
                    isImage = false,
                    textBody = body,
                ),
                message = "已附加技能：${entry.name}",
            )
        }
    }

    // ---------- 角色卡 ----------

    /**
     * 打开角色卡管理器。
     *
     * 角色卡与技能的区别值得说清楚：技能是"按需加载的资料"（Agent 主动调用
     * `Skill` 工具，或用户手动附加一次），角色卡是**长期生效的人设指令**——
     * 引擎把启用中的内容作为 `<role_card>` 块拼进每一次系统提示词。
     */
    fun openRoleCards() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:roleCards")
        // 同 openSkills：侧栏入口也要把设置主页垫在底下，保证返回层级完整。
        // 先同步推页、数据后读，理由见 openApiConfig。
        _state.update {
            it.copy(
                roleCards = RoleCardsState(cards = emptyList(), activeId = "", loading = true),
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<android.app.Application>()
            val cards = runCatching { RoleCardStore.list(context) }.getOrDefault(emptyList())
            val activeId = runCatching { RoleCardStore.activeId(context) }.getOrDefault("")
            _state.update { s ->
                val current = s.roleCards ?: return@update s
                s.copy(
                    roleCards = current.copy(cards = cards, activeId = activeId, loading = false),
                )
            }
        }
    }

    fun closeRoleCards() {
        _state.update { it.copy(roleCards = null) }
        refreshExtensions()
    }

    fun newRoleCard() = _state.update {
        it.copy(roleCards = it.roleCards?.copy(editor = RoleCardEditor()))
    }

    fun editRoleCard(card: RoleCard) = _state.update {
        it.copy(
            roleCards = it.roleCards?.copy(
                editor = RoleCardEditor(id = card.id, name = card.name, content = card.content),
            ),
        )
    }

    fun updateRoleCardDraft(transform: (RoleCardEditor) -> RoleCardEditor) = _state.update {
        val state = it.roleCards ?: return@update it
        val editor = state.editor ?: return@update it
        it.copy(roleCards = state.copy(editor = transform(editor)))
    }

    fun cancelRoleCardEditor() = _state.update {
        it.copy(roleCards = it.roleCards?.copy(editor = null))
    }

    /**
     * 保存角色卡并**立即启用**。
     *
     * 与原版一致：保存一张卡就意味着"我现在要用它"，所以同时写清单、写 activeId、
     * 并把内容灌进引擎配置。分三步做是必须的——只写清单不写 activeId 的话，
     * 用户会看到卡保存成功却没有任何效果。
     */
    fun saveRoleCard() = runBusy("roleCards.save") {
        val editor = _state.value.roleCards?.editor ?: return@runBusy
        if (!editor.saveable) return@runBusy
        val context = getApplication<android.app.Application>()
        val result = RoleCardStore.upsert(
            context,
            RoleCard(id = editor.id, name = editor.name.trim(), content = editor.content),
        )
        result.fold(
            onSuccess = { saved ->
                roleCardOverride = saved.content
                refreshRoleCards(message = "角色卡「${saved.name}」已保存并启用")
                runCatching { engine.configure(engineOverrides()) }
            },
            onFailure = { error ->
                _state.update { it.copy(message = "保存失败：${error.message ?: "未知原因"}", messageIsError = true) }
            },
        )
    }

    /** 启用一张已存在的卡。 */
    fun selectRoleCard(card: RoleCard) = runBusy("roleCards.select") {
        val context = getApplication<android.app.Application>()
        val result = RoleCardStore.save(context, RoleCardStore.list(context), card.id)
        if (result.isFailure) {
            _state.update { it.copy(message = "启用失败：${result.exceptionOrNull()?.message ?: "未知原因"}", messageIsError = true) }
            return@runBusy
        }
        roleCardOverride = card.content
        refreshRoleCards(message = "已启用角色卡：${card.name}")
        runCatching { engine.configure(engineOverrides()) }
    }

    fun disableRoleCard() = runBusy("roleCards.disable") {
        val context = getApplication<android.app.Application>()
        val result = RoleCardStore.save(context, RoleCardStore.list(context), "")
        if (result.isFailure) {
            _state.update { it.copy(message = "停用失败：${result.exceptionOrNull()?.message ?: "未知原因"}", messageIsError = true) }
            return@runBusy
        }
        // 覆盖值置成空串（而不是 null）：null 表示"不覆盖"，那会退回配置里的旧值，
        // 于是"停用"看起来没生效——角色卡还挂在系统提示词里。
        roleCardOverride = ""
        refreshRoleCards(message = "角色卡已停用")
        runCatching { engine.configure(engineOverrides()) }
    }

    fun deleteRoleCard(card: RoleCard) {
        val context = getApplication<android.app.Application>()
        val wasActive = _state.value.roleCards?.activeId == card.id
        val result = RoleCardStore.delete(context, card.id)
        if (result.isFailure) {
            _state.update { it.copy(message = "删除失败：${result.exceptionOrNull()?.message ?: "未知原因"}", messageIsError = true) }
            return
        }
        if (wasActive) {
            roleCardOverride = ""
            runCatching { engine.configure(engineOverrides()) }
        }
        refreshRoleCards(message = "已删除角色卡：${card.name}")
    }

    private fun refreshRoleCards(message: String) {
        val context = getApplication<android.app.Application>()
        _state.update {
            it.copy(
                roleCards = RoleCardsState(
                    cards = RoleCardStore.list(context),
                    activeId = RoleCardStore.activeId(context),
                ),
                message = message,
            )
        }
    }

    /**
     * 把磁盘上启用中的角色卡内容读进会话覆盖值。
     *
     * 启动时调用一次：`SessionConfig.load()` 不带 roleCard（引擎只按 `applyProfile`
     * 填协议/地址/模型），所以不主动读的话，重启后角色卡在列表里显示"已启用"
     * 却不会被注入请求——正是那种"看起来配好了其实没生效"的状态。
     */
    private fun syncRoleCardFromStore() {
        val content = runCatching { RoleCardStore.activeContent(getApplication()) }.getOrDefault("")
        // 空串表示"确实没有启用的卡"，也要覆盖掉配置里的旧值，所以这里不能判空就跳过。
        roleCardOverride = content
    }

    // ---------- 记忆文件（ZhiCode.md） ----------

    fun openMemory() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:memory")
        // 同 openSkills：侧栏入口也把设置主页垫在底下。
        // 先同步推页、数据后读，理由见 openApiConfig。`MemoryStore.list` 要读文件正文
        // （`readText()`），放在主线程上同样会挡住点击那一帧。
        _state.update {
            it.copy(
                memory = MemoryState(files = emptyList(), loading = true),
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = _state.value.projectPath
            val files = runCatching { MemoryStore.list(path) }.getOrDefault(emptyList())
            _state.update { s ->
                val current = s.memory ?: return@update s
                s.copy(memory = current.copy(files = files, loading = false))
            }
        }
    }

    fun closeMemory() = _state.update { it.copy(memory = null) }

    /**
     * 打开 ZhiCode 沙箱管理界面（`SandboxBoard`）。
     *
     * 与「环境弹窗」不同，这里**不能**只改本进程的 UI 状态：沙箱引擎整个跑在
     * `:zhisandbox` 进程里，管理界面通过 `${applicationId}.sandbox.control` 这个同 UID
     * 私有 provider 与它通信（见 SandboxRpcService）。所以这里必须真的
     * 启动那个 Activity，而不是弹一个本地的 Compose 面板 —— 后者会得到一个
     * "永远连不上引擎"的空壳界面。
     *
     * ViewModel 手里只有 Application 上下文，从 Application 启动 Activity
     * 必须带 FLAG_ACTIVITY_NEW_TASK，否则直接抛 AndroidRuntimeException。
     * 失败时如实上报，不静默吞掉（例如清单里少声明了该 Activity）。
     */
    fun openSandbox() {
        // 见 hideSidebarForNavigation。这一行尤其明显：沙箱是个**跨进程的 Activity**，
        // 不收侧栏的话用户回到本应用时看到的还是那层侧栏，会以为"点了根本没打开"。
        hideSidebarForNavigation()
        val context = getApplication<android.app.Application>()
        val started = runCatching {
            context.startActivity(
                android.content.Intent(context, com.zhizhu.zhicode.sandbox.SandboxBoard::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        started.onFailure { error ->
            appendInfo(
                "无法打开 ZhiCode 沙箱",
                "启动沙箱管理界面失败：${error.javaClass.simpleName}: ${error.message ?: "未知原因"}\n\n" +
                    "沙箱引擎运行在 :zhisandbox 进程，界面必须由系统拉起而无法在本进程内绘制。",
            )
        }
    }


    /** 打开某个记忆文件编辑。文件不存在时给一份空编辑器（即"新建"）。 */
    fun editMemory(file: MemoryFile) {
        val path = _state.value.projectPath
        val body = MemoryStore.read(path, file.scope).getOrElse { error ->
            _state.update { it.copy(message = "打开失败：${error.message ?: "未知原因"}", messageIsError = true) }
            return
        }
        _state.update {
            it.copy(
                memory = it.memory?.copy(
                    editing = MemoryEditor(scope = file.scope, path = file.path, body = body, exists = file.exists),
                ),
            )
        }
    }

    fun updateMemoryBody(body: String) = _state.update {
        val memory = it.memory ?: return@update it
        val editing = memory.editing ?: return@update it
        it.copy(memory = memory.copy(editing = editing.copy(body = body)))
    }

    fun cancelMemoryEdit() = _state.update {
        it.copy(memory = it.memory?.copy(editing = null))
    }

    fun saveMemory() = runBusy("memory.save") {
        val state = _state.value
        val editing = state.memory?.editing ?: return@runBusy
        val result = MemoryStore.save(state.projectPath, editing.scope, editing.body)
        _state.update {
            it.copy(
                memory = MemoryState(files = MemoryStore.list(it.projectPath)),
                message = if (result.isSuccess) "${editing.scope.label} 已保存"
                else "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
            )
        }
    }

    /**
     * `/init`：让 Agent 直接创建或完善项目 ZhiCode.md。
     *
     * 指令照抄原版：明确要求**不要**进计划模式、不建任务、不起子 Agent、不做大范围调研，
     * 只读现有的 ZhiCode.md / CLAUDE.md / README 与主构建清单，然后直接写 ZhiCode.md。
     *
     * 两个细节都保留：
     * - 先退出计划模式。计划模式下引擎只产出计划不落盘，`/init` 会变成"只给我一份计划"，
     *   而那显然不是用户想要的。
     * - 对话流里放一条可见的 `/init` 用户气泡：这条请求是应用自己发的，
     *   不显示出来的话用户会看到一条自己没写过的消息引发的回复。
     */
    /** 界面入口：记忆面板里的「让智蛛完善」按钮（`ui/dialogs/MemoryOverlay.kt`）。 */
    fun runInitFromUi() = runInitPrompt()

    private fun runInitPrompt() {
        if (engine.isBusy()) {
            appendInfo("初始化项目说明", "智蛛正在执行任务，请先等待或停止当前任务。")
            return
        }
        runCatching { engine.cancelPlanModeFromUi("/init 使用快速直接模式") }

        _state.update {
            it.copy(
                transcript = it.transcript + ChatItem(id = nextId("u"), kind = ChatKind.USER, title = "你", body = "/init"),
                composerBusy = true,
                workingStatus = "正在整理项目说明…",
                busySessionIds = it.busySessionIds + it.activeSessionId,
            )
        }
        startTurn(INIT_INSTRUCTION)
    }

    fun removeAttachment(id: String) {
        attachmentPayloads.remove(id)
        _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }) }
    }

    // ---------- 发送 / 停止 ----------

    fun send() {
        val text = _state.value.composerText.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            _state.update { it.copy(composerText = "", slashQuery = null, slashMatches = emptyList()) }
            runSlashCommand(text)
            return
        }
        if (engine.isBusy()) {
            // 预输入，复刻原版 sendPrompt() 的 steering 分支（MainActivity.java:1425-1450）：
            //  1) 用户气泡**立刻**进对话流（原版同样 addChatView(userItem)），这就是可见的确认；
            //  2) 清空输入框与附件；
            //  3) 工作状态文案改成「已预输入，等待当前回复完成…」（原版 showWorkingIndicator）；
            //  4) 文案按当时在执行什么分三种（原版的 wasTool / wasModel / 其它）。
            // ⚠️ 追加到**队尾**（不是覆盖）：连发多条都要保留。
            //
            // 图片必须在 clearAttachments() **之前**取出来挂到气泡上：附件载荷存在
            // attachmentPayloads 里、由 clearAttachments() 整个清掉，而气泡一旦进了
            // 对话流就没法再回头找那张图 —— 这正是「发出去图片不见了」的成因。
            val userItem = ChatItem(
                id = nextId("u"),
                kind = ChatKind.USER,
                title = "你",
                body = text,
                images = currentImages(),
            )
            // 顺序很重要：先编好要发的内容块与拼好提示词，再把附件清掉。
            // 反过来的话队列里这条预输入就永远发不出那张图、也丢掉附加的技能了。
            val imageBlocks = buildImageBlocks()
            val promptWithTextAttachments = buildPromptWithTextAttachments(text)
            clearAttachments()
            _state.update { s ->
                val toolsRunning = s.transcript.lastOrNull()
                    ?.tools?.any { !it.completed } == true
                val queuedNote = when {
                    s.permissionRequest != null || toolsRunning -> "已预输入：当前工具完成后加载"
                    else -> "已预输入：当前回复完成后加载"
                }
                val queue = s.pendingInputs + QueuedPrompt(userItem.id, text)
                s.copy(
                    composerText = "",
                    slashQuery = null,
                    slashMatches = emptyList(),
                    transcript = s.transcript + userItem,
                    pendingInputs = queue,
                    workingStatus = queuedStatus(queue.size),
                    message = queuedNote,
                    // 发出去就必须看得见：用户可能正在翻历史，而此刻的意图很明确。
                    scrollToBottomToken = s.scrollToBottomToken + 1,
                )
            }
            // 真正的排队交给引擎：它会在「当前工具执行完 / 当前模型回复结束」这个
            // 协议安全点把预输入并入对话，并回调 onQueuedPromptApplied。
            // 界面这边不再自己维护"跑完一轮再发下一条"的递归链（那会与引擎重复排队）。
            val steered = engine.steer(promptWithTextAttachments, userItem.id, imageBlocks)
            if (!steered) {
                // 极端竞态：这里判定为忙，但真正入队时回合已经结束。
                // 不静默丢弃 —— 把文字放回输入框，用户能直接重发。
                _state.update { s ->
                    s.copy(
                        pendingInputs = s.pendingInputs.filterNot { q -> q.chatItemId == userItem.id },
                        composerText = text,
                        message = "上一回合刚好结束，消息未能入队，已放回输入框",
                    )
                }
            }
            return
        }
        val userItem = ChatItem(
            id = nextId("u"),
            kind = ChatKind.USER,
            title = "你",
            body = text,
            // 同上：必须在下面的 clearAttachments() 之前取，否则拿到的是空列表。
            images = currentImages(),
        )
        // 同上：先编内容块再清附件。
        val imageBlocks = buildImageBlocks()
        clearAttachments()
        _state.update {
            it.copy(
                composerText = "",
                slashQuery = null,
                slashMatches = emptyList(),
                transcript = it.transcript + userItem,
                contextTokens = it.contextTokens + 1_100,
                composerBusy = true,
                workingStatus = "正在思考…",
                busySessionIds = it.busySessionIds + it.activeSessionId,
                // 发送即恢复吸底：用户可能正在翻历史，气泡不能落在屏幕外。
                scrollToBottomToken = it.scrollToBottomToken + 1,
            )
        }
        startTurn(buildPromptWithTextAttachments(text), imageBlocks)
    }

    /**
     * 把一条提示词交给真实引擎。
     *
     * 会话配置**每一轮都重新下发**：用户在设置里改了权限模式/推理强度/上下文窗口后，
     * 不需要重启应用就能在下一轮生效。
     */
    private fun startTurn(prompt: String, extraContent: JSONArray? = null) {
        // 记下这一轮属于哪个会话。放在这里而不是各调用点：`startTurn` 是**唯一漏斗**
        // （普通消息与 /init 都走它），只需维护一处；错误迟到时靠它判断该不该落地。
        turnSessionId = _state.value.activeSessionId
        val configured = runCatching { engine.configure(engineOverrides()) }
        if (configured.isFailure) {
            // 配置阶段就失败（例如 SharedPreferences 损坏）时不要静默：
            // 否则界面会一直停在"正在思考…"，用户只能干等。
            finishTurnWithError("引擎配置失败：" + (configured.exceptionOrNull()?.message ?: "未知原因"))
            return
        }
        runCatching { engine.sendPrompt(prompt, extraContent) }.onFailure { error ->
            finishTurnWithError(ToolText.friendlyError(error.message, error))
        }
    }

    /**
     * 把当前界面状态翻译成引擎覆盖项。
     *
     * `model` **故意留空**：界面上的 `modelLabel` 目前还是演示标签，真正生效的模型
     * 来自 API 配置记录（`ApiSettingsStore`）。在这里覆盖会用一个不存在的模型名把请求打挂；
     * 等设置页接线（Phase 5）之后再把模型选择器接上。
     */
    private fun engineOverrides(): EngineOverrides {
        val s = _state.value
        // 搜索后端：**有生效的服务就用它**，否则回落到免费后端（auto）。
        //
        // 这是这次改造的关键接线：搜索服务页是"多服务列表 + 当前生效项"，
        // 而引擎只认「一个 provider + 一份配置」。同步一次之后两边就一致了 ——
        // 缺了这一段，用户在列表里切来切去，实际发出去的还是老配置（而界面不会报错）。
        val activeService = runCatching { SearchServiceStore.active(getApplication()) }.getOrNull()
        val serviceKey = activeService?.let {
            runCatching { ApiSettingsStore.getSearchServiceKey(getApplication(), it.id) }.getOrDefault("")
        }.orEmpty()
        val legacyKey = s.settings.webSearchKeys[s.settings.webSearchProvider] ?: ""
        return EngineOverrides(
            permissionMode = s.permissionMode.toEngineMode(),
            effort = s.effort.toEngineEffort(),
            contextWindow = s.contextWindow,
            projectDirectory = s.projectPath,
            customSystemPrompt = s.settings.customSystemPrompt,
            visionEnabled = s.settings.visionEnabled,
            autoCompact = s.settings.autoCompact,
            autoCompactPercent = s.settings.autoCompactPercent,
            webSearchEnabled = s.settings.webSearchEnabled,
            // 服务类型的小写名就是引擎侧的 provider（见 WebSearchTool.providerName）。
            webSearchProvider = activeService?.type?.name?.lowercase()
                ?: when (s.settings.webSearchProvider) {
                    WebSearchProvider.AUTO -> "auto"
                    WebSearchProvider.DUCKDUCKGO -> "duckduckgo"
                    WebSearchProvider.BING -> "bing"
                    WebSearchProvider.TAVILY -> "tavily"
                    WebSearchProvider.EXA -> "exa"
                    WebSearchProvider.BRAVE -> "brave"
                    WebSearchProvider.SEARXNG -> "searxng"
                },
            webSearchMaxResults = s.settings.webSearchMaxResults,
            webTimeoutSec = s.settings.webSearchTimeoutSec,
            // 密钥取**生效服务**的那一份；没有服务时回落到老配置的单键。
            webSearchApiKey = if (activeService != null) serviceKey else legacyKey,
            webSearchBaseUrl = activeService?.config?.get(SearchFieldName.BASE_URL)
                ?: s.settings.webSearchSearxngUrl,
            webSearchServiceConfig = activeService?.config?.let { config ->
                runCatching { org.json.JSONObject(config.toMap()).toString() }.getOrDefault("")
            } ?: "",
            rootExecutionEnabled = s.settings.rootExecutionEnabled,
            shizukuExecutionEnabled = s.settings.shizukuExecutionEnabled,
            sandboxAgentFullAccess = s.settings.sandboxAgentFullAccess,
            forcedKeepAliveEnabled = s.settings.forcedKeepAliveEnabled,
            model = modelOverride,
            roleCard = roleCardOverride,
        )
    }

    /**
     * 停止当前回合（也是**唯一的重试入口**）。
     *
     * <h3>为什么不能按一下就宣布"已停止"</h3>
     * `engine.cancel()` 只是把中断送到 agent 线程。真正把命令停掉要等工具执行栈收尾：
     * 先读 pid、再对整个进程组发信号、确认退出、不行再强制杀（见 `TermuxShellExecutor`）。
     * 这段收尾是**异步**的，而且可能很久 —— 命令正卡在一次不可中断的 IO 上时，
     * 要等它自己的超时（HTTP/MCP 30 秒起，包管理器 1 小时）。
     *
     * <p>所以这里不再当场清 `composerBusy`（那会让停止键在按下的同一帧消失，
     * 用户再也没有第二个能按的东西，最后只能清后台），而是进入 [WorkspaceUiState.stopping]：
     * 停止键留在原地、状态写"正在停止…"，由引擎的 `onTurnComplete` 来收尾。
     *
     * <h3>再按一次是"重发中断"，不是重复动作</h3>
     * 第二次之后不再清缓冲、不再重复把预输入放回输入框，只重发一次中断 ——
     * 第一次的中断可能正好被一次在途 IO 吃掉（那正是"按了没反应"的常见成因）。
     */
    fun stop() {
        val snapshot = _state.value
        // 什么都没跑时不进停止流程：否则会点亮一个"正在停止…"，
        // 三秒后再由看门狗自己收掉 —— 无中生有的一句状态。
        if (!snapshot.composerBusy && !snapshot.stopping
            && snapshot.workingStatus == null && !engine.isBusy()) {
            return
        }
        val attempt = stopAttempts + 1
        stopAttempts = attempt
        val firstAttempt = attempt == 1

        // 引擎的 cancel() 会一并取消权限/提问/计划三个门控，
        // 所以这里不需要再单独回执一次权限，只要把界面状态收干净。
        engine.cancel()
        if (firstAttempt) {
            pendingPermissionId = null
            // 在途的流式残片与工具进度缓冲全部丢弃：它们属于已经作废的这一轮。
            progressBuffers.clear()
            progressLastFlush.clear()
            liveTools.clear()
            currentGroupId = null
        }
        _state.update {
            // 停止时把还在排队的预输入**还原回输入框**（拼成多行逐条显示），
            // 而不是直接丢弃 —— 否则连发多条再按停止就会漏消息。
            // 只在第一次做：第二次按的时候队列已经空了，再拼一次只会重复。
            val restored = it.pendingInputs.joinToString("\n") { q -> q.text }
            it.copy(
                stopping = true,
                // 保持忙碌态：真实状态就是"命令可能还在跑"。
                composerBusy = true,
                workingStatus = if (firstAttempt) {
                    "正在停止当前任务…"
                } else {
                    "正在停止当前任务…（第 $attempt 次）"
                },
                permissionRequest = null,
                composerText = if (restored.isNotEmpty()) restored else it.composerText,
                pendingInputs = emptyList(),
                message = when {
                    firstAttempt && it.pendingInputs.isNotEmpty() ->
                        "正在停止当前任务，${it.pendingInputs.size} 条预输入已放回输入框"
                    firstAttempt -> "正在停止当前任务"
                    else -> "已再次发送停止指令"
                },
            )
        }
        armStopWatchdog()
    }

    /**
     * 停止看门狗：等待引擎的收尾确认，并且**不把界面锁死**。
     *
     * <p>正常情况几毫秒内就会回调 `onEngineTurnComplete`，这里只是兜底。
     * 分两档，因为两种"慢"要区别对待：
     *
     * <ol>
     *   <li>[STOP_CONFIRM_MS] 之后还在忙 —— 大概率是工具卡在一次不可中断的 IO 上。
     *       只改状态文案，**保留**停止键，让用户能再按一次重发中断。</li>
     *   <li>再等 [STOP_UNLOCK_MS] 仍未确认 —— 解锁输入框（`composerBusy = false`），
     *       但仍然保留停止键（`stopping` 不变）。宁可让用户能继续打字，
     *       也不要因为一次卡住的回合把整个界面冻住；也绝不说"已停止"，
     *       因为那时候我们并不知道。</li>
     * </ol>
     */
    private fun armStopWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = viewModelScope.launch {
            delay(STOP_CONFIRM_MS)
            if (!isStopping()) return@launch
            if (!engine.isBusy()) {
                clearStopState("已停止当前任务")
                return@launch
            }
            _state.update {
                if (!it.stopping) it
                else it.copy(
                    workingStatus = "停止指令已发送，正在等待命令退出…",
                    message = "命令没有立刻退出，可再点一次停止键重发中断",
                )
            }
            delay(STOP_UNLOCK_MS)
            if (!isStopping()) return@launch
            if (!engine.isBusy()) {
                clearStopState("已停止当前任务")
                return@launch
            }
            // 见 KDoc 第 2 档：解锁输入，保留停止键。
            _state.update {
                if (!it.stopping) it
                else it.copy(
                    composerBusy = false,
                    workingStatus = null,
                    busySessionIds = it.busySessionIds - it.activeSessionId,
                    message = "停止指令已发送，但命令仍在运行；可再次点击停止键重发中断",
                    messageIsError = true,
                )
            }
        }
    }

    private fun isStopping(): Boolean = stopAttempts > 0 && _state.value.stopping

    /** 收掉停止流程的簿记（看门狗 + 计数）；不动界面状态本身。 */
    private fun resetStopTracking() {
        stopWatchdog?.cancel()
        stopWatchdog = null
        stopAttempts = 0
    }

    /**
     * 停止流程结束（引擎确认收尾、或者看门狗判定它已经不忙了）。
     *
     * <p>[message] 为 null 表示不要动提示语 —— 正常完成的回合也走这里，
     * 那时候说一句"已停止当前任务"就是假话。
     */
    private fun clearStopState(message: String? = null) {
        resetStopTracking()
        _state.update {
            if (!it.stopping) it
            else it.copy(
                stopping = false,
                composerBusy = false,
                workingStatus = null,
                busySessionIds = it.busySessionIds - it.activeSessionId,
                message = message ?: it.message,
                messageIsError = if (message == null) it.messageIsError else false,
            )
        }
    }

    // ==================================================================
    // 引擎事件逐条落地
    //
    // 线程约定：这些方法**全部在主线程被调用**（ZhiEngineController 负责搬运），
    // 所以可以直接改 StateFlow、不需要再加 post/Handler。
    // 顺序约定：凡是"文本之后紧跟工具/结束"的地方，控制器都已先 flush 过文本缓冲，
    // 否则工具卡会插到还没显示的正文前面。
    // ==================================================================

    override fun onEngineSessionStarted(model: String) {
        streamingAssistantId = null
        currentGroupId = null
        liveTools.clear()
        progressBuffers.clear()
        progressLastFlush.clear()
        // 新回合开跑，上一轮的停止流程自动作废：看门狗若还挂着，
        // 它几秒后会去问"停了没有"，而那时候已经在跑新任务了。
        resetStopTracking()
        _state.update {
            it.copy(
                stopping = false,
                composerBusy = true,
                workingStatus = "正在思考…",
                busySessionIds = it.busySessionIds + it.activeSessionId,
            )
        }
    }

    /**
     * 取当前流式气泡的 id；没有就建一个。
     *
     * <p>原本只有 [onEngineText] 会建气泡。现在思考也要走这里 ——
     * 工具批次结束后模型**先思考再说话**，如果思考不建气泡，那一段思考会被
     * 静默丢掉（[onEngineThinking] 拿不到 id 就直接 return）。
     *
     * <p>「首个 delta 才创建」这件事没变。原版是回合开始时就插一个空气泡、
     * 结束时再丢弃空的；Compose 里那样会先闪一个空白块，观感更差 ——
     * 语义等价（空回复同样不会留下气泡，见 [finalizeStreaming] 的 keepIfEmpty）。
     */
    private fun ensureStreamingAssistant(): String {
        streamingAssistantId?.let { return it }
        val id = nextId("a")
        streamingAssistantId = id
        _state.update { s ->
            s.copy(
                transcript = s.transcript + ChatItem(
                    id = id,
                    kind = ChatKind.ASSISTANT,
                    title = "智蛛",
                    body = "",
                    streaming = true,
                    processSteps = listOf("开始分析请求"),
                ),
            )
        }
        return id
    }

    /**
     * 把当前流式气泡**封口**（定稿，不再往里追加），但不丢弃。
     *
     * <p>⚠️ 这是「正文与工具卡按时序穿插」的唯一支点：工具批次开始时必须先封口，
     * 否则工具结束后模型继续说的那段会被追加到**工具之前**的同一个气泡里 ——
     * 界面上的表现就是"正文全在最上面、工具卡全挤在下面"，时序完全是平的。
     *
     * <p>封口 ≠ 结束回合：正文气泡和工具卡都留在原位、顺序不变，
     * 后续正文会开一个**新的**气泡接在工具卡后面（[ensureStreamingAssistant]）。
     *
     * <p>还在转圈的东西不受影响：[currentGroupId] 归 [onEngineToolBatchCompleted]
     * 管，工具行自己的状态由 `ToolActivity.completed` 管。
     */
    private fun sealStreamingAssistant() {
        val id = streamingAssistantId ?: return
        streamingAssistantId = null
        _state.update { s ->
            s.copy(
                transcript = s.transcript.mapNotNull { item ->
                    when {
                        item.id != id -> item
                        // 一个字都没说过、也没思考过：这是延迟创建出来的空壳，
                        // 留着就是一块空白卡，直接丢掉。
                        item.body.isBlank() && item.thinking.isBlank() -> null
                        else -> item.copy(streaming = false)
                    }
                },
            )
        }
    }

    override fun onEngineText(delta: String) {
        val id = ensureStreamingAssistant()
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map {
                    if (it.id == id) it.copy(body = it.body + delta) else it
                },
                workingStatus = "正在回复…",
            )
        }
    }

    override fun onEngineThinking(delta: String) {
        val id = ensureStreamingAssistant()
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map {
                    if (it.id != id) it
                    // 思考预览按 1200 字符上限保留尾部（原版 liveThinking 的做法），
                    // 否则长思考会把内存和渲染都拖住。
                    else {
                        val merged = foldWhitespace(it.thinking + delta)
                        it.copy(thinking = if (merged.length > THINKING_LIMIT) merged.takeLast(THINKING_LIMIT) else merged)
                    }
                },
            )
        }
    }

    override fun onEngineToolBatchStarted(toolIds: List<String>) {
        // ⚠️ 先封口正文气泡，再开新的工具分组卡。
        //
        // 一个批次 = 一张新的工具分组卡，批次边界由引擎给，界面不猜。
        // 但批次边界同时意味着「上一段正文到此为止」—— 模型在工具之后说的话
        // 是新的一段，必须有自己的气泡，否则时序就平了。
        //
        // 引擎在工具事件之前已经 flush 过文本缓冲（见 onToolUse 的 flushTextNow），
        // 所以这里封到的一定是**已经显示出来**的那部分，不会把字留在后面。
        sealStreamingAssistant()
        val groupId = nextId("g")
        currentGroupId = groupId
        _state.update { s ->
            s.copy(
                transcript = s.transcript + ChatItem(
                    id = groupId,
                    kind = ChatKind.TOOL_GROUP,
                    tools = emptyList(),
                ),
            )
        }
    }

    override fun onEngineToolUse(id: String, name: String, input: JSONObject?) {
        // 工作流类工具（任务清单/计划模式）不产生工具卡，只更新进度与步骤，
        // 与原版一致：它们的效果体现在任务面板和计划窗口上。
        if (ToolText.isWorkflowTool(name)) {
            _state.update { it.copy(workingStatus = "正在执行 $name…") }
            appendProcessStep("调用工具：$name")
            return
        }

        // 防御路径：引擎没发批次开始事件就来了工具调用，这里自己开一张分组卡。
        // 顺手把正文气泡封口 —— 理由与 onEngineToolBatchStarted 相同：
        // 工具卡之前的正文到此为止，之后的要另起新气泡，否则时序是平的。
        var groupId = currentGroupId
        if (groupId == null) {
            sealStreamingAssistant()
            groupId = nextId("g")
            currentGroupId = groupId
        }
        val summary = ToolText.summary(name, input) ?: ""
        val command = input?.optString("command", "") ?: ""
        val (added, deleted) = ToolText.delta(name, input)
        val activity = ToolActivity(
            id = id,
            toolName = name,
            displayName = ToolText.userFacingName(name),
            summary = summary,
            additions = added,
            deletions = deleted,
            kind = kindOf(name),
            // 记下起点：引擎的 elapsedMs 只在有输出时被推过来，跑很久不吐字的命令
            // 标签会冻在最后一次进度的值上。有了起点，界面侧的定时刷新才能续走
            // （见 ToolActions.displayElapsedMs）。
            //
            // ⚠️ 用 `elapsedRealtime`（单调时钟，含休眠）而**不是** `currentTimeMillis`：
            // 后者会被系统对时/NTP/用户改时间拨动 —— 那种情况下秒表会突然跳一大步
            // 甚至变成负数。参考实现用的就是 `SystemClock.elapsedRealtime()`。
            startedAtMs = SystemClock.elapsedRealtime(),
            // 原始命令行（未截断）：折叠态用 summary，展开态要看完整的那一份。
            command = command,
            // 折叠组副行要显示的路径/模式。规则与 `summary` **不同**（见 ToolText.activityHint），
            // 所以在这里按参考实现的 `toolActivityHint` 算好存下来。
            hint = ToolText.activityHint(name, input),
            // `ReadMany` 读了几个文件 —— 组表头要按实际条数计入"读取 N 个文件"。
            readRequests = ToolText.readRequestCount(name, input),
        )
        liveTools[id] = LiveTool(groupId, name, command)

        _state.update { s ->
            val text = if (s.transcript.any { it.id == groupId }) s.transcript
            else s.transcript + ChatItem(id = groupId, kind = ChatKind.TOOL_GROUP)
            s.copy(
                transcript = text.map { item ->
                    if (item.id != groupId) item
                    else {
                        val tools = item.tools + activity
                        // ⚠️ 这里**不再**算一个批次级的汇总标签：一个批次里的工具会按
                        // 连续 read/search 被切成若干段（见 `ToolGrouping`），每段自己
                        // 带标题，批次级那个标签没有渲染位置了。
                        item.copy(tools = tools)
                    }
                },
                workingStatus = "正在执行 $name…",
            )
        }
        appendProcessStep("调用工具：$name")
    }

    override fun onEngineToolProgress(id: String, chunk: String, stderr: Boolean, elapsedMs: Long) {
        if (liveTools[id] == null) return
        // 累积**每一块**（不按 flush 间隔丢块）：缓冲要完整，只有"推给 StateFlow"
        // 这件事才做合并。
        //
        // ⚠️ `LiveOutput.append` 里有两件以前在这里做错的事：
        //  1. `[stderr]` 标记原来**每个** stderr chunk 都插一次，连续报错的命令会把
        //     输出刷成一片 `[stderr]`；现在只在 stdout/stderr 切换时插一次。
        //  2. 换行没有归一化，PTY 的 `\r\n` 会在界面上留下看不见的回车。
        val buffer = LiveOutput.append(
            previous = progressBuffers[id] ?: LiveOutput.Buffer(),
            chunk = chunk,
            stderr = stderr,
            keep = LIVE_OUTPUT_LIMIT,
        )
        progressBuffers[id] = buffer

        val now = System.currentTimeMillis()
        val last = progressLastFlush[id] ?: 0L
        if (now - last < PROGRESS_FLUSH_MS) return
        progressLastFlush[id] = now
        val text = buffer.text
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.kind != ChatKind.TOOL_GROUP) item
                    else item.copy(
                        tools = item.tools.map {
                            if (it.id == id) {
                                it.copy(
                                    output = text,
                                    // 运行标签要显示"错误输出有多少"，所以两个计数
                                    // 要跟着文本一起落进 state。
                                    stdoutChars = buffer.stdoutChars,
                                    stderrChars = buffer.stderrChars,
                                    elapsedMs = maxOf(it.elapsedMs, elapsedMs),
                                )
                            } else {
                                it
                            }
                        },
                    )
                },
            )
        }
    }

    override fun onEngineToolResult(
        id: String,
        name: String,
        isError: Boolean,
        exitCode: Int,
        content: String,
        diff: String,
        addedLines: Int,
        deletedLines: Int,
        command: String,
        previews: List<ChatImage>,
    ) {
        val live = liveTools.remove(id)
        progressBuffers.remove(id)
        progressLastFlush.remove(id)

        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.kind != ChatKind.TOOL_GROUP) item
                    else item.copy(
                        tools = item.tools.map { tool ->
                            if (tool.id != id) tool
                            else tool.copy(
                                completed = true,
                                failed = isError,
                                exitCode = exitCode,
                                output = content,
                                // ⚠️ 这个赋值不能省。引擎的写文件类工具把改动放在
                                // `result.diff`（工具自述里只有一句 "Wrote N bytes …"），
                                // 少这一行，界面那块更暗的 diff 预览井就永远不出现 ——
                                // 参数一路传到这里却被丢掉，编译器不会提醒。
                                diff = diff,
                                // 同 `diff`：漏了这行，沙箱截图就停在这一层，工具卡里什么都不显示
                                // （模型那边照样看得到图），且**编译器不会提醒**。
                                previews = previews,
                                elapsedMs = maxOf(tool.elapsedMs, 0L),
                                additions = if (addedLines > 0) addedLines else tool.additions,
                                deletions = if (deletedLines > 0) deletedLines else tool.deletions,
                                // 包管理器命令失败时默认展开：报错信息通常很长，
                                // 折叠着用户只能看到"退出码 1"，等于没说。
                                expanded = tool.expanded ||
                                    (isError && ToolText.isPackageManagerTool(command)),
                            )
                        },
                    )
                },
                workingStatus = "正在继续处理…",
            )
        }
        appendProcessStep("工具完成：$name")

    }

    override fun onEngineToolBatchCompleted(toolIds: List<String>) {
        if (toolIds.isEmpty()) return
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.kind != ChatKind.TOOL_GROUP) item
                    else {
                        val touched = item.tools.any { it.id in toolIds }
                        if (!touched) item
                        else item.copy(
                            groupCompleted = true,
                            // 批结束时仍没拿到结果的成员，原版会标记成失败并补一句话。
                            // 保留这个行为：静默留一个"永远转圈"的卡更难排查。
                            tools = item.tools.map { tool ->
                                if (tool.id in toolIds && !tool.completed) {
                                    tool.copy(
                                        completed = true,
                                        failed = true,
                                        output = tool.output.ifEmpty { "工具在返回结果前结束。" },
                                    )
                                } else tool
                            },
                        )
                    }
                },
            )
        }
        currentGroupId = null
    }

    override fun onEnginePermissionRequest(requestId: String, tool: String, summary: String, highRisk: Boolean) {
        pendingPermissionId = requestId
        _state.update {
            it.copy(
                permissionRequest = PermissionRequest(
                    id = requestId,
                    tool = tool,
                    subtitle = subtitleFor(tool),
                    detail = summary,
                    riskLevel = if (highRisk) RiskLevel.HIGH else RiskLevel.NORMAL,
                ),
            )
        }
    }

    override fun onEngineQuestionRequest(requestId: String, questionsJson: String) {
        val questions = runCatching { JSONArray(questionsJson) }.getOrDefault(JSONArray())
        if (questions.length() == 0) {
            // 空提问没有可问的内容，直接回执让引擎继续，别把用户晾在一个空窗口前。
            engine.respondQuestion(requestId, JSONObject())
            return
        }
        pendingQuestions = PendingQuestions(requestId = requestId, questions = questions)
        showQuestionStep()
    }

    /**
     * 展示提问流程的第 [step] 步。
     *
     * 引擎的 `AskUserQuestion` 有两种形态：单问题（直接提交）与多问题（分步，
     * 每步一个窗口，按钮是「下一步」，最后一步才是「提交」）。
     * 答案按**问题原文**为键累积（与引擎的约定一致）。
     */
    private fun showQuestionStep() {
        val flow = pendingQuestions ?: return
        val question = flow.questions.optJSONObject(flow.index)
        if (question == null) {
            // 该步不是合法对象：跳过它而不是整个流程失败。
            advanceQuestion(JSONObject())
            return
        }
        val options = question.optJSONArray("options")
        val choices = buildList {
            if (options != null) {
                for (i in 0 until options.length()) {
                    val option = options.optJSONObject(i) ?: continue
                    add(ChoiceOption(label = option.optString("label", ""), detail = option.optString("description", "")))
                }
            }
        }
        val isLast = flow.index >= flow.questions.length() - 1
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = question.optString("header", "问题"),
                    intent = ChoiceIntent.QUESTION,
                    prompt = question.optString("question", "智蛛应该怎么做？"),
                    options = choices,
                    allowFreeForm = true,
                    freeFormHint = "其他回答…",
                    multiSelect = question.optBoolean("multiSelect", false),
                    // 多问题时分步：中间步骤是「下一步」，最后一步才是「提交」。
                    submitLabel = if (isLast) "提交" else "下一步",
                ),
            )
        }
    }

    /** 记录这一步的答案并前进；已是最后一步则回执引擎。 */
    private fun advanceQuestion(answer: Any?) {
        val flow = pendingQuestions ?: return
        val question = flow.questions.optJSONObject(flow.index)
        val key = question?.optString("question", "") ?: ""
        if (key.isNotEmpty() && answer != null) {
            runCatching { flow.answers.put(key, answer) }
        }
        val next = flow.index + 1
        if (next >= flow.questions.length()) {
            pendingQuestions = null
            _state.update { it.copy(choicePicker = null) }
            engine.respondQuestion(flow.requestId, flow.answers)
            return
        }
        flow.index = next
        showQuestionStep()
    }

    /** 取消整个提问流程：回执空答案，引擎会按「未选择」继续。 */
    private fun cancelQuestions() {
        val flow = pendingQuestions ?: return
        pendingQuestions = null
        _state.update { it.copy(choicePicker = null) }
        engine.respondQuestion(flow.requestId, JSONObject())
        appendInfo("已跳过提问", "智蛛的提问被跳过，它会按「未选择」继续。")
    }

    override fun onEnginePlanApprovalRequest(request: EnginePlanApproval) {
        _state.update { it.copy(planApproval = request.toUiPlanApproval()) }
    }

    override fun onEngineTasksChanged(tasks: List<AgentTask>) {
        _state.update { it.copy(tasks = tasks) }
    }

    override fun onEngineProjectDirectoryChanged(directory: String) {
        _state.update {
            it.copy(
                projectPath = directory,
                projectName = directory.trimEnd('/').substringAfterLast('/').ifEmpty { directory },
                filePath = directory,
            )
        }
    }

    override fun onEngineQueuedPromptApplied() {
        // 引擎已经把队首预输入并入了对话。界面这边做的是：
        // 出队一条（FIFO），并把还在流的上一段回复收尾。
        finalizeStreaming(keepIfEmpty = false)
        _state.update { s ->
            val rest = s.pendingInputs.drop(1)
            s.copy(
                pendingInputs = rest,
                workingStatus = "预输入已加载，正在继续…",
                message = if (rest.isEmpty()) "预输入已加载，正在继续" else "预输入已加载，还剩 ${rest.size} 条",
            )
        }
    }

    override fun onEngineResponseRetry() {
        // 重试会重发整段回复：把上一次失败尝试留下的半截气泡丢掉，
        // 否则界面上会出现"半句话 + 完整回复"。
        discardStreamingAssistant()
        _state.update { it.copy(workingStatus = "连接中断，正在重试模型请求…", composerBusy = true) }
    }

    override fun onEngineInterruptedBySteering() {
        discardStreamingAssistant()
        _state.update {
            it.copy(
                workingStatus = "旧响应已打断，正在按最新纠正重新规划…",
                composerBusy = true,
            )
        }
    }

    override fun onEngineUsage(inputTokens: Long, outputTokens: Long) {
        val used = maxOf(0L, inputTokens) + maxOf(0L, outputTokens)
        _state.update { s ->
            // 有效窗口下限 16000：配置里若是 0/异常值，也不能让上下文条除出 Inf。
            val window = maxOf(16_000, engine.contextWindowTokens().takeIf { it > 0 } ?: s.contextWindow)
            val shown = if (used > 0) used.toInt() else engine.estimateContextTokens()
            s.copy(contextTokens = shown, contextWindow = window)
        }
    }

    override fun onEngineStatus(status: String) {
        val text = ToolText.displayStatus(status, "thinking", true)
        _state.update { it.copy(workingStatus = text) }
        appendProcessStep(text)
    }

    override fun onEngineTurnComplete(stopReason: String) {
        finalizeStreaming(keepIfEmpty = false)
        // 停止流程到此为止：引擎已经把这一轮收干净了（见 stop() 的说明）。
        // 这里取消看门狗，所以"等一下就好"那条兜底路径不会再多说一句话。
        resetStopTracking()
        val cancelled = "cancelled".equals(stopReason, ignoreCase = true)
        _state.update { s ->
            s.copy(
                // 只有"我们确实在等停止"且引擎报的是取消，才可以说已停止。
                // 正常跑完的回合也走这个回调，那时候说"已停止"就是假话。
                stopping = false,
                composerBusy = false,
                workingStatus = null,
                busySessionIds = s.busySessionIds - s.activeSessionId,
                contextTokens = engine.estimateContextTokens().takeIf { it > 0 } ?: s.contextTokens,
                sessions = s.sessions.map { session ->
                    if (session.id == s.activeSessionId) session.copy(busy = false, updatedAtLabel = "刚刚") else session
                },
                message = if (cancelled && s.stopping) "已停止当前任务" else s.message,
                messageIsError = if (cancelled && s.stopping) false else s.messageIsError,
            )
        }
        // 第一轮结束后会话才真正落库（引擎在 sendPrompt 里创建 SessionStore），
        // 所以这里必须重读列表，否则新会话在侧栏里不存在。
        syncActiveSessionFromDisk()
    }

    /**
     * 回合结束后把当前会话对齐到库里状态。
     *
     * 做两件事：
     * 1. 重读会话列表 —— 让刚创建的会话出现、让标题/消息数/时间刷新；
     * 2. 如果当前还没有 activeSessionId（刚 /new 过），把引擎刚创建的会话认领为当前会话。
     */
    private fun syncActiveSessionFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = SessionReader.list(_state.value.projectPath)
            val sessionId = engine.sessionId()
            _state.update { s ->
                val activeId = when {
                    s.activeSessionId.isNotEmpty() -> s.activeSessionId
                    sessionId != null -> sessionId
                    else -> ""
                }
                s.copy(sessions = sessions, activeSessionId = activeId)
            }
        }
    }

    override fun onEngineError(message: String, error: Throwable?) {
        finishTurnWithError(ToolText.friendlyError(message, error))
    }

    // ---------- 引擎事件用到的内部工具 ----------

    /**
     * 一轮以错误收尾：收尾气泡、落一条错误卡、把忙碌态清干净。
     *
     * ## 为什么开头要校验会话归属
     *
     * 报错可能是**迟到**的：用户在等待回复时切到了别的会话（或点了新会话），
     * 这一轮随即被 `engine.cancel()` 打断，而错误回调仍可能在之后到达。
     * `ZhiEngineController` 的代际校验能挡掉一部分，但 `startTurn` 这条路径是
     * 同步调用的、不经过代际门，而且 `_state` 此刻已经是**新会话**的 ——
     * 于是错误卡会落到新会话里，看起来像"串台"。
     *
     * 所以这里再按会话 id 兜一道：本轮所属的会话已经不是当前会话时，直接丢弃，
     * 不去污染新会话。（用户的预期是"错误应当出现在出问题的那个会话里"，
     * 但它已经不在前台、内存里也没有它的 transcript，落不进去；
     * 丢掉比串台正确 —— 那个会话下次打开时会从自己的记录重建。）
     */
    private fun finishTurnWithError(text: String) {
        val owner = turnSessionId
        turnSessionId = null
        if (owner != null && owner != _state.value.activeSessionId) return

        finalizeStreaming(keepIfEmpty = false)
        // 以错误收尾也算"停止流程结束了"：不取消看门狗的话，它几秒后还会
        // 去问一个已经结束的回合，顺手把提示语改掉。
        resetStopTracking()
        _state.update { s ->
            s.copy(
                transcript = s.transcript + ChatItem(
                    id = nextId("e"),
                    kind = ChatKind.ERROR,
                    title = "错误",
                    body = text,
                ),
                stopping = false,
                composerBusy = false,
                workingStatus = null,
                permissionRequest = null,
                busySessionIds = s.busySessionIds - s.activeSessionId,
            )
        }
        pendingPermissionId = null
    }

    /** 结束流式状态。[keepIfEmpty] 为 false 时，空白气泡会被丢弃（原版 discardEmptyStreamingMessage）。 */
    private fun finalizeStreaming(keepIfEmpty: Boolean) {
        val id = streamingAssistantId ?: return
        streamingAssistantId = null
        _state.update { s ->
            s.copy(
                transcript = s.transcript.mapNotNull { item ->
                    if (item.id != id) item
                    else if (!keepIfEmpty && item.body.isBlank() && item.thinking.isBlank()) null
                    else item.copy(streaming = false)
                },
            )
        }
    }

    /** 整条丢弃当前流式气泡（重试 / 被纠偏打断时用）。 */
    private fun discardStreamingAssistant() {
        val id = streamingAssistantId ?: return
        streamingAssistantId = null
        _state.update { s -> s.copy(transcript = s.transcript.filterNot { it.id == id }) }
    }

    /** 给当前助手气泡记一条过程步骤（最多 12 条，相邻重复去重，与原版一致）。 */
    private fun appendProcessStep(step: String) {
        val id = streamingAssistantId ?: return
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.id != id) item
                    else {
                        val merged = if (item.processSteps.lastOrNull() == step) item.processSteps
                        else (item.processSteps + step).takeLast(PROCESS_STEP_LIMIT)
                        item.copy(processSteps = merged)
                    }
                },
            )
        }
    }

    /** 思考文本的空白折叠：连续空白合成一个空格（原版 appendCollapsedWhitespace）。 */
    private fun foldWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var lastSpace = false
        for (ch in text) {
            val isSpace = ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r'
            if (isSpace) {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(ch)
                lastSpace = false
            }
        }
        return out.toString()
    }


    private fun queuedStatus(count: Int): String =
        if (count <= 1) "已预输入，等待当前回复完成…" else "已预输入 $count 条，等待当前回复完成…"

    /**
     * 回执一次权限请求。
     *
     * 回执对象是**引擎**而不是界面上的临时对象：引擎的工具线程正阻塞在
     * `PermissionGate.require()` 的闩锁上，只有真正 respond 过去它才会继续。
     * 若一直不回执，引擎会等满 30 分钟。
     */
    fun resolvePermission(allow: Boolean, alwaysAllow: Boolean = false) {
        val requestId = pendingPermissionId
        pendingPermissionId = null
        engine.respondPermission(requestId ?: "", allow)
        _state.update {
            it.copy(
                permissionRequest = null,
                permissionMode = if (alwaysAllow) PermissionMode.ACCEPT_EDITS else it.permissionMode,
                message = if (alwaysAllow) {
                    if (allow) "已允许本次调用，并切换为自动编辑" else "已拒绝本次调用，并切换为自动编辑"
                } else {
                    if (allow) "已允许本次调用" else "已拒绝本次调用"
                },
            )
        }
        // 切到自动编辑后要立刻下发，否则下一条工具调用还会弹窗。
        if (alwaysAllow) {
            runCatching { engine.configure(engineOverrides()) }
            // 这一次授权**改变了权限模式**（自动编辑），所以要落盘：
            // 它不再是"仅本次"的语义了。少了这一句，重启后会读回旧模式，
            // 用户会以为"总是允许"没生效过。
            persistRuntimeChoice(mode = PermissionMode.ACCEPT_EDITS)
        }
    }

    private fun kindOf(toolName: String): ToolKind = when (toolName) {
        "Grep", "Glob", "Search" -> ToolKind.SEARCH
        "Read", "ReadMany", "LS", "List", "Tree", "Stat" -> ToolKind.READ
        "Edit", "MultiEdit", "Write", "Move", "Delete", "Mkdir", "Copy" -> ToolKind.EDIT
        "Bash", "Root", "Shizuku", "BashTool" -> ToolKind.COMMAND
        else -> ToolKind.OTHER
    }

    private fun subtitleFor(tool: String): String = when (tool) {
        "Root" -> "高风险：智蛛请求以 Android uid 0 执行系统命令"
        "Shizuku" -> "智蛛请求以 shell 身份（uid 2000）执行 Android 系统命令"
        "Bash" -> "智蛛请求在本地 Termux 环境中执行以下命令"
        "AndroidIntent" -> "智蛛请求通过 Android 应用进程打开手机 App、网页或系统页面"
        "Write", "Edit", "MultiEdit" -> "智蛛请求修改项目中的文件"
        "Delete" -> "智蛛请求删除项目中的文件或目录"
        else -> "智蛛请求调用工具 $tool"
    }

    // ---------- 斜杠命令 ----------

    /**
     * 斜杠命令路由。
     *
     * ## 两条原则
     *
     * 1. **能接真功能的就接真功能**，不要保留"看起来执行了"的假回执。
     * 2. **没实现的就直说没实现**。原来兜底分支会打印"已执行 `xxx`（Mock）"，
     *    这是最糟的一种反馈：用户以为命令生效了，于是去做下一件事。
     *    现在的兜底是明确列出"这条还没移植"以及可用的替代指令。
     */
    private fun runSlashCommand(raw: String) {
        val command = raw.substringBefore(' ').lowercase(Locale.US)
        val arg = raw.substringAfter(' ', "").trim()
        when (command) {
            "/clear", "/new" -> newSession()
            "/terminal" -> selectTab(WorkspaceTab.TERMINAL)
            "/files" -> selectTab(WorkspaceTab.FILES)
            "/plan" -> enterPlanMode(arg)
            "/permissions" -> showPermissionPicker()
            "/effort" -> showEffortPicker()
            "/model" -> showModelPicker()
            "/cancel" -> stop()
            "/config" -> openSettings()
            // `/mcp` 与 IQ Code 0.30.4 的 `handleMcpSlash` 同一套子命令：
            // 不带参数打开面板，带参数走 list / add / enable / disable / remove。
            //
            // 之前这里**吞掉参数**直接开面板 —— 照着文档敲 `/mcp list` 的用户
            // 只会看到一个面板，不知道自己敲的其实是有效命令。
            "/mcp" -> handleMcpSlash(arg)
            "/copy" -> copyLatestAssistantReply()
            "/compact" -> compactContext(arg)

            // 环境类：都落到真实的 Termux 环境弹窗上（含安装/修复入口），
            // 不再是把命令当普通消息发出去换一句假回复。
            "/doctor", "/runtime" -> openEnvironment()
            "/repair" -> {
                openEnvironment()
                repairRuntime()
            }

            "/tasks" -> openTaskList()
            // 带参数 = 直接附加那个技能（见 handleSkillsSlash）。
            "/skills" -> handleSkillsSlash(arg)
            "/agents" -> openRoleCards()
            "/memory" -> openMemory()
            "/init" -> runInitPrompt()

            // 沙箱是**跨进程**的：引擎在 :zhisandbox，界面必须交给系统启动。
            // 以前这条命令没有路由，会掉进下面的"指令尚未移植"兜底卡片。
            "/sandbox" -> openSandbox()

            "/web" -> handleWebSlash(arg)
            "/shizuku" -> handleShizukuSlash(arg)

            "/context" -> {
                if (arg.isEmpty()) {
                    showContextInfo()
                } else {
                    val tokens = parseTokenCount(arg)
                    if (tokens == null) {
                        _state.update { it.copy(message = "请输入 128k、200k、1m、1.5m 这类格式", messageIsError = true) }
                    } else {
                        _state.update { it.copy(contextWindow = tokens, message = "上下文窗口已设置为 ${formatTokens(tokens)}") }
                    }
                }
            }

            "/resume" -> {
                openSidebar()
                _state.update { it.copy(message = "在侧栏选择要恢复的会话") }
            }

            "/usage", "/stats", "/status" -> showContextInfo()
            "/help" -> appendInfo(
                "全部指令",
                SLASH_COMMANDS.joinToString("\n") { "` ${it.name} ` — ${it.hint}" },
            )

            else -> appendInfo(
                "指令尚未移植",
                buildString {
                    append("`").append(command).append("` 在当前 Compose 版本里还没有对应实现，")
                    append("本机也没有执行任何操作。\n\n")
                    append("可用的相关指令：\n")
                    append("` /doctor ` `/runtime` `/repair` — 内置 Termux 环境\n")
                    append("` /compact ` — 真正调用模型压缩上下文\n")
                    append("` /context ` — 查看或设置上下文窗口\n")
                    append("` /copy ` — 复制最近一条回复\n")
                    append("` /tasks ` — 查看 Agent 任务列表\n")
                    append("` /resume ` — 从侧栏恢复历史会话\n")
                    append("` /mcp ` — MCP 服务器配置\n")
                    append("` /model ` — 切换模型")
                },
            )
        }
    }

    /**
     * 重新估算上下文用量。
     *
     * 压缩之后必须重算：`contextTokens` 平时是**累加**出来的（每轮加上 API 上报的用量），
     * 压缩会真的丢掉一部分历史，累加值只会越走越大，不重算就会显示一个虚高的占用。
     */
    private fun syncContextUsage() {
        _state.update { s ->
            val window = maxOf(16_000, engine.contextWindowTokens().takeIf { it > 0 } ?: s.contextWindow)
            s.copy(contextTokens = engine.estimateContextTokens(), contextWindow = window)
        }
    }

    /**
     * `/context 1m` 这类参数。
     *
     * 返回 null 表示格式不合法（而不是抛异常）——调用方要的是"给一句提示"，
     * 不是错误处理。边界与原版一致：16k–2m，超出范围会让引擎按无效窗口处理。
     */
    private fun parseTokenCount(raw: String): Int? {
        var s = raw.trim().lowercase(Locale.US).replace(",", "").replace("_", "")
        if (s.isEmpty()) return null
        var multiplier = 1.0
        when {
            s.endsWith("k") -> { multiplier = 1_000.0; s = s.dropLast(1) }
            s.endsWith("m") -> { multiplier = 1_000_000.0; s = s.dropLast(1) }
        }
        val value = s.toDoubleOrNull()?.times(multiplier) ?: return null
        if (!value.isFinite() || value < 16_000.0 || value > 2_000_000.0) return null
        return value.toInt()
    }

    /**
     * 「重试上一问」：把**被点那条消息之前**最近的一条用户消息重新发一次。
     *
     * 注意重发**不会**撤回上一轮的结果，所以它会作为新一轮追加在对话末尾
     * （与原版行为一致：原版也是重发，而不是回滚历史）。
     *
     * ## 为什么必须从"被点的那条"往前找
     *
     * 以前这里取的是整条对话的**最后一条**用户消息。于是用户翻到很早以前的一轮回复、
     * 对它点「重试上一问」时，重发的却是**最新**那个问题 —— 点 A 发了 B，
     * 而界面上看不出任何异常。参考实现同样是 `for (i = index - 1; i >= 0; i--)`，
     * 从被点的那条往前找。
     *
     * @param from 被点的那条消息。为空时退回"最后一条用户消息"（正常不会发生）。
     */
    private fun retryLastUserPrompt(from: ChatItem?) {
        // 定位逻辑在纯逻辑层（`ChatNavigation.previousUserPrompt`，有单测）：
        // 这里只负责把对话流翻译成它需要的最小输入。
        val transcript = _state.value.transcript
        val previous = ChatNavigation.previousUserPrompt(
            entries = transcript.map { item ->
                ChatNavigation.Entry(
                    id = item.id,
                    isUser = item.kind == ChatKind.USER,
                    body = item.body,
                )
            },
            anchorId = from?.id,
        )
        if (previous == null) {
            _state.update { it.copy(message = "这条消息之前没有可重试的问题", messageIsError = true) }
            return
        }
        _state.update { it.copy(composerText = previous) }
        send()
    }

    /**
     * `/web`：联网搜索。
     *
     * 四种形态（与原版 `handleWebSlash` 一致）：
     * - 无参数 → 打开联网设置
     * - `on` / `off` → 开关，**真的写进设置并重配引擎**（否则下一轮请求还按旧值走）
     * - `fetch <url>` → 直接读网页
     * - 其它 → 当作搜索词直接搜
     *
     * 后两种走 [ZhiEngineController.manualWebSearch]，**不经过模型**：
     * 用户输入后立刻拿到结果，省掉一次模型往返与相应的上下文消耗。
     */
    private fun handleWebSlash(arg: String) {
        when {
            arg.isEmpty() -> {
                openSettings()
                _state.update { it.copy(settingsDraft = it.settingsDraft?.copy(category = SettingsCategory.NETWORK)) }
            }
            arg.equals("on", ignoreCase = true) || arg == "开启" -> setWebSearch(true)
            arg.equals("off", ignoreCase = true) || arg == "关闭" -> setWebSearch(false)
            arg.startsWith("fetch ", ignoreCase = true) -> {
                val url = arg.substring(6).trim()
                if (url.isEmpty()) appendInfo("网页读取", "用法：`/web fetch https://example.com`")
                else runWebAction("网页读取", url) { engine.manualWebFetch(url) }
            }
            else -> runWebAction("联网搜索", arg) { engine.manualWebSearch(arg) }
        }
    }

    private fun setWebSearch(enabled: Boolean) {
        _state.update { s ->
            s.copy(
                settings = s.settings.copy(webSearchEnabled = enabled),
                settingsDraft = s.settingsDraft?.copy(webSearchEnabled = enabled),
                message = if (enabled) "联网搜索已开启" else "联网搜索已关闭",
            )
        }
        // 立刻下发：这一步容易漏，漏了就会"开关看起来变了，但下一轮请求还用旧值"。
        runCatching { engine.configure(engineOverrides()) }
    }

    /**
     * `/shizuku`：Agent Shizuku（无 Root 的 shell 身份）。
     *
     * 四种形态与原版的 `handleShizukuSlash` 一致：无参数 → 打开设置里的那一组，
     * `on` / `off` → 开关（真的落盘并重配引擎），`status` → 报告状态。
     *
     * <h3>为什么 status 要同时报两件事</h3>
     * 「工具开关」与「Shizuku 授权」是**两个独立的前提**：开着工具但没授权，
     * 模型一调用就会收到一条授权错误；只报其中一个，用户会以为另一半已经好了。
     */
    private fun handleShizukuSlash(arg: String) {
        when {
            arg.isEmpty() -> {
                openSettings()
                _state.update { it.copy(settingsDraft = it.settingsDraft?.copy(category = SettingsCategory.AGENT_SECURITY)) }
            }
            arg.equals("on", ignoreCase = true) || arg.equals("enable", ignoreCase = true) ->
                setShizukuExecution(true)
            arg.equals("off", ignoreCase = true) || arg.equals("disable", ignoreCase = true) ->
                setShizukuExecution(false)
            else -> {
                val state = ShizukuBridge.state(getApplication())
                val enabled = _state.value.settings.shizukuExecutionEnabled
                _state.update {
                    it.copy(
                        message = "Shizuku ${ShizukuBridge.stateLabel(getApplication())}"
                            + if (enabled) "；Agent 可用" else "；Agent 不可用（/shizuku on 开启）",
                        messageIsError = enabled && state != ShizukuBridge.GRANTED,
                    )
                }
            }
        }
    }

    /**
     * 开关 Shizuku 工具，并**立刻**落盘 + 重配引擎。
     *
     * 直接用 [applySettingsDraft]：它既是设置页那套自动保存的入口，
     * 也就顺带保证了「斜杠命令改的值」与「设置页改的值」走同一条持久化路径 ——
     * 两条各自写盘的话，后写的那条会把另一条的新值冲掉。
     */
    fun setShizukuExecution(enabled: Boolean) {
        val draft = _state.value.settingsDraft ?: SettingsDraft.from(_state.value)
        applySettingsDraft(draft.copy(shizukuExecutionEnabled = enabled))
        val granted = ShizukuBridge.state(getApplication()) == ShizukuBridge.GRANTED
        _state.update {
            it.copy(
                message = when {
                    !enabled -> "Agent Shizuku 已关闭"
                    granted -> "Agent Shizuku 已开启（shell 身份已授权）"
                    else -> "Agent Shizuku 已开启；还需要在 设置 → Agent 与安全 里授权"
                },
            )
        }
        runCatching { engine.configure(engineOverrides()) }
    }

    /**
     * 执行一次手动联网动作，并把文本结果落成一张助手卡片。
     *
     * 先放一张"进行中"的卡片：网络请求可能要几秒，没有反馈用户会以为没反应。
     * 结果出来后**原地替换**那张卡片，而不是追加一条——否则对话流里会留下
     * 一个永远停在"正在搜索…"的死卡片。
     */
    private fun runWebAction(title: String, subject: String, action: suspend () -> Result<String>) {
        val cardId = nextId("web")
        _state.update {
            it.copy(
                transcript = it.transcript + ChatItem(
                    id = cardId,
                    kind = ChatKind.ASSISTANT,
                    title = title,
                    body = "正在处理：`$subject` …",
                ),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            _state.update { s ->
                val body = result.getOrElse { error -> (error.message ?: "未知原因") }
                s.copy(
                    transcript = s.transcript.map { item ->
                        if (item.id != cardId) item else item.copy(body = body, streaming = false)
                    },
                )
            }
        }
    }

    /** `/context`（无参数）与 `/usage`、`/stats`、`/status` 共用。 */    private fun showContextInfo() {
        val s = _state.value
        val measured = engine.hasMeasuredContextUsage()
        appendInfo(
            "会话状态",
            buildString {
                append("模型：").append(s.modelLabel).append('\n')
                append("API 配置：").append(s.profileName)
                append(if (s.apiKeyConfigured) "（已配置密钥）" else "（未配置密钥）").append('\n')
                // 只显示项目名：完整路径在沙箱里是 /data/user/0/<宿主>/blackbox/...
                // 内部虚拟化路径，又长又吓人且用户无法据此操作（与附加选择器读不到目录时的空态同理）。
                append("项目：").append(s.projectPath.trimEnd('/').substringAfterLast('/')).append('\n')
                append("上下文：").append(formatTokens(s.contextTokens))
                append(" / ").append(formatTokens(s.contextWindow))
                append(if (measured) "（API 实测用量）" else "（本地估算，尚未产生 API 用量）").append('\n')
                append("权限：").append(s.permissionMode.label).append('\n')
                append("推理强度：").append(s.effort.label).append('\n')
                append("运行环境：").append(if (s.runtimeReady) "已就绪" else "未初始化")
            },
        )
    }

    /**
     * `/compact`：真正调用模型做语义压缩。
     *
     * 引擎侧是同步阻塞的（会走网络），所以放 IO 线程；期间给一条进行中提示，
     * 因为大上下文压缩可能要几十秒，没有反馈用户会以为卡死了。
     *
     * ⚠️ 引擎**不保证**真的调用了模型：`compactContextSemantic` 在算不出可压缩区间时
     * 会直接返回 [ENGINE_NOTHING_TO_COMPACT] 那句话（见其 `cut <= 0` 分支），
     * 引擎自己的自动压缩也用同一个前缀判断。所以这里必须分流——
     * 不加区分地宣称"已用模型生成摘要"就是在编造一次并不存在的模型调用。
     */
    private fun compactContext(instructions: String) {
        if (engine.isBusy()) {
            appendInfo("上下文压缩", "任务正在运行，完成后再压缩上下文。")
            return
        }
        // 用中性的进行时措辞：此时还不知道引擎会不会真的调模型。
        appendInfo("上下文压缩", "正在整理上下文…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { engine.compactContext(instructions) }
            result.fold(
                onSuccess = { summary ->
                    syncContextUsage()
                    val usage = "现在上下文：${formatTokens(_state.value.contextTokens)} / ${formatTokens(_state.value.contextWindow)}"
                    if (summary.startsWith(ENGINE_NOTHING_TO_COMPACT)) {
                        appendInfo(
                            "无需压缩",
                            "引擎判断当前没有可压缩的历史，因此**没有**调用模型。\n$usage",
                        )
                        return@fold
                    }
                    appendInfo(
                        "上下文压缩完成",
                        buildString {
                            append("已用模型生成语义摘要，后续请求改用摘要后的上下文。\n")
                            append(usage).append('\n')
                            if (summary.isNotBlank()) {
                                append("\n摘要要点：\n").append(summary.take(COMPACT_SUMMARY_LIMIT))
                                if (summary.length > COMPACT_SUMMARY_LIMIT) append("…")
                            }
                        },
                    )
                },
                onFailure = { error -> appendInfo("上下文压缩失败", error.message ?: "未知原因") },
            )
        }
    }

    /**
     * `/plan` 进入计划模式。
     *
     * 参考原版流程：先弹「计划目标」窗口问清楚要做哪件事，再产出计划。
     * 直接把计划推给用户（旧行为）会跳过"目标澄清"这一步。
     */
    private fun enterPlanMode(arg: String) {
        if (arg == "off" || arg == "exit") {
            runCatching { engine.cancelPlanModeFromUi("") }
            _state.update { it.copy(planApproval = null, message = "已退出计划模式") }
            return
        }
        if (engine.isBusy()) {
            _state.update { it.copy(message = "当前回合还在进行，等它结束后再进入计划模式") }
            return
        }
        val result = runCatching { engine.enterPlanModeFromUi() }
        val entered = result.getOrNull()?.isError == false
        if (!entered) {
            // 计划模式进不去时必须说出来。默默什么都不做的话，用户会以为
            // 自己已经在只读模式里了 —— 而实际上下一条消息会照常执行。
            finishTurnWithError("进入计划模式失败：" + (result.exceptionOrNull()?.message ?: "引擎拒绝了该请求"))
            return
        }
        _state.update { it.copy(permissionMode = PermissionMode.PLAN, message = "已进入计划模式（只读）") }
        // 带了目标就当成一条普通提示词发出去：计划模式下引擎只会产出计划，不会执行。
        // 「问目标」这一步由引擎自己用「选择」窗口发起（等提问门控接线后生效）。
        if (arg.isNotEmpty()) {
            _state.update { it.copy(composerText = arg) }
            send()
        }
    }

    /**
     * 批准 / 退回计划。
     *
     * 回执必须发给引擎：`onPlanApprovalRequest` 对应的工具线程正阻塞在审批闩锁上，
     * 只有 `respondPlanApproval` 才会让它继续。原版这个闩锁**故意没有超时**——
     * 无人值守的请求永远不能变成"批准执行"。
     */
    fun resolvePlan(approved: Boolean) {
        val plan = _state.value.planApproval ?: return
        engine.respondPlanApproval(
            plan.id,
            if (approved) PlanApprovalGate.Decision.APPROVE else PlanApprovalGate.Decision.KEEP_PLANNING,
            "",
        )
        appendInfo(
            if (approved) "计划已批准" else "计划已退回",
            if (approved) "开始执行：${plan.title}" else "等待修改后的计划。",
        )
        _state.update { it.copy(planApproval = null) }
    }


    /** 「继续规划」并带上用户填写的反馈。 */
    fun resolvePlanWithFeedback(feedback: String) {
        val plan = _state.value.planApproval ?: return
        val trimmed = feedback.trim()
        // 空反馈等价于"退回"：把 KEEP_PLANNING 与空 feedback 一起发过去，
        // 引擎会重新进入规划并等待下一版计划。
        engine.respondPlanApproval(plan.id, PlanApprovalGate.Decision.KEEP_PLANNING, trimmed)
        appendInfo(
            if (trimmed.isEmpty()) "计划已退回" else "已提交反馈",
            if (trimmed.isEmpty()) "等待修改后的计划。" else trimmed,
        )
        _state.update { it.copy(planApproval = null) }
    }

    // ---------- API 配置 ----------

    /**
     * 打开 API 配置（列表页）。
     *
     * 顺带刷新 [WorkspaceUiState.profileName] / [WorkspaceUiState.modelLabel]：
     * 这两个字段原来写死成演示值，现在它们是**真实生效的配置摘要**，
     * 否则顶栏会显示一个跟实际请求无关的模型名。
     */
    fun openApiConfig() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:api")
        // ---- 为什么页面状态必须先翻、数据后读 ----
        //
        // 原来的写法是「读完盘再翻页」：
        //
        // ```
        // viewModelScope.launch(Dispatchers.IO) {
        //     val state = ApiConfigStore.read(getApplication())   // 先等磁盘
        //     _state.update { it.copy(apiConfig = state, settingsOpen = true) }  // 才推页
        // }
        // ```
        //
        // 于是「点一下」到「页面开始动」之间隔着整整一次磁盘读 —— 用户的原话是
        // 「点击某些东西切换页面时，过一段时间才执行」。转场本身不慢，是它**开始得晚**：
        // 帧循环那几帧没有新状态可组合，只能一直等 IO 回来。
        //
        // 现在把顺序倒过来：本函数**同步**把页推上去（同一次点击事件、同一帧），
        // 载荷先给空 + `loading = true`，真正的读取挪到 IO，读完只回填数据字段、
        // 绝不碰页面栈。于是点击那一帧就有东西可画。
        //
        // 空载荷是安全的：`apiConfig != null` 在本工程里的语义就是「这一页开着」，
        // 而「一个配置档都没有」本来也是合法状态（子页没有对列表做 `!!`）。
        _state.update {
            it.copy(
                apiConfig = ApiConfigState(profiles = emptyList(), activeId = "", loading = true),
                // 设置主页垫在子页之下（页面栈），返回才有回退目标。
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { ApiConfigStore.read(getApplication()) }.getOrNull()
            _state.update { s ->
                // 读盘期间用户可能已经返回：那时载荷已置空，结果直接丢弃。
                val current = s.apiConfig ?: return@update s
                s.copy(apiConfig = loaded ?: current.copy(loading = false))
            }
            // 这两个只在读成功后才跑：它们要从刚读到的配置里推导当前档位。
            if (loaded != null) {
                syncActiveProfile()
                syncRoleCardFromStore()
            }
        }
    }

    fun closeApiConfig() = _state.update { it.copy(apiConfig = null) }

    // ---------------------------------------------------------------- 搜索服务

    /**
     * 打开「搜索服务」页（形态对齐 RikkaHub：多服务列表 + 当前生效项）。
     *
     * 与 [openApiConfig] 同一套页面栈规矩：设置主页垫在底下，返回才有回退目标。
     */
    fun openSearchServices() {
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:search")
        // 先同步推页、数据后读 —— 理由见 openApiConfig。原来这里同样把
        // `SearchServiceStore.read()`（还带上迁移）夹在「读盘」和「翻页」之间。
        _state.update {
            it.copy(
                searchServices = SearchServicesState(loading = true),
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 先把老版「单个 provider」迁成一条服务，否则老用户的配置会在这次打开时
            // 看起来"全都消失了"（列表空、而搜索也回落到免费后端）。
            runCatching {
                SearchServiceStore.migrateLegacyIfNeeded(
                    getApplication(),
                    _state.value.settings.webSearchProvider,
                    _state.value.settings.webSearchSearxngUrl,
                    _state.value.settings.webSearchKeys[_state.value.settings.webSearchProvider]
                        .orEmpty(),
                )
            }
            val loaded = runCatching { SearchServiceStore.read(getApplication()) }.getOrNull()
            _state.update { s ->
                // 读盘期间用户可能已经返回：载荷已置空，结果丢弃。
                val current = s.searchServices ?: return@update s
                s.copy(searchServices = loaded ?: current.copy(loading = false))
            }
        }
    }

    fun closeSearchServices() = _state.update { it.copy(searchServices = null) }

    /** 编辑页返回：只关表单、留在列表（与 API/MCP 页的 cancelForm 同一语义）。 */
    fun cancelSearchServiceForm() = _state.update {
        it.copy(searchServices = it.searchServices?.copy(form = null))
    }

    fun newSearchService() = _state.update {
        it.copy(searchServices = it.searchServices?.copy(form = SearchServiceDraft.blank()))
    }

    fun editSearchService(service: SearchService) = _state.update {
        it.copy(searchServices = it.searchServices?.copy(form = SearchServiceDraft.from(service)))
    }

    fun updateSearchServiceDraft(transform: (SearchServiceDraft) -> SearchServiceDraft) = _state.update {
        it.copy(searchServices = it.searchServices?.copy(form = transform(it.searchServices.form ?: SearchServiceDraft.blank())))
    }

    fun saveSearchService() {
        val draft = _state.value.searchServices?.form ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = SearchServiceStore.save(getApplication(), draft)
            result.fold(
                onSuccess = {
                    val services = SearchServiceStore.read(getApplication())
                    _state.update { s ->
                        s.copy(
                            searchServices = services,
                            message = "已保存搜索服务：${draft.name}",
                            messageIsError = false,
                        )
                    }
                    syncSearchServiceToEngine()
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(message = failure.message ?: "保存失败", messageIsError = true)
                    }
                },
            )
        }
    }

    fun selectSearchService(serviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SearchServiceStore.select(getApplication(), serviceId).fold(
                onSuccess = {
                    val services = SearchServiceStore.read(getApplication())
                    val name = services.services.firstOrNull { it.id == serviceId }?.name.orEmpty()
                    _state.update {
                        it.copy(searchServices = services, message = "搜索服务已切换：$name", messageIsError = false)
                    }
                    syncSearchServiceToEngine()
                },
                onFailure = {
                    _state.update { s -> s.copy(message = "切换失败", messageIsError = true) }
                },
            )
        }
    }

    fun deleteSearchService(serviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SearchServiceStore.delete(getApplication(), serviceId).fold(
                onSuccess = {
                    val services = SearchServiceStore.read(getApplication())
                    _state.update {
                        it.copy(searchServices = services, message = "已删除搜索服务", messageIsError = false)
                    }
                    syncSearchServiceToEngine()
                },
                onFailure = {
                    _state.update { s -> s.copy(message = "删除失败", messageIsError = true) }
                },
            )
        }
    }

    /**
     * 把「当前生效的搜索服务」推给引擎。
     *
     * 与 [syncActiveProfile] 同一类操作：切换服务之后下一次请求就该走新服务，
     * 而不是等用户再进一次设置。
     */
    private fun syncSearchServiceToEngine() {
        runCatching { engine.configure(engineOverrides()) }
    }

    /** 新增：给一张空表单。 */
    fun newApiProfile() = _state.update {
        it.copy(apiConfig = it.apiConfig?.copy(form = ApiProfileDraft.blank()))
    }

    /** 编辑：**故意不回填密钥**（见 ApiConfigStore 的说明）。 */
    fun editApiProfile(profile: ApiProfile) = _state.update {
        it.copy(apiConfig = it.apiConfig?.copy(form = ApiProfileDraft.from(profile)))
    }

    fun updateApiProfileDraft(transform: (ApiProfileDraft) -> ApiProfileDraft) = _state.update {
        val config = it.apiConfig ?: return@update it
        val form = config.form ?: return@update it
        it.copy(apiConfig = config.copy(form = transform(form)))
    }

    fun cancelApiProfileForm() = _state.update {
        it.copy(apiConfig = it.apiConfig?.copy(form = null))
    }

    /**
     * 保存一条 API 配置。
     *
     * 密钥写入 AndroidKeyStore 加密存储；编辑已有记录时若密钥留空，
     * 传 `replaceKey=false` 沿用原密钥，而不是把它清掉。
     */
    fun saveApiProfile() {
        val form = _state.value.apiConfig?.form ?: return
        if (!form.saveable) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = ApiConfigStore.save(getApplication(), form)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                _state.update { it.copy(message = "保存失败：" + (error?.message ?: "未知原因"), messageIsError = true) }
                return@launch
            }
            val state = ApiConfigStore.read(getApplication())
            _state.update { it.copy(apiConfig = state, message = "API 配置已保存") }
            syncActiveProfile()
            syncRoleCardFromStore()
        }
    }

    fun selectApiProfile(profileId: String) {
        // 换配置就用那份配置自己的模型，别把上一条配置的会话级覆盖带过去。
        modelOverride = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = ApiConfigStore.select(getApplication(), profileId)
            val state = ApiConfigStore.read(getApplication())
            _state.update {
                it.copy(
                    apiConfig = state,
                    message = if (result.isSuccess) "已切换 API 配置" else "切换失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                )
            }
            syncActiveProfile()
            syncRoleCardFromStore()
        }
    }

    fun deleteApiProfile(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = ApiConfigStore.delete(getApplication(), profileId)
            val state = ApiConfigStore.read(getApplication())
            _state.update {
                it.copy(
                    apiConfig = state,
                    message = if (result.isSuccess) "配置已删除" else "无法删除：${result.exceptionOrNull()?.message ?: "未知原因"}",
                )
            }
            syncActiveProfile()
            syncRoleCardFromStore()
        }
    }

    // ---------- MCP 配置 ----------

    /**
     * 打开 MCP 服务器配置（列表页）。
     *
     * 与 API 配置一样共用"列表页 / 表单页"两态，`form` 非空即表单。
     * 这里的读写是**同步**的：`McpConfigStore` 只读一个本地小 JSON，
     * 没有网络也没有密钥解密，放 IO 线程反而让状态更新顺序更难推理。
     */
    /**
     * `/mcp` 的子命令（对齐 IQ Code 0.30.4 的 `handleMcpSlash`）。
     *
     * | 命令 | 行为 |
     * | --- | --- |
     * | `/mcp` | 打开配置页 |
     * | `/mcp list` | 在对话流里列出全部服务器（启用状态 / 类型 / 端点 / 作用域） |
     * | `/mcp add` | 打开配置页并直接进入「添加」表单 |
     * | `/mcp enable <名称>` / `disable <名称>` | 切换启用状态 |
     * | `/mcp remove <名称>`（同 `delete`） | 删除那一条 |
     *
     * 打错的子命令**不会**变成一句"已执行"：它会列出支持的命令 ——
     * 与其它斜杠指令的兜底策略一致（不做假回执）。
     */
    private fun handleMcpSlash(arg: String) {
        val a = arg.trim()
        if (a.isEmpty()) {
            openMcpConfig()
            return
        }
        val op = a.substringBefore(' ').lowercase(Locale.US)
        val name = a.substringAfter(' ', "").trim()
        when (op) {
            "list", "列表" -> showMcpSummary()
            "add", "添加" -> {
                openMcpConfig()
                // 数据是异步读的：表单的开合状态由 mcpConfig 承载，所以等它落地再开表单。
                // 用 ready 标志而不是延时猜：读盘慢的机器上延时一定会错。
                mcpOpenFormWhenReady = true
            }
            "enable", "disable" -> {
                if (name.isEmpty()) {
                    _state.update { it.copy(message = "用法：/mcp $op <服务器名称>", messageIsError = true) }
                    return
                }
                val target = _state.value.mcpConfig?.servers?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: runCatching { McpStore.read().servers.firstOrNull { it.name.equals(name, ignoreCase = true) } }.getOrNull()
                if (target == null) {
                    _state.update { it.copy(message = "未找到 MCP 服务器：$name", messageIsError = true) }
                    return
                }
                val wantEnabled = op == "enable"
                if (target.enabled == wantEnabled) {
                    _state.update { it.copy(message = "${target.name} 已经是${if (wantEnabled) "启用" else "停用"}状态") }
                    return
                }
                val result = McpStore.setEnabled(target.name, wantEnabled)
                _state.update {
                    it.copy(
                        message = if (result.isSuccess) {
                            "${if (wantEnabled) "已启用" else "已停用"}：${target.name}"
                        } else {
                            "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
                        },
                        messageIsError = result.isFailure,
                    )
                }
            }
            "remove", "delete", "删除" -> {
                if (name.isEmpty()) {
                    _state.update { it.copy(message = "用法：/mcp $op <服务器名称>", messageIsError = true) }
                    return
                }
                val result = McpStore.delete(name)
                _state.update {
                    it.copy(
                        message = if (result.isSuccess) "已删除 MCP 服务器：$name"
                        else "删除失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                        messageIsError = result.isFailure,
                    )
                }
            }
            else -> _state.update {
                it.copy(
                    message = "/mcp 支持：list、add、enable <名称>、disable <名称>、remove <名称>",
                    messageIsError = true,
                )
            }
        }
    }

    /** `/mcp add` 要等异步读盘结束后再打开表单（见 [handleMcpSlash]）。 */
    private var mcpOpenFormWhenReady = false

    /**
     * `/mcp list`：把服务器清单写进对话流（与 IQ Code 的 `showMcpSummary` 一样
     * 落在对话里而不是弹窗里 —— 用户多半是"想看一眼再接着干活"）。
     */
    private fun showMcpSummary() {
        val servers = runCatching { McpStore.read().servers }.getOrDefault(emptyList())
        val body = buildString {
            if (servers.isEmpty()) {
                append("当前没有配置 MCP 服务器。输入 `/mcp` 打开配置页。")
            } else {
                servers.forEach { server ->
                    append(if (server.enabled) "● " else "○ ")
                    append("**").append(server.name).append("** · `").append(server.type.value).append("`")
                    append(" · ").append(if (server.scope == com.zhizhu.zhicode.compose.model.McpScope.PROJECT) "项目" else "用户")
                    append('\n')
                    append("  `").append(if (server.type.needsCommand) server.command else server.url).append("`")
                    append("\n\n")
                }
            }
        }
        appendInfo("MCP 服务器", body.trimEnd())
    }

    fun openMcpConfig() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        ZhiFrameTrace.begin("page:mcp")
        // 同 openSkills：hub 垫底，返回回设置主页。
        //
        // ⚠️ 这里原来是最糟的一处：`McpStore.read()`（内部 `EngineStore().load()` 读文件）
        // **直接写在主线程的 `_state.update{}` 里** —— 点击那一帧连绘制机会都没有。
        // 先同步推页、数据后读，理由见 openApiConfig。
        _state.update {
            it.copy(
                mcpConfig = McpConfigState(servers = emptyList(), filePath = "", loading = true),
                settingsOpen = true,
                settingsDraft = it.settingsDraft ?: SettingsDraft.from(it),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { McpStore.read() }.getOrNull()
            _state.update { s ->
                // 读盘期间用户可能已经返回：载荷已置空，结果丢弃。
                val current = s.mcpConfig ?: return@update s
                var next = loaded ?: current.copy(loading = false)
                // `/mcp add`：读盘完成后直接进「添加」表单（见 handleMcpSlash）。
                if (mcpOpenFormWhenReady) {
                    mcpOpenFormWhenReady = false
                    // 新建表单：`originalName = null` 才表示"新增"（见 McpServerDraft.isEditing）。
                    next = next.copy(form = McpServerDraft(originalName = null))
                }
                s.copy(mcpConfig = next)
            }
        }
    }

    fun closeMcpConfig() {
        _state.update { it.copy(mcpConfig = null) }
        refreshExtensions()
    }

    /**
     * `/skills` 的子命令（对齐 IQ Code 0.30.4 的 `/skills`：
     * 不带参数列清单，`<名称>` 直接把那份技能附加到下一条消息）。
     *
     * 之前这里**吞掉参数**直接开管理器 —— 敲 `/skills pdf` 的用户
     * 期望的是"把 pdf 这个技能挂上"，而不是打开一个列表再自己找。
     */
    private fun handleSkillsSlash(arg: String) {
        val name = arg.trim()
        if (name.isEmpty()) {
            openSkills()
            return
        }
        // 同名的项目级与用户级可以共存：优先项目级（更贴近当前工作目录）。
        val skills = runCatching { SkillStore.list(_state.value.projectPath) }.getOrDefault(emptyList())
        val target = skills.firstOrNull { it.name.equals(name, ignoreCase = true) && it.scope == SkillScope.PROJECT }
            ?: skills.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (target == null) {
            _state.update {
                it.copy(
                    message = "未找到技能：$name（输入 /skills 看清单）",
                    messageIsError = true,
                )
            }
            return
        }
        attachSkill(target)
    }

    fun newMcpServer() = _state.update {
        it.copy(mcpConfig = it.mcpConfig?.copy(form = McpServerDraft(originalName = null)))
    }

    /**
     * 编辑已有服务器：需要回读 `args` / `env` / `headers` 的原文，
     * 因为列表模型只带了参数个数（这些内容可能很大，不该进列表状态）。
     */
    fun editMcpServer(server: McpServer) {
        val raw = McpStore.rawOf(server.name)
        _state.update {
            val config = it.mcpConfig ?: return@update it
            val draft = if (raw != null) McpStore.draftOf(server, raw.first, raw.second, raw.third)
            else McpStore.draftOf(server, emptyList(), "", "")
            it.copy(mcpConfig = config.copy(form = draft))
        }
    }

    fun updateMcpDraft(transform: (McpServerDraft) -> McpServerDraft) = _state.update {
        val config = it.mcpConfig ?: return@update it
        val form = config.form ?: return@update it
        it.copy(mcpConfig = config.copy(form = transform(form)))
    }

    fun cancelMcpForm() = _state.update {
        it.copy(mcpConfig = it.mcpConfig?.copy(form = null))
    }

    fun setMcpQuery(query: String) = _state.update {
        it.copy(mcpConfig = it.mcpConfig?.copy(query = query))
    }

    fun saveMcpServer() = runBusy("mcp.save") {
        val form = _state.value.mcpConfig?.form ?: return@runBusy
        if (!form.saveable) return@runBusy
        val result = McpStore.save(form)
        _state.update {
            it.copy(
                // 成功时表单要关掉，否则用户会停在"已保存"的表单上再点一次。
                // 关闭动作在保存成功后做，失败时**保留表单**（内容不能丢）。
                mcpConfig = McpStore.read().let { fresh ->
                    if (result.isSuccess) fresh.copy(query = it.mcpConfig?.query.orEmpty()) else fresh
                },
                message = if (result.isSuccess) "MCP 配置已保存"
                else "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                messageIsError = result.isFailure,
            )
        }
        refreshExtensions()
    }

    /** 启用 / 停用：整行点击触发，等价于原版行尾的「启用/停用」按钮。 */
    fun toggleMcpServer(server: McpServer) = runBusy("mcp.toggle") {
        val result = McpStore.setEnabled(server.name, !server.enabled)
        _state.update {
            it.copy(
                mcpConfig = McpStore.read().copy(query = it.mcpConfig?.query.orEmpty()),
                message = when {
                    result.isFailure -> "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
                    server.enabled -> "已停用：${server.name}"
                    else -> "已启用：${server.name}"
                },
                messageIsError = result.isFailure,
            )
        }
        refreshExtensions()
    }

    fun deleteMcpServer(server: McpServer) = runBusy("mcp.delete") {
        val result = McpStore.delete(server.name)
        _state.update {
            it.copy(
                mcpConfig = McpStore.read().copy(query = it.mcpConfig?.query.orEmpty()),
                message = if (result.isSuccess) "已删除 MCP 服务器：${server.name}"
                else "删除失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                messageIsError = result.isFailure,
            )
        }
        refreshExtensions()
    }

    /**
     * 「测试连接」：真的去连这台服务器，拿回它的工具清单。
     *
     * ## 为什么必须由用户动作触发
     *
     * `ZhiEngineController.mcpStatus()` 是**阻塞且昂贵**的：每台服务器都要起一次
     * 子进程或发一轮 HTTP，单台超时 30 秒。如果在打开 MCP 页时自动跑一遍，
     * "打开设置看一眼"就会变成"把每台服务器都启动一次" —— 在用户还没决定要改什么
     * 之前就产生了副作用，而且页面会卡住。
     *
     * ## 为什么按名字单独测
     *
     * 引擎的接口是"列出所有启用的服务器"（没有单台的入口），所以这里测完之后
     * **只把那台的结果**并进状态：把其它服务器的结果也一起刷新会让人以为
     * "我只测了 A，为什么 B 的状态也变了"。
     */
    fun testMcpServer(name: String) {
        viewModelScope.launch {
            _state.update { state ->
                val config = state.mcpConfig ?: return@update state
                state.copy(mcpConfig = config.copy(testing = config.testing + name))
            }
            val result = withContext(Dispatchers.IO) { engine.mcpStatus() }
            _state.update { state ->
                val config = state.mcpConfig ?: return@update state
                val testing = config.testing - name
                result.fold(
                    onSuccess = { rows ->
                        val row = rows.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        state.copy(
                            mcpConfig = config.copy(
                                testing = testing,
                                // 没在结果里出现 = 这台被停用了（引擎只列启用的），
                                // 明确记一条而不是留着旧状态：留着会显示一个
                                // 与当前配置不符的"已连接"。
                                status = config.status + (name to (row ?: McpServerStatus(
                                    name = name,
                                    connected = false,
                                    error = "该服务器已停用，未被测试",
                                ))),
                            ),
                            message = when {
                                row == null -> "已停用，跳过测试：$name"
                                row.connected -> "$name 已连接，发现 ${row.tools.size} 个工具"
                                else -> "$name 连接失败"
                            },
                            messageIsError = row?.connected == false,
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            mcpConfig = config.copy(testing = testing),
                            message = "测试失败：${error.message ?: "未知原因"}",
                            messageIsError = true,
                        )
                    },
                )
            }
        }
    }

    /**
     * 「全部测试」：一次把**所有启用的**服务器都连一遍。
     *
     * 与逐台测试的关键差别：引擎的 `mcpStatus()` 本来就是"列全部启用的服务器"，
     * 所以批量测试**只调用它一次**，而不是循环调 N 次 —— 后者会把每台服务器
     * 重复启动 N 遍，代价是 N 倍，而且中间结果会互相覆盖。
     *
     * 结果整体替换 `status`（而不是并进旧的）：批量测试的语义就是"现在每台分别是什么状态"，
     * 保留旧值会让停用/已删除的条目继续显示"已连接"。
     */
    fun testAllMcpServers() {
        val config = _state.value.mcpConfig ?: return
        if (config.servers.none { it.enabled }) {
            _state.update { it.copy(message = "没有启用中的服务器可测试", messageIsError = true) }
            return
        }
        if (config.testing.isNotEmpty()) return
        val names = config.servers.filter { it.enabled }.map { it.name }
        viewModelScope.launch {
            _state.update { state ->
                val current = state.mcpConfig ?: return@update state
                state.copy(mcpConfig = current.copy(testing = names.toSet()))
            }
            val result = withContext(Dispatchers.IO) { engine.mcpStatus() }
            _state.update { state ->
                val current = state.mcpConfig ?: return@update state
                result.fold(
                    onSuccess = { rows ->
                        val connected = rows.count { it.connected }
                        state.copy(
                            mcpConfig = current.copy(
                                testing = emptySet(),
                                status = rows.associateBy { it.name },
                            ),
                            message = "测试完成：$connected / ${rows.size} 台已连接",
                            messageIsError = connected < rows.size,
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            mcpConfig = current.copy(testing = emptySet()),
                            message = "测试失败：${error.message ?: "未知原因"}",
                            messageIsError = true,
                        )
                    },
                )
            }
            refreshExtensions()
        }
    }

    /**
     * 打开「从 JSON 导入」对话框。
     */
    fun openMcpImport() = _state.update {
        it.copy(
            mcpConfig = it.mcpConfig?.copy(importText = "", importError = null, form = null),
        )
    }

    fun updateMcpImportText(text: String) = _state.update {
        it.copy(mcpConfig = it.mcpConfig?.copy(importText = text, importError = null))
    }

    fun cancelMcpImport() = _state.update {
        it.copy(mcpConfig = it.mcpConfig?.copy(importText = null, importError = null))
    }

    /**
     * 执行导入。同名**跳过不覆盖**（用户可能已经在本机改过那份配置），
     * 认不出的条目也一并报出来 —— 只说"导入完成"会让用户以为全都进来了。
     */
    fun importMcpJson() = runBusy("mcp.import") {
        val text = _state.value.mcpConfig?.importText ?: return@runBusy
        val result = McpStore.importJson(text)
        result.fold(
            onSuccess = { imported ->
                _state.update {
                    it.copy(
                        mcpConfig = McpStore.read(),
                        message = buildString {
                            append("已导入 ${imported.added.size} 个服务器")
                            if (imported.skipped.isNotEmpty()) {
                                append("，${imported.skipped.size} 个同名已跳过")
                            }
                            if (imported.invalid.isNotEmpty()) {
                                append("，${imported.invalid.size} 个缺少 url/command 未导入")
                            }
                        },
                        messageIsError = imported.added.isEmpty(),
                    )
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        mcpConfig = it.mcpConfig?.copy(
                            importError = error.message ?: "无法解析",
                        ),
                    )
                }
            },
        )
        if (result.isSuccess) refreshExtensions()
    }

    /**
     * 写入某个工具的「启用 / 需要审批」。
     *
     * ⚠️ 写完之后必须把**状态里的那一行**也改掉：引擎侧的过滤要到下次
     * `mcpStatus()` 才会生效，而用户点完开关立刻要看的就是那一行的变化。
     * 只写磁盘不改状态的话，开关会弹回去 —— 看起来像"点了没反应"。
     */
    fun setMcpToolOptions(serverName: String, toolName: String, enabled: Boolean, approval: Boolean) {
        val result = McpStore.setToolOptions(serverName, toolName, enabled, approval)
        _state.update { state ->
            val config = state.mcpConfig ?: return@update state
            val status = config.status[serverName] ?: return@update state
            state.copy(
                mcpConfig = config.copy(
                    status = config.status + (serverName to status.copy(
                        tools = status.tools.map { tool ->
                            if (tool.name == toolName) tool.copy(enabled = enabled, approval = approval) else tool
                        },
                    )),
                ),
                message = if (result.isSuccess) {
                    val tool = status.tools.firstOrNull { it.name == toolName }
                    val label = tool?.let { if (enabled) "已启用" else "已停用" } ?: "已更新"
                    "$toolName：$label" + if (approval) "（调用前需要确认）" else ""
                } else {
                    "保存失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
                },
                messageIsError = result.isFailure,
            )
        }
    }

    /**
     * 把当前生效的 API 配置同步到界面摘要，并让引擎重新读取。
     *     * 必须让引擎重读：它内部持有配置副本，不重新下发的话
     * 用户"保存了密钥却仍然报 API key is not configured"。
     */
    private fun syncActiveProfile() {
        val active = ApiConfigStore.active(getApplication())
        _state.update {
            it.copy(
                profileName = active?.name ?: "未配置",
                modelLabel = modelOverride ?: active?.model ?: "未设置",
                apiKeyConfigured = active?.hasKey == true,
            )
        }
        runCatching { engine.configure(engineOverrides()) }
    }

    private fun appendInfo(title: String, body: String) {
        _state.update {
            it.copy(transcript = it.transcript + ChatItem(id = nextId("i"), kind = ChatKind.INFO, title = title, body = body))
        }
    }

    /**
     * 把 **Compose 树之外**发生的事件写进对话流。
     *
     * 目前唯一的来源是 `MainActivity.onResume`：`AndroidIntentBridge.resumePendingApkInstall`
     * 在用户从「安装未知应用」权限页返回后会继续 APK 安装，并可能返回错误
     * （例如 APK 已损坏、系统安装器不可用）。这类结果必须落进对话流，
     * 因为一次性的 Toast 很容易被漏掉，而失败恰恰是最不能漏的。
     */
    fun reportExternalEvent(title: String, body: String) = appendInfo(title, body)

    // ---------- 输入器下拉（权限 / 推理）----------

    /*
     * 输入器页脚的权限 / 推理两个 chip 现在是**下拉菜单**
     * （Miuix `OverlayDropdownPreference`），选中即生效 —— 不需要"选完再提交"
     * 那一层，所以这里只有两个 setter。
     *
     * 副作用与旧实现保持一致：写状态 + 在对话里留一句可追溯的记录。
     */

    /**
     * 把盘上存的**权限模式 / 推理档**读回界面状态。
     *
     * ## 为什么必须有这一步（用户：「权限在退出软件后恢复成原来的样子」）
     *
     * 这两项确实是 [SessionConfig] 的字段、也确实会被 `store.save()` 写盘 ——
     * 但**界面状态从来不读回来**。于是形成一个固定回路：
     *
     * 1. 用户在输入器底排选了「跳过权限」→ 只有 `state.permissionMode` 变了；
     * 2. 发送时 `configure(engineOverrides())` 把它写盘（这一步是对的）；
     * 3. 重启 → `state.permissionMode` 又是默认的「每次询问」；
     * 4. 启动路径里的 `syncActiveProfile()` → `configure()` 立刻**落盘**，
     *    把界面那份默认值写回去 —— 盘上刚存的「跳过权限」就这样被冲掉。
     *
     * 前一步不做、第 4 步就会反向覆盖，所以这个函数读回来的不只是"显示"，
     * 它还决定了盘上的值能不能活过一次启动。
     *
     * ⚠️ 调用时机：**必须在任何 `configure()` 之前**（见 [initSessionState]）。
     * 读得晚一步，第 4 步已经把默认值写进盘里了，再读就只能读到刚被冲掉的那份。
     */
    private suspend fun restoreRuntimeChoices() = withContext(Dispatchers.IO) {
        val o = readEngineSettings(ApiSettingsStore(getApplication())) ?: return@withContext
        val mode = engineModeToUi(o.permissionMode)
        val effort = engineEffortToUi(o.effort)
        // 搜索后端只按名字回匹配；匹配不上（比如盘上留着旧版本写下的别名）就保持界面原值，
        // 不要拿一个猜出来的值覆盖 —— 那与"设置被冲掉"是同一类伤害。
        val provider = o.webSearchProvider?.let { stored ->
            WebSearchProvider.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
        }
        _state.update { s ->
            s.copy(
                permissionMode = mode,
                effort = effort,
                contextWindow = o.contextWindow ?: s.contextWindow,
                projectPath = o.projectDirectory?.takeIf { it.isNotBlank() } ?: s.projectPath,
                settings = s.settings.copy(
                    visionEnabled = o.visionEnabled ?: s.settings.visionEnabled,
                    customSystemPrompt = o.customSystemPrompt ?: s.settings.customSystemPrompt,
                    autoCompact = o.autoCompact ?: s.settings.autoCompact,
                    autoCompactPercent = o.autoCompactPercent ?: s.settings.autoCompactPercent,
                    webSearchEnabled = o.webSearchEnabled ?: s.settings.webSearchEnabled,
                    webSearchProvider = provider ?: s.settings.webSearchProvider,
                    webSearchMaxResults = o.webSearchMaxResults ?: s.settings.webSearchMaxResults,
                    webSearchTimeoutSec = o.webTimeoutSec ?: s.settings.webSearchTimeoutSec,
                    webSearchSearxngUrl = o.webSearchBaseUrl ?: s.settings.webSearchSearxngUrl,
                    sandboxAgentFullAccess = o.sandboxAgentFullAccess ?: s.settings.sandboxAgentFullAccess,
                    rootExecutionEnabled = o.rootExecutionEnabled ?: s.settings.rootExecutionEnabled,
                    shizukuExecutionEnabled = o.shizukuExecutionEnabled ?: s.settings.shizukuExecutionEnabled,
                    forcedKeepAliveEnabled = o.forcedKeepAliveEnabled ?: s.settings.forcedKeepAliveEnabled,
                ),
                // 设置页若已经开着（正常不会，这一步在启动路径上），草稿也要跟着对齐，
                // 否则页面显示的是旧值、点一下又把它写回去。
                settingsDraft = s.settingsDraft?.let { draft ->
                    draft.copy(
                        permissionMode = mode,
                        effort = effort,
                        contextWindow = o.contextWindow ?: draft.contextWindow,
                        projectPath = o.projectDirectory?.takeIf { p -> p.isNotBlank() } ?: draft.projectPath,
                        customSystemPrompt = o.customSystemPrompt ?: draft.customSystemPrompt,
                        visionEnabled = o.visionEnabled ?: draft.visionEnabled,
                        autoCompact = o.autoCompact ?: draft.autoCompact,
                        autoCompactPercent = o.autoCompactPercent ?: draft.autoCompactPercent,
                        webSearchEnabled = o.webSearchEnabled ?: draft.webSearchEnabled,
                        webSearchProvider = provider ?: draft.webSearchProvider,
                        webSearchMaxResults = o.webSearchMaxResults ?: draft.webSearchMaxResults,
                        webSearchTimeoutSec = o.webTimeoutSec ?: draft.webSearchTimeoutSec,
                        webSearchSearxngUrl = o.webSearchBaseUrl ?: draft.webSearchSearxngUrl,
                        sandboxAgentFullAccess = o.sandboxAgentFullAccess ?: draft.sandboxAgentFullAccess,
                        rootExecutionEnabled = o.rootExecutionEnabled ?: draft.rootExecutionEnabled,
                        shizukuExecutionEnabled = o.shizukuExecutionEnabled ?: draft.shizukuExecutionEnabled,
                        forcedKeepAliveEnabled = o.forcedKeepAliveEnabled ?: draft.forcedKeepAliveEnabled,
                    )
                },
            )
        }
    }

    /**
     * 把**权限模式 / 推理档**立刻写回磁盘。
     *
     * 为什么不能只靠发送前那次 `configure()`：用户完全可能"选完就退出"。
     * 那一刻盘上还是旧值，下次启动读回来的自然也是旧值 —— 表现与"设置记不住"一样。
     *
     * 走 `load()` + 改字段 + `save()` 而不是自己拼一份 `SessionConfig`：
     * `save()` 会把整张表写下去，凭空构造的对象会把其它字段（含 API 密钥槽）
     * 一起覆盖成空。
     *
     * 失败**不**上报：与 [persistTheme] 同一个约定 —— 这类界面偏好的写入
     * 不该在输入器旁边弹一条错误，内存里的改动本来就是即时生效的。
     */
    private fun persistRuntimeChoice(mode: PermissionMode? = null, effort: EffortLevel? = null) {
        if (mode == null && effort == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val store = ApiSettingsStore(getApplication())
                val config = store.load()
                mode?.let { config.permissionMode = it.toEngineMode() }
                effort?.let { config.effort = it.toEngineEffort() }
                store.save(config)
            }
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode, message = "权限模式：${mode.label}") }
        persistRuntimeChoice(mode = mode)
    }

    fun setEffort(level: EffortLevel) {
        _state.update { it.copy(effort = level, message = "推理强度：${level.label}") }
        persistRuntimeChoice(effort = level)
    }

    /*
     * 下面两个是**斜杠命令**用的（`/permissions`、`/effort`）。
     *
     * 为什么不共用下拉：斜杠命令是在终端/输入框里敲出来的，没有"点一下展开"
     * 的按钮可以挂下拉菜单，只能弹一个居中列表让人挑。所以这两条路都得留。
     */

    fun showPermissionPicker() {
        val values = PermissionMode.entries
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = "权限模式",
                    intent = ChoiceIntent.PERMISSION_MODE,
                    options = values.map { mode ->
                        ChoiceOption(
                            label = mode.label,
                            detail = mode.detail,
                            checked = mode == it.permissionMode,
                        )
                    },
                )
            )
        }
    }

    fun showEffortPicker() {
        val values = EffortLevel.entries
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = "推理强度",
                    intent = ChoiceIntent.EFFORT,
                    options = values.map { level -> ChoiceOption(level.label, checked = level == it.effort) },
                )
            )
        }
    }

    // ---------- 附加项目文件（浏览器，不是搜索） ----------

    /**
     * 打开「附加项目文件」面板。
     *
     * ## 从**项目根**起，这件事本身就是一次修 bug
     *
     * 这里原先调的是 `FileSearch.search(projectPath, "")` —— 一次**递归搜索**。
     * 而 `projectPath` 在 Termux 环境下就是 HOME，HOME 下有 Termux 的
     * `storage/{pictures,dcim,downloads,…}` 软链（`FileSearch` 只按名字排除
     * `build`/`node_modules` 这类构建目录），空查询的深度上限（2）又刚好够到
     * `storage/pictures/`。于是**面板一打开、用户一个字都没敲**，列出来的是
     * 一整屏 `storage/pictures/END…` 的设备截图，而不是项目文件。
     *
     * 现在改成**浏览一个目录**：一层一层走，永远不递归。那些软链只是
     * "可以点进去的一个目录"。
     */
    fun openAttachPicker() {
        val root = _state.value.projectPath
        _state.update {
            it.copy(
                attachPickerOpen = true,
                attachBrowser = AttachBrowserState(root = FileRoot.HOME, path = root),
            )
        }
        reloadAttachEntries(root)
    }

    fun closeAttachPicker() = _state.update {
        it.copy(attachPickerOpen = false, attachBrowser = AttachBrowserState())
    }

    /**
     * 目录内过滤：**纯内存**，只筛已经列出来的那一层。
     *
     * ⚠️ 刻意不做"敲字就重扫磁盘的递归搜索"—— 那正是上面那个 bug 的成因，
     * 而且每个字符都要遍历目录，在手机上会明显卡顿（这一条原代码也记过）。
     */
    fun updateAttachFilter(filter: String) = _state.update {
        it.copy(attachBrowser = it.attachBrowser.copy(filter = filter))
    }

    /** 进入一个目录（面包屑点某一级、或点某一行的目录）。 */
    fun attachNavigateTo(path: String) {
        _state.update {
            it.copy(attachBrowser = it.attachBrowser.copy(path = path, filter = ""))
        }
        reloadAttachEntries(path)
    }

    /**
     * 上一级。
     *
     * 到根就**停住**（回到根，而不是继续往外走）—— 与文件面板 `navigateUp` 同一套规则：
     * 换根是根切换条的活儿，不是"上一级"的活儿，否则在共享存储里点着点着会走到 `/`，
     * 而那里什么都列不出来。
     */
    fun attachUp() {
        val browser = _state.value.attachBrowser
        val root = attachRootPath(browser.root)
        if (browser.path == root) return
        val parent = browser.path.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent.length < root.length) {
            attachNavigateTo(root)
            return
        }
        attachNavigateTo(parent)
    }

    /** 切换选择器的根。切过去时回到该根的顶层（同文件面板 `switchFileRoot`）。 */
    fun attachSwitchRoot(root: FileRoot) {
        val path = attachRootPath(root)
        _state.update {
            it.copy(attachBrowser = AttachBrowserState(root = root, path = path))
        }
        reloadAttachEntries(path)
    }

    /** 某个 [FileRoot] 的顶层路径。与文件面板的 `rootPath()` 同一套对应关系。 */
    private fun attachRootPath(root: FileRoot): String = when (root) {
        FileRoot.HOME -> TermuxConstants.TERMUX_HOME_DIR_PATH
        FileRoot.SHARED -> StorageLinks.EXTERNAL_ROOT
    }

    /**
     * 列出**一层**子项（`FileBrowser.children`，与文件面板同一个数据来源）。
     *
     * ⚠️ 结果异步回来，所以落回状态前必须确认回来的是**当前**那个目录：
     * 用户点得快时会有多次列目录在飞，慢的那个回来会把新的覆盖掉
     * （与「错误串台到新会话」是同一类竞态）。面板关掉后路径已被清空，也不会写回。
     */
    private fun reloadAttachEntries(path: String) {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { FileBrowser.children(path) }
            // 空态要说清**为什么**空：与文件面板 reloadFiles 同一套判据，
            // 否则权限不足看起来和"这个目录是空的"一模一样。
            val dir = java.io.File(path)
            val note = when {
                entries.isNotEmpty() -> ""
                !dir.isDirectory -> if (dir.exists()) "不是目录：$path" else "目录不存在：$path"
                dir.list() == null -> "无法读取（权限不足）：$path"
                else -> ""
            }
            _state.update { s ->
                if (s.attachBrowser.path != path) return@update s
                s.copy(attachBrowser = s.attachBrowser.copy(entries = entries, note = note))
            }
        }
    }

    /**
     * 把一个项目文件附加到下一条消息。
     *
     * 走**文本附件**路径（与 [attachSkill] 一样）：内容由 [buildPromptWithTextAttachments]
     * 在发送时包进 `<attached_context>`，而不是灌进输入框 —— 输入框是用户的编辑区。
     *
     * 读取交给 [FileBrowser.readTextForAttachment]，它沿用界面预览那套 256 KB 截断与
     * NUL 二进制判定。所以附加一个几 MB 的日志不会把界面拖死，附加一个 `.so`
     * 也会被明确拒绝而不是塞一坨乱码进上下文。
     *
     * 附加**不关面板**：参考图的说明就是「可多次附加到下一条消息」。
     *
     * ## [relative] 由这里算，不由界面传
     *
     * 它只干两件事：**去重**（`detail == relative`）和**界面标签**。
     * 提示词正文走 [Attachment.textBody]，`<attachment name="…">` 用的是文件名
     * （见 [buildPromptWithTextAttachments]）—— 所以它不参与提示词格式，只要求唯一。
     *
     * 规则：文件在**项目根之下**时给项目相对路径（与旧行为一致、更短更好认），
     * 否则给绝对路径。后者是需要的 —— 选择器现在能切到 HOME 与共享存储，
     * 那两个根下面的文件本来就没有"项目相对路径"可言。
     *
     * ⚠️ 由 VM 算而不是让界面把 relative 一起传进来：界面不需要知道"项目根在哪"，
     * 那是这一层的事。旧签名 `attachProjectFile(path, relative)` 把这条知识漏到了界面。
     */
    fun attachProjectFile(path: String) {
        viewModelScope.launch {
            val name = File(path).name
            if (!attachPath(path, name)) {
                _state.update { it.copy(message = "附加失败：$name 不是文本文件或读不了", messageIsError = true) }
            }
        }
    }

    /**
     * 真正把一条文本附件放进状态。返回 false 表示这个文件不该/不能附加。
     *
     * <p>抽出来是因为现在有**三个**入口：附件面板点一行、选择模式批量附加、
     * 系统文件管理器（SAF）挑文件。三处各写一遍的话，"截断到 256 KB、
     * 二进制要拒绝、同一路径去重"这几条迟早只有一处生效。
     *
     * <p>[label] 单独传而不是从路径推：SAF 那条路上的路径是个 content URI，
     * 它的末段是文档 id（一串数字），显示给用户毫无意义 —— 那种情况下要传
     * 显示名。文件系统那条路上两者相同。
     */
    private suspend fun attachPath(path: String, label: String, detail: String? = null): Boolean {
        val body = withContext(Dispatchers.IO) { FileBrowser.readTextForAttachment(path) } ?: return false
        val size = withContext(Dispatchers.IO) { runCatching { File(path).length() }.getOrDefault(0L) }
        // 见 attachProjectFile 的 KDoc：项目根之下的给相对路径，否则给绝对路径。
        // SAF 那条路没有文件系统路径可算，由调用方直接把 detail 给进来。
        val key = detail ?: run {
            val projectRoot = _state.value.projectPath.trimEnd('/')
            if (projectRoot.isNotEmpty() && path.startsWith("$projectRoot/")) path.removePrefix("$projectRoot/") else path
        }
        _state.update { s ->
            // 同一路径只留一条（用 key 去重，它在项目内唯一）：
            // 连着点两次应该还是那一份，而不是叠两份进提示词。
            val kept = s.attachments.filterNot { it.detail == key }
            s.copy(
                attachments = kept + Attachment(
                    id = nextId("file"),
                    label = label,
                    detail = key,
                    isImage = false,
                    textBody = body,
                ),
                message = "已附加：$key（${FileFormat.size(size)}）",
                messageIsError = false,
            )
        }
        return true
    }

    /**
     * 系统文件管理器（SAF）挑来的文件直接附加。
     *
     * <h3>为什么不复制进项目目录</h3>
     *
     * 附件在引擎那边**本来就是内联文本**（见 [Attachment.textBody] 与
     * `buildPromptWithTextAttachments`）：发送时内容被包进 `<attached_context>`，
     * 不是把路径交给模型去读。所以"把文件复制到项目里"这一步对功能没有任何贡献，
     * 只会往用户的工程里丢一份副本（而用户挑的多半是项目外的文档）。
     *
     * 于是**不需要任何存储权限**：SAF 给的是一次性的读授权，我们当场读、
     * 读完就进内存。这也是为什么这条入口能work —— 直接去扫 `/sdcard` 反而要
     * 「所有文件访问权限」。
     *
     * <p>⚠️ 不取 `takePersistableUriPermission`：授权只为"立刻读一次"服务，
     * 读完内容就进了内存，不需要跨进程重启保留（与 SkillsOverlay 导入文件同一判断）。
     */
    fun attachDocument(uri: android.net.Uri) {
        viewModelScope.launch {
            val context = getApplication<android.app.Application>()
            val label = withContext(Dispatchers.IO) { documentDisplayName(context, uri) } ?: "文件"
            // 先拿到**字节**（而不是直接解成字符串）：长度要报给用户，
            // 而 `String.toByteArray()` 再算一遍是重新编码一次 —— 对 UTF-8 通常相等，
            // 但没必要绕这一圈，而且非 UTF-8 的输入会让数字对不上。
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // 与 FileBrowser 同一套上限：256 KB 截断 + NUL 判定二进制。
                        // 各写一份的话，"附加一个 500 MB 的视频"迟早会把内存吃光。
                        readBounded(input, DocumentAttachLimit)
                    }
                }.getOrNull()
            }
            if (bytes == null || (bytes.isNotEmpty() && bytes.any { it == 0.toByte() })) {
                _state.update { it.copy(message = "附加失败：$label 不是文本文件或读不了", messageIsError = true) }
                return@launch
            }
            val body = String(bytes, Charsets.UTF_8)
            _state.update { s ->
                val kept = s.attachments.filterNot { it.detail == uri.toString() }
                s.copy(
                    attachments = kept + Attachment(
                        id = nextId("doc"),
                        label = label,
                        detail = uri.toString(),
                        isImage = false,
                        textBody = body,
                    ),
                    message = "已附加：$label（${FileFormat.size(bytes.size.toLong())}）",
                    messageIsError = false,
                )
            }
        }
    }

    /** SAF 文档的显示名。取不到就返回 null，由调用方给个兜底标签。 */
    private fun documentDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 读满上限就停（与 `FileBrowser` 的 256 KB 同一口径）。 */
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /**
     * 打开模型选择面板。
     *
     * 面板先立起来（显示"正在获取"），目录拉取在 IO 线程后台跑；回来后再填列表。
     * 原版就是这个顺序，否则网络慢时点按钮像没反应。
     *
     * 会话正在跑的时候换模型不会打断当前轮：模型写在配置记录里，下一轮 `configure`
     * 自然带上（原版的"下一完整轮生效"）。
     */
    fun showModelPicker() {
        // 直接从存储读那几份 API 记录，而**不是**从 `s.apiConfig` 读：
        // 后者的语义是"API 配置弹窗当前打开"，面板显示时它恰好是 null，
        // 所以那样取值会永远落到 `s.profileName` 兜底上、tab 栏也永远没有数据。
        val config = ApiConfigStore.read(getApplication())
        val active = config.profiles.firstOrNull { it.id == config.activeId }
        _state.update { s ->
            s.copy(
                modelPicker = ModelPickerState(
                    profileName = active?.name ?: s.profileName,
                    currentModel = active?.model?.takeIf { it.isNotBlank() } ?: s.modelLabel,
                    // 面板一打开就先把"要用的模型名"填成当前生效的那个：
                    // 高亮看的正是这个字段，不填的话刚打开时一行都不高亮。
                    query = active?.model?.takeIf { it.isNotBlank() } ?: s.modelLabel,
                    profiles = config.profiles.map { ModelProfileTab(it.id, it.name) },
                    activeProfileId = config.activeId,
                ),
            )
        }
        fetchModelCatalog()
    }

    fun closeModelPicker() {
        modelCatalogJob?.cancel()
        modelCatalogJob = null
        modelSaveJob?.cancel()
        modelSaveJob = null
        _state.update { it.copy(modelPicker = null) }
    }

    /**
     * 切换面板里 tab 选中的那份 API 记录，并重新拉它自己的模型目录。
     *
     * ## 为什么不复用 [selectApiProfile]
     *
     * 那个函数会写 `apiConfig = state`，而 `apiConfig != null` 在本工程里的语义是
     * **"API 配置弹窗当前打开"**（`AppScaffold` 用它决定显示哪个弹窗）。在模型面板里
     * 调它就会在面板上凭空弹出一张配置页。这里只更新面板自己那份状态。
     */
    fun selectModelPickerProfile(profileId: String) {
        // 换配置就用那份配置自己的模型，别把上一条配置的会话级覆盖带过去。
        // 与 selectApiProfile 同一处理。
        modelOverride = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = ApiConfigStore.select(getApplication(), profileId)
            val config = ApiConfigStore.read(getApplication())
            val active = config.profiles.firstOrNull { it.id == config.activeId }
            _state.update { s ->
                // 面板可能已经被关掉了：那就只同步摘要，不要把状态又塞回去。
                val picker = s.modelPicker ?: return@update s
                s.copy(
                    modelPicker = picker.copy(
                        profileName = active?.name ?: picker.profileName,
                        currentModel = active?.model.orEmpty(),
                        // 换配置后旧配置的模型名不能留在框里 —— 那会让「完成」把
                        // A 家的模型名写到 B 家的配置上。清空比留错值安全。
                        query = active?.model.orEmpty(),
                        profiles = config.profiles.map { ModelProfileTab(it.id, it.name) },
                        activeProfileId = config.activeId,
                        loading = true,
                        status = "正在从当前 API 获取模型…",
                        models = emptyList(),
                    ),
                    message = if (result.isSuccess) "已切换 API 配置"
                    else "切换失败：${result.exceptionOrNull()?.message ?: "未知原因"}",
                )
            }
            // 面板还开着才需要重新拉目录；关掉了就不必再发一次请求。
            if (_state.value.modelPicker != null) fetchModelCatalog()
        }
        // 摘要与引擎配置要立刻跟上，否则底栏还显示旧配置名。
        syncActiveProfile()
        syncRoleCardFromStore()
    }

    fun setModelQuery(text: String) {
        _state.update { s -> s.copy(modelPicker = s.modelPicker?.copy(query = text)) }
    }

    /**
     * 重新拉一次模型目录（面板里额度卡片的「刷新」走这里）。
     *
     * 面板没开时直接返回：没有面板可更新，发出去的请求就只是白跑一趟。
     * 拉取本身复用 [fetchModelCatalog]，它已经把 loading 态与失败态都处理好了。
     */
    fun refreshModelCatalog() {
        if (_state.value.modelPicker == null) return
        _state.update { s ->
            s.copy(modelPicker = s.modelPicker?.copy(loading = true, status = "正在刷新模型与额度…"))
        }
        fetchModelCatalog()
    }

    private fun fetchModelCatalog() {
        modelCatalogJob?.cancel()
        modelCatalogJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ModelCatalogStore.fetch(getApplication()) }
            val fetched = result.getOrNull()
            _state.update { s ->
                // 面板可能已经被关掉了：这时不要把状态又塞回去。
                val picker = s.modelPicker ?: return@update s
                s.copy(
                    modelPicker = if (fetched == null) {
                        picker.copy(
                            loading = false,
                            status = ModelCatalogStore.friendlyError(result.exceptionOrNull()),
                            models = emptyList(),
                            // 列表没拿到，额度卡片也一并清掉：留着上一次的数字
                            // 会让人以为那是当前的（它可能已经是刷新前的旧值）。
                            quota = emptyList(),
                            quotaError = "",
                            modelsNote = "",
                        )
                    } else {
                        picker.copy(
                            loading = false,
                            status = if (fetched.models.isEmpty()) "API 未返回可用模型，可手动输入"
                            else "已获取 ${fetched.models.size} 个模型",
                            models = fetched.models,
                            quota = fetched.quota,
                            // 额度失败**不**让整次读取失败（见 ModelCatalogStore.Catalog）：
                            // 模型列表照旧可用，原因只写在额度卡片里。
                            quotaError = fetched.quotaError,
                            modelsNote = fetched.note,
                        )
                    },
                )
            }
        }
    }

    /**
     * 在列表里**点某一行**：立刻选中它并保存，但**不关面板**。
     *
     * 与 [applySelectedModel] 的区别只有"关不关面板"这一件事，但这一点决定了观感：
     * 之前只有点底部按钮才写配置，于是高亮要等那一下才动 —— 用户点了一行却看不到
     * 任何反馈，会以为没点上。现在点哪行哪行立刻变蓝，配置同时也已经存好了。
     *
     * 保存做成 **latest-wins**：连点几下时取消上一个写入任务。不取消的话多个 IO 写入
     * 并发进行、完成顺序不定，最后落盘的可能是中间那一次点到的模型 —— 而界面上显示的
     * 却是最后一次点的那个，两边就此分叉。
     */
    fun selectModel(model: String) {
        val target = model.trim()
        if (target.isEmpty()) return
        // 先把界面上那一份改掉，点击的反馈必须是**同步**的（等 IO 回来才变蓝会顿一下）。
        // 用于高亮的 `query` 就是"要用的模型名"，所以改它等于把高亮挪过去。
        _state.update { s -> s.copy(modelPicker = s.modelPicker?.copy(query = target)) }
        persistModel(target)
    }

    /**
     * 应用选定的模型：写进当前配置记录并重跑 `configure`，让下一次请求就用上新模型。
     *
     * 这里**不**走 `engineOverrides().model`：模型属于配置记录的持久化内容，
     * 只做一次内存覆盖会让"底栏显示的模型"和"实际请求的模型"在下一次启动后分叉。
     */
    fun applySelectedModel(model: String) {
        val target = model.trim()
        if (target.isEmpty()) return
        closeModelPicker()
        persistModel(target)
    }

    /** [selectModel] 与 [applySelectedModel] 共用的落盘部分（区别只在调用方关不关面板）。 */
    private fun persistModel(target: String) {
        modelOverride = target
        modelSaveJob?.cancel()
        modelSaveJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ApiConfigStore.setDefaultModel(getApplication(), target) }
            // 覆盖值先生效，所以即使写配置失败模型也已经切换了；把这一点如实说出来，
            // 而不是报一个"保存失败"让人以为模型没换。
            syncActiveProfile()
            syncRoleCardFromStore()
            _state.update {
                it.copy(
                    message = if (result.isSuccess) "模型：$target"
                    else "模型：$target（本次会话已生效，未能写入配置：${result.exceptionOrNull()?.message ?: "未知原因"}）",
                )
            }
        }
    }

    fun showMessageActions(item: ChatItem) {
        val options = when (item.kind) {
            ChatKind.USER -> listOf("复制", "再次发送", "编辑后发送")
            ChatKind.ASSISTANT, ChatKind.ERROR -> listOf("复制", "重试上一问")
            // 工具组这一层只留"复制整组概要"。
            //
            // 这里曾经给第二项是 `if (item.groupCompleted) "已全部完成" else "执行中"` —— 那是
            // **状态文字冒充菜单项**：点下去什么都不发生，而用户会以为坏了。
            // 单个工具该有的动作（复制命令/输出/diff、展开这一条）走那一行自己的
            // `⋯` 菜单（Miuix 下拉菜单 → [applyToolAction]），只有那一行知道
            // 用户点的是哪一条。
            else -> listOf("复制概要")
        }
        // 与 showSessionActions 同理：动作在**选择器回调**里执行，那时拿到的只有选项文案，
        // 不记住来源就会"复制了别的消息"。
        pendingMessageAction = item
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = "消息操作",
                    intent = ChoiceIntent.MESSAGE_ACTION,
                    // 锚到这条消息自己：列表里只有它的 Box 会认领这份菜单
                    anchorId = item.id,
                    options = options.map { ChoiceOption(it) },
                )
            )
        }
    }

    /**
     * 对话流里**单个工具**的 `⋯` 菜单里选中了一项 —— 执行它。
     *
     * ## 为什么这里只有"执行"，没有"弹菜单"
     *
     * 菜单由**那一行自己**渲染（Miuix 下拉菜单，与输入器底排同一个组件，
     * 见 `ui/chat/MessageCards.kt` 的 `ToolRow`）。所以这一层不需要
     * `choicePicker` 中转、也不需要"记住用户点的是哪一条"——
     * 目标（组 id + 行 id）与文案一起从界面传进来，一进来就能执行。
     *
     * 之前这里是 `showToolActions(item, toolId)`：先把菜单塞进 `choicePicker`，
     * 再由界面按 `anchorId` 认领。而 `ChoiceIntent.TOOL_ACTION` 不在
     * `ChoicePickerState.isActionMenu` 的名单里，于是它退化成**屏幕中央的对话框** ——
     * 一个只作用于某一行的动作，弹窗却出现在屏幕正中。
     *
     * ## 菜单内容仍然只由纯逻辑层决定
     *
     * 选项来自 [ToolActions.options]（表驱动、有单测），界面按同一份判据渲染。
     * 这一层负责**分派**：文案 → 动作。文案是 [ToolActions] 里的常量，
     * 所以两边不会各写一份。
     *
     * @param itemId 工具组那一条消息的 id（工具存在它下面）
     * @param toolId 那一行工具的 id
     * @param label  菜单文案（[ToolActions] 的常量）
     */
    fun applyToolAction(itemId: String, toolId: String, label: String) {
        val target = _state.value.transcript.firstOrNull { it.id == itemId }
        if (target == null) {
            _state.update { it.copy(message = "找不到目标工具，操作已取消") }
            return
        }
        val tool = target.tools.firstOrNull { it.id == toolId } ?: run {
            _state.update { it.copy(message = "找不到这个工具，操作已取消") }
            return
        }

        if (ToolActions.isToggle(label)) {
            setToolExpanded(target.id, toolId, ToolActions.toggledTo(label))
            return
        }

        when (ToolActions.copySource(label)) {
            ToolActions.CopySource.COMMAND -> copyText(tool.summary, "工具命令")
            ToolActions.CopySource.OUTPUT -> copyText(tool.output, "工具输出")
            ToolActions.CopySource.LIVE_OUTPUT ->
                // 运行中的实时输出就是 `output` 此刻的内容（进度回调持续写进去的）。
                copyText(tool.output, "工具实时输出")
            // diff 是输出里的一段：整份输出已经是 diff 文本，单独给"复制 Diff"
            // 是为了让用户不必先展开、也不必在长文本里自己挑。
            ToolActions.CopySource.DIFF -> copyText(tool.output, "工具 Diff")
            ToolActions.CopySource.INPUT -> copyText(toolActionInput(target, toolId), "工具参数")
            null -> _state.update { it.copy(message = "不支持的操作：$label") }
        }
    }

    /**
     * "复制参数"要复制的东西。
     *
     * 用界面上已经算好的 `summary`（命令类）或工具的展示名 —— 而不是再回到引擎的
     * 原始入参去取：那个入参在本层已经没有保存（只有渲染需要的字段被留下来），
     * 为了一个"复制"回头再加一份原始 JSON 的保留，代价大于收益。
     */
    private fun toolActionInput(item: ChatItem, toolId: String): String {
        val tool = item.tools.firstOrNull { it.id == toolId }
        if (tool == null) return ""
        return listOfNotNull(
            tool.displayName.takeIf { it.isNotBlank() },
            tool.summary.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    // ---------- 剪贴板 ----------

    /**
     * 复制一条消息的可见文本。
     *
     * 复制的是用户**看得到的内容**：助手消息取正文（没有正文时退回思考内容），
     * 工具组则是把各工具的标题拼起来——复制一个空串对用户毫无用处。
     */
    fun copyMessage(item: ChatItem) {
        val text = when (item.kind) {
            ChatKind.ASSISTANT, ChatKind.ERROR -> item.body.ifBlank { item.thinking }
            // 工具组没有正文，把各工具的展示名与摘要拼起来——复制空串对用户没用。
            ChatKind.TOOL_GROUP -> item.tools.joinToString("\n") { tool ->
                listOfNotNull(tool.displayName.takeIf { it.isNotBlank() }, tool.summary.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
            }
            else -> item.body
        }
        copyText(text, "智蛛消息")
    }

    /** `/copy`：复制最近一条助手回复。 */
    fun copyLatestAssistantReply() {
        val latest = _state.value.transcript.lastOrNull {
            it.kind == ChatKind.ASSISTANT && it.body.isNotBlank()
        }
        if (latest == null) {
            _state.update { it.copy(message = "还没有可复制的回复", messageIsError = true) }
            return
        }
        copyText(latest.body, "智蛛回复")
    }

    private fun copyText(text: String, label: String) {
        val ok = Clipboard.copy(getApplication(), label, text)
        _state.update {
            it.copy(
                message = when {
                    !ok -> "复制失败：内容为空或剪贴板不可用"
                    // Android 13 起系统自己会弹「已复制」，应用再弹一次就是重复打扰。
                    Clipboard.systemShowsCopyToast() -> ""
                    else -> "已复制到剪贴板"
                },
            )
        }
    }

    fun showSessionActions(session: SessionSummary) {
        // 记住操作对象：动作是在**选择器回调**里执行的，那时只能拿到选项文案，
        // 拿不到是哪条会话 —— 不记住就会出现"删掉了当前会话"这种错删。
        pendingSessionAction = session
        val options = if (session.note.isEmpty()) listOf("重命名", "编辑备注", "恢复会话", "删除会话")
        else listOf("重命名", "编辑备注", "清除备注", "恢复会话", "删除会话")
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = session.title,
                    intent = ChoiceIntent.SESSION_ACTION,
                    // 锚到这条会话自己：侧栏只有它的 Box 会认领这份菜单
                    anchorId = session.id,
                    options = options.map { ChoiceOption(it) },
                )
            )
        }
    }

    /** 打开备注编辑：没有选项，只靠自由输入提交。 */
    private fun editSessionNote(session: SessionSummary) {
        // 二级弹窗也要记得操作对象：SESSION_ACTION 分派时已经把它清空了，
        // 不补回去的话 onSubmitFreeForm 拿到 null，「备注未保存」是必现结果。
        pendingSessionAction = session
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = "会话备注",
                    intent = ChoiceIntent.SESSION_NOTE,
                    prompt = "给这条会话写一句备注，方便以后在侧栏里认出来。",
                    options = emptyList(),
                    allowFreeForm = true,
                    freeFormHint = if (session.note.isEmpty()) "例如：修好了相册崩溃" else session.note,
                ),
            )
        }
    }

    /** 打开重命名：同样只靠自由输入提交，输入框预填当前标题。 */
    private fun editSessionTitle(session: SessionSummary) {
        pendingSessionAction = session
        _state.update {
            it.copy(
                choicePicker = ChoicePickerState(
                    title = "重命名会话",
                    intent = ChoiceIntent.SESSION_RENAME,
                    prompt = "改一个你认得出的名字；只影响侧栏显示，不改会话内容本身。",
                    options = emptyList(),
                    allowFreeForm = true,
                    freeFormHint = session.title,
                ),
            )
        }
    }

    /** 写入备注到存储。空字符串表示清除。 */
    private fun saveSessionNote(session: SessionSummary, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // titleOverride 传空串 = 不覆盖标题，只改备注。
            val ok = SessionReader.updateMetadata(session.id, note, "")
            val sessions = SessionReader.list(_state.value.projectPath)
            _state.update {
                it.copy(
                    sessions = sessions,
                    message = if (ok) {
                        if (note.isEmpty()) "已清除备注" else "备注已保存"
                    } else {
                        "备注保存失败"
                    },
                )
            }
        }
    }

    /** 写入标题覆盖到存储，备注原样保留。 */
    private fun saveSessionTitle(session: SessionSummary, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = SessionReader.updateMetadata(session.id, session.note, title)
            val sessions = SessionReader.list(_state.value.projectPath)
            _state.update {
                it.copy(
                    sessions = sessions,
                    message = if (ok) "标题已更新" else "标题保存失败",
                )
            }
        }
    }

    fun dismissChoicePicker() {
        // 提问流程的「取消」必须把空答案回执给引擎，否则引擎会一直等
        // （`QuestionGate.ask` 的闩锁要等 30 分钟才超时）。
        if (_state.value.choicePicker?.intent == ChoiceIntent.QUESTION) {
            cancelQuestions()
            return
        }
        _state.update { it.copy(choicePicker = null) }
    }

    /**
     * 提交选择器里选中的项。
     *
     * [indices] 是选中的下标列表：单选选择器只会有 0 或 1 个，
     * 多选的提问流程会有多个。统一用列表是为了让两条路径共用一套 UI 回调。
     */
    fun onSubmitSelection(indices: List<Int>) {
        val picker = _state.value.choicePicker ?: return
        // 提问流程走自己的分步状态机，答案要按问题原文累积。
        if (picker.intent == ChoiceIntent.QUESTION) {
            val labels = indices.mapNotNull { picker.options.getOrNull(it)?.label }
            val answer: Any? = when {
                labels.isEmpty() -> null
                picker.multiSelect -> JSONArray(labels)
                else -> labels.first()
            }
            advanceQuestion(answer)
            return
        }
        // 单选选择器语义不变：等价于点第一项。
        onChoiceSelected(indices.firstOrNull() ?: -1)
    }

    fun onChoiceSelected(index: Int) {
        val picker = _state.value.choicePicker ?: return
        val option = picker.options.getOrNull(index) ?: return
        when (picker.intent) {
            // 这两条只服务斜杠命令 `/permissions`、`/effort`（见 showPermissionPicker）。
            // 输入器上的入口是下拉菜单，走 setPermissionMode / setEffort，不经过这里。
            ChoiceIntent.PERMISSION_MODE -> {
                val mode = PermissionMode.entries.getOrNull(index)
                if (mode != null) {
                    _state.update {
                        it.copy(choicePicker = null, permissionMode = mode, message = "权限模式：${mode.label}")
                    }
                    // 斜杠命令这条路与输入器下拉是**同一件事**，落盘也不能只做一边：
                    // 少写这一处，用户用 `/permissions` 改完之后重启就会被打回旧值
                    // （启动时会按盘上的值回填界面）。
                    persistRuntimeChoice(mode = mode)
                } else _state.update { it.copy(choicePicker = null) }
            }
            ChoiceIntent.EFFORT -> {
                val level = EffortLevel.entries.getOrNull(index)
                if (level != null) _state.update {
                    it.copy(choicePicker = null, effort = level, message = "推理强度：${level.label}")
                } else _state.update { it.copy(choicePicker = null) }
            }
            // 这里**没有** MODEL：模型面板要异步拉目录并写回配置记录，
            // 用 5 个固定选项的通用选择器表达不了，已换成 ui/dialogs/ModelPickerOverlay。
            ChoiceIntent.MESSAGE_ACTION -> {
                val target = pendingMessageAction
                pendingMessageAction = null
                _state.update { it.copy(choicePicker = null) }
                if (target == null) {
                    // 拿不到来源就不猜：宁可无反应，也不能复制到别的消息。
                    _state.update { it.copy(message = "找不到目标消息，操作已取消") }
                } else when (option.label) {
                    // 「复制概要」而不是「复制」：工具组没有正文，复制的是各工具的
                    // 展示名与摘要。用动词说清"复制的是什么"比一个笼统的"复制"好 ——
                    // 用户点下去才知道拿到的是概要而不是某一条的完整输出。
                    "复制概要" -> copyMessage(target)
                    "复制" -> copyMessage(target)
                    "再次发送" -> {
                        // 复用用户消息的原文重发；工具组这类没有正文的目标不支持。
                        if (target.body.isBlank()) _state.update { it.copy(message = "这条消息没有可重发的内容", messageIsError = true) }
                        else {
                            _state.update { it.copy(composerText = target.body) }
                            send()
                        }
                    }
                    "编辑后发送" -> _state.update { it.copy(composerText = target.body, message = "已放回输入框，可编辑后发送") }
                    "重试上一问" -> retryLastUserPrompt(target)
                    else -> _state.update { it.copy(message = "不支持的操作：${option.label}") }
                }
            }
            // 单个工具的动作**不经过这里**：那一行的 `⋯` 是 Miuix 下拉菜单，
            // 选中的文案直接走 applyToolAction(itemId, toolId, label)，
            // 不存在"弹菜单 → 回调里回头找目标"这一段。
            ChoiceIntent.SESSION_ACTION -> {
                val target = pendingSessionAction
                pendingSessionAction = null
                _state.update { it.copy(choicePicker = null) }
                if (target == null) {
                    // 拿不到操作对象就什么都不做。宁可无反应，也不能猜一条会话删掉。
                    _state.update { it.copy(message = "找不到目标会话，操作已取消") }
                } else when (option.label) {
                    "重命名" -> editSessionTitle(target)
                    "删除会话" -> deleteSession(target.id)
                    "恢复会话" -> openSession(target)
                    "编辑备注" -> editSessionNote(target)
                    "清除备注" -> saveSessionNote(target, "")
                    else -> _state.update { it.copy(message = "${option.label}（未接线）") }
                }
            }
            ChoiceIntent.SESSION_NOTE -> {
                // 这个分支正常不会走到（备注靠自由输入提交），留作兜底。
                _state.update { it.copy(choicePicker = null) }
            }
            ChoiceIntent.SESSION_RENAME -> {
                // 同上：重命名也只靠自由输入提交，这里只是穷尽性兜底。
                _state.update { it.copy(choicePicker = null) }
            }
            ChoiceIntent.QUESTION -> {
                // 提问流程正常走 onSubmitSelection / dismissChoicePicker；
                // 单选回调真落到这里时按「跳过本步」处理，不要静默什么都不做。
                advanceQuestion(null)
            }
            // 通用选择器：没有任何附加语义，选中只意味着"用户做了选择"。
            // 这里**不能**再打印"已执行 xxx（Mock）"——那会让人以为某个动作发生了。
            // 附件也不再走这里：它已经有真正的系统图片选择器（attachImage）。
            ChoiceIntent.GENERIC -> _state.update {
                it.copy(choicePicker = null, message = "已选择：${option.label}")
            }
            // 计划模式下的「目标澄清」：用户选定的目标本身就是一条提示词。
            // 计划正文由引擎产出（不再由界面拼），所以这里只是把它发出去。
            ChoiceIntent.PLAN_GOAL -> {
                val goal = if (option.detail.isNotEmpty()) "${option.label} —— ${option.detail}" else option.label
                _state.update { it.copy(choicePicker = null, composerText = goal) }
                send()
            }
        }
    }

    /**
     * 选择窗口里的「其他回答…」提交：不走选项，直接把这段自由文本当作输入。
     * 在计划模式下它等价于"自定义目标"，会作为提示词发给引擎；
     * 在会话备注中它是备注正文；在重命名中它是新标题。
     */
    fun onSubmitFreeForm(text: String) {
        val trimmed = text.trim()
        val picker = _state.value.choicePicker
        if (picker == null) return
        // 备注可以提交空字符串（= 清除），所以不能和"空输入就关闭"混在一起；
        // 重命名反过来：空标题无效，直接提示而不是当作"恢复原名"。
        if (picker.intent == ChoiceIntent.SESSION_NOTE || picker.intent == ChoiceIntent.SESSION_RENAME) {
            val rename = picker.intent == ChoiceIntent.SESSION_RENAME
            val target = pendingSessionAction
            pendingSessionAction = null
            _state.update { it.copy(choicePicker = null) }
            when {
                target == null ->
                    _state.update { it.copy(message = if (rename) "找不到目标会话，标题未保存" else "找不到目标会话，备注未保存") }
                rename && trimmed.isEmpty() ->
                    _state.update { it.copy(message = "标题不能为空") }
                rename -> saveSessionTitle(target, trimmed)
                else -> saveSessionNote(target, trimmed)
            }
            return
        }
        if (trimmed.isEmpty()) {
            dismissChoicePicker()
            return
        }
        if (picker.intent == ChoiceIntent.PLAN_GOAL) {
            _state.update { it.copy(choicePicker = null, composerText = trimmed) }
            send()
            return
        }
        _state.update {
            it.copy(
                choicePicker = null,
                composerText = trimmed,
                slashQuery = null,
                slashMatches = emptyList(),
                message = null,
            )
        }
    }

    // ---------- 会话 ----------

    /**
     * 新建会话。
     *
     * 这里只重置引擎的对话上下文（新 workflowId），**不**立即建会话：
     * 引擎的 `SessionStore` 是在第一轮发消息时才创建的，提前建会留下
     * 一堆"0 条消息"的空会话，侧栏会很快被垃圾填满。
     * 所以界面上先插入一个占位条目，等第一轮结束后 [refreshSessions] 会用真实会话替换它。
     */
    fun newSession() {
        engine.resetConversation()
        discardStreamingAssistant()
        // 上一轮即使还在跑也已经不属于任何会话了，清掉归属标记避免误拦。
        turnSessionId = null
        currentGroupId = null
        liveTools.clear()
        progressBuffers.clear()
        progressLastFlush.clear()
        pendingPermissionId = null
        // 换了会话，上一轮的停止流程不再属于任何东西：留着它会让新会话
        // 一打开就显示"正在停止"，而且看门狗还会去问一个已经作废的回合。
        resetStopTracking()
        _state.update {
            it.copy(
                // 占位条目的 id 用空串而不是伪造 id：它不是真实文件，
                // 若被当成路径去打开会失败，空串能让"打不开"这件事显式化。
                activeSessionId = "",
                stopping = false,
                transcript = emptyList(),
                transcriptHasOlder = false,
                transcriptOldestSeq = -1L,
                transcriptLoadingOlder = false,
                transcriptPrependRevision = 0,
                transcriptHasNewer = false,
                transcriptJumpRevision = 0,
                tasks = emptyList(),
                contextTokens = 0,
                composerBusy = false,
                workingStatus = null,
                tab = WorkspaceTab.CHAT,
                sidebarOpen = false,
                message = "已开始新会话，第一条消息会创建会话记录",
            )
        }
    }

    /**
     * 打开一条历史会话。
     *
     * 两件事都要做，缺一不可：
     * 1. `engine.resumeConversation(sessionId)` —— 让**引擎**把历史装回 provider 上下文，
     *    否则界面上看得到历史、模型却不知道，追问会答非所问；
     * 2. 从同一个会话标识重建界面 transcript。
     *
     * 读取放 IO 线程：一次长会话可能有上千条消息，在主线程读会卡住界面。
     */
    fun openSession(session: SessionSummary) {
        if (session.id.isEmpty()) {
            _state.update { it.copy(message = "会话标识为空，无法打开", messageIsError = true) }
            refreshSessions()
            return
        }
        // 切会话必须先把引擎这一轮停掉：否则旧会话的回调还会继续往新的
        // transcript 里写（引擎的 cancel 会连带取消三个门控）。
        engine.cancel()
        discardStreamingAssistant()
        // 切走会话：上一轮的迟到事件一律不应再落到新会话里。
        // 注意这里**不要**把 turnSessionId 置 null —— 置空等于关掉拦截。
        // 保留了旧值，[finishTurnWithError] 才能看出"归属 ≠ 当前"并丢弃。
        currentGroupId = null
        liveTools.clear()
        progressBuffers.clear()
        progressLastFlush.clear()
        pendingPermissionId = null
        // 见 newSession()：换会话时停止流程一并作废。
        resetStopTracking()

        _state.update { it.copy(sidebarOpen = false, tab = WorkspaceTab.CHAT, workingStatus = "正在载入会话…") }
        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            // 只读**最近一页**（`SessionReader.PAGE_MESSAGES` 条）＋完整任务清单。
            // 早先这里是「一次读完整条会话」—— 一次长会话的内容块是几十 MB 量级，
            // 真机上"打开一万条的长会话直接崩"就是这一步。往前翻页见 loadOlderTranscript。
            val page = SessionReader.openPage(session.id)
            ZhiMemory.log(
                "openPage session=" + session.id +
                    " n=" + page.items.size +
                    " hasOlder=" + page.hasMore +
                    " ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt),
            )
            // 引擎必须先配置好才有 config 可用，否则 resumeConversation 不会恢复项目/工作流。
            val resumed = runCatching {
                engine.configure(engineOverrides())
                engine.resumeConversation(session.id)
            }
            val footprint = engine.memoryFootprint()
            ZhiMemory.log(
                "resumeConversation session=" + session.id +
                    " engineMsgs=" + footprint.first +
                    " historyChars=" + ZhiMemory.size(footprint.second) +
                    " ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt),
            )
            _state.update {
                it.copy(
                    activeSessionId = session.id,
                    transcript = page.items,
                    transcriptHasOlder = page.hasMore,
                    transcriptOldestSeq = page.oldestSeq,
                    transcriptLoadingOlder = false,
                    // 换了一条会话就重新计数：新会话的第一页不是"往前补"，
                    // 列表必须无条件吸底（见 UiModels 里的说明）。
                    transcriptPrependRevision = 0,
                    transcriptHasNewer = false,
                    transcriptJumpRevision = 0,
                    tasks = page.tasks,
                    contextTokens = engine.estimateContextTokens(),
                    contextWindow = engine.contextWindowTokens().takeIf { w -> w > 0 } ?: it.contextWindow,
                    stopping = false,
                    composerBusy = false,
                    workingStatus = null,
                    message = resumed.exceptionOrNull()?.let { error ->
                        "会话历史已显示，但引擎恢复失败：" + (error.message ?: "未知原因")
                    },
                )
            }
        }
    }

    /**
     * 往前补一页更早的历史（列表滚到顶时调用）。
     *
     * 三次进入都直接返回：正在加载、已经没有更早的、或者还没有打开会话。
     * 它们是幂等的守卫 —— 列表滚动会连着触发很多次，不能每次都读一遍库。
     */
    fun loadOlderTranscript() {
        val snapshot = _state.value
        val sessionId = snapshot.activeSessionId
        if (sessionId.isEmpty() || snapshot.transcriptLoadingOlder || !snapshot.transcriptHasOlder) return
        val before = snapshot.transcriptOldestSeq
        if (before <= 0L) {
            _state.update { it.copy(transcriptHasOlder = false) }
            return
        }
        _state.update { it.copy(transcriptLoadingOlder = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val page = SessionReader.olderPage(sessionId, before)
            _state.update { current ->
                // 读的过程中可能已经切走会话（或者又删了它）：那就把这一页丢掉，
                // 绝不能把上一个会话的历史接到新会话头上。
                if (current.activeSessionId != sessionId) {
                    current.copy(transcriptLoadingOlder = false)
                } else if (page == null || page.items.isEmpty()) {
                    current.copy(transcriptLoadingOlder = false, transcriptHasOlder = false)
                } else {
                    val merged = SessionReader.merge(page.items, current.transcript)
                    // 补页之后**必须**从尾部淘汰（见 trimResidentTail 的说明）：
                    // 只加不减的话，用户往上翻完整条会话就等于把整条会话又装回内存。
                    val kept = trimResidentTail(merged, current)
                    ZhiMemory.log(
                        "olderPage n=" + page.items.size +
                            " held=" + kept.items.size +
                            " trimmed=" + kept.trimmed +
                            " hasOlder=" + page.hasMore,
                    )
                    current.copy(
                        transcript = kept.items,
                        transcriptHasNewer = current.transcriptHasNewer || kept.trimmed,
                        transcriptHasOlder = page.hasMore,
                        transcriptOldestSeq = page.oldestSeq,
                        transcriptLoadingOlder = false,
                        // 让列表知道这次变长是**往前补**的（见 UiModels 里的说明）。
                        transcriptPrependRevision = current.transcriptPrependRevision + 1,
                    )
                }
            }
        }
    }

    /**
     * 回到最新：用**最新一页**替换常驻窗口，并把列表落到底。
     *
     * 这是"尾部被淘汰之后"唯一的回去路径（见 `UiModels.transcriptHasNewer`）。
     * 之所以是**替换**而不是"再补一页"：替换不会把同一条消息灌两遍 ——
     * 一轮回合结束后，同一条消息既在库里、也以实时条目留在内存里，
     * 按 seq 往回补就会重复。
     *
     * <p><b>但实时条目必须留下。</b>库里那一页是"已经落盘的消息"，而正在流式输出的
     * 气泡、正在跑的工具卡都还只在内存里。全部替换掉的话，用户点一下「回到最新」
     * 就会看见自己的提问和正在回复的内容**凭空消失**。所以按既有的 id 约定
     * （库读出来的 id 一律以 `session-` 开头，见 `SessionReader`）把实时条目摘出来，
     * 接在库里那一页**后面** —— 它们本来就是最新的。
     */
    fun jumpToLatest() {
        val sessionId = _state.value.activeSessionId
        if (sessionId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val page = SessionReader.openPage(sessionId)
            ZhiMemory.log("jumpToLatest session=" + sessionId + " n=" + page.items.size)
            _state.update { current ->
                if (current.activeSessionId != sessionId) current
                else current.copy(
                    transcript = page.items + current.transcript.filterNot { it.id.startsWith("session-") },
                    transcriptHasOlder = page.hasMore,
                    transcriptHasNewer = false,
                    transcriptOldestSeq = page.oldestSeq,
                    transcriptLoadingOlder = false,
                    // 计一次"往前补"，让列表**不要**为这次替换吸底 ——
                    // 落底由 jumpRevision 单独驱动（替换会丢掉当时的滚动位置，
                    // 必须显式落底，不能靠吸底那条路顺手带）。
                    transcriptPrependRevision = current.transcriptPrependRevision + 1,
                    transcriptJumpRevision = current.transcriptJumpRevision + 1,
                    tasks = page.tasks,
                )
            }
        }
    }

    /** [trimResidentTail] 的结果。 */
    private class Trimmed(val items: List<ChatItem>, val trimmed: Boolean)

    /**
     * 常驻条数的上限。
     *
     * <p>取值理由：一屏看得见几条，600 条已经是几十屏的翻页量；而每一条的常驻成本
     * 是正文 + 工具输出预览（正文不限、预览 8 KB），600 条量级在 10MB 以内 ——
     * 相对 512MB 的堆完全可以忽略。上限存在的意义不是"省这一点"，而是**保证它不随
     * 会话长度增长**。
     */
    private val maxResidentItems = 600

    /**
     * 超上限时从**尾部（最新那端）**淘汰。
     *
     * ## 为什么淘汰尾部而不是头部
     *
     * 用户会一路往上滚，所以被补进来的是头部；**离视口最远的那一端是尾部**。
     * 淘汰头部等于把刚读进来的东西立刻扔掉（徒劳），淘汰尾部才是"把看不见的放掉"。
     *
     * ## 为什么"有回合在跑 / 有弹窗"时不动
     *
     * 权限询问、计划审批、选项选择、以及正在跑的工具，都靠 `id` 在 transcript 里
     * 找回对应的那一条。淘汰掉那一条 = 弹窗点确认时找不到目标。所以这些状态下
     * **宁可暂时超过上限**（上限不是硬保证，是稳态目标）。
     */
    private fun trimResidentTail(items: List<ChatItem>, state: WorkspaceUiState): Trimmed {
        if (items.size <= maxResidentItems) return Trimmed(items, false)
        val busy = state.workingStatus != null || state.composerBusy ||
            state.permissionRequest != null || state.planApproval != null ||
            state.choicePicker != null || pendingPermissionId != null
        if (busy) return Trimmed(items, false)
        // ⚠️ 复制一份而不是 `subList` 返回的**视图**：视图会一直抓着它背后那个
        // 更长的数组，于是"淘汰掉"的那 200 条其实一条都没被释放 ——
        // 淘汰就白做了。600 个引用的复制本身可以忽略。
        return Trimmed(ArrayList(items.subList(0, maxResidentItems)), true)
    }

    /**
     * 删除会话。
     *
     * 删的是**存储里的会话**，不是列表里的一项 —— 只从列表移除的话，
     * 下次启动它又会回来，用户会以为删除没生效。
     */
    fun deleteSession(id: String) {
        if (id.isEmpty()) {
            // 尚未落库的占位会话：直接清掉当前视图即可。
            _state.update {
                it.copy(
                    activeSessionId = "",
                    transcript = emptyList(),
                    transcriptHasOlder = false,
                    transcriptOldestSeq = -1L,
                    transcriptLoadingOlder = false,
                    transcriptPrependRevision = 0,
                    transcriptHasNewer = false,
                    transcriptJumpRevision = 0,
                    tasks = emptyList(),
                    message = "会话已清空",
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val removed = SessionReader.delete(id)
            val wasActive = _state.value.activeSessionId == id
            if (wasActive) {
                engine.resetConversation()
                discardStreamingAssistant()
            }
            val sessions = SessionReader.list(_state.value.projectPath)
            val next = sessions.firstOrNull()
            _state.update {
                it.copy(
                    sessions = sessions,
                    activeSessionId = if (wasActive) "" else it.activeSessionId,
                    transcript = if (wasActive) emptyList() else it.transcript,
                    transcriptHasOlder = if (wasActive) false else it.transcriptHasOlder,
                    transcriptOldestSeq = if (wasActive) -1L else it.transcriptOldestSeq,
                    transcriptLoadingOlder = if (wasActive) false else it.transcriptLoadingOlder,
                    transcriptPrependRevision = if (wasActive) 0 else it.transcriptPrependRevision,
                    transcriptHasNewer = if (wasActive) false else it.transcriptHasNewer,
                    transcriptJumpRevision = if (wasActive) 0 else it.transcriptJumpRevision,
                    tasks = if (wasActive) emptyList() else it.tasks,
                    message = if (removed) "会话已删除" else "删除失败：会话可能已被移除",
                )
            }
            // 删除后仍留历史时，顺手把最近一条打开，避免出现"删完一片空白"。
            if (wasActive && next != null) openSession(next)
        }
    }

    /** 从磁盘重新读取会话列表（新建/发消息/删除之后调用）。 */
    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val projectAtStart = _state.value.projectPath
            val sessions = SessionReader.list(projectAtStart)
            _state.update { state ->
                if (state.projectPath != projectAtStart) return@update state
                // 保留"尚未落库的新会话"这一状态：此刻 activeSessionId 为空且
                // 列表里还没有对应会话，不能被列表刷新覆盖掉。
                state.copy(sessions = sessions)
            }
        }
    }



    fun openSidebar() {
        // 每次拉开侧栏都重读一次会话列表：新会话是在**第一轮消息之后**才落库的，
        // 不刷新的话"刚聊完的会话"在侧栏里看不到。
        refreshSessions()
        maybeRunStorageMaintenance()
        _state.update { it.copy(sidebarOpen = true) }
    }

    /** 手动维护入口（设置/调试页用）。 */
    fun maintainStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = SessionReader.maintain()
            _state.update { it.copy(message = result, messageIsError = result.startsWith("维护失败")) }
        }
    }

    /** 读取真实的存储占用报告（设置/调试页用）。 */
    fun storageReport() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = SessionReader.storageReport()
            _state.update { it.copy(message = report, messageIsError = report.startsWith("存储信息读不出来")) }
        }
    }

    /** 把当前会话导出到系统文件选择器交给的 Uri。 */
    fun exportSessionTo(uri: Uri) {
        val sessionId = _state.value.activeSessionId
        if (sessionId.isEmpty()) {
            _state.update { it.copy(message = "没有打开的会话", messageIsError = true) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val temp = File(cacheDir(), SessionStore.suggestedArchiveName(sessionId))
                SessionStore.exportSession(sessionId, temp)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法打开目标文件")
                temp.delete()
                "会话已导出"
            }.getOrElse { "导出失败：" + (it.message ?: "未知原因") }
            _state.update { it.copy(message = result, messageIsError = result.startsWith("导出失败")) }
        }
    }

    /** 从系统文件选择器得到的 Uri 导入一条新会话。 */
    fun importSessionFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val temp = File(cacheDir(), "import-" + System.currentTimeMillis() + ".jsonl.gz")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取归档")
                val id = SessionStore.importSession(temp)
                temp.delete()
                refreshSessions()
                "已导入会话：" + id.take(8)
            }.getOrElse { "导入失败：" + (it.message ?: "未知原因") }
            _state.update { it.copy(message = result, messageIsError = result.startsWith("导入失败")) }
        }
    }

    private fun cacheDir(): File = getApplication<Application>().cacheDir
    fun closeSidebar() = _state.update { it.copy(sidebarOpen = false) }

    /**
     * 侧栏里的每一行在打开目标页面之前都要调这个。
     *
     * ## 为什么它值得一个专门的函数
     *
     * 侧栏是画在内容**上层**的浮层（见 `AppScaffold` 的 Sidebar 分支），
     * 而各个目标的 `openXxx()` 只负责把自己那面页推上来，谁也没管侧栏。
     * 于是：点「设置」→ 设置页铺满，但**侧栏还盖在上面**；点「环境」同理；
     * 点「ZhiCode 沙箱」更明显（那是个跨进程的 Activity）。
     * 用户看到的是一层"点不动"的界面，得先返回一次才回到目标页 ——
     * 症状是"点了没反应"，根因只是少关一层。
     *
     * 唯一例外是「新会话」：它本来就自己设了 `sidebarOpen = false`（见 [newSession]）。
     *
     * ## 为什么不写成"每个 openXxx 里各加一行"
     *
     * 那正是它坏掉的原因：四个入口各写一遍，加第五个时必然漏。
     * 收口在这里之后，新增入口只有一条路可走。
     */
    private fun hideSidebarForNavigation() {
        _state.update { it.copy(sidebarOpen = false) }
    }


    // ---------- 工作区 ----------

    /**
     * 新 Activity 从启动器打开时回到对话页。
     *
     * 工作区页签不是会话内容，不应被旧 Activity/系统恢复成文件页；运行中的用户主动
     * 切页仍由 [selectTab] 保留，不在每次 onResume 时强行打断。
     */
    fun resetTabForLaunch() {
        _state.update {
            it.copy(
                tab = WorkspaceTab.CHAT,
                fileSelection = emptySet(),
                fileSelectionMode = false,
            )
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        _state.update {
            it.copy(
                tab = tab,
                fileSelection = if (tab == WorkspaceTab.FILES) it.fileSelection else emptySet(),
                fileSelectionMode = if (tab == WorkspaceTab.FILES) it.fileSelectionMode else false,
            )
        }
    }

    fun cycleThemeMode() {
        var next = ThemeMode.SYSTEM
        _state.update {
            next = when (it.themeMode) {
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
            }
            it.copy(themeMode = next, message = "主题：${next.label}")
        }
        persistTheme(next)
    }

    // ---------- 内置 Termux 运行环境 ----------

    /**
     * 追加「内置环境版本」一节到自检报告。
     *
     * 为什么要单独列出来：以前换 APK 之后 `usr` 会悄悄停在旧的那份，界面上完全看不出来
     * （`isInstalled()` 只查文件在不在）。现在自检报告里能直接读到"已装哪版 / 内置哪版"，
     * 不一致时还给出说明 —— 排障时一眼能定位。
     */
    private fun bootstrapVersionSection(): String {
        val installed = runCatching { installer.installedVersion() }.getOrNull()
        val bundled = runCatching { installer.bundledVersion() }.getOrNull() ?: return ""
        val same = runCatching { installer.isUpToDate() }.getOrDefault(false)
        return buildString {
            append("\n\n## 内置环境版本\n")
            append("- 已装：").append(installed ?: "（无标记文件，视为未初始化）").append('\n')
            append("- 内置：").append(bundled).append('\n')
            append(
                if (same) "- 状态：一致 ✅"
                else "- 状态：**不一致** — 启动时会自动重装 `usr`（`home` 不受影响，\n" +
                    "  但用 apt 装过的包会丢，因为它们在 `usr` 里）"
            )
        }
    }

    /**
     * 生成自检报告。
     *
     * 刻意**绝不抛异常**：这是排障入口，它自己崩掉或什么都不显示，就等于把唯一的诊断手段也弄没了。
     * 探测失败时把失败原因当作报告正文显示出来，至少还能看到是哪一项炸的。
     */
    private fun buildEnvironmentReport(): String = runCatching {
        EnvDoctor.report(getApplication()) + bootstrapVersionSection()
    }.getOrElse { error ->
        "# ZhiCode · 环境自检\n\n" +
            "自检本身失败了：\n" +
            "${error.javaClass.name}: ${error.message}\n\n" +
            "堆栈：\n" +
            error.stackTraceToString() +
            "\n— 报告结束，可整段复制"
    }

    /** 打开「环境自检」：现场跑一遍探测并生成可复制的报告。 */
    fun openEnvironment() {
        // 见 hideSidebarForNavigation：侧栏里点进来的行必须先收起侧栏。
        hideSidebarForNavigation()
        val reportText = buildEnvironmentReport()
        _state.update {
            it.copy(
                environmentOpen = true,
                environmentReport = reportText,
                runtimeReady = runCatching { installer.isInstalled() }.getOrDefault(false),
            )
        }
    }

    fun closeEnvironment() = _state.update { it.copy(environmentOpen = false) }

    /** 重新生成自检报告（安装前后各跑一次，便于对比）。 */
    fun refreshEnvironmentReport() {
        val reportText = buildEnvironmentReport()
        _state.update {
            it.copy(
                environmentReport = reportText,
                runtimeReady = runCatching { installer.isInstalled() }.getOrDefault(false),
            )
        }
    }

    /**
     * 安装（或重新初始化）内置 Termux 环境。
     *
     * 解压 32MB、落 3473 个文件、建 1177 个符号链接，必须放到 IO 线程，
     * 并且把进度实时回灌给 UI —— 整个过程在真机上要十几秒，不给反馈会被当成卡死。
     */
    fun installRuntime() {
        if (_state.value.runtimeInstalling) return
        _state.update {
            it.copy(runtimeInstalling = true, runtimeProgress = 0, runtimeMessage = "正在准备…")
        }
        viewModelScope.launch(Dispatchers.IO) { installRuntimeBlocking("正在准备…") }
    }

    /**
     * 真正干活的那一段。**必须在 IO 线程调用**（解压 33MB、建 2689 个符号链接）。
     *
     * 抽出来是因为有两处入口：用户手动点「初始化」（[installRuntime]）与启动时的
     * 版本过期自动重装（[autoInstallRuntimeIfStale]）。两处必须走同一段逻辑 ——
     * 分别写一份必然走样（一处忘了 `ensureWorkspace()`，另一处忘了更新报告）。
     */
    private suspend fun installRuntimeBlocking(preparing: String) {
        // 装之前必须先关终端：install() 的 activatePrefix() 会把整个 usr 目录 rename 换掉，
        // 正在跑的 bash 会立刻指向一个已不存在的前缀（`/bin/bash` 还在内存里，
        // 但它 fork 出的任何东西都会失败）。切换要在主线程做，所以这里显式切过去等它。
        withContext(Dispatchers.Main) { releaseTerminalPane() }

        _state.update { it.copy(runtimeMessage = preparing) }
        val failure = installRuntimeReporting()
        if (failure != null) {
            appendRuntimeLog("安装失败：$failure")
            _state.update { it.copy(runtimeInstalling = false, runtimeMessage = "初始化失败：$failure") }
            return
        }
        appendRuntimeLog("安装完成：已装=${runCatching { installer.installedVersion() }.getOrNull() ?: "(无)"}")
        val reportText = buildEnvironmentReport()
        _state.update {
            it.copy(
                runtimeInstalling = false,
                runtimeProgress = 100,
                runtimeMessage = "内置 Termux 环境已就绪",
                runtimeReady = installer.isInstalled(),
                environmentReport = reportText,
            )
        }
        // home 目录是随运行环境一起出现的，所以工作区只能在这之后创建。
        // 不创建的话：文件面板永远显示"目录不存在"，
        // 终端的工作目录会回退到 home，会话也会落在一个不存在的项目键下。
        ensureWorkspace()
    }

    /**
     * 跑一次安装，失败时返回一句人话原因（成功返回 `null`）。
     *
     * 把"失败原因"作为返回值而不是就地写状态：调用方还需要把同一句话写进日志，
     * 两处分别拼一遍字符串必然走样。
     */
    private suspend fun installRuntimeReporting(): String? = runCatching {
        installer.install { message, percent ->
            _state.update { it.copy(runtimeMessage = message, runtimeProgress = percent) }
        }
    }.exceptionOrNull()?.let { error ->
        error.message ?: error.javaClass.simpleName
    }

    /** 幂等修复：清半成品目录、补齐 apt/dpkg 兼容层。适用于 apt 升级过或上次中断。 */
    fun repairRuntime() {
        if (_state.value.runtimeInstalling) return
        _state.update { it.copy(runtimeInstalling = true, runtimeMessage = "正在修复…") }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { installer.repairIfInstalled() }.isSuccess
            val reportText = buildEnvironmentReport()
            _state.update {
                it.copy(
                    runtimeInstalling = false,
                    runtimeMessage = if (ok) "已修复" else "修复失败",
                    runtimeReady = installer.isInstalled(),
                    environmentReport = reportText,
                )
            }
        }
    }

    /** 复制自检报告到剪贴板，便于把真机现状整段发出来定位问题。 */
    fun copyEnvironmentReport(): Boolean {
        val app = getApplication<Application>()
        val text = _state.value.environmentReport.ifBlank { buildEnvironmentReport() }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("ZhiCode 环境自检", text))
        _state.update { it.copy(message = "环境自检报告已复制") }
        return true
    }

    // ---------- 设置 ----------

    /**
     * 打开设置弹窗：从当前 state 快照一份 draft。
     *
     * 之后的编辑全部落在 draft 上，「取消」丢弃 draft 即可，不需要回滚任何东西。
     */
    fun openSettings() {
        _state.update {
            it.copy(
                settingsOpen = true,
                // 见 hideSidebarForNavigation：点侧栏里的入口要先收起侧栏。
                sidebarOpen = false,
                settingsDraft = SettingsDraft.from(it),
            )
        }
        refreshExtensions()
    }

    /**
     * 统计「扩展」组要显示的数量（MCP / 技能 / 角色卡）。
     *
     * 三个存储都在磁盘上：`mcp.json` 解析、技能目录 walk、角色卡列表读。
     * 所以**只能在 IO 线程读一次**，不能在设置页里读（那会变成"每次重组读一次盘"）。
     * 打开设置时算一次、从扩展页返回时再算一次就够 —— 这些数量不会在页面停留期间变。
     */
    fun refreshExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            val projectPath = _state.value.projectPath
            val summary = ExtensionSummary(
                mcpTotal = runCatching { McpStore.read().servers.size }.getOrDefault(0),
                mcpEnabled = runCatching { McpStore.read().servers.count { it.enabled } }.getOrDefault(0),
                skillTotal = runCatching { SkillStore.list(projectPath).size }.getOrDefault(0),
                projectSkills = runCatching {
                    SkillStore.list(projectPath).count { it.scope == com.zhizhu.zhicode.compose.model.SkillScope.PROJECT }
                }.getOrDefault(0),
                userSkills = runCatching {
                    SkillStore.list(projectPath).count { it.scope == com.zhizhu.zhicode.compose.model.SkillScope.USER }
                }.getOrDefault(0),
                roleCardTotal = runCatching {
                    RoleCardStore.list(getApplication()).size
                }.getOrDefault(0),
                loaded = true,
            )
            _state.update { state ->
                val connected = state.mcpConfig?.status?.values?.count { it.connected } ?: 0
                state.copy(extensions = summary.copy(mcpConnected = connected))
            }
        }
    }

    /**
     * 设置落盘的去抖窗口。
     *
     * 400ms 是"打完最后一个字到写入"的延迟上限：短到用户察觉不到，
     * 长到足以把连续输入合并成一次写。取值与 Miuix / Material 的
     * `debounce` 惯例同档（300~500ms）。
     */
    private val SettingsPersistDebounceMs = 400L

    fun closeSettings() {
        // 关页面前把去抖窗口里那次改动兑现：用户"在提示词里打完字就走"时，
        // 那一次改动还没到 [SettingsPersistDebounceMs] 就会被丢掉。
        flushSettings()
        _state.update { it.copy(settingsDraft = null, settingsOpen = false) }
    }

    /**
     * 打开「UI 调试」整页（**仅 debug 构建**）。
     *
     * 刻意不动 [openSettings] 的 draft：从设置页进来时它已经垫在栈底，
     * 返回时 draft 原样还在，所以调试完回到设置页不会丢未保存的改动。
     */
    fun openUiDebug() {
        // 见 hideSidebarForNavigation。
        hideSidebarForNavigation()
        _state.update { it.copy(uiDebugOpen = true) }
    }

    /** 关闭 UI 调试页。若它是由设置页打开的，设置主页会随之露出来（栈自动回退一层）。 */
    fun closeUiDebug() = _state.update { it.copy(uiDebugOpen = false) }

    /**
     * 重置 UI 调试页的样例数据。
     *
     * 只递增令牌：页面用 `remember(token)` 重建自己那份样例（含秒表状态）。
     * 样例数据是纯调试物，不进持久化状态，也不该让 ViewModel 认识它的结构。
     */
    fun requestUiDebugReset() = _state.update { it.copy(uiDebugResetToken = it.uiDebugResetToken + 1) }

    /**
     * 开关**全局调试浮层**。
     *
     * 打开时默认**展开**（用户按下开关就是要看东西，先给他面板），
     * 关闭时把展开态一起复位，下次打开仍是展开 —— 免得留下"上次收起"的隐式记忆。
     */
    fun setDebugOverlayEnabled(enabled: Boolean) = _state.update {
        it.copy(debugOverlayEnabled = enabled, debugOverlayExpanded = enabled)
    }

    /** 展开 / 收起调试浮层（收起态是贴边的窄药丸）。 */
    fun setDebugOverlayExpanded(expanded: Boolean) = _state.update {
        it.copy(debugOverlayExpanded = expanded)
    }

    /**
     * 开关**主体调试模式**：真实界面就地显示调试信息（不另开页面）。
     *
     * 同时把当前 API 配置切到「调试 · 本地模拟」**并记住原来那条**：
     * 这条配置的协议是脚本化传输（不发网络、按脚本产回复、工具照常真实执行），
     * 于是"发一句话就能走完整条对话流"——包括流式正文、思考、工具行、
     * 权限确认、计划审批、任务卡。关掉时切回原来那条。
     *
     * 之所以要真的切配置而不是在引擎里加开关：引擎读的就是"当前生效的那条配置"，
     * 走真实链路才能保证调试出来的行为与真正用起来一致（见 [DebugApiProfile]）。
     */
    fun setDebugAppMode(enabled: Boolean) {
        // 双保险：开关本身在 debug 独有页面里，但这里再挡一道 ——
        // 发布包绝不该因为某处误接线而切到脚本化传输。
        if (!com.zhizhu.zhicode.compose.BuildConfig.DEBUG) return
        if (!enabled) {
            val previous = _state.value.debugPreviousProfileId
            _state.update { it.copy(debugAppMode = false, debugPreviousProfileId = "") }
            val restored = if (previous.isBlank()) {
                Result.failure(IllegalStateException("没有记录到原来的配置"))
            } else {
                DebugApiProfile.restore(getApplication(), previous)
            }
            syncActiveProfile()
            _state.update {
                it.copy(
                    message = if (restored.isSuccess) "已退出调试模式，API 配置还原为「${it.profileName}」"
                    else "已退出调试模式，但没能切回原配置（${restored.exceptionOrNull()?.message ?: "未知原因"}）；" +
                        "请在「API 配置记录」里手动选一条",
                )
            }
            return
        }
        // 先记下当前生效的那条，再切走 —— 顺序反了会记成调试配置自己。
        val previous = runCatching { ApiConfigStore.active(getApplication())?.profileId }.getOrNull().orEmpty()
        val activated = DebugApiProfile.activate(getApplication())
        _state.update {
            it.copy(
                debugAppMode = true,
                debugPreviousProfileId = if (activated.isSuccess) previous else "",
            )
        }
        syncActiveProfile()
        _state.update {
            it.copy(
                message = if (activated.isSuccess) {
                    "调试模式已开启：当前 API 切到「${DebugApiProfile.NAME}」，发消息不会出网，" +
                        "工具仍按真实链路执行"
                } else {
                    "调试模式已开启，但没能写入调试 API 配置（${activated.exceptionOrNull()?.message ?: "未知原因"}）；" +
                        "请到「API 配置记录」手动新增一条，协议选「调试 · 本地模拟（无网络）」"
                },
            )
        }
    }

    /**
     * 设置页的**唯一**改动入口：既更新 draft（页面显示的来源），又立刻写回 state。
     *
     * 设置页改成自动保存后就不再需要「保存 / 取消」两个按钮：改动即时生效，
     * 返回只是关页面。draft 仍然保留 —— 页面用它做显示源，
     * 某些字段（如项目目录留空）的「未改动」语义也靠它。
     *
     * ⚠️ 这里**必须**走 [SettingsDraft.applyTo] 而不是各自 `copy`：`applyTo` 里有
     * `clampWebResults` / `projectPath.ifBlank` 这些归一化，绕过它就等于把用户
     * 原始输入直接塞进引擎配置。
     */
    fun applySettingsDraft(draft: SettingsDraft) {
        val previous = _state.value.settingsDraft
        _state.update { draft.applyTo(it, keepDraft = true) }
        // 离散改动（开关 / 下拉 / 步进）立刻落盘；只有**自由文本**才去抖。
        //
        // ⚠️ 去抖这一条是必须的，而且是在修一个我自己引入的卡顿：
        // `SettingsTextField` 的 `onValueChange` 是**逐字符**触发的，而落盘一次要
        // `load()` 整表 + 序列化配置 JSON + 走密钥库 + 写 prefs。不去抖的话，
        // 在「自定义头部提示词」里打一句话就是几十次全量落盘 ——
        // 用户的原话正是「卡顿更加多了」。
        val typingOnly = previous != null && differsOnlyInFreeText(previous, draft)
        scheduleSettingsPersist(debounce = typingOnly)
    }

    /**
     * 两次草稿是否**只**在自由文本字段上不同。
     *
     * 只有这种情况才值得去抖：其余字段都是一次性动作（点开关、选下拉），
     * 改动即最终值，没有"还在输入中"的中间态，也就没有理由延后落盘。
     *
     * 名单要跟着设置页的自由文本输入框走（`SettingsTextField` / `SettingsIntField`
     * 的那几处）：提示词、项目目录，以及支持 `1.5m` 这种写法的数字框 —— 它们也是文本输入。
     */
    private fun differsOnlyInFreeText(a: SettingsDraft, b: SettingsDraft): Boolean =
        a.copy(
            customSystemPrompt = b.customSystemPrompt,
            projectPath = b.projectPath,
            contextWindow = b.contextWindow,
            webSearchMaxResults = b.webSearchMaxResults,
            webSearchTimeoutSec = b.webSearchTimeoutSec,
            autoCompactPercent = b.autoCompactPercent,
        ) == b

    /**
     * 安排一次设置落盘。
     *
     * [debounce] 为真时等 [SettingsPersistDebounceMs] 再写（输入中）；为假时立刻写。
     * 无论哪条路径，最终都走 [persistSettingsNow]，而它是**同步落盘**的。
     */
    private fun scheduleSettingsPersist(debounce: Boolean) {
        settingsPersistJob?.cancel()
        if (!debounce) {
            settingsPersistJob = null
            persistSettingsNow()
            return
        }
        settingsPersistJob = viewModelScope.launch {
            delay(SettingsPersistDebounceMs)
            persistSettingsNow()
        }
    }

    /**
     * 立刻兑现去抖窗口里那次设置改动（不等剩下那几百毫秒）。
     *
     * 两个调用点，缺一都会让"改完就走"丢改动：
     * - [closeSettings]：用户关掉设置页；
     * - `MainActivity.onStop`：应用退到后台（包括「直接大退」前那一下）。
     *
     * 没有待兑现的改动时**什么都不做**：`onStop` 每次都会调它，
     * 不能让它变成一个"每次切后台都全量写盘"的热路径。
     */
    fun flushSettings() {
        if (settingsPersistJob == null) return
        settingsPersistJob?.cancel()
        settingsPersistJob = null
        persistSettingsNow()
    }

    /**
     * 把当前生效的设置写回磁盘。
     *
     * ## 为什么必须**同步**落盘（`saveDurable`）
     *
     * `SharedPreferences.apply()` 只保证内存可见，磁盘写是异步的；从最近任务里
     * 划掉应用时进程被杀，**没人等那个后台写** —— 这就是
     * 「直接大退软件，这些保存恢复到未修改的样子」。本类此前 14 处写入全是
     * `apply()`、一处 `commit()` 都没有，所以补上"调用点"并不够（上一轮就只补了
     * 调用点，于是设置照丢）。用户设置一律走 `commit()`。
     *
     * ## 为什么复用 `engineOverrides()`
     *
     * 落盘那份必须与**发送时实际生效**的那份逐字段相同，否则会出现
     * "界面上是 A、发出去的也是 A、但重启回来变成 B"。两者共用
     * [applyEngineOverrides] 同一段映射（见其注释），从根上排除漂移。
     *
     * ## 三条刻意的约束
     *
     * 1. **先 `store.load()` 再改**，不凭空 `SessionConfig()`：`save()` 是整表覆盖，
     *    凭空构造会把 API 密钥槽这类本函数不管的字段一起清空。
     * 2. **不调 `engine.configure()`**：那样会顺带创建引擎实例，而这里只想要落盘；
     *    引擎实例的创建目前是单线程假设（`engine()` 非同步），从 IO 线程碰它有竞争风险。
     *    下一次发消息时 `configure()` 自会把新值推给引擎。
     * 3. **在 IO 线程上**：`commit()` 会阻塞到磁盘写完，压在点击那一帧上就是一次卡顿。
     */
    private fun persistSettingsNow() {
        val snapshot = _state.value
        val overrides = engineOverrides()
        val draft = SettingsDraft.from(snapshot)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val store = ApiSettingsStore(getApplication())
                val config = store.load()
                applyEngineOverrides(config, overrides)
                store.saveDurable(config)
            }
            // 主题与搜索密钥**不在** SessionConfig 里，configure() 那次落盘罩不到它们，
            // 各自补一次（密钥走加密槽，见 ApiSettingsStore.setWebSearchKey）。
            persistTheme(draft.themeMode)
            persistWebSearchKey(draft)
            // 终端输入类型也不在 SessionConfig 里（终端是独立 Activity，不读会话配置）。
            runCatching {
                ApiSettingsStore.setTerminalCharMode(getApplication(), draft.terminalCharMode)
            }
        }
    }

    /**
     * 把主题写回磁盘。
     *
     * 主题是纯界面概念，但它「选完重启就丢」与设置不落盘是同一件事 ——
     * 用户的感受是「这个软件的设置根本记不住」。
     */
    private fun persistTheme(mode: ThemeMode) {
        runCatching { ApiSettingsStore.setThemeMode(getApplication(), mode.name.lowercase()) }
    }

    /** 持久化**当前选中服务**的密钥（界面只可能改这一个）。 */
    private fun persistWebSearchKey(draft: SettingsDraft) {
        val provider = draft.webSearchProvider
        if (!provider.needsKey) return
        val key = draft.webSearchKeys[provider].orEmpty()
        runCatching { ApiSettingsStore.setWebSearchKey(getApplication(), provider.name.lowercase(), key) }
    }

    /**
     * 分类页里的「入口类」条目：先关掉设置弹窗，再把对应目标打开。
     *
     * 这里只接**真的有实现**的目标。原来还给 `canvas` / `other` 留了分支，
     * 但它们分别指向"未移植的运行时画布"和"自定义头部提示词的第二个入口"，
     * 现在画布入口已撤、提示词并入「上下文与项目」，两条分支一并删掉——
     * 留着只会让后来的人以为这里有功能。
     */
    fun navigateFromSettings(target: String) {
        // rikkahub 式导航：hub 保留在下层，子页盖上来；返回回 hub 时 draft 原样。
        // 「保存」只在 hub 顶栏点（draft 语义不变）。
        when (target) {
            "apiProfiles" -> openApiConfig()
            "mcp" -> openMcpConfig()
            "skills" -> openSkills()
            "searchServices" -> openSearchServices()
            "roleCards" -> openRoleCards()
            "memory" -> openMemory()
            "uiDebug" -> openUiDebug()
            else -> Unit
        }
    }

    // ---------- 对话流交互 ----------

    fun toggleToolExpanded(id: String) {
        // 先决定"要展开还是收起"：只有从收起变展开、且这条带的是截断预览时才需要取全文。
        val willExpand = _state.value.transcript
            .firstOrNull { it.kind == ChatKind.TOOL_GROUP }
            ?.tools?.firstOrNull { it.id == id }
            ?.expanded != true
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.kind != ChatKind.TOOL_GROUP) item
                    else item.copy(
                        tools = item.tools.map { if (it.id == id) it.copy(expanded = !it.expanded) else it },
                    )
                },
            )
        }
        if (willExpand) loadFullToolOutput(id)
    }

    /**
     * 展开一条工具时把**完整输出**取回来。
     *
     * <p>列表里那份是截断过的预览（见 `SessionReader.OUTPUT_PREVIEW_CHARS`）——
     * 一条会话里几百条工具的完整输出全部常驻内存，正是打开长会话 OOM 的主因。
     * 所以全文按需取：命中 `content_blocks(session_id, tool_use_id)` 索引只读一行，
     * 取回来后替换掉预览（`outputTruncated` 置回 false，之后不会再重复取）。
     *
     * <p>取失败（会话已删、记录里本来就只有预览）时什么都不做：保持预览，
     * 而不是把输出清空 —— 后者看起来像"这条工具没有输出"。
     */
    private fun loadFullToolOutput(toolUseId: String) {
        if (toolUseId.isEmpty()) return
        val sessionId = _state.value.activeSessionId
        if (sessionId.isEmpty()) return
        val already = _state.value.transcript
            .firstOrNull { it.kind == ChatKind.TOOL_GROUP }
            ?.tools?.firstOrNull { it.id == toolUseId }
        if (already == null || !already.outputTruncated) return

        viewModelScope.launch(Dispatchers.IO) {
            val full = SessionStore.readToolResult(sessionId, toolUseId)
            if (full.isEmpty()) return@launch
            _state.update { s ->
                s.copy(
                    transcript = s.transcript.map { item ->
                        if (item.kind != ChatKind.TOOL_GROUP) item
                        else item.copy(
                            tools = item.tools.map { tool ->
                                if (tool.id != toolUseId) tool
                                else tool.copy(output = full, outputTruncated = false)
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * 把某一条工具的展开状态设成**指定值**（不是取反）。
     *
     * 工具菜单里给的是"展开输出 / 折叠输出"这种**目标状态**的文案，所以必须能直接设值：
     * 用取反的话，用户点"展开输出"时如果状态在这期间已经变了（比如刚跑完自动展开），
     * 就会得到与文案相反的结果。
     *
     * 按 `(itemId, toolId)` 定位而不是只按 toolId：调用方手上就有这两个值，
     * 而只按 toolId 遍历时，同一 id 若出现在多条条目里会一起被改。
     */
    private fun setToolExpanded(itemId: String, toolId: String, expanded: Boolean) {
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.id != itemId || item.kind != ChatKind.TOOL_GROUP) item
                    else item.copy(
                        tools = item.tools.map { if (it.id == toolId) it.copy(expanded = expanded) else it },
                    )
                },
            )
        }
        // 与 toggleToolExpanded 同一条规则：展开时按需取全文。
        if (expanded) loadFullToolOutput(toolId)
    }

    /**
     * 展开/收起一个**折叠组**。
     *
     * @param id       工具批次那一条消息的 id
     * @param groupKey 组键 = 组内首成员的 toolId（见 [ToolGrouping.Segment.Group.key]）
     *
     * 记的是 [ChatItem.expandedGroups] 这份集合，而**不是**把组里每个成员的
     * `expanded` 置真/置假 —— 成员的 `expanded` 是"这条工具的输出展开"，
     * 两件事共用一个标志位时，点开一条工具的输出会连带把整组摊开。
     *
     * 组键由界面按 toolId 推导后传进来，所以这一层不需要知道"哪几条属于哪一组"。
     */
    fun toggleGroupExpanded(id: String, groupKey: String) {
        if (groupKey.isEmpty()) return
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { item ->
                    if (item.id != id || item.kind != ChatKind.TOOL_GROUP) {
                        item
                    } else {
                        val open = item.expandedGroups
                        item.copy(
                            expandedGroups = if (groupKey in open) open - groupKey else open + groupKey,
                        )
                    }
                },
            )
        }
    }

    fun toggleThinking(id: String) {
        _state.update { s ->
            s.copy(
                transcript = s.transcript.map { if (it.id == id) it.copy(thinkingExpanded = !it.thinkingExpanded) else it },
            )
        }
    }

    // ---------- 文件 ----------

    fun openDirectory(entry: FileEntry) {
        navigateTo(entry.path)
    }

    /** 刷新文件面板。Agent 改动文件后用它重新列目录。 */
    fun refreshFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            reloadFiles()
            _state.update { it.copy(message = "已刷新文件列表") }
        }
    }

    fun navigateTo(path: String) {
        // 列目录要读盘，放 IO 线程：大目录（例如 node_modules）在主线程列会卡住界面。
        //
        // ⚠️ 同时清掉选择：`fileSelection` 存的是**路径**，跨目录之后那些路径
        // 指向的是另一个目录里的东西。留着的话，在 A 目录选中的文件会在 B 目录里
        // 被"删除/附加" —— 而那是另外一批文件。
        _state.update { it.copy(filePath = path, pendingEditorOpen = null, fileSelection = emptySet(), fileSelectionMode = false, fileListOptions = FileListOptions()) }
        scheduleFileReload()
    }

    fun navigateUp() {
        val current = _state.value.filePath
        val root = rootPath()
        if (current == root) return
        val parent = current.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent.length < root.length) {
            // 已经到根（或再往上会越过根）：回到根，而不是继续往外走。
            navigateTo(root)
            return
        }
        navigateTo(parent)
    }

    /**
     * 点开一条列表项：目录就进去，文件就交给**编辑器那个独立 Activity**。
     *
     * 这里只往状态里放一个「待打开路径」，真正 `startActivity` 的是界面层
     * （`ZhiCodeScreen` 里那个 `rememberLauncherForActivityResult`）：
     * ViewModel 不该拿 Context 去启动界面，而且启动结果要回调到界面上
     * 才能决定“回来了要不要刷新文件列表”。
     */
    fun openFile(entry: FileEntry) {
        if (entry.directory) {
            openDirectory(entry)
            return
        }
        requestOpenEditor(entry.path)
    }

    /** 请求把某个文件交给编辑器打开（新建文件后立即打开也走它）。 */
    fun requestOpenEditor(path: String) = _state.update { it.copy(pendingEditorOpen = path) }

    /** 界面已经拿着这个路径去启动编辑器了，清掉标志，避免重组时重复启动。 */
    fun consumePendingEditorOpen() = _state.update { it.copy(pendingEditorOpen = null) }

    /** 文件面板的根路径（内置 Termux home 不存在时回退到应用私有目录）。 */
    /**
     * 文件面板当前的根，取决于用户在界面上选的 [FileRoot]。
     *
     * 原先这里恒等于 `projectPath`，于是面板被关在项目里 —— HOME 与共享存储
     * 都走不到（面包屑点不出去，「上一级」也会被弹回来）。
     */
    private fun rootPath(): String = when (_state.value.fileRoot) {
        FileRoot.HOME -> TermuxConstants.TERMUX_HOME_DIR_PATH
        FileRoot.SHARED -> StorageLinks.EXTERNAL_ROOT
    }

    /** 切换文件面板的根。切过去时**回到该根的顶层**，而不是停在别的根里的路径上。 */
    fun switchFileRoot(root: FileRoot) {
        _state.update {
            it.copy(
                fileRoot = root,
                filePath = when (root) {
                    FileRoot.HOME -> TermuxConstants.TERMUX_HOME_DIR_PATH
                    FileRoot.SHARED -> StorageLinks.EXTERNAL_ROOT
                },
                pendingEditorOpen = null,
                fileNameForm = null,
                fileDeletePrompt = null,
                // 同 navigateTo：跨根之后旧路径完全没有意义。
                fileSelection = emptySet(),
                fileSelectionMode = false,
                // ⚠️ 必须清空：不清的话，换根那次淡变的**进场**那一屏画的还是上一个根的文件
                // （列表要等 reloadFiles 从 IO 回来才换），于是动画看起来像"闪了一下旧内容"。
                // 清掉之后进场是干净的，本地列目录是毫秒级，用户看不到中间态。
                fileEntries = emptyList(),
                fileNote = "",
                fileListOptions = FileListOptions(),
            )
        }
        scheduleFileReload()
    }

    private fun scheduleFileReload(debounceMs: Long = 0L) {
        fileReloadJob?.cancel()
        fileReloadJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounceMs > 0) delay(debounceMs)
            reloadFiles()
        }
    }

    fun updateFileQuery(query: String) {
        _state.update { it.copy(fileListOptions = it.fileListOptions.copy(query = query)) }
        scheduleFileReload(debounceMs = 180L)
    }

    fun updateFileSort(key: FileSortKey, descending: Boolean = _state.value.fileListOptions.descending) {
        _state.update { it.copy(fileListOptions = it.fileListOptions.copy(sortKey = key, descending = descending)) }
        scheduleFileReload()
    }

    fun toggleFileSortDirection() {
        val current = _state.value.fileListOptions
        updateFileSort(current.sortKey, !current.descending)
    }

    /**
     * 文件管理器式排序：目录永远在文件之前，正序/倒序只改变每组内部顺序。
     * 不能对完整列表直接调用 reversed()，否则倒序会把文件夹整体翻到文件后面。
     */
    private fun fileComparator(options: FileListOptions): Comparator<FileEntry> {
        val inside = when (options.sortKey) {
            FileSortKey.NAME -> compareBy<FileEntry> { it.name.lowercase() }
            FileSortKey.SIZE -> compareBy<FileEntry> { it.size }.thenBy { it.name.lowercase() }
            FileSortKey.MODIFIED -> compareBy<FileEntry> { it.modifiedAt }.thenBy { it.name.lowercase() }
            FileSortKey.TYPE -> compareBy<FileEntry> {
                it.name.substringAfterLast('.', "").lowercase()
            }.thenBy { it.name.lowercase() }
        }
        val orderedInside = if (options.descending) inside.reversed() else inside
        return compareBy<FileEntry> { if (it.directory) 0 else 1 }.then(orderedInside)
    }

    /** 共享存储没授权时，跳去系统那个「所有文件访问权限」页面。 */
    fun refreshSharedStoragePermission() {
        val granted = runCatching {
            android.os.Environment.isExternalStorageManager()
        }.getOrDefault(false)
        _state.update { it.copy(sharedStorageGranted = granted) }
    }

    // ---------- 文件：编辑（已搬到独立 Activity） ----------
    //
    // 这里**只剩一个入口**：把「要编辑的路径」放进状态，由界面层去启动
    // com.zhizhu.zhicode.compose.editor.EditorActivity。
    //
    // 原先这一整段（读正文 / markFileDirty / saveFile / reloadOpenFile /
    // cancelEditingFile / requestCloseFile / saveAndCloseFile / editorContentOf /
    // isEditablePreview …）都长在主界面的 ViewModel 里，因为编辑器曾经是主界面里的
    // 一个页面。代价是返回键与输入法可见性要在同一个窗口里和 NavDisplay、对话输入器
    // 分权 —— 「编辑后返回不弹未保存确认」「键盘时有时无」都出在这里。
    // 编辑器现在有自己的 ViewModel（EditorViewModel）与自己的窗口，所以这一整段消失。

    // ---------- 文件：新建 / 重命名 / 删除 ----------

    /**
     * 打开「新建」表单。
     *
     * <p>**没有 `directory` 参数**：建文件还是建文件夹是弹窗上两个按钮的选择，
     * 只有点下去那一刻才知道。原先这里按扩展名猜（`name.contains('.')`）——
     * 那是"两个入口各建一种"时代的产物，而它会把 `Makefile`、`LICENSE`、
     * `.gitignore` 这类**没有扩展名的文件**建成目录，用户点完「文件」得到一个文件夹。
     *
     * <p>名字框**留空**（占位符提示"名称"）。原先预填 `新建文件.txt` / `新建文件夹`，
     * 现在两种可能并存，预填哪一个都会让另一个按钮建出用户没想要的名字
     * （点「文件夹」得到一个叫 `新建文件.txt` 的文件夹）。
     */
    fun newFileForm() {
        _state.update { it.copy(fileNameForm = FileNameForm(title = "新建", draft = "")) }
    }

    /** 打开「重命名」表单。 */
    fun renameForm(entry: FileEntry) {
        _state.update {
            it.copy(fileNameForm = FileNameForm(title = "重命名", target = entry, draft = entry.name))
        }
    }

    fun updateFileNameDraft(text: String) = _state.update { s ->
        // 改名字就把上一次的失败原因清掉：不清的话"已经存在"会一直挂在那里，
        // 用户明明已经换成另一个名字了，看到的还是上一个错。
        s.copy(fileNameForm = s.fileNameForm?.copy(draft = text, failure = null))
    }

    fun cancelFileNameForm() = _state.update { it.copy(fileNameForm = null) }

    /**
     * 提交「新建 / 重命名」。
     *
     * <p>[directory] 来自弹窗上被点的那一个按钮（重命名时无意义，走 target 那一支）。
     *
     * <p>新建之后**立刻打开它**（文件）或**走进去**（目录）—— 「新建了个文件然后还要自己找出来」
     * 是一步没必要的操作。重命名则刷新列表即可（当前内容还开着，路径没变）。
     *
     * <p>失败**回填进表单**（`failure`）而不是写 `message`：`MessageBar` 是 `ChatArea`
     * 的孩子，在文件页上提交失败时界面上什么都看不见（弹窗还会照常关掉，
     * 看起来像"建成功了但列表里没有"）。
     */
    fun submitFileNameForm(directory: Boolean = false) {
        val form = _state.value.fileNameForm ?: return
        val dir = File(_state.value.filePath)
        viewModelScope.launch(Dispatchers.IO) {
            val name = form.draft
            val error = if (form.target != null) {
                FileOps.rename(File(form.target.path), name)
            } else if (directory) {
                FileOps.createDirectory(dir, name)
            } else {
                FileOps.createFile(dir, name)
            }
            if (error != null) {
                _state.update { s ->
                    s.copy(fileNameForm = s.fileNameForm?.copy(failure = error))
                }
                return@launch
            }
            _state.update { it.copy(fileNameForm = null, message = if (form.target != null) "已重命名为 $name" else "已新建 $name") }
            val created = File(dir, name)
            if (form.target == null && created.isDirectory) {
                navigateTo(created.absolutePath)
            } else {
                reloadFiles()
                if (form.target == null && created.isFile) {
                    // 「新建了个文件然后还要自己找出来」是一步没必要的手续：直接交给编辑器打开。
                    requestOpenEditor(created.absolutePath)
                }
            }
        }
    }

    /** 打开单条删除确认（带"会一起消失多少条"）。 */
    fun requestDelete(entry: FileEntry) {
        openDeletePrompt(listOf(entry))
    }

    private fun openDeletePrompt(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // 数条数要递归读盘（`countForDelete`），放 IO 线程。
            val count = entries.sumOf { FileOps.countForDelete(File(it.path)) }
            _state.update { it.copy(fileDeletePrompt = FileDeletePrompt(entries = entries, count = count)) }
        }
    }

    fun cancelDelete() = _state.update { it.copy(fileDeletePrompt = null) }

    fun confirmDelete() {
        val prompt = _state.value.fileDeletePrompt ?: return
        val targets = prompt.entries
        viewModelScope.launch(Dispatchers.IO) {
            var failed: String? = null
            targets.forEach { entry ->
                val error = FileOps.delete(File(entry.path))
                if (error != null && failed == null) failed = error
            }
            if (failed != null) {
                _state.update { it.copy(fileDeletePrompt = null, message = failed, messageIsError = true) }
                return@launch
            }
            val removed = targets.map { it.path }.toSet()
            _state.update { s ->
                s.copy(
                    fileDeletePrompt = null,
                    // 选中的东西被删掉之后选择模式自然结束（空集合＝不在选择模式）。
                    fileSelection = s.fileSelection - removed,
                    fileSelectionMode = false,
                    message = if (targets.size == 1) "已删除 ${targets[0].name}" else "已删除 ${targets.size} 项",
                )
            }
            reloadFiles()
        }
    }

    // 退出确认与保存都不再在这里：编辑器是独立 Activity，它自己的 EditorViewModel
    // 持有正文、脏标志与确认弹窗（见 compose/editor/）。主界面只需要在编辑器返回
    // RESULT_OK 时刷新一次文件列表。

    // ---------- 文件：选择模式（长按多选） ----------

    /**
     * 长按一行：**进入选择模式并选中它**。
     *
     * <p>已经在选择模式里时长按＝切换这一条（与小米一致：选择模式下长按和点击
     * 都是勾/取消勾，不会突然又"进入一次"）。
     */
    fun longPressFileEntry(entry: FileEntry) {
        _state.update { state ->
            val selection = if (state.fileSelectionMode && entry.path in state.fileSelection) {
                state.fileSelection - entry.path
            } else {
                state.fileSelection + entry.path
            }
            state.copy(fileSelectionMode = true, fileSelection = selection)
        }
    }

    fun setFileSelection(paths: Set<String>) {
        _state.update { s ->
            if (!s.fileSelectionMode) s else s.copy(fileSelection = paths.intersect(s.fileEntries.map { it.path }.toSet()))
        }
    }

    /**
     * 点一行。
     *
     * <p>选择模式下是"勾/取消勾"，而不是打开 —— 这一条与长按共用同一份切换逻辑，
     * 分成两套的话迟早出现"长按进去了、一点又跳出去"。
     */
    fun toggleFileSelection(entry: FileEntry) {
        _state.update { s ->
            s.copy(
                fileSelection = if (s.fileSelection.contains(entry.path)) s.fileSelection - entry.path
                else s.fileSelection + entry.path,
            )
        }
    }

    /** 选择模式是否开着。 */
    private fun selectionOn(): Boolean = _state.value.fileSelectionMode

    fun clearFileSelection() = _state.update { it.copy(fileSelection = emptySet(), fileSelectionMode = false) }

    /**
     * 「全选」/「取消全选」。
     *
     * <p>判据是"当前这一层的**全部**条目是否都选中了"，而不是另存一个布尔：
     * 另存的话，用户全选之后又手动取消一条，按钮还写着「取消全选」，
     * 点下去却什么都不会变（或者更糟 —— 变成"选全部"）。
     */
    fun toggleSelectAllFiles() {
        val all = _state.value.fileEntries.map { it.path }.toSet()
        _state.update { s ->
            val allSelected = all.isNotEmpty() && s.fileSelection.containsAll(all)
            s.copy(fileSelection = if (allSelected) emptySet() else all)
        }
    }

    /** 选择模式下选中的那些条目（按当前列表里的条目还原）。 */
    private fun selectedEntries(): List<FileEntry> {
        val selection = _state.value.fileSelection
        return _state.value.fileEntries.filter { it.path in selection }
    }

    /** 将选中项暂存为复制任务；切换目录后使用「粘贴」放入目标目录。 */
    fun copySelectedEntries() = stageSelectedEntries(move = false)

    /** 将选中项暂存为移动任务；源文件只在目标副本成功后删除。 */
    fun moveSelectedEntries() = stageSelectedEntries(move = true)

    private fun stageSelectedEntries(move: Boolean) {
        val paths = selectedEntries().map { it.path }
        if (paths.isEmpty()) return
        _state.update {
            it.copy(
                fileClipboard = paths,
                fileClipboardMove = move,
                fileSelection = emptySet(),
                fileSelectionMode = false,
                message = if (move) "已选择 ${paths.size} 项移动，请打开目标目录后粘贴"
                else "已选择 ${paths.size} 项复制，请打开目标目录后粘贴",
                messageIsError = false,
            )
        }
    }

    /** 把暂存的文件粘贴到当前目录；目标冲突逐项保留原文件并反馈。 */
    fun pasteFilesIntoCurrentDirectory() = runBusy("files.paste") {
        val snapshot = _state.value
        val paths = snapshot.fileClipboard
        if (paths.isEmpty()) return@runBusy
        val destination = File(snapshot.filePath)
        run {
            val failures = mutableListOf<String>()
            val movedPaths = mutableSetOf<String>()
            var completed = 0
            paths.forEach { path ->
                val source = File(path)
                val error = if (snapshot.fileClipboardMove) FileOps.move(source, destination)
                else FileOps.copy(source, destination)
                if (error == null) {
                    completed++
                    if (snapshot.fileClipboardMove) movedPaths += path
                } else failures += "${source.name}：$error"
            }
            reloadFiles()
            _state.update { current ->
                val remainingMovePaths = current.fileClipboard.filterNot { it in movedPaths }
                val allSucceeded = failures.isEmpty()
                current.copy(
                    fileClipboard = if (snapshot.fileClipboardMove) remainingMovePaths else current.fileClipboard,
                    fileClipboardMove = if (snapshot.fileClipboardMove && remainingMovePaths.isEmpty()) false else current.fileClipboardMove,
                    message = if (allSucceeded) {
                        if (snapshot.fileClipboardMove) "已移动 $completed 项" else "已复制 $completed 项"
                    } else "已完成 $completed 项，${failures.size} 项失败：${failures.joinToString("；")}",
                    messageIsError = failures.isNotEmpty(),
                )
            }
        }
    }

    /** 重命名选中的那一条（只在**恰好选中一条**时可用）。 */
    fun renameSelectedEntry() {
        val entry = selectedEntries().singleOrNull() ?: return
        clearFileSelection()
        renameForm(entry)
    }

    /** 删除选中的那些（走同一个确认弹窗，代价按条累加）。 */
    fun deleteSelectedEntries() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        openDeletePrompt(entries)
    }

    /**
     * 把选中的文件附加到下一条消息。
     *
     * <p>这是选择模式里最有用的一个动作：一次挑好几个文件丢进输入器，
     * 比在附件面板里一个一个点快得多（那个面板一次只能附加一个）。
     *
     * <p>读文本可能有失败（二进制、太大），失败的**逐条说清**、成功的照样进去，
     * 而不是整批失败 —— 用户挑了五个文件，其中一个是图片，不该整批白干。
     */
    fun attachSelectedEntries() {
        val entries = selectedEntries()
        // 附加的语义是读取文件正文；目录或文件夹/文件混选时明确置灰，避免
        // 用户点下后悄悄忽略一部分选择。
        if (entries.isEmpty() || entries.any { it.directory }) return
        clearFileSelection()
        viewModelScope.launch(Dispatchers.IO) {
            var attached = 0
            val refused = mutableListOf<String>()
            entries.forEach { entry ->
                if (attachPath(entry.path, entry.name)) attached++ else refused += entry.name
            }
            // 附加完切到对话页：附件是给「下一条消息」的，留在文件页看不到它们，
            // 用户会以为没生效。
            _state.update {
                it.copy(
                    message = if (refused.isEmpty()) "已附加 $attached 个文件"
                    else "已附加 $attached 个，${refused.size} 个读不了（${refused.joinToString("、")}）",
                    messageIsError = refused.isNotEmpty(),
                    tab = if (attached > 0) WorkspaceTab.CHAT else it.tab,
                )
            }
        }
    }

    /** 进入选择模式时用：共享存储没授权时给一个真能点的入口。 */
    fun openSharedStorageSettings() {
        val context = getApplication<android.app.Application>()
        val pkg = context.packageName
        val launched = runCatching {
            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$pkg"),
                )
            } else {
                // API 30 以下没有「所有文件访问权限」这一页，退到应用详情页。
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$pkg"),
                )
            }
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        launched.onFailure { error ->
            // ViewModel 手里只有 Application 上下文，必须带 NEW_TASK（见 openSandbox）。
            // 有些 ROM 把这两个页面也裁掉了 —— 失败就如实说，不静默吞掉。
            _state.update {
                it.copy(
                    message = "打不开系统设置页：${error.javaClass.simpleName}。请手动到「设置 → 应用 → ZhiCode → 权限 → 文件和媒体 → 允许管理所有文件」",
                    messageIsError = true,
                )
            }
        }
    }

    // ---------- 操作反馈 ----------
    //
    // 这段注释曾经写着"原先由 Snackbar 消费 message，但浮层会遮挡底部输入器，已移除"，
    // 于是 29 处 `copy(message = …)` 全都在往一个没人渲染的地方写：保存失败时界面
    // **一点反应都没有**。现在由 `ui/MessageBar.kt` 在悬浮输入器**上方**内联显示 ——
    // 既不遮输入器，也不再是死代码。

    /**
     * 手动关掉当前提示（点提示条本身，或自动超时）。
     *
     * <p>要判空再更新，而不是无条件 `copy(message = null)`：否则一次无关的点击会把
     * 刚刚弹出的**新**提示也抹掉（点旧位置、提示刚换成新的那种竞态）。
     */
    fun clearMessage(text: String? = null) {
        _state.update { current ->
            if (text != null && current.message != text) current
            else current.copy(message = null, messageIsError = false)
        }
    }

    companion object {
        fun clockLabel(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        fun formatTokens(value: Int): String = when {
            value >= 1_000_000 -> trimZero(value / 1_000_000f) + "m"
            value >= 1_000 -> trimZero(value / 1_000f) + "k"
            else -> "$value"
        }

        /** 200.0k → 200k，省出顶栏 chip 的横向空间。 */
        private fun trimZero(value: Float): String =
            if (value >= 100f || value % 1f == 0f) String.format(Locale.US, "%.0f", value)
            else String.format(Locale.US, "%.1f", value)

        /**
         * SAF 文档附加的字节上限。
         *
         * 与 `FileBrowser.MAX_PREVIEW_BYTES` **同一个数**：附件在引擎那边是内联文本，
         * 它进的是模型上下文，256 KB 已经很大了。这里写死常量而不是把
         * `FileBrowser` 的那个 private 常量公开，是因为两者服务的不是同一个上限来源
         * —— 但 256 KB 这个数必须一致，改一个另一个也要改（guard 里钉着）。
         */
        private const val DocumentAttachLimit = 256 * 1024

        /** 思考预览保留的尾部字符数（原版 `liveThinking` 上限 1200）。 */
        private const val THINKING_LIMIT = 1200

        /** 单条工具实时输出的上限（原版 `liveOutput` 上限 40000）。 */
        private const val LIVE_OUTPUT_LIMIT = 40_000

        /**
         * 内置环境安装日志保留的行数。
         *
         * `~/.zhicode/runtime.log` 是排障用的滚动日志，不是审计记录 ——
         * 自动重装这条路径是静默的，不写日志就完全无法判断它走到了哪一步。
         */
        private const val RUNTIME_LOG_MAX_LINES = 200

        /** 工具实时输出落盘间隔：每条进程输出都改一次 StateFlow 会把界面拖垮。 */
        private const val PROGRESS_FLUSH_MS = 200L

        /**
         * 按下停止之后，等多久去问一次"到底停了没有"（第一档）。
         *
         * 3 秒是**兜底**而不是常态：正常收尾是毫秒级，`onEngineTurnComplete` 一到
         * 看门狗就被取消了。设太短会在快速停止时刷出一句多余的提示。
         */
        private const val STOP_CONFIRM_MS = 3_000L

        /**
         * 第一档之后仍未确认时，再等多久就解锁输入框（第二档）。
         *
         * 取值依据是"哪些东西会拖这么久"：MCP / HTTP 这类不可中断的读，
         * 单次超时是 30 秒；Bash 侧最长的是包管理器（1 小时下限）。
         * 等到 9 秒还没确认，基本可以断定这不是"再等一下就好"，
         * 与其把界面锁死，不如让用户能继续打字、同时保留停止键。
         */
        private const val STOP_UNLOCK_MS = 9_000L

        /** 助手气泡上保留的过程步骤条数上限（原版 12）。 */
        private const val PROCESS_STEP_LIMIT = 12

        /** 压缩摘要回显上限：摘要是给人看要点的，整段贴进对话会把记录淹掉。 */
        private const val COMPACT_SUMMARY_LIMIT = 600

        /**
         * 引擎"没什么可压缩"时返回的前缀。
         *
         * 这不是我发明的约定：`ZhiCodeEngine.compactContextSemantic` 用这句话作为
         * 未调用模型的信号，引擎自己的自动压缩分支也用 `startsWith` 判断它。
         * 引擎没有给出结构化返回值，所以只能照它的契约来。
         */
        private const val ENGINE_NOTHING_TO_COMPACT = "上下文已经足够精简"

        /** 文本附件（技能）拼进提示词的长度上限，与原版一致。 */
        private const val TEXT_ATTACHMENT_LIMIT = 60_000

        /**
         * `/init` 的指令，逐字取自原版，只改了一处：目标文件名换成 `ZhiCode.md`。
         *
         * 全英文是刻意的（原版如此）：这段是要模型执行的指令，
         * 而它明确要求限制调研范围、禁止起子 Agent，措辞改动会改变实际行为，
         * 所以不翻译、不改写。
         *
         * 目标文件名必须是 `ZhiCode.md`：写别的名字会让界面在另一个文件上找它，
         * `/init` 看起来就成了「什么都不做」。
         */
        private const val INIT_INSTRUCTION =
            "This is the built-in fast /init maintenance command. Do not enter plan mode, create tasks, " +
                "launch subagents, or perform broad repository research. Read only the existing " +
                "ZhiCode.md/CLAUDE.md/README and primary build manifest or script when present, then directly " +
                "create or improve ZhiCode.md with concise build, test, architecture, conventions, and " +
                "repository-specific instructions. Preserve useful existing instructions and run at most " +
                "one small verification command."
    }
}
