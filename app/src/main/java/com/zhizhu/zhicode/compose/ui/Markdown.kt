package com.zhizhu.zhicode.compose.ui

import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.data.Clipboard

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.LinkedHashMap

/**
 * Markdown 渲染层。语法解析在 `MarkdownParse.kt`（纯 Kotlin、可独立测试），
 * 本文件只负责把那份 AST 画成 Miuix 组件。
 *
 * ## 为什么不用三方库
 *
 * 这个工程跑在受限的 Termux 环境里，多引一个依赖就多一份版本冲突与离线解析风险；
 * 而 Markdown 的常用子集并不大，且必须复用 Miuix 配色。自写反而更可控。
 *
 * ## 与解析层的约定
 *
 * 解析层**保证不丢字符**：未闭合的围栏、未闭合的强调都会原样输出。
 * 这条对流式输出很关键 —— 模型正在写 `**粗` 的那一刻，不能把半截标记吃掉。
 *
 * ## 链接
 *
 * 用 Compose 1.7+ 的 [LinkAnnotation.Url]，是**真正可点击**的链接（不是只换个颜色）。
 * 本工程 Compose UI 1.12.0，满足要求。
 */
@Composable
fun ZhiMarkdown(
    source: String,
    modifier: Modifier = Modifier,
    bodyFontSize: TextUnit = 12.sp,
    /**
     * 这段文本是不是**正在流式写入**。
     *
     * 传 true 时才启用「已完结前缀 + 在写尾部」的分段渲染（见 [settledPrefixLength]）；
     * 默认 false 走整段解析 —— 已定稿的消息不该有任何理由拆开解析。
     *
     * ⚠️ 调用方必须在流式结束时把它翻回 false：尾部是按**行内**渲染的（不做块级解析），
     * 只有整段重解析一次，最后那一段的标题/列表/代码块才会拿到正确的块级样式。
     */
    streaming: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val inlineStyles = rememberInlineStyles(bodyFontSize)
    val split = remember(source, streaming) { splitForStreaming(source, streaming) }
    // 流式尾部会不断变化，不能写进进程级缓存；定稿正文则跨 LazyColumn 回收、
    // 会话切换和相同内容的多个调用点复用 AST。组合级 remember 仍保留在外层，
    // 这样热路径不需要每次触碰全局锁。
    val blocks = remember(split.settled, streaming) {
        if (streaming) parseMarkdown(split.settled) else MarkdownBlockCache.getOrParse(split.settled)
    }

    // 前沿淡入的斜坡宽度：按**到达速率**换算，目标是固定时长（见 TailFadeRate）。
    //
    // ⚠️ key 是 `source` 而不是 `split.tail`：速率要按**整段正文**的增长来估，
    // 而 tail 在跨过块边界时会突然归零重算 —— 按 tail 估会在每次换段时把速率
    // 估成负数（文本变短），于是斜坡在开新段时抖一下。整段正文的长度只增不减，
    // 估计干净得多；而**要用宽度的地方是 tail**，两者本来就是分开的两件事。
    //
    // ⚠️ 这段计算只在文本真的变了时跑（`remember(source)`），所以没有额外帧。
    val fadeRate = remember { TailFadeRate() }
    val fadeRamp = remember(source) { fadeRate.widthFor(source.length, System.nanoTime()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ⚠️ 淡入的落点有两处，缺一不可：
        //   · 切过（长消息）→ 淡在 `split.tail` 上；
        //   · 没切（短于 [MinSettledChars]）→ 全部都在 `blocks` 里，
        //     那就淡在**最后一个块**上，否则短消息一个字都不淡。
        //   两处互斥（tail 为空时才是"没切"），所以同一时刻只有一个在淡。
        blocks.forEachIndexed { index, block ->
            MdBlockView(
                block = block,
                fontSize = bodyFontSize,
                fadeTail = streaming && split.tail.isEmpty() && index == blocks.lastIndex,
                fadeRamp = fadeRamp,
            )
        }
        // 在写尾部按行内渲染（不是纯文本）：模型正在写的那一段里的 `代码`、**粗体**
        // 照常生效，只有块级语法（标题/列表/围栏）要等它跨过块边界。
        if (split.tail.isNotEmpty()) {
            Text(
                text = rememberInlineFading(
                    text = split.tail,
                    styles = inlineStyles,
                    base = scheme.onSurface,
                    fade = true,
                    ramp = fadeRamp,
                ),
                color = scheme.onSurface,
                fontSize = bodyFontSize,
                lineHeight = bodyFontSize * 1.5f,
            )
        }
    }
}

/**
 * 流式渲染的切分结果。
 *
 * 用 `remember(source, streaming)` 一次性算出，**不要**在组合里各算各的：
 * `settled` 只在跨块时变（几十次），`tail` 每个 delta 都变（短），
 * 于是 `parseMarkdown` 的 key 也只在跨块时失效 —— 这正是这次优化的全部意义。
 */
private class MdStreamSplit(val settled: String, val tail: String)

/**
 * 定稿 Markdown 的跨组合缓存。
 *
 * `remember(source)` 只覆盖当前组合树：LazyColumn 回收一条历史消息后，下一次滚回来
 * 仍会重新做整篇块解析。这里缓存的是不含主题/字号的纯 AST，因此可以安全地跨消息、
 * 跨主题和跨组合复用；行内 `AnnotatedString` 仍由各自的 [InlineStyles] 缓存，避免把
 * 主题色对象带进全局缓存。
 *
 * 只缓存定稿正文，并限制条数与源文本总量。流式文本每个 delta 都是新 key，放进这里
 * 会把有限缓存变成“只进不命中”的队列；超长正文也宁可交给组合级 remember，避免
 * 一个很大的回复长期占住进程级缓存。
 */
private object MarkdownBlockCache {
    /**
     * 缓存多少条**定稿正文**的块解析结果。
     *
     * <p>原先只有 12。`LazyColumn` 会回收组合，滚回去一条历史消息就要重新整篇解析 ——
     * 而解析会分配整棵块 AST 与跨段 `AnnotatedString`。真机日志里滑动长会话的分配速率
     * 约 33MB/s、单次 GC 总耗时最高 794ms，这条路径贡献了一份。
     *
     * <p>抬到 48 是安全的：真正的内存闸门是下面的 [MaxSourceChars]（源文本总字符数），
     * 条数只是"能记住几条"的上限。短消息多了不会撑爆缓存，长消息仍会按字符数被挤出去。
     */
    private const val MaxEntries = 48
    private const val MaxSourceChars = 512 * 1024
    private const val MaxCacheableSourceChars = 128 * 1024

    private val entries = LinkedHashMap<String, List<MdBlock>>(MaxEntries, 0.75f, true)
    private var sourceChars = 0

    fun getOrParse(source: String): List<MdBlock> {
        if (source.length > MaxCacheableSourceChars) return parseMarkdown(source)

        synchronized(this) {
            entries[source]?.let { return it }
        }

        val parsed = parseMarkdown(source)
        synchronized(this) {
            // 另一个组合可能在解析期间先填入了同一正文，优先复用那份。
            entries[source]?.let { return it }
            entries[source] = parsed
            sourceChars += source.length
            trim()
        }
        return parsed
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (entries.size > MaxEntries || sourceChars > MaxSourceChars) {
            if (!iterator.hasNext()) break
            sourceChars -= iterator.next().key.length
            iterator.remove()
        }
    }
}

/**
 * 短于这个长度就不切。
 *
 * 两个理由：一是短文本整段解析本来就比「切一次 + 建两个对象」更划算；
 * 二是不切就没有「尾部按行内渲染」这回事，短消息在流式期间与最终形态**完全一致**，
 * 不会出现"先看着像纯文本、定稿后突然多出标题样式"的跳变。
 */
private const val MinSettledChars = 512

private fun splitForStreaming(source: String, streaming: Boolean): MdStreamSplit {
    if (!streaming) return MdStreamSplit(source, "")
    val cut = settledPrefixLength(source)
    if (cut < MinSettledChars) return MdStreamSplit(source, "")
    return MdStreamSplit(source.substring(0, cut), source.substring(cut))
}

/** 递归渲染一个块。引用块内部会有嵌套的块，所以这里必须能自我调用。 */
@Composable
private fun MdBlockView(
    block: MdBlock,
    fontSize: TextUnit,
    fadeTail: Boolean = false,
    fadeRamp: Int = TailFadeMinChars,
) {
    val scheme = MiuixTheme.colorScheme
    val inlineStyles = rememberInlineStyles(fontSize)

    when (block) {
        is MdBlock.Heading -> Text(
            text = rememberInline(block.text, inlineStyles),
            color = scheme.onSurface,
            fontSize = when (block.level) {
                1 -> (fontSize.value + 4).sp
                2 -> (fontSize.value + 2.5).sp
                3 -> (fontSize.value + 1.5).sp
                else -> (fontSize.value + 0.5).sp
            },
            fontWeight = FontWeight.Bold,
            lineHeight = (fontSize.value + 6).sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        is MdBlock.Paragraph -> Text(
            // 段落是在写正文最常见（也是唯一常见）的形状，所以淡入只在这里落点。
            // 标题/列表/引用/代码块不淡：它们在被写出来的一瞬间本来就是"跳"着出现的
            // （块级结构变了），给它们加前沿淡入反而看不出差别，只会多付 span 的开销。
            text = rememberInlineFading(
                text = block.text,
                styles = inlineStyles,
                base = scheme.onSurface,
                fade = fadeTail,
                ramp = fadeRamp,
            ),
            color = scheme.onSurface,
            fontSize = fontSize,
            lineHeight = fontSize * 1.5f,
        )

        is MdBlock.Bullet -> BulletRow(block, fontSize, inlineStyles)

        is MdBlock.Quote -> {
            // 竖条颜色在这里取好：drawBehind 的 lambda 不是 @Composable，里面不能读主题。
            val barColor = scheme.primary.copy(alpha = 0.55f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 竖条用 drawBehind 按**实测高度**画，而不是 `height(IntrinsicSize.Min)`
                    // + `fillMaxHeight`：后者要求 Compose 做固有测量，在滚动容器（LazyColumn）
                    // 里属于容易踩坑的构造，而 drawBehind 拿到的是已经量好的 size，零风险。
                    .drawBehind {
                        val w = 3.dp.toPx()
                        drawRoundRect(
                            color = barColor,
                            size = Size(w, size.height),
                            cornerRadius = CornerRadius(w / 2f),
                        )
                    }
                    .padding(start = 11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                block.blocks.forEach { inner -> MdBlockView(inner, fontSize) }
            }
        }

        MdBlock.Rule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(scheme.dividerLine),
        )

        is MdBlock.Code -> CodeBlock(block, fontSize)

        is MdBlock.Table -> MdTableView(block, fontSize, inlineStyles)
    }
}

@Composable
private fun BulletRow(block: MdBlock.Bullet, fontSize: TextUnit, styles: InlineStyles) {
    val scheme = MiuixTheme.colorScheme
    // 每层缩进 12dp；太深时收敛，避免把正文挤成一条窄柱
    val indent = (block.depth * 12).coerceAtMost(48).dp

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        verticalAlignment = Alignment.Top,
    ) {
        if (block.task != null) {
            // 任务项用勾选框一样的方块，而不是圆点：语义不同（一个是"待办"，一个是"列举"）
            Icon(
                painter = if (block.task) ZhiIcons.done else ZhiIcons.pending,
                contentDescription = if (block.task) "已完成" else "未完成",
                tint = if (block.task) ZhiColors.green() else scheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp).size(fontSize.value.dp + 2.dp),
            )
        } else {
            Text(
                text = block.marker,
                color = scheme.onSurfaceVariantSummary,
                fontSize = fontSize,
                // 有序列表的编号用等宽，位数不同也对得齐
                fontFamily = if (block.marker == "•" || block.marker == "◦") {
                    FontFamily.Default
                } else {
                    FontFamily.Monospace
                },
                modifier = Modifier.widthIn(min = 14.dp),
            )
        }
        Text(
            text = rememberInline(block.text, styles),
            color = scheme.onSurface,
            fontSize = fontSize,
            lineHeight = fontSize * 1.5f,
            modifier = Modifier.padding(start = if (block.task != null) 6.dp else 6.dp),
        )
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, fontSize: TextUnit) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    // 复制反馈的**本地**状态：点击后把标签换成「已复制」，约 520ms 后复原
    // （时长对齐参考实现 `MarkdownRenderer` 的 `postDelayed(..., 520)`）。
    // 不往 ViewModel 里放：这个状态只影响这一个代码块，而且它随滚动回收，
    // 放进全局 state 反而会出现"滚回来还是已复制"的残留。
    var copied by remember(block.body) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(CopyFeedbackMs)
        copied = false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = ZhiRadius.inner,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = CardDefaults.defaultColors(
            color = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
        ),
    ) {
        Column {
            // 语言标记与「复制」同处一行：语言靠左（便于一眼看出是哪种代码），
            // 复制按钮靠右（离拇指最近的位置）。
            //
            // 代码块是对话流里最常被整段拿走的东西（命令、补丁、配置），而在这之前
            // 唯一的复制途径是长按整条消息「复制」—— 那会把整篇回答一起复制走。
            if (block.lang.isNotBlank() || block.body.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠️ 语言为空时显示 `text`，**不能留空**。
                    //
                    // 依据是官方实现（反编译 `MarkdownRenderer.codeBlock()`）：
                    //   `String lowerCase = (str == null || str.trim().isEmpty())
                    //                        ? "text" : str.trim().toLowerCase(Locale.US);`
                    //   `textView.setText(lowerCase);`   ← 永远不会是空串
                    //
                    // 留空的后果是这一行变成「左边一片空白 + 右边一个复制按钮」。
                    // 它在结构上仍是表头（高度 24dp 胶囊 + 4dp 下边距），但**看起来就是
                    // 代码块顶部多出一大块空**——用户就是这么报的：
                    // 「为什么这个 card 顶部有这么多空」。
                    // 多数工具输出与不带语言的围栏都走这一支（`bash`/`json` 这类标记是少数），
                    // 所以这个空行出现在绝大多数代码块上。
                    //
                    // 另外，`copy`/`copied` 也是官方那两个字面量（上面 520ms 的复原节奏
                    // 同样取自那里），语言占位沿用官方的 `text` 而不是自造词。
                    Text(
                        text = block.lang.ifBlank { "text" },
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    ZhiSmallPill(
                        label = if (copied) "已复制" else "复制",
                        highlighted = copied,
                        onClick = {
                            val ok = Clipboard.copy(context, "ZhiCode 代码块", block.body)
                            copied = ok
                        },
                    )
                }
            }
            Text(
                text = block.body,
                color = scheme.onSurface,
                fontSize = (fontSize.value - 1).coerceAtLeast(9f).sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = (fontSize.value + 4).sp,
            )
        }
    }
}

