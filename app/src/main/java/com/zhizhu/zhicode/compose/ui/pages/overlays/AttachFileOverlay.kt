package com.zhizhu.zhicode.compose.ui.pages.overlays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.AttachBrowserState
import com.zhizhu.zhicode.compose.model.FileEntry
import com.zhizhu.zhicode.compose.model.FileRoot
import com.zhizhu.zhicode.compose.theme.ZhiSpace
import com.zhizhu.zhicode.compose.ui.panes.FileListRow
import com.zhizhu.zhicode.compose.ui.panes.FilePathBar
import com.zhizhu.zhicode.compose.ui.panes.FileRootSwitcher
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 附加文件 sheet 的高度边界。
 *
 * ⚠️ 这里原来还有一个 `AttachSheetHeightFraction = 0.5f`，面板高度是
 * `clamp(窗口高 × 0.5, 280dp, 480dp)` —— **与内容多少无关**。定高的好处是
 * chrome（根标签栏 / 面包屑 / 过滤框）的位置永远不跳，代价在用户截图里露了出来：
 * 一个只有 5 项的目录，底部空了约三分之一。
 *
 * 现在改成**内容自适应**：少时贴着内容（下限 [AttachSheetHeightFloor]），
 * 多时才长到上限 [AttachSheetHeightCap]。代价说清：从少项目录进到多项目录时，
 * 面板会看到一次高度变化 —— 这是"不留一大片空"必须付的。
 *
 * ⚠️ 光把 `.height(sheetHeight)` 换成 `.heightIn(...)` 是**不够的**：
 * 列表那边的 `weight(1f)` 默认 `fill = true`，会把 Column 一直撑到上限，
 * 等于什么都没改。必须同时写 `weight(1f, fill = false)`（见列表那一行的注释）。
 */
private val AttachSheetHeightCap = 560.dp
private val AttachSheetHeightFloor = 320.dp

/**
 * 「附加项目文件」面板。输入器 `+` 的第一项。
 *
 * ## 它是一台**浏览器**，不是一份搜索结果
 *
 * 这一屏原先长成「搜索框 + 递归搜索出来的扁平结果」。实测的失效很难看：
 * 搜索从 HOME 起递归，而 HOME 下有 Termux 的 `storage/{pictures,dcim,…}` 软链，
 * 空查询的深度上限刚好够到 `storage/pictures/` —— 于是**面板一打开、用户一个字都没敲**，
 * 列出来的是整屏设备截图；而且 BFS 浅层优先，那些照片还排在真正的项目代码前面。
 *
 * 现在改成**浏览当前目录**：一层一层走（`FileBrowser.children`），永远不递归，
 * 而且**共用文件面板那一套外壳**（根切换条 / 面包屑 / 行 / 尺寸文案）——
 * 两边必须长得一样，所以是同一份实现（见 `ui/panes/FileChrome.kt`），不是各写一份。
 *
 * ## 交互
 *
 * - 点**目录** → 进去（`onNavigate`）；
 * - 点**文件** → 附加（`onPick`），**面板不关**：可以连着附加好几个到下一条消息；
 * - 面包屑点任一级 / 「上一级」按钮 → 回上一层，到根就停；
 * - 根切换条 → 项目 / HOME / 共享存储（与文件面板同一套语义）。
 *
 * ## 为什么过滤框只筛当前目录
 *
 * 它是**纯内存过滤**，不重扫磁盘。敲字就递归搜索正是上面那个 bug 的成因，
 * 而且每个字符都要遍历目录，手机上会明显卡顿。
 * 想找别的目录，点面包屑或根切换条过去就行。
 *
 * ## 为什么是底部 Sheet 而不是居中弹窗
 *
 * 内容是可变长的目录列表 —— 这正是居中弹窗最差的场景（完整理由见
 * `ModelPickerOverlay` 顶部注释）：竖屏可用高度小、挡住正在生成的回复、动画只有
 * fade。底部 sheet 自带上滑进场、下拉 / 点背板关闭。过滤框留在**不滚动**的头部
 * （原来就这条规则，sheet 里照样成立），列表吃掉余量并内部滚动。
 *
 * ## 过滤框：胶囊搜索框，而不是表单字段
 *
 * 原来是 `ZhiTextField`（转发 Miuix 通用 `TextField`：16dp 圆角、label 左对齐常显、
 * 默认 16dp 内边距），而且 `fillMaxWidth()` **没带横向内边距** —— 于是它成了整屏
 * 唯一满幅的元素，比下面的文件行和上面的根标签栏都宽一截，读起来还像个小标题。
 *
 * 现在换成 Miuix `InputField`（`SearchBar.kt` 那一节）：胶囊底、自带放大镜与
 * 有字时出现的清除按钮。**侧栏的「搜索会话」用的就是它**，那里的注释写着为什么
 * 不用 `ZhiTextField`；同一屏里两种搜索框长得不一样才是问题，所以统一到它。
 *
 * 标题由 [OverlayBottomSheet] 自己渲染，**不再**套 `DialogShell`（两行标题 +
 * weight 语义对不上，见 ModelPickerOverlay 的说明）。
 */
