@file:OptIn(top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi::class)

package com.zhizhu.zhicode.compose.ui.pages.overlays

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.data.Clipboard
import com.zhizhu.zhicode.compose.model.ChatItem
import com.zhizhu.zhicode.compose.model.ChatKind
import com.zhizhu.zhicode.compose.model.ToolActivity
import com.zhizhu.zhicode.compose.model.WorkspaceTab
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.state.WorkspaceViewModel
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.ui.Glass
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.ui.ZhiMarkdown
import com.zhizhu.zhicode.compose.ui.ZhiSectionLabel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * **全局调试浮层**（debug 构建的「UI 调试」页里打开）。
 *
 * ## 与 UI 调试页的分工
 *
 * UI 调试页是"把组件全摆出来看"（静态画布 + 可互动样例）；
 * 这一层是**跟着真实运行状态走的仪表盘** —— 它就压在工作区之上，边用边看：
 *
 * | 区块 | 回答什么问题 |
 * |---|---|
 * | 状态 | 现在哪个面板 / 哪条会话 / 几条消息 / 工具在跑没有 |
 * | 输入器 | 我打的字到哪去了：字符数、斜杠命令匹配了几条、附件几个、发给谁会怎样 |
 * | **Markdown 实时预览** | 我这段输入渲染出来长什么样（用的就是对话流的那个渲染器） |
 * | 工具调用 | 每个工具的完整状态机：运行中 / 完成 / 失败（退出码）/ 等待授权 |
 * | 对话条目 | 每条消息的类型与长度，定位"这条为什么渲染成这样" |
 * | 环境 | 屏幕尺寸 / 密度 / insets / 当前主题令牌 —— 排版问题先看这几个数 |
 *
 * ## 为什么是"折叠 / 展开"两态
 *
 * 浮层压在工作区上，全展开会挡住内容。所以常态是一个**贴着右缘的小药丸**
 * （只显示最关键的几个计数），点一下展开成面板、再点收起。药丸本身很窄，
 * 不会挡住输入器与对话的主要区域。
 *
 * ## 它不抢输入
 *
 * 浮层只在自己那块矩形里响应手势；其余区域的事件原样透传给下面的工作区。
 * 展开态固定右对齐 + 最大宽度，于是它盖住的是对话右侧而不是输入框。
 */
@Composable
fun ZhiDebugHud(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    glass: Glass,
    topInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val maxWidth = (configuration.screenWidthDp.dp * 0.86f).coerceAtMost(360.dp)

    Box(modifier = modifier.fillMaxSize()) {
        if (state.debugOverlayExpanded) {
            ExpandedHud(
                state = state,
                viewModel = viewModel,
                glass = glass,
                width = maxWidth,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset, end = 8.dp),
            )
        } else {
            CollapsedPill(
                state = state,
                onExpand = { viewModel.setDebugOverlayExpanded(true) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset, end = 8.dp),
            )
        }
    }
}

