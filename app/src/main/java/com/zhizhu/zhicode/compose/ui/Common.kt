package com.zhizhu.zhicode.compose.ui

import com.zhizhu.zhicode.compose.theme.LocalThemeSettings
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.theme.ZhiSpace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 本文件是 Miuix 组件的**唯一转发层**。
 *
 * 这里曾经是一堆手写的 `Box + background + clip(RoundedCornerShape)`，
 * 现已全部换成 Miuix 自己的组件，好处是按压反馈、涟漪、圆角(squircle)、
 * 主题色解析都由 Miuix 负责，和 HyperOS 原生观感一致。
 *
 * 保留 `Zhi*` 包装只是为了给调用点一个稳定的签名，内部不再有自绘逻辑。
 */

/** 顶栏 chip 的固定高度；胶囊半径由它推出来。 */
private val ChipHeight = 26.dp

/** 输入器底部小 pill 的固定高度。 */
private val PillHeight = 24.dp

/**
 * 底部悬浮层（任务卡 / 反馈条 / 输入器）的横向外边距。
 *
 * ## 为什么要收成一个函数
 *
 * 这三块是**竖着叠在一起的同一列**，左右必须严格对齐 —— 差 1dp 都会在那条中缝上
 * 看得出来。原先这个表达式在三个文件里各写了一遍（原文
 * `if (wide) 24.dp else 12.dp`）。三份拷贝意味着"改一处忘两处"迟早发生，
 * 而一旦发生，表现是"两块宽度对不齐"这种**只有肉眼能发现**的问题：
 * 编译通过、单测全绿、跑起来也不崩。
 *
 * 收成一个函数之后，宽度一致就是**结构性**保证，而不是靠三处记得同步。
 *
 * [wide] 是横屏/大屏档，比手机竖屏多留一段边距。
 */
internal fun floatingHorizontalInset(wide: Boolean): Dp = if (wide) 24.dp else 12.dp

/**
 * 底部悬浮层**共用的外壳**：任务卡、反馈条、输入器都从这一个组件出。
 *
 * <p>之前三处各自调 Miuix [FloatingToolbar]，虽然横向内缩都是
 * [floatingHorizontalInset]，但**阴影**（12 / 10 / 6dp）与**模糊半径**
 * （24 / 16 / 24）各写各的。阴影是画在卡片边界**外面**的，
 * 阴影档位不同，三张卡的"可见边缘"就差出好几 dp —— 看起来就是
 * "不是同宽的"，而几何上它们其实对齐了。这种差异编译器看不见、
 * 单测也不管，只有截图能暴露。
 *
 * <p>所以把外壳整个收进来：圆角、阴影、模糊半径、内缩的**摆放方式**都在
 * 这一个地方定死，三块想不一致都不行。形态以**之前的发送栏**为准：
 * 横向内缩做在布局层（卡片贴住布局框）、`outSidePadding` 清零、
 * 阴影 12dp —— 内缩要是改走 `outSidePadding`，卡片会从布局框里再缩一圈，
 * 三块虽然还是对齐的，但整列观感就变成另一回事了。
 *
 * @param verticalPadding 卡片**外**的纵向间距（任务卡 8dp、反馈条 6dp、
 * 输入器 0 —— 它的外层 Column 自带 top 4 / bottom 10）。
 */
@Composable
internal fun FloatingBottomShell(
    wide: Boolean,
    glass: Glass,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 「悬浮底栏」开关：关闭时贴满宽度、去圆角与阴影 —— 回到传统底部栏的观感。
    // 它不是样式偏好而是**可读性**选择：屏幕小时悬浮内缩会吃掉两侧各 12dp，
    // 而长命令恰好最需要横向空间。
    val floating = LocalThemeSettings.current.floatingBar
    val shape = RoundedCornerShape(if (floating) ZhiRadius.floating else 0.dp)
    val inset = if (floating) floatingHorizontalInset(wide) else 0.dp
    FloatingToolbar(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            // 横向内缩在**布局层**，卡片贴住布局框 —— 与之前发送栏一致。
            .padding(horizontal = inset)
            .then(if (floating) glass.blur(Modifier, shape, radius = 24f) else Modifier)
            // 玻璃边缘的高光 + 阴影共同给出悬浮层与背景的距离；浅色不能只靠
            // 白色底，因为那会回到"一块平白卡片"，也正是 blur 看不见的原因。
            .then(
                if (floating) {
                    Modifier.border(width = 1.dp, color = glass.edgeHighlight(), shape = shape)
                } else {
                    Modifier
                },
            ),
        color = glass.surfaceColor(if (ZhiColors.isDark()) scheme.surfaceContainer else ZhiColors.panelSurface()),
        cornerRadius = if (floating) ZhiRadius.floating else 0.dp,
        outSidePadding = PaddingValues(0.dp),
        shadowElevation = if (floating) 12.dp else 0.dp,
        showDivider = !floating,
        content = content,
    )
}

/**
 * 顶栏/侧栏/工具行使用的图标按钮。转发到 Miuix [IconButton]（自带涟漪与按压反馈）。
 *
 * [compact] 非空时把按钮压成该边长的方形。**必须**在窄行里显式传：
 * Miuix `IconButtonDefaults` 的最小尺寸是 40dp 且默认正圆
 * （实测字节码：`MinWidth = MinHeight = CornerRadius = 40`），
 * 直接用在 25dp 高的工具行上会把整行撑高。
 */
@Composable
fun ZhiIconButton(
    icon: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 20.dp,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    compact: Dp? = null,
) {
    val scheme = MiuixTheme.colorScheme
    // `onBackgroundVariant` 在浅色 Miuix 色板中是较弱的辅助色，作为通用
    // 图标默认色会让菜单、返回和设置等可操作图标显得发灰；主文字色在深浅
    // 主题中都保持足够对比度。明确传入的 tint（包括禁用态）仍优先保留。
    val color = if (tint == Color.Unspecified) scheme.onSurface else tint
    val content: @Composable () -> Unit = {
        Icon(
            painter = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
    }
    if (compact == null) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            backgroundColor = background,
            content = content,
        )
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            backgroundColor = background,
            cornerRadius = compact / 2,
            minHeight = compact,
            minWidth = compact,
            content = content,
        )
    }
}

