package com.zhizhu.zhicode.compose.ui.pages.overlays

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.CopySheetState
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「自由复制」底部抽屉：把一条消息的**原文**摊开，让人自己划词取一段。
 *
 * ## 为什么是抽屉而不是又一条菜单项
 *
 * 长按菜单里的「复制」只能整条复制。而用户想要的常常是中间那三行 ——
 * 一条命令、一段报错、一小段代码。气泡本身又选不中：`Text` 默认不可选，
 * 卡片还接着长按手势（给了 `SelectionContainer` 也会与长按抢事件）。
 * 所以把文本搬到浮层上：抽屉里的 `Text` 包在 [SelectionContainer] 里，
 * 长按可以直接拖选，松手就有系统的复制菜单。
 *
 * ## 为什么用 [OverlayBottomSheet] 而不是 `OverlayDialog`
 *
 * 内容是一条可能很长、也常常比屏幕矮的文本：抽屉从底部滑入、顶栏贴住标题、
 * 高度随内容自适应，比一个居中对话框更省事，也让"临时看一眼、划个词、
 * 划走关掉"这个动作更顺手。
 *
 * ⚠️ 与其它浮层一样，**`show` 与内容要分开传**：退场动画期间
 * `state.copySheet` 已经是 null，但抽屉还要把滑出播完 —— 所以内容用
 * [rememberLastNonNull] 兜住，`show` 传当前是否为 null。
 * 反过来写 `if (sheet != null) OverlayBottomSheet(...)` 会变成硬切。
 *
 * ⚠️ 必须位于 `Scaffold` 的内容里（它内部要在 `MiuixPopupHost` 上渲染）。
 * 挂载点是 `ui/OverlayHost.kt`，与其它浮层同一处。
 */
@Composable
fun CopySheetOverlay(
    sheet: CopySheetState?,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit,
) {
    // 退场期间仍需要一份内容，否则抽屉会先空成一条再滑走。
    val shown = rememberLastNonNull(sheet) ?: return
    OverlayBottomSheet(
        show = sheet != null,
        title = shown.title,
        onDismissRequest = onDismiss,
        // 顶栏右侧就是唯一的动作：整条复制。抽屉本身不承担"提交"语义，
        // 所以不给左侧按钮 —— 拖拽与点遮罩都能关，多一个「取消」只是噪音。
        endAction = {
            TextButton(
                text = "复制全部",
                onClick = onCopyAll,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            // 高度上限：长回复可以几千字，不设上限会把抽屉撑到顶住状态栏，
            // 而且滚动手势要穿过整块内容才生效。留出屏幕的约六成，
            // 超出部分在抽屉内部滚动（`enableNestedScroll` 默认为 true）。
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 原文照抄，不做 Markdown 渲染：这里的目的是**取字**，
                // 渲染成标题/列表反而会把 `#`、`-` 这类标记吃掉，
                // 用户划走的就不再是原来那份文本了。
                Text(
                    text = shown.text,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
