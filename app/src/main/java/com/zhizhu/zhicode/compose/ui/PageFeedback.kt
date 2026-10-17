package com.zhizhu.zhicode.compose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「页面级」操作反馈条：[WorkspaceUiState.message] 在**非对话页**上的展示。
 *
 * ## 为什么需要第二个反馈点
 *
 * 反馈条原先只挂在对话页（输入器上方那一条，见 [MessageBar]）。于是：
 * 在文件页按「删除」、在设置页点「保存」、在技能页点「创建」——
 * ViewModel 都老老实实写了 `message`，而**屏幕上什么都不会出现**：
 * 那条 MessageBar 属于对话页，用户此刻并不在对话页上。
 *
 * 现在把同一份 `state.message` 在这两处补齐：
 *
 * - 窄屏工作区、且当前面板不是对话时（文件 / 终端），贴在底部 Tab 栏之上；
 * - 设置整页与它的二级页，贴在页面底部。
 *
 * 宽屏不重复：那时对话面板常驻，[MessageBar] 已经把消息显示出来了。
 *
 * ## 为什么不是 Snackbar
 *
 * 与 [MessageBar] 同一条理由：`SnackbarHost` 是浮在内容之上的覆盖层，会压住
 * 底部控件；这里作为布局的一层参与排版，长高只把内容往上顶。另外它的配色
 * 走主题（`errorContainer` / `surfaceContainerHigh`），与对话页那一条必须**看起来是同一件事**。
 */
@Composable
fun PageFeedback(
    message: String?,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // 淡出期间还要显示旧文案，否则会看到"一截空条滑走"（同 MessageBar）。
    var shown by remember { mutableStateOf(message) }
    var shownError by remember { mutableStateOf(isError) }
    if (message != null) {
        shown = message
        shownError = isError
    }

    // 同一段文案反复出现（连点两次保存）也要重新计时。
    LaunchedEffect(shown) {
        if (shown != null) {
            delay(if (shownError) ERROR_DURATION_MS else INFO_DURATION_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(ZhiMotion.enterSpec) { it / 2 } + fadeIn(ZhiMotion.fadeInSpec),
        exit = slideOutVertically(ZhiMotion.exitSpec) { it / 2 } + fadeOut(ZhiMotion.fadeOutSpec),
        modifier = modifier,
    ) {
        val text = shown ?: return@AnimatedVisibility
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
                shape = RoundedCornerShape(ZhiRadius.card),
                color = if (shownError) scheme.errorContainer else scheme.surfaceContainerHigh,
                contentColor = if (shownError) scheme.error else scheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Text(
                    text = text,
                    color = if (shownError) scheme.error else scheme.onSurface,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    maxLines = MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 与 [MessageBar] 保持同一组时长：错误留得久，普通提示短一些。 */
private const val INFO_DURATION_MS = 2200L
private const val ERROR_DURATION_MS = 4000L

/** 最多三行（同 [MessageBar]）：异常可能把整段堆栈塞进 message。 */
private const val MAX_LINES = 3

/**
 * 页面级反馈的数据源。
 *
 * 为什么用 CompositionLocal 而不是一路加参数：设置整页与它的六个二级页各有自己的
 * 状态类型（`SettingsDraft` / `SkillsState` / `MemoryState` …），它们**都不持有**
 * `WorkspaceUiState.message`。为了显示一条提示而给七个页面的构造器各加三个参数，
 * 既容易漏，也会让每个页面的签名都被这个与业务无关的横切关注点污染。
 *
 * 提供点在 `AppScaffold`（那里才有 ViewModel）；消费点只有 [PageFeedbackSlot]。
 */
data class PageFeedbackData(
    val message: String?,
    val isError: Boolean,
    val onDismiss: () -> Unit,
)

/** 由 `AppScaffold` 提供；没有提供时 [PageFeedbackSlot] 什么也不画。 */
val LocalPageFeedback = androidx.compose.runtime.compositionLocalOf<PageFeedbackData?> { null }

/**
 * 在**整页**底部显示当前反馈（设置页、二级页用它）。
 *
 * 对话页与工作区面板不用它：那两处各自有明确的挂载点（[MessageBar] 与
 * `WorkspacePage` 里的 [PageFeedback]），重复挂会出现两条一模一样的提示。
 */
@Composable
fun PageFeedbackSlot(modifier: Modifier = Modifier) {
    val data = LocalPageFeedback.current ?: return
    PageFeedback(
        message = data.message,
        isError = data.isError,
        onDismiss = data.onDismiss,
        modifier = modifier,
    )
}

/**
 * 在飞动作的快照（[com.zhizhu.zhicode.compose.model.ActionBusy] 的只读视图）。
 *
 * 与 [LocalPageFeedback] 同样的理由：六个二级页都不持有 `WorkspaceUiState`，
 * 而"保存中就别再点了"这条规则对它们全都适用。提供点同样在 `AppScaffold`；
 * 缺省空集合表示什么都不在跑（独立预览、测试环境）。
 */
val LocalActionBusy = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/** 某个页面动作是否在跑；没有提供数据源时恒为 false。 */
@Composable
fun rememberActionBusy(key: String): Boolean = key in LocalActionBusy.current
