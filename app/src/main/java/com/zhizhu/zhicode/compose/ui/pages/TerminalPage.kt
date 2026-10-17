package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhizhu.zhicode.TermuxTerminalPane
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiIndeterminateBar
import com.zhizhu.zhicode.compose.ui.ZhiUsageBar
import com.zhizhu.zhicode.compose.ui.chat.rememberAutoFollow
import com.zhizhu.zhicode.compose.model.TerminalLine
import com.zhizhu.zhicode.compose.model.TerminalTone
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiHorizontalDivider
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.panes.PaneHeader
import com.zhizhu.zhicode.compose.ui.panes.TerminalDrawer
import com.zhizhu.zhicode.compose.ui.panes.TerminalExtraKeys
import com.zhizhu.zhicode.compose.ui.panes.TerminalFailure
import com.zhizhu.zhicode.compose.ui.panes.TerminalHeaderActions
import com.zhizhu.zhicode.compose.ui.panes.TerminalQuickActionsDialog
import com.zhizhu.zhicode.compose.ui.panes.TerminalRenameDialog
import com.zhizhu.zhicode.compose.ui.panes.TerminalRuntimeNotice
import com.zhizhu.zhicode.compose.ui.panes.rememberTerminalState
import com.zhizhu.zhicode.compose.ui.panes.toast
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端面板。
 *
 * 两种形态，由 [runtimeReady] 决定：
 * - **环境就绪**：挂真实的 Termux PTY（`AndroidView` 承载 [TermuxTerminalPane]），
 *   里面是真正的 bash，可以跑命令；
 * - **环境未就绪**：显示只读滚动缓冲 + 一句"为什么不能输入"，
 *   而不是给一个点了没反应的输入框。
 *
 * 为什么用 `AndroidView` 而不是纯 Compose 重写终端：
 * `TerminalView`（约 2000 行）依赖 `Canvas` 逐字符绘制、CSI 序列解析、
 * 文本选择与缩放手势，全部重写成 Compose 的收益很低、风险很高。
 * 终端的正确性来自 `TerminalEmulator`/`TerminalBuffer`（已完整移植），
 * 渲染层复用现成 View 是更稳的选择。
 */
@Composable
fun TerminalPage(
    lines: List<TerminalLine>,
    projectName: String,
    runtimeReady: Boolean,
    workingDirectory: String,
    /** 终端实例的持有者（跨 Tab 保活，见 [WorkspaceViewModel.terminalPane]）。 */
    terminalHolder: TerminalHolder,
    modifier: Modifier = Modifier,
    /** 清屏动作。默认 `null` = 不显示按钮。 */
    onClear: (() -> Unit)? = null,
    /** 正在安装内置运行环境（与 `state.runtimeInstalling` 同源）。 */
    installing: Boolean = false,
    /** 安装进度 0~100；0 表示不显示确定进度。 */
    progress: Int = 0,
    /** 安装步骤文案或最后一次失败原因。 */
    message: String = "",
    /** 初始化运行环境。`null` 表示当前入口不可用（例如已有任务在跑）。 */
    onInstall: (() -> Unit)? = null,
) {
    if (runtimeReady) {
        RealTerminalPane(
            projectName = projectName,
            workingDirectory = workingDirectory,
            terminalHolder = terminalHolder,
            modifier = modifier,
        )
        return
    }
    TerminalPlaceholder(
        lines = lines,
        projectName = projectName,
        modifier = modifier,
        onClear = onClear,
        installing = installing,
        progress = progress,
        message = message,
        onInstall = onInstall,
    )
}

/**
 * 终端实例的取用口。
 *
 * 做成接口而不是直接把 `WorkspaceViewModel` 传进来：这个包（`ui.panes`）里其它面板
 * 都不认识 ViewModel，终端也不该是例外 —— 它需要的只是“给我一个能跨 Tab 活着的
 * `TermuxTerminalPane`”。由 `AppScaffold` 在接线处注入。
 */
fun interface TerminalHolder {
    fun obtain(context: Context): TermuxTerminalPane
}

