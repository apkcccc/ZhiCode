package com.zhizhu.zhicode.compose.ui.pages.overlays

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.ModelOption
import com.zhizhu.zhicode.compose.model.ModelPickerState
import com.zhizhu.zhicode.compose.model.QuotaRow
import com.zhizhu.zhicode.compose.ui.ZhiUsageBar
import com.zhizhu.zhicode.compose.ui.ZhiLoadingIndicator
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiSectionLabel
import com.zhizhu.zhicode.compose.ui.ZhiSegmentedTabs
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.dialogs.DialogShell
import com.zhizhu.zhicode.compose.ui.dialogs.PrimaryButton
import com.zhizhu.zhicode.compose.ui.dialogs.SecondaryButton
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import kotlinx.coroutines.launch

/**
 * 模型选择面板。
 *
 * 对应原版 `showModelPanel()`：API 切换 tab 栏、状态行、异步填充的模型列表、
 * 手动输入框、底部「添加 API / 完成」。
 *
 * ## 为什么是底部 Sheet 而不是居中弹窗
 *
 * 这是**唯一**一个"内容是可变长列表 + 用户要边看边挑"的面板。居中弹窗在这个场景
 * 上有两个具体问题：
 *
 * 1. 它悬在屏幕中间，上方那条正在流式输出的回复被压掉一半 —— 而挑模型往往正是
 *    因为**看了正在生成的回复**才想换一个，这时最不该挡住的就是它；
 * 2. 手机竖屏下弹窗的可用高度比弹窗小（上下都要留边距），长列表更早触发滚动。
 *
 * 底部 sheet 贴底、上不封顶（按内容涨到窗口高为止），正好把"被挡住的对话"留给
 * 用户看，这也是 rikkahub 的做法。
 *
 * ## 标题与按钮的分工
 *
 * 标题由 [OverlayBottomSheet] 自己渲染（它的 `title` 参数），所以这里**不再**套
 * `DialogShell` —— 那个外壳自带标题行与 `weight(1f)`，套进来会出现两行标题，
 * 而且它的 weight 依赖一个有界高度，sheet 的高度由内容决定，语义也对不上。
 * 底部两个按钮仍留在内容里：sheet 的 `endAction` 是画在**标题行**上的
 * （见 Miuix 的 `TitleAndActionsRow`），与用户习惯的"按钮在底部"不是一回事。
 *
 * ## 结构全部照 Miuix 自己的示例，不自己拼
 *
 * 这个面板前后改过五版，踩的坑有个共同点：**都是在"自己拼结构 + 自己挑颜色"**。
 * 每一处都有 Miuix 官方示例或组件可以直接照：
 *
 * | 问题 | 之前的错法 | 官方做法 |
 * | --- | --- | --- |
 * | 卡片与背板同色、卡片看不见 | 什么都不传，信"默认值总是搭好的" | 官方**显式**传 `secondaryContainer` |
 * | 选中行文字反而更暗 | 把 `onSecondaryContainer`(`#7C7C7C` 中灰) 当前景色 | 前景色用 `onSurfaceContainer`(近白) |
 * | 搜索框填充看不见 | 覆写成 `surfaceContainerHigh` | 用默认的 `secondaryContainer` |
 * | 高亮"选中项" | 自己拼卡片 + 手绘色块 | 官方 Card 示例的蓝卡：`primaryVariant` |
 *
 * 原因是 Miuix 的**默认值分属两套、并不保证互相搭**：`OverlayBottomSheet` 的背板取
 * `background`，而 `CardDefaults` 的卡片色取 `surfaceContainer` —— **深色下两者都是
 * `#242424`**。所以"全用默认值"在 sheet 里恰好会让卡片消失。这不是它设计得差，
 * 而是它的默认值面向"页面里直接放卡片"，不是"卡片放在 sheet 里"。
 *
 * ## 选中态为什么是蓝色卡片
 *
 * 直接照 Miuix 自己的 Card 示例（那个"ShowIndication: true"的蓝卡）：
 * `CardDefaults.defaultColors(color = primaryVariant)` + 文字 `onPrimaryVariant`。
 * 深色下分别是 `#0073DD` 和 `#99C7F1` —— 蓝底浅蓝字。
 *
 * ## 为什么不用 `RadioButtonPreference`
 *
 * 它是 Miuix 里"从列表单选一个"的原生组件，一度用过。换掉的原因很具体：
 * 它的选中表达是**单选圈 + 主色文字**，而这里要的是**整块蓝卡**；两者叠在一起时，
 * 单选圈用的是 `primary`（`#277AF7`），画在 `primaryVariant`（`#0073DD`）的蓝底上
 * **几乎看不见**，得把 `radioButtonColors` 和四组 `titleColor`/`summaryColor` 全部
 * 重写一遍 —— 那就又回到"自己拼"的老路上了。
 *
 * 无障碍语义没有丢：[BasicComponent] 本身有 `role` 参数，传 `Role.RadioButton`
 * 就还是"单选组里的一个选项"，读屏软件读得出来；只是不画那个圈。
 */