/**
 * 实心图标按钮（发送 / 停止）。
 * 转发到 Miuix [IconButton]，用 `cornerRadius = 半径` 得到正圆；
 * [square] 为 true 时改成**方角**，发送键即用这个形态。
 * `enabled` 的禁用态由 Miuix 自己处理，不再手写 alpha。
 *
 * [glyph] 非空时改用**文字字形**而不是矢量图标：Miuix 图标库没有纯右箭头，
 * 而上游原版 IQ Code 的发送键本来就是文字字形（`text("↑")`），所以 `→` 走这里。
 */
@Composable
fun ZhiFilledIconButton(
    icon: Painter? = null,
    description: String,
    onClick: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    enabled: Boolean = true,
    size: Dp = 34.dp,
    square: Boolean = false,
    glyph: String? = null,
    glyphSize: TextUnit = MiuixTheme.textStyles.title2.fontSize,
    /** 前景色。默认 `onPrimary`（配 `primary` 容器）；配 `error` 容器时要传 `onError`。 */
    contentColor: Color = Color.Unspecified,
) {
    val foreground = if (contentColor == Color.Unspecified) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        contentColor
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        backgroundColor = containerColor,
        // ⚠️ 方角用的是 `ZhiRadius.actionKey`（8dp），**不是** `ZhiRadius.square`（4dp），
        // 也不是 `ZhiRadius.inner`（10dp）。两个都试过：
        // 4dp 在 30dp 的键上是个硬邦邦的方块（"太方了"），
        // 10dp 又已经偏胶囊、两个键并排像"一块被劈成两半"。
        //
        // 根因是**方角的观感取决于「圆角/边长」比例**，而不是圆角的绝对值：
        // 同一个 10dp 放在 40dp 的按钮上只有 0.25、还算方，放到 30dp 上就是 0.33。
        // 所以按钮变小必须跟着换档，不能沿用同一个绝对值。详见 ZhiRadius.actionKey。
        cornerRadius = if (square) ZhiRadius.actionKey else size / 2,
        minHeight = size,
        minWidth = size,
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                color = foreground,
                fontSize = glyphSize,
                fontWeight = FontWeight.Medium,
            )
        } else if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = description,
                tint = foreground,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 右下角的悬浮操作按钮。
 *
 * 转发到 Miuix 的 [FloatingActionButton]，**不改它的默认值**：圆形、`primary` 底色、
 * 带阴影 —— 这就是 HyperOS 的原生样子。以前这类入口在本工程里是顶栏的一个 TextButton
 * （「新建」），列表长了以后要滑到顶部才够得着；FAB 是悬浮的，滚动时一直可用。
 *
 * 放在 Common.kt 而不是直接在各页 import：这样"用 Miuix 原生 FAB"只有一处声明，
 * 后来的人要改尺寸/颜色时也只有一个地方要动。
 */
@Composable
fun ZhiFloatingActionButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    icon: Painter = ZhiIcons.attach,
    containerColor: Color = Color.Unspecified,
) {
    val scheme = MiuixTheme.colorScheme
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (containerColor == Color.Unspecified) scheme.primary else containerColor,
    ) {
        Icon(
            painter = icon,
            contentDescription = description,
            tint = scheme.onPrimary,
        )
    }
}

/**
 * 顶栏里的胶囊 chip（上下文用量、模型名、设备时间）。
 * Miuix 没有 Chip 组件，用带 `onClick` 的 [Surface] 代替——
 * 这样按压反馈是 Miuix 原生的，而不是之前手写的缩放。
 */
