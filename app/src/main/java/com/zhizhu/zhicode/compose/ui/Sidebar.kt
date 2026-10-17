package com.zhizhu.zhicode.compose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiSpace
import com.zhizhu.zhicode.compose.model.SessionSummary
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 侧栏**真正读到的**四项。
 *
 * <p>理由与 [TopBarState] 完全相同：侧栏只用 `projectName` / `sessions` /
 * `activeSessionId` / `runtimeReady`，而整份 `WorkspaceUiState` 里带着
 * 对话流与全部工具输出 —— 流式期间每 32ms 换一次实例，侧栏就要为一次
 * 无关的正文增量重组一遍（它一个字都不会变），判等还要逐个走会话列表。
 *
 * <p>⚠️ **不能用 `@Immutable` 标这个类**：`sessions` 是 `List`，而 Kotlin 的
 * `List` 只是个只读视图，背后完全可能是可变的 `ArrayList`。标成不可变等于向
 * 编译器保证一件我核实不了的事，一旦哪里原地 add 一下，界面就会静默停更。
 * 它保持 unstable，跳过判断由 Compose 用 `equals()` 做 —— 会话列表通常很短
 * （几十条以内），这个代价是可接受的，而"传整份 UiState"的代价不是。
 */
data class SidebarState(
    val projectName: String,
    val sessions: List<SessionSummary>,
    val activeSessionId: String,
    val runtimeReady: Boolean,
) {
    companion object {
        fun from(state: WorkspaceUiState) = SidebarState(
            projectName = state.projectName,
            sessions = state.sessions,
            activeSessionId = state.activeSessionId,
            runtimeReady = state.runtimeReady,
        )
    }
}

/**
 * 侧栏（UI 重设计 R4 —— 对齐 Miuix 官方文档）。
 *
 * 这一轮的判据是 `compose-miuix-ui.github.io` 的中文文档（本地在
 * `~/projects/miuix/docs/zh_CN/`）：**凡是文档给了默认值的，一律不覆盖**。
 * 上一版为了"紧凑"自己定了一堆数，结果每一处都和组件对着干：
 *
 * | 位置 | 上一版（自定） | 现在（文档值） |
 * |---|---|---|
 * | 行内边距 | `PaddingValues(horizontal = 16, vertical = 4)` | `BasicComponentDefaults.InsideMargin` = 16dp |
 * | 行高 | `heightIn(max = 46.dp)` 截断 | Miuix 自己的 `heightIn(min = 56.dp)` |
 * | 卡片圆角 | `ZhiRadius.card` = 14dp | `CardDefaults.CornerRadius` = 16dp |
 * | 行图标 | `Modifier.size(16.dp)` | 字形固有 24dp（文档 `Icon` 无默认尺寸） |
 * | 会话行 | 手搭 `Row` + `主题 textStyles` | `BasicComponent`（标题 `headline1`、摘要 `body2`） |
 * | 搜索框 | `ZhiTextField` | `InputField`（文档 `SearchBar` 一节，45dp 胶囊） |
 *
 * ## 几何：一条 28dp 基线
 *
 * 裸 `SmallTitle`（自带 28dp 内边距，文档值）+ `Card(padding(horizontal = 12.dp))`
 * 里的行（`BasicComponentDefaults.InsideMargin` 横向 16dp，文档值）→
 * 组标题与行内容的左边缘**都在 28dp**。这正是文档 `SmallTitle` 一节
 * 「与其他组件组合使用」示例的组合方式。
 *
 * ⚠️ 上一版把组标题放在 34dp、行内容放在 14dp（差 20dp）：我把 `start = 8.dp`
 * 拿掉时只算了自己的那 8dp，**没算 `SmallTitle` 自带的 28dp**。用户看到的就是
 * 「标题缩进比行深一截」。
 *
 * 注意本项目 `SettingsGroup` 在裸 `SmallTitle` 之上又套了一层
 * `padding(horizontal = horizontalPadding)`，所以设置页的标题落在 40dp、行落在 28dp。
 * 那是设置页的现状（页内自洽，本轮不动它）；侧栏是窄面板，这里按文档几何对齐。
 *
 * ## 分组卡片
 *
 * 短组（「新会话」「更多」）用 [SidebarGroup] 包成卡片，与会话列表形成
 * 「有框的分组 / 无框的列表」两级层次。会话列表**不**进卡片：它的高度随条数变化，
 * 包成卡片要么撑出一大片空底色，要么让底部「更多」随条数上下跳。
 * 会话列表用的是文档 `Card` 一节「列表中的卡片」的形态（每条一张卡、纵向 8dp 间距）。
 *
 * 行本体用 Miuix [BasicComponent]（文档值：56dp 最小高、`headline1`/`body2` 字号、
 * start 与 center 之间内置 8dp 间距）。会话行的选中态由 [SessionRowInner] 表达。
 */
