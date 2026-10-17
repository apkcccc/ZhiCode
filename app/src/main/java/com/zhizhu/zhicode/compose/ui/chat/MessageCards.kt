package com.zhizhu.zhicode.compose.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.engine.ToolText
import com.zhizhu.zhicode.compose.model.ToolActions
import com.zhizhu.zhicode.compose.model.ChatImage
import com.zhizhu.zhicode.compose.model.ChatItem
import com.zhizhu.zhicode.compose.model.ErrorSummary
import com.zhizhu.zhicode.compose.model.ToolActivity
import com.zhizhu.zhicode.compose.model.ToolGrouping
import com.zhizhu.zhicode.compose.model.LiveOutput
import com.zhizhu.zhicode.compose.model.ToolKind
import com.zhizhu.zhicode.compose.state.WorkspaceViewModel
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.ui.ZhiHorizontalDivider
import com.zhizhu.zhicode.compose.ui.ZhiIconDropdownMenu
import com.zhizhu.zhicode.compose.ui.ZhiMenuItem
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiImageRow
import com.zhizhu.zhicode.compose.ui.ZhiImageViewer
import com.zhizhu.zhicode.compose.ui.ZhiMarkdown
import com.zhizhu.zhicode.compose.ui.ZhiNoticeBar
import com.zhizhu.zhicode.compose.ui.ZhiNoticeTone
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 对话流里的卡片全部改用 Miuix [Card]：
 * 圆角、按压下沉(pressFeedbackType)、按下态与主题色解析都交给 Miuix，
 * 不再手写 `Box + clip + background + pressScale`。
 *
 * 用 Card 的地方**不要**再叠按压缩放修饰符，否则会和 Miuix 的按压反馈打架。
 */
private val BubbleMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
private val MessageMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
/** Markdown above this size is previewed first; the full body remains in the model. */
private const val MaxInitialMarkdownChars = 120_000

/**
 * 用户气泡宽度占可用宽的比例（**上限**，不是固定值）。
 *
 * 用 `widthIn(max = …)` 而不是 `fillMaxWidth(0.86f)`：后者是**强制**占 86%，
 * 于是内容只有「1」这种短消息也会被撑成几乎整行宽的蓝条，看起来像一条色带
 * 而不是一个气泡。改成上限之后，短消息收成合适宽度、长消息仍然封顶。
 */
private val BubbleMaxWidthFraction = 0.86f
/**
 * 折叠组表头的内边距。
 *
 * 反编译版 `addCollapsedToolActivity` 的组头是 `setPadding(dp(7), dp(3), dp(4), dp(3))`，
 * 而且**整块没有任何背景** —— 它就是"图标 + 粗体标签 + ⌄"一行，层级靠成员缩进
 * 与 `⎿` 副行体现。这里取同量级。
 */
private val GroupMargin = PaddingValues(start = 7.dp, top = 3.dp, end = 4.dp, bottom = 3.dp)
private val RowMargin = PaddingValues(horizontal = 9.dp, vertical = 7.dp)

/** 空态，对应原版 addEmptyState()。 */
@Composable
fun EmptyState() {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 82.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "想让 ZhiCode 做什么？",
            color = scheme.onBackground,
            fontSize = MiuixTheme.textStyles.title1.fontSize,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "ZhiCode 可以读取项目、编辑文件、运行命令，并在内置 Termux 环境中验证修改。",
            color = scheme.onBackgroundVariant,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
        )
    }
}

/**
 * 列表顶部「上面还有更早的历史」的占位。
 *
 * 恢复一条会话时只读最近 [com.zhizhu.zhicode.compose.data.SessionReader.PAGE_MESSAGES] 条，
 * 往下有两种补页方式：**顶部下拉**（Miuix `PullToRefresh`，见 `ChatList`）与
 * **滚到顶自动补**。这一条既是提示（上面不是没有内容，而是还没取），也是状态
 * （正在取），否则用户滚到顶只会看到内容突然停住，像是记录缺了一块。
 */
@Composable
fun OlderLoader(loading: Boolean) {
    val scheme = MiuixTheme.colorScheme
    // 文案里写出**具体条数**：只说"更早的消息"时用户不知道一次补多少，
    // 也就无法判断"要不要为了找某一条一直往下拉"。
    val count = com.zhizhu.zhicode.compose.data.SessionReader.PAGE_MESSAGES
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, end = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loading) "正在加载更早的 $count 条…" else "下拉或继续上滑，加载更早的 $count 条",
            color = scheme.onBackgroundVariant,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        )
    }
}

/**
 * 「已经到会话开头」的终点提示。
 *
 * 没有它时，用户滚到顶会看到 `OlderLoader` **消失**——而那与「这一页刚好到头」
 * 长得一模一样，于是会反复下拉确认。一条两行的终点说明比"什么都没有"便宜得多。
 */
@Composable
fun TranscriptStartHint() {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, end = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "已经到会话开头",
            color = scheme.onBackgroundVariant,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
        )
    }
}

/**
 * 列表末尾的「回到最新」。
 *
 * 常驻窗口有上限（`WorkspaceViewModel.maxResidentItems`），往上翻到一定深度之后
 * **最新那一段会被淘汰出内存**。这时列表的"最后一条"已经不是最新消息了，
 * 所以需要一个明确的返回入口 —— 没有它，用户往下滚只会滚到历史中间，
 * 看起来像"后面的消息丢了"。
 *
 * 放在列表**末尾**而不是做悬浮按钮：末尾正好是用户滚到底时看到的位置，
 * 而悬浮按钮会和输入器上方的悬浮任务卡抢同一块地方。
 */