@Composable
fun ZhiChip(
    label: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    maxLines: Int = 1,
    fontSize: TextUnit = MiuixTheme.textStyles.body2.fontSize,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val scheme = MiuixTheme.colorScheme
    // 显式传入的颜色优先，用于上下文用量这类需要按阈值变色的 chip
    val targetBackground = when {
        containerColor != Color.Unspecified -> containerColor
        active -> scheme.primary
        else -> scheme.surfaceContainer
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = ZhiMotion.colorSpec,
        label = "chipBackground",
    )
    val foreground = when {
        contentColor != Color.Unspecified -> contentColor
        active -> scheme.onPrimary
        else -> scheme.onSurface
    }
    ZhiPillSurface(
        onClick = onClick,
        modifier = modifier.height(ChipHeight),
        color = background,
        contentColor = foreground,
        // 胶囊半径 = 高度的一半，不写进 ZhiRadius：高度一改，写死的半径就不再是胶囊
        cornerRadius = ChipHeight / 2,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            maxLines = maxLines,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** 输入器底部的小 pill（权限 / 推理 / 模型）。同样是 [Surface] 的薄封装。 */
@Composable
fun ZhiSmallPill(
    label: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val foreground by animateColorAsState(
        targetValue = if (highlighted) scheme.primary else scheme.onSurface,
        animationSpec = ZhiMotion.colorSpec,
        label = "pillForeground",
    )
    ZhiPillSurface(
        onClick = onClick,
        modifier = modifier.height(PillHeight),
        color = if (highlighted) scheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        contentColor = foreground,
        cornerRadius = PillHeight / 2,
    ) {
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * chip / pill 共用的 Miuix [Card] 底座：内容居中，颜色由外层决定。
 *
 * 为什么用 `Card` 而不是 `Surface`：Miuix 的 squircle 圆角与按压反馈（Sink）只在
 * `Card` / `Button` / `IconButton` / `FloatingToolbar` 上生效，`Surface` 只吃标准
 * `RoundedCornerShape`。chip 是个经常被点的元素，走 `Card` 才能拿到原生手感。
 */
@Composable
private fun ZhiPillSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier,
    color: Color,
    contentColor: Color,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.defaultColors(color = color, contentColor = contentColor)
    val slot: @Composable ColumnScope.() -> Unit = {
        // 用 fillMaxHeight 而不是 fillMaxSize：高度由调用方固定，
        // 宽度应随文字内容走。否则配合 widthIn 时会被撑满整行剩余宽度。
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = colors,
            pressFeedbackType = PressFeedbackType.Sink,
            content = slot,
        )
    } else {
        Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = colors,
            content = slot,
        )
    }
}

/**
 * 加载指示器。转发到 Miuix **[InfiniteProgressIndicator]**：一圈细环 + 一个绕行的点。
 *
 * ## 为什么不用 `CircularProgressIndicator`
 *
 * 那个先是在这里用的，换掉是因为观感：它的自转态是"弧长还会变的一条弧"，
 * 在**居中放大显示**时显得笨重；而 Miuix 这个轨道点式的更轻、更像 HyperOS 里
 * 那种"正在转圈加载"的观感（同一个包里两个都提供，官方示例两种都用）。
 * 换回前者只改这一个函数。
 *
 * ## 尺寸与颜色
 *
 * Miuix 的默认值是 `size = 20.dp, strokeWidth = 2.dp, orbitingDotSize = 2.dp`，
 * `color` 默认是**写死的 `Color.Gray`**。20dp 是给"行内小图标位"用的，
 * 放在面板中间太小、而且灰得看不出是加载中，所以：
 * - 尺寸放大到 [LoadingIndicatorSize]（这个函数只有"页面中央等待"这一种用法）；
 * - 颜色取主题 `primary`，深浅色各自成立，不写死。
 *
 * ## 只有"不确定进度"这一种形态
 *
 * 本工程所有等待都是"不知道还要多久"（拉模型目录、装运行时、跑命令）。
 * 画一条走到 80% 就停住的确定进度条是**假精确**，比一个诚实的转圈更糟。
 * 哪天真有可量化的进度，再加参数。
 */
@Composable
fun ZhiLoadingIndicator(modifier: Modifier = Modifier, size: Dp = LoadingIndicatorSize) {
    InfiniteProgressIndicator(
        modifier = modifier,
        color = MiuixTheme.colorScheme.primary,
        size = size,
        // 环与点都跟着 size 放大，比例与 Miuix 默认值一致（20 : 2 : 2 → 40 : 4 : 4）。
        strokeWidth = size / 10,
        orbitingDotSize = size / 10,
    )
}

/**
 * 加载指示器的直径。
 *
 * 40dp：是 Miuix 默认值（20dp）的两倍。默认那个是给行内小图标位准备的，
 * 放在页面/面板正中央等待时太小、与旁边的文字不成比例。
 */
private val LoadingIndicatorSize = 40.dp

/**
 * 分组小标题（"项目历史 · xxx"、"工作区"）。转发到 Miuix [SmallTitle]。
 *
 * [textColor] / [insideMargin] 两个可选参数是为**底部 Sheet 里的分区**加的：
 * - 标题有时要随状态换色（例如模型面板的"已获取 2 个模型"在失败时要变灰）；
 * - Miuix `SmallTitle` 默认内边距是 `PaddingValues(28.dp, 8.dp)`，而 sheet 自身
 *   已经有 24dp 横向内边距，两者相加会让标题比卡片缩进 52dp。Miuix 官方
 *   `BottomSheetSection` 示例在 sheet 里显式覆写成 `PaddingValues(16.dp, 8.dp)`，
 *   这里跟随它 —— 标题比卡片左边缘多缩进 16dp，是官方的层级观感。
 *
 * 两个参数都有默认值，现有调用点不受影响。
 */
@Composable
fun ZhiSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    insideMargin: PaddingValues? = null,
) {
    // insideMargin 传 null 时不传该参数，走 Miuix 自己的默认值 —— 不用 "0dp 表示默认"
    // 这种哨兵值，否则哪天想把某个位置的内边距显式清零就没法表达。
    if (insideMargin == null) {
        SmallTitle(text = text, modifier = modifier, textColor = textColor)
    } else {
        SmallTitle(text = text, modifier = modifier, textColor = textColor, insideMargin = insideMargin)
    }
}

/**
 * `matchWidth` 时使用足够大的宽度上限，让 Miuix 按可用宽度计算真正的等分 Tab。
 *
 * Miuix 的宽度算法会在 `maxWidth * tabCount + spacing` 小于可用宽度时允许等分宽度，
 * 因此这里不能使用 252dp 之类的较小上限：工作区三项在宽屏上会被错误限制成固定窄块，
 * 剩余空间变成空白，选中胶囊与内容区域看起来就像错位。非等分场景仍使用 Miuix 默认宽度。
 */
private val TabsMatchWidthLimit = 1000.dp

/**
 * 分段按钮组。转发到 Miuix **[TabRowWithContour]** —— `TabRow` 的带轮廓变体。
 *
 * ## 为什么用带轮廓的那个
 *
 * Miuix 的 `TabRow` 有两个变体（官方文档「页面导航 → TabRow」）：
 *  - `TabRow`：各项各自一个圆角块，未选中项**也**有可见描边，像一排独立按钮；
 *  - `TabRowWithContour`：整行一个外轮廓 + 内部一条底轨，只有选中项凸起一个圆角块。
 *
 * 后者才是 HyperOS 里那种「一格一格」的分段控件观感，也是顶栏工作区标签与
 * 设置页分类标签想要的形态。
 *
 * ## 两个变体的默认尺寸不同，换用时要一起改
 *
 * | | `TabRow` | `TabRowWithContour` |
 * |---|---|---|
 * | 高 / 圆角 | 42 / 12 | **45 / 8** |
 * | min / max 宽 | 76 / 98 | **62 / 84** |
 * | itemSpacing | 9 | **5** |
 *
 * 高度差 3dp：外层的固定高度常量（如 `WorkspaceTabRowHeight`）必须跟着给到 45dp，
 * 否则轮廓会被裁掉。
 *
 * ## `matchWidth`
 *
 * 置 `true` 时把 min/max 宽放开，让 n 项精确等分整行宽度并填满；置 `false`
 * 时沿用 Miuix 默认区间，项数超出一屏就横向滚动。
 */
