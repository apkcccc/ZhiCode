package com.zhizhu.zhicode.compose.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.model.Attachment
import com.zhizhu.zhicode.compose.model.ChatImage
import com.zhizhu.zhicode.compose.model.EffortLevel
import com.zhizhu.zhicode.compose.model.PermissionMode
import com.zhizhu.zhicode.compose.model.SlashCommand
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.ui.Glass
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiFilledIconButton
import com.zhizhu.zhicode.compose.ui.FloatingBottomShell
import com.zhizhu.zhicode.compose.ui.ZhiPendingImageChip
import com.zhizhu.zhicode.compose.ui.ZhiHorizontalDivider
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.ui.ZhiIconDropdownMenu
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMarkdown
import com.zhizhu.zhicode.compose.ui.ZhiMenuItem
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiSmallPill
import com.zhizhu.zhicode.compose.ui.ZhiTextDropdownChip
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.debug.ZhiFrameTrace
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入器，对应原版 buildComposer()：
 * 斜杠面板 / 附件条 / 多行输入 / 三个 chip + 发送与停止。
 *
 * 外观做成**悬浮输入条**：整个输入器是一块带阴影的圆角面板，浮在对话之上
 * （不再是贴底的普通卡片）。相比原版把 `minLines` 收到 1、页脚压到 34dp，
 * 整体更矮更紧凑。
 */
