package com.zhizhu.zhicode.compose.ui

import com.zhizhu.zhicode.compose.ui.debug.UiDebugPage
import com.zhizhu.zhicode.compose.ui.debug.ZhiDebugHud
import com.zhizhu.zhicode.compose.ui.debug.ZhiFrameTrace
import com.zhizhu.zhicode.compose.ui.dialogs.ApiConfigOverlay
import com.zhizhu.zhicode.compose.ui.dialogs.McpConfigOverlay
import com.zhizhu.zhicode.compose.ui.dialogs.SearchServicesOverlay
import com.zhizhu.zhicode.compose.ui.dialogs.MemoryOverlay
import com.zhizhu.zhicode.compose.ui.dialogs.RoleCardsOverlay
import com.zhizhu.zhicode.compose.ui.dialogs.SkillsOverlay
import com.zhizhu.zhicode.compose.ui.settings.SettingsDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Application
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhizhu.zhicode.compose.editor.EditorActivity
import com.zhizhu.zhicode.compose.theme.ZhiAppTheme
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.model.ApiConfigState
import com.zhizhu.zhicode.compose.model.McpConfigState
import com.zhizhu.zhicode.compose.model.SearchServicesState
import com.zhizhu.zhicode.compose.model.SkillsState
import com.zhizhu.zhicode.compose.model.RoleCardsState
import com.zhizhu.zhicode.compose.model.MemoryState
import com.zhizhu.zhicode.compose.model.SettingsDraft
import com.zhizhu.zhicode.compose.theme.ZhiThemeMode
import com.zhizhu.zhicode.compose.model.WorkspaceTab
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.state.WorkspaceViewModel
import com.zhizhu.zhicode.compose.state.WorkspaceViewModelFactory
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/**
 * 取（或创建）主 ViewModel。
 *
 * 不能用默认的 `viewModel()`：反射工厂需要精确的 `(Application)` 构造器，而
 * `WorkspaceViewModel` 的构造器带默认参数，Kotlin 不会生成那个重载，会直接抛
 * `NoSuchMethodException` 并让界面白屏。详见 [WorkspaceViewModelFactory]。
 *
 * 工厂里再注入 `WorkspaceRepository`。注意它现在只剩「终端占位横幅」一项：
 * 对话流、工具执行、会话文件都已由真实引擎负责，不再是模拟实现。
 */
@Composable
private fun rememberWorkspaceViewModel(): WorkspaceViewModel {
    val application = LocalContext.current.applicationContext as Application
    val factory = remember(application) { WorkspaceViewModelFactory(application) }
    return viewModel(factory = factory)
}

/** 应用入口：主题 + 主界面。 */
@Composable
fun ZhiCodeApp(viewModel: WorkspaceViewModel = rememberWorkspaceViewModel()) {
    // UI 收集与 Activity 生命周期绑定：后台时暂停快照消费，避免流式引擎仍在
    // 组合树不可见时持续驱动整棵 UI。
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 深/浅只有一处判定（见 ZhiThemeMode 的推导）：应用设置 `ThemeMode` + 系统深浅。
    // 原先这里和三处各写一份，其中沙箱页那份读的是系统而不是应用设置，于是
    // 「设置里选浅色、系统是深色」时两屏颜色不一致。
    val isDark = ZhiThemeMode.rememberCurrentDark(state.themeMode)
    // 主题栈只有一处（theme/ZhiAppTheme.kt）：编辑器那个独立 Activity 用的是同一个，
    // 所以"应用内切主题后两屏颜色不一致"这类问题不会再出现。
    ZhiAppTheme(mode = state.themeMode) {
        // 这里**不再**挂常开的帧泵。
        //
        // 原来这里是 `ZhiFrameTraceHost()`，内部是
        // `while (true) { withFrameNanos { ... } }` —— 等于**每一帧都主动申请一帧**，
        // 应用永不休眠。实测空闲 14 秒、完全不碰屏幕时仍是：
        //   idle frames=300 avg=8.3ms (=120.0fps)  ← 持续满帧
        // 后果不是"多画几帧"，而是主线程永远被占着：任何触摸到达时都得等当前帧
        // 画完，于是"点设置、点模型、点页签全都滞后"，与点哪里无关。
        //
        // 现在改成按需：只有 `ZhiFrameTrace.begin()` 之后才要帧（见那里的说明），
        // 测量结束就自然停下，空闲时一帧都不申请。
        ZhiCodeScreen(state = state, viewModel = viewModel, isDark = isDark)
    }
}

