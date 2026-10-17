package com.zhizhu.zhicode.compose.ui.pages.overlays

import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideInsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.DialogWideOutsideMargin
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.SecondaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.ZhiDialogWidth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 环境自检窗口。
 *
 * 存在的理由：真机往返验证很贵。与其在真机上逐项问"哪里不对"，不如让 App 把
 * 运行期事实（真实路径、环境是否装好、native 是否加载、权限实际授予情况）
 * 全部列出来，一键复制，一次就能定位。
 *
 * 报告正文用等宽字体：里面全是路径、字节数、权限名，等宽才对齐可读。
 */
@Composable
fun EnvironmentOverlay(
    open: Boolean,
    report: String,
    runtimeReady: Boolean,
    installing: Boolean,
    progress: Int,
    message: String,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRepair: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme

    OverlayDialog(
        show = open,
        onDismissRequest = onDismiss,
        maxWidth = ZhiDialogWidth.Regular,
        outsideMargin = DialogWideOutsideMargin,
        insideMargin = DialogWideInsideMargin,
    ) {
        // 原来这里有一句 `if (!open) return@OverlayDialog`：它会在关窗那一瞬间把内容
        // 全拆掉，退场那 250~260ms 能看到的就只是一张空壳在缩。`report` / `message`
        // 这些字段关闭时并不清（见 closeEnvironment），直接让它继续画就行。
        DialogShell(
            title = "环境自检",
            fillBody = true,
            titleAction = {
                IconButton(
                    onClick = onDismiss,
                    minHeight = 32.dp,
                    minWidth = 32.dp,
                    cornerRadius = ZhiRadius.floating,
                ) {
                    Icon(
                        painter = ZhiIcons.close,
                        contentDescription = "关闭环境自检",
                        tint = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
            // 状态条固定不滚动，安装进度一眼可见
            prompt = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (runtimeReady) "内置 Termux 环境：已就绪" else "内置 Termux 环境：未安装",
                        color = if (runtimeReady) scheme.primary else scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                    )
                    if (installing) {
                        LinearProgressIndicator(
                            progress = (progress.coerceIn(0, 100)) / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(6.dp),
                        )
                        Text(
                            text = if (message.isBlank()) "$progress%" else "$progress% · $message",
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (message.isNotBlank()) {
                        Text(
                            text = message,
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            actions = {
                TextButton(
                    text = "复制报告",
                    onClick = onCopy,
                    cornerRadius = ZhiRadius.button,
                )
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    if (runtimeReady) {
                        SecondaryButton(text = "重新初始化", onClick = onInstall)
                    } else {
                        PrimaryButton(
                            text = if (installing) "安装中…" else "初始化环境",
                            onClick = onInstall,
                            enabled = !installing,
                        )
                    }
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "诊断报告",
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        // 用 weight 而不是 fillMaxWidth(0.6f)：后者是**固定比例**占位，
                        // 和同一行里两个按钮抢宽度 —— 窄屏上「修复」放不下就把文字
                        // 折成竖排（实测截图上就是竖着的两个字）。weight 让标签
                        // 吃剩余宽度，按钮按内容取宽，两者不再竞争。
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "重新检测",
                        onClick = onRefresh,
                        cornerRadius = ZhiRadius.button,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (runtimeReady) {
                        TextButton(
                            text = "修复",
                            onClick = onRepair,
                            cornerRadius = ZhiRadius.button,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = report.ifBlank { "（无内容，点「重新检测」）" },
                    color = scheme.onBackground,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