/**
 * 真实 PTY。
 *
 * ⚠️ **这里不能持有会话的生命周期**。
 *
 * 原来写的是 `remember { TermuxTerminalPane(...) }` + `onDispose { pane.closeAll() }`，
 * 而 `PaneHost` 用 `when(tab)` 切面板：切到别的 Tab，这段 Composable 直接离开组合，
 * `onDispose` 触发，**`closeAll()` 把每个会话（bash 进程 + PTY fd）都 finish 掉**。
 * 所以“切走再切回来”看到的永远是全新的空终端。
 *
 * 现在实例由 [TerminalHolder] 持有（实际挂在 ViewModel 上），离开组合时只把它
 * **从视图树上摘下来**，会话原封不动：
 * - 子进程不依赖 View 挂在哪里，摘下来之后 bash 照常跑，回声也照常进缓冲；
 * - 重新切回来时 [AndroidView] 的 factory 返回同一个实例，终端内容还是原来那些。
 *
 * 唯一需要防御的是“同一个 View 不能有两个父节点”：切回来时新的 AndroidView 可能
 * 先建立、旧的还没来得及 dispose，所以 attach 前再摘一次（[detachFromParent]）。
 *
 * <h3>外壳为什么是 Compose 了</h3>
 * 工具栏、抽屉、扩展键、两个对话框原来全在 `TermuxTerminalPane` 里用 Java 拼 View，
 * 与它正上方那行 Compose 标题栏割裂，而且整块和原版逐行相同（95.4%）。
 * 现在壳在 [TerminalToolbar] / [TerminalDrawer] / [TerminalExtraKeys] /
 * [TerminalQuickActionsDialog] 里，宿主只剩下 PTY 与上游 `TerminalView`
 * （由中间那个 [AndroidView] 承载）。**渲染层仍然是上游 View**：
 * `TerminalView` 靠 Canvas 逐字符绘制 + CSI 解析 + 选区手势，重写收益低风险高。
 */