@Composable
fun ZhiSegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    matchWidth: Boolean = false,
) {
    if (tabs.isEmpty()) return
    val safeSelectedIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    // 只向 Miuix 转发合法下标；调用方若在列表更新后暂时拿到旧下标，
    // 不应把越界值传入组件或触发一次无效的回调。
    val selectTab = rememberUpdatedState(onSelect)
    // TabRow 自己拥有横向懒列表；把 state 稳定地留在 wrapper 内，避免每次上层
    // 流式状态变化都重建横向滚动位置，也让选中项自动保持在可见区。
    val tabListState = rememberLazyListState()
    TabRowWithContour(
        tabs = tabs,
        selectedTabIndex = safeSelectedIndex,
        onTabSelected = { index ->
            if (index in tabs.indices && index != safeSelectedIndex) {
                selectTab.value(index)
            }
        },
        modifier = modifier,
        listState = tabListState,
        itemSpacing = 4.dp,
        minWidth = if (matchWidth) 0.dp else TabRowDefaults.TabRowWithContourMinWidth,
        maxWidth = if (matchWidth) TabsMatchWidthLimit else TabRowDefaults.TabRowWithContourMaxWidth,
    )
}

/**
 * 下拉菜单里的一项。
 *
 * 不直接把 Miuix 的 `DropdownItem` 暴露给界面代码：那样每个调用点都要认识
 * `DropdownEntry` / `DropdownItem` 两个类，将来 Miuix 改签名会波及一片。
 * 这里只留界面真正需要表达的三样东西。
 */
data class ZhiMenuItem(
    val text: String,
    /** 条目下方的说明文字。 */
    val summary: String? = null,
    /** 条目左侧的图标。传 null 就不显示。 */
    val icon: Painter? = null,
    /** 单选场景：当前项会在弹出列表里打勾。 */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 触发按钮 + 展开的动作菜单。转发到 Miuix **`OverlayIconDropdownMenu`**。
 *
 * ## 为什么两个 chip 也用它，而不用 `OverlayDropdownPreference`
 *
 * 后者是「设置行」形态：内部是 `BasicComponent` + `Button`，字号取主题的
 * `body1`（**16sp**）、按钮最小高 `ButtonDefaults.MinHeight`（40dp）。
 * 而输入器页脚那一行只有 34dp（同类元素 `ZhiSmallPill` 是 10sp / 28dp），
 * 放在一起就是"格格不入"：字大一截、高度溢出、标签被挤到折行。
 *
 * 官方文档对 `content` 的说明是「按钮内显示的图标（**或其他可组合内容**）」，
 * 所以这里把它当"任意触发内容"用：`+` 传一个图标，两个 chip 传
 * 与 `ZhiSmallPill` 完全同款的文字 + 折叠箭头。这样三者在同一行里尺寸一致。
 *
 * ⚠️ **必须位于 Miuix `Scaffold` 内** —— `Overlay*` 系列靠 Scaffold 提供的
 * `MiuixPopupHost` 渲染弹出内容（官方文档「使用前提」）。本工程根部就是它。
 *
 * 默认尺寸给 34dp：Miuix `IconButtonDefaults` 是 40dp 正圆，
 * 而输入器页脚那一行只有 34dp 高，用默认值会把行撑高。
 */
@Composable
fun ZhiIconDropdownMenu(
    items: List<ZhiMenuItem>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 34.dp,
    minWidth: Dp = 34.dp,
    cornerRadius: Dp = minHeight / 2,
    backgroundColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    /*
     * ⚠️ 这个 entry 必须 `remember`。
     *
     * 输入器页脚有三个 chip 走本函数（`+`、权限、推理、模型），而输入器随
     * `state.composerText` **每敲一个字**重组一次。原来这里没有任何缓存，
     * 于是每个字符都要重建一整份 `DropdownEntry` + 每个条目一个 `DropdownItem`
     * 对象 + 每个条目一个 icon 的可组合 lambda。
     *
     * key 取 `items` 与 `scheme.primary`：前者变了（选中项、文案、回调）必须重建；
     * 后者是 iconSlot 里真正读到的主题色，主题切换时要跟着换，否则图标留在旧配色上。
     * 两个 key 都是值/身份比较，不会因为「重建了一份内容相同的 list」而失效 ——
     * 这正是 Compose 的 lambda 记忆化能配合上的地方。
     */
    val entry = remember(items, scheme.primary) {
        DropdownEntry(
            items = items.map { item ->
                // 先取出成局部 val：`item.icon` 是可空字段，直接进 lambda 无法智能转换。
                val icon = item.icon
                val iconSlot: (@Composable (Modifier) -> Unit)? =
                    if (icon == null) {
                        null
                    } else {
                        { m: Modifier ->
                            Icon(
                                painter = icon,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = m,
                            )
                        }
                    }
                DropdownItem(
                    text = item.text,
                    summary = item.summary,
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = iconSlot,
                )
            },
        )
    }
    // 打开菜单时先收起输入法：App 是 edge-to-edge，窗口不随键盘缩小，而 Miuix
    // 浮层的定位（0.9.4 的 rememberListPopupLayoutInfo）只扣状态栏/导航栏、
    // **不认识 IME** —— 键盘开着时菜单会整个落在键盘底下（用户实测"显示在下层"）。
    // 收起键盘后锚点随 composer 上移，浮层跟着锚点走（ListPopupLayout 持续跟踪
    // onGloballyPositioned），这也是原版 dialog 式菜单的天然行为。
    val keyboard = LocalSoftwareKeyboardController.current
    OverlayIconDropdownMenu(
        entry = entry,
        modifier = modifier,
        enabled = enabled,
        minHeight = minHeight,
        minWidth = minWidth,
        cornerRadius = cornerRadius,
        backgroundColor = backgroundColor,
        onExpandedChange = { expanded -> if (expanded) keyboard?.hide() },
        content = content,
    )
}

/**
 * 文字下拉 chip：外观与 [ZhiSmallPill] **完全一致**（10sp / 28dp / 透明底 + 折叠箭头），
 * 点一下展开选项。
 *
 * 用于输入器页脚的权限 / 推理 —— 旁边就是同款的模型药丸，三者必须看起来是一套。
 *
 * 选中即生效，不需要"选完再提交"那一层（对应 [WorkspaceViewModel.setPermissionMode]
 * / `setEffort`）。
 *
 * ⚠️ 别换回 `OverlayDropdownPreference`：它是 16sp / 40dp 的"设置行"，
 * 放到这条 34dp 的页脚里会撑高整行、把标签挤到折行。
 */
@Composable
fun ZhiTextDropdownChip(
    label: String,
    items: List<ZhiMenuItem>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    ZhiIconDropdownMenu(
        items = items,
        modifier = modifier,
        enabled = enabled,
        minHeight = PillHeight,
        // 宽度交给外层的 weight(1f)：这里放开最小宽，否则三列会被 Miuix 默认的
        // 40dp 下限顶出去
        minWidth = 0.dp,
        cornerRadius = PillHeight / 2,
        // 透明底：底色是输入器那块 FloatingToolbar，chip 自己不该再画一层
        backgroundColor = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painter = ZhiIcons.collapse,
                contentDescription = null,
                tint = scheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 2.dp).size(11.dp),
            )
        }
    }
}