@Composable
fun ModelPickerOverlay(
    picker: ModelPickerState?,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUse: (String) -> Unit,
    onAddApi: () -> Unit,
    onRefreshQuota: () -> Unit,
) {
    // 退场时 `picker` 已经是 null，内容不能跟着空掉（见 rememberLastNonNull）。
    val shownPicker = rememberLastNonNull(picker)
    OverlayBottomSheet(
        show = picker != null,
        onDismissRequest = onDismiss,
        title = "选择模型 · ${picker?.profileName.orEmpty()}",
        // 其余参数（圆角、底色、拖拽把手颜色、内外边距、最大宽度）一律用 Miuix 默认值。
        // ⚠️ `backgroundColor` 尤其不要动：默认的 `background` 就是官方示例里的背板色，
        // 卡片之所以要显式传 `secondaryContainer`，正是为了跟它**拉开**一档。
    ) {
        val current = shownPicker ?: return@OverlayBottomSheet
        ModelPickerBody(
            picker = current,
            onQueryChange = onQueryChange,
            onPickModel = onPickModel,
            onSelectProfile = onSelectProfile,
            onUse = onUse,
            onAddApi = onAddApi,
            onRefreshQuota = onRefreshQuota,
        )
    }
}

/**
 * Sheet 里小标题的内边距。
 *
 * Miuix `SmallTitleDefaults.InsideMargin` 是 `PaddingValues(28.dp, 8.dp)`，而 sheet 自身
 * 已经有 24dp 横向内边距 —— 两者相加 52dp，标题会缩进得比卡片深一大截。官方
 * `BottomSheetSection` 示例在 sheet 里覆写成 16dp，这里跟随它。
 */
private val PickerSectionInsideMargin = PaddingValues(16.dp, 8.dp)

/** 组与组之间的间距，与官方示例一致（12dp）。 */
private val PickerGroupSpacing = 12.dp

/**
 * 列表里相邻两张卡片之间的间距。
 *
 * 官方示例里**互不相关**的卡片之间是 12dp；这里是同一份列表内的相邻项，取 8dp ——
 * 比 12dp 紧凑、又不至于像更小那样让卡片边界糊在一起。
 */
private val ModelRowSpacing = 8.dp

/**
 * 面板高度：**固定的**，不随模型条数自适应。
 *
 * ## 为什么改成固定
 *
 * 原来是"内容多高它就多高"（sheet 没有高度参数，高度只能由内容决定）。结果是
 * 只有 1~2 个模型时面板缩成薄薄一条：加载圈挤在一条缝里、列表一闪就到底、
 * 底部的按钮贴着屏幕最下沿，而且**切换 API 时高度会跳**（1 个模型 → 5 个模型）。
 * 固定高度之后这几种情况都不再有。
 *
 * 高度取窗口高度的 [SheetHeightFraction]，并用 [SheetHeightCap] / [SheetHeightFloor]
 * 兜住极高与极矮的屏。
 *
 * ## 空间怎么分配（这一条和"定高"配套，不能只做一半）
 *
 * 内容列**定高**之后，中间那块（加载态 / 列表）用 `weight(1f)` 把余量吃掉 ——
 * 这样按钮稳定落在面板底部，而不是"内容少时在按钮下面留一条空白"。
 * 列表也因此有了**有界**高度（`weight` 给的是确定约束），
 * 这仍然满足"无界高度会让 LazyColumn 崩"那条硬性要求。
 */
private const val SheetHeightFraction = 0.6f
private val SheetHeightCap = 560.dp
private val SheetHeightFloor = 320.dp

