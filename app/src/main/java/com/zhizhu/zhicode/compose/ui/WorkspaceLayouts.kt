package com.zhizhu.zhicode.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.WorkspaceTab
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.state.WorkspaceViewModel
import com.zhizhu.zhicode.compose.ui.pages.ChatPage
import com.zhizhu.zhicode.compose.ui.pages.FilesPage
import com.zhizhu.zhicode.compose.ui.pages.TerminalPage
import com.zhizhu.zhicode.compose.ui.pages.ZhiAnchoredMenuHost
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import kotlinx.coroutines.launch

internal val SidebarWidth = 264.dp
internal val DividerWidth = 1.dp

/** 宽屏：侧栏常驻 + 对话主栏 + 工作区副栏。 */
@Composable
internal fun WideWorkspace(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    isDark: Boolean,
    glass: Glass,
    glassMain: Glass,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        ZhiSidebarHost(state = state, viewModel = viewModel, modifier = Modifier.width(SidebarWidth))
        ZhiVerticalDivider()
        Column(modifier = Modifier.weight(56f)) {
            ZhiTopBar(
                state = TopBarState.from(state),
                wide = true,
                glass = glassMain,
                onOpenSidebar = viewModel::openSidebar,
                onContextClick = {
                    viewModel.onComposerChange("/usage")
                    viewModel.send()
                },
                onSettings = viewModel::openSettings,
                tabs = null,
            )
            ChatPage(state = state, viewModel = viewModel, wide = true, modifier = Modifier.weight(1f), glass = glass)
        }
        ZhiVerticalDivider()
        Column(modifier = Modifier.weight(44f)) {
            val secondary = WorkspaceTab.entries.filter { it != WorkspaceTab.CHAT }
            val paneTabs = remember { secondary }
            val panePager = rememberPagerState(pageCount = { paneTabs.size })
            val paneScope = rememberCoroutineScope()
            WorkspaceTabs(
                tabs = paneTabs,
                selected = state.tab.takeIf { it in paneTabs } ?: paneTabs.first(),
                onSelect = { tab ->
                    // 宽屏副栏也写入同一个业务真源；否则 TabRow 高亮、Pager 和
                    // 下次 state 更新之间会出现短暂的双状态。
                    viewModel.selectTab(tab)
                    paneScope.launch { panePager.springAnimateToPage(paneTabs.indexOf(tab)) }
                },
            )
            WorkspacePager(
                tabs = paneTabs,
                pagerState = panePager,
                selectedTab = state.tab.takeIf { it in paneTabs } ?: paneTabs.first(),
                modifier = Modifier.weight(1f),
                compact = false,
                state = state,
                viewModel = viewModel,
                isDark = isDark,
                glass = glass,
            )
        }
    }
}

