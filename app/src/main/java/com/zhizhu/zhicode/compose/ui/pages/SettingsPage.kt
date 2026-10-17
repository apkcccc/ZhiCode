@file:OptIn(top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi::class)

package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.COMPACT_PERCENT_MAX
import com.zhizhu.zhicode.compose.model.COMPACT_PERCENT_MIN
import com.zhizhu.zhicode.compose.model.EffortLevel
import com.zhizhu.zhicode.compose.model.ExtensionSummary
import com.zhizhu.zhicode.compose.model.PermissionMode
import com.zhizhu.zhicode.compose.model.SettingsDraft
import com.zhizhu.zhicode.compose.model.ThemeMode
import com.zhizhu.zhicode.compose.model.WEB_RESULTS_MAX
import com.zhizhu.zhicode.compose.model.WEB_RESULTS_MIN
import com.zhizhu.zhicode.compose.model.WEB_TIMEOUT_MAX_SEC
import com.zhizhu.zhicode.compose.model.WEB_TIMEOUT_MIN_SEC
import com.zhizhu.zhicode.compose.model.formatTokenCountShort
import com.zhizhu.zhicode.compose.model.parseTokenCount
import com.zhizhu.zhicode.compose.ui.PageFeedbackSlot
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.settings.SettingsChoice
import com.zhizhu.zhicode.compose.ui.settings.SettingsEntry
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsIconPlate
import com.zhizhu.zhicode.compose.ui.settings.SettingsIntField
import com.zhizhu.zhicode.compose.ui.settings.SettingsPlateColors
import com.zhizhu.zhicode.compose.ui.settings.SettingsTextField
import com.zhizhu.zhicode.compose.ui.settings.SettingsToggle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ZhiCode 设置页：标题 + 返回 / 分组设置行。
 *
 * ## 没有「保存」按钮：改动即时生效
 *
 * 原来这里有一对「取消 / 保存」，draft 只在点保存时才写回 state。实际上「保存」
 * 除了把值搬进内存 state 之外什么都没做（不落盘、不下发引擎），却让每次改一项都要
 * 先把页面拉到底点一下右上角 —— 所以改成**自动保存**：任一改动立刻写回 state，
 * 返回只是关页面。
 *
 * draft 仍然保留，但语义变了：它不再是一份“待提交副本”，而是**这一屏的显示源**
 * （见 `AppScaffold` 的 `settingsUi = state.settingsDraft ?: lastSettingsDraft`），
 * 也承载「项目目录留空 = 不改动」这类字段级语义。
 *
 * ## 设置行不是手写的
 *
 * 页面骨架（弹窗外壳、分组、标题）用 Miuix 组件搭；
 * 每一条设置项走 `ui/settings/SettingsRows.kt`，那里全部转发到 `miuix-preference`
 * 的 `*Preference` 组件（见该文件表格）。本文件只负责**把 SettingsDraft 的字段
 * 接到那些组件上**，不再自己画值框、下拉和滚轮。
 *
 * ## 容器为什么是 `OverlayDialog`
 *
 * `WindowDialog` 创建**独立 Android Window**，`Scaffold` 提供的 `popupHost` 不会被它继承，
 * 于是 `Overlay*` 系列（本页所有选择器）在里面不可靠，而且主窗口的 `LayerBackdrop`
 * 也采不到它的背景。`OverlayDialog` 参数集与它一致，是 drop-in 替换。
 *
 * ## 每一行的行首都是一块彩色图标
 *
 * 用户看过小米「设置」的截图之后明确要求：
 *
 * > 那就借鉴小米自带的设置的图标
 *
 * 参考物里没有一行是裸字形的 —— 行首一律是**彩色圆角方块 + 白色字形**。所以本页每一行
 * 都带一对 `icon` + `plate`（底色与几何见 `SettingsIconPlate.kt`）。
 *
 * 两条容易走偏的地方，`SettingsIconPlateTest` 都逐行数着：
 *
 * - **不能只给一半**。只给 `icon` 就退回改版前的单色裸字形（那就是用户说的"扁扁的"），
 *   只给 `plate` 就是一块空色块；两者都只在调用点成对出现才有意义。
 * - **不能有两行一模一样**。同一页里出现两个相同的（字形, 底色）会让人以为它们共用一条设置，
 *   所以 `推理强度`（滑杆/紫）与 `自动压缩上限`（滑杆/绿）同字形不同底色，
 *   `上下文压缩`（缩放/绿）与 `自动压缩上限`（滑杆/绿）同底色不同字形。
 */