@Composable
fun AttachFileOverlay(
    open: Boolean,
    browser: AttachBrowserState,
    onFilterChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onSwitchRoot: (FileRoot) -> Unit,
    onPick: (FileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    // 退场时 `browser` 已经被 closeAttachPicker 清成空（而 `open` 也翻了 false），
    // 内容得靠「最后一次非空」兜住 —— 否则退场看到的是空的搜索面板。
    val shownBrowser = rememberLastNonNull(browser.entries.ifEmpty { null })
    OverlayBottomSheet(
        show = open,
        onDismissRequest = onDismiss,
        title = "附加项目文件",
        sheetMaxWidth = 720.dp,
        // 官方默认 insideMargin 为左右各 24dp；与共用文件行自身边距叠加后可读宽度过窄。
        // 由内容行自行控制留白，sheet 外壳不再额外吃掉可用宽度。
        outsideMargin = DpSize(0.dp, 0.dp),
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        // 原来这里有一句 `if (!open) return@OverlayBottomSheet`：它会在关窗那一瞬间
        // 把内容全拆掉，退场那 250~260ms 只是一张空壳在滑下去。
        val visible = browser.visibleEntries.ifEmpty { shownBrowser.orEmpty() }
        val scheme = MiuixTheme.colorScheme
        // 过滤框是否已展开。`InputField` 的必填参数，且承担 API ≤ 27 上的
        // 「先展开再聚焦」兼容职责（见下面那段注释），所以必须是真的状态。
        var filterExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容自适应：少时贴内容、多时才到上限。见两个常量的注释
                // （原先这里是按窗口比例算出来的**定高**）。
                .heightIn(min = AttachSheetHeightFloor, max = AttachSheetHeightCap),
        ) {
            // 1) 根切换条（与文件面板共用）。换根是这一屏最常用的动作之一，
            //    所以放在最上面一行，而不是藏进菜单。
            FileRootSwitcher(
                selected = browser.root,
                onSelect = onSwitchRoot,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZhiSpace.m, vertical = 2.dp),
            )

            // 2) 面包屑。点击面包屑中的任一级即可返回该目录，不再额外显示上一级图标。
            FilePathBar(
                filePath = browser.path,
                onNavigate = onNavigate,
            )

            // 3) 目录内过滤（不滚动头部）。Miuix `InputField` = 胶囊搜索框，
            //    与侧栏「搜索会话」同一形态。
            //
            // ⚠️ 横向内边距**必须**给：不给的话它会满幅横过去，比同屏的面包屑行、
            //    根标签栏和文件行都宽（用户截图里最刺眼的就是这一处）。
            //
            // ⚠️ `expanded` 必须接**真实状态**，不能传常量：API ≤ 27（本工程 minSdk 24）
            //    上 `hasFocusReassignBug` 为 true，未展开时组件是 **disabled** 的，
            //    靠 `onExpandedChange(true)` 先展开再聚焦。传常量的话那个回调是空的，
            //    Android 8.x 上这个框永远点不进去。（与 `Sidebar.kt` 同一处理由。）
            //
            // ⚠️ 代价说清：`InputField` 的 `label` 只在「未展开且为空」时当占位符显示
            //    （源码 `labelText = if (!(query.isNotEmpty() || expanded)) label else ""`），
            //    所以**第一次点开之后**占位文字就让位给放大镜图标。侧栏本来就是这个行为，
            //    两处一致 —— 要的是"同一个搜索框"，不是"每处一套占位符规则"。
            InputField(
                query = browser.filter,
                onQueryChange = onFilterChange,
                onSearch = { },
                expanded = filterExpanded,
                onExpandedChange = { filterExpanded = it },
                label = "在本目录里过滤",
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZhiSpace.m),
            )

            // 4) 一层子项。空时把**原因**说出来，别让面板空着让人以为坏了。
            // ⚠️ `fill = false` 是这一整处改动的**关键**：默认 `fill = true` 会把
            // Column 撑到 heightIn 的上限，少条目时照样是一大片空 —— 等于没改。
            // 置 false 之后列表按内容高，Column 也就按内容高（下限由 heightIn 的 min 托住）。
            Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = ZhiSpace.m),
                    verticalArrangement = Arrangement.spacedBy(ZhiSpace.xs),
                ) {
                    items(
                        items = visible,
                        key = { it.path },
                        contentType = { if (it.directory) "directory" else "file" },
                    ) { entry ->
                        FileListRow(
                            entry = entry,
                            // ⚠️ 点目录是**进去**，点文件才是附加。
                            //    这一条如果反了，用户会发现自己"附加"了一整个目录 ——
                            //    而目录根本读不出文本，只会得到一句"不是文本文件"。
                            onOpen = { if (entry.directory) onNavigate(entry.path) else onPick(entry) },
                            modifier = Modifier.animateItem(),
                            // 尾部不给「重命名/删除」：这里是挑选附件的场景，
                            // 摆两个破坏性动作既没用又危险。
                        )
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                text = emptyMessage(
                                    filter = browser.filter,
                                    note = browser.note,
                                    whileClosing = browser.entries.isEmpty() && shownBrowser != null,
                                ),
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = MiuixTheme.textStyles.body1.fontSize,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                            )
                        }
                    }
                    item { Box(modifier = Modifier.padding(bottom = 8.dp)) }
                }
                VerticalScrollBar(
                    adapter = rememberScrollBarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 空态的文案。
 *
 * ⚠️ 三种原因必须分开说，不能都显示"0 项"：
 *
 * - **过滤没命中** —— 用户自己敲的字，提示他换一个词；
 * - **目录读不出来**（[note] 非空，典型是共享存储没给「所有文件访问权限」）——
 *   这时说"这个目录是空的"是**撒谎**，用户会以为是目录坏了；
 * - **目录真的是空的**。
 *
 * 还有一个只会在退场那 250ms 出现的中间态：`entries` 已被清空、而屏幕上还挂着
 * 最后一次的内容（[whileClosing]）—— 那时什么都不说，免得在滑下去的过程中
 * 闪出一行"这个目录下没有可附加的文件"。
 */
private fun emptyMessage(filter: String, note: String, whileClosing: Boolean): String = when {
    filter.isNotBlank() -> "这个目录里没有匹配「$filter」的文件"
    note.isNotEmpty() -> note
    whileClosing -> ""
    else -> "这个目录下没有可附加的文件"
}
