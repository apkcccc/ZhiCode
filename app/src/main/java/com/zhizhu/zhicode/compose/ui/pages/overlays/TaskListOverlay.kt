package com.zhizhu.zhicode.compose.ui.pages.overlays

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.model.AgentTask
import com.zhizhu.zhicode.compose.model.TaskState
import com.zhizhu.zhicode.compose.ui.chat.TaskStatusIndicator
import androidx.compose.ui.unit.sp
import com.zhizhu.zhicode.compose.model.ChoicePickerState
import com.zhizhu.zhicode.compose.model.PermissionRequest
import com.zhizhu.zhicode.compose.model.PlanApproval
import com.zhizhu.zhicode.compose.model.RiskLevel
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideInsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideOutsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.SecondaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.ZhiDialogWidth
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiHorizontalDivider
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiMarkdown
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 所有模态窗口都走 Miuix `overlay` 包的 [OverlayDialog]。
 *
 * 为什么不用 `window` 包的 `WindowDialog`：它会创建**独立 Android Window**，
 * 既不会继承 `Scaffold` 的 `popupHost`（内部再用 Miuix 弹层组件就会失效），
 * 也无法参与主窗口的 `LayerBackdrop` 背景采样 —— 弹窗背后的模糊会做不出来。
 * 两者参数集完全一致，换过来无需改调用方形状。
 *
 * ## 位置与动画都交给 Miuix
 *
 * ⚠️ `largeScreen` 只在确实需要官方中央形态的调用点显式传入；它会同时决定
 * DialogContentLayout 的定位与动画，调用点不再额外叠加 AnimatedVisibility。
 *
 * ```
 * if (isLargeScreen) {                // true：大屏规格
 *     scale = 0.8f + 0.2f * progress   //   0.8→1 缩放
 *     alpha = progress
 *     // 进场 folmeSpring(damping = 0.9, response = 0.3)
 * } else {                            // false：手机规格
 *     translationY = (1 - progress) * 屏高   // 从下往上滑入
 *     // 进场 spring(dampingRatio = 0.88, stiffness = 450)
 * }
 * ```
 *
 * 文件面板需要中央形态时，直接按官方 `CenteredOverlayDialogDemo` 传
 * `largeScreen = true`；其它长内容/列表型弹窗不强制中央，继续使用 Miuix 的自动规格。
 *
 * ## 手写降到最低
 *
 * - 列表行 → Miuix [BasicComponent]（`title` / `summary` / `startAction` / `endActions`）
 * - 选中指示 → Miuix [Checkbox]，单选与多选**共用**。不用 Miuix `RadioButton`：
 *   它在未选中时不画任何东西（只画那个勾），单选行会连"这里能点"都看不出来。
 *   单选/多选的差别在**行为**（点另一行是否清掉原来那行），不在控件长相。
 * - 主按钮 → Miuix [Button] + [ButtonDefaults.buttonColorsPrimary]
 * - 次要按钮 → Miuix [Button] + `buttonColors`（与主按钮同形状、只有配色不同）
 * - 开关 → Miuix [Switch]
 *
 * 仅保留一处自定义：左对齐标题。因为 Miuix 的 `title` 参数会把标题强制居中并染色，
 * 与参考图的白字左对齐不一致。
 *
 * 弹窗底色是 `DialogDefaults.backgroundColor` = `Colors.background`，
 * 而深色方案的 `surfaceContainerHigh` **也是同一个值**（从 0.9.4 字节码
 * `darkColorScheme$default` 读出的默认值），卡片直接放在弹窗底上会完全隐身。
 * 解决办法是 `DialogShell(groupBody = true)`：把内容区包进 Miuix `Card`，
 * 由主题提供与弹窗底色的对比度 —— 用纯黑底板会在浅色模式下直接坏掉。
 */

// ---------------------------------------------------------------- 任务清单