@Composable
private fun ZhiCodeScreen(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    isDark: Boolean,
) {
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 600

    // 帧耗时测量的出口：结果以一条 INFO 消息落在对话流里（仅 debug 构建会触发）。
    // 之所以不只用 logcat：沙箱 guest 的日志不进宿主 logcat，而截图是唯一可靠的观察通道。
    //
    // 另外落一份到 filesDir 下的纯文本：排查「页签高亮与面板内容对不上」这类问题时
    // 对话面板本身就看不见，写进对话流的那些行也读不到。宿主机上可以直接读这个文件
    // （blackbox/data/user/0/<包名>/files/zhi-frame.log）。
    val traceContext = LocalContext.current
    LaunchedEffect(viewModel) {
        // 主落点：filesDir（沙箱里宿主机可直接读，路径见上面注释）。
        ZhiFrameTrace.bindLogFile(
            java.io.File(traceContext.filesDir, "zhi-frame.log"),
            // 第二落点：`Android/media/<包名>/`。
            //
            // 真机上 filesDir 读不到（release 包不是 debuggable、`Android/data` 也不给列），
            // 而 `Android/media/<包名>/` 既是本应用无权限可写、又能被文件管理器直接打开。
            // 目录可能还没建好，所以这里只做一次 mkdirs，失败就只留主落点。
            shared = traceContext.getExternalMediaDirs()?.firstOrNull()?.let { dir ->
                runCatching {
                    dir.mkdirs()
                    java.io.File(dir, "zhi-frame.log")
                }.getOrNull()
            },
        )
        ZhiFrameTrace.sink = { line -> viewModel.reportExternalEvent("帧耗时", line) }
    }

    // 每秒汇总一次重组计数（仅 debug 构建真的执行；release 里 `ZhiFrameTrace.enabled` 为假）。
    //
    // 这条通道是给「狂闪」那类问题用的：帧耗时探针**测不出来**它 ——
    // 实测页签转场 avg=9.6ms max=25.0ms janks=0，logcat 里一条 Skipped frames 都没有。
    // 因为那不是"单帧慢"，而是**每帧都在重建界面**；帧耗时区分不了这两种病，重组次数可以。
    //
    // 输出形如 `ZhiFrame: recompose/s ChatList=60 ChatArea=60 Composer=1`：
    // =60 就是每帧一次（60Hz）—— 那就是要找的元凶；=1 是正常的一次。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            ZhiFrameTrace.flushRecomposeCounts()
        }
    }

    // 各整页在退出动画期间的「最后内容」缓存（见下方 settingsUi / apiUi 的说明）。
    var lastSettingsDraft by remember { mutableStateOf<SettingsDraft?>(null) }
    var lastApiConfig by remember { mutableStateOf<ApiConfigState?>(null) }
    var lastMcpConfig by remember { mutableStateOf<McpConfigState?>(null) }
    var lastSearchServices by remember { mutableStateOf<SearchServicesState?>(null) }
    var lastSkills by remember { mutableStateOf<SkillsState?>(null) }
    var lastRoleCards by remember { mutableStateOf<RoleCardsState?>(null) }
    var lastMemory by remember { mutableStateOf<MemoryState?>(null) }

    // ---- 编辑器（独立 Activity）------------------------------------------------
    //
    // 主界面与编辑器之间只有两条线：
    //  · `state.pendingEditorOpen`：非空就打开它（然后立刻清标志，否则重组会重复启动）；
    //  · 返回结果：RESULT_OK 表示编辑器里至少保存过一次 → 刷一次文件列表
    //    （不刷的话列表上还是旧的大小/时间，用户会以为没存上）。
    //
    // 正文、脏状态、未保存确认全在编辑器那边（EditorViewModel），主界面一概不知道。
    val context = LocalContext.current
    val editorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.refreshFiles()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri -> uri?.let(viewModel::exportSessionTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importSessionFrom) }
    LaunchedEffect(state.pendingEditorOpen) {
        val path = state.pendingEditorOpen ?: return@LaunchedEffect
        viewModel.consumePendingEditorOpen()
        editorLauncher.launch(EditorActivity.intent(context, path))
    }

    // 每页保留「最后一次非空状态」：关闭动作会先把状态置空，而退出动画还要跑
    // 几百毫秒，没有保留值那段时间页面内容会整个闪没（只剩空背景）。
    val settingsUi = state.settingsDraft ?: lastSettingsDraft
    lastSettingsDraft = settingsUi
    val apiUi = state.apiConfig ?: lastApiConfig
    lastApiConfig = apiUi
    val mcpUi = state.mcpConfig ?: lastMcpConfig
    lastMcpConfig = mcpUi
    val searchServicesUi = state.searchServices ?: lastSearchServices
    lastSearchServices = searchServicesUi
    val skillsUi = state.skills ?: lastSkills
    lastSkills = skillsUi
    val roleCardsUi = state.roleCards ?: lastRoleCards
    lastRoleCards = roleCardsUi
    val memoryUi = state.memory ?: lastMemory
    lastMemory = memoryUi


    // 玻璃对象分两层，因为捕获节点不能包含自己：
    //  · glassMain —— 捕获「整个工作区」；用于顶栏 / 侧栏抽屉 / 弹窗。
    //  · glassChat —— 捕获「对话列表」；用于底部悬浮的任务卡与输入器。
    //    这两层必须分开：输入器就在对话区里面，若共用整屏捕获，它会把自己
    //    录进背景里，模糊时出现自我叠影。
    val glassMain = rememberGlass()
    val glass = rememberGlass()

    // UI 重构（S1/S2）：顶栏迁入官方 `topBar` 槽位（与 Miuix example 的
    // BlurredBar + TopAppBar 同一结构）。此前顶栏是 Scaffold 内容里手排的
    // 悬浮 Box 层叠 —— 这让「+」下拉菜单等 Overlay 弹层的锚点坐标
    // （调用层的 positionInWindow）与根 MiuixPopupHost 的窗口原点不一致，
    // 菜单整体飞到锚点上方。迁入槽位后触发器与 PopupHost 同处 Scaffold
    // 根坐标系，弹出位置贴回锚点；手排 z 序与 TopBarInset 手工留白一并删除。
    // 抽屉必须画在 Scaffold **之外**：Miuix Scaffold 的绘制顺序是
    // bodyContent → topBar → popup，放在内容里的抽屉永远被顶栏压住。
    // ---- 页面栈：工作区（root）+ 设置页们（miuix-nav 的 NavDisplay）----
    //
    // 整页之间的推入/弹出交给官方导航容器，动效用它的 MiuixDefault 预设：
    //   推入：新页全宽从**右缘滑入**，被覆盖页向左视差 1/4 宽 + α 衰减 10%
    //   弹出：反向（返回时当前页向右滑出）
    // 这正是需求里「进入从右到左、退出反之」；之前的 AnimatedVisibility 是
    // 页面各自硬切，方向得手工标记、层级也得手工维护，还会出现「二级页返回
    // 直接回工作区」的断栈问题。栈由系统返回键驱动（NavDisplay 内部接
    // PredictiveBackHandler），层级天然正确：二级页 → 设置主页 → 工作区。
    val nav = remember { NavController(navBackStackOf(AppKey.Workspace)) }

    // 由 ViewModel 状态派生的「期望栈」：VM 仍是打开/关闭的唯一入口，
    // 这里只把它翻译成栈，增量 reconcile —— pop 触发弹出动画、push 触发推入动画。
    val desiredStack: List<NavKey> = buildList {
        add(AppKey.Workspace)
        if (state.uiDebugOpen) {
            // UI 调试是**整页**而不是设置二级页：它显示的是全部组件与状态，
            // 与设置 draft 无关。放在 settingsOpen 之前判断，于是从设置页进来时
            // 栈是 [工作区, UI 调试]（设置主页留在 settingsOpen 里，返回即回到它）。
            add(AppKey.UiDebug)
        } else {
            if (state.settingsOpen) add(SettingsKey.Hub)
            when {
                state.apiConfig != null -> add(SettingsKey.Api)
                state.mcpConfig != null -> add(SettingsKey.Mcp)
                state.searchServices != null -> add(SettingsKey.Search)
                state.skills != null -> add(SettingsKey.Skills)
                state.roleCards != null -> add(SettingsKey.RoleCards)
                state.memory != null -> add(SettingsKey.Memory)
            }
        }
    }
    LaunchedEffect(desiredStack) {
        val stack = nav.backStack
        // 先弹掉多余的（返回），再压入新增的（进入）；同深度换页走 replace。
        while (stack.size > desiredStack.size) stack.removeAt(stack.lastIndex)
        for (i in stack.size until desiredStack.size) stack.add(desiredStack[i])
        if (stack.isNotEmpty() && stack.last() != desiredStack.last()) {
            stack[stack.lastIndex] = desiredStack.last()
        }
    }

    // ---- 正交效果层（圆角裁剪 + 调暗）与手势返回方向 ----
    //
    // 照官方 example（`AppContent.kt`）的写法：
    //  · 圆角跟随**设备屏幕圆角** —— `rememberNavSystemCornerRadius()`，平台报 0
    //    （方角屏 / 非 Android）时就不裁。此前写死 16dp：在圆角更大的机器上会
    //    多切一块，在方屏上又白裁一圈，都不像系统。
    //  · `dimAmount` 保持官方滑动式转场的 0.5（卡片式才用 0.2/0.8）。
    //  · `backdropColor` 用**应用背板色**：被覆盖页向左视差 1/4 宽后露出的那一条
    //    读起来应当是"页面之外"，所以取与窗口底色同一层的颜色，而不是面板纯黑/纯白。
    val navCornerRadius = rememberNavSystemCornerRadius()
    val navBackdrop = ZhiColors.backdrop()
    val navEffects = remember(navCornerRadius, navBackdrop) {
        NavDisplayEffects(
            cornerClipRadius = navCornerRadius,
            dimAmount = 0.5f,
            backdropColor = navBackdrop,
        )
    }
    // 滑动关闭是 **opt-in**（Miuix 默认全关）。方向是**物理方向**、不随布局方向镜像，
    // 所以按当前布局方向在 LTR / RTL 之间选 —— 与官方 example 同一段逻辑。
    val swipeBack = when (LocalLayoutDirection.current) {
        LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }

    NavDisplay(
        backStack = nav.backStack,
        modifier = Modifier.fillMaxSize(),
        transition = NavTransitions.MiuixDefault,
        effects = navEffects,
        onBack = {
            // 系统返回：按当前栈顶逐级回退，并同步关掉 VM 的对应状态。
            // 只有 root（工作区）时把返回交还给系统（退出应用）。
            //
            // 读的是 `viewModel.state.value` 而不是闭包里捕获的 `state`：
            // NavDisplay 会把这份 lambda 一直留着，而捕获进去的 state 是**上一次
            // 组合的快照**，它会在"刚变脏、刚关闭"这类时刻过期。
            val live = viewModel.state.value
            when {
                live.uiDebugOpen -> viewModel.closeUiDebug()
                live.apiConfig != null -> viewModel.closeApiConfig()
                live.mcpConfig != null -> viewModel.closeMcpConfig()
                live.searchServices != null -> viewModel.closeSearchServices()
                live.skills != null -> viewModel.closeSkills()
                live.roleCards != null -> viewModel.closeRoleCards()
                live.memory != null -> viewModel.closeMemory()
                live.settingsOpen -> viewModel.closeSettings()
            }
        },
    ) {
        entry<AppKey.Workspace> {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            floatingToolbar = {
                com.zhizhu.zhicode.compose.ui.panes.FileSelectionToolbar(
                    visible = !wide && (state.fileSelectionMode || state.fileSelection.isNotEmpty()),
                    entries = state.fileEntries,
                    selection = state.fileSelection,
                    glass = glassMain,
                    onAttach = viewModel::attachSelectedEntries,
                    onRename = viewModel::renameSelectedEntry,
                    onDelete = viewModel::deleteSelectedEntries,
                    onCopy = viewModel::copySelectedEntries,
                    onMove = viewModel::moveSelectedEntries,
                    clipboardCount = state.fileClipboard.size,
                    clipboardMove = state.fileClipboardMove,
                    onPaste = viewModel::pasteFilesIntoCurrentDirectory,
                    onSelectAll = viewModel::toggleSelectAllFiles,
                    onDismiss = viewModel::clearFileSelection,
                )
            },
            topBar = {
                if (wide) {
                    // 宽屏：侧栏常驻，顶栏只覆盖右侧内容区 —— 由 WorkspaceLayouts
                    // 里的 WideWorkspace 自己排侧栏 + 分隔线 + 顶栏，这里不重复挂。
                } else {
                    ZhiTopBar(
                        state = TopBarState.from(state),
                        wide = false,
                        glass = glassMain,
                        onOpenSidebar = viewModel::openSidebar,
                        onContextClick = {
                            viewModel.onComposerChange("/usage")
                            viewModel.send()
                        },
                        onSettings = {
                            ZhiFrameTrace.begin("settings:open")
                            viewModel.openSettings()
                        },
                        tabs = WorkspaceTab.entries,
                        selectedTab = state.tab,
                        onSelectTab = { tab ->
                            ZhiFrameTrace.begin("tab:${tab.name}")
                            // ViewModel 是唯一业务真源；Pager 由 state.tab 反向同步。
                            viewModel.selectTab(tab)
                        },
                    )
                }
            },
        ) { padding ->
            // 顶栏 blur 需要内容从它**底下滚过**才有东西可采样：
            // 去掉 padding.top，让内容 Box 从 y=0 铺满，各面板自己在头部留白。
            //
            // ⚠️ 丢掉 top 是**有代价的**，改动这里之前必须知道：
            // 任何从 y=0 开始画、又不自己顶开的面板，头部都会被顶栏盖住。
            // 所以每个面板必须二选一 ——
            //   · 对话面板：用 ChatList 的 topInset（**滚动内边距**，它要能滚过顶栏）；
            //   · 其余面板：用 `TopBarInsetCompact` 的 **padding** 顶开
            //     （见 WorkspaceLayouts 里 PaneHost 那个分支）。
            // **新加面板时最容易漏的就是这一步**（文件面板就这么被盖过一次，
            // 当时这里有一句指向并不存在的常量的注释，照它做就漏了）。
            Box(
                modifier = Modifier.fillMaxSize().padding(
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                // 整块工作区先录进 glassMain 的背景（抽屉/弹窗的模糊来源）
                Box(modifier = Modifier.fillMaxSize().then(glassMain.capture(Modifier))) {
                    if (wide) {
                        // 宽屏的顶栏在右侧内容区内部（与常驻侧栏并列），见 WideWorkspace
                        WideWorkspace(
                            state = state,
                            viewModel = viewModel,
                            isDark = isDark,
                            glass = glass,
                            glassMain = glassMain,
                        )
                    } else {
                        CompactWorkspace(
                            state = state,
                            viewModel = viewModel,
                            isDark = isDark,
                            glass = glass,
                            )
                    }
                }


                // ---- Overlay 系列 ----
                // 弹窗挂载与模态模糊背景在 OverlayHost.kt。
                // 必须仍处于 Scaffold 内容里才能找到 popupHost。
                ZhiOverlayHost(state = state, viewModel = viewModel, glassMain = glassMain)

                // ---- 全局调试浮层（debug 构建的 UI 调试页里打开）----
                // 画在最后 = 压在所有东西之上，但只在自己那块矩形里响应手势，
                // 其余事件原样透传给下面的工作区。
                //
                // `BuildConfig.DEBUG` 是硬门控：唯一能把它打开的地方是 debug 独有
                // 的「UI 调试」页，这里再挡一道 —— 万一将来有人把开关挪到别处，
                // 也不会有发布包里冒出调试浮层这种事。
                if (com.zhizhu.zhicode.compose.BuildConfig.DEBUG && state.debugOverlayEnabled) {
                    ZhiDebugHud(
                        state = state,
                        viewModel = viewModel,
                        glass = glassMain,
                        // 让出顶栏（含 Tab 行）的高度，否则药丸会被模糊顶栏压住。
                        topInset = TopBarInsetCompact,
                    )
                }
            }
        }

        // ---- 侧边栏抽屉（窄屏，画在 Scaffold 之上）----
        if (!wide) {
            ZhiSideDrawer(
                open = state.sidebarOpen,
                onClose = viewModel::closeSidebar,
                // 宽度上限 300dp：抽屉是"导航"，不是工作区 —— 铺到 320dp/82% 时
                // 它一打开就把后面的对话流整个盖住，用户反馈「侧栏有点太宽泛了」。
                // 比例 0.76 兜住小屏：360dp 的机器上是 274dp，不至于挤掉 24dp 图标。
                width = (configuration.screenWidthDp * 0.76f).dp.coerceAtMost(300.dp),
                glass = glassMain,
            ) {
                ZhiSidebarHost(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
        }
        }
        // 设置页与它的二级页开启边缘滑动返回（Miuix 的 opt-in）；root 工作区不开 ——
        // 它下面没有可回退的页，而内部已经有侧栏抽屉与面板切换在横向上处理手势。
        entry<SettingsKey.Hub>(swipeDismiss = swipeBack) {
            SettingsDialog(
                draft = settingsUi,
                onChange = viewModel::applySettingsDraft,
                onDismiss = viewModel::closeSettings,
                onNavigate = viewModel::navigateFromSettings,
            )
        }
        entry<SettingsKey.Api>(swipeDismiss = swipeBack) {
            ApiConfigOverlay(
                config = apiUi,
                onDismiss = viewModel::closeApiConfig,
                onNew = viewModel::newApiProfile,
                onEdit = viewModel::editApiProfile,
                onSelect = viewModel::selectApiProfile,
                onDelete = viewModel::deleteApiProfile,
                onDraftChange = viewModel::updateApiProfileDraft,
                onSave = viewModel::saveApiProfile,
                onCancelForm = viewModel::cancelApiProfileForm,
            )
        }
        entry<SettingsKey.Search>(swipeDismiss = swipeBack) {
            SearchServicesOverlay(
                state = searchServicesUi,
                onDismiss = viewModel::closeSearchServices,
                onNew = viewModel::newSearchService,
                onEdit = viewModel::editSearchService,
                onSelect = viewModel::selectSearchService,
                onDelete = viewModel::deleteSearchService,
                onDraftChange = viewModel::updateSearchServiceDraft,
                onSave = viewModel::saveSearchService,
                onCancelForm = viewModel::cancelSearchServiceForm,
            )
        }
        entry<SettingsKey.Mcp>(swipeDismiss = swipeBack) {
            McpConfigOverlay(
                config = mcpUi,
                onDismiss = viewModel::closeMcpConfig,
                onNew = viewModel::newMcpServer,
                onEdit = viewModel::editMcpServer,
                onToggle = viewModel::toggleMcpServer,
                onDelete = viewModel::deleteMcpServer,
                onDraftChange = viewModel::updateMcpDraft,
                onSave = viewModel::saveMcpServer,
                onCancelForm = viewModel::cancelMcpForm,
                onTest = viewModel::testMcpServer,
                onToolOptionsChange = viewModel::setMcpToolOptions,
                onOpenImport = viewModel::openMcpImport,
                onImportTextChange = viewModel::updateMcpImportText,
                onImportConfirm = viewModel::importMcpJson,
                onImportCancel = viewModel::cancelMcpImport,
            )
        }
        entry<SettingsKey.Skills>(swipeDismiss = swipeBack) {
            SkillsOverlay(
                state = skillsUi,
                onDismiss = viewModel::closeSkills,
                onQueryChange = viewModel::setSkillQuery,
                onNew = viewModel::newSkill,
                onNewUrl = viewModel::newSkillUrl,
                onOpenDetail = viewModel::openSkillDetail,
                onCloseDetail = viewModel::closeSkillDetail,
                onEdit = viewModel::editSkill,
                onAttach = viewModel::attachSkill,
                onDelete = viewModel::deleteSkill,
                onCreateDraftChange = viewModel::updateSkillCreateDraft,
                onCreate = viewModel::createSkill,
                onCancelCreate = viewModel::cancelSkillCreate,
                onBodyChange = viewModel::updateSkillBody,
                onSave = viewModel::saveSkill,
                onCancelEdit = viewModel::cancelSkillEdit,
                onNewFile = viewModel::newSkillFile,
                onFileDraftChange = viewModel::updateSkillFileDraft,
                onSaveFile = viewModel::saveSkillFile,
                onCancelFile = viewModel::cancelSkillFileDraft,
                onImportFile = viewModel::importSkillFromUri,
                onDeleteFile = viewModel::deleteSkillFile,
                onUrlDraftChange = viewModel::updateSkillUrlDraft,
                onUrlImport = viewModel::importSkillFromUrl,
                onCancelUrl = viewModel::cancelSkillUrl,
            )
        }
        entry<SettingsKey.RoleCards>(swipeDismiss = swipeBack) {
            RoleCardsOverlay(
                state = roleCardsUi,
                onDismiss = viewModel::closeRoleCards,
                onNew = viewModel::newRoleCard,
                onEdit = viewModel::editRoleCard,
                onSelect = viewModel::selectRoleCard,
                onDisable = viewModel::disableRoleCard,
                onDelete = viewModel::deleteRoleCard,
                onDraftChange = viewModel::updateRoleCardDraft,
                onSave = viewModel::saveRoleCard,
                onCancelEditor = viewModel::cancelRoleCardEditor,
            )
        }
        entry<SettingsKey.Memory>(swipeDismiss = swipeBack) {
            MemoryOverlay(
                state = memoryUi,
                onDismiss = viewModel::closeMemory,
                onEdit = viewModel::editMemory,
                onRunInit = { viewModel.closeMemory(); viewModel.runInitFromUi() },
                onBodyChange = viewModel::updateMemoryBody,
                onSave = viewModel::saveMemory,
                onCancelEdit = viewModel::cancelMemoryEdit,
            )
        }
        // UI 调试整页（仅 debug 构建有入口）。它拿的是**真实 state 与 ViewModel**：
        // 页面里的输入器、各浮层入口都是真能用的，不是静态贴图。
        entry<AppKey.UiDebug>(swipeDismiss = swipeBack) {
            UiDebugPage(
                state = state,
                viewModel = viewModel,
                glass = glass,
                onBack = viewModel::closeUiDebug,
                onExportSession = { name -> exportLauncher.launch(name) },
                onImportSession = { importLauncher.launch(arrayOf("application/gzip", "application/octet-stream", "*/*")) },
            )
        }
    }
}




// ------------------------------------------------------------------ 页面栈 key

/**
 * 应用根页。整页栈的 root = 工作区（顶栏/Tab/面板/输入器），
 * 设置与它的二级页作为栈上更深的一层压在上面。
 *
 * 用 [navBackStackOf] 建栈（内存态，不跨进程持久化），所以 key 不需要
 * `@Serializable` —— 栈的真源仍是 ViewModel 状态，这里只是把它渲染出来。
 */
private sealed interface AppKey : NavKey {
    data object Workspace : AppKey

    /** UI 调试整页（debug 构建的设置页里有入口）。 */
    data object UiDebug : AppKey
}

/** 设置相关页面：hub 与五个二级页。 */
private sealed interface SettingsKey : NavKey {
    data object Hub : SettingsKey
    data object Api : SettingsKey
    data object Mcp : SettingsKey
    data object Search : SettingsKey
    data object Skills : SettingsKey
    data object RoleCards : SettingsKey
    data object Memory : SettingsKey
}