/** 收起态：一个窄药丸，只显示最要紧的几个计数。 */
@Composable
private fun CollapsedPill(
    state: WorkspaceUiState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val runningTools = state.toolsRunning()
    val label = buildString {
        append("调试")
        append(" · ").append(state.tab.label)
        append(" · 消息 ").append(state.transcript.size)
        if (state.composerText.isNotEmpty()) append(" · 输入 ").append(state.composerText.length)
        if (runningTools > 0) append(" · 运行 ").append(runningTools)
        if (state.composerBusy) append(" · 忙碌")
    }
    Card(
        onClick = onExpand,
        modifier = modifier,
        cornerRadius = ZhiRadius.floating,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = ZhiColors.cardSurface(),
            contentColor = scheme.onSurface,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = ZhiIcons.info,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 展开态：可滚动的仪表盘面板。 */
@Composable
private fun ExpandedHud(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    glass: Glass,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val shape = RoundedCornerShape(ZhiRadius.floating)

    FloatingToolbar(
        modifier = modifier
            .width(width)
            .heightIn(max = 520.dp)
            .then(glass.blur(Modifier, shape, radius = 24f)),
        color = glass.surfaceColor(scheme.surfaceContainer),
        cornerRadius = ZhiRadius.floating,
        outSidePadding = PaddingValues(0.dp),
        shadowElevation = 12.dp,
        showDivider = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---- 标题条：收起 / 关掉 / 打开完整调试页 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = ZhiIcons.info,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "调试仪表盘",
                    fontSize = MiuixTheme.textStyles.main.fontSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                ZhiIconButton(
                    icon = ZhiIcons.expand,
                    description = "打开完整 UI 调试页",
                    onClick = viewModel::openUiDebug,
                    iconSize = 14.dp,
                    compact = 26.dp,
                )
                ZhiIconButton(
                    icon = ZhiIcons.close,
                    description = "关闭调试浮层",
                    onClick = { viewModel.setDebugOverlayEnabled(false) },
                    iconSize = 14.dp,
                    compact = 26.dp,
                )
                ZhiIconButton(
                    icon = ZhiIcons.collapse,
                    description = "收起为药丸",
                    onClick = { viewModel.setDebugOverlayExpanded(false) },
                    iconSize = 14.dp,
                    compact = 26.dp,
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).overScrollVertical(),
                    contentPadding = PaddingValues(bottom = 10.dp),
                ) {
                    item { LiveStatusBlock(state) }
                    item { ComposerBlock(state) }
                    item { MarkdownPreviewBlock(state) }
                    item { ToolsBlock(state) }
                    item { TranscriptBlock(state) }
                    item { EnvironmentBlock(state) }
                    item { QuickActionsBlock(state, viewModel) }
                }
                VerticalScrollBar(
                    adapter = rememberScrollBarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 各区块

/** 浮层内的小节标题。 */
@Composable
private fun HudLabel(text: String) {
    ZhiSectionLabel(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp),
    )
}

/** 浮层内的一行"名 → 值"。 */
@Composable
private fun HudRow(name: String, value: String, valueColor: Color = Color.Unspecified) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = name,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.width(74.dp),
        )
        Text(
            text = value,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            fontFamily = FontFamily.Monospace,
            color = if (valueColor == Color.Unspecified) scheme.onSurface else valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 区块 1：实时状态。 */
@Composable
private fun LiveStatusBlock(state: WorkspaceUiState) {
    val scheme = MiuixTheme.colorScheme
    HudLabel("状态")
    HudRow("面板", state.tab.label)
    HudRow("会话", state.activeSessionId.ifBlank { "（未开始）" })
    HudRow("消息", "${state.transcript.size} 条 · 工具组 ${state.transcript.count { it.kind == ChatKind.TOOL_GROUP }}")
    HudRow("工作", state.workingStatus ?: "空闲")
    HudRow(
        name = "工具",
        value = "运行 ${state.toolsRunning()} · 完成 ${state.toolsCompleted()} · 失败 ${state.toolsFailed()} · 待授权 ${state.toolsAwaiting()}",
        valueColor = if (state.toolsFailed() > 0) ZhiColors.red() else scheme.onSurface,
    )
    HudRow("Agent", "${state.tasks.size} 任务 · 清单${if (state.taskListOpen) "开" else "关"}")
    HudRow("浮层", state.openOverlays().ifEmpty { listOf("无") }.joinToString("/"))
}

/** 区块 2：输入器 —— "我打的字到哪去了"。 */
@Composable
private fun ComposerBlock(state: WorkspaceUiState) {
    val scheme = MiuixTheme.colorScheme
    HudLabel("输入器")
    HudRow("文本", "${state.composerText.length} 字 · ${state.composerText.lineCount()} 行")
    HudRow(
        name = "状态",
        value = if (state.composerBusy) "任务运行中（发送=预输入排队 ${state.pendingInputs.size} 条）" else "空闲（发送=立即执行）",
        valueColor = if (state.composerBusy) scheme.primary else scheme.onSurface,
    )
    HudRow("附件", if (state.attachments.isEmpty()) "无" else state.attachments.joinToString("/") { it.label })
    HudRow(
        name = "斜杠",
        value = state.slashQuery?.let { "「$it」→ ${state.slashMatches.size} 项" } ?: "未触发",
        valueColor = if (state.slashQuery != null) scheme.primary else scheme.onSurface,
    )
    HudRow("发送目标", "${state.profileName} · ${state.modelLabel}")
    if (state.pendingInputs.isNotEmpty()) {
        HudRow("预输入队列", state.pendingInputs.joinToString(" | ") { it.text.take(18) })
    }
}

/** 区块 3：**Markdown 实时预览** —— 输入的东西用对话流的渲染器画一遍。 */
@Composable
private fun MarkdownPreviewBlock(state: WorkspaceUiState) {
    val scheme = MiuixTheme.colorScheme
    val source = state.composerText
    HudLabel("Markdown 实时预览（对话流渲染器）")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        cornerRadius = ZhiRadius.inner,
        insideMargin = PaddingValues(10.dp),
        colors = CardDefaults.defaultColors(
            color = ZhiColors.cardInnerSurface(),
            contentColor = scheme.onSurface,
        ),
    ) {
        if (source.isBlank()) {
            Text(
                text = "输入框为空。在输入框里写 Markdown（# 标题、- 列表、**粗体**、`代码`、表格…），" +
                    "这里会立刻显示它在对话流里的样子。",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            )
        } else {
            // 用的就是对话流的渲染器：所以这里的排版问题就是消息里会有的问题。
            ZhiMarkdown(source = source, bodyFontSize = MiuixTheme.textStyles.footnote2.fontSize)
        }
    }
}

/** 区块 4：工具调用 —— 每个工具的完整状态机。 */
@Composable
private fun ToolsBlock(state: WorkspaceUiState) {
    val scheme = MiuixTheme.colorScheme
    val tools = state.allTools()
    HudLabel("工具调用（${tools.size}）")
    if (tools.isEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                text = "本次会话还没有工具调用。发一条需要工具的任务，这里会逐条列出来。",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            )
        }
        return
    }
    tools.forEach { tool ->
        val status = tool.statusLabel()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = when {
                        tool.awaitingPermission -> ZhiIcons.awaiting
                        !tool.completed -> ZhiIcons.pending
                        tool.failed -> ZhiIcons.failed
                        else -> ZhiIcons.done
                    },
                    contentDescription = null,
                    tint = when {
                        tool.awaitingPermission -> scheme.primary
                        !tool.completed -> scheme.primary
                        tool.failed -> ZhiColors.red()
                        else -> ZhiColors.green()
                    },
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = tool.displayName,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 5.dp),
                )
                Text(
                    text = " " + tool.summary,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.padding(start = 17.dp)) {
                Text(
                    text = status,
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = when {
                        tool.failed -> ZhiColors.red()
                        tool.awaitingPermission -> scheme.primary
                        !tool.completed -> scheme.primary
                        else -> scheme.onSurfaceVariantSummary
                    },
                )
                if (tool.additions > 0 || tool.deletions > 0) {
                    Text(
                        text = "  +${tool.additions} −${tool.deletions}",
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = "  cid=${tool.id}",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
            if (tool.output.isNotBlank()) {
                Text(
                    text = tool.output.take(160).let { if (tool.output.length > 160) "$it…" else it },
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 17.dp, top = 1.dp),
                )
            }
        }
    }
}