/**
 * API 切换 tab 栏（在模型名输入框的正上方）。
 *
 * 用既有的 [ZhiSegmentedTabs]（Miuix `TabRowWithContour` 的转发）：`matchWidth = false`
 * 时项数超出一屏会自动横向滚动 —— API 记录多了也放得下。选择态由 Pager.currentPage
 * 驱动，页面内容由同一个 Pager 负责跟手。
 *
 * ## 为什么只有多于一条时才画
 *
 * 只有一条时它是**死控件**：点了不会切到任何别的地方。这与下面"没有折叠箭头"是同一条
 * 判断标准（画一个点了没用的控件比不画更糟）。判断放在 `ModelPickerState.showProfileTabs`
 * 里，那样它可脱离 Compose 单测。
 */
@Composable
private fun ProfileTabs(
    picker: ModelPickerState,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (!picker.showProfileTabs) return
    val profileNames = remember(picker.profiles) { picker.profiles.map { it.name } }
    ZhiSegmentedTabs(
        tabs = profileNames,
        selectedIndex = selectedIndex.coerceIn(0, picker.profiles.lastIndex),
        onSelect = { index ->
            // 点击只启动 Pager 弹簧；配置切换在 Pager settle 后发起。
            if (index in picker.profiles.indices) onSelect(index)
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = PickerGroupSpacing),
    )
}

/**
 * 加载态：居中的加载指示器 + 状态文字。
 *
 * 之前加载时只有顶部一行小字，列表位置是空的 —— 看起来像"面板坏了"。
 * 现在把指示器与文字放在列表本来的位置居中，一眼就知道是在等东西。
 *
 * 高度**填满**调用方给的那块区域（定高列里的 weight 区）：面板高度已经是固定的，
 * 所以这里不需要自己算高度，之前那套按窗口比例算 minHeight 的代码随之删掉。
 */
@Composable
private fun PickerLoading(status: String) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZhiLoadingIndicator()
            Text(
                text = status,
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/**
 * 目录里的一行：**每个模型一张 Miuix Card**，选中那张是蓝卡。
 *
 * 卡片颜色对照（深色方案的实际值），这也是整块面板的层级关系：
 *
 * | | 卡片底 | 标题 | 副标题 |
 * | --- | --- | --- | --- |
 * | sheet 背板 | `#242424` | — | — |
 * | 未选中行 | `secondaryContainer` `#434343` | `onBackground` | `onSurfaceVariantSummary` |
 * | **选中行** | `primaryVariant` `#0073DD` | `onPrimaryVariant` `#99C7F1` | 同左 |
 *
 * 三行文字色都必须**显式**传：`BasicComponentDefaults.titleColor()` 的默认值是
 * `onBackground`、`summaryColor()` 的默认值是 `onSurfaceVariantSummary` ——
 * 都不跟随卡片的 `contentColor`（这条和 Material3 的直觉相反），所以不传的话
 * 蓝卡上会出现深色字，几乎读不出来。官方 Card 示例同样两个文字色都显式写了
 * `onPrimaryVariant`。
 *
 * `role = Role.RadioButton`：这是同一个单选组里的一个选项，语义上要报给读屏软件。
 * 只是不画单选圈 —— 整张蓝卡就是选中标记（理由见文件顶部）。
 */
@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // `displayName` 与 `id` 相同时只显示一次，否则每行会重复两遍同一个名字
    // （服务端常常不给 display_name，那种情况下它会被回落成 id）。
    val duplicated = option.displayName == option.id

    // 三个颜色都走淡变，而不是硬切。
    //
    // 为什么三个都要：选中时**底色与文字是同时变**的，只淡化其中一个会出现
    // "字已经变浅蓝、底还是灰的"这种中间态，比不做动画更难看。
    // 用的是 [ZhiMotion.colorSpec]（150ms + SinOut），与 Miuix 弹窗的淡出同一条曲线 ——
    // 不是为了好看，而是为了**手感与 Miuix 组件一致**（这一点在 `Animations.kt` 里有完整说明）。
    val cardColor by animateColorAsState(
        targetValue = if (selected) scheme.primaryVariant else scheme.secondaryContainer,
        animationSpec = ZhiMotion.colorSpec,
        label = "modelRowCard",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryVariant else scheme.onBackground,
        animationSpec = ZhiMotion.colorSpec,
        label = "modelRowTitle",
    )
    val subColor by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryVariant else scheme.onSurfaceVariantSummary,
        animationSpec = ZhiMotion.colorSpec,
        label = "modelRowSummary",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        // 官方 Card 示例用的就是这两个令牌；`color` 必须显式传而不是靠默认值 ——
        // `CardDefaults` 默认的 `surfaceContainer` 与 sheet 背板 `background` 同值
        // （都是 `#242424`），不传的话未选中的卡片在背板上完全看不出来。
        colors = CardDefaults.defaultColors(color = cardColor),
    ) {
        BasicComponent(
            title = option.displayName,
            titleColor = BasicComponentDefaults.titleColor(color = textColor),
            summary = if (duplicated) null else option.id,
            summaryColor = BasicComponentDefaults.summaryColor(color = subColor),
            role = Role.RadioButton,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
            // insideMargin 不传：走 `BasicComponentDefaults.InsideMargin`（16dp），
            // 与官方示例给卡片写的 `PaddingValues(16.dp)` 是同一个值。
        )
    }
}