/**
 * 文本输入框。转发到 Miuix [TextField] 的 **`TextFieldState`** 重载。
 *
 * ## 为什么不能直接用 `TextField(value = 字符串, …)` 那条重载
 *
 * 工程原先 22 处输入框全部走 `value: String` 那条旧重载，它在真机上暴露了两个
 * 缺陷，而且**看起来毫无关联、实则同源**：
 *
 * 1. **输入的字看不见**（和占位符一个色）。Miuix 里输入字的颜色是
 *    `textStyle.color.takeOrElse { LocalContentColor.current }`
 *    （源码 `TextField.kt` 的 `resolvedTextStyle`）。本工程的字阶只改
 *    `fontSize`、不动 color，于是它一路回落到 `LocalContentColor` ——
 *    而 **Miuix `Card` 会用 `colors.contentColor` 覆盖这个局部值**
 *    （`CardKt` 里 `CompositionLocalProvider(LocalContentColor provides
 *    colors.contentColor)`）。弹窗内容区（`Dialogs/RoleCardsOverlay` 那层用的
 *    [com.zhizhu.zhicode.compose.ui.dialogs.DialogShell]），暗色方案里它是
 *    **`#666666`**；占位符 `onSecondaryContainer` 是 `#7C7C7C` ——
 *    在 `#242424` 底上两者分不出来。
 *
 * 2. **光标停在第一个字母前面，输入顺序错乱**（输入 `123` 显示 `231`）。
 *    旧重载内部是 `remember {}`（无 key）建一个 `TextFieldValue`，选区取默认值
 *    `TextRange.Zero`；之后每次外部文本变化只 `copy(text = 新值)`，
 *    **选区原样保留**（实测 Compose `BasicTextFieldKt` 字节码：建值与
 *    `copy$default` 两处的 mask 都是 6，selection 都走默认）。于是光标恒在
 *    索引 0，输入法按「光标在 0」提交，字符就插到了最前面：先 `1`，
 *    再把 `23` 插到 0，得 `231`。
 *
 * 所以改用 `TextFieldState` 重载（Miuix 0.9.4 文档列在首位的现代 API），
 * 并守两条纪律：**外部改值**用 [setTextAndPlaceCursorAtEnd]（光标落末尾，
 * 不是 0）；**回传**走 `snapshotFlow`，不把渲染时机与状态时机绑在一起。
 *
 * 参数只保留各调用点真正用到的那些，其余交给 Miuix 默认值。
 */
@Composable
fun ZhiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    useLabelAsPlaceholder: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    /**
     * 默认 **false**，与 Miuix 原默认值一致。
     *
     * 不能默认 true：本工程有几处输入框（输入器、反馈、自由输入）**不写**
     * `singleLine` 而是靠 `minLines`/`maxLines` 表达多行，默认 true 会把它们
     * 变成单行。凡是单行的地方，调用点本来就都显式写了 `singleLine = true`。
     */
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    /** 传 null 用主题的正文样式（只补一个可见的文字色）。 */
    textStyle: TextStyle? = null,
    /**
     * 光标颜色。默认取主题的 primary，而**不是** Miuix 的默认值。
     *
     * Miuix `TextField` 的光标默认画成 `SolidColor(colors.borderColor)`
     * （核过 0.9.4 源码），而本工程有输入框为了"只留外层方角框"把
     * `borderColor` 设成了透明 —— 那一下连光标也一起透明了，
     * 表现就是"发送栏没有光标"。所以这里显式给一个与描边无关的颜色，
     * 以后再有透明描边的输入框也不会复现。
     */
    cursorBrush: Brush = SolidColor(MiuixTheme.colorScheme.primary),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    interactionSource: MutableInteractionSource? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val fieldState = remember { TextFieldState(value) }

    // 外部改值：只在真不一致时写回，并把光标放到末尾。
    // 少了这一步，点列表填值之后光标会留在旧位置，用户接着输入就插错地方
    // ——即上面第 2 个缺陷的来源。
    LaunchedEffect(value) {
        if (fieldState.text != value) fieldState.setTextAndPlaceCursorAtEnd(value)
    }

    // 内部改值回传给状态层。snapshotFlow 自身只在值真的变化时发射，
    // 所以外部写回不会被再次回传而打成循环。
    // （`TextFieldState.text` 是 `CharSequence`，这里显式转成 `String`。）
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect { onValueChange(it) }
    }

    val base = textStyle ?: MiuixTheme.textStyles.main
    TextField(
        state = fieldState,
        modifier = modifier,
        insideMargin = insideMargin,
        colors = colors,
        cornerRadius = cornerRadius,
        label = label,
        useLabelAsPlaceholder = useLabelAsPlaceholder,
        enabled = enabled,
        readOnly = readOnly,
        // takeOrElse：调用点若显式指定了颜色就尊重它，否则补一个在深浅两套里
        // 都看得清的文字色。
        textStyle = base.copy(color = base.color.takeOrElse { scheme.onSurface }),
        keyboardOptions = keyboardOptions,
        lineLimits = if (singleLine) {
            TextFieldLineLimits.SingleLine
        } else {
            TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines)
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
    )
}