@Composable
fun ZhiSidebar(
    state: SidebarState,
    onNewSession: () -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
    onSessionActions: (SessionSummary) -> Unit,
    /**
     * 长按动作菜单的宿主插槽。在**每一条会话自己的布局里**调用，菜单就会贴那一条
     * 弹出（见 `ZhiAnchoredActionMenu`）。
     *
     * 第一个参数是会话 id（只有被长按那一条会认领）；第二个是**手指位置**
     * （相对该条），非空时菜单从那一点长出来。
     */
    anchoredMenu: @Composable (String, DpOffset?) -> Unit,
    onSandbox: () -> Unit,
    onRuntime: () -> Unit,
    onSettings: () -> Unit,
    /** 「关于」入口（与设置页底部那一项同一个目标）。 */
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // 搜索是**本地**过滤：`state.sessions` 本来就是完整列表，过滤只影响渲染那一层。
    // 抽屉关闭时内容会被 AnimatedVisibility 销毁，所以查询词不跨开关保留 ——
    // 这是与参考实现（查询词存在 Activity 字段里）的差别，代价是重开抽屉后要重输。
    var query by remember { mutableStateOf("") }
    // `InputField` 的 `expanded` 是必填参数，而且它承担着 API ≤ 27 上的"先展开再聚焦"
    // 兼容职责（见上面搜索框的注释），所以这里必须是真的状态，不能传常量。
    var searchExpanded by remember { mutableStateOf(false) }
    val sessions = remember(state.sessions, query) { filterSessions(state.sessions, query) }

    Surface(modifier = modifier, color = ZhiColors.panelSurface()) {
        // 这里**不能**加横向内边距：28dp 基线由「Card 的 12dp + 行的 16dp」与
        // 「SmallTitle 自带的 28dp」各自算出来，页级再垫一层就会把两者一起推走，
        // 而且会把分组卡片挤窄。横向留白一律交给 SidebarInset。
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // ── 新建会话 ────────────────────────────────────────────────────
            //
            // 不再是一条满宽的主色填充条。`scheme.primary` 是 Miuix 的**品牌蓝**，
            // 那是整屏唯一的满饱和色块，于是整个侧栏的视觉重心压在一个"新建"上
            // （截图里最抢眼的就是它）。改成普通行、**只有图标**取主色 ——
            // 与下面「运行环境就绪」只有图标变绿是同一种做法。
            //
            // rikkahub 式语义化入口：直接开页面/动作，不走命令行通道。
            //
            // 「项目路径与会话」（旧 = 往输入器塞 `/status`）和「当前项目上下文」
            // （旧 = `/resume`）都已删除。它们都把**一次会话动作**伪装成导航行：
            // 前者发一条斜杠命令、后者要模型来选会话，点下去都不会立刻看到结果，
            // 而正下方就是项目历史列表 —— 同一个目标两条路径，用户得猜该点哪个。
            // 恢复会话直接点历史里的会话；项目信息在顶栏与设置里都能看到。
            // （用户已确认：这两行删掉，之后按新的做法重做。）
            SidebarGroup {
                SidebarRow(
                    label = "新会话",
                    icon = ZhiIcons.newSession,
                    iconTint = scheme.primary,
                    onClick = onNewSession,
                )
            }

            SmallTitle(text = "项目历史 · ${state.projectName}")
            // 搜索：Miuix 的 [InputField]（文档 `SearchBar` 一节）。
            //
            // ⚠️ 为什么不用 [ZhiTextField]：`TextFieldConventionTest` 的两条缺陷
            // （文字色被 Card 的 contentColor 吃掉、光标停在索引 0）都出自 Miuix 的
            // 通用 `TextField`。`InputField` 是另一个组件 —— 胶囊底、自带放大镜与清除、
            // 自己取 `LocalContentColor`、光标取 primary，不经过那条路径，
            // 而且胶囊形状正是文档里的 HyperOS 搜索框（45dp 最小高）。
            //
            // ⚠️ `expanded` **必须接一个真实的状态**，不能为了省事传常量 `false`：
            // `hasFocusReassignBug` 在 API ≤ 27（本工程 minSdk 24）为 true，那时
            // InputField 在未展开时是 **disabled** 的，靠点一下回调
            // `onExpandedChange(true)` 再请求焦点。传常量的话那个回调是空的，
            // Android 8.x 上这个搜索框就永远点不进去。
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = SidebarInset),
                label = "搜索会话",
            )
            // 会话多时吃掉剩余高度让一屏看到更多；空/少时按内容自适应。
            // 横向内缩在本层做（行自身只负责纵向），这样每一行的可点区域
            // 与上面的分组卡片等宽。
            //
            // 卡片间距 **4dp**（不是文档示例的 8dp）。文档那 8dp 是给
            // 「设置页里几张互不相关的卡片」的；侧栏是一份**密集导航列表**，
            // 8dp 的缝在小屏上直接把一屏能看到的会话数砍掉一条（用户原话：
            // 「你不觉得这个侧栏UI很扩散吗，收紧一点不懂吗」）。
            LazyColumn(
                modifier = Modifier
                    .padding(horizontal = SidebarInset)
                    .padding(top = ZhiSpace.xs)
                    .then(
                        if (sessions.isEmpty()) Modifier.fillMaxWidth()
                        else Modifier.weight(1f, fill = true),
                    ),
                verticalArrangement = Arrangement.spacedBy(SessionRowSpacing),
            ) {
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "暂无已保存会话" else "没有匹配的会话",
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            modifier = Modifier.padding(
                                start = 16.dp, top = ZhiSpace.s, bottom = ZhiSpace.s,
                            ),
                        )
                    }
                }
                items(
                    items = sessions,
                    key = { it.id },
                    contentType = { "session-row" },
                ) { session ->
                    SessionRow(
                        session = session,
                        active = session.id == state.activeSessionId,
                        onOpen = { onOpenSession(session) },
                        onActions = { onSessionActions(session) },
                        anchoredMenu = anchoredMenu,
                        // 会话被删除、或按更新时间重排时让行**滑过去**而不是瞬移。
                        //
                        // `key` 上面已经给了，所以 Compose 认得出"还是那一行、只是位置变了"；
                        // 但少了这一句它仍然是硬切：删除一条之后，下面所有会话行会"啪"地
                        // 整体上跳一位 —— 用户看到的是列表闪一下，而不是"它挪上去了"。
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // 「技能」「自定义角色卡」原本在这里，已删：它们是**设置里的配置对象**，
            // 侧栏是"去哪一屏"的导航，把两处配置项摆在导航栏里会让同一目标出现两条
            // 路径（侧栏一条、设置页「扩展」组一条），且侧栏会被越堆越长。
            // 配置类入口统一收到设置页（见 SettingsDialog 的 ExtensionsPage）。
            SmallTitle(text = "更多")
            SidebarGroup {
                SidebarRow(label = "ZhiCode 沙箱", icon = ZhiIcons.sandbox, onClick = onSandbox)

                SidebarRow(
                    label = if (state.runtimeReady) "运行环境就绪" else "准备内置 Termux 环境",
                    icon = ZhiIcons.runtime,
                    tint = if (state.runtimeReady) ZhiColors.green() else Color.Unspecified,
                    onClick = onRuntime,
                )
                SidebarRow(label = "设置", icon = ZhiIcons.settings, onClick = onSettings)
                // 「关于」放在导航组的最后：它是**只读信息**，不是工作入口。
                // 与设置页底部那一项是同一个目标（两处入口是刻意的：侧栏是导航，
                // 设置页是"我在找设置时顺手会看到"）。
                SidebarRow(label = "关于 ZhiCode", icon = ZhiIcons.info, onClick = onAbout)
            }
        }
    }
}