/** 区块 5：对话条目 —— 每条消息的类型与长度。 */
@Composable
private fun TranscriptBlock(state: WorkspaceUiState) {
    val scheme = MiuixTheme.colorScheme
    HudLabel("对话条目（最近 ${minOf(state.transcript.size, 8)}/${state.transcript.size}）")
    if (state.transcript.isEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                text = "对话流为空（界面上此时显示的是空状态）。",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            )
        }
        return
    }
    state.transcript.takeLast(8).reversed().forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.kind.name,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontWeight = FontWeight.Medium,
                color = when (item.kind) {
                    ChatKind.ERROR -> ZhiColors.red()
                    ChatKind.INFO -> ZhiColors.amber()
                    else -> scheme.onSurface
                },
                modifier = Modifier.width(72.dp),
            )
            Text(
                text = item.digest(),
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 区块 6：环境 —— 排版问题先看这几个数。 */
@Composable
private fun EnvironmentBlock(state: WorkspaceUiState) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scheme = MiuixTheme.colorScheme
    HudLabel("环境")
    HudRow("屏幕", "${configuration.screenWidthDp}×${configuration.screenHeightDp} dp · ${configuration.screenWidthDp >= 600}（宽屏）")
    HudRow("密度", "${density.density} · fontScale ${density.fontScale}")
    HudRow("主题", "浅色 ${ZhiColors.isDark()} · primary ${hex(scheme.primary)}")
    HudRow("背景", "backdrop ${hex(ZhiColors.backdrop())} · panel ${hex(ZhiColors.panelSurface())}")
    HudRow("工程", state.projectName)
    HudRow("路径", state.projectPath)
    HudRow("环境", if (state.runtimeReady) "Termux 就绪" else "未就绪 ${state.runtimeProgress}% ${state.runtimeMessage}")
}