/**
 * Miuix 复选设置行的稳定转发。
 *
 * 选择列表优先使用官方 [CheckboxPreference]，让标题、摘要、点击语义与复选框
 * 的位置都由 Miuix 统一处理。调用点只负责把业务状态接到回调上。
 */
@Composable
fun ZhiCheckboxPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    CheckboxPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        startAction = startAction,
        checkboxLocation = CheckboxLocation.End,
        enabled = enabled,
    )
}

/**
 * 表单校验提示（表单里唯一该用的错误行）。
 *
 * <p>两条纪律，都是用户实测反馈出来的：
 *
 * <ol>
 *   <li><b>没碰过的字段不飘红。</b>原先一打开表单就是一片红字（「请填写名称」
 *       「必须填写启动命令」）—— 用户还没开始填就被指责，看上去像页面坏了。
 *       [touched] 由调用点在**用户真的改过这一项**时置真。</li>
 *   <li><b>横向内缩必须与同组输入框一致（12dp）。</b>原先各处只有 `top = 4.dp`，
 *       于是错误行比它上面的输入框、比 `SmallTitle` 都往左凸出一截 ——
 *       表单左边缘参差不齐，看着很不舒服。四处调用点当初各写了一份，
 *       所以这里收成一个组件：以后不会再有第四个缩进值。</li>
 * </ol>
 */
@Composable
fun ZhiFieldError(message: String?, touched: Boolean = true) {
    if (message == null || !touched) return
    Text(
        text = message,
        color = MiuixTheme.colorScheme.error,
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * 手指位置的**只读**观察者。
 *
 * ## 为什么不用「换掉手势」的办法
 *
 * 要让长按菜单从手指处长出来就得拿到手指坐标，而 Miuix `Card` 的 `onLongPress`
 * 不给坐标。直觉做法是把手势换成
 * `detectTapGestures(onLongPress = { offset -> … })` —— 但那会**顶掉**
 * 组件自己的 `combinedClickable`，连带丢掉两样东西：
 *
 * - **点击行为**。侧栏那一条是「点击打开会话 + 长按出菜单」，两者都要在；
 * - **无障碍语义**。长按动作不再是组件声明的，而是一个裸手势。
 *
 * 所以这里走 `PointerEventPass.Initial`：**先于子组件看到事件，但绝不消费**。
 * 事件原封不动继续下传，`Card` 的 `combinedClickable` 完全不受影响。
 *
 * ⚠️ **绝不要在这里调用 `consume()`**。加了就会把点击与长按一起吞掉，
 * 而界面只是"点了没反应"，没有任何编译错误 —— `AnchoredMenuStructureTest`
 * 专门钉住这一条。
 *
 * ## 为什么写普通字段而不是 `State`
 *
 * 回调在**每次指针移动**（含滚动）时都会触发。若写进 `mutableStateOf`，滚动一屏
 * 就要重组几百次。这里只更新一个普通对象，值在长按回调触发时读一次即可 ——
 * 全程零重组。
 */
fun Modifier.zhiObservePointer(onPosition: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Initial：先于子组件拿到事件；不消费，于是子组件照常处理。
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.lastOrNull() ?: continue
                onPosition(change.position)
            }
        }
    }

/**
 * 手指位置的**普通**持有对象（不是 `State`，理由见 [zhiObservePointer]）。
 *
 * 长按是「先按下、再等待」，所以触发时读到的是**最后一次指针事件**的位置 ——
 * 对长按来说就是手指按住不放的那个点。
 */
class FingerPosition {
    /** 最近一次指针事件的位置；从未收到事件时为 `Offset.Unspecified`。 */
    var value: Offset = Offset.Unspecified
        internal set

    internal fun record(position: Offset) {
        value = position
    }
}

/**
 * 长按动作菜单用的**手指位置追踪器**：[modifier] 挂到条目上，[offset] 在长按
 * 触发时读出手指位置。
 *
 * 分成两个成员是刻意的：
 * - [modifier] 是**只读观察者**（见 [zhiObservePointer]），只写普通字段，
 *   所以滚动期间不产生重组；
 * - [offset] 在**触发时**才把 px 换算成 `DpOffset`，所以滚动期间连单位换算都没有；
 *   尚未收到过指针事件时返回 null，调用点据此退回「贴条目锚定」。
 */
class FingerTracker internal constructor(
    private val holder: FingerPosition,
    private val density: Density,
    /** 观察者修饰符，由调用点挂到被长按的那一项上。 */
    val modifier: Modifier,
) {
    /** 手指位置（相对挂载的那一项）；从未收到指针事件时为 null。 */
    fun offset(): DpOffset? {
        val position = holder.value
        if (position == Offset.Unspecified) return null
        return with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
    }
}

/** 记住一个 [FingerTracker]。把它的 [FingerTracker.modifier] 挂到条目上即可。 */
@Composable
fun rememberFingerTracker(): FingerTracker {
    val density = LocalDensity.current
    val holder = remember { FingerPosition() }
    val observer = remember {
        Modifier.zhiObservePointer { position -> holder.record(position) }
    }
    return remember(density, observer) { FingerTracker(holder, density, observer) }
}