/** 窄屏：底部 Tab 切换；内容使用 Miuix 官方示例同款 HorizontalPager。 */
@Composable
internal fun CompactWorkspace(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    isDark: Boolean,
    glass: Glass,
    onPageSelected: (WorkspaceTab) -> Unit = {},
) {
    val tabs = remember { WorkspaceTab.entries.toList() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    WorkspacePager(
        tabs = tabs,
        pagerState = pagerState,
        selectedTab = state.tab,
        modifier = Modifier.fillMaxSize(),
        compact = true,
        state = state,
        viewModel = viewModel,
        isDark = isDark,
        glass = glass,
        onPageSelected = onPageSelected,
    )
}

/**
 * 工作区内容页：与 Miuix 官方示例的布局板块相同，TabRow 只负责选择，
 * HorizontalPager 负责页面跟手、点击后的弹簧吸附和拖动后的 fling。
 */
@Composable
private fun WorkspacePager(
    tabs: List<WorkspaceTab>,
    pagerState: PagerState,
    selectedTab: WorkspaceTab,
    modifier: Modifier,
    compact: Boolean,
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    isDark: Boolean,
    glass: Glass,
    onPageSelected: (WorkspaceTab) -> Unit = {},
) {
    val currentSelectedTab by rememberUpdatedState(selectedTab)
    val latestOnPageSelected by rememberUpdatedState(onPageSelected)
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                tabs.getOrNull(page)?.let { tab ->
                    // currentPage 只在页真正变化时变化；isScrollInProgress 仅负责
                    // 把 settle 边界传给业务层，不把逐帧拖动状态向上扩散。
                    latestOnPageSelected(tab)
                    if (!scrolling && tab != currentSelectedTab) viewModel.selectTab(tab)
                }
            }
    }
    var pagerInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, selectedTab) {
        val target = tabs.indexOf(selectedTab).coerceIn(0, tabs.lastIndex)
        if (!pagerInitialized) {
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            pagerInitialized = true
        } else if (pagerState.currentPage != target) {
            // 顶部 Tab 点击先更新 ViewModel；外部状态变化必须反向驱动 Pager，
            // 否则选中态可能变化而页面仍停在旧页。
            pagerState.springAnimateToPage(target)
        }
    }
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = PagerNavigationSpringSpec,
    )
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = false,
        // 工作区只有少量页面；不要让 Pager 预组合远离当前页的重型对话、终端和文件树。
        beyondViewportPageCount = 0,
        flingBehavior = flingBehavior,
        key = { tabs[it] },
        pageContent = { page ->
            when (tabs[page]) {
                WorkspaceTab.CHAT -> ChatPage(
                    state = state,
                    viewModel = viewModel,
                    wide = !compact,
                    modifier = Modifier.fillMaxSize(),
                    glass = glass,
                )
                else -> PaneHost(
                    state = state,
                    viewModel = viewModel,
                    isDark = isDark,
                    glass = glass,
                    tabOverride = tabs[page],
                    modifier = if (compact) {
                        Modifier.fillMaxSize().padding(top = TopBarInsetCompact)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
        },
    )
}

/** 工作区 Tab 行高度 = 官方 `TabRowDefaults.TabRowWithContourHeight`（45dp）。 */
internal val WorkspaceTabRowHeight = 45.dp

@Composable
internal fun WorkspaceTabs(
    tabs: List<WorkspaceTab>,
    selected: WorkspaceTab,
    onSelect: (WorkspaceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tabs.indexOf(selected).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val labels = remember(tabs) { tabs.map { it.label } }
    ZhiSegmentedTabs(
        tabs = labels,
        selectedIndex = selectedIndex,
        onSelect = { index -> tabs.getOrNull(index)?.takeIf { it != selected }?.let(onSelect) },
        modifier = modifier.fillMaxWidth().height(WorkspaceTabRowHeight),
        matchWidth = true,
    )
}

/**
 * 工作区标签 → 图标。顶栏（TopBar.kt）与宽屏按键组共用，所以是 internal。
 *
 * `@Composable`：图标改成 `Painter` 之后要经 `painterResource` 解析，
 * 而那一步只能发生在组合里（它读 `LocalContext` 并按 id 缓存）。见 [ZhiIcons]。
 */
@Composable
internal fun iconForTab(tab: WorkspaceTab): Painter = when (tab) {
    WorkspaceTab.CHAT -> ZhiIcons.chat
    WorkspaceTab.TERMINAL -> ZhiIcons.terminal
    WorkspaceTab.FILES -> ZhiIcons.files
}

/** 顶栏标题行高度（窄屏用 48dp，见 `ZhiTopBar` 里的 height）。 */
internal val TopBarTitleRowHeight = 48.dp

/** 归顺到顶栏的 Tab 行上下留白（`ZhiTopBar` 里 `padding(vertical = 6.dp)` 的两倍）。 */
internal val TopBarTabRowPadding = 12.dp

/**
 * 悬浮头部的占位高度：宽屏只有标题行，窄屏还要加上归顺进来的 Tab 行。
 *
 * 这三个数字必须和 `ZhiTopBar` 里的实际尺寸严格对齐，否则面板的首行
 * （尤其是标题栏右侧的动作图标）会被悬浮头部压掉一半。
 */
// SmallTopAppBar 官方 CollapsedHeight = 52dp；窄屏 Tab 行在 bottomContent 槽位。
// S1 之后顶栏占布局高度、内容不再重叠 —— 但顶栏 blur 需要内容从底下滚过，
// 所以 Scaffold 内容层不再吃 padding.top，各面板用这两个常量自己留出可见区起点。
internal val TopBarInset = 52.dp

/**
 * 顶栏**自己消费掉的窗口 inset**（就是状态栏那一条）。
 *
 * ## 为什么这个偏移量必须来自窗口，不能写成常量
 *
 * `MainActivity` 调了 `enableEdgeToEdge()`，内容从屏幕 y=0 起排（Miuix Scaffold 的
 * `bodyContentPlaceable.place(0, 0)`）；而 `SmallTopAppBar` 内部是
 * **无条件** `.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))`
 * ——注意 `defaultWindowInsetsPadding = false` 只关掉**横向**的挖孔与导航栏，
 * 纵向那一条关不掉（见 Miuix `TopAppBar.kt` 的 `SmallTopAppBarLayout`）。
 *
 * 于是顶栏真实高度 = 状态栏高度 + 52 + Tab 行。这里原先是
 * `52.dp + WorkspaceTabRowHeight + TopBarTabRowPadding` —— **漏了状态栏**，
 * 所有"自己顶开顶栏"的面板都因此短了一截：滚到最顶上时，第一行内容
 * （对话的首条消息、终端的首行、文件面板的标题）正好被顶栏盖住，
 * 表现就是用户说的「划到最顶上，顶部栏把这些东西全部遮盖了」。
 *
 * 各家状态栏高度并不相同（挖孔屏更高），所以只能实测。
 */
@Composable
private fun topBarWindowInset(): Dp =
    with(LocalDensity.current) {
        WindowInsets.systemBars.only(WindowInsetsSides.Top).getTop(this).toDp()
    }

internal val TopBarInsetCompact: Dp
    @Composable get() = TopBarInset + WorkspaceTabRowHeight + 1.dp + topBarWindowInset()

@Composable
private fun PaneHost(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    isDark: Boolean,
    glass: Glass,
    modifier: Modifier = Modifier,
    tabOverride: WorkspaceTab? = null,
) {
    when (val tab = tabOverride ?: state.tab) {
        WorkspaceTab.TERMINAL -> TerminalPage(
            lines = state.terminalLines,
            projectName = state.projectName,
            // 内置 Termux 环境是否就绪决定这个是"真终端"还是"只读占位"。
            // runtimeReady 是真实探测（见 EnvDoctor / RuntimeInstaller），不是常量。
            runtimeReady = state.runtimeReady,
            workingDirectory = state.projectPath,
            // 终端实例由 ViewModel 持有，**不能**在这里 remember：
            // PaneHost 用 when(tab) 切面板，切走会让这段 Composable 离开组合，
            // 实例若建在这里就会连会话一起被销毁（见 TerminalHolder 的注释）。
            terminalHolder = { ctx -> viewModel.terminalPane(ctx) },
            // 环境未就绪时的三态入口：未安装 / 安装中（进度）/ 失败（可重试）。
            // 以前只有一句说明，用户得自己去设置里找"安装运行环境"。
            installing = state.runtimeInstalling,
            progress = state.runtimeProgress,
            message = state.runtimeMessage,
            onInstall = viewModel::installRuntime,
            modifier = modifier,
        )
        WorkspaceTab.FILES -> FilesPage(
            filePath = state.filePath,
            entries = state.fileEntries,
            emptyNote = state.fileNote,
            listOptions = state.fileListOptions,
            onQueryChange = viewModel::updateFileQuery,
            onSortChange = viewModel::updateFileSort,
            onToggleSortDirection = viewModel::toggleFileSortDirection,
            onOpen = viewModel::openFile,
            onNavigate = viewModel::navigateTo,
            root = state.fileRoot,
            onSwitchRoot = viewModel::switchFileRoot,
            nameForm = state.fileNameForm,
            // 只有一个「新建」入口：建文件还是建文件夹由弹窗里按下的那个按钮决定
            // （见 WorkspaceViewModel.submitFileNameForm 的 KDoc）。
            onNewEntry = viewModel::newFileForm,
            onRename = viewModel::renameForm,
            onNameDraftChange = viewModel::updateFileNameDraft,
            onSubmitName = viewModel::submitFileNameForm,
            onCancelName = viewModel::cancelFileNameForm,
            deletePrompt = state.fileDeletePrompt,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            sharedStorageGranted = state.sharedStorageGranted,
            onGrantSharedStorage = viewModel::openSharedStorageSettings,
            // 长按多选。
            active = state.tab == WorkspaceTab.FILES,
            selectionMode = state.fileSelectionMode,
            fileClipboardCount = state.fileClipboard.size,
            fileClipboardMove = state.fileClipboardMove,
            onPasteFiles = viewModel::pasteFilesIntoCurrentDirectory,
            onSetSelection = viewModel::setFileSelection,
            selection = state.fileSelection,
            onLongPressEntry = viewModel::longPressFileEntry,
            onToggleEntry = viewModel::toggleFileSelection,
            onToggleSelectAll = viewModel::toggleSelectAllFiles,
            onClearSelection = viewModel::clearFileSelection,
            onRenameSelected = viewModel::renameSelectedEntry,
            onDeleteSelected = viewModel::deleteSelectedEntries,
            onAttachSelected = viewModel::attachSelectedEntries,
            modifier = modifier,
        )
        WorkspaceTab.CHAT -> ChatPage(state = state, viewModel = viewModel, wide = false, modifier = modifier, glass = glass)
    }
}

@Composable
internal fun ZhiSidebarHost(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    ZhiSidebar(
        state = SidebarState.from(state),
        onNewSession = viewModel::newSession,
        onOpenSession = viewModel::openSession,
        onSessionActions = viewModel::showSessionActions,
        // 长按会话的动作菜单：由侧栏里那一条会话自己渲染（从手指位置长出来）
        anchoredMenu = { anchorId, fingerOffset ->
            ZhiAnchoredMenuHost(state, viewModel, anchorId, fingerOffset)
        },
        onSandbox = { viewModel.openSandbox() },
        // 「运行环境」行现在是真实探测结果 + 真实的安装/自检窗口，
        // 不再是把 /doctor 当普通消息发出去（那样只会得到一句 Mock 回复）。
        onRuntime = viewModel::openEnvironment,
        onSettings = viewModel::openSettings,
        modifier = modifier,
    )
}