@Composable
fun Composer(
    state: WorkspaceUiState,
    wide: Boolean,
    glass: Glass,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickSlash: (SlashCommand) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    // ---- `+` 菜单的动作 ----
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
    /**
     * 走**系统文件管理器**（SAF）挑文件附加，可多选。
     *
     * <p>与 [onAttachFile] 的分工：那个是**项目目录内**的浏览器（快，但只覆盖项目里
     * 的东西），这个是系统范围（能挑到下载目录、别的 App 的文档），代价是每次都要
     * 经过系统那个选择器。两条路都留着 —— 只留系统那条的话"附加一个项目里的文件"
     * 要翻半个手机去找。
     */
    onPickSystemFiles: () -> Unit,
    /**
     * 取某个待发附件的图片（输入器里的缩略图）。
     *
     * 走回调而不是把字节放进 `WorkspaceUiState`：几 MB 的 base64 跟着每次 `copy()`
     * 走、还要参与 Compose 的状态比较，是纯粹的浪费（见 ViewModel 里
     * `AttachmentPayload` 的注释）。附件列表本身在 state 里，它一变重组就会重新调用。
     */
    onAttachmentImage: (String) -> ChatImage?,
    // ---- 页脚三个下拉 ----
    onPermissionSelected: (PermissionMode) -> Unit,
    onEffortSelected: (EffortLevel) -> Unit,
    onModelChip: () -> Unit,
    /**
     * **主体调试模式**：输入行下方就地渲染当前输入的 Markdown（用对话流的渲染器）。
     *
     * 之所以做在真实输入器里：Markdown 观感问题（折行、代码块宽度、表格溢出）
     * 只在真实的输入器宽度与真实字体下才看得出来。
     */
    debugMode: Boolean = false,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    ZhiFrameTrace.countRecompose("Composer")
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 横向内缩**不在这里**做：交给 FloatingBottomShell（Common.kt）统一处理。
            // 输入器、任务卡、反馈条是同一列，三块的横向内缩、圆角、阴影必须同源，
            // 否则阴影档位不同会让"可见边缘"差出几 dp，看上去就是"不是同宽的"。
            .padding(top = 4.dp, bottom = 10.dp),
    ) {

        // 斜杠面板展开/收起走动画。
        //
        // ⚠️ 用**官方裸默认**（`fadeIn() + expandVertically()`），不传 animationSpec。
        // 出处：Miuix example 的 `AppContent.kt:529` 与 `component/SwitchSection.kt:80`
        // —— 官方所有"整块展开/收起"都是这一行，一个字都不多。
        //
        // 这里原先传的是 `ZhiMotion.sizeSpec`（`tween(200, DecelerateEasing(1.5))`）。
        // 那套数字本身抄自 Miuix，但它抄的是**弹窗位移退出**那条曲线，而"展开"在
        // Miuix 里走的是 spring —— 拿退出曲线做展开，就是用户在"很多地方该有动画的
        // 没有/很割裂"里感受到的那种不一致。展开/收起这一类比"时长精确相等"更重要的是
        // **与官方同一族曲线**，所以这里整体交还给官方默认。
        //
        // `expandFrom` 也一并去掉：`Alignment.Bottom` 正是 `expandVertically()` 的默认值，
        // 写出来是重复。（面板位于输入框上方，从底边长出来是它该有的方向。）
        AnimatedVisibility(
            visible = state.slashQuery != null && state.slashMatches.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SlashPalette(matches = state.slashMatches, onPick = onPickSlash)
        }

        // 输入框 + 发送键**并排**：输入箱在左（weight 1f），方角发送键在右。
        // 卡片统一走 FloatingBottomShell（Common.kt）：与任务卡、反馈条同一个
        // 圆角 / 阴影 / 横向内缩来源，保证三块"同框"。
        FloatingBottomShell(wide = wide, glass = glass) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = ZhiMotion.sizeSpec,
                    )
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            ) {
            // 附件条随附件增减平滑展开/收起。
            // 同样交还给官方裸默认（出处见上面斜杠面板那一处的说明）：附件条和斜杠面板
            // 是上下相邻的两块，曲线不一致的话，先展开的那块和后展开的那块会各走各的节奏。
            AnimatedVisibility(
                visible = state.attachments.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                // 横向可滚动，而不是 `take(4)`：
                //
                // 多选图片之后 `.take(4)` 变成了一个真 bug —— 第 5 张起会**照样发给模型**，
                // 却在输入器上既看不见也删不掉。用户看到的是"我选了 6 张，怎么只有 4 张"，
                // 而模型收到的却是 6 张。横向滚动让每一张都可达、可删。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        // 图片走缩略图卡片（图片本身就是"芯片"，X 叠在右上角），
                        // 文本附件继续用「图标 + 文件名」的窄条 —— 两者形态不同是**有意的**：
                        // 图片靠画面辨认，文件只能靠名字。
                        val image = onAttachmentImage(attachment.id)
                        if (attachment.isImage && image != null) {
                            ZhiPendingImageChip(image = image, onRemove = { onRemoveAttachment(attachment) })
                        } else {
                            AttachmentChip(attachment) { onRemoveAttachment(attachment) }
                        }
                    }
                }
            }


            // 输入行独占一排（参考图）：透明输入框 + 宽 placeholder
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZhiTextField(
                    value = state.composerText,
                    onValueChange = onTextChange,
                    label = "描述任务或向智蛛提问",
                    useLabelAsPlaceholder = true,
                    // 透明容器 + 透明描边：只留外层方角框
                    colors = TextFieldDefaults.textFieldColors(
                        // 浅色模式下不再用半透明的最高层容器，否则输入框会变成
                        // 一块偏灰的脏色；使用主题明确提供的输入容器层。
                        backgroundColor = if (ZhiColors.isDark()) scheme.surfaceContainerHighest else ZhiColors.panelSurface(),
                        labelColor = scheme.onSurfaceVariantSummary,
                        borderColor = Color.Transparent,
                    ),
                    // 收紧 Miuix TextField 的内部留白，让输入区更矮
                    insideMargin = DpSize(6.dp, 1.dp),
                    // 常规字重的正文样式：Miuix 主题默认文字样式偏粗，会显得比原版重
                    textStyle = MiuixTheme.textStyles.main.copy(
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Normal,
                    ),
                    // 比原版的 minLines = 2 更矮，只占一行起，随内容长高
                    minLines = 1,
                    maxLines = 5,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                )


            }

            // ---- 主体调试模式：输入行下方的 Markdown 实时预览 ----
            // 用生产渲染器 ZhiMarkdown，所以这里看到的排版就是消息里会有的排版。
            if (debugMode) {
                ZhiHorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.outline.copy(alpha = 0.45f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "M↓ 实时预览",
                        color = scheme.primary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "  ${state.composerText.length} 字 · ${state.attachments.size} 附件 · " +
                            (state.slashQuery?.let { "斜杠「$it」" } ?: "无斜杠"),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (state.composerText.isBlank()) {
                    Text(
                        text = "输入框为空；在上面写点 Markdown（# 标题 / - 列表 / **粗体** / `代码` / 表格）即会在此渲染。",
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                } else {
                    ZhiMarkdown(
                        source = state.composerText,
                        bodyFontSize = MiuixTheme.textStyles.body2.fontSize,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            // 底排（参考图）：+ 圆钮 | 权限/推理下拉 | 弹性 | 模型 pill | 停止 | 发送
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `+` 不再直接弹相册：改成 Miuix 的动作菜单（OverlayIconDropdownMenu）。
                //
                // ⚠️ 它必须位于 Miuix `Scaffold` 内 —— Overlay 系列靠 Scaffold 提供的
                // MiuixPopupHost 渲染弹出内容。本工程根部就是 Scaffold。
                // ⚠️ 这里的 summary **必须短**（几个字，不要写成句子）。
                // 弹出面板的宽度是**内容撑出来的**：Miuix 用
                // `DropdownDefaults.MaxItemTextWidth`（实测 216dp）限行内容宽，
                // 再加左右各 `InsideHorizontalPadding`（20dp），所以一条长文案
                // 就能把面板撑到约 256dp —— 在这台 411dp 宽的设备上是 62%。
                // 面板本身没有宽度参数可调（`OverlayIconDropdownMenu` 的
                // `minWidth` 是给触发按钮的），所以**缩短文案是唯一不偏离库默认的收窄办法**。
                // ⚠️ 三个图标必须在这里（组合上下文里）先取出来。
                //
                // 图标改成 `Painter` 之后，取值本身是 `@Composable` 的
                // （`rememberVectorPainter` 要 `remember` 住结果），而下面
                // `remember { }` 的 lambda 不是组合上下文，在里面取会直接编译不过
                // （`@Composable invocations can only happen from the context of
                // a @Composable function`）。取出来当 key 的一部分传进去，
                // 图标变了才重建列表。
                //
                // ⚠️ `photoIcon` 用的是 `ZhiIcons.image`（照片），**不是**
                // `ZhiIcons.floatingBall`（悬浮球）—— 那是两件事，见 ZhiIcons 里的说明。
                val attachFileIcon = ZhiIcons.file
                val photoIcon = ZhiIcons.image
                // 「从系统文件管理器选」用**打开的文件夹**（`directory`）：
                // 它是"去别处挑一份文件"，与上面那条 `file`（一个文档，指项目内的）
                // 和 `files`（合口的文件夹，指我们自己的文件面板）都是不同的字形，
                // 三者在同一张菜单里并排出现，必须一眼分得开。
                val systemFileIcon = ZhiIcons.directory
                ZhiIconDropdownMenu(
                    /*
                     * ⚠️ 这份 `listOf` 必须 `remember`。
                     *
                     * `ZhiIconDropdownMenu` 内部按 `remember(items, ...)` 缓存整份
                     * `DropdownEntry`（含每个条目的 icon 可组合 lambda）。而 `listOf(...)`
                     * 写在参数位置上，**每次重组都是一个新的 List 实例** —— 身份不等，
                     * 那份缓存就永远命中不了，等于没做。输入器随 `state.composerText`
                     * 每敲一个字重组一次，账单按字符数付。
                     *
                     * key 取三个图标加三个回调：菜单的文案是常量，唯一会变的就是
                     * 图标实例与动作本身。回调在 `ChatArea` 里是方法引用与不捕获变量的
                     * lambda，Compose 的 lambda 记忆化让它们跨重组保持同一实例，
                     * 所以这个 `remember` 是真的会命中。
                     */
                    items = remember(
                        attachFileIcon, photoIcon, systemFileIcon,
                        onAttachFile, onPickImage, onPickSystemFiles,
                    ) {
                        listOf(
                            ZhiMenuItem(
                                text = "附加项目文件",
                                summary = "浏览项目目录",
                                icon = attachFileIcon,
                                onClick = onAttachFile,
                            ),
                            ZhiMenuItem(
                                text = "文件管理器",
                                summary = "从系统里挑，可多选",
                                icon = systemFileIcon,
                                onClick = onPickSystemFiles,
                            ),
                            ZhiMenuItem(
                                text = "上传照片",
                                summary = "作为视觉输入",
                                icon = photoIcon,
                                onClick = onPickImage,
                            ),
                        )
                    },
                ) {
                    Icon(
                        painter = ZhiIcons.attach,
                        contentDescription = "添加附件",
                        tint = scheme.onBackgroundVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // 两个下拉 chip 的**外观与旁边的模型药丸完全一致**（10sp / 28dp /
                // 透明底 + 折叠箭头）。别换成 Miuix 的 OverlayDropdownPreference：
                // 那是 16sp / 40dp 的"设置行"，放进这条 34dp 的页脚会撑高整行、
                // 把标签挤到折行（实测过一次）。
                //
                // 三者共用 OverlayIconDropdownMenu：它的 content 可以是任意可组合内容，
                // 所以 `+` 放图标、这两个放"文字 + 箭头"，弹出菜单是同一套原生样式。
                // ⚠️ 这三个**不能**都用 `weight(1f)`（曾经就是）。
                //
                // 均分意味着"每个 chip 固定拿 1/3，不管它需不需要"：「每次询问」只要
                // ~55dp，却和另外两个各占 1/3；忙时停止键再抢走 ~36dp，于是
                // 「推理：自动」被切成人「推理: …」、模型名被切成「deepse」——
                // 都是**词中被切**，看上去像坏了。
                //
                // 现在的分工：这两个标签长度固定，按**自然宽度**排（不参与分配）；
                // 只有模型那一条吃剩余空间。它本来就是唯一可缩短的（`shorten()`），
                // 被压缩时也会规规矩矩地打省略号，而不是把词砍一半。
                ZhiTextDropdownChip(
                    label = state.permissionMode.label,
                    // 与 `+` 菜单同一个理由（见上）：这两个 chip 的 items 也要 `remember`，
                    // 否则 `ZhiIconDropdownMenu` 里那份 `remember(items, ...)` 永远命不中。
                    // key 取「当前选中项」与回调 —— 前者决定哪个条目打勾（外观），
                    // 后者是条目真正要调用的东西。
                    items = remember(state.permissionMode, onPermissionSelected) {
                        PermissionMode.entries.map { mode ->
                            ZhiMenuItem(
                                text = mode.label,
                                summary = mode.detail,
                                selected = mode == state.permissionMode,
                                onClick = { onPermissionSelected(mode) },
                            )
                        }
                    },
                )
                ZhiTextDropdownChip(
                    label = "推理：${state.effort.label}",
                    items = remember(state.effort, onEffortSelected) {
                        EffortLevel.entries.map { level ->
                            ZhiMenuItem(
                                text = level.label,
                                selected = level == state.effort,
                                onClick = { onEffortSelected(level) },
                            )
                        }
                    },
                )
                // 模型这一项**不是**下拉：它要异步拉目录、还要写回配置记录，
                // 表达不了"固定几项"，仍走原有的 ModelPickerOverlay。
                ZhiSmallPill(
                    // 原版是 shorten(profileName + " · " + model, wide?26:16)（MainActivity.java:1393）
                    label = shorten(
                        state.profileName + " · " + state.modelLabel,
                        if (wide) 26 else 16,
                    ),
                    onClick = onModelChip,
                    modifier = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.width(8.dp))
                // 停止键在任务运行时滑入，位置就在发送键左边
                //
                // 可见性看的是 `composerBusy || stopping`：停止指令发出后引擎还在收尾
                // （Bash 子进程要等 pid 与超时，最长能到分钟级），那一整段时间里
                // 这个键必须**留着**——它是用户唯一能重发中断的入口。
                // 只看 composerBusy 的话它会在按下的同一帧消失（见 UiModels.stopping）。
                AnimatedVisibility(

                    visible = state.composerBusy || state.stopping,
                    enter = fadeIn(ZhiMotion.fadeInSpec) + expandHorizontally(ZhiMotion.sizeSpec) +
                        scaleIn(initialScale = 0.7f, animationSpec = ZhiMotion.scaleEnterSpec),
                    exit = fadeOut(ZhiMotion.fadeOutSpec) + shrinkHorizontally(ZhiMotion.sizeSpec) +
                        scaleOut(targetScale = 0.7f, animationSpec = ZhiMotion.scaleExitSpec),
                ) {
                    ZhiFilledIconButton(
                        icon = ZhiIcons.stop,
                        description = if (state.stopping) {
                            "停止指令已发出，再次点击会重发中断"
                        } else {
                            "立即停止当前任务"
                        },
                        onClick = onStop,
                        // 走主题的 error 语义色而不是写死的红：浅色模式会自动换成暗红，
                        // 前景也必须是 onError，否则浅色下红底白字对比度不足。
                        containerColor = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                        // 与发送键同尺寸同方角：两个键在同一个 ActionBar 位置上互换，
                        // 一个圆一个方、一个大一个小，切换时会看到形状在跳。
                        square = true,
                        iconSize = 12.dp,
                        size = ComposerActionSize,
                        // 8dp 而不是原来的 6dp：两个键颜色一红一蓝，挨太近会被读成
                        // "一块被劈成两半的控件"，拉开一点才像两个独立按键。
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                // 方形发送键（参考图：上箭头）
                ZhiFilledIconButton(
                    description = "发送消息",
                    glyph = "↑",
                    glyphSize = 16.sp,
                    onClick = onSend,
                    containerColor = scheme.primary,
                    enabled = state.composerText.isNotBlank(),
                    // ⚠️ 这两个参数是**成对**的：Common.kt 的文档写着
                    // 「`square` 为 true 时改成方角（圆角 10dp），发送键即用这个形态」，
                    // 但这里一直没传 `square` —— 文档与代码互相矛盾了很久。
                    // 现在让代码追上文档：方角 + 与停止键同一个尺寸。
                    square = true,
                    size = ComposerActionSize,
                )

            } // 底排 Row

            } // 悬浮面板内容 Column
        } // FloatingBottomShell
    }
}
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.height(26.dp),
        cornerRadius = ZhiRadius.inner,
        insideMargin = PaddingValues(start = 8.dp, end = 2.dp),
        colors = CardDefaults.defaultColors(
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = if (attachment.isImage) ZhiIcons.image else ZhiIcons.file,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = attachment.label,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(112.dp).padding(start = 4.dp),
            )
            // 直接用 ZhiIconButton 的 compact 参数压成 22dp 点击区，
            // 不再外面套一个固定尺寸的 Box（那样只有内层图标可点，命中区也不对）
            ZhiIconButton(
                icon = ZhiIcons.close,
                description = "移除附件",
                onClick = onRemove,
                iconSize = 12.dp,
                compact = 22.dp,
            )
        }
    }
}

private fun shorten(value: String, max: Int): String =
    if (value.length <= max) value else value.substring(0, max - 1) + "…"

/**
 * 底排「发送 / 停止」两个动作键的边长。
 *
 * 用一个常量而不是各写一个数字：这两个键占据同一个 ActionBar 位置、
 * 由 `AnimatedVisibility` 互换，尺寸不同就会看到按钮在切换时**跳一下**。
 * 之前是 34 与 36 两个值，视觉上就是没对齐。
 *
 * 30dp 比 48dp 的无障碍最小点击区小，这是**有意的**：它与这一排其它元素
 * （页脚 chip 26dp、附件芯片 26dp）同一量级，且外面还有输入器的内边距；
 * 前身 34/36 本来也不满足 48dp，这次只是把它统一并再收小一档。
 */
private val ComposerActionSize = 30.dp
