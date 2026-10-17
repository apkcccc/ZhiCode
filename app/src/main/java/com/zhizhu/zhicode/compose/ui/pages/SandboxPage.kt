package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.theme.ZhiSpace
import com.zhizhu.zhicode.compose.ui.ZhiAnchoredActionMenu
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiNoticeBar
import com.zhizhu.zhicode.compose.ui.ZhiNoticeTone
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideInsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideOutsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.SecondaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.ZhiDialogWidth
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsToggle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 「ZhiCode 沙箱」管理界面的 Compose 主体。
 *
 * ## 为什么要有这个文件（原来的界面为什么被换掉）
 *
 * 这一屏原本是 {@code SandboxBoard.java} 里手写的 View 树（`LinearLayout` / `TextView` /
 * 平台 `Switch` / `AlertDialog`）。它有两个绕不开的问题：
 *
 * 1. **它不在这套主题里**。那个 Activity 没有声明 `android:theme`，于是继承应用级主题
 *    `@android:style/Theme.Material.NoActionBar.Fullscreen` —— 也就是框架的 Material v1。
 *    所以它的开关是**青色**（Material v1 的默认 accent），弹窗是平台对话框样式，
 *    和 Miuix 那一套完全不是一路。这不是配色没调好，是压根没进 Miuix 主题树。
 * 2. **几何是手写死的**。行高固定 48/44/54dp、开关被塞进 64×44dp 的盒子（于是滑块被压变形）、
 *    按钮 `setTextSize(10)`、空态是裸 `TextView` + 固定 120dp。这些数字各自独立，
 *    一改布局就互相打架。
 *
 * 现在这一屏跟其余界面走**同一套主题栈**（见 `SandboxBoard` 里的 `setContent`）：
 * `LocalZhiDark` → `MiuixTheme(colors = …backdrop(), textStyles = zhiTextStyles())`，
 * 圆角统一走 [ZhiRadius]、字阶统一走 [主题 textStyles]，控件全部是 Miuix 组件。
 *
 * ## 这个文件只负责画
 *
 * 它不做任何 RPC、不碰 `SandboxRpc`、不持有线程 —— 那些留在 `SandboxBoard` 里。
 * 界面拿到的是一份不可变的 [SandboxBoardUiState]，所有动作通过回调往上抛。
 * 因此「界面怎么写」与「后端怎么调」互不影响，改样式不会碰到启动重试与回滚逻辑。
 */

/** 状态行语义色。原来靠 `SandboxPalette` 的 success/danger，现在交给主题。 */
enum class SandboxStatusTone { NORMAL, SUCCESS, DANGER }

/**
 * 界面状态。**只读快照**：每次变化由 `SandboxBoard` 整份替换。
 *
 * `*Busy` 是那两个开关的「请求在飞」位：请求在飞的时候必须压住交互，
 * 否则用户连点会让界面显示的状态和服务端最终生效的值不一致。回滚的语义
 * （失败回到原值）由 `SandboxBoard` 负责，这里只表达"能不能点"。
 *
 * ⚠️ 名字曾经叫 `*Interactive`，且生产者把它读成"已就绪"、消费者读成"处理中"，
 * 极性正好相反 —— 一次成功的加载就能把开关永久钉在「正在应用…」且点不动。
 * 现在两边只有一个含义：**true = 请求在飞**。
 */
data class SandboxBoardUiState(
    val status: String = "",
    val statusTone: SandboxStatusTone = SandboxStatusTone.NORMAL,
    val packages: List<String> = emptyList(),
    val hideRoot: Boolean = true,
    val hideRootBusy: Boolean = false,
    val floatingLog: Boolean = true,
    val floatingLogBusy: Boolean = false,
    /** 后端不可用时内联展示的完整原因（含最后启动阶段）；正常时为 null。 */
    val errorDetail: String? = null,
    val dialog: SandboxDialog? = null,
)

/** 当前弹出的对话框。用密封类型而不是若干 boolean —— 同时只可能有一个。 */
sealed interface SandboxDialog {
    /** Root 隐藏的确认框；[hidden] 是用户**想要**切到的那一侧。 */
    data class RootVisibility(val hidden: Boolean) : SandboxDialog

    /** 破坏性操作的确认框（清数据 / 卸载）。 */
    data class ConfirmAction(
        val title: String,
        val packageName: String,
        val action: String,
        val successText: String,
    ) : SandboxDialog

    /**
     * Frida Gadget 首次安装的确认框。
     *
     * 它**不能**复用 [ConfirmAction]：后者点确定后会把 `action` 当成后端动作名发出去，
     * 而安装 Gadget 是本地下载 + 校验，不走 RPC。
     */
    data class FridaInstall(val packageName: String, val pid: Int, val version: String) : SandboxDialog