/** 任务清单 sheet 的定高参数（含义与取值理由见 ModelPickerOverlay 的同名常量）。 */
private const val TaskSheetHeightFraction = 0.55f
private val TaskSheetHeightCap = 520.dp
private val TaskSheetHeightFloor = 300.dp

/**
 * 任务详情窗口：**全部** Agent 任务，点悬浮任务卡打开。
 *
 * 与悬浮卡的分工：悬浮卡只在输入器上方露出「当前窗口」两条，避免遮挡对话；
 * 窗口里给出完整清单，每条任务的 [AgentTask.detail] 是 **Markdown**，
 * 交给 `ZhiMarkdown` 渲染（标题 / 粗体 / 行内代码 / 列表 / 代码块）。
 *
 * 文本可自由选择：整块内容包在 [SelectionContainer] 里，长按即可选中复制。
 *
 * ## 为什么是底部 Sheet 而不是居中弹窗
 *
 * 原来是居中 [OverlayDialog]：内容是一份**可变长**的清单，弹窗只有 fade+spring
 * 进出，长清单在竖屏下又早早就触发滚动（可用高度比 sheet 小）。
 * 底部 sheet 自带上滑进场、下拉 / 点背板关闭，且与任务卡、模型、技能这些
 * "长列表面板"同一形态（完整理由见 `ModelPickerOverlay` 顶部注释）。
 *
 * 标题由 [OverlayBottomSheet] 自己渲染，**不再**套 `DialogShell`：那会出两行标题，
 * 且它的 `weight(1f)` 依赖有界高度，与 sheet"高度由内容决定"的语义对不上。
 */
@Composable
fun TaskListOverlay(
    tasks: List<AgentTask>,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme

    OverlayBottomSheet(
        show = open,
        onDismissRequest = onDismiss,
        title = "任务清单",
        // 其余参数一律用 Miuix 默认值（同 ModelPickerOverlay：backgroundColor 别动）。
    ) {
        // 「最后一位客人」常驻：`open` 翻成 false 时内容不能空掉，否则退场先缩成空壳。
        // `tasks` 是非空列表，关面板时 ViewModel 不清它，直接去掉原来那句早退即可。
        // 定高：任务从 1 条涨到 10 条时面板高度不跳；高度算的是**内容区**，
        // sheet 的标题行与内边距由组件自己叠加（ModelPickerBody 同款写法）。
        // ⚠️ 窗口高度取 LocalWindowInfo，不能取 BoxWithConstraints 的 maxHeight
        // （sheet 量内容时给的约束不是屏幕高度，见 ModelPickerBody 的实测注释）。
        val windowHeight = LocalWindowInfo.current.containerDpSize.height
        val sheetHeight = (windowHeight * TaskSheetHeightFraction)
            .coerceAtMost(TaskSheetHeightCap)
            .coerceAtLeast(TaskSheetHeightFloor)
        Column(modifier = Modifier.fillMaxWidth().height(sheetHeight)) {
            val done = tasks.count { it.state == TaskState.DONE }
            Text(
                text = "$done / ${tasks.size} 已完成",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            // 清单区吃掉余量并内部滚动（`DialogShell` 中间区同款写法）：
            // 定高列里不 weight 的话，长清单会把「完成」按钮顶出屏幕。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 整块内容可选中：长按选中、拖动扩展选区
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        tasks.forEachIndexed { index, task ->
                            if (index > 0) {
                                ZhiHorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TaskStatusIndicator(state = task.state, size = 14.dp)
                                Text(
                                    text = task.title,
                                    color = if (task.state == TaskState.PENDING) {
                                        scheme.onSurfaceVariantSummary
                                    } else {
                                        scheme.onSurface
                                    },
                                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (task.detail.isNotBlank()) {
                                ZhiMarkdown(
                                    source = task.detail,
                                    modifier = Modifier.padding(start = 21.dp, top = 6.dp),
                                    bodyFontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
            }

            PrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
