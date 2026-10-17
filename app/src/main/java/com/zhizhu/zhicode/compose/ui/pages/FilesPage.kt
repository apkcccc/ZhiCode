package com.zhizhu.zhicode.compose.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhizhu.zhicode.compose.model.FileDeletePrompt
import com.zhizhu.zhicode.compose.model.FileEntry
import com.zhizhu.zhicode.compose.model.FileNameForm
import com.zhizhu.zhicode.compose.model.FileFormat
import com.zhizhu.zhicode.compose.model.FileRoot
import com.zhizhu.zhicode.compose.model.FileListOptions
import com.zhizhu.zhicode.compose.model.FileSortKey
import com.zhizhu.zhicode.compose.model.fileRangeSelection
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMaterialIcons
import com.zhizhu.zhicode.compose.ui.ZhiNoticeBar
import com.zhizhu.zhicode.compose.ui.ZhiNoticeTone
import com.zhizhu.zhicode.compose.ui.Glass
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.panes.DeleteConfirmDialog
import com.zhizhu.zhicode.compose.ui.panes.FileListRow
import com.zhizhu.zhicode.compose.ui.panes.FilePathBar
import com.zhizhu.zhicode.compose.ui.panes.FileRootSwitcher
import com.zhizhu.zhicode.compose.ui.panes.FileRowSidePadding
import com.zhizhu.zhicode.compose.ui.panes.NewEntryDialog
import com.zhizhu.zhicode.compose.ui.panes.RenameEntryDialog
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage

@Composable
fun FilesPage(
    filePath: String,
    entries: List<FileEntry>,
    onOpen: (FileEntry) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyNote: String = "",
    root: FileRoot = FileRoot.HOME,
    listOptions: FileListOptions = FileListOptions(),
    onQueryChange: (String) -> Unit = {},
    onSortChange: (FileSortKey, Boolean) -> Unit = { _, _ -> },
    onToggleSortDirection: () -> Unit = {},
    onSwitchRoot: (FileRoot) -> Unit = {},
    nameForm: FileNameForm? = null,
    onNewEntry: () -> Unit = {},
    onRename: (FileEntry) -> Unit = {},
    onNameDraftChange: (String) -> Unit = {},
    onSubmitName: (Boolean) -> Unit = {},
    onCancelName: () -> Unit = {},
    deletePrompt: FileDeletePrompt? = null,
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    sharedStorageGranted: Boolean = true,
    onGrantSharedStorage: () -> Unit = {},
    selection: Set<String> = emptySet(),
    selectionMode: Boolean = false,
    fileClipboardCount: Int = 0,
    fileClipboardMove: Boolean = false,
    onPasteFiles: () -> Unit = {},
    onLongPressEntry: (FileEntry) -> Unit = {},
    onToggleEntry: (FileEntry) -> Unit = {},
    onSetSelection: (Set<String>) -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRenameSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onAttachSelected: () -> Unit = {},
    active: Boolean = true,
) {
    val effectiveSelectionMode = selectionMode || selection.isNotEmpty()
    BackHandler(active && effectiveSelectionMode, onClearSelection)
    Surface(modifier.fillMaxSize(), color = ZhiColors.panelSurface()) {
        Column(Modifier.fillMaxSize()) {
            var pagerRoot by remember { mutableStateOf(root) }
            LaunchedEffect(root) { pagerRoot = root }
            FileRootSwitcher(pagerRoot, { selected ->
                pagerRoot = selected
                onSwitchRoot(selected)
            })
            FileRootPager(
                selectedRoot = root,
                onSwitchRoot = onSwitchRoot,
                modifier = Modifier.fillMaxWidth().weight(1f),
                filePath = filePath,
                entries = entries,
                emptyNote = emptyNote,
                sharedStorageGranted = sharedStorageGranted,
                onGrantSharedStorage = onGrantSharedStorage,
                listOptions = listOptions,
                onQueryChange = onQueryChange,
                onSortChange = onSortChange,
                onToggleSortDirection = onToggleSortDirection,
                selection = selection,
                selectionMode = effectiveSelectionMode,
                fileClipboardCount = fileClipboardCount,
                fileClipboardMove = fileClipboardMove,
                onPasteFiles = onPasteFiles,
                onOpen = onOpen,
                onNavigate = onNavigate,
                onNewEntry = onNewEntry,
                onLongPressEntry = onLongPressEntry,
                onToggleEntry = onToggleEntry,
                onSetSelection = onSetSelection,
                onToggleSelectAll = onToggleSelectAll,
                onClearSelection = onClearSelection,
                onPageSelected = { pagerRoot = it },
            )
        }
    }
    NewEntryDialog(nameForm?.takeIf { it.target == null }, onNameDraftChange, onSubmitName, onCancelName)
    RenameEntryDialog(nameForm?.takeIf { it.target != null }, onNameDraftChange, { onSubmitName(false) }, onCancelName)
    DeleteConfirmDialog(deletePrompt, onConfirmDelete, onCancelDelete)
}