/** 「已复制」保留多久（参考实现 `MarkdownRenderer` 用的是 520ms）。 */
private const val CopyFeedbackMs = 520L

/**
 * 表格。
 *
 * 列宽用 `weight` **等分**而不是按内容自适应：Compose 没有跨行测量，
 * 要做到真正的内容自适应需要自己两趟测量，代价远大于收益；等分在
 * 模型输出的小表格上观感已经足够，且不会因为某一列内容特别长而挤爆布局。
 */
@Composable
private fun MdTableView(table: MdBlock.Table, fontSize: TextUnit, styles: InlineStyles) {
    val scheme = MiuixTheme.colorScheme
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(scheme.surfaceContainerHighest, RoundedCornerShape(ZhiRadius.inner))
            .padding(horizontal = 2.dp),
    ) {
        Column(Modifier.widthIn(min = (cols * 116).dp)) {
            TableRow(
                cells = table.header,
                cols = cols,
                fontSize = fontSize,
                styles = styles,
                header = true,
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.dividerLine),
            )
            table.rows.forEachIndexed { index, row ->
                TableRow(
                    cells = row,
                    cols = cols,
                    fontSize = fontSize,
                    styles = styles,
                    header = false,
                )
                if (index != table.rows.lastIndex) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(1.dp)
                            .background(scheme.dividerLine.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    cols: Int,
    fontSize: TextUnit,
    styles: InlineStyles,
    header: Boolean,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        for (c in 0 until cols) {
            Text(
                text = rememberInline(cells.getOrElse(c) { "" }, styles),
                color = if (header) scheme.primary else scheme.onSurface,
                fontSize = if (header) fontSize else (fontSize.value - 0.5).sp,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                lineHeight = fontSize * 1.35f,
                modifier = Modifier.width(116.dp).padding(end = if (c == cols - 1) 0.dp else 6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 行内

/** 解析行内时要用的几种颜色，集中传入避免每层都取一遍主题。 */
private class InlineStyles(
    val codeColor: Color,
    val codeBackground: Color,
    val linkColor: Color,
)

@Composable
private fun rememberInlineStyles(fontSize: TextUnit): InlineStyles {
    val scheme = MiuixTheme.colorScheme
    return remember(fontSize, scheme) {
        InlineStyles(
            codeColor = scheme.primary,
            codeBackground = scheme.surfaceContainerHighest,
            linkColor = scheme.primary,
        )
    }
}

/**
 * [inline] 的 `remember` 包装。
 *
 * 每一步都值得：`inline` 会跑一遍 [parseInline]（逐字符扫描 + 大量 substring），
 * 再 `buildAnnotatedString` 建一份带 span 副本的对象。而 `MdBlockView` 的调用点原本是
 * 直接 `inline(block.text, styles)` —— 只要**任何**一次重组（父级状态变化、滚动时
 * 复用、主题切换）就会把每个段落的行内解析重跑一遍。
 *
 * key 用 `(文本, 样式)`：`InlineStyles` 没有 `equals`，靠 [rememberInlineStyles] 返回的
 * 同一个实例保证恒等；它在主题/字号不变时不会换新，所以不会破坏缓存。
 */
@Composable
private fun rememberInline(text: String, styles: InlineStyles): AnnotatedString =
    remember(text, styles) { inline(text, styles) }

/**
 * 流式正文的「前沿淡入」：**最末尾 [TailFadeChars] 个字按离末尾的距离给一个透明度斜坡**。
 *
 * ## 为什么是"空间斜坡"而不是"定时器逐字揭示"
 *
 * 常见的打字机做法是起一个定时器（官方 20ms/字、GetStream 30ms/词），每 tick 把
 * "可见字数" +1。那条路在 Compose 里有三个代价，本工程都实测过：
 *
 *  1. **每秒多 20~50 次重组。** 本轮刚把 `ChatArea` 从 69 次/秒压到 2 次/秒
 *     （见 ChatArea 的说明），定时器等于把这个量在正文节点上再引回来。
 *  2. **会追不上。** 揭示速率写死的话，模型出字快于它时就积压；正文越长越明显
 *     —— 官方自己都得按长度把间隔从 20ms 退化到 32/48ms，正是这个病的症状。
 *  3. **它是"事后补动画"**：文字其实早就到了，只是被压着不放，等于给用户**加延迟**。
 *
 * 空间斜坡把这三条一起解决：透明度是**位置**的函数，不是时间的函数。
 * 于是：
 *
 *  · **零额外帧。** 不需要动画、不需要定时器 —— 斜坡只在文本内容变化时重算一次
 *    （而文本本来就每个 delta 变一次，这是流式的固有成本，不是这里加的）。
 *  · **速率自适应，恒不落后。** 一个字符从末尾往前"走"出斜坡所花的时间是
 *    `斜坡宽度 / 到达速率`：出字快就淡得快，出字慢就淡得慢。永远没有队列。
 *  · **不加延迟。** 文字到达即显示，只是末尾那几个还浅着、随后变实。
 *
 * ## ⚠️⚠️ 斜坡宽度必须按**时间**给，不能按**字数**给（第一版就是这里错了）
 *
 * 第一版把宽度写死成 8 个字，结果**用户完全看不到**。原因不是没生效，是物理：
 *
 *     可见时长 = 斜坡宽度 ÷ 到达速率
 *
 * 8 个字在 30 字/秒下是 265ms（勉强），在 100 字/秒下只有 **80ms**；
 * 而真实的流式每个 delta 常常一次带进好几个字符 —— 最新那 8 个字往往
 * **同一帧内就来齐了**，于是斜坡瞬间走完，再叠加一个很浅的下限（0.52），
 * 就是"什么都看不见"。
 *
 * 所以宽度改成 [TailFadeRate] 实测出来的结果：目标时长固定 [TailFadeMillis]，
 * 宽度 = 到达速率 × 目标时长，只受 [TailFadeMinChars] 下限约束。
 * 于是**快模型和慢模型看到的是同样长的一段淡入**。
 *
 * ⚠️ 这一点仍然是零定时器：速率只用"这次比上次多几个字 / 隔了多少纳秒"估计，
 * 而那两个量在每次 delta 组合时本来就拿得到（文本变了才会有这一次组合）。
 * 没有额外的帧、没有额外的状态写入。
 *
 * ## ⚠️⚠️ 宽度上限**不能**卡在"一行左右"（第二版就是这里错了）
 *
 * 第二版把上限设成 28 个字，用户反馈「输出太快会导致动画不明显」。
 * 原因还是同一个公式：
 *
 *     可见时长 = 斜坡宽度 ÷ 到达速率
 *
 * 上限一卡，**超过 `上限 ÷ 目标时长` 字/秒之后时长就又开始变短**：
 *
 *     40 字/秒 → 17 字        → 420ms   ✅ 目标内
 *    100 字/秒 → 28 字（截断） → 280ms   ❌
 *    273 字/秒 → 28 字（截断） → 103ms   ❌ 几乎看不见
 *
 * （273 字/秒不是假想值：本工程的「调试 · 本地模拟」provider 是 22ms/6 字，
 *   正好就是这个速率 —— 也就是说**调试模式下必然撞到这个上限**。）
 *
 * 所以 [TailFadeMaxChars] 取 120：420ms 一直守到 **285 字/秒**，
 * 覆盖真实模型的全部常见区间与调试 provider。
 *
 * 上限仍然存在，只为一件事兜底：极端突发（一次几百字）不该让斜坡宽到几屏。
 *
 * ## ⚠️ 它不完美的地方（写在这里免得被当成 bug）
 *
 * 流**中途停顿**时（模型在思考）末尾那一截会停在斜坡上、达不到全不透明。
 * 这是刻意的取舍：换成定时器方案停顿时会淡完，但代价是上面那三条。
 * 停顿期间被"停住"的那一截最长就是 [TailFadeMaxChars]（约 3 行），
 * 且下限是 [TailFadeFloor]（不是 0），所以观感是"末尾几行渐变着变浅"，仍然读得清。
 * 流式一结束就不再有斜坡（`streaming` 翻假 → 不再走这条路），所以**定稿后一律全实**。
 */

/** 一个字从最浅走到全实的目标时长。想更明显就调大它（斜坡会变宽）。 */
private const val TailFadeMillis = 420f

/**
 * 斜坡宽度的下限。
 *
 * 不能太小：小到几个字就又回到"看不见"。12 个字在 30 字/秒下 ≈ 400ms，够看见。
 */
private const val TailFadeMinChars = 12

/**
 * 斜坡宽度的上限。
 *
 * ⚠️ 这个值**不是**用来"把停顿时的半透明那一截压短"的 —— 那样做会顺带把
 * 高到达速率下的时长也压短（见类文档里的算式）。它只为一件事兜底：
 * 极端突发（一次几百字）不该让斜坡宽到几屏。
 *
 * 120 字 ≈ 3 行，同时保证 [TailFadeMillis] 一直守到 285 字/秒。
 */
private const val TailFadeMaxChars = 120

/**
 * 两次采样之间的最大间隔（秒）。超过它就不更新速率估计。
 *
 * ## 为什么需要这个
 *
 * 模型"思考"时会停顿几秒。停顿结束后第一个 delta 的间隔里**大部分是思考时间**，
 * 拿它算速率会得出一个很低的数（几字/秒），于是斜坡塌到下限、接下来几个 delta
 * 才慢慢被 EMA 拉回来 —— 观感就是"刚恢复输出那一下，淡入突然变窄又变宽"。
 *
 * 那个样本说的其实不是"流式节奏"，而是"模型想了多久"，所以直接不要它。
 * 0.5s 定得比较宽松：正常流式的 delta 间隔是 20~50ms，差一个量级，
 * 不会把真正"出字变慢"的情形误判成停顿。
 *
 * 早退发生在**已经写回 lastLength / lastAt 之后**，所以下一次采样照常按新时刻算。
 */
private const val TailFadeMaxGapSeconds = 0.5f

/**
 * 斜坡最浅处的透明度。
 *
 * 0.30 —— 比第一版的 0.52 深得多（那半档变化本来就看不出来），
 * 又留得够读：停顿期间最末那个字是 0.30，往前一个字比一个字实。
 */
private const val TailFadeFloor = 0.30f

/**
 * 到达速率的估计器：把"斜坡宽度"从**字数**换算成**时间**。
 *
 * ⚠️ 字段全部是**普通字段，不是 snapshot state** —— 这一点是刻意的：
 * 写 state 会让它自己触发重组，变成"写 → 重组 → 再写"的回路
 * （本工程在 FrameTrace 里记过这个坑）。这里的写只发生在组合期间，
 * 不会让任何东西失效。
 *
 * ⚠️ 同一份文本重复组合时（`length` 没变）必须**原样返回、不更新估计**：
 * 否则 dLen = 0 → 速率被 EMA 拉向 0 → 斜坡莫名缩短。
 */
private class TailFadeRate {
    private var lastLength = 0
    private var lastAtNanos = 0L
    private var charsPerSecond = 0f
    private var width = TailFadeMinChars

    fun widthFor(length: Int, nowNanos: Long): Int {
        if (length == lastLength) return width

        val prevLength = lastLength
        val prevAt = lastAtNanos
        lastLength = length
        lastAtNanos = nowNanos

        // 首帧，或者文本**变短**了（跨过块边界、开了新的一段）：
        // 沿用上一次的宽度，不重估 —— 换段不该让淡入忽长忽短。
        if (prevAt == 0L || length < prevLength) return width

        val seconds = (nowNanos - prevAt) / 1_000_000_000.0
        if (seconds <= 0.0) return width
        // 间隔太长 = 那段时间里模型在思考，不是在出字：这个样本说的不是流式节奏，
        // 拿它算会把速率估得很低、让斜坡塌下去（见 TailFadeMaxGapSeconds 的说明）。
        if (seconds > TailFadeMaxGapSeconds) return width

        val rate = ((length - prevLength) / seconds).toFloat()
        // EMA 平滑：单次突发的 chunk（比如一次来 40 个字）不该让斜坡瞬间变宽。
        charsPerSecond =
            if (charsPerSecond <= 0f) rate else charsPerSecond * 0.6f + rate * 0.4f
        width = (charsPerSecond * TailFadeMillis / 1000f).toInt()
            .coerceIn(TailFadeMinChars, TailFadeMaxChars)
        return width
    }
}

/**
 * [inline] + 可选的前沿淡入。
 *
 * [fade] 为假时**完全等于** [rememberInline]（同一个 key 组合、同一个结果），
 * 所以定稿消息与历史消息一分钱都不多付。
 */
@Composable
private fun rememberInlineFading(
    text: String,
    styles: InlineStyles,
    base: Color,
    fade: Boolean,
    ramp: Int,
): AnnotatedString = remember(text, styles, base, fade, ramp) {
    val annotated = inline(text, styles)
    if (fade) fadeTailOf(annotated, base, ramp) else annotated
}

/**
 * 给末尾 [ramp] 个字叠一层透明度。
 *
 * ⚠️ **跳过已经有自己颜色的字符**（`代码` 的 primary 色、链接色、行内高亮的底色）。
 * 不跳的话，斜坡的颜色会盖掉它们的语法色 —— 末尾那半行里的代码会**短暂掉色**，
 * 看起来像闪了一下，比不淡还糟。宁可让那几个字不淡，也不要颜色乱跳。
 */
private fun fadeTailOf(source: AnnotatedString, base: Color, ramp: Int): AnnotatedString {
    val length = source.text.length
    if (length == 0) return source
    // 只挑"自带颜色或底色"的 span：其余的（粗体/斜体）只是字形变化，没有颜色可盖。
    val colored = source.spanStyles.filter {
        it.item.color != null || it.item.background != null
    }
    val builder = AnnotatedString.Builder(source)
    var touched = false
    for (index in (length - ramp).coerceAtLeast(0) until length) {
        if (colored.any { index >= it.start && index < it.end }) continue
        builder.addStyle(
            SpanStyle(color = base.copy(alpha = fadeAlpha(index, length, ramp))),
            index,
            index + 1,
        )
        touched = true
    }
    // 整段都被语法色占住时不返回新对象：省一次 AnnotatedString 拷贝。
    return if (touched) builder.toAnnotatedString() else source
}

/**
 * 第 [index] 个字的透明度：离末尾越远越实，超过斜坡宽度 [ramp] 就是全实。
 *
 * 线性就够 —— 起点和终点之间的跨度最多 [TailFadeMaxChars] 个字，看不出曲线差别，
 * 而线性少一次 pow、少一个要调的参数。
 *
 * ⚠️ 分母是 `ramp - 1`（不是 `ramp`），这样**末尾那个字正好落在 [TailFadeFloor]**、
 * 往前第 ramp 个字正好全实。写成除以 `ramp` 的话两端都够不到：
 * 末尾那个字够不到下限、最前一个也到不了 1（那个 bug 会让注释与代码对不上，
 * 而且"下限"这个可调参数就失去意义了）。第一版真写出过这个 bug。
 */
private fun fadeAlpha(index: Int, length: Int, ramp: Int): Float {
    if (ramp <= 1) return 1f
    val fromEnd = length - index // 末尾那个字是 1
    if (fromEnd >= ramp) return 1f
    val t = (fromEnd - 1).toFloat() / (ramp - 1)
    return (TailFadeFloor + (1f - TailFadeFloor) * t).coerceAtMost(1f)
}

/**
 * [MdSpan] 列表 → [AnnotatedString]。
 *
 * 链接走 [withLink] + [LinkAnnotation.Url]，由 Compose 自己处理点击与
 * 按下高亮，不需要手动 `clickable` 或 `LocalUriHandler`。
 */
private fun inline(text: String, styles: InlineStyles): AnnotatedString {
    val spans = parseInline(text)
    return buildAnnotatedString {
        spans.forEach { span ->
            val style = when (span.style) {
                MdStyle.PLAIN -> null
                MdStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                MdStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                MdStyle.BOLD_ITALIC -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                )
                MdStyle.CODE -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = styles.codeColor,
                    background = styles.codeBackground,
                )
                MdStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                MdStyle.LINK -> SpanStyle(
                    color = styles.linkColor,
                    textDecoration = TextDecoration.Underline,
                )
            }

            val href = span.href
            if (span.style == MdStyle.LINK && href != null) {
                withLink(
                    LinkAnnotation.Url(
                        url = href,
                        styles = TextLinkStyles(style = style ?: SpanStyle()),
                    ),
                ) {
                    append(span.text)
                }
            } else if (style != null) {
                withStyle(style) { append(span.text) }
            } else {
                append(span.text)
            }
        }
    }
}