/**
 * **贴住某一项弹出**的动作菜单（长按菜单）。
 *
 * ## 为什么用它，而不是居中对话框
 *
 * 长按一条消息 / 一条会话时，"会弹在屏幕正中"与手指所在的位置完全无关，
 * 而动作又是针对**那一项**的 —— 位置与语义对不上。所以改成 Miuix 的下拉
 * 菜单组件：把它放进被长按那一项的布局里，它就会锚在那一项上弹出；
 * 再配合 [fingerOffset]，就能锚在**手指**上。
 *
 * ## 转发到 `OverlayDropdownPopup`
 *
 * 上游源码（`miuix-preference/.../popup/OverlayDropdownPopup.kt`）对这个组件的
 * 说明里明确写了「Entries without selection state **can be used as action menus**」，
 * 它就是为动作菜单准备的：分组分隔线、按压触感、点击后收起都由它负责。
 *
 * ## `fingerOffset` 是怎么让面板跟手的
 *
 * 面板位置不是我们算的。Miuix 的 `ListPopupLayout` 用一个零尺寸 `Spacer` 的
 * `parentLayoutCoordinates.positionInWindow()` 取**调用处那一层**的窗口坐标
 * 当作锚点边界（核过 v0.9.4 源码；`OverlayDropdownPopup` **没有** popupModifier 参数）。
 * 所以只要给这里的外层 [Box] 加位移，锚点就跟着动、面板自然跟过去，
 * 并且仍然保留库自带的「下方空间不足则翻到上方」与「贴边内收」。
 *
 * 用 `absoluteOffset` 而不是 `offset`：手指位置是从指针事件里读出来的**绝对**像素，
 * 不该在 RTL 布局下被镜像。
 *
 * ⚠️ 偏移为空时**不要**施加 0 位移：那会把锚点拉回条目左上角，反而跑偏。
 * 为空就什么都不加，退回「贴条目锚定」。
 *
 * ⚠️ **`collapseOnSelection` 必须显式传 true**。
 * 它的默认值是 `entries.size <= 1`，而本工程恰好只建**一个** entry
 * （一个 entry 装全部动作），所以默认值这次会等于 true —— 但这是**巧合**：
 * 万一以后按分组拆成两个 entry，默认值就变成 false，表现是"点完不收起"，
 * 而那种 bug 不会编译失败。显式写出来，让意图不依赖入口数量。
 *
 * `renderInRootScaffold = true`（与 Miuix 默认一致）：弹层渲染在最外层
 * Scaffold 的弹出宿主里，所以能在整个屏幕范围内定位与绘制，不受局部裁剪影响。
 *
 * ## `open` 而不是 `if`：退出动画需要浮层常驻
 *
 * Miuix 的弹层退出动画在 `ListPopupLayout` 内部（`fractionProgress` / `alphaProgress` /
 * `dimProgress` 三个 `Animatable`），而那里有一句
 * `if (!show && !internalVisible.value) return` —— **先播完退出才 return**。
 * 所以调用方不能写 `if (open) { ZhiAnchoredActionMenu(...) }`：组件一被移除，
 * 这三个 `Animatable` 随 composition 一起走，退出根本来不及跑，表现是硬切。
 *
 * 传 `open = false` 即可：浮层还在 composition 里，自己把退场播完（之后内部
 * 直接 return，不占开销）。长按那一项的宿主记得用 `rememberLastNonNull` 兜住
 * 退出期间已经变 `null` 的载荷（见 `ChoicePicker` / `menuAt` 等调用点）。
 *
 * ## 调用点
 *
 * 只应由"被长按的那一项"调用（见 `ChatList` 的每项 Box 与 `Sidebar` 的 `SessionRow`），
 * 这样锚点天然就是那一项，手指偏移也是相对那一项量的，两者不会错位。
 */
@Composable
fun ZhiAnchoredActionMenu(
    /** 亮着就弹、灭掉就退。**不要**用 `if` 包住整个组件，见上面的说明。 */
    open: Boolean,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 手指位置（相对被长按的那一项）。为空则退回「贴条目锚定」。 */
    fingerOffset: DpOffset? = null,
) {
    if (labels.isEmpty()) return
    // 每次重组重建即可：OverlayDropdownPopup 内部用 rememberUpdatedState(entries)
    // 持有它，所以不会因为新实例而丢状态；反过来若用 remember 缓存，
    // onClick 里捕获的 onSelect 就有过期风险（这个回调会随重组变化）。
    val entries = listOf(
        DropdownEntry(
            items = labels.mapIndexed { index, label ->
                DropdownItem(text = label, onClick = { onSelect(index) })
            },
        ),
    )
    // 锚点跟着手指走：位移加在**这一层**（弹层取的就是它的父坐标）。
    val anchorModifier = if (fingerOffset == null) {
        Modifier
    } else {
        Modifier.absoluteOffset(x = fingerOffset.x, y = fingerOffset.y)
    }
    Box(modifier.then(anchorModifier)) {
        OverlayDropdownPopup(
            entries = entries,
            show = open,
            onDismiss = onDismiss,
            onDismissFinished = {},
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
            collapseOnSelection = true,
        )
    }
}

/**
 * 人类可读的字节数 —— **已经搬走了**。
 *
 * <p>它原先叫 `zhiFormatSize` 住在这里，而 `FileChrome.kt` 里还另有一份
 * 字节完全相同的 `formatFileSize`（注释写着"三个使用者，没必要各写一份"，
 * 自己却是重复的那一份）。现在收成唯一一份：[com.zhizhu.zhicode.compose.model.FileFormat.size]。
 *
 * <p>搬去 `model/` 而不是留在这里，是因为 ViewModel 也要用它
 * （`"已附加：x（1.2 KB）"`）—— 反向 import `ui` 是把界面层当工具库。
 * 顺带它就能被纯 JVM 单测覆盖（`FileFormatTest`，挂在 test-jvm-fast 快路径里）。
 */

/** 横向分隔线。转发到 Miuix [HorizontalDivider]（默认 0.75dp 的 HyperOS 细分线）。 */
@Composable
fun ZhiHorizontalDivider(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    if (color == Color.Unspecified) {
        HorizontalDivider(modifier = modifier)
    } else {
        HorizontalDivider(modifier = modifier, color = color)
    }
}