/**
 * 分组卡片。
 *
 * 与设置页的 `SettingsGroup` 同构（`Card` 外挂 [SidebarInset]），但**不带组标题**：
 * 组标题由调用点用裸 `SmallTitle` 自己放（见文件头「28dp 基线」）。
 * 本项目 `SettingsGroup` 会给组标题再加一层 `padding(horizontal = 12.dp)`，
 * 于是标题落在 40dp、行落在 28dp —— 设置页页内自洽，但侧栏是窄面板，
 * `Card` 的 `insideMargin` 默认就是 `PaddingValues(0.dp)`、圆角默认 `CornerRadius` = 16dp
 * （都是文档值），这里**不覆盖**；横向留白只由本函数的 `padding` 与
 * `BasicComponentDefaults.InsideMargin`（16dp，文档值）决定。
 */
@Composable
private fun SidebarGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = SidebarInset),
        content = content,
    )
}

/**
 * 侧栏功能行：直接 [BasicComponent]，无外层 Card（卡片由 [SidebarGroup] 提供）。
 *
 * [tint] 让**整行**（图标 + 标题）换色，「运行环境就绪」用它表达就绪/未就绪；
 * [iconTint] 只换图标，用于「新会话」这种"主色只是一个提示"的行。两者分开是因为
 * 合成一个参数就没法表达"只有图标变色"。
 *
 * ⚠️ **覆盖 Miuix 的默认值是有意的**（R5 收紧，见文件顶部的尺寸表）。
 *
 * Miuix 的 `BasicComponentDefaults.InsideMargin`（16dp）与组件内部的
 * `heightIn(min = 56.dp)` 是给**设置页**的一行一个开关用的；侧栏是密集导航列表，
 * 直接吃默认值会让一屏只放得下五条会话。所以这里显式收窄：
 *
 * - `insideMargin` 纵向压到 4dp（横向保持 16dp —— 12(卡片内缩) + 16 = **28dp** 基线，
 *   与 `SmallTitle` 自带的那条线对齐）；
 * - `heightIn(max = SidebarRowMinHeight)` 把行**夹到 44dp**。这条必须写在
 *   外层 modifier 上：组件内部的 `min = 56.dp` 在收到外层 `max = 44.dp` 时会被
 *   Compose 自身夹成 44（`heightIn` 的 min 会被强制收进 [min, max]），不会崩。
 * - 图标显式 `size(SidebarRowIconSize)` = 20dp（字形固有 24dp 在密集列表里偏大）。
 */