/**
 * 模型列表：每个模型一张独立卡片（不是一张大卡装多行）。
 *
 * 这里刻意**不用**"一张 Card 装多行"的官方分组写法：那种写法是给**设置项分组**用的
 * （组内各行只是并列，没有"哪一行被选中"的概念）。而这里每行都有选中态，选中态要
 * 表达成一整张变色卡片 —— 装在同一张卡里的话，蓝底只能在大卡内部画一块，四角与卡片
 * 圆角对不上（这正是更早一版用裸色块时的毛病）。
 *
 * ## 高亮跟随**点击**而不是跟随已保存的值
 *
 * 判据是 `model.id == picker.query`，而 `query` 就是"要用的模型名"。点一行会立刻
 * 把 `query` 改过去（并同时存盘），所以点哪行哪行马上变蓝。
 *
 * 早先这里判的是 `picker.currentModel`（**已保存**的那个），于是必须点底部按钮
 * 之后高亮才动 —— 用户点了一行却看不到任何反馈，会以为没点上。
 */
@Composable
private fun ModelList(
    models: List<ModelOption>,
    picked: String,
    listMaxHeight: Dp,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = PickerGroupSpacing)
            // ⚠️ 这个 heightIn 不能删：弹窗里的竖直 LazyColumn 若没有高度上限，
            // 会拿到 Infinity 约束直接崩（DialogScrollNestingTest 守着这条）。
            // 实际高度由调用方的 weight 决定，这里再给一个明确上限是第二道保险。
            .heightIn(max = listMaxHeight),
        verticalArrangement = Arrangement.spacedBy(ModelRowSpacing),
    ) {
        items(
            items = models,
            key = { it.id },
            contentType = { "model-row" },
        ) { model ->            ModelRow(
                option = model,
                selected = model.id == picked,
                onPick = { onPick(model.id) },
                // 目录重排（切了 API、或服务端顺序变了）时让行**滑过去**而不是瞬移。
                // 用 key 才有意义（上面 items 已给 key = id），否则 Compose 认不出
                // "还是那一行、只是位置变了"，会当成整批新建。
                modifier = Modifier.animateItem(),
            )
        }
        // 这里原本还有一个"没有匹配「…」的模型"的空态：它是给**搜索**用的，
        // 而调用方保证了 `models` 非空，所以搜索一去掉它就是一段死分支。
        // "目录是空的"那种情况由上面的状态行负责说明（见 PickerLoading / status）。
    }
}