@Composable
private fun FileRootPager(
    selectedRoot: FileRoot,
    onSwitchRoot: (FileRoot) -> Unit,
    modifier: Modifier,
    filePath: String,
    entries: List<FileEntry>,
    emptyNote: String,
    sharedStorageGranted: Boolean,
    onGrantSharedStorage: () -> Unit,
    listOptions: FileListOptions,
    onQueryChange: (String) -> Unit,
    onSortChange: (FileSortKey, Boolean) -> Unit,
    onToggleSortDirection: () -> Unit,
    selection: Set<String>,
    selectionMode: Boolean,
    fileClipboardCount: Int,
    fileClipboardMove: Boolean,
    onPasteFiles: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onNavigate: (String) -> Unit,
    onNewEntry: () -> Unit,
    onLongPressEntry: (FileEntry) -> Unit,
    onToggleEntry: (FileEntry) -> Unit,
    onSetSelection: (Set<String>) -> Unit,
    onToggleSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onPageSelected: (FileRoot) -> Unit = {},
) {
    val roots = remember { FileRoot.entries.toList() }
    val pagerState = rememberPagerState(pageCount = { roots.size })
    val currentSelectedRoot by rememberUpdatedState(selectedRoot)
    var pagerInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, selectedRoot) {
        val target = roots.indexOf(selectedRoot).coerceIn(0, roots.lastIndex)
        if (!pagerInitialized) {
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            pagerInitialized = true
        } else if (pagerState.currentPage != target) {
            pagerState.springAnimateToPage(target)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                roots.getOrNull(page)?.let { root ->
                    onPageSelected(root)
                    if (!scrolling && root != currentSelectedRoot) onSwitchRoot(root)
                }
            }
    }
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = PagerNavigationSpringSpec,
    )
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = false,
        beyondViewportPageCount = 0,
        flingBehavior = flingBehavior,
        key = { roots[it] },
        pageContent = { page ->
            FileBrowserList(
                filePath, entries, roots[page], emptyNote, sharedStorageGranted, onGrantSharedStorage,
                listOptions, onQueryChange, onSortChange, onToggleSortDirection,
                selection, selectionMode, fileClipboardCount, fileClipboardMove, onPasteFiles, onOpen, onNavigate, onNewEntry,
                onLongPressEntry, onToggleEntry, onSetSelection, onToggleSelectAll, onClearSelection,
            )
        },
    )
}

