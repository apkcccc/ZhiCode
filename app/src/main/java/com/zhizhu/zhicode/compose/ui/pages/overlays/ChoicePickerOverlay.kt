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

// -------------------------------------------------------------- 选择窗口

@Composable
fun ChoicePickerOverlay(
    picker: ChoicePickerState?,
    onSubmit: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    onSubmitFreeForm: (String) -> Unit = { onDismiss() },
) {
    val scheme = MiuixTheme.colorScheme
    // 同 PermissionOverlay：退场时 `picker` 已经是 null。
    val shownPicker = rememberLastNonNull(picker)
    var freeForm by remember(shownPicker) { mutableStateOf("") }
    // 选项点击只改这个**本地**选中态，不回调 ViewModel。
    // 之前点击直接调 onSelect()，ViewModel 会立刻提交并把 choicePicker 置空，
    // 于是窗口一点就关、根本没机会按「提交」，也没法改主意。
    var localSelected by remember(shownPicker) { mutableStateOf(-1) }
    // 多选（引擎提问可能带 multiSelect）走这一份本地集合。
    var localMulti by remember(shownPicker) { mutableStateOf(emptySet<Int>()) }
    val multi = shownPicker?.multiSelect == true
    val selectedIndices: Set<Int> = if (multi) {
        if (localMulti.isNotEmpty()) localMulti
        else shownPicker?.options?.indices?.filter { shownPicker.options[it].checked }?.toSet() ?: emptySet()
    } else {
        val single = if (localSelected >= 0) localSelected else shownPicker?.options?.indexOfFirst { it.checked } ?: -1
        if (single >= 0) setOf(single) else emptySet()
    }
    val usingFreeForm = freeForm.isNotBlank()

    OverlayDialog(
        show = picker != null,
        onDismissRequest = onDismiss,
        maxWidth = ZhiDialogWidth.Regular,
        outsideMargin = DialogWideOutsideMargin,
        insideMargin = DialogWideInsideMargin,
    ) {
        val p = shownPicker ?: return@OverlayDialog
        DialogShell(
            title = p.title,
            prompt = if (p.prompt.isEmpty()) null else {
                {
                    Text(
                        text = p.prompt,
                        color = scheme.onBackground,
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            groupBody = true,
            // 自由文本输入框不随选项列表滚动，固定在底板下方
            footer = if (!p.allowFreeForm) null else {
                {
                    ZhiTextField(
                        value = freeForm,
                        onValueChange = { freeForm = it },
                        label = p.freeFormHint,
                        useLabelAsPlaceholder = true,
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            actions = {
                SecondaryButton(text = p.cancelLabel, onClick = onDismiss)
                // 提交按钮是**唯一**的提交入口；没选任何项也没填自由文本时置灰，
                // 让"还不能提交"这件事可见，而不是点了没反应。
                // 文案来自 [ChoicePickerState.submitLabel]：多问题的提问流程中途是「下一步」。
                PrimaryButton(
                    text = p.submitLabel,
                    enabled = usingFreeForm || selectedIndices.isNotEmpty(),
                    onClick = {
                        if (usingFreeForm) onSubmitFreeForm(freeForm)
                        else onSubmit(selectedIndices.sorted())
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            },
        ) {
            p.options.forEachIndexed { index, option ->
                val checked = index in selectedIndices && !usingFreeForm
                // 选中态是「整张卡底色 + 标题色」同时变，两处都走 150ms 淡变：
                // 只淡底或只淡字会出现「字已经变蓝、底还是灰的」中间态（同 ModelPicker 的推导）。
                val cardColor by animateColorAsState(
                    targetValue = if (checked) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                    animationSpec = ZhiMotion.colorSpec,
                    label = "choiceRowCard",
                )
                val titleColor by animateColorAsState(
                    targetValue = if (checked) scheme.primary else scheme.onBackground,
                    animationSpec = ZhiMotion.colorSpec,
                    label = "choiceRowTitle",
                )
                // 每项 = Card 包一个 Miuix BasicComponent：
                // 标题/说明/选中控件的排版完全交给 Miuix
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    cornerRadius = ZhiRadius.card,
                    insideMargin = PaddingValues(0.dp),
                    colors = CardDefaults.defaultColors(
                        color = cardColor,
                        contentColor = scheme.onBackground,
                    ),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    val toggle = {
                        freeForm = ""
                        if (multi) {
                            localMulti = if (index in localMulti) localMulti - index else localMulti + index
                        } else {
                            localSelected = index
                        }
                    }
                    BasicComponent(
                        title = option.label,
                        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
                        summary = option.detail.ifEmpty { null },
                        summaryColor = BasicComponentDefaults.summaryColor(
                            color = scheme.onSurfaceVariantSummary,
                        ),
                        // 行首选择控件统一用 Miuix [Checkbox]，**单选与多选都一样**。
                        //
                        // 为什么不能按"单选用 RadioButton、多选用 Checkbox"来分：
                        // Miuix 0.9.4 的 `RadioButton` 只画那个勾（字节码里唯一的绘制路径
                        // 是 `drawTrimmedCheck`，未选中时 alpha 动画到 0），
                        // 也就是说**未选中时整行不画任何东西** —— 一列选项看起来就是普通文字，
                        // 用户看不出"这里能点"。而 `Checkbox` 有 checked/unchecked 两套
                        // 前景与底色（见 `CheckboxColors`），未选中态是画得出来的。
                        // 所以单选行也要它，否则同一个窗口里"能选/不能选"长得不一样。
                        //
                        // 单选与多选的**行为**差异仍然保留：单选点另一行会换掉原来那一行
                        // （见下面的 toggle），多选可以同时勾多个。控件只是"看得见的入口"。
                        //
                        // 关于尺寸：0.9.4 的 Checkbox 固定 26dp 且是圆的（字节码
                        // `requiredSize(26.dp)`，在调用方 modifier 之后，外面压不动），
                        // 行因此比自己画一个小方框要高一点 —— 这是跟库对齐的代价，认了。
                        //
                        // Checkbox 自己也接同一个 toggle：点框与点整行是同一个动作，
                        // 不会出现"点框没反应"（Compose 的点击会被消费，不会触发两遍）。
                        startAction = {
                            Checkbox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off,
                                onClick = toggle,
                            )
                        },
                        onClick = toggle,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