@Composable
private fun ModelPickerLoaded(
    picker: ModelPickerState,
    listMaxHeight: Dp,
    onPickModel: (String) -> Unit,
    onRefreshQuota: () -> Unit,
) {
    // 列表直接铺 `models`。这里曾经有一层搜索过滤（`visibleModels`），已按用户要求
    // 去掉；去掉之后 `search` / `visibleModels` / `setModelSearch` 就是死代码，
    // 一并删除，不留"没人用但还留着"的字段。
    if (picker.models.isEmpty()) return

    // 铺满调用方给的那块区域（它是定高列里的 weight 区，所以高度有界）。
    Column(modifier = Modifier.fillMaxSize()) {
        // 额度卡片在列表**上面**：它是"我现在还剩多少"，比"我能选哪些"更该先看到。
        //
        // 读不到额度时卡片**照样要出现**（只是内容换成原因）：额度是个网络请求，
        // 失败很正常，而"卡片不见了"和"额度是 0"在界面上分不出来 ——
        // 用户会以为自己没套餐。留着卡片才能看见原因，也才有那个「刷新」可点。
        if (picker.quota.isNotEmpty() || picker.quotaError.isNotEmpty()) {
            QuotaCard(
                quota = picker.quota,
                error = picker.quotaError,
                onRefresh = onRefreshQuota,
                modifier = Modifier.padding(bottom = PickerGroupSpacing),
            )
        }
        // 分组标题在卡片**外面**（官方示例就是这样）。
        //
        // ## 为什么这里没有折叠箭头、没有吸顶分组头、没有底部提供方跳转条
        //
        // rikkahub 的面板这三样都有。它们成立的前提是它支持**多个提供方**：
        // 分组头要能折叠是为了收起不看的那些提供方，跳转条是为了快速跳到某一个，
        // 吸顶是为了在长列表里始终知道自己在哪个提供方下面。
        //
        // 而这个面板的每一份 API 记录**各自**就是一组（上面的 tab 栏负责在
        // 它们之间切换），切进来的这一组永远只有一组：折叠 = 把列表收起来，
        // 跳转条没有任何目标可跳，吸顶也没有第二种分组要区分。
        // **画一个点了没用的控件比不画更糟** —— 用户会去点它，然后以为应用坏了。
        ZhiSectionLabel(
            text = "${picker.profileName} · ${picker.models.size} 个模型${picker.modelsNote}",
            insideMargin = PickerSectionInsideMargin,
        )
        ModelList(
            models = picker.models,
            picked = picker.query,
            listMaxHeight = listMaxHeight,
            onPick = onPickModel,
            // 列表吃掉标题之外的余量。定高面板下这一步是必需的：
            // 不 weight 的话列表按内容高度算，余量会掉到**按钮下面**变成一条空白。
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 套餐额度卡片（目前只有 ZCode 用得上）。
 *
 * ## 为什么数字与倒计时都由状态里带过来
 *
 * 这一行显示的是 `剩余 / 总量`、进度条比例、以及「剩 3:12 重置」这样的文案。
 * 单位换算（万/亿）与倒计时的分档规则属于**协议知识**，只有一处实现
 * （`ZcodeWire.formatUnits` / `formatCountdown`）。界面若自己再算一遍，
 * 两处迟早会不一致 —— 而那种不一致表现为"两个地方数字不一样"，最难判哪个对。
 *
 * ## 为什么卡片里必须有「刷新」
 *
 * 额度是会被消耗的（也可能是别人在别处用掉的）。面板打开时拉一次之后，
 * 用户看着一个不再变化的数字会以为它坏了。给一个明确的刷新入口，
 * 比"每次展开都偷偷重拉"更好：后者会让用户在读数字时它突然跳一下。
 *
 * 顺带：额度读不到时卡片**不消失**，只是把 [error] 那句原因显示出来 ——
 * 有那张卡片在，「刷新」才有个可点的地方。
 */
@Composable
private fun QuotaCard(
    quota: List<QuotaRow>,
    error: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        // 与模型行同一套令牌入口（`defaultColors(color=…)`）：卡片的底色必须显式传，
        // 否则默认值与 sheet 背板同值，整张卡在背板上看不出来。
        colors = CardDefaults.defaultColors(color = scheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "套餐额度",
                    color = scheme.onBackground,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    modifier = Modifier.weight(1f),
                )
                // 文案是动作而不是状态，所以用 SecondaryButton 而不是可点的文字：
                // 可点文字在这套面板里没有可辨识的按下反馈。
                SecondaryButton(text = "刷新", onClick = onRefresh)
            }
            if (quota.isEmpty() && error.isNotEmpty()) {
                // 一行说明而不是一个空卡片：用户要的是"为什么没有数字"。
                // 原文由协议层/数据层截断过（见 ModelCatalogStore.quotaFailureText）。
                Text(
                    text = error,
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            quota.forEachIndexed { index, row ->
                // 第一行与标题之间留一点空隙，行与行之间留得少一些。
                val top = if (index == 0) 12.dp else 12.dp
                Column(modifier = Modifier.fillMaxWidth().padding(top = top)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.name,
                            color = scheme.onBackground,
                            fontSize = MiuixTheme.textStyles.main.fontSize,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${row.remaining} / ${row.total}",
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        )
                    }
                    ZhiUsageBar(
                        fraction = row.fraction,
                        height = 4.dp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = row.resetLabel,
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerBody(
    picker: ModelPickerState,
    onQueryChange: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUse: (String) -> Unit,
    onAddApi: () -> Unit,
    onRefreshQuota: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme

    // ⚠️ 窗口高度取 LocalWindowInfo，不能取 BoxWithConstraints 的 maxHeight。
    // 这一条是**实测**出来的：最初用后者算比例，面板反而变矮、heightIn(min=…) 完全不生效 ——
    // sheet 要先量一次内容才能决定自己多高，量的时候给的约束不是屏幕高度。
    // Miuix 自己在 BottomSheetContentLayout 里用的就是这个来源。
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val sheetHeight = (windowHeight * SheetHeightFraction)
        .coerceAtMost(SheetHeightCap)
        .coerceAtLeast(SheetHeightFloor)
    // 列表上限：定高面板里 weight 已经把它夹住了，这里再给一个明确上限是为了满足
    // "弹窗里的 LazyColumn 必须自带 heightIn"那条防崩守卫（DialogScrollNestingTest）。
    val listMaxHeight = sheetHeight * 0.8f

    // 定高：面板高度不再随模型条数变化（只有 1~2 个模型时也保持这个高度）。
    Column(modifier = Modifier.fillMaxWidth().height(sheetHeight)) {
        // 状态行走 Miuix 的分组小标题（`SmallTitle`）。它本来就是干这个的，
        // 之前用裸 `Text` + 自己挑颜色，正是不必要的自绘。
        ZhiSectionLabel(
            text = picker.status,
            textColor = if (picker.models.isEmpty() && !picker.loading) {
                scheme.onSurfaceVariantSummary
            } else {
                scheme.primary
            },
            insideMargin = PickerSectionInsideMargin,
        )

        val profileCount = picker.profiles.size
        val pagerState = rememberPagerState(pageCount = { maxOf(profileCount, 1) })
        val pagerScope = rememberCoroutineScope()
        val currentActiveIndex by rememberUpdatedState(picker.activeProfileIndex)
        LaunchedEffect(pagerState, picker.activeProfileIndex, profileCount) {
            val target = picker.activeProfileIndex.coerceIn(0, maxOf(profileCount - 1, 0))
            if (pagerState.currentPage != target) pagerState.springAnimateToPage(target)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .collect { (page, scrolling) ->
                    if (!scrolling && page != currentActiveIndex) {
                        picker.profiles.getOrNull(page)?.let { onSelectProfile(it.id) }
                    }
                }
        }
        val flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = PagerNavigationSpringSpec,
        )

        // Pager 占中间剩余空间；搜索框和 API Tab 保持在内容底部，顺序与原布局一致。
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = false,
            flingBehavior = flingBehavior,
            key = { page -> picker.profiles.getOrNull(page)?.id ?: "model-picker-empty" },
            pageContent = { page ->
                val pageProfile = picker.profiles.getOrNull(page)
                if (pageProfile == null || page == picker.activeProfileIndex) {
                    if (picker.loading) {
                        PickerLoading(status = picker.status)
                    } else {
                        ModelPickerLoaded(
                            picker = picker,
                            listMaxHeight = listMaxHeight,
                            onPickModel = onPickModel,
                            onRefreshQuota = onRefreshQuota,
                        )
                    }
                } else {
                    PickerLoading(status = "正在切换到 ${pageProfile.name}…")
                }
            },
        )

        ZhiTextField(
            value = picker.query,
            onValueChange = onQueryChange,
            label = "模型名",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = PickerGroupSpacing),
        )

        ProfileTabs(
            picker = picker,
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> pagerScope.launch { pagerState.springAnimateToPage(index) } },
        )

        // 这里原本有一行说明文字（"点列表里的模型会立刻选中并保存…"），已按用户要求去掉。
        // 交互本身已经是自解释的：点一行立刻变蓝并落盘，按钮只剩「完成」。

        // 按钮照旧靠右，与居中弹窗时的位置一致：换容器不该顺手改按钮的排布，
        // 否则用户得重新找一遍主按钮在哪。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(text = "添加 API", onClick = onAddApi)
            PrimaryButton(
                // 选择已经在点击那一刻存好了，这个按钮只是"收起面板"。
                // 文案因此是「完成」而不是「使用模型」—— 后者会让人以为不点它就不生效。
                // 它同时也是**手动输入**那条路的重试点：在框里改了名字后点它就写回配置。
                text = "完成",
                enabled = picker.query.isNotBlank(),
                onClick = { onUse(picker.query) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