@Composable
private fun FileBrowserList(
    filePath: String, entries: List<FileEntry>, root: FileRoot, emptyNote: String,
    sharedStorageGranted: Boolean, onGrantSharedStorage: () -> Unit,
    listOptions: FileListOptions, onQueryChange: (String) -> Unit,
    onSortChange: (FileSortKey, Boolean) -> Unit, onToggleSortDirection: () -> Unit,
    selection: Set<String>, selectionMode: Boolean,
    fileClipboardCount: Int, fileClipboardMove: Boolean, onPasteFiles: () -> Unit,
    onOpen: (FileEntry) -> Unit, onNavigate: (String) -> Unit, onNewEntry: () -> Unit,
    onLongPressEntry: (FileEntry) -> Unit, onToggleEntry: (FileEntry) -> Unit,
    onSetSelection: (Set<String>) -> Unit, onToggleSelectAll: () -> Unit, onClearSelection: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    val currentSelection by rememberUpdatedState(selection)
    val currentMode by rememberUpdatedState(selectionMode)
    val setSelection by rememberUpdatedState(onSetSelection)
    val enterSelection by rememberUpdatedState(onLongPressEntry)
    val allSelected = entries.isNotEmpty() && entries.all { it.path in selection }
    val sortMenuEntries = listOf(
        DropdownEntry(items = FileSortKey.entries.map { key ->
            DropdownItem(key.label, selected = listOptions.sortKey == key, onClick = { onSortChange(key, listOptions.descending) })
        }),
        DropdownEntry(items = listOf(
            DropdownItem("正序", selected = !listOptions.descending, onClick = { if (listOptions.descending) onToggleSortDirection() }),
            DropdownItem("倒序", selected = listOptions.descending, onClick = { if (!listOptions.descending) onToggleSortDirection() }),
        )),
    )
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = FileRowSidePadding), verticalAlignment = Alignment.CenterVertically) {
        if (selectionMode) {
            ZhiIconButton(ZhiIcons.close, "退出选择", onClearSelection, compact = 40.dp)
            Text("已选择 ${selection.size} 项", fontSize = MiuixTheme.textStyles.main.fontSize, modifier = Modifier.weight(1f))
            ZhiIconButton(
                icon = ZhiIcons.listCount,
                description = if (allSelected) "取消全选" else "全选",
                onClick = onToggleSelectAll,
                compact = 40.dp,
                tint = if (allSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackgroundVariant,
                background = if (allSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
            )
        } else {
            ZhiTextField(
                value = listOptions.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 48.dp),
                label = "搜索文件",
                useLabelAsPlaceholder = true,
                singleLine = true,
                insideMargin = androidx.compose.ui.unit.DpSize(8.dp, 0.dp),
                leadingIcon = { Icon(ZhiIcons.search, "搜索文件", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (listOptions.query.isNotEmpty()) {
                    { ZhiIconButton(ZhiIcons.close, "清除搜索", { onQueryChange("") }, compact = 32.dp, iconSize = 16.dp) }
                } else null,
            )
            if (fileClipboardCount > 0) ZhiIconButton(ZhiIcons.paste, if (fileClipboardMove) "粘贴移动的 $fileClipboardCount 项" else "粘贴复制的 $fileClipboardCount 项", onPasteFiles, compact = 40.dp)
            ZhiIconButton(icon = ZhiIcons.newEntry, description = "新建", onClick = onNewEntry, compact = 40.dp, tint = MiuixTheme.colorScheme.primary)
            OverlayIconDropdownMenu(sortMenuEntries, minHeight = 40.dp, minWidth = 44.dp, collapseOnSelection = true) {
                Icon(ZhiMaterialIcons.Sort, "排序与显示方式", modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    FilePathBar(filePath, onNavigate)
    if (root == FileRoot.SHARED && !sharedStorageGranted) {
        ZhiNoticeBar(
            text = "共享存储需要「所有文件访问权限」",
            modifier = Modifier.padding(horizontal = 28.dp),
            tone = ZhiNoticeTone.WARN,
            content = { TextButton("去授权", onGrantSharedStorage) },
        )
    }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = if (selectionMode) 96.dp else 16.dp),
            modifier = Modifier.fillMaxSize().pointerInput(filePath) {
                fun indexAt(y: Float): Int = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index ?: -1
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val anchor = indexAt(down.position.y)
                    val item = currentEntries.getOrNull(anchor) ?: return@awaitEachGesture
                    val base = currentSelection.toSet()
                    val selecting = item.path !in base
                    val endX = size.width - FileRowSidePadding.toPx()
                    val hotspot = currentMode && down.position.x in (endX - 26.dp.toPx())..endX
                    var pointer = down.position
                    var scrolling: Job? = null
                    var dragging = false
                    try {
                        if (!hotspot) {
                            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                                }
                            }
                            if (cancelled != null) return@awaitEachGesture
                            enterSelection(item)
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { if (dragging || !hotspot) change.consume(); break }
                            pointer = change.position
                            if (!dragging && (pointer - down.position).getDistance() <= viewConfiguration.touchSlop) continue
                            change.consume()
                            if (!dragging) {
                                dragging = true
                                scrolling = scope.launch {
                                    val edge = 48.dp.toPx()
                                    while (true) {
                                        val height = listState.layoutInfo.viewportEndOffset.toFloat()
                                        val delta = when {
                                            pointer.y < edge -> -((edge - pointer.y) / edge).coerceIn(0f, 1f) * 12.dp.toPx()
                                            pointer.y > height - edge -> ((pointer.y - height + edge) / edge).coerceIn(0f, 1f) * 12.dp.toPx()
                                            else -> 0f
                                        }
                                        if (delta != 0f) {
                                            listState.scrollBy(delta)
                                            val visible = listState.layoutInfo.visibleItemsInfo
                                            val end = indexAt(pointer.y).takeIf { it >= 0 } ?: (if (delta < 0f) visible.firstOrNull()?.index else visible.lastOrNull()?.index)
                                            if (end != null) setSelection(fileRangeSelection(currentEntries.map { it.path }, anchor, end, base, selecting))
                                        }
                                        delay(16)
                                    }
                                }
                            }
                            val end = indexAt(pointer.y)
                            if (end >= 0) setSelection(fileRangeSelection(currentEntries.map { it.path }, anchor, end, base, selecting))
                        }
                    } finally { scrolling?.cancel() }
                }
            },
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.path },
                contentType = { _, entry -> if (entry.directory) "directory" else "file" },
            ) { _, entry ->
                FileListRow(entry = entry, onOpen = { if (selectionMode) onToggleEntry(entry) else onOpen(entry) }, selected = entry.path in selection, selectionMode = selectionMode, modifier = Modifier.animateItem())
            }
        }
            if (entries.isEmpty()) Text(emptyNote.ifEmpty { "这个目录是空的" }, modifier = Modifier.align(Alignment.Center), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun FileSelectionToolbar(
    visible: Boolean, entries: List<FileEntry>, selection: Set<String>, glass: Glass,
    onAttach: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onCopy: () -> Unit, onMove: () -> Unit, clipboardCount: Int, clipboardMove: Boolean, onPaste: () -> Unit,
    onSelectAll: () -> Unit, onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf<List<FileEntry>?>(null) }
    val allSelected = entries.isNotEmpty() && entries.all { it.path in selection }
    val more = DropdownEntry(items = listOf(
        DropdownItem(if (allSelected) "取消全选" else "全选", onClick = onSelectAll),
        DropdownItem("复制路径", onClick = { clipboard.setText(AnnotatedString(selection.sorted().joinToString("\n"))) }),
        DropdownItem("详情", onClick = { details = entries.filter { it.path in selection } }),
        DropdownItem("退出选择", onClick = onDismiss),
    ))
    AnimatedVisibility(visible, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .then(glass.blur(Modifier, RoundedCornerShape(28.dp), radius = 20f))
                .border(
                    width = 1.dp,
                    color = if (ZhiColors.isDark()) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        Color.White.copy(alpha = 0.62f)
                    },
                    shape = RoundedCornerShape(28.dp),
                ),
            shape = RoundedCornerShape(28.dp),
            color = glass.surfaceColor(
                if (ZhiColors.isDark()) MiuixTheme.colorScheme.surfaceContainer
                else ZhiColors.panelSurface(),
            ),
            contentColor = MiuixTheme.colorScheme.onSurface,
            shadowElevation = 12.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val attachable = selection.isNotEmpty() && entries.filter { it.path in selection }.all { !it.directory }
                    FileToolbarAction(ZhiMaterialIcons.Add, "附加", onAttach, attachable)
                    FileToolbarAction(ZhiMaterialIcons.ContentCopy, "复制", onCopy, selection.isNotEmpty())
                    FileToolbarAction(ZhiMaterialIcons.DriveFileMove, "移动", onMove, selection.isNotEmpty())
                    FileToolbarAction(ZhiMaterialIcons.ContentPaste, "粘贴", onPaste, clipboardCount > 0)
                    FileToolbarAction(ZhiMaterialIcons.Edit, "重命名", onRename, selection.size == 1)
                    FileToolbarAction(ZhiMaterialIcons.Delete, "删除", onDelete, selection.isNotEmpty())
                }
                OverlayIconDropdownMenu(more, minHeight = 42.dp, minWidth = 42.dp) {
                    Icon(ZhiMaterialIcons.MoreHoriz, "更多", modifier = Modifier.size(21.dp), tint = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
    OverlayDialog(show = details != null && visible, onDismissRequest = { details = null }) {
        val shown = rememberLastNonNull(details)
        DialogShell("详情", actions = { PrimaryButton("确定", { details = null }) }) { Text(shown.orEmpty().joinToString("\n") { it.path }) }
    }
}

@Composable
private fun FileToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean) {
    val scheme = MiuixTheme.colorScheme
    val content = if (enabled) scheme.onSurface else scheme.onSurfaceVariantSummary.copy(alpha = 0.42f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(42.dp).padding(horizontal = 2.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = content)
            Text(label, fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = content)
        }
    }
}
