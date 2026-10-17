package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.SecondaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.ZhiDialogWidth
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「有未保存的改动」的离开确认。
 *
 * ## 为什么需要它
 *
 * 二级页里的编辑器（技能正文、角色卡、记忆文件）都是**未提交的表单**：顶栏返回键与
 * 系统返回键都直接关页，改了一半的内容无声消失。它们此前唯一的"保护"是——
 * 用户从不指望这些页面会保留草稿，于是每次返回都先自己记住"我改了没存"。
 *
 * 现在统一拦一次：有改动就问，没有改动就直接走（不多一步确认）。
 *
 * ## 判定在哪一侧
 *
 * 「有没有改动」由各自的**表单模型**回答（`SkillEditTarget.dirty` /
 * `RoleCardEditor.dirty` / `MemoryEditor.dirty`：当前值与打开时的基准比）。
 * 界面只负责"要不要拦"，不自己记快照 —— 否则保存成功之后界面会一直以为还脏。
 */
@Composable
internal fun UnsavedChangesDialog(
    visible: Boolean,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    // 退出动画期间 visible 已经翻成 false，内容不能跟着空掉。
    var shown by remember { mutableStateOf(visible) }
    if (visible) shown = true
    OverlayDialog(
        show = visible,
        onDismissRequest = onKeepEditing,
        maxWidth = ZhiDialogWidth.Compact,
    ) {
        if (!shown) return@OverlayDialog
        DialogShell(
            title = "放弃未保存的修改？",
            prompt = {
                Text(
                    text = "这一页有还没保存的内容，返回会丢掉它们。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                )
            },
            actions = {
                SecondaryButton(text = "继续编辑", onClick = onKeepEditing)
                PrimaryButton(
                    text = "放弃修改",
                    onClick = onDiscard,
                    modifier = Modifier.padding(start = 8.dp),
                )
            },
        ) { }
    }
}