/** 纵向分隔线。转发到 Miuix [VerticalDivider]。 */
@Composable
fun ZhiVerticalDivider(modifier: Modifier = Modifier) {
    VerticalDivider(modifier = modifier)
}

/**
 * 通知条的语义档。
 *
 * - [INFO]   中性提示（灰底）
 * - [WARN]   警告/注意（琥珀底）—— 对话流里斜杠命令输出、计划审批结果那一类
 * - [ERROR]  错误（红底）
 */
enum class ZhiNoticeTone { INFO, WARN, ERROR }

/**
 * 通知条（一条贴边的横幅）：整宽、**纯色底 + 同色系文字、没有阴影**。
 *
 * ## 为什么要有这个分量
 *
 * 同一件事（"出错了"）在这份工程里原来有**三种长相**：
 *
 * - 沙箱页 `ErrorDetail`：一张 `surfaceContainer` 的 **Card**（深灰）配红字 ——
 *   看起来像一张普通卡片，只是里面的字恰好是红的；红色只出现在文字上，
 *   颜色面积太小，一眼扫过去不觉得"这是出事了"。
 * - 对话流 `ErrorCard`：左边一根 3dp 红竖条 + 红标题 + 正文。
 * - 对话流 `MessageBar` 的错误档：红底红字，但是一个**带投影的浮动工具栏**。
 *
 * 三者讲同一件事却长得不一样。现在统一成这一种**通知条**：
 * 整宽横幅、纯色底、无阴影，改配色只需要改这一处。
 *
 * ## 几何为什么是「小圆角 + 紧内边距」
 *
 * 圆角用 [ZhiRadius.inner]（10dp）而不是卡片的 14dp：通知条比卡片矮得多
 * （两三行文字），14dp 圆角配这点高度会显得"泡"起来。10dp 配 40dp 左右的高度
 * 观感是"有圆润感的横条"，这是参考图里那一条的样子。
 *
 * 内边距比卡片紧一档（竖直 8dp）：它是一条**通知**，不是一张内容卡，
 * 不该有卡片那种舒展的留白。
 *
 * ## 用 `errorContainer` / `error` 这一对，而不是 `error` / `onError*`
 *
 * `errorContainer` 在深色档是**暗红底**，`error` 是它上面那个**亮红字**；
 * 浅色档反过来（浅红底 + 深红字）。两端都是"底色的对比色"，
 * 所以不用按深浅色分叉。`onErrorContainer` 只有浅色档才对得上。
 *
 * @param text 正文。可以是多行（后端错误会带完整的启动阶段）。
 * @param title 可选标题（"错误"、"提示"）。空则不占一行。
 * @param tone 决定底色与文字色，见 [ZhiNoticeTone]。
 * @param content 正文的**自定义渲染**，给了它就不画 [text]。
 *
 *   ⚠️ 这个槽**刻意不把颜色传出去**：对话流的错误/提示正文要过 Markdown，
 *   而 Markdown 里每类块（标题/引用/代码/表格）都有自己的主题色。
 *   传一个"统一正文色"进去只会让调用方以为能覆盖，实际覆盖不了（代码块自带底色）。
 *   标题仍然由这里的 [titleColor] 统一着成该档的主色 —— 那才是"这是错误"的信号。
 */
@Composable
fun ZhiNoticeBar(
    text: String = "",
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: ZhiNoticeTone = ZhiNoticeTone.INFO,
    content: (@Composable () -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val titleColor = when (tone) {
        ZhiNoticeTone.ERROR -> scheme.error
        ZhiNoticeTone.WARN -> ZhiColors.amber()
        ZhiNoticeTone.INFO -> scheme.onSurfaceVariantSummary
    }
    val bodyColor = when (tone) {
        ZhiNoticeTone.ERROR -> scheme.error
        ZhiNoticeTone.WARN -> scheme.onSurface
        ZhiNoticeTone.INFO -> scheme.onSurfaceVariantSummary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        // 见上面「几何」那段：通知条比卡片矮，圆角跟着小一档。
        shape = RoundedCornerShape(ZhiRadius.inner),
        color = when (tone) {
            ZhiNoticeTone.ERROR -> scheme.errorContainer
            // 琥珀在主题里没有对应的容器色，用「琥珀按低透明度铺在表面色上」——
            // 这样深浅色两档都成立，也不写死一个只在深色下对的十六进制值。
            ZhiNoticeTone.WARN -> ZhiColors.amber().copy(alpha = 0.16f).compositeOver(scheme.surface)
            ZhiNoticeTone.INFO -> scheme.surfaceContainer
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = ZhiSpace.m, vertical = ZhiSpace.s)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (content != null) {
                content()
            } else if (text.isNotEmpty()) {
                Text(
                    text = text,
                    color = bodyColor,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                )
            }
        }
    }
}

/** 上下文用量细条。转发到 Miuix [LinearProgressIndicator]。 */
@Composable
fun ZhiUsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    height: Dp = Dp.Unspecified,
) {
    val scheme = MiuixTheme.colorScheme
    val barColor = if (color == Color.Unspecified) scheme.primary else color
    val background = if (trackColor == Color.Unspecified) scheme.surfaceContainerHighest else trackColor
    val colors = ProgressIndicatorDefaults.progressIndicatorColors(
        foregroundColor = barColor,
        backgroundColor = background,
    )
    if (height == Dp.Unspecified) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            progress = fraction.coerceIn(0f, 1f),
            colors = colors,
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            progress = fraction.coerceIn(0f, 1f),
            colors = colors,
            height = height,
        )
    }
}

/** 不确定态进度条（顶栏忙碌扫动条）。转发到 Miuix [LinearProgressIndicator]。 */
@Composable
fun ZhiIndeterminateBar(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val scheme = MiuixTheme.colorScheme
    val colors = if (color == Color.Unspecified) {
        ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = scheme.primary)
    } else {
        ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color)
    }
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth(),
        progress = null,
        colors = colors,
        height = 2.dp,
    )
}
