package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.MemoryEditor
import com.zhizhu.zhicode.compose.model.MemoryFile
import com.zhizhu.zhicode.compose.model.MemoryState
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsLoadingHint
import com.zhizhu.zhicode.compose.ui.settings.SettingsPageKey
import com.zhizhu.zhicode.compose.ui.settings.SettingsPageStack
import com.zhizhu.zhicode.compose.ui.settings.SettingsSubPage
import com.zhizhu.zhicode.compose.ui.rememberActionBusy
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记忆文件（`ZhiCode.md`）窗口：文件列表与编辑器共用一个弹窗。
 *
 * ## 说明文字必须诚实
 *
 * 引擎**不会**自动读 ZhiCode.md（`SystemPromptBuilder` 里没有相关引用，原版也一样）。
 * 所以顶部提示写的是"写入不会自动注入模型，它需要被读到"，而不是
 * "会自动作为记忆生效"——后者会让用户以为写完就生效了，然后困惑于模型为什么不遵守。
 */
@Composable
fun MemoryPage(
    state: MemoryState?,
    onDismiss: () -> Unit,
    onEdit: (MemoryFile) -> Unit,
    onRunInit: () -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    if (state == null) return
    val editing = state.editing
    // 退出动画期间离场页仍在绘制，那时 editing 已经是 null —— 见 rememberLastNonNull 的说明。
    val shownEditing = rememberLastNonNull(editing)

    // 有未保存的改动时，返回先问一次（顶栏返回、边缘滑动、系统返回都走 onBack）。
    // 判定来自 MemoryEditor.dirty —— 界面不自己记快照，否则保存成功后仍会以为还脏。
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = editing?.dirty == true
    val requestExit: () -> Unit = {
        if (dirty) confirmDiscard = true else onCancelEdit()
    }

    SettingsPageStack(
        // 路径含最底下那一页：栈要靠整条路径算层级与方向。
        path = listOf(
            SettingsPageKey("memory.list", 0),
            if (editing != null) SettingsPageKey("memory.editor", 1) else null,
        ).filterNotNull(),
        onBack = if (editing == null) onDismiss else requestExit,
    ) { key ->
        // ⚠️ 分支必须看**正在渲染的那一页**（key），不能看当前状态。
        val open = if (key.id == "memory.editor") shownEditing else null
        if (open == null) {
            SettingsSubPage(
                title = "记忆文件 · ZhiCode.md",
                onBack = onDismiss,
                action = "完善" to onRunInit,
            ) {
                MemoryFileList(
                    state = state,
                    onEdit = onEdit,
                )
            }
        } else {
            SettingsSubPage(
                title = open.title,
                onBack = requestExit,
                action = "保存" to onSave,
                actionEnabled = !rememberActionBusy("memory.save"),
            ) {
                MemoryEditorBody(
                    editing = open,
                    onBodyChange = onBodyChange,
                )
            }
        }
    }

    UnsavedChangesDialog(
        visible = confirmDiscard,
        onKeepEditing = { confirmDiscard = false },
        onDiscard = {
            confirmDiscard = false
            onCancelEdit()
        },
    )
}

@Composable
private fun MemoryFileList(
    state: MemoryState,
    onEdit: (MemoryFile) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column {
        Text(
            text = "项目级与用户级两份说明文件。写入后**不会**自动注入模型——" +
                "「让 ZhiCode 完善」会让 Agent 读取现有说明与构建清单后直接整理 ZhiCode.md；" +
                "平时的任务里 Agent 也可以自己用 Read 打开它。",
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )

        // 与设置主页同形态：一张分组卡里若干行，每行不再各套一张卡。
        SettingsGroup("说明文件") {
            // 载荷在 IO 上读（见 openMemory）：这段窗口里 files 是空的，
            // 而空列表在这页上不是合法状态（两个作用域恒定存在），所以要显式说明是「在读」。
            if (state.files.isEmpty()) {
                SettingsLoadingHint()
            } else state.files.forEach { file ->
                BasicComponent(
                    title = file.scope.label,
                    titleColor = BasicComponentDefaults.titleColor(
                        color = if (file.exists) scheme.onBackground else scheme.onSurfaceVariantSummary,
                    ),
                    summary = "${file.sizeLabel} · ${file.path}",
                    summaryColor = BasicComponentDefaults.summaryColor(color = scheme.onSurfaceVariantSummary),
                    endActions = {
                        // 未创建的文件也给编辑按钮：点进去就是一份空编辑器（新建）。
                        // 只画图标不给文字的话，用户不容易看出"这里可以创建"。
                        Text(
                            text = if (file.exists) "编辑" else "创建",
                            color = scheme.primary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    onClick = { onEdit(file) },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryEditorBody(
    editing: MemoryEditor,
    onBodyChange: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column {
        Text(
            text = editing.path,
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        // 与设置主页同形态：编辑区放进分组卡（分组卡 insideMargin=0，行自带边距）。
        // 滚动由外层 SettingsSubPage 负责，这里不再套滚动容器。
        SettingsGroup("内容") {
            ZhiTextField(
                value = editing.body,
                onValueChange = onBodyChange,
                label = "ZhiCode.md",
                useLabelAsPlaceholder = false,
                singleLine = false,
                minLines = 12,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .heightIn(min = 260.dp),
            )
        }
        Text(
            text = if (editing.empty) {
                "内容为空。建议写清楚：构建/测试命令、目录结构、项目约定。"
            } else {
                "当前 ${editing.body.length} 字"
            },
            color = if (editing.empty) scheme.error else scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
