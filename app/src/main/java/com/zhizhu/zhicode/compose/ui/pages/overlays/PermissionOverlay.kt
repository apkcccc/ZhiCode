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

// ---------------------------------------------------------------- 工具授权

@Composable
fun PermissionOverlay(
    request: PermissionRequest?,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 退场那 250~260ms 里 `request` 已经是 null，内容不能跟着空掉（否则看起来是
    // 弹窗先缩成一张空壳再淡出）—— 见 rememberLastNonNull。
    val shownRequest = rememberLastNonNull(request)
    // 也按 shownRequest 记：按 request 记的话，关闭那一刻「始终允许」会自己弹回去。
    var alwaysAllow by remember(shownRequest) { mutableStateOf(false) }

    OverlayDialog(
        show = request != null,
        onDismissRequest = onDeny,
        maxWidth = ZhiDialogWidth.Compact,
        outsideMargin = DialogWideOutsideMargin,
        insideMargin = DialogWideInsideMargin,
    ) {
        val req = shownRequest ?: return@OverlayDialog
        DialogShell(
            title = "工具授权",
            groupBody = true,
            actions = {
                SecondaryButton(text = "拒绝", onClick = onDeny)
                PrimaryButton(
                    text = "允许",
                    onClick = { if (alwaysAllow) onAlwaysAllow() else onAllowOnce() },
                    modifier = Modifier.padding(start = 8.dp),
                )
            },
        ) {
            // 工具名 + 说明 + 风险徽标：一个 Miuix BasicComponent 搞定，不再手拼 Row/Column
            BasicComponent(
                title = req.tool,
                titleColor = BasicComponentDefaults.titleColor(color = scheme.onBackground),
                summary = req.subtitle,
                summaryColor = BasicComponentDefaults.summaryColor(
                    color = scheme.onSurfaceVariantSummary,
                ),
                startAction = {
                    Icon(
                        painter = ZhiIcons.tool(req.tool),
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                endActions = {
                    if (req.riskLevel == RiskLevel.HIGH) {
                        // Miuix Badge：容器/文字色走主题，浅色模式自动变浅红底 + 暗红字。
                        // 之前是手写 Surface + Box + Text，且用固定红叠 18% alpha，
                        // 叠在任何底色上都会变成"另一种红"，也不随主题。
                        Badge(
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.error,
                        ) {
                            Text(text = "高风险", fontSize = MiuixTheme.textStyles.footnote1.fontSize)
                        }
                    }
                },
                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            )

            // 详情：Card 提供比弹窗底色更亮的容器
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .heightIn(min = 44.dp, max = 180.dp),
                cornerRadius = ZhiRadius.inner,
                insideMargin = PaddingValues(10.dp),
                colors = CardDefaults.defaultColors(
                    color = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurface,
                ),
            ) {
                Text(
                    text = req.detail,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                Text(
                    text = "始终允许",
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