    /** 长文本面板（进程/SO 基址、Frida 结果、诊断）。[loading] 时显示占位而不是空面板。 */
    data class Detail(val title: String, val body: String, val loading: Boolean) : SandboxDialog
}

/**
 * 沙箱管理界面。
 *
 * 参数全是回调，没有一个是「直接调后端」的 —— 见文件头的说明。
 */
@Composable
fun SandboxPage(
    state: SandboxBoardUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDiagnostics: () -> Unit,
    onToggleHideRoot: (Boolean) -> Unit,
    onToggleFloatingLog: (Boolean) -> Unit,
    onPickApk: () -> Unit,
    onLaunch: (String) -> Unit,
    onStop: (String) -> Unit,
    onClearData: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onProcesses: (String) -> Unit,
    onFrida: (String) -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmRootVisibility: (Boolean) -> Unit,
    onConfirmAction: (SandboxDialog.ConfirmAction) -> Unit,
    onConfirmFridaInstall: (SandboxDialog.FridaInstall) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            // 与设置二级页（SettingsSubPage）同款：Miuix TopAppBar + 大标题随滚动折叠 +
            // 官方的 Back 图标。这里原本是 SmallTopAppBar（固定小标题）配
            //
            // 现在返回图标取自**同一套图标集**（`ZhiIcons.back` = AOSP `ic_arrow_back`），
            // 于是沙箱页与设置二级页的返回键形状一致，也不再需要任何旋转。
            TopAppBar(
                title = "ZhiCode 沙箱",
                scrollBehavior = topAppBarScrollBehavior,
                color = scheme.surface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = ZhiIcons.back,
                            contentDescription = "返回",
                            tint = scheme.onBackground,
                        )
                    }
                },
                actions = {
                    TextButton(text = "诊断", onClick = onDiagnostics)
                },
            )
        },
    ) { padding ->
        // 列表与浮层同处一层 Box：浮层在 LazyColumn 之外，不会因为列表项滚出可视区被销毁。
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .overScrollVertical()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    .padding(horizontal = ZhiSpace.m),
                contentPadding = PaddingValues(
                    // 大标题会折叠，顶栏高度由 padding 给：不能像固定头那样写死。
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { StatusLine(state.status, state.statusTone) }

                // 两个开关放同一张分组卡，与设置页的「分组卡片」逐像素同款：
                // 组标题走 SmallTitle、行本体走 SwitchPreference（都是 Miuix 的组件）。
                // 原来是自己搭 Card + SwitchPreference，没有组标题、内边距也不一样。
                // 开关可用性由 errorDetail 推出来：后端不通就不可点。
                // 不另存字段 —— 多存一个字段就多一处可能与它不一致的地方。
                val settingsEnabled = state.errorDetail == null
                item {
                    // horizontalPadding = 0：这个 LazyColumn 上已经挂了整页的 12dp 内边距，
                    // 分组再各加一次会变成 24dp。
                    SettingsGroup("沙箱行为", horizontalPadding = 0.dp) {
                        SettingsToggle(
                            title = "隐藏 Root",
                            summary = switchSummary(
                                busy = state.hideRootBusy,
                                enabled = settingsEnabled,
                                description = "Guest 看不到常见 Root 路径与管理包",
                            ),
                            checked = state.hideRoot,
                            enabled = settingsEnabled,
                            onCheckedChange = { wanted ->
                                // 请求在飞时直接丢弃点击：连点会让界面值与服务端值错位。
                                // （不可点时由 Miuix 自己拦住，不需要在这里再判一次。）
                                if (state.hideRootBusy) return@SettingsToggle
                                onToggleHideRoot(wanted)
                            },
                        )
                        SettingsToggle(
                            title = "日志悬浮窗",
                            summary = switchSummary(
                                busy = state.floatingLogBusy,
                                enabled = settingsEnabled,
                                description = "在 Guest 界面上叠一层日志与返回控制栏",
                            ),
                            checked = state.floatingLog,
                            enabled = settingsEnabled,
                            onCheckedChange = { wanted ->
                                if (state.floatingLogBusy) return@SettingsToggle
                                onToggleFloatingLog(wanted)
                            },
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            text = "导入 APK",
                            onClick = onPickApk,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "刷新",
                            onClick = onRefresh,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 三种尾部状态（错误详情 / 空态 / 安装列表）之间的过渡**交给 LazyColumn 自己**，
                // 不用 `AnimatedContent` 把整块包起来。
                //
                // 为什么不用 AnimatedContent（照抄计划里的写法会踩到两件事）：
                // 1. 它必须包在 LazyColumn **外面**，于是分支一换整页重建 ——
                //    安装 / 卸载一个包时用户正停在列表中部，会被弹回顶部；
                // 2. 顶栏折叠的 `padding.calculateTopPadding()` 与
                //    `nestedScroll` 都挂在这个 LazyColumn 上，包一层会让那两处错位。
                //
                // 改成给三个分支各自的 item 加 `animateItem()` 并给**稳定的 key**：
                // 分支切换时旧项淡出、新项淡入，观感与 AnimatedContent 的淡变一致，
                // 而滚动位置、惰性、顶栏联动全部原样保留。
                // （三个 key 互不相同，所以旧分支一定是"消失"而不是"复用成新内容"。）
                if (state.errorDetail != null) {
                    item(key = "sandboxError") {
                        ErrorDetail(
                            detail = state.errorDetail,
                            onRetry = onRefresh,
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else if (state.packages.isEmpty()) {
                    item(key = "sandboxEmpty") {
                        EmptyState(modifier = Modifier.animateItem())
                    }
                } else {
                    items(
                        items = state.packages,
                        key = { it },
                        contentType = { "sandbox-package" },
                    ) { pkg ->                        AppCard(
                            packageName = pkg,
                            onLaunch = onLaunch,
                            onStop = onStop,
                            onClearData = onClearData,
                            onUninstall = onUninstall,
                            onProcesses = onProcesses,
                            onFrida = onFrida,
                            // 安装 / 卸载后列表重排时让卡片**滑过去**而不是瞬移。
                            // 上一轮漏了这里：这个 items 本来就给了 key，只是没挂动效。
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )

            // ⚠️ 弹窗宿主**必须**在这个 Scaffold 的 composition 之内。
            //
            // 原来它写在 `Scaffold(...) { }` 的**外面**，于是四个框（诊断详情、
            // 隐藏 Root 确认、清数据/卸载确认、Frida 安装确认）全都不显示，
            // 而且 `state.dialog` 停在非 null —— 点了没反应，界面也不报错。
            //
            // 原因是 Miuix 的弹层不是"就地画"的：`DialogLayout` 只是把一个
            // `DialogState` 注册进 **Scaffold 提供的**那张表
            // （`MiuixPopupUtils.kt` 的 `LocalRootDialogStates.current ?: LocalDialogStates.current`），
            // 真正的绘制由 Scaffold 的 `MiuixPopupHost` 负责
            // （`Scaffold.kt` 的 `CompositionLocalProvider`）。在 Scaffold 之外，
            // 这两个 local 都还是各自的默认值（`null` / 一个空的 `mutableStateListOf`），
            // 于是状态被加进一张**没人画的孤儿表**。
            //
            // 这与技能页当初「加号点不了」是同一个坑，`SettingsSubPage` 的 `overlay`
            // 参数注释里已经写过一次 —— 新增整页时最容易漏的就是这一步。
            SandboxDialogHost(
                dialog = state.dialog,
                onDismiss = onDismissDialog,
                onConfirmRootVisibility = onConfirmRootVisibility,
                onConfirmAction = onConfirmAction,
                onConfirmFridaInstall = onConfirmFridaInstall,
            )
        }
    }
}

/**
 * 开关的副标题。
 *
 * 三档的优先级是 **在飞 > 不可用 > 说明**：正在应用的时候“为什么点不动”不重要，
 * 重要的是告诉用户已经收到了；后端不通的时候则要明确说清楚显示的是**保存值**，
 * 否则用户会以为那里的值就是服务端正在用的值。
 */
@Composable
private fun switchSummary(busy: Boolean, enabled: Boolean, description: String): String = when {
    busy -> "正在应用…"
    !enabled -> "后端不可用，显示的是已保存的值"
    else -> description
}

/**
 * 状态行：后端状态、重试进度、错误原因都落在这里。
 *
 * ⚠️ SUCCESS 不能落回 `scheme.primary`。那是 Miuix 的**默认蓝**（品牌色），
 * 在这里只因为它是主题里第一个抓得到的颜色。它不是语义色：只要用户换主题/
 * 换深浅，这一行就不再读作“成功”了。成功有现成的语义色 `ZhiColors.green()`。
 */
@Composable
private fun StatusLine(status: String, tone: SandboxStatusTone) {
    val scheme = MiuixTheme.colorScheme
    val color = when (tone) {
        SandboxStatusTone.NORMAL -> scheme.onSurfaceVariantSummary
        SandboxStatusTone.SUCCESS -> ZhiColors.green()
        SandboxStatusTone.DANGER -> scheme.error
    }
    if (status.isEmpty()) return
    Text(
        text = status,
        color = color,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/**
 * 后端不可用时的内联说明。
 *
 * 旧实现把这段文字直接塞进列表区（红色 `TextView`，可选中）。这里保留「内联、可选中、
 * 带完整启动阶段」这三个性质 —— 后端没起来时，这段文字就是唯一能说明原因的东西，
 * 不能藏进需要再点一下的弹窗里。
 *
 * ## 为什么改成 [ZhiNoticeBar]
 *
 * 原来这里是一张 `Card(colors = surfaceContainer)` 里面放红字：**看起来就是一张普通卡片**，
 * 只是字恰好是红的。红色只落在文字上，色块面积太小，一眼扫过去不像"出事了"。
 * 现在改用与对话流错误同款的**通知条**（红底红字、无阴影），
 * 两处讲同一件事的地方长相一致。
 *
 * `SelectionContainer` 保留：后端错误常常需要整段复制去搜。
 */
@Composable
private fun ErrorDetail(
    detail: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // `animateItem()`（由调用方传进来）必须落在**这一项的根节点**上。
    // 这里的根是 Column（不是里面那个 ZhiNoticeBar）——
    // 挂在里层的话动画动的是 bar 在容器里的位置，而那个位置从来不变。
    Column(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            ZhiNoticeBar(
                text = detail,
                tone = ZhiNoticeTone.ERROR,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 后端没起来时，界面上的开关与列表都不可用。原先唯一的出路是**离开这一页
        // 再回来**（onResume 才会 reload）。这里给一个就地重试：失败原因看上面那段，
        // 修好之后点它，不必退出。
        if (onRetry != null) {
            TextButton(
                text = "重试连接",
                onClick = onRetry,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 空态。原来是一句裸文本 + 固定 120dp 高，飘在一大片空白里；现在给一个真正的容器。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Card(
        // `animateItem()` 在**最外层**：它管的是这一项在 LazyColumn 里的进出。
        modifier = modifier.fillMaxWidth(),
        cornerRadius = ZhiRadius.card,
        insideMargin = PaddingValues(horizontal = ZhiSpace.l, vertical = 22.dp),
        colors = CardDefaults.defaultColors(
            color = scheme.surfaceContainer,
            contentColor = scheme.onSurfaceVariantSummary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "还没有沙箱应用",
                color = scheme.onBackground,
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "导入一个 APK，或让 Agent 调用 Sandbox install。",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 单个沙箱应用。
 *
 * ## 为什么不是「六个按钮排两行」
 *
 * 原来三个高频动作一行、卸载与两个调试入口一行，每颗按钮 `weight(1f)` 等分 ——
 * 于是「进程 / SO 基址」只有 1/3 屏宽，而 Miuix `ButtonDefaults` 的最小尺寸是
 * 58×40dp、文字还会被挤断。更根本的问题是：**调试入口不是每次都会用的动作**，
 * 把它们跟「运行 / 停止」并排摆，日常操作反而更难看清（六个同权重的按钮 = 没有主次）。
 *
 * 现在按设置页的读法分三层：包名走 [BasicComponent] 当标题行（高度、按压态、
 * 左右留白由 Miuix 负责），运行 / 停止 / 清数据一行（都是日常操作），
 * 「进程 / SO 基址」「Frida」两个调试入口与「卸载」收进行尾的 ⋮ 溢出菜单 ——
 * 与 MCP 列表页同一套写法（[ZhiAnchoredActionMenu] + `OverlayDropdownPopup`）。
 * 卸载进菜单而不是留在明面上：它是破坏性动作，且不该和「运行」抢视线。
 */
@Composable
private fun AppCard(
    packageName: String,
    onLaunch: (String) -> Unit,
    onStop: (String) -> Unit,
    onClearData: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onProcesses: (String) -> Unit,
    onFrida: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // 溢出菜单的锚点。按下标分发，顺序与下面 labels 一一对应。
    var menuOpen by remember(packageName) { mutableStateOf(false) }

    Card(
        // `modifier`（animateItem）必须挂在**最外层**：它管的是这张卡在 LazyColumn
        // 里的位置动画，挂在里面那层的话 Compose 移的是另一层，卡会瞬移。
        modifier = modifier.fillMaxWidth(),
        cornerRadius = ZhiRadius.card,
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = packageName,
                summary = "沙箱内已安装",
                titleColor = BasicComponentDefaults.titleColor(color = scheme.onBackground),
                startAction = {
                    Icon(
                        painter = ZhiIcons.sandbox,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                endActions = {
                    ZhiIconButton(
                        icon = ZhiIcons.more,
                        description = "更多操作",
                        onClick = { menuOpen = true },
                        iconSize = 18.dp,
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Action("运行", Modifier.weight(1f)) { onLaunch(packageName) }
                Action("停止", Modifier.weight(1f)) { onStop(packageName) }
                Action("清数据", Modifier.weight(1f)) { onClearData(packageName) }
            }

            // 不套 `if`：`open` 是布尔入参，浮层常驻才播得完退出动画（见 ZhiAnchoredActionMenu）。
            ZhiAnchoredActionMenu(
                open = menuOpen,
                // 顺序即下标，与下面的分发一一对应，别重排。
                labels = listOf("进程 / SO 基址", "Frida", "卸载"),
                onSelect = { index ->
                    menuOpen = false
                    when (index) {
                        0 -> onProcesses(packageName)
                        1 -> onFrida(packageName)
                        else -> onUninstall(packageName)
                    }
                },
                onDismiss = { menuOpen = false },
                fingerOffset = null,
            )
        }
    }
}

@Composable
private fun Action(text: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        text = text,
        onClick = onClick,
        cornerRadius = ZhiRadius.button,
        modifier = modifier,
    )
}

/** 三个对话框的宿主。同一时刻只可能有一个，所以用 `state.dialog` 的 `when` 分发。 */
@Composable
private fun SandboxDialogHost(
    dialog: SandboxDialog?,
    onDismiss: () -> Unit,
    onConfirmRootVisibility: (Boolean) -> Unit,
    onConfirmAction: (SandboxDialog.ConfirmAction) -> Unit,
    onConfirmFridaInstall: (SandboxDialog.FridaInstall) -> Unit,
) {
    OverlayDialog(
        show = dialog != null,
        onDismissRequest = onDismiss,
        maxWidth = ZhiDialogWidth.Regular,
        outsideMargin = DialogWideOutsideMargin,
        insideMargin = DialogWideInsideMargin,
    ) {
        when (val current = dialog) {
            null -> Unit

            is SandboxDialog.RootVisibility -> DialogShell(
                title = if (current.hidden) "开启 Root 隐藏？" else "关闭 Root 隐藏？",
                actions = {
                    SecondaryButton(text = "取消", onClick = onDismiss)
                    PrimaryButton(
                        text = "确定",
                        onClick = { onConfirmRootVisibility(current.hidden) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            ) {
                Text(
                    text = rootVisibilityEffect(current.hidden) +
                        "\n\n所有正在运行的 Guest 将停止，重新启动后生效。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SandboxDialog.ConfirmAction -> DialogShell(
                title = current.title,
                actions = {
                    SecondaryButton(text = "取消", onClick = onDismiss)
                    PrimaryButton(
                        text = "确定",
                        onClick = { onConfirmAction(current) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            ) {
                Text(
                    text = current.packageName,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SandboxDialog.FridaInstall -> DialogShell(
                title = "安装 Frida Gadget " + current.version,
                actions = {
                    SecondaryButton(text = "取消", onClick = onDismiss)
                    PrimaryButton(
                        text = "安装并加载",
                        onClick = { onConfirmFridaInstall(current) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            ) {
                Text(
                    text = current.packageName + "\n\n首次使用需要从 Frida 官方发布页下载 arm64 Gadget，" +
                        "并校验固定的 SHA-256。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SandboxDialog.Detail -> DialogShell(
                title = current.title,
                actions = { SecondaryButton(text = "关闭", onClick = onDismiss) },
            ) {
                if (current.loading) {
                    Text(
                        text = "正在读取…",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 等宽字体：这几块内容都是「基址 / 阶段」这类对齐敏感的文本，
                    // 比例字体会让列对不齐 —— 与旧实现用 MONOSPACE 是同一个理由。
                    //
                    // ⚠ 这里**不能**再套 verticalScroll：DialogShell 已经把 body 放进了
                    // 竖向滚动容器，嵌套会让内层拿到无限大高度约束并抛
                    // Infinite maximum height constraints 直接崩界面
                    // （就是 DialogScrollNestingTest 守的那个坑，本轮刚修过一次）。
                    Text(
                        text = current.body,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Root 隐藏两侧各自的实际影响。文案与旧实现逐字一致（有测试钉住后半句）。 */
internal fun rootVisibilityEffect(hidden: Boolean): String = if (hidden) {
    "Guest 将隐藏常见 Root 路径和管理包。"
} else {
    "Guest 将可以发现并请求设备上的 su，授权仍由设备 Root 管理器决定。"
}