@Composable
fun SettingsPage(
    draft: SettingsDraft?,
    onChange: (SettingsDraft) -> Unit,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    /**
     * 「扩展」组的数量摘要（MCP / 技能 / 角色卡）。
     *
     * 默认空摘要 = 未统计，界面显示"正在统计…"。页面自身不读盘：
     * 三个存储都在磁盘上，在设置页里读会变成"每次重组读一次"。
     */
    extensions: ExtensionSummary = ExtensionSummary(),
) {
    // 整页设置，骨架全部用 Miuix 原生组件，照官方 example 的 SettingsPage 模式：
    // Scaffold + SmallTopAppBar(MiuixScrollBehavior) + LazyColumn(overScroll+nestedScroll)。
    // 返回键 = ZhiIcons.back + 原生 IconButton。顶栏**没有动作按钮**：改动即时生效，
    // 没有需要用户再确认一次的东西（见文件顶部「没有保存按钮」一节）。
    if (draft == null) return

    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            // rikkahub 用 LargeFlexibleTopAppBar（大标题 + 滚动折叠）；
            // Miuix 对应组件 = TopAppBar（自带 largeTitle 折叠行为）。
            TopAppBar(
                title = "设置",
                largeTitle = "设置",
                scrollBehavior = topAppBarScrollBehavior,
                color = scheme.surface,
                navigationIcon = {
                    // 官方 BackNavigationIcon 同款：原生 IconButton + 左箭头。
                    // 字形走 ZhiIcons（Material Symbols），与整页其余图标同源。
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onDismiss) {
                        Icon(
                            painter = ZhiIcons.back,
                            contentDescription = "返回",
                            tint = scheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .overScrollVertical()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                // ---- 每个分组各自一个 item，而不是全部塞进一个 item ----
                //
                // 原来这里是单个 `item(key = "settingsBody") { AllSettingsPages(...) }`。
                // `LazyColumn` 的懒加载单位是 **item**，只有一个 item 就等于没有懒加载：
                // 整页（5 组、几百个设置行）在首次组合时被一次建完。
                // 实测「点设置」首帧 **max=266.6ms**、avg 17.5ms、卡 2 次。
                //
                // 拆开之后只组合可见的那部分，首帧成本随**屏幕高度**增长而不是整页长度。
                // 各分组的状态都是组内局部的（`SettingsGroup` 只画一张卡），
                // 所以拆 item 不会打断任何 remember/动画。
                item(key = "general", contentType = "settings-group") { SettingsGroup("通用") { GeneralPage(draft, onChange, onNavigate) } }
                item(key = "modelService", contentType = "settings-group") {
                    SettingsGroup("模型与服务") { ModelServicePage(draft, onChange, onNavigate) }
                }
                item(key = "context", contentType = "settings-group") { SettingsGroup("上下文与项目") { ContextProjectPage(draft, onChange) } }
                item(key = "agent", contentType = "settings-group") { SettingsGroup("Agent 与安全") { AgentSecurityPage(draft, onChange) } }
                item(key = "extensions", contentType = "settings-group") { SettingsGroup("扩展") { ExtensionsPage(onNavigate, extensions) } }
            }
            // Miuix 原生滚动条
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
            // 设置页是自动保存，但"保存失败""已切换 API 配置"这类反馈仍然要看得见。
            // 原先这些消息只落在对话页的 MessageBar 上，而在设置页里根本看不到。
            PageFeedbackSlot(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ------------------------------------------------------------------ 各分类页

/** 主题模式的显示文案对齐参考图的「夜间模式 / 白天模式」。 */
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "白天模式"
    ThemeMode.DARK -> "夜间模式"
}

/** 通用组：主题模式 + 联网搜索（rikkahub 的 generalSettings / search 合并）。 */
@Composable
private fun GeneralPage(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onNavigate: (String) -> Unit,
) {
    SettingsChoice(
        title = "主题模式",
        options = ThemeMode.entries.map(::themeLabel),
        selectedIndex = ThemeMode.entries.indexOf(draft.themeMode),
        onSelect = { onChange(draft.copy(themeMode = ThemeMode.entries[it])) },
        // 与顶栏那个明暗按钮同一个字形（半明半暗的圆）。
        icon = ZhiIcons.theme,
        plate = SettingsPlateColors.orange,
        // 不写「右上角 ☼/☾ 可快速切换」——顶栏早就没有这个快捷键了，
        // 指向不存在入口的说明比没有说明更糟。
    )
    NetworkPage(draft, onChange, onNavigate)
}

@Composable
private fun ModelServicePage(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onNavigate: (String) -> Unit,
) {
    // rikkahub hub 的「当前值行」：标题 + 当前值当副标题 + 行尾箭头，
    // 细节进二级页。原先这里用 SettingsEntry 且写了一句「密钥不会回填」的长说明，
    // 该说明在二级页里已有，这里按 hub 惯例收成一行当前值。
    //
    // 这一行**曾经特意不带行首图标**（理由："同屏只有它一个有图标会显得像另一种优先级"）。
    // 那条理由现在不成立了：整页每一行都有图标块，缺一个反而成了唯一的例外。
    SettingsEntry(
        title = "API 配置",
        valueText = "${draft.profileName} · ${draft.modelLabel}",
        onClick = { onNavigate("apiProfiles") },
        icon = ZhiIcons.link,
        plate = SettingsPlateColors.blue,
    )

    SettingsChoice(
        title = "视觉图片输入（Vision）",
        options = listOf("开启（发送图片给模型）", "关闭（仅保留本地预览）"),
        selectedIndex = if (draft.visionEnabled) 0 else 1,
        onSelect = { onChange(draft.copy(visionEnabled = it == 0)) },
        summary = "模型不支持 vision 时请关闭",
        // 与输入器里"图片附件"同一个照片字形。
        icon = ZhiIcons.image,
        plate = SettingsPlateColors.purple,
    )

    SettingsChoice(
        title = "推理强度",
        options = EffortLevel.entries.map { it.label },
        selectedIndex = EffortLevel.entries.indexOf(draft.effort),
        onSelect = { onChange(draft.copy(effort = EffortLevel.entries[it])) },
        icon = ZhiIcons.tune,
        plate = SettingsPlateColors.purple,
    )

    SettingsChoice(
        title = "权限模式",
        options = PermissionMode.entries.map { it.label },
        selectedIndex = PermissionMode.entries.indexOf(draft.permissionMode),
        onSelect = { onChange(draft.copy(permissionMode = PermissionMode.entries[it])) },
        summary = draft.permissionMode.detail,
        icon = ZhiIcons.lock,
        plate = SettingsPlateColors.blue,
    )
}

@Composable
private fun AgentSecurityPage(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    SettingsToggle(
        title = "Agent 沙箱全权调试",
        checked = draft.sandboxAgentFullAccess,
        onCheckedChange = { onChange(draft.copy(sandboxAgentFullAccess = it)) },
        summary = "允许容器内安装、UI 操作与 Frida；不含真机 host 与 Root",
        warn = true,
        // 与侧栏「沙箱」同一个箱子字形：同一个东西在两处必须是同一个图标。
        icon = ZhiIcons.sandbox,
        plate = SettingsPlateColors.purple,
    )

    SettingsToggle(
        title = "Agent Root 权限",
        checked = draft.rootExecutionEnabled,
        onCheckedChange = { onChange(draft.copy(rootExecutionEnabled = it)) },
        summary = "高风险：需要 Magisk/KernelSU 授权；普通模式仍逐次确认",
        warn = true,
        // 终端字形：这一项开通的正是"在终端里以 root 身份跑命令"。
        icon = ZhiIcons.terminal,
        plate = SettingsPlateColors.red,
    )

    SettingsToggle(
        title = "强制后台保活",
        checked = draft.forcedKeepAliveEnabled,
        onCheckedChange = { onChange(draft.copy(forcedKeepAliveEnabled = it)) },
        summary = "开启前台服务 + WakeLock；Root 时还会强制系统后台策略。",
        // 循环箭头 = 心跳/保活。不借 `refresh` 作"刷新"，两者本来就是同一个字形，
        // 区别在语义：这里是"一直活着"，不是"重新拉一次"。
        icon = ZhiIcons.refresh,
        plate = SettingsPlateColors.green,
    )

    // 输入法：两种输入类型各有代价，只能让用户按自己机型选（默认关 = 正常输入法）。
    SettingsToggle(
        title = "终端字符模式输入",
        checked = draft.terminalCharMode,
        onCheckedChange = { onChange(draft.copy(terminalCharMode = it)) },
        summary = "若终端里调出的不是你常用的输入法（而是安全/密码键盘），保持关闭；" +
            "若个别机型在终端里切换输入法后状态残留，再开启。",
        icon = ZhiIcons.keyboard,
        plate = SettingsPlateColors.gray,
    )
}

@Composable
private fun NetworkPage(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    SettingsToggle(
        title = "联网搜索",
        checked = draft.webSearchEnabled,
        onCheckedChange = { onChange(draft.copy(webSearchEnabled = it)) },
        icon = ZhiIcons.search,
        plate = SettingsPlateColors.blue,
    )

    // 关闭联网搜索时后续配置全部折叠（官方 SettingsPage 的 AnimatedVisibility 模式）
    //
    // 官方示例那里写的是**裸默认**（`AnimatedVisibility(visible = …)`），视觉上等价：
    // `expandIn` / `shrinkOut` 的默认展开方向就是竖直从下往上揭开，宽度本来就满。
    // 这里显式写出来只是让「展开是竖直长出来」这件事在源码里读得到，
    // 与 `Composer` 里那两处（斜杠面板、附件条）用同一组。
    AnimatedVisibility(
        visible = draft.webSearchEnabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            // 搜索服务已改成**独立整页**（RikkaHub 形态：多服务列表 + 每服务专属选项）。
            // 这里只留入口 —— 原先是一排「类型下拉 + 一个 Key 框 + 实例地址框」，
            // 只能配一个服务，而这一页要能配多个、每个还有自己的 depth/topic/语言。
            // 入口行与 API 配置记录同一个形态（都是"配置对象列表"）。
            //
            // 这一行是裸 `BasicComponent`（不要行尾箭头），所以图标块直接挂在 `startAction`
            // 上，没走 `SettingsEntry`。
            BasicComponent(
                title = "搜索服务",
                titleColor = BasicComponentDefaults.titleColor(color = scheme.onBackground),
                summary = "添加 Tavily / Exa / Brave / Perplexity / SearXNG 等；点一个设为当前使用",
                summaryColor = BasicComponentDefaults.summaryColor(
                    color = scheme.onSurfaceVariantSummary,
                ),
                startAction = {
                    SettingsIconPlate(icon = ZhiIcons.cloud, color = SettingsPlateColors.blue)
                },
                onClick = { onNavigate("searchServices") },
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            )

            SliderPreference(
                title = "默认搜索结果数（1-50）",
                value = draft.webSearchMaxResults.toFloat(),
                onValueChange = { onChange(draft.copy(webSearchMaxResults = it.toInt())) },
                valueRange = WEB_RESULTS_MIN.toFloat()..WEB_RESULTS_MAX.toFloat(),
                steps = WEB_RESULTS_MAX - WEB_RESULTS_MIN - 1,
                valueText = "${draft.webSearchMaxResults} 条",
                summary = "服务未单独指定条数时用它",
                startAction = { SettingsIconPlate(icon = ZhiIcons.listCount, color = SettingsPlateColors.blue) },
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            )

            SliderPreference(
                title = "联网超时（秒）",
                value = draft.webSearchTimeoutSec.toFloat(),
                onValueChange = { onChange(draft.copy(webSearchTimeoutSec = it.toInt())) },
                valueRange = WEB_TIMEOUT_MIN_SEC.toFloat()..WEB_TIMEOUT_MAX_SEC.toFloat(),
                steps = WEB_TIMEOUT_MAX_SEC - WEB_TIMEOUT_MIN_SEC - 1,
                valueText = "${draft.webSearchTimeoutSec} 秒",
                summary = "支持 ${WEB_TIMEOUT_MIN_SEC}-${WEB_TIMEOUT_MAX_SEC} 秒",
                startAction = { SettingsIconPlate(icon = ZhiIcons.timeout, color = SettingsPlateColors.blue) },
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ContextProjectPage(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    SettingsIntField(
        title = "上下文窗口",
        value = draft.contextWindow,
        // 上下文窗口不设人为上限：给多少就是多少，超出实际支持量由引擎侧处理。
        // `parseTokenCount` 已经挡掉了非正数与解析失败，这里只兜住上下界。
        min = 1,
        max = Int.MAX_VALUE,
        parse = ::parseTokenCount,
        // 显示成 `200k` 而不是 `200000`：写作写法与标题里提示的 `128k / 1.5m` 一致。
        format = ::formatTokenCountShort,
        onValueChange = { onChange(draft.copy(contextWindow = it)) },
        summary = "支持 128k / 1.5m 写法",
        // 上下文 = 对话历史，"气泡"是它最直接的样子。
        icon = ZhiIcons.chat,
        plate = SettingsPlateColors.green,
    )

    SettingsToggle(
        title = "上下文压缩",
        checked = draft.autoCompact,
        onCheckedChange = { onChange(draft.copy(autoCompact = it)) },
        icon = ZhiIcons.compress,
        plate = SettingsPlateColors.green,
    )

    AnimatedVisibility(
        visible = draft.autoCompact,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        SliderPreference(
            title = "自动压缩上限（50-100%，安全缓冲优先）",
            value = draft.autoCompactPercent.toFloat(),
            onValueChange = { onChange(draft.copy(autoCompactPercent = it.toInt())) },
            valueRange = COMPACT_PERCENT_MIN.toFloat()..COMPACT_PERCENT_MAX.toFloat(),
            steps = COMPACT_PERCENT_MAX - COMPACT_PERCENT_MIN - 1,
            valueText = "${draft.autoCompactPercent}%",
            summary = "达到上下文窗口的 ${draft.autoCompactPercent}% 时触发压缩",
            startAction = { SettingsIconPlate(icon = ZhiIcons.tune, color = SettingsPlateColors.green) },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
    }

    ProjectPathField(draft, onChange)
    CustomSystemPromptField(draft, onChange)
}

/**
 * 自定义头部提示词。
 *
 * 这一项**已经真的生效**：`WorkspaceViewModel.engineOverrides()` 会把它塞进
 * `SessionConfig.customSystemPrompt`，每次 `startTurn` 前重新 `configure`，
 * 所以它随下一次完整任务一起发给模型。
 *
 * 因此提示语必须如实说明两件事，否则用户会误判它的权限：
 * 一是它**不能**覆盖安全规则（权限模式、工具白名单、Root 限制由引擎侧强制）；
 * 二是它**只从下一轮生效**（当前这轮已经在跑，改了对它没用）。
 * 另外要提醒不要在这里写密钥——这段文本会原样进入请求。
 */
@Composable
private fun CustomSystemPromptField(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    SettingsTextField(
        title = "自定义头部提示词",
        value = draft.customSystemPrompt,
        onValueChange = { onChange(draft.copy(customSystemPrompt = it)) },
        summary = "随请求发送，下一任务生效；不能覆盖安全规则，勿填密钥。" +
            if (draft.customSystemPrompt.isBlank()) "" else "当前 ${draft.customSystemPrompt.length} 字。",
        singleLine = false,
        minLines = 5,
        icon = ZhiIcons.edit,
        plate = SettingsPlateColors.orange,
    )
}

/** 项目目录是自由文本，走 `BasicComponent` 的 `bottomAction` 槽位放输入框。 */
@Composable
private fun ProjectPathField(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    var text by remember(draft.projectPath) { mutableStateOf(draft.projectPath) }

    SettingsTextField(
        title = "项目目录",
        value = text,
        onValueChange = {
            text = it
            // 失焦前就同步回 draft，避免用户点「保存」时漏掉最后一次输入
            onChange(draft.copy(projectPath = it.trim()))
        },
        summary = "与上下文历史一一绑定，保存后可恢复该项目记录",
        icon = ZhiIcons.directory,
        plate = SettingsPlateColors.blue,
    )
}

/**
 * 扩展功能。
 *
 * 这里只列**已经能用**的入口。原版还有两项（运行时 UI 画布、其他设置），当前处理如下：
 *
 * - 「运行时 UI 画布」：**画布的存储与工具是齐的**——`com.zhizhu.zhicode.UiCanvasStore`、
 *   `UiCanvasController`、以及已注册进 `ToolRegistry` 的 `ui_canvas` 工具都在，
 *   Agent 调用它也会正常落盘一份有界操作文档。缺的是**消费端**：
 *   `UiCanvasController.apply(View root, JSONObject document)` 是按稳定节点 id
 *   在 **Android View 树**上找 View 再改属性；Compose 这边没有这套 id，
 *   也没有任何代码读那份文档。所以现在点这个入口只会打开一个空壳面板，
 *   不如先不放入口。要接的话工作量在"给 Compose 界面定义稳定槽位 id
 *   并把文档作用上去"，不是重写存储。
 * - 「其他设置」：那一项原本只是「自定义头部提示词」的另一个入口。现在提示词输入框
 *   已经并入「上下文与项目」分类，同一个字段不需要两条路径，入口撤销。
 */
@Composable
private fun ExtensionsPage(onNavigate: (String) -> Unit, extensions: ExtensionSummary) {
    // rikkahub 式入口行：图标 + 标题 + 副标题；Miuix 用 ArrowPreference 的 startAction。
    // 注意：这里不放「API 配置」——那条带当前生效值的入口留在上面的
    // 「模型与对话」组里，同一目标只保留一条路径（hub 的规矩）。
    //
    // 每一行的副标题带**当前数量**（rikkahub 的扩展入口也是"标题 + 当前值"）：
    // 之前这里是四句固定说明，想确认"那个 MCP 服务器还在不在"得点进去再退回来。
    // 数量由 ViewModel 在打开设置时统计一次（三个存储都在磁盘上，界面里不读盘）。
    SettingsIconEntry(
        title = "Model Context Protocol（MCP）",
        summary = if (!extensions.loaded) {
            "正在统计…"
        } else {
            buildString {
                append("${extensions.mcpTotal} 台服务器")
                if (extensions.mcpTotal > 0) {
                    append(" · ${extensions.mcpEnabled} 台启用")
                    if (extensions.mcpConnected > 0) append(" · ${extensions.mcpConnected} 台已连接")
                } else {
                    append(" · 还没有配置")
                }
            }
        },
        icon = ZhiIcons.mindMap,
        plate = SettingsPlateColors.purple,
        onClick = { onNavigate("mcp") },
    )
    SettingsIconEntry(
        title = "Skill 管理器",
        summary = if (!extensions.loaded) {
            "正在统计…"
        } else if (extensions.skillTotal == 0) {
            "还没有技能 · 可按需加载的资料包"
        } else {
            "${extensions.skillTotal} 个技能 · 项目级 ${extensions.projectSkills} / 用户级 ${extensions.userSkills}"
        },
        icon = ZhiIcons.skill,
        plate = SettingsPlateColors.purple,
        onClick = { onNavigate("skills") },
    )
    SettingsIconEntry(
        title = "自定义角色卡",
        summary = when {
            !extensions.loaded -> "正在统计…"
            extensions.roleCardTotal == 0 -> "还没有角色卡 · 可切换的多份人设指令"
            else -> "${extensions.roleCardTotal} 张角色卡 · 同一时刻只有一张生效"
        },
        icon = ZhiIcons.roleCard,
        plate = SettingsPlateColors.green,
        onClick = { onNavigate("roleCards") },
    )
    // 「关于」行已删：onClick 是空的，点了没反应——rikkahub 的 About 页有
    // 版本/开源许可/检查更新等真实内容，我们暂时没有可放的，空壳不如没有。
    SettingsIconEntry(
        title = "记忆文件 · ZhiCode.md",
        summary = "项目级与用户级说明文件",
        icon = ZhiIcons.file,
        plate = SettingsPlateColors.orange,
        onClick = { onNavigate("memory") },
    )

    // 仅 debug 构建：把工程真正在用的组件按类别铺开（含对话流/任务卡/输入器），
    // 用来复现"看起来不对"这类问题 —— 详见 ui/debug/UiDebugPage.kt。
    if (com.zhizhu.zhicode.compose.BuildConfig.DEBUG) {
        SettingsIconEntry(
            title = "UI 调试",
            summary = "铺开全部组件与状态（仅 debug 构建）",
            icon = ZhiIcons.layers,
            plate = SettingsPlateColors.gray,
            onClick = { onNavigate("uiDebug") },
        )
    }
}

/**
 * 设置页的入口行：图标块 + 标题 + 副标题 + 箭头（Miuix [ArrowPreference]）。
 *
 * `plate` **不能省**：这一行原先是一个裸的单色字形（`tint = scheme.onBackground`），
 * 在一屏彩色图标块中间会显得像"少了底色"。两者必须在调用点成对出现，
 * 由 `SettingsIconPlateTest` 逐行数。
 */
@Composable
private fun SettingsIconEntry(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.painter.Painter,
    plate: Color,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = { SettingsIconPlate(icon = icon, color = plate) },
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ 工具