/** 区块 7：快捷动作 —— 不必退出去就能改状态。 */
@Composable
private fun QuickActionsBlock(state: WorkspaceUiState, viewModel: WorkspaceViewModel) {
    val context = LocalContext.current
    var lastAction by remember { mutableStateOf("（还没点过）") }
    HudLabel("快捷动作")
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactAction("对话") { viewModel.selectTab(WorkspaceTab.CHAT); lastAction = "切到对话" }
            CompactAction("终端") { viewModel.selectTab(WorkspaceTab.TERMINAL); lastAction = "切到终端" }
            CompactAction("文件") { viewModel.selectTab(WorkspaceTab.FILES); lastAction = "切到文件" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactAction("侧栏") { viewModel.openSidebar(); lastAction = "打开侧栏" }
            CompactAction("设置") { viewModel.openSettings(); lastAction = "打开设置" }
            CompactAction("调试页") { viewModel.openUiDebug(); lastAction = "打开 UI 调试页" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 主体调试模式的开关也放在这里：它在真实界面上生效，所以最顺手的做法是
            // 边看着界面边开关，而不是先退回调试页。
            CompactAction(if (state.debugAppMode) "主体调试：开" else "主体调试：关") {
                viewModel.setDebugAppMode(!state.debugAppMode)
                lastAction = if (state.debugAppMode) "关闭主体调试模式" else "打开主体调试模式"
            }
            CompactAction("收起") { viewModel.setDebugOverlayExpanded(false); lastAction = "收起浮层" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactAction("复制快照") {
                val text = state.snapshotText()
                val ok = Clipboard.copy(context, "ZhiCode 调试快照", text)
                Toast.makeText(
                    context,
                    if (ok) "已复制调试快照" else "复制失败：内容为空或剪贴板不可用",
                    Toast.LENGTH_SHORT,
                ).show()
                lastAction = "复制快照（${text.length} 字）"
            }
            CompactAction("收起") { viewModel.setDebugOverlayExpanded(false); lastAction = "收起浮层" }
        }
        Text(
            text = "最近动作：$lastAction",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
        )
    }
}

/** 浮层内的窄按钮（比 TextButton 更省横向空间）。 */
@Composable
private fun CompactAction(label: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(
        onClick = onClick,
        cornerRadius = ZhiRadius.inner,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        colors = CardDefaults.defaultColors(
            color = ZhiColors.cardInnerSurface(),
            contentColor = scheme.onSurface,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Text(text = label, fontSize = MiuixTheme.textStyles.footnote2.fontSize, fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------ 取值助手

private fun WorkspaceUiState.allTools(): List<ToolActivity> = transcript.flatMap { it.tools }

private fun WorkspaceUiState.toolsRunning(): Int =
    allTools().count { !it.completed && !it.awaitingPermission }

private fun WorkspaceUiState.toolsCompleted(): Int =
    allTools().count { it.completed && !it.failed }

private fun WorkspaceUiState.toolsFailed(): Int = allTools().count { it.failed }

private fun WorkspaceUiState.toolsAwaiting(): Int = allTools().count { it.awaitingPermission }

/** 当前打开了哪些浮层（只读判断，用来确认"刚才那一按有没有生效"）。 */
private fun WorkspaceUiState.openOverlays(): List<String> = buildList {
    if (apiConfig != null) add("API配置")
    if (mcpConfig != null) add("MCP")
    if (skills != null) add("技能")
    if (roleCards != null) add("角色卡")
    if (memory != null) add("记忆")
    if (modelPicker != null) add("模型")
    if (environmentOpen) add("环境")
    if (attachPickerOpen) add("附加")
    if (taskListOpen) add("任务")
    if (settingsOpen) add("设置")
    if (uiDebugOpen) add("UI调试")
}

private fun ToolActivity.statusLabel(): String = when {
    awaitingPermission -> "等待授权"
    !completed -> if (elapsedMs > 0) "运行中 ${elapsedMs / 1000.0}s" else "运行中"
    failed -> "失败" + (exitCode?.let { " · 退出码 $it" } ?: "")
    else -> "完成" + (if (elapsedMs > 0) " · ${elapsedMs / 1000.0}s" else "")
}

/** 对话条目的紧凑摘要：类型之外还能看出"这条为什么这样渲染"。 */
private fun ChatItem.digest(): String = buildString {
    append("body ").append(body.length)
    append(" · think ").append(thinking.length)
    if (streaming) append(" · streaming")
    if (tools.isNotEmpty()) append(" · tools ").append(tools.size)
    if (contextTokens >= 0) append(" · ctx ").append(contextTokens).append("/").append(contextWindow)
    append(" · id=").append(id)
}

private fun String.lineCount(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1

private fun hex(color: Color): String = String.format(
    "#%02X%02X%02X",
    (color.red * 255).toInt(),
    (color.green * 255).toInt(),
    (color.blue * 255).toInt(),
)

/** 整份调试快照文本（复制到剪贴板用；出问题时贴进 issue 就够复现了）。 */
private fun WorkspaceUiState.snapshotText(): String = buildString {
    appendLine("== ZhiCode 调试快照 ==")
    appendLine("面板：${tab.label}")
    appendLine("会话：${activeSessionId.ifBlank { "（未开始）" }} · 消息 ${transcript.size}")
    appendLine("工作：${workingStatus ?: "空闲"} · composerBusy=$composerBusy")
    appendLine("工具：运行 ${toolsRunning()} / 完成 ${toolsCompleted()} / 失败 ${toolsFailed()} / 待授权 ${toolsAwaiting()}")
    appendLine("输入：${composerText.length} 字 · 附件 ${attachments.size} · 斜杠 ${slashQuery ?: "未触发"}")
    appendLine("模型：$profileName · $modelLabel")
    appendLine("上下文：$contextTokens / $contextWindow")
    appendLine("浮层：${openOverlays().ifEmpty { listOf("无") }.joinToString("/")}")
    appendLine("-- 工具 --")
    allTools().forEach { appendLine("  ${it.displayName} ${it.summary} :: ${it.statusLabel()}") }
    appendLine("-- 消息 --")
    transcript.forEach { appendLine("  ${it.kind} ${it.digest()}") }
    appendLine("-- 输入全文 --")
    append(composerText)
}