@Composable
fun JumpToLatest(onJumpToLatest: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp, end = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onJumpToLatest,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.primary,
            shape = RoundedCornerShape(ZhiRadius.inner),
        ) {
            Text(
                text = "回到最新",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 用户消息气泡，右对齐。长按触发操作菜单。 */
@Composable
fun UserBubble(item: ChatItem, onLongPress: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    // 放大查看的当前图。放在**气泡内部**而不是提到 AppScaffold：
    // 它是一个纯本地 UI 状态（点了哪张图），提升上去只会让上层多一个字段，
    // 而这个浮层本身是 OverlayDialog，画在主窗口里，不存在被气泡裁掉的问题。
    var viewing by remember { mutableStateOf<ChatImage?>(null) }

    // 气泡位于 LazyColumn item 内，不再用 BoxWithConstraints 触发测量期子组合。
    // widthIn 的固定上限交给父级列表宽度处理，窄屏/宽屏都保持内容自适应。
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Card(
            // ⚠️ 必须是 onLongPress，**不要**写回 onClick。
            // 这里曾经是 `onClick = onLongPress`，于是短按一下就弹菜单，
            // 而函数文档与 AssistantCard 都是「长按」—— 同一段代码自相矛盾。
            // Miuix `Card` 内部用 combinedClickable 且 `isClickable = hasOnClick || hasLongPress`
            // （核过 v0.9.4 源码），所以只给 onLongPress 是被正确支持的。
            onLongPress = onLongPress,
            // 宽度 = 「内容自适应，上限 86%」：先按可用宽取 86% 作为**上限**，
            // 再让内容自己决定实际宽度。
            // 之前是 `fillMaxWidth(0.86f)` —— 那是**强制** 86%，于是像「1」这样的
            // 短消息也会撑成一条几乎整行宽的蓝条。见根部的 BoxWithConstraints。
            modifier = Modifier.widthIn(max = 420.dp),
            cornerRadius = ZhiRadius.floating,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.defaultColors(
                color = scheme.primary,
                contentColor = scheme.onPrimary,
            ),
            // ⚠️ 必须是 None，理由与 AssistantCard 完全相同：
            // 这张卡只有**长按**才有动作，而 Sink 是「按下即缩放」，
            // 短按也会看到整块气泡缩放一下、松手却没有反应 —— 像卡了点不动。
            pressFeedbackType = PressFeedbackType.None,
        ) {
            // 图片画在正文**上方**：一条「图 + 一句话」的消息，图是主体，
            // 说明文字在下面；反过来会让图看起来像附注。
            //
            // 空 body 时不要画那个空 Text：`Text("")` 仍会占一行行高，
            // 于是"只发图"的气泡下面会多出一条空隙。
            ZhiImageRow(images = item.images, onOpen = { viewing = it })
            if (item.body.isNotBlank()) {
                Text(
                    text = item.body,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        }

    // 放大查看：浮层挂在气泡之外（OverlayDialog 画在主窗口里）。
    // 放在 BoxWithConstraints 之后而不是里面，是为了不让它参与气泡的宽度测量 ——
    // 否则 `maxWidth` 会把浮层也算进去，气泡宽度可能被它影响。
    ZhiImageViewer(image = viewing, onDismiss = { viewing = null })
}

/**
 * 助手消息：与用户气泡一样套一层卡片底色，占满宽度、配色中性。
 *
 * 结构（自上而下）：思考面板 → 正文 → 上下文页脚。
 *
 * 原版 `renderAssistant` 只给用户气泡和工具行上底色，这里是**有意偏离**：
 * 只有一方有框时，AI 的消息看起来像没有归属的裸文本。操作入口是整块卡片的
 * 长按（原先那个独占一行的 ⋯ 已移除，见函数内注释）。
 */
@Composable
fun AssistantCard(
    item: ChatItem,
    onToggleThinking: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var showFullBody by remember(item.id) { mutableStateOf(false) }
    val bodyTooLong = item.body.length > MaxInitialMarkdownChars
    val markdownBody = if (bodyTooLong && !showFullBody) {
        item.body.take(MaxInitialMarkdownChars)
    } else {
        item.body
    }
    // 与 UserBubble 用同一套容器参数（同样的 ZhiRadius.card 圆角与 BubbleMargin 内边距），
    // 只是配色中性、占满宽度。
    //
    // 此前这里是 `Surface(color = Color.Transparent)`，也就是**没有框**：于是只有用户的
    // 消息有气泡、AI 的消息像裸文本贴在背景上，双方看起来不对等。
    //
    // 那个独占一行的 ⋯（"消息操作"）同时去掉：它是一个高 26dp 的满宽 Row，位置在每条
    // AI 消息的**最顶部**，所以看上去像粘在上一条消息的下沿、并和上一条的按压高亮连成
    // 一片。消息操作仍然可用 —— 整块卡片本身就是入口（onLongPress）。
    Card(
        onLongPress = onLongPress,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
        cornerRadius = ZhiRadius.card,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        // 助手正文使用极轻的容器底，保留阅读连续性，同时与用户气泡形成清晰层级。
        colors = CardDefaults.defaultColors(
            // 使用当前主题的独立容器层，不再把浅色 surface 以低 alpha 叠在背板上。
            // 低 alpha 在浅色模式下会把卡片压成发灰的半透明块，深色模式也会
            // 随背景变化而失去稳定的层次。
            color = if (ZhiColors.isDark()) scheme.surfaceContainer else ZhiColors.cardSurface(),
            contentColor = scheme.onSurface,
        ),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(2.dp)
                .height(34.dp)
                .background(scheme.primary, RoundedCornerShape(1.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                // clip 在 animateContentSize **外侧**：思考面板展开/收起时文字
                // 按自然高度绘制、被逐帧露出来（详见 ToolGroupCard 的注释）。
                .clipToBounds()
                // 尺寸动画**流式期间也挂**（用户要的「流式正文平滑增长」）。
                //
                // ## 这里改过，两个方向的理由都要留着
                //
                // 原来写的是 `if (item.streaming) Modifier else animateContentSize(...)`，
                // 理由是：正文每 32ms（DELTA_MERGE_MS）长高一次，会反复触发尺寸动画 →
                // 整张卡在整条回复期间持续"测量→布局→动画"。
                //
                // 但那个理由**站不住**，因为 `animateContentSize` 不是"每次变化都重放一遍
                // 200ms 的补间"：目标一变它就从**当前动画值**继续跑到新目标。
                // 真正的后果只是尺寸比内容**略微滞后**（跟得慢一点），而不是
                // "重新触发风暴"。而流式正文增长的本身就是"内容每 32ms 多一行"，
                // 不挂动画时那是**一帧一跳**的硬跳，正是用户报的「文字是直接没动画」。
                //
                // ⚠️ 这是本轮**唯一可能反向影响性能**的一项：想清楚再动它。
                // 代价确实是"流式期间每一帧都在做一次测量+布局"，这是**想要平滑增长的
                // 必然成本**，不是 bug。若真机上真的出现"流式一顿一顿"，回退点就是
                // 这一处 —— 把它改回按 item.streaming 分支，其余几项动画不受影响
                // （它们各自独立）。守卫 `MarkdownStreamingTest` 会把回退方向的选择记下来。
                // Streaming text changes frequently. Animating every intermediate height
                // keeps a second layout animation alive for the entire response and makes
                // long replies compete with Markdown measurement; the settled card still
                // gets the smooth expand/collapse transition.
                .then(if (item.streaming) Modifier else Modifier.animateContentSize(animationSpec = ZhiMotion.sizeSpec)),
        ) {
            if (item.thinking.isNotEmpty() || item.processSteps.isNotEmpty()) {
                ThinkingPanel(item = item, onToggle = onToggleThinking)
            }
            // 正文走 Markdown（标题/列表/代码块/表格/引用/链接…）。
            // 流式光标用一个独立的 Text 尾随，而不是拼进 Markdown 源里 ——
            // 拼进去的话光标会被当成行内内容参与解析（例如紧跟在 ` 后面会变成代码）。
            //
            // `streaming` 传下去，Markdown 层据此只在**跨过块边界**时重解析前缀
            // （见 settledPrefixLength）：不传的话每 32ms 会重建整篇。
            ZhiMarkdown(
                source = markdownBody,
                bodyFontSize = MiuixTheme.textStyles.main.fontSize,
                streaming = item.streaming && !bodyTooLong,
            )
            if (bodyTooLong && !showFullBody) {
                Surface(
                    onClick = { showFullBody = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = Color.Transparent,
                    contentColor = scheme.primary,
                ) {
                    Text(
                        text = "正文较长，已显示前 ${MaxInitialMarkdownChars / 1000} KB · 点按展开全部",
                        color = scheme.primary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    )
                }
            }
            if (item.streaming) {
                // 流式光标呼吸闪烁：之前是一块静止的字符，文本区里唯一「活着」的
                // 记号却不动。周期 = 淡入 300ms 去程 + 300ms 回程。
                //
                // ⚠️⚠️ 这里**不能**写 `val cursorAlpha by cursor.animateFloat(...)`。
                //
                // 那个 `by` 就是 `getValue()` —— 它在**组合期**把动画的当前值读出来，
                // 而这是个无限动画：值每帧都变 → **整张 AssistantCard 每帧重组一次**。
                // 本文件那句注释原先写着"alpha 走 draw 层，不重组"，**与事实相反**：
                // 走 draw 层的只有 `graphicsLayer { alpha = … }` 这个 lambda，
                // 而喂给它的 `cursorAlpha` 早就把读操作放在了组合里。
                //
                // 正确写法：保留 `State<Float>` 本身（**不 `by`**），在 `graphicsLayer`
                // 的 lambda 里读 `.value` —— `graphicsLayer` 的 lambda 在**绘制阶段**求值，
                // 于是每帧只让这一层的绘制属性失效，**不触发重组**。
                //
                // 这是实测到的真问题：探针记录过 `recompose/s ChatArea=69 ChatList=64`
                // （60Hz 下就是每帧一次）。参考实现（GetStream 的 `AITypingIndicator`）
                // 用的是同一套 `rememberInfiniteTransition` + `graphicsLayer{alpha=}`，
                // 差别在它把指示器放在**独立 item** 里，所以只有那几个点付代价；
                // 我们放在正文卡里，代价就是整张卡。
                val cursor = rememberInfiniteTransition(label = "stream-cursor")
                val cursorAlpha = cursor.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(ZhiMotion.FADE_IN_MILLIS, easing = SinOutEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "stream-cursor-alpha",
                )
                Text(
                    text = "▍",
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .graphicsLayer { alpha = cursorAlpha.value },
                )
            }
                // 上下文页脚随高度出现/收回，不让文字单独淡入淡出。
            AnimatedVisibility(
                visible = !item.streaming && item.contextTokens >= 0,
                enter = expandVertically(animationSpec = ZhiMotion.sizeSpec, expandFrom = Alignment.Top),
                exit = shrinkVertically(animationSpec = ZhiMotion.sizeSpec, shrinkTowards = Alignment.Top),
            ) {
                ContextFooter(
                    tokens = item.contextTokens,
                    window = item.contextWindow,
                )
            }
        }
        }
    }
}

/**
 * 上下文页脚，复刻原版 `addContextFooter`（`MainActivity.java:2152-2166`）：
 * `上下文 X / Y · 剩余 N%`，等宽小字，并按占用率换色（≥90% 红 / ≥72% 强调色）。
 */
@Composable
private fun ContextFooter(tokens: Int, window: Int) {
    val scheme = MiuixTheme.colorScheme
    val safeWindow = if (window > 0) window else 200_000
    val usedPct = Math.round(tokens * 100.0 / safeWindow).toInt().coerceIn(0, 999)
    val remaining = (100 - usedPct.coerceAtMost(100)).coerceAtLeast(0)
    val color = when {
        usedPct >= 90 -> ZhiColors.red()
        usedPct >= 72 -> scheme.primary
        else -> scheme.onSurfaceVariantSummary
    }
    val label = "上下文 " + WorkspaceViewModel.formatTokens(tokens) + " / " +
        WorkspaceViewModel.formatTokens(safeWindow) + " · 剩余 " + remaining + "%"
    Text(
        text = label,
        color = color,
        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 5.dp, end = 3.dp),
    )
}

/** 思考过程折叠面板，对应原版 refreshAssistantThinking()。 */
@Composable
private fun ThinkingPanel(item: ChatItem, onToggle: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        // 表头点击走 Miuix Surface(onClick)，不再手写 Modifier.clickable
        Surface(
            onClick = onToggle,
            color = Color.Transparent,
            contentColor = scheme.primary,
        ) {
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = if (item.thinkingExpanded) ZhiIcons.collapse else ZhiIcons.expand,
                        contentDescription = if (item.thinkingExpanded) "折叠思考过程" else "展开思考过程",
                        tint = scheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "思考过程" + (if (item.streaming) " · 进行中" else ""),
                        color = scheme.primary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        // 展开/收起只改变内容高度，不再交叉淡化两份文本；旧内容会从顶部
        // 逐帧收回，新内容也从同一条边缘展开，和工具组保持同一交互模型。
        AnimatedContent(
            targetState = item.thinkingExpanded,
            transitionSpec = {
                expandVertically(animationSpec = ZhiMotion.sizeSpec, expandFrom = Alignment.Top) togetherWith
                    shrinkVertically(animationSpec = ZhiMotion.sizeSpec, shrinkTowards = Alignment.Top)
            },
            label = "thinking-panel",
        ) { expanded ->
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (item.processSteps.isNotEmpty()) {
                        item.processSteps.forEach { step ->
                            Text(
                                text = "· $step",
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                            )
                        }
                    }
                    if (item.thinking.isNotEmpty()) {
                        Text(
                            text = item.thinking,
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                        )
                    }
                }
            } else if (item.thinking.isNotEmpty()) {
                Text(
                    text = item.thinking,
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * 一个**工具批次**在对话流里的样子。
 *
 * ## 一个批次不是一张卡片
 *
 * 之前这里是"每个批次套一张「已运行 N 个工具」卡片"，于是单独一条 `Bash` 也被包进
 * 一张**带标题、带子标签、带计数徽章**的大卡里 —— 那三点是与参考实现不一致的地方
 * （IQ Code 的组标题直接说干了什么，而且只有"连续的 read/search 且 ≥2"才有组）。
 *
 * 现在的分工：
 * - 一个批次先按 [ToolGrouping] 切成若干段；
 * - **单条工具**各自一张卡（视觉上仍是一个框），但**没有标题行、没有子标签、没有徽章**
 *   —— 卡片只负责"这一条工具自成一块"，不冒充整批的汇总；
 * - **连续的 read/search 且 ≥2** → 折成一张卡，标题是
 *   「正在搜索 2 个模式、读取 3 个文件」这种**说明干了什么**的话。
 */
@Composable
fun ToolBatch(
    item: ChatItem,
    onToggleTool: (String) -> Unit,
    /** 参数是**那一组**的 groupKey（首成员 toolId）。 */
    onToggleGroup: (String) -> Unit,
    /**
     * 某一行的 `⋯` 菜单里**选中了一项**。两个参数：那一行的 toolId + 菜单文案。
     *
     * 这里以前是 `onActions: () -> Unit`，来自整组那一层 —— 于是点单个工具的 `⋯`
     * 弹出的是整组菜单，而且回调里根本不知道用户点的是哪一行。
     * 单个工具的动作（复制命令 / 复制输出 / 展开这一条）都作用在某一行上，
     * 所以行号必须传出去。
     *
     * 菜单**本身**由这一行自己画（Miuix 下拉菜单，见 [ToolRow]），这里只负责
     * 把"选中了哪一行的哪一项"交给上层去执行。
     */
    onToolAction: (String, String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val toolById = remember(item.tools) { item.tools.associateBy { it.id } }
    val segments = remember(item.tools) {
        ToolGrouping.group(item.tools.map { it.toGroupingEntry() })
    }

    /*
     * 运行中的秒表。
     *
     * `elapsedMs` 是跟着输出块推过来的（引擎按 chunk 回调进度），所以一个跑很久
     * 都不吐字的命令，标签会冻在最后一次进度的值上，看起来像卡死。
     * 这里按 500ms 续走一次（与参考实现的 `scheduleToolElapsedTicker` 同频），
     * 取「起点至今」与「引擎推送值」的**较大者**（见 `ToolActions.displayElapsedMs`，
     * 规则本身在纯逻辑层、有单测）。
     *
     * 整个批次共用同一个 `nowMs` 而不是每行各起一个 ticker：一次重组足够，而且同屏里
     * 各行的秒数不会因为 ticker 相位不同而看起来错开。
     *
     * **没有运行中的工具时 ticker 不排队** —— 否则一个后台死循环会一直持有重组。
     */
    val runningClock = rememberRunningClock(item.tools.any { !it.completed })

    // 放大查看的当前图。
    //
    // 与 `UserBubble` 同一条约定（那里写着「它是一个纯本地 UI 状态（点了哪张图），
    // 提升上去只会让上层多一个字段」）：宿主放在**工具卡自己**里，而不是提到
    // ChatList / AppScaffold。工具卡的预览图只有点开才有意义，没有任何跨项共享的需求。
    var viewing by remember { mutableStateOf<ChatImage?>(null) }

    // 段与段之间的间隔：单条卡与组卡各自带垂直留白，所以这里不再额外加 padding
    // （两边都加会让"两条命令之间"的缝比"命令与它的输出之间"还宽）。
    Column(modifier = Modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is ToolGrouping.Segment.Single -> {
                    val tool = toolById[segment.entry.toolId] ?: return@forEach
                    // ⚠️ 每一行都要 `key`，否则行内的 `remember`（展开态、下拉菜单的
                    // 展开态）是按**位置**归属的：工具是边跑边追加的，新工具插进来之后
                    // 位置会挪，于是"打开的菜单"和"展开的输出"会串到另一行上。
                    key(tool.id) {
                        // 单条工具各自一张卡（Miuix `Card`）。
                        //
                        // ## 这里来回改过两次，两次的理由都留着，免得下次又当成"改错了"
                        //
                        // 1. 最早每条工具都被套进"整批"那张大卡里 —— 单条 Bash 也顶着
                        //    「已运行 N 个工具」的假标题。错的是**假标题**，不是壳。
                        // 2. 后来把壳整个去掉，只留"内容自己那口更黑的井"当框，依据是用户
                        //    当时那句话：「底色比其他地方要黑的就是我说的预览框」。
                        // 3. **现在把壳加回来** —— 用户看过实际界面之后要的：「给 tools 加个框，
                        //    这样也好分辨东西」。理由和当初那条提交说的同一件事：命令行与
                        //    输出块直接浮在页面上时看不出边界，一屏几条命令会糊成一片。
                        //
                        // ⚠️ 这一版**不是**把第 2 步原样撤销：井的 `terminalSurface` 保留
                        // （它仍然就是用户认过的那个"预览框"），卡在外面**额外**给出本条工具的
                        // 边界。卡底 `cardSurface`(#2E2E2E) 与井底 `terminalSurface`(#0A0B0C)
                        // 差两档，是"浅卡里的深井"，不是两块同色的底叠在一起。
                        //
                        // 卡**不接管点击**：点击仍在 `ToolRow` 内部那个 `Surface` 上 ——
                        // 两层都挂 onClick 会互相抢，而且外面那层拿不到"展开这一条"的语义。
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                            cornerRadius = ZhiRadius.card,
                            insideMargin = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
                            colors = CardDefaults.defaultColors(
                                color = ZhiColors.cardSurface(),
                                contentColor = scheme.onSurface,
                            ),
                        ) {
                        ToolRow(
                            activity = tool,
                            nowMs = runningClock,
                            onToggle = { onToggleTool(tool.id) },
                            // 传**这一行**的 id：菜单内容与动作都按它算。
                            onToolAction = { label -> onToolAction(tool.id, label) },
                            onImageOpen = { viewing = it },
                        )
                        }
                    }
                }
                is ToolGrouping.Segment.Group -> {
                    val members = segment.members.mapNotNull { member -> toolById[member.toolId] }
                    if (members.isEmpty()) return@forEach
                    key(segment.key) {
                        ToolGroupCard(
                            members = members,
                            expanded = segment.key in item.expandedGroups,
                            onToggle = { onToggleGroup(segment.key) },
                            nowMs = runningClock,
                            onToggleTool = onToggleTool,
                            onRowAction = onToolAction,
                            onImageOpen = { viewing = it },
                        )
                    }
                }
            }
        }
    }

    // 放大查看：与 `UserBubble` 同一形态（全屏黑底 + `ContentScale.Fit` + 右上 ×），
    // 也同一条约定 —— 浮层挂在 `Column` **之外**，不参与列表项的宽高测量。
    // 没有预览时 `show = false`，它不画任何东西，所以这一行是零成本。
    ZhiImageViewer(image = viewing, onDismiss = { viewing = null })
}

/** 界面模型 → 分组判据。字段一一对应，于是两边不会各判一次"算不算候选"。 */
private fun ToolActivity.toGroupingEntry(): ToolGrouping.Entry = ToolGrouping.Entry(
    toolId = id,
    name = toolName,
    hint = hint,
    readRequests = readRequests,
    completed = completed,
    failed = failed,
)

/**
 * 折叠组卡片，对应原版 `addCollapsedToolActivity()` + `collapsedActivityLabel()`。
 *
 * 只由 [ToolBatch] 在"连续的 read/search 且 ≥2"时调用 —— 单条工具绝不走这里。
 */
@Composable
private fun ToolGroupCard(
    members: List<ToolActivity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    nowMs: Long,
    onToggleTool: (String) -> Unit,
    /** 传给每一行的回调（**带 toolId**，见 [ToolBatch] 的说明）。 */
    onRowAction: (String, String) -> Unit,
    /** 某一行里的预览图被点开（需要全屏查看）。宿主在 [ToolBatch]。 */
    onImageOpen: (ChatImage) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 这张卡片只需要"这一组"自己的判据，所以在这里重建一份 Segment.Group —
    // 判据仍然出自 ToolGrouping，不在这里另写一套计数。
    val group = remember(members) {
        ToolGrouping.Segment.Group(
            key = members.first().id,
            members = members.map { it.toGroupingEntry() },
        )
    }
    val done = ToolGrouping.isDone(group)
    val failed = ToolGrouping.hasFailure(group)

    // 折叠组**没有卡片底**（反编译版 `addCollapsedToolActivity`：组头就是"图标 + 粗体标签 +
    // ⌄"，靠成员缩进与 `⎿` 副行体现层级，整块没有任何背景）。
    //
    // ⚠️⚠️ 根容器必须是**竖向**的，这不是随便挑的：
    //
    //   · 官方那一处是 `linearLayoutVbox3 = vbox()` —— 竖向 `LinearLayout`；
    //   · 本轮之前这里是 `Box(...)`，而 **`Box` 是叠放**：于是「表头 + 副标题 +
    //     展开内容」三个兄弟节点被放在**同一个原点**上互相盖住。
    //
    // 它是怎么从竖向变成叠放的：为了去掉卡片底，把原来的 `Card(...)` 换成了 `Box(...)`。
    // Miuix 的 `Card` 内部就是 `Column(`（见 Card.kt），它**同时**提供了"竖向排列"与
    // "内边距"两件事；换成 `Box` 时只补回了内边距和动画的挂点，**竖向排列没人补**，
    // 而且不会有任何编译错误 —— 表现只是"工具卡的标题被内容压住、收起后文字对不上"。
    //
    // 这和文件面板那次「裸 `when` 换成 `AnimatedContent`」是同一类失误：
    // 换容器时只看了它"多"提供了什么，没看它**顺手**提供了什么。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // 表头点击走 Miuix Surface(onClick)：不再手写 Modifier.clickable。
        // 传的是"切换"：展开态由 `ChatItem.expandedGroups` 记账，这一层不去推目标状态。
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            contentColor = scheme.onSurface,
        ) {
        Row(
            // 反编译版组头：`setPadding(dp(7), dp(3), dp(4), dp(3))`
            modifier = Modifier.fillMaxWidth().padding(GroupMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 行首是**状态字形**，与单条工具行同一个口径：有失败 → 红，跑完 → 绿，
            // 否则强调色（参考实现：`failed>0 ? RED : (done ? GREEN : ACCENT)`）。
            //
            // 这里原来显示的是「正在运行工具 / 已运行 N 个工具」加一个计数徽章 ——
            // 那是**计数**，回答了"有几个"，却没回答"在干什么"。参考实现的组标题
            // 直接说干了什么（「正在搜索 2 个模式、读取 3 个文件」），计数也就在里面了。
            Icon(
                painter = when {
                    failed -> ZhiIcons.failed
                    done -> ZhiIcons.done
                    else -> ZhiIcons.pending
                },
                contentDescription = null,
                tint = when {
                    failed -> ZhiColors.red()
                    done -> ZhiColors.green()
                    else -> scheme.primary
                },
                modifier = Modifier.size(13.dp),
            )
            // 标题直接更新；组本身的展开/收起由内容高度过渡负责，避免标题和
            // 内容同时保留两套淡变文本。
            Text(
                text = ToolGrouping.label(group, batchDone = done),
                color = scheme.onSurface,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            // ⚠️ 这里**没有**折叠箭头，是刻意的（用户：「⌃/⌄ 箭头可以去掉，
            // 因为点击内容可以快速收回或展开」）。
            //
            // 整行表头本身就是 `Surface(onClick = onToggle)` —— 点哪儿都能展开/收起。
            // 再挂一个箭头等于用图标重复同一件事，而且它紧挨 `⋯`，
            // 两个小图形在右端互相挤（参考实现那一行也只到 `⋯` 为止）。
            //
            // 需要"方向指示"的地方（思考过程、MCP、技能列表）仍走
            // `ZhiIcons.expand` / `ZhiIcons.collapse`，那一对没有被删。
        }
        } // Surface(onClick) 表头
        Text(
            text = ToolGrouping.subtitle(group, expanded = expanded, batchDone = done),
            color = if (failed) ZhiColors.red() else scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 21.dp, top = 2.dp),
        )
        // 展开/收起：**内容自己**做高度动画。
        //
        // ⚠️ 以前是「外层 animateContentSize + `if (expanded) { Column { … } }`」。
        // 那条写法**展开时看着还行**（内层按自然高度绘制、被外层裁剪逐帧露出来），
        // 但**收起时是坏的**：`if` 一翻，内容当帧就被移出组合，于是只剩外层的框在
        // 缩小、里面已经是空的 —— 用户报的就是「工具卡收回，文字就应该跟着收回」。
        // 根因是"谁在动"不一致：框在动，内容不动。
        //
        // 现在内容自己就是动画的驱动者，展开与收起都由它负责，高度只有一个来源。
        // ⚠️ 所以外层**不能**再挂 animateContentSize（本轮已撤掉）：
        // 两层各挂一次会让外框按一条曲线、内层按另一条曲线走，
        // 看起来就是"文字在卡片里自己飘"——本文件里记过这个坑。
        //
        // `expandFrom` / `shrinkTowards = Alignment.Top`：内容长在表头**下面**，
        // 所以从顶边露出、朝顶边收回。用默认的 `Bottom` 会从底边往上长，
        // 看起来像内容在把卡片往上"顶"。
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = ZhiMotion.sizeSpec, expandFrom = Alignment.Top),
            exit = shrinkVertically(animationSpec = ZhiMotion.sizeSpec, shrinkTowards = Alignment.Top),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                members.forEach { tool ->
                    key(tool.id) {
                        ToolRow(
                            activity = tool,
                            nowMs = nowMs,
                            onToggle = { onToggleTool(tool.id) },
                            onToolAction = { label -> onRowAction(tool.id, label) },
                            onImageOpen = onImageOpen,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个工具行，复刻原版 `addToolCard()`（`MainActivity.java:2344-2430`）。
 *
 * 与原版一致的要点：
 * - 整行左缩进 20dp、行高 25dp；
 * - 行首是**状态字形**：`✓`(完成) / `×`(失败) / `●`(运行中) / `○`(等待授权)；
 * - 名称 11.5sp 粗体；摘要 10.5sp 等宽、单行省略、`weight(1f)`；
 * - `+N` / `−N` 是**纯文字着色**（9.5sp 等宽），不套底色胶囊；
 * - 右端 `⋯` 打开操作菜单（**Miuix 下拉菜单**，与输入器底排同一组件），
 *   `⌄` / `⌃` 折叠展开（仅完成且有详情时出现）；
 * - Bash 的命令**不放标题行**（原版注释：否则命令会出现两次），改为独立下一行；
 * - 折叠态只显示 `⎿ 摘要`（等宽、缩进 23dp），展开态才渲染全量输出。
 */
@Composable
private fun ToolRow(
    activity: ToolActivity,
    nowMs: Long,
    onToggle: () -> Unit,
    /** 这一行的菜单里选中了一项（参数是菜单文案）。 */
    onToolAction: (String) -> Unit,
    /** 展开态里的预览图被点开。 */
    onImageOpen: (ChatImage) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val isCommand = activity.kind == ToolKind.COMMAND
    // 引擎单独算好的统一 diff（写文件类工具才有）。它是**另一块井**，
    // 和 output 并列显示，不是二选一 —— 见下面 EXPANDED 分支。
    val hasDiff = activity.diff.isNotBlank()
    // 预览也算"有详情"：一个只带截图、自述为空的工具同样应该能展开看那张图。
    // 少了 `previews` 这一项，它会落在 `toolStatusRegion` 的 QUIET 分支上，
    // 展开之后什么都不画 —— 图被静默吞掉，而且不会编译失败。
    val hasDetails = activity.output.isNotBlank() || hasDiff || activity.previews.isNotEmpty()
    val showChevron = activity.completed && hasDetails

    /*
     * 这两个都是 O(输出长度) 的扫描（展开输出上限 40 000 字符，见
     * `WorkspaceViewModel.LIVE_OUTPUT_LIMIT`），而这一行每次重组都要用它们 ——
     * 状态切换、滚动复用、展开/收起都会触发。所以先算一次并缓存。
     *
     * ⚠️ key **不能**用 `activity` 整体：它带 `elapsedMs`，运行中每秒都在变，
     * 那样等于每秒白算一次。key 只取真正参与计算的那几个字段。
     *
     * 输出字符串没变时 `remember` 的相等判断走的是同一实例的 `String.equals`
     * 快路径（O(1)）；真变了才付一次 memcmp —— 都比重新 split 一遍便宜。
     */
    val isFileDiff = remember(activity.toolName, activity.output) {
        activity.isFileDiff()
    }
    // 命令行的折叠/展开两副面孔：展开时看**原始**命令，折叠时才用被截短的摘要。
    // 恢复出来的历史工具没有 `command`（旧记录里没存），那时退回摘要 —— 有总比空着好。
    val commandLine = if (activity.expanded && activity.command.isNotBlank()) activity.command
    else activity.summary
    val collapsedSummary = remember(
        activity.kind,
        activity.output,
        activity.additions,
        activity.deletions,
        activity.failed,
        activity.exitCode,
    ) {
        compactToolSummary(activity)
    }

    // 这一行菜单的判据。构造规则只在 `ToolActions.flags` 一处实现（VM 分派动作时用的是
    // 同一份），所以这里**不算**"有没有 diff"这类结论，只把结论喂进去：
    // 输出本身是 diff（`ToolText.isFileDiff`）**或**引擎单独给了 diff（`activity.diff`）
    // —— 两种情况菜单里都该有「复制 diff」，而后者以前是漏的。
    val menuFlags = ToolActions.flags(
        kind = activity.kind,
        summary = activity.summary,
        output = activity.output,
        isFileDiff = isFileDiff || hasDiff,
        completed = activity.completed,
        expanded = activity.expanded,
    )

    // 整行点击：Miuix Surface(onClick)（color = Transparent 不填色，仅取按压反馈）
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = scheme.onSurface,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 1.dp, end = 2.dp, bottom = 4.dp)
            // 运行中不挂尺寸动画：这段时间里状态行每秒都在换文字（elapsed），
            // 而「运行中… → ⎿ 摘要 → 全量输出」的切换已经由 Crossfade 负责过渡。
            // clip 在动画**外侧**：展开输出时文字按自然高度绘制、被逐帧露出，
            // 而不是整段先冒出来再等卡片长高。
            .clipToBounds()
            .then(
                if (activity.completed) {
                    Modifier.animateContentSize(animationSpec = ZhiMotion.sizeSpec)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(25.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStatusGlyph(activity)

            Text(
                text = activity.displayName,
                color = scheme.onSurface,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 命令类工具的命令单独成行，这里留空占位（原版行为）
            if (!isCommand && activity.summary.isNotEmpty()) {
                Text(
                    text = "  " + activity.summary,
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            if (activity.additions > 0) DiffCount("+${activity.additions}", ZhiColors.green())
            if (activity.deletions > 0) DiffCount("−${activity.deletions}", ZhiColors.red())

            // ⋯ 的菜单内容只由 `ToolActions` 决定（判据的构造也在那里，界面与 VM 共用）：
            // 这样"哪个状态该有哪些动作"只有一份实现，界面这边只负责把它渲染出来。
            //
            // ⚠️ `items` 必须 `remember`：`ZhiIconDropdownMenu` 内部按 `remember(items, …)`
            // 缓存整份 `DropdownEntry`，而写在参数位置上的 `map { … }` 每次重组都是新的
            // List 实例 —— 身份不等，那份缓存永远命不中，等于没做。
            // key 取旗标与回调：旗标是 data class（按值比较），回调在 ToolGroupCard 里
            // 捕获的是稳定值，两者跨重组都能命中。
            val menuItems = remember(menuFlags, onToolAction) {
                ToolActions.options(menuFlags).map { label ->
                    ZhiMenuItem(text = label, onClick = { onToolAction(label) })
                }
            }

            // ⋯ 与 ⌄/⌃ 都转发到 Miuix IconButton。
            //
            // `⋯` 这一个走 **`ZhiIconDropdownMenu`**（= Miuix `OverlayIconDropdownMenu`），
            // 与输入器底排的 `+`、权限、推理**同一个组件**：触发按钮自己持有展开态、
            // 按下时进入 hold-down 态、面板由 Miuix 贴着按钮弹出。
            //
            // ⚠️ 以前这里是一个普通 `ZhiIconButton` + VM 里的一份 `choicePicker` 状态，
            // 于是点它弹出来的是**屏幕中央的对话框**（`ChoiceIntent.TOOL_ACTION` 不在
            // `isActionMenu` 的名单里，选择器被交给居中的 `ChoicePickerOverlay`）——
            // 动作明明只作用于这一行，弹窗却出现在屏幕中央，位置与语义对不上。
            // 现在展开态就在这一行自己的组合里，不需要任何坐标换算，也不会锚偏。
            ZhiIconDropdownMenu(
                items = menuItems,
                minHeight = 25.dp,
                minWidth = 25.dp,
                cornerRadius = 12.5.dp,
                // 透明底：底色是工具组那张 Card，触发按钮不该再画一层
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    painter = ZhiIcons.more,
                    contentDescription = "工具操作",
                    tint = scheme.onSurfaceVariantSummary,
                    // 横排 `⋯` 现在由**资源本身**保证（`ic_more_horiz` 就是三点横排）。
                    //
                    // 这里曾经写成 `ZhiIcons.more`（Miuix 的 `More`，竖排三点）再
                    // `rotate(90f)` 假装横排 —— 靠旋转凑形状，而且旁边那个折叠箭头
                    // 一转就跟着歪。换成 AOSP 的 `more_horiz` 之后旋转整段删掉。
                    //
                    // 尺寸 18dp：它是可点的控件，比状态图标（14dp）大一号
                    // （反编译版是 `iconOnly(R.drawable.ic_more, 18, MUTED_2)`）。
                    modifier = Modifier.size(18.dp),
                )
            }

            // ⚠️ 这里**没有**展开/收起箭头（用户：「⌃/⌄ 箭头可以去掉」）。
            //
            // 整行都是 `Surface(onClick = onToggle)`，点内容即可展开/收起；
            // 输出区域自己也有 `点按展开` 的提示行（见下面 COLLAPSED 分支）。
            // 参考实现的这一行到 `⋯` 为止，右端不再有第二个小图形。
            //
            // `showChevron` 仍然有用：它决定**有没有详情可展开**，
            // 但那个判断现在只用于"输出区是否可点"，不再用来画图标。
        }

        // Bash 命令：等宽、缩进，与图片里「命令在名称下方」一致。
        //
        // ⚠️ 展开态必须显示**完整**命令（`activity.command`），折叠态才用 `summary`
        // （那是 `truncateCommand` + `shorten(190)` 的结果，只保证标题行不撑破）。
        // 参考实现同样是 `item.expanded ? command : truncateCommand(command)` ——
        // 一条 `&&` 串起来的多行脚本被截成前两行之后，用户没法核对它到底跑了什么。
        if (isCommand && commandLine.isNotEmpty()) {
            Text(
                text = "  " + commandLine,
                color = if (activity.expanded) scheme.onSurface else scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontFamily = FontFamily.Monospace,
                // 展开时**不限制行数**：完整命令是用户主动要求看的，再截就等于没展开。
                maxLines = if (activity.expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                // 反编译版：`setPadding(dp(22), 0, dp(4), dp(2))`
                modifier = Modifier.padding(start = 22.dp),
            )
        }

        // 状态区只有尺寸过渡：旧内容从顶部收回，新内容从同一边缘展开。
        // 不使用淡入淡出，避免长输出在收回时先淡掉、再让卡片缩小。
        AnimatedContent(
            targetState = toolStatusRegion(activity),
            transitionSpec = {
                expandVertically(animationSpec = ZhiMotion.sizeSpec, expandFrom = Alignment.Top) togetherWith
                    shrinkVertically(animationSpec = ZhiMotion.sizeSpec, shrinkTowards = Alignment.Top)
            },
            label = "tool-status",
        ) { region ->
            when (region) {
                // 运行中：**终端井**（对齐反编译版 `addToolCard` 的 `else if (!item.completed)` 分支）。
                //
                // 只显示一个"运行中 · 00:12"是不够的 —— 一条跑两分钟都不吐字的命令与
                // 一条正在刷日志的命令在界面上长得一模一样。所以这里整块做成一口井：
                //
                //   命令类（Bash/Root）: ● bash                     实时 00:12 · 标准输出 1.2 KB
                //                        cd /… && npm run build          ← 命令行
                //                        ─────────────────────────────   ← 分隔线
                //                        [实时输出尾部 7 行]
                //
                //   其他工具          : 正在执行 00:03…
                //                        ┌ [输出尾部 5000 字符] ┐
                //
                // 与反编译版的差别只有一处：它的首行是"圆点 + `bash` 字面量"，这里用
                // `activity.displayName`（Root 命令也走同一条路，硬写 `bash` 会说谎）。
                ToolStatusRegion.RUNNING -> RunningPanel(activity, nowMs, isCommand = isCommand)
                ToolStatusRegion.COLLAPSED -> Text(
                    text = "  ⎿  " + collapsedSummary,
                    color = if (activity.failed) ZhiColors.red() else scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 22.dp, bottom = 2.dp),
                )
                // 展开：**两块井**，与参考实现 `addToolCard` 一致 ——
                // 先 diff（`colorDiff(item.diff)` 画在 `TERMINAL_BG` 上，外距 23,3,0,4），
                // 再 result（`TERMINAL_BG`，外 padding 22,2,4,4 + 内 8,6,8,6）。
                // 不是二选一：写文件类工具的自述（"Wrote 505 bytes to …"）本身也有信息
                // （字节数、目标路径），把它丢掉换成只有 diff 是**减信息**。
                //
                // 井的判据两路：`activity.diff`（引擎单独算的）优先，
                // 其次才是"输出文本本身长得像 diff"（老记录 / 引擎没给 diff 的情况）。
                //
                // 左右不留给外层：着色条要顶到井两边（像 diff 该有的样子），
                // 所以 insideMargin 只给上下；横向留白由每一行自己出（见 DiffLines）。
                ToolStatusRegion.EXPANDED -> Column(modifier = Modifier.fillMaxWidth()) {
                    // 富内容预览**排在最前**：本分支的顺序是「图片 → diff 井 → 输出井」。
                    //
                    // 为什么图在最前：沙箱截图是"发生了什么"最直接的证据，diff 与输出是细节。
                    // 参考实现的展开态同样是先给最直观的那块（`addToolCard` 里 diff 井在
                    // result 井之上）。
                    //
                    // 复用 `ZhiImageRow`（横向可滑 + 同高）而不是自己拼一行图：一串截图
                    // 要能左右翻着比对，而它的可用宽度、长宽比夹取、跨项解码缓存都已经处理好了
                    // （含"同一轴不能再套一层滚动"那条约束）。
                    //
                    // 缩进对齐 diff 井的 `start = 23.dp` —— 三块井的左缘在同一条竖线上。
                    if (activity.previews.isNotEmpty()) {
                        ZhiImageRow(
                            images = activity.previews,
                            onOpen = onImageOpen,
                            modifier = Modifier.padding(start = 23.dp),
                        )
                    }
                    val diffText = if (hasDiff) activity.diff else if (isFileDiff) activity.output else ""
                    if (diffText.isNotBlank()) {
                        Card(
                            modifier = Modifier.padding(start = 23.dp, top = 5.dp),
                            // 反编译版这里用的是 `round(TERMINAL_BG, 10)` —— `ZhiRadius.inner` 正是 10dp
                            cornerRadius = ZhiRadius.inner,
                            insideMargin = PaddingValues(vertical = 6.dp),
                            colors = CardDefaults.defaultColors(
                                color = ZhiColors.terminalSurface(),
                                contentColor = scheme.onSurfaceVariantSummary,
                            ),
                        ) {
                            DiffLines(diffText)
                        }
                    }
                    if (activity.output.isNotBlank()) {
                        Card(
                            modifier = Modifier.padding(start = 23.dp, top = 5.dp),
                            cornerRadius = ZhiRadius.inner,
                            insideMargin = PaddingValues(8.dp),
                            colors = CardDefaults.defaultColors(
                                color = ZhiColors.terminalSurface(),
                                contentColor = scheme.onSurfaceVariantSummary,
                            ),
                        ) {
                            // OutputLines 与 DiffLines 共用同一套「最多渲染 300 行 + 点按显示全部」
                            // 的上限（见 ui/chat/ToolOutputText.kt 的 MaxRenderedLines）：展开的输出最多
                            // 40 000 字符，整段当一个 Text 放在单个 LazyColumn item 里，
                            // 那个 item 会比视口还高，懒加载复用彻底失效。
                            OutputLines(activity.output)
                        }
                    }
                }
                ToolStatusRegion.QUIET -> Unit
            }
        }
    }
    } // Surface(onClick)
}

/**
 * 运行中那一块 —— **终端井**。
 *
 * 对照反编译版 `addToolCard` 的运行分支（`~/.iqcode/.../MainActivity.java`）：
 *
 * ```java
 * // 命令类
 * LinearLayout panel = vbox();
 * panel.setPadding(dp(10), dp(7), dp(10), dp(8));
 * panel.setBackground(round(TERMINAL_BG, 10));
 * panel.setLayoutParams(margins(dp(23), dp(4), 0, dp(4)));
 *   ├─ 7×7 圆点（awaitingPermission ? MUTED_2 : ACCENT）
 *   ├─ "bash" 标签 10.5f 等宽
 *   ├─ [弹性]
 *   ├─ runningToolLabel（右对齐、单行）
 *   ├─ 命令行（未展开 → truncateCommand）
 *   ├─ dividerHorizontal()
 *   └─ liveOutputPreview(7000, 7 | 10 行)  或  "等待程序输出…"
 *
 * // 非命令类
 * runningToolLabel（一行）
 * + 输出尾部 5000 字符，padding(8,6,8,6)、round(TERMINAL_BG, 10)、margins(23,3,0,3)
 * ```
 *
 * 为什么值得做成"一口井"而不只是一行字：这几行是**程序自己的原始输出**，
 * 与上方"应用渲染出来的说明文字"是两种东西，底色方向（比页面更暗）就是这个区分本身。
 *
 * 增量刷新：反编译版只对 `liveOutputView.setText(...)` 做替换、不重建这张卡；
 * 这边由 Compose 的重组承担（`remember` 的 key 只取真正会变的那两个字段，
 * 不会因为 `elapsedMs` 每 500ms 变化而白算一遍）。
 */
@Composable
private fun RunningPanel(activity: ToolActivity, nowMs: Long, isCommand: Boolean) {
    val scheme = MiuixTheme.colorScheme
    val live = remember(activity.kind, activity.output) { liveOutputPreview(activity) }
    val label = runningToolLabel(activity, nowMs)

    if (!isCommand) {
        // 非命令类：计时一行 + 井里的输出尾部。它们多数只吐一段文本（读到的片段、
        // 搜索结果），没有"行"结构，所以按字符取尾部（见 liveOutputPreview）。
        Column(modifier = Modifier.padding(start = 23.dp, top = 2.dp)) {
            Text(
                text = label,
                color = if (activity.awaitingPermission) scheme.primary else scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontFamily = FontFamily.Monospace,
            )
            if (live.isNotEmpty()) {
                Card(
                    modifier = Modifier.padding(top = 3.dp, bottom = 3.dp),
                    cornerRadius = ZhiRadius.inner,
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(
                        color = ZhiColors.terminalSurface(),
                        contentColor = scheme.onSurfaceVariantSummary,
                    ),
                ) {
                    Text(
                        text = live,
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }

    Card(
        // margins(23, 4, 0, 4)
        modifier = Modifier.padding(start = 23.dp, top = 4.dp, bottom = 4.dp),
        cornerRadius = ZhiRadius.inner,
        // padding(10, 7, 10, 8)
        insideMargin = PaddingValues(start = 10.dp, top = 7.dp, end = 10.dp, bottom = 8.dp),
        colors = CardDefaults.defaultColors(
            color = ZhiColors.terminalSurface(),
            contentColor = scheme.onSurfaceVariantSummary,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 7×7 的圆点：运行中是强调色，等授权时变灰（"停住了"）。
                // 用 Box + background 而不是字符 ●，理由与状态字形一致：字形依赖字体。
                //
                // 运行中额外做**呼吸**（alpha 在 0.45~1 之间往返）。这不是装饰：
                // 命令安静下来时屏幕上唯一能证明"还活着"的就是它在动 ——
                // 耗时那一栏每 500ms 才刷新一次，而呼吸是每帧的。
                //
                // ⚠️ 呼吸只改 `graphicsLayer.alpha`（绘制期属性），
                // 不触发重组也不触发布局 —— 这是本组件里唯一一处"持续动画"，
                // 用错了就会让整条工具行每帧重组。
                //
                // 等授权时**不呼吸**而是恒亮：那时"停住了"本身就是要传达的信息，
                // 让它继续脉动会读成"还在跑，只是没输出"。
                val dotAlpha = if (activity.awaitingPermission) {
                    1f
                } else {
                    val breath = rememberInfiniteTransition(label = "runningDot")
                    val value by breath.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.45f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = SinOutEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "runningDotAlpha",
                    )
                    value
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = dotAlpha }
                        .background(
                            color = if (activity.awaitingPermission) scheme.onSurfaceVariantSummary else scheme.primary,
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
                Text(
                    text = activity.displayName,
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = if (activity.awaitingPermission) "等待授权…" else label,
                    color = if (activity.awaitingPermission) scheme.primary else scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            // 命令行：未展开时截断（与完成态同一套 `truncateCommand`），
            // 这样"跑的是什么"在这块井里也看得见，不必先去点一下 ⌄。
            if (activity.command.isNotBlank()) {
                Text(
                    text = ToolText.truncateCommand(activity.command),
                    color = scheme.onSurface,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp, bottom = 7.dp),
                )
            }
            ZhiHorizontalDivider(modifier = Modifier.fillMaxWidth())
            val body = if (live.isNotEmpty()) live
            else if (activity.awaitingPermission) "等待授权…"
            else "等待程序输出…"
            // 实时输出区：**只在有内容时**做高度过渡。
            //
            // `animateContentSize` 挂在这一层（单条卡片内部），不是整表 ——
            // 整表上的高度动画会让 LazyColumn 每帧重新测量所有可见项。
            // 这里的目的只是"输出从 1 行涨到 12 行时不硬跳"。
            Text(
                text = body,
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontFamily = FontFamily.Monospace,
                // 实时区只按行数控制高度（见 `LiveOutput.preview`）：
                // 一行很长的编译命令折成三行会把工具行顶得很高，而这几行的
                // 用途只是"看见它在动"。
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .animateContentSize(ZhiMotion.sizeSpec),
            )
        }
    }
}

/**
 * 这条工具的输出该不该按 diff 渲染。 *
 * <p>判据在 [ToolText.isFileDiff]（纯函数、有单测）：既要工具是写文件的，
 * **也要**输出真的是 diff —— 写入失败时输出是一行错误文本，
 * 按工具名着色会把那行错误画成 diff 配色，比不着色更误导。
 */
private fun ToolActivity.isFileDiff(): Boolean = ToolText.isFileDiff(toolName, output)

/**
 * 行首状态字形，复刻原版 `addToolCard()` 的 `statusGlyph`：
 * 完成 `✓`（绿）/ 失败 `×`（红）/ 等待授权 `○`（强调色）/ 运行中 `●`（强调色）。
 * 固定 23dp 宽保证多行左对齐。
 */
@Composable
private fun ToolStatusGlyph(activity: ToolActivity) {
    val scheme = MiuixTheme.colorScheme
    // 状态用 Miuix 图标 / 进度指示器表达，不再手写 ✓ × ○ ● 这些字符：
    // 字符字形依赖字体，等宽字体缺字时会显示成方块，而且颜色/粗细无法统一。
    Box(modifier = Modifier.width(23.dp), contentAlignment = Alignment.Center) {
        when {
            activity.completed && activity.failed -> Icon(
                painter = ZhiIcons.failed,
                contentDescription = "失败",
                tint = ZhiColors.red(),
                modifier = Modifier.size(13.dp),
            )
            activity.completed -> Icon(
                painter = ZhiIcons.done,
                contentDescription = "完成",
                tint = ZhiColors.green(),
                modifier = Modifier.size(13.dp),
            )
            activity.awaitingPermission -> Icon(
                painter = ZhiIcons.awaiting,
                contentDescription = "等待授权",
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            // 运行中：不确定态转圈，而不是一个静止的 ●（静止字符看不出"在进行"）
            else -> InfiniteProgressIndicator(size = 12.dp, color = scheme.primary)
        }
    }
}

/** 运行中/等待授权的说明文字，对应原版 `runningToolLabel()`。 */
/**
 * 运行中/等待授权的说明文字，对应原版 `runningToolLabel()`。
 *
 * `nowMs` 是界面侧时钟（见 [rememberRunningClock]）。耗时取「起点至今」与「引擎推送值」
 * 的较大者 —— 引擎那个值只在有输出时更新，光用它会让长时间不吐字的命令看起来卡死。
 * 取值规则在 [ToolActions.displayElapsedMs]（纯逻辑、有单测）。
 *
 * 命令类工具额外报出**输出体量**（`实时 00:12 · 标准输出 12.3 KB · 错误输出 0 B · 进程运行中`）：
 * 字符数是单调递增的，即使屏幕上那几行没变，这一项也在动 —— 它同时回答了
 * "到底有没有在产出"和"错误输出是不是在涨"。
 */
private fun runningToolLabel(activity: ToolActivity, nowMs: Long): String {
    if (activity.awaitingPermission) return "等待授权…"
    val display = ToolActions.displayElapsedMs(activity.elapsedMs, activity.startedAtMs, nowMs)
    val clock = if (display > 0) ToolText.formatElapsed(display) else ""
    return when {
        activity.kind != ToolKind.COMMAND ->
            if (clock.isEmpty()) "正在执行…" else "正在执行 $clock…"
        clock.isEmpty() -> "实时 · ${LiveOutput.volume(activity.stdoutChars, activity.stderrChars)} · 进程运行中"
        else -> "实时 $clock · ${LiveOutput.volume(activity.stdoutChars, activity.stderrChars)} · 进程运行中"
    }
}

/**
 * 运行中要在行内摊出来的实时输出。
 *
 * 两种取法对应参考实现的两个函数（`MainActivity.liveOutputPreview` / `liveOutputTail`）：
 * - **命令类**（Bash / Root）按"最后 7 行、最多 7000 字符"取 —— 命令的输出是**行**结构，
 *   按行取才看得出跑到哪一步了；
 * - **其他工具**按"尾部 5000 字符"取 —— 它们多数只吐一段文本（读到的片段、搜索结果），
 *   没有"行"的概念，硬按行裁会把内容切碎。
 *
 * 参考实现在宽屏下按 10 行取。这里固定 7 行：`wide` 要一路从 `ChatArea` 穿过
 * `ChatList` / `ToolGroupCard` / `ToolRow` 四层，而差别只是横屏多三行 ——
 * 横向空间的价值在别处，不值得为它铺一条参数通道。
 *
 * 裁剪与"省略提示"都在 [LiveOutput]（纯逻辑、有单测）。
 */
private fun liveOutputPreview(activity: ToolActivity): String {
    val text = activity.output
    if (text.isEmpty()) return ""
    return if (activity.kind == ToolKind.COMMAND) {
        LiveOutput.preview(text, maxChars = 7_000, maxLines = 7)
    } else {
        LiveOutput.tail(text, maxChars = 5_000)
    }
}

/**
 * 运行中的刷新时钟：每 [periodMs] 返回一个新的「现在」。
 *
 * [active] 为 false 时**不排队下一次** —— 参考实现也是这么做的
 * （`refreshToolElapsed` 只有在仍存在未完成工具时才重新 post），
 * 否则一个常驻的定时器会一直触发重组。
 */
@Composable
private fun rememberRunningClock(active: Boolean, periodMs: Long = 500L): Long {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(periodMs)
            // 与 `ToolActivity.startedAtMs` 同一个时钟：混用墙上时钟与单调时钟会让
            // "现在 - 起点"在改过系统时间之后变成一个无意义的差值。
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

/**
 * 工具行状态区（Crossfade 的目标态）：运行中 / 折叠摘要 / 展开输出 / 无内容。
 *
 * 收敛成枚举而不是拿布尔组合当 key：运行中的 `elapsedMs` 每秒都在变，
 * 但只要 completed/expanded 没翻转，region 就不变 —— Crossfade 不会被打断重放。
 */
private enum class ToolStatusRegion { RUNNING, COLLAPSED, EXPANDED, QUIET }

private fun toolStatusRegion(activity: ToolActivity): ToolStatusRegion = when {
    !activity.completed -> ToolStatusRegion.RUNNING
    // ⚠️ 判据必须带上 `previews`。只判 output 的话，一个"只有截图、自述为空"的工具会
    // 落进 QUIET，而 QUIET 分支是 `-> Unit`（什么都不画）—— 于是预览被静默吞掉。
    // 沙箱截图目前总是带一段 JSON 自述所以碰不到，但那是**巧合**不是保证。
    activity.output.isBlank() && activity.previews.isEmpty() -> ToolStatusRegion.QUIET
    activity.expanded -> ToolStatusRegion.EXPANDED
    else -> ToolStatusRegion.COLLAPSED
}

/**
 * 折叠态的紧凑结果摘要，复刻原版 `compactResult()`（`MainActivity.java:2764-2776`）
 * 与 `addToolCard()` 里 diff 优先的分支：
 * - 有 diff → `N 行已修改 · 点按展开`
 * - 命令类失败 → `退出码 N · 原因 · 点按展开`
 * - 命令类成功 → `N 行 · 点按展开`
 * - 其他工具：单行且够短就直接显示，否则 `N 行 · 点按展开`
 */
private fun compactToolSummary(activity: ToolActivity): String {
    // 预览图**优先**说。沙箱截图那类工具的自述是一大段 JSON，若走到下面的行数分支
    // 会显示成"28 行 · 点按展开" —— 用户完全看不出这里有一张截图可看，
    // 而这恰恰是它最该被发现的地方。所以它排在行数与退出码之前。
    if (activity.previews.isNotEmpty()) {
        return "${activity.previews.size} 张图 · 点按展开"
    }

    val changed = activity.additions + activity.deletions
    if (changed > 0) return "$changed 行已修改 · 点按展开"

    // ⚠️ 这里曾经是 `val clean = activity.output.trim()` 再 `clean.split('\n').size`。
    //
    // 两步都在**每次重组**时按输出全长的代价做一次：trim 会复制整份文本（上限
    // 40 000 字符），split 会再建出所有行的数组 —— 而结果只有三个数：
    // 去掉首尾空白后的边界、行数、以及"单行且不超 96 字符"时的那段文本。
    // 改成按下标算：`trim()` 的边界是一个双向扫描，行数数的是区间里的换行符，
    // 那段文本只在真要显示时才 substring（一行，很短）。
    //
    // 语义与原来逐字一致，包括：区间内部的空行**计入**行数
    // （`"a\n\nb".trim().split('\n').size == 3`）。
    val text = activity.output
    var lo = 0
    var hi = text.length
    while (lo < hi && text[lo].isWhitespace()) lo++
    while (hi > lo && text[hi - 1].isWhitespace()) hi--
    if (lo >= hi) return "已完成"

    var lineCount = 1
    for (i in lo until hi) {
        if (text[i] == '\n') lineCount++
    }

    if (activity.kind == ToolKind.COMMAND) {
        val failedExit = activity.failed || (activity.exitCode != null && activity.exitCode != 0)
        if (failedExit) {
            val why = ErrorSummary.firstUsefulLine(text)
            val code = activity.exitCode ?: 1
            return buildString {
                append("退出码 ").append(code)
                if (why.isNotEmpty()) append(" · ").append(why.take(105))
                if (lineCount > 1) append(" · 点按展开")
            }
        }
        return "$lineCount 行 · 点按展开"
    }

    if (lineCount == 1 && hi - lo <= 96) return text.substring(lo, hi)
    return "$lineCount 行 · 点按展开"
}

/** 行内 diff 计数：原版是**纯文字着色**（9.5sp 等宽），没有底色胶囊。 */
@Composable
private fun DiffCount(label: String, color: Color) {
    Text(
        text = label,
        color = color,
        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 5.dp),
    )
}

/**
 * 错误卡片。
 *
 * ## 外观与沙箱页的后端错误同款
 *
 * 原来是「左边一根 3dp 红竖条 + 红标题 + 正文」，而沙箱页那处是「深灰卡片配红字」，
 * 输入器上方那处又是「带投影的浮动工具栏」—— 同一件"出错了"三种长相。
 * 现在三处都走 [ZhiNoticeBar] 的通知条（整宽红底、无阴影、小圆角）。
 *
 * 正文仍然走 [ZhiMarkdown]：`ToolText.friendlyError` 会输出带 `代码` 与列表的
 * 可操作建议，与回复正文保持一致 —— 这一条是功能，不因为换外观而丢。
 */
@Composable
fun ErrorCard(item: ChatItem) {
    ZhiNoticeBar(
        title = item.title.ifEmpty { "错误" },
        tone = ZhiNoticeTone.ERROR,
        modifier = Modifier.padding(vertical = 6.dp),
        // 正文用 Markdown 渲染，所以要自己给 slot 而不是让通知条画一行纯文本。
        // 见上面说明：错误正文里有 `代码` 与列表，纯文本会把它压平。
        content = {
            ZhiMarkdown(
                source = item.body,
                bodyFontSize = MiuixTheme.textStyles.body1.fontSize,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
    )
}

/**
 * 提示卡片（斜杠命令输出、计划审批结果等）。
 *
 * 与 [ErrorCard] 同一个通知条分量，只有配色不同（琥珀 = [ZhiNoticeTone.WARN]）：
 * 它和错误是同一层级的信息块，形状本就该一致。
 */
@Composable
fun InfoCard(item: ChatItem) {
    ZhiNoticeBar(
        title = item.title.ifEmpty { null },
        tone = ZhiNoticeTone.WARN,
        modifier = Modifier.padding(vertical = 4.dp),
        content = {
            // 提示正文也走 Markdown。这些内容里大量使用 `反引号` 标记命令与参数，
            // 原先那个"整段变等宽"的启发式太粗（一句里只要有反引号，全段都成等宽），
            // 现在由行内解析只给反引号包住的部分加等宽 + 底色。
            ZhiMarkdown(
                source = item.body,
                bodyFontSize = MiuixTheme.textStyles.body2.fontSize,
                modifier = Modifier.padding(top = 3.dp),
            )
        },
    )
}


// 这里曾经有一个私有的 `formatElapsed`，输出的是 `7.0s` / `1:04.2` —— 现在已经删掉：
// 参考实现（IQ Code `MainActivity.formatElapsed`）是 `01:04`（分:秒，≥1 小时才带小时位），
// 而 `ToolText.formatElapsed` 早就是那一份了，只是没人调用它（于是两版截图对不上秒数格式）。
// 耗时显示统一走 `ToolText.formatElapsed`，不再留第二份实现。