@Composable
private fun RealTerminalPane(
    projectName: String,
    workingDirectory: String,
    terminalHolder: TerminalHolder,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // remember 只是避免每次重组都调一次 obtain；真正的实例缓存由 holder 负责。
    val pane = remember(terminalHolder) { terminalHolder.obtain(context) }
    val state by rememberTerminalState(pane)

    // 项目目录变化时同步给终端（下一次新建会话用它当工作目录）。
    LaunchedEffect(workingDirectory) {
        pane.setNextSessionWorkingDirectory(workingDirectory)
    }

    // 终端跟随应用主题（用户报过「终端对深浅色不适配」）。
    //
    // ⚠️ 方向只能是**界面 → 宿主**：应用主题的唯一权威在 Compose 这边
    // （`LocalZhiDark`，见 `ZhiColors.isDark`），而宿主是纯 Java 的 `FrameLayout`，
    // 读不到它。反过来在宿主里读配置会变成最底层依赖界面框架 —— 那正是宿主类注释
    // 里明确拒绝的做法。
    //
    // 用 `LaunchedEffect(isDark)` 而不是 `DisposableEffect`：主题一变就要推一次，
    // 且不需要 onDispose。主题没变时 `setDarkTheme` 自己会（同值直接返回），
    // 所以重复组合不会做多余的工作。
    //
    // 默认档是深色，所以**深色模式下这条推送是空操作** —— 外观与改动前逐字节相同；
    // 只有浅色模式会真正换表（色值取自历史上那张没人走到的浅色表）。
    val isDark = ZhiColors.isDark()
    LaunchedEffect(isDark) { pane.setDarkTheme(isDark) }

    DisposableEffect(pane) {
        pane.onRuntimeReady()
        // ⚠️ 只摘视图，**不关会话**。真正释放是 ViewModel.onCleared() 与重装环境之前。
        onDispose { detachFromParent(pane) }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var quickActionsOpen by remember { mutableStateOf(false) }
    // -1 = 没在改名。存下标而不是布尔值：长按哪一行就要改哪一行。
    var renamingIndex by remember { mutableStateOf(-1) }

    // 配置变化（旋转、换主题）后让终端重新对齐一次尺寸。
    //
    // MainActivity 声明了 configChanges，所以旋转**不会**重建 Activity，组合也不会重来；
    // 而终端能不能画出来，取决于上游 TerminalView 内部那个 mEmulator 有没有被设上 ——
    // 它只在 updateSize() 里被设，而 updateSize() 在尺寸为 0 时静默返回，
    // onDraw() 在 mEmulator == null 时只画一块纯黑。真机上确实这样表现过：
    // 旋转后终端一片空白，切到别的标签再切回来才恢复。
    //
    // ⚠️ 这里**不能**无条件 key(configuration) 去重建 AndroidView 节点。
    // 试过，不行：宿主是同一个 View 实例，同一帧内从旧节点迁到新节点时，
    // 旧节点的释放会把刚挂上的宿主再摘一次 → 宿主不在视图树上 → 没有测量/布局 →
    // 尺寸恒为 0 → 谁都救不回来（表现仍然是「旋转后空白、切标签才恢复」）。
    // 所以顺序反过来：先让宿主自己在原地重挂渲染（refreshTerminal），
    // **只有当它报告自己真的不在视图树上时才重建节点**（那是它唯一无法自救的情况）。
    val configuration = LocalConfiguration.current
    var viewEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(configuration) { pane.refreshTerminal() }
    LaunchedEffect(state.hostInTree) { if (!state.hostInTree) viewEpoch++ }

    val selectedIndex = state.sessions.indexOfFirst { it.selected }

    Surface(modifier = modifier.fillMaxSize(), color = ZhiColors.panelSurface()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 只留一条头。这里原来还画的是 `PaneHeader("终端", projectName)`，紧接着
            // 下面 RealTerminalPane 又画一条自己的工具栏（☰ / 会话名 / ⌨ / ⋮）——
            // 于是终端比文件面板多一行标题。现在两块面板同构：
            // 标题 + 副标题（当前会话名，没会话时退回项目名）+ 行尾动作。
            PaneHeader(
                title = "终端",
                subtitle = state.title.ifBlank { projectName },
                actions = {
                    TerminalHeaderActions(
                        onMenu = { drawerOpen = true },
                        onKeyboard = { pane.toggleKeyboard() },
                        onMore = { quickActionsOpen = true },
                    )
                },
            )
            // 抽屉要盖住"终端 + 扩展键"这两段（和原实现一样，
            // 它当时是往面板的 FrameLayout 里加一层全高视图），所以这两段同处一个 Box。
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // ⚠️ `AnimatedContent` 的 content lambda 读的是**当前**闭包，不是
                        // 那个 stage 当时的快照：转场期间 `failure` 已经是 null 了，
                        // 直接用会让离场那一屏画成空文本。所以两个载荷都要过
                        // 「最后一次非空」（见 rememberLastNonNull 的说明）。
                        val failure = rememberLastNonNull(state.failureDetail)
                        val notice = rememberLastNonNull(state.runtimeNotice)
                        // 三态（真终端 / 失败 / 未就绪须知）之间淡变。原来是裸 `when`：
                        // 终端就绪或崩掉那一刻整块一帧换掉。
                        //
                        // ⚠️ **硬性不变量：组合里最多只允许一个 `AndroidView`。**
                        //
                        // `AndroidView` 只出现在 `REAL` 分支，而 `AnimatedContent` 在转场期间
                        // 同时组合的是 `initialState` 与 `targetState` **两个不同的** stage
                        // （相等就不会有转场）—— 两者至多一个等于 `REAL`，
                        // 所以同一个 `pane` 实例永远不会被挂到两个父容器上。
                        // `factory` 里那句 `detachFromParent(pane)` 就是为这条不变量兜底的
                        // （见 §28 守卫：把它挪出 `REAL` 分支、或让两个分支都能拿到
                        // AndroidView，守卫会红）。
                        AnimatedContent(
                            targetState = when {
                                state.failureDetail != null -> TerminalStage.FAILURE
                                state.runtimeNotice != null -> TerminalStage.NOTICE
                                else -> TerminalStage.REAL
                            },
                            transitionSpec = {
                                fadeIn(ZhiMotion.fadeInSpec) togetherWith fadeOut(ZhiMotion.fadeOutSpec)
                            },
                            label = "terminalStage",
                        ) { stage ->
                            when (stage) {
                                TerminalStage.FAILURE -> TerminalFailure(
                                    failure.orEmpty(),
                                    onRetry = { pane.retryTerminal() },
                                )

                                TerminalStage.NOTICE -> TerminalRuntimeNotice(notice.orEmpty())

                                TerminalStage.REAL -> key(viewEpoch) {
                                    AndroidView(
                                        factory = {
                                            // 可能还挂在上一轮的容器上（旧 AndroidView 尚未 dispose），先摘干净
                                            detachFromParent(pane)
                                            pane
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                    TerminalExtraKeys(state) { action ->
                        // DRAWER 的目标是 Compose 自己这个抽屉，宿主碰不到它，
                        // 所以在这一层拦下来 —— 属性文件里写 `DRAWER` 仍然有效。
                        if (action.trim().uppercase() == "DRAWER") {
                            drawerOpen = true
                        } else {
                            pane.sendExtraKey(action)
                        }
                    }
                }
                TerminalDrawer(
                    open = drawerOpen,
                    state = state,
                    onDismiss = { drawerOpen = false },
                    onSelect = { index ->
                        pane.selectSession(index)
                        drawerOpen = false
                    },
                    onNewSession = {
                        pane.newSession()
                        drawerOpen = false
                    },
                    onCloseSession = { index -> pane.closeSession(index) },
                    onRenameSession = { index -> renamingIndex = index },
                    onToggleKeyboard = {
                        pane.toggleKeyboard()
                        drawerOpen = false
                    },
                    onReloadProperties = {
                        pane.reloadProperties()
                        drawerOpen = false
                    },
                )
            }
        }
    }

    TerminalQuickActionsDialog(
        show = quickActionsOpen,
        onDismiss = { quickActionsOpen = false },
        onAction = { index ->
            // 下标顺序与 [QuickActions] 一一对应，两边都不能重排。
            when (index) {
                0 -> pane.pasteFromClipboard()
                1 -> toast(context, pane.copySelection())
                2 -> pane.resetTerminal()
                3 -> pane.newSession()
                4 -> if (selectedIndex >= 0) renamingIndex = selectedIndex
                5 -> pane.closeSession(selectedIndex)
                6 -> pane.killShell()
                7 -> pane.changeFont(-1)
                8 -> pane.changeFont(1)
                9 -> pane.reloadProperties()
                10 -> toast(context, pane.toggleWakeLock())
            }
        },
    )

    TerminalRenameDialog(
        show = renamingIndex >= 0,
        initial = state.sessions.getOrNull(renamingIndex)?.name ?: "",
        onDismiss = { renamingIndex = -1 },
        onConfirm = { name ->
            pane.renameSession(renamingIndex, name)
            renamingIndex = -1
        },
    )
}

/** 把一个 View 从它当前的父容器上摘下来（没父容器就什么都不做）。 */
private fun detachFromParent(view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
}

/**
 * 真实终端区域此刻画的是三种东西中的哪一种。
 *
 * 单独列成枚举、而不是直接拿 `state.failureDetail` / `runtimeNotice` 当
 * `AnimatedContent` 的 key：那两个是**字符串**，内容一变（比如失败原因从
 * 「进程已退出」变成「重试中」）都会被当成"换了一屏"，于是原地重放一次转场。
 * 枚举只表达"是哪一种"，同一种内部的文案变化不再是转场。
 *
 * 顺序即优先级：失败 > 未就绪须知 > 真终端（与原来的 `when` 一致）。
 */
private enum class TerminalStage { REAL, FAILURE, NOTICE }

/** 环境未就绪时的只读占位。文案明确说明**为什么**不能输入。 */
@Composable
private fun TerminalPlaceholder(
    lines: List<TerminalLine>,
    projectName: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    installing: Boolean = false,
    progress: Int = 0,
    message: String = "",
    onInstall: (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()

    // ⚠️ 用 `requestScrollToItem`（非挂起，只登记目标下标、在下次测量里生效），
    // **不要**换回 `animateScrollToItem`。
    //
    // 它俩的差别在"内容连续增长"这个场景下是决定性的：`animateScrollToItem` 是挂起的，
    // 而它的 key 是 `lines.size` —— 终端每来一行就变一次，于是 effect 被不停地取消并重启，
    // 滚动常常在真正滚到底之前就被取消掉，只能等某个空档才追上，看起来就是"滚一下停一下"。
    // 日志输出时本来也不该有动画（每一步都起动画会互相打断）。
    // 完整推导见 `ui/chat/ChatList.kt` 里那段同源注释（对话流踩过同一个坑）。
    //
    // 跟/不跟走和对话流同一套判定（[rememberAutoFollow]）：之前这里无条件贴底，
    // 于是一边看历史、一边有新行进来就会被一次次拽回去 —— 那是"自动滚动不顺畅"
    // 最直接的一种。现在只有用户本来就贴底（或主动拖回底部）时才跟。
    val autoFollow by rememberAutoFollow(listState)
    LaunchedEffect(lines.size, autoFollow) {
        if (autoFollow && lines.isNotEmpty()) listState.requestScrollToItem(lines.size - 1)
    }

    Surface(modifier = modifier.fillMaxSize(), color = ZhiColors.panelSurface()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PaneHeader(
                title = "终端",
                actionIcon = ZhiIcons.clear,
                actionDescription = "清屏",
                onAction = onClear,
                subtitle = projectName,
            )
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = ZhiColors.cardSurface(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        // 同 FilesPane 的正文列表：**不加** `key = { it }` —— 行号就是下标，
                        // 拿它当 key 只是位置别名，不是身份（理由见那边的完整注释）。只挂 animateItem。
                        // 这里本来就没几行，也不会重排，挂上主要是让「清屏 → 重新出 banner」
                        // 这两步不再瞬移。
                        items(
                            count = lines.size,
                            key = { it },
                            contentType = { "terminal-line" },
                        ) { index ->
                            val line = lines[index]
                            Text(
                                text = line.text.ifEmpty { " " },
                                color = toneColor(line.tone, scheme.onSurface),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    VerticalScrollBar(
                        adapter = rememberScrollBarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
            ZhiHorizontalDivider()
            Text(
                text = if (lines.any { it.text.contains("未就绪") || it.text.contains("尚未接入") }) {
                    "初始化内置 Termux 环境后，这里会变成可输入的真实终端。"
                } else {
                    "内置 Termux 环境未就绪，无法启动终端。"
                },
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            )
            // 「环境未就绪」以前只有一句话，没有任何可点的东西 —— 用户得自己
            // 去设置里找到"安装运行环境"。这里直接把入口放在文案下面：
            // 初始化期间显示进度并禁用按钮，失败就退回可再次点击的形态。
            TerminalSetupAction(
                installing = installing,
                progress = progress,
                message = message,
                onInstall = onInstall,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * 占位态的「初始化运行环境」入口。
 *
 * 三态：未安装（按钮可点）/ 正在安装（进度 + 禁用）/ 失败（按钮可再点，附最后一次错误）。
 * 失败时**不假装成功**：文案来自 ViewModel 的 `runtimeMessage`，它会说明卡在哪一步。
 */
@Composable
private fun TerminalSetupAction(
    installing: Boolean,
    progress: Int,
    message: String,
    onInstall: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onInstall == null) return
    val scheme = MiuixTheme.colorScheme
    Column(modifier = modifier) {
        if (installing) {
            if (progress in 1..99) {
                ZhiUsageBar(
                    fraction = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ZhiIndeterminateBar(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = message.ifBlank { "正在解压并配置内置 Termux 环境…" },
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else {
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = "初始化运行环境", color = scheme.onPrimary)
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = scheme.error,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun toneColor(tone: TerminalTone, fallback: Color): Color {
    val scheme = MiuixTheme.colorScheme
    return when (tone) {
        TerminalTone.NORMAL -> fallback
        TerminalTone.DIM -> scheme.onSurfaceVariantSummary
        TerminalTone.PROMPT -> scheme.primary
        TerminalTone.ERROR -> ZhiColors.red()
        TerminalTone.SUCCESS -> ZhiColors.green()
    }
}