@Composable
private fun SidebarRow(
    label: String,
    onClick: () -> Unit,
    icon: Painter? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconTint: Color = Color.Unspecified,
) {
    val scheme = MiuixTheme.colorScheme
    val foreground = if (tint != Color.Unspecified) tint else scheme.onSurface
    val glyph = if (iconTint != Color.Unspecified) iconTint else foreground
    BasicComponent(
        onClick = onClick,
        title = label,
        titleColor = BasicComponentDefaults.titleColor(color = foreground),
        insideMargin = SidebarRowInsideMargin,
        startAction = icon?.let {
            {
                Icon(
                    painter = it,
                    contentDescription = null,
                    tint = glyph,
                    modifier = Modifier.size(SidebarRowIconSize),
                )
            }
        },
        modifier = modifier.fillMaxWidth().heightIn(max = SidebarRowMinHeight),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    anchoredMenu: @Composable (String, DpOffset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只读观察者：不会抢走卡片自己的「点击打开会话」与长按（也不影响无障碍语义）。
    val finger = rememberFingerTracker()
    // 长按那一刻定格手指位置：菜单显示期间手指已经抬起，必须留住这个值。
    var fingerOffset by remember { mutableStateOf<DpOffset?>(null) }
    // 外面这层 Box 只为托住长按菜单与手指追踪：菜单作为它的子项就会用**相对这一条**
    // 的偏移定位（与观察者同一个坐标系）。它不参与布局，行依旧 fillMaxWidth。
    //
    // `modifier` 里是 `animateItem()`，所以它必须挂在**最外层**：挂在里层的话
    // LazyColumn 移动这一条时，动的是内层的内容，外层 Box 仍然原地跳过去。
    Box(modifier = modifier.then(finger.modifier)) {
        SessionRowInner(
            session = session,
            active = active,
            onOpen = onOpen,
            onActions = {
                fingerOffset = finger.offset()
                onActions()
            },
        )
        anchoredMenu(session.id, fingerOffset)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowInner(
    session: SessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // ---- 选中态：用户点名要的三样一起做（整行底色 + 标题加粗换色 + 左侧 3dp 竖条）
    //
    // 底色用 `secondaryContainer` 而**不是** `primary`：主色是这块面板上唯一的满饱和色，
    // 只留给那根竖条与会话图标。整行铺主色会把"我现在在这里"变成"这一条被选中了"，
    // 和「新会话」那条满宽蓝条是同一个毛病。
    //
    // 底色淡入，切换会话时不会硬跳。
    val background by animateColorAsState(
        targetValue = if (active) scheme.secondaryContainer.copy(alpha = 0.72f) else Color.Transparent,
        animationSpec = ZhiMotion.colorSpec,
        label = "sessionRowBackground",
    )
    val titleColor = when {
        active -> scheme.onSecondaryContainer
        session.busy -> scheme.primary
        else -> scheme.onSurface
    }
    // 文档 `BasicComponent` 一节：标题用 `textStyles.headline1`、摘要用 `textStyles.body2`。
    // 这里走它的**自定义内容变体**（文档：「当你希望完全掌控组件内部布局，但仍复用容器
    // 与交互效果时」）—— 唯一要自己接手的是标题字重，因为选中态要把标题加粗，
    // 而标题+摘要那个变体的 `FontWeight.Medium` 是写死的。
    //
    // ⚠️ `endActions` 保持为空（不传）：行尾**不放**任何按钮。
    //
    // 用户原话：「对话中的三个点有什么用，为啥不改成长按触发下拉菜单（跟对话流一样）」。
    // 长按这一条**已经**会弹 `ZhiAnchoredActionMenu`（Miuix `OverlayDropdownPopup`，
    // 与对话流长按工具行是同一个组件），菜单里有重命名 / 删除 —— ⋯ 只是把"还有个菜单"
    // 这件事变得可见，代价是每行多一个 40dp 触摸区，点开会话时手指滑一下就会碰到。
    //
    // 同理这里**不许**出现删除键（见 SidebarNavigationTest 第 6 节）：
    // 删除的入口只有长按菜单里的「删除会话」。
    // 尾部的「时间 · 条数」。
    //
    // R5 收紧：原来是**第二行**（标题一行、摘要一行），一行会话因此要 ~72dp，
    // 一屏只放得下五条。现在挪到标题**右侧**、用 `footnote1`(13sp)，整行降到 44dp。
    // 用的还是原来那两个字段，信息没丢，只是不再各占一行。
    val trailing = "${session.updatedAtLabel} · ${session.messageCount} 条" +
        if (session.note.isNotEmpty()) " · 备注" else ""
    Card(
        onClick = onOpen,
        onLongPress = onActions,
        modifier = Modifier.fillMaxWidth(),
        // 圆角 12dp（文档默认是 `CardDefaults.CornerRadius` = 16dp）。
        // 密集列表里 16dp 会让相邻两行看起来各是一个大方块，"列表"的连续感没了；
        // 12dp 与项目里的 `ZhiRadius.inner` 同一档。
        cornerRadius = SidebarRowCorner,
        colors = CardDefaults.defaultColors(color = background, contentColor = titleColor),
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Box {
            // 竖条**贴卡片左边缘**画，而不是排在图标前面。
            //
            // 排在图标前面的话，为了让它落在 28dp 基线上，图标就得被推到 39dp ——
            // 于是会话行的图标与「更多」组那些行的图标差 11dp。贴边缘画时
            // 图标仍在 12(内缩) + 16(SidebarRowHorizontal) = **28dp**，
            // 与组标题、与所有功能行同一条线。
            //
            // 未选中时**同样占位**没必要：它是绝对定位的覆盖层，不参与排布。
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(3.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.primary),
                )
            }
            // 单行：图标 · 标题(吃满剩余宽度、超出省略) · 时间/条数。
            //
            // 这里**不再**用 `BasicComponent`：它内部 `heightIn(min = 56.dp)` 是
            // 为"标题 + 摘要"两行设计的，单行布局套上去只会白白多出十几 dp 的空白。
            // 交互没有丢 —— 卡片自己带 `onClick` / `onLongPress`，
            // 按压反馈由 `pressFeedbackType = Sink` 给。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SessionRowMinHeight)
                    .padding(
                        start = SidebarRowHorizontal,
                        end = SidebarRowHorizontal - 4.dp,
                        top = 2.dp,
                        bottom = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = ZhiIcons.chat,
                    contentDescription = null,
                    tint = if (active) scheme.primary else scheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(SessionRowIconSize),
                )
                Text(
                    text = session.title,
                    style = MiuixTheme.textStyles.headline1,
                    color = titleColor,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    text = trailing,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 紧凑尺寸

/**
 * 分组卡片与搜索框的横向内缩。
 *
 * **不要**把它加到面板的 `Column` 上：28dp 基线是两边各自算出来的 ——
 * `SmallTitle` 自带 28dp；卡片路径是「本值 12dp + 行自身的 16dp」。
 * 加在页级就会把两者一起推走。
 */
private val SidebarInset = 12.dp

/**
 * 行的横向内边距（图标左边缘到卡片左边缘）。
 *
 * 12([SidebarInset]) + 本值 16 = **28dp** 基线 —— 与 `SmallTitle` 自带的 28dp 对齐，
 * 组标题、功能行图标、会话行图标落在同一条竖线上。
 *
 * 保持 16dp 不动是**有意的**：R5 收紧的是纵向（行高）与行间距，
 * 横向再收会让这条 28dp 基线断掉，看起来像"有的行缩进、有的行没缩进"。
 */
private val SidebarRowHorizontal = 16.dp

/** 功能行（新会话 / 沙箱 / 运行环境 / 设置）的纵向内边距。 */
private val SidebarRowInsideMargin = PaddingValues(horizontal = SidebarRowHorizontal, vertical = 4.dp)

/** 功能行高度上限。Miuix 默认 56dp，密集导航列表里收到 44dp。 */
private val SidebarRowMinHeight = 44.dp

/** 会话行高度下限（单行）。 */
private val SessionRowMinHeight = 44.dp

/** 会话行与功能行的图标尺寸。Miuix 字形固有 24dp，密集列表里收到 20dp。 */
private val SidebarRowIconSize = 20.dp

/** 会话行图标尺寸：比功能行再小一档，让"列表项"与"入口"有层级差。 */
private val SessionRowIconSize = 18.dp

/** 卡片圆角。Miuix 默认 16dp，密集列表里收到 12dp。 */
private val SidebarRowCorner = 12.dp

/** 会话卡片之间的间距。Miuix 示例给"互不相关的卡片"是 8dp，密集列表用 4dp。 */
private val SessionRowSpacing = 4.dp

/**
 * 按标题与备注过滤会话。
 *
 * 判据与参考实现 `renderSidebarSessionRows` 一致（标题 **或** 备注命中即算匹配）。
 * 空查询返回**同一个实例**：`remember(state.sessions, query)` 的键里 `sessions`
 * 没变时不该产生一个新的 List，否则每次重组都会让 `LazyColumn` 重新比对一遍。
 */
private fun filterSessions(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.lowercase().contains(needle) || session.note.lowercase().contains(needle)
    }
}

// ---------------------------------------------------------------- 行高与尺寸

// 尺寸常量集中在上面「紧凑尺寸」一节（`SidebarRowInsideMargin` / `SidebarRowMinHeight` /
// `SessionRowMinHeight` / `SidebarRowIconSize` / `SessionRowIconSize` /
// `SidebarRowCorner` / `SessionRowSpacing`），这里只留历史。
//
// 走过的三段，别再来回翻：
//
// 1. **R3/R4（按文档）**：把自有覆盖全删掉，吃 Miuix 默认 —— 功能行 56dp、
//    会话行「标题 + 摘要」两行约 72dp、图标 24dp、卡片圆角 16dp、间距 8dp。
//    结果是用户看到「侧栏 UI 很扩散」，一屏只放得下五条会话。
// 2. **R5（本版）**：纵向收紧到 44dp、会话行改**单行**（时间/条数挪到标题右侧）、
//    图标 20/18dp、圆角 12dp、间距 4dp。
// 3. 教训：文档默认值面向**设置页**（一行一个开关，56dp 合理），侧栏是**密集导航
//    列表**，照抄只会得到一堆空白。遵照文档指的是交互与视觉语言，不是数值抄写。
