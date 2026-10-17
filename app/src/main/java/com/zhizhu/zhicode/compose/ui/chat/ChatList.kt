package com.zhizhu.zhicode.compose.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.AgentTask
import com.zhizhu.zhicode.compose.model.ChatItem
import com.zhizhu.zhicode.compose.model.ChatKind
import com.zhizhu.zhicode.compose.model.TaskState
import com.zhizhu.zhicode.compose.model.ToolGrouping
import com.zhizhu.zhicode.compose.model.TurnLayout
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.ui.ZhiHorizontalDivider
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMarkdown
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiSmallPill
import com.zhizhu.zhicode.compose.ui.debug.ZhiFrameTrace
import com.zhizhu.zhicode.compose.ui.rememberFingerTracker
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话内容的指纹。
 *
 * 用 `snapshotFlow` 观察它时，只要它没变就不会重复触发吸底 ——
 * 所以它必须覆盖所有会让**末尾那一项变高**的信号：条数、正文长度、
 * 思考长度、工具条数，以及切会话与工作状态。
 *
 * 做成 `data class` 是为了让 `snapshotFlow` 能用 `equals` 判重，
 * 否则每次发射都会当成新值。
 */
private data class ContentStamp(
    val size: Int,
    val body: Int,
    val thinking: Int,
    val tools: Int,
    val session: String,
    val status: String?,
    /**
     * 往前补过多少页（见 `WorkspaceUiState.transcriptPrependRevision`）。
     *
     * 用它区分「**前面**补了历史」与「后面来了新内容」：两者都会让列表变长，
     * 但只有后者该吸底 —— 前者吸底会把用户刚滚上去的历史一把甩掉。
     *
     * 不靠"首项变了"去猜：重新打开同一条会话时首项也会变，而那次是该吸底的。
     */
    val prepend: Int,
)

/** 对话流，对应原版 renderChat() + addTranscriptWindow()。 */
private fun requestScrollToBottom(listState: androidx.compose.foundation.lazy.LazyListState) {
    val total = listState.layoutInfo.totalItemsCount
    if (total > 0) listState.requestScrollToItem(total - 1)
}

@Composable
fun ChatList(
    state: WorkspaceUiState,
    onToggleTool: (String) -> Unit,
    /**
     * 展开/收起**一个折叠组**。第二个参数是那一组的 groupKey（首成员 toolId）。
     *
     * 以前传的是"目标状态 + 整批的成员列表"，因为展开态记在成员的 `expanded` 上；
     * 现在分组由界面按 `ToolGrouping` 推导、展开态记在 `ChatItem.expandedGroups` 上，
     * 所以这一层只需要说清"哪一组"。
     */
    onToggleGroup: (String, String) -> Unit,
    onToggleThinking: (String) -> Unit,
    onMessageActions: (ChatItem) -> Unit,
    /**
     * 某一行的 `⋯` 菜单里选中了一项。参数：**哪一组**、**哪一行**、菜单文案。
     *
     * 与 [onMessageActions] 分开：后者只知道"哪一组"，做不了"展开这一条"这类动作。
     *
     * 菜单本身由那一行自己画（Miuix 下拉菜单），这里只转发"选中了什么" —— 所以
     * 不需要手指位置，也不需要把菜单状态放进 ViewModel。
     */
    onToolAction: (ChatItem, String, String) -> Unit,
    /**
     * 长按动作菜单的宿主插槽。在**每一项自己的布局里**调用，菜单就会贴那一项弹出
     * （见 `ZhiAnchoredActionMenu`）。
     *
     * 第二个参数是**手指位置**（相对该项）：非空时菜单从那一点长出来，
     * 而不是固定贴条目的边。
     */
    anchoredMenu: @Composable (String, DpOffset?) -> Unit,
    /**
     * 列表滚到顶、而上面还有没读进来的历史时调用一次。
     *
     * 恢复历史是分页的（见 `SessionReader.PAGE_MESSAGES`），所以"顶上还有"这件事
     * 只有列表自己知道（它才知道用户滚到了哪）。重复调用是安全的：ViewModel 里
     * 有「正在加载 / 已经没有更早的」两道守卫。
     */
    onLoadOlder: () -> Unit = {},
    /**
     * 「回到最新」：常驻窗口的尾部被淘汰过、最新消息不在内存里时调用。
     *
     * 由两处触发：列表底部的 chip，以及"发送时人还在历史里"（见上面滚动令牌那段）。
     * ViewModel 侧会用**最新一页替换**整份 transcript，所以这里不需要游标。
     */
    onLoadLatest: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * 列表底部预留的高度。悬浮的任务/状态卡会盖住列表下部，
     * 用它把内容顶到卡片上方，保证被遮住的对话仍能滑出来看到。
     */
    bottomInset: Dp = 0.dp,
    /** Activity 级 IME 从关闭到打开的代数；只在输入框真实聚焦时用于一次吸底。 */
    imeOpenGeneration: Int = 0,
    /** 发送框是否真实取得焦点；侧栏搜索框弹键盘时必须为 false。 */
    composerFocused: Boolean = false,
    /** 顶部预留高度：顶栏改成悬浮层后，内容要能滚到它下面。 */
    topInset: Dp = 0.dp,
    /**
     * **主体调试模式**：在真实消息上就地加调试信息（不另开页面）。
     *
     * 打开后每条消息上方多一行 `类型 · id · 正文/思考长度 · 工具数`，并给一个
     * 「源码」开关把原始文本摊出来；**并且把细节默认全展开**：
     *
     * - 思考面板按展开渲染（不改 state，只改这一次渲染的输入）；
     * - 工具组里的每条工具都摊开全量输出；
     * - 处理步骤（`processSteps`）也显示出来；
     * - 对话流末尾**内联**一份完整任务清单（不是悬浮卡那片截断视图）。
     *
     * 目的只有一个：**没有藏起来的东西**。排版问题（折行、行高、卡片间距、长文本溢出）
     * 只在内容全都铺开、且处于真实滚动容器与真实宽度下才看得出来。
     *
     * 之所以做在**真实列表**里而不是另开一页：另开一页用的是样例数据，
     * 长文本长度、工具输出体量、附件数量都与真实不符，测不出真实排版。
     */
    debugMode: Boolean = false,
    /**
     * 与 [debugMode] 配套：对话流末尾内联的任务清单。
     *
     * 悬浮任务卡只显示前 `maxTasks` 条（多了会盖住对话），完整清单原本只在
     * 「任务清单」窗口里。调试模式把它**摊进对话流本身**，于是"任务与消息的对应关系"
     * 一眼可见，也不必再开一个窗口。
     */
    debugTasks: List<AgentTask> = emptyList(),
) {
    ZhiFrameTrace.countRecompose("ChatList")
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    // 分页回调也走 rememberUpdatedState：那个长驻的 snapshotFlow effect 不该因为
    // 宿主每帧传进来一个新的 lambda 就被重启（重启会丢掉"上次观察到的三元组"）。
    val onLoadOlderState by rememberUpdatedState(onLoadOlder)
    val onLoadLatestState by rememberUpdatedState(onLoadLatest)

    /*
     * 是否跟随最新内容（吸底）。
     *
     * 判定挪进了 [rememberAutoFollow]（纯逻辑在 [AutoFollowPolicy]，有单测）：
     *
     * - **一开始滚动就立刻暂停** —— 否则用户按住往上拖的时候流式内容还会把他
     *   拽回底部，手感是"手指和自动滚动在抢"。
     * - **停手时按位置决定，带 8dp 滞回** —— 停在底部（或拖动过程中到过底部）
     *   就恢复跟随，往旧内容方向拖过阈值就不跟。
     *
     * ⚠️ 不能用「可见的末项是不是最后一项」来判断：内容增长本身就会把末项
     * 顶出屏幕，于是在我们来得及滚动之前条件就已经变成 false —— 表现是
     * 回复长过一屏之后跟随就断了，得手动往下滑。
     *
     * ⚠️ `isScrollInProgress` 在这里只反映用户拖动 / 惯性滚动：我们用的是
     * `requestScrollToItem`，它不走挂起滚动、也不占滚动互斥锁，不会把它置真。
     */
    // 长驻 effect 读取 State 的最新值，不能冻结首次组合时的 Boolean。
    val autoFollowState = rememberAutoFollow(listState, forceFollowToken = state.scrollToBottomToken)

    /*
     * 发消息 → 立刻滚到底（并恢复跟随）。
     *
     * 与下面的吸底 effect 分开，因为触发条件完全不同：吸底是"内容变了且用户在跟随"，
     * 而这条是"用户按了发送" —— 此刻即使他正在翻历史也要把他带回来。原版
     * `scrollChat()` 也是无条件 `chatAutoFollow = true` + 滚到底。
     *
     * ⚠️ 判据是"令牌**变了**"，不是"令牌非零"。
     *
     * 原先写的是 `if (state.scrollToBottomToken == 0L) return` —— 只挡住了首次组合
     * （令牌还是 0）那一次。问题是 `LaunchedEffect` 在**每次重新进入组合**时都会重跑：
     * 切走页签再切回来，面板被重建、这个 effect 重启，而令牌此时早就 > 0，
     * 于是又滚了一次底 —— 用户刚翻到的位置白翻。用户的原话：
     * 「切换页面，对话老是回到最低端」。
     *
     * 记下已处理过的令牌，只有它真的变化（= 用户按了发送）才滚。
     */
    var lastScrollToken by remember { mutableStateOf(state.scrollToBottomToken) }
    LaunchedEffect(state.scrollToBottomToken) {
        if (state.scrollToBottomToken == lastScrollToken) return@LaunchedEffect
        lastScrollToken = state.scrollToBottomToken
        // ⚠️ 尾部被淘汰过时**不能**滚到底：那时列表最后一条不是最新消息，
        // 滚过去只会停在历史中间，看起来像"发了消息却没反应"。
        // 这种情况要走「回到最新」（替换整份 transcript），落底由下面那条
        // transcriptJumpRevision 的 effect 负责 —— 它才拿得到替换后的新列表。
        if (currentState.transcriptHasNewer) onLoadLatestState() else requestScrollToBottom(listState)
    }

    /*
     * 上一次已处理的会话 id。
     *
     * 切会话 / 新会话时要**无条件**回到底部（新打开的会话应该从底部看起），
     * 而流式过程中只在用户本来就贴底时才跟随。
     * 两者用同一个标记区分：`activeSessionId` 变了就是“换会话了”，无条件滚。
     */
    var lastSessionId by remember { mutableStateOf(state.activeSessionId) }

    /*
     * 上一次看到的"往前补历史"计数。与 [lastSessionId] 配套：会话没变而计数变了，
     * 说明这次变长是**前面**补的，不是来了新消息。见 [ContentStamp.prepend]。
     */
    var lastPrepend by remember { mutableStateOf(state.transcriptPrependRevision) }

    /*
     * 「回到最新」之后显式落底。
     *
     * 单独一条 effect，因为它是**替换**：整份 transcript 换掉之后，刚才的滚动位置
     * 指向的那个条目已经不在列表里了，LazyColumn 会把位置夹到某一项上 ——
     * 落底必须显式做，不能靠吸底那条路顺手带（那条路会被"这次是往前补"的判据挡掉）。
     *
     * 只在**回到底部**之后才做，否则列表还没测出高度，`requestScrollToItem(total-1)`
     * 拿到的 total 可能是 0 或旧值。
     */
    var lastJump by remember { mutableStateOf(state.transcriptJumpRevision) }
    LaunchedEffect(state.transcriptJumpRevision) {
        if (state.transcriptJumpRevision == lastJump) return@LaunchedEffect
        lastJump = state.transcriptJumpRevision
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        withFrameNanos { }
        requestScrollToBottom(listState)
    }

    /*
     * 滚到顶部就往前补一页历史。
     *
     * 判据是 `(贴顶, 上面还有, 正在加载, 本次贴顶还没补过)`。
     *
     *  · 只贴顶不算数 —— 上方本来就没有更早的内容时贴顶是常态；
     *  · 正在加载时不算数 —— 否则滚动会连着触发很多次，每次都读一遍库；
     *  · **一次到达顶端只补一页**（`loadedAtTop`）—— 这一条是保险丝，不是优化。
     *
     * 为什么要那根保险丝：`LazyColumn` 在头部插入内容之后会把滚动锚点重新定位到
     * 原来那条上（它按 `key` 找回第一可见项的下标），于是列表通常**不再贴顶**，
     * 用户往上看时自然会再触发一次 —— 这正是想要的节奏。
     * 但如果某个版本/某个状态下锚点没有移动，`贴顶` 会一直是真，
     * 而"补完一页"又把 `loading` 从 true 变回 false（三元组变值 → 再触发），
     * 结果就是**一口气把整条会话从库里读一遍**：内存不会涨（尾部会淘汰），
     * 但读者是白读的，一万条的会话要多花几百毫秒。
     * 加了它之后最坏情况是"要再滚一下才能继续往前翻"，代价可以接受。
     *
     * ⚠️ 不要把它放宽成"只要贴顶就补"：那就是"打开会话就自动把整条历史读回来"，
     * 正是这一轮要消灭的东西。
     */
    var loadedAtTop by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
            .distinctUntilChanged()
            .collect { atTop ->
                // 离开顶端就重新上膛：再滚回来时可以再补一页。
                if (!atTop) {
                    loadedAtTop = false
                    return@collect
                }
                if (loadedAtTop || !currentState.transcriptHasOlder || currentState.transcriptLoadingOlder) {
                    return@collect
                }
                loadedAtTop = true
                onLoadOlderState()
            }
    }

    /*
     * 自动吸底。
     *
     * 原实现有两个叠在一起的问题，合起来就是“不及时”：
     *
     * 1. 用 `LaunchedEffect(内容长度…)` + `scrollToItem`。
     *    `scrollToItem` 是**挂起**函数，而流式输出下内容长度几乎每帧都在变 ——
     *    LaunchedEffect 于是不停地取消并重启协程，滚动经常在真正生效之前就被
     *    取消掉，只能等某个空档才追上，看起来就是“滚一下停一下”。
     *    改用 `requestScrollToItem`：**非挂起**，只登记一个目标下标，
     *    在**下一次测量**里生效，取消不掉。
     *
     * 2. 用“可见末项”算贴底（见 [autoFollow] 的注释）。
     *
     * 另外改成一个**长驻**的 effect + `snapshotFlow` 观察内容指纹，
     * 而不是把内容长度当 `LaunchedEffect` 的 key —— 后者每个 token 都要
     * 重建一次协程，这些开销完全没有必要。
     *
     * 日志输出时不用 `animateScrollToItem`：内容连续增长时每一步都起动画
     * 会互相打断，反而更抖。
     */
    var handledImeGeneration by remember { mutableStateOf(imeOpenGeneration) }
    LaunchedEffect(listState, imeOpenGeneration, composerFocused) {
        // autoFollow 是键盘打开前的用户意图快照：只有本来就在底部才吸底，
        // 键盘缩短 viewport 后不能再用 canScrollForward 反推，否则会把真正贴底误判成历史位置。
        if (!composerFocused || imeOpenGeneration <= handledImeGeneration) return@LaunchedEffect
        handledImeGeneration = imeOpenGeneration
        if (autoFollowState.value) {
            snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.viewportEndOffset }
                .first { (count, viewport) -> count > 0 && viewport > 0 }
            withFrameNanos { }
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
        snapshotFlow {
            val s = currentState
            val last = s.transcript.lastOrNull()
            ContentStamp(
                size = s.transcript.size,
                body = last?.body?.length ?: -1,
                thinking = last?.thinking?.length ?: -1,
                tools = last?.tools?.size ?: -1,
                session = s.activeSessionId,
                status = s.workingStatus,
                prepend = s.transcriptPrependRevision,
            )
        }.collect { stamp ->
            // LazyColumn 实际画的是 blocks，不能由 transcript.size 推算索引；
            // 多条消息合并为一个回合时，真实尾项只由 totalItemsCount 决定。
            val switched = stamp.session != lastSessionId
            lastSessionId = stamp.session
            // 只是往前补了一页历史就不吸底（见 [ContentStamp.prepend]）。
            // ⚠️ 判定要在写入新值**之前**做，而且要排除切会话那一次 ——
            // 新会话的第一页同样带着一次计数变化。
            val prepended = !switched && stamp.prepend != lastPrepend
            lastPrepend = stamp.prepend
            if (listState.layoutInfo.totalItemsCount <= 0) return@collect
            // 尾部被淘汰过时也不吸底：那时"最后一条"不是最新消息，
            // 吸过去等于把用户钉在历史中间（而且要回到最新得整份替换，见 jump effect）。
            if (currentState.transcriptHasNewer) return@collect
            if (switched || (autoFollowState.value && !prepended)) requestScrollToBottom(listState)
        }
    }

    // 输入法弹出/收起时把对话吸回底部：键盘顶起视口后列表可视高度骤变，
    // 原先贴底的内容被顶出屏幕；用户此刻的意图几乎总是"接着输入"，所以这里
    // 无条件回底（不改 autoFollow，避免键盘收起时把正在看历史的人拽走）。
    //
    // ⚠️ 这里**不能在组合期读 inset**，必须走 `snapshotFlow`。
    //
    // 原先写的是 `val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)`
    // 再 `LaunchedEffect(imeBottom)`。问题是 `WindowInsets.ime` 在**键盘动画的整个
    // 过程中每帧都在变**，而组合期读 = 每帧都让这一层（连同整个列表）重组一次。
    //
    // 三个触发点全都落在这条路径上：输入器随键盘升降、悬浮任务卡与它同列跟着动、
    // 以及**发送消息时键盘收起**（所以"发个你好、回复也会闪"）。用户报的
    // 「对话流狂闪」正是这条路径：每帧重组 → 每帧重新布局列表 →
    // 相邻两帧的内容在"重排前 / 重排后"之间来回，看起来就是整片在闪。
    //
    // 放进 `snapshotFlow` 之后，读取发生在协程里、**不进组合**，所以 inset 每帧变
    // 一次也不重组；而"要先读一次当前值"这件事由 flow 的首次发射承担，
    // 与原先那次组合期读**语义等价**。
    //
    // `distinctUntilChanged` 不是省事：键盘动画里 inset 会连着好几帧同值，
    // 去掉重复可以少发几次滚动请求（滚动是幂等的，但没必要发）。
    // 对话流切成「块」（一轮助手回合一块）。放在 `LazyColumn` **外面**算：
    // 它的 content lambda 是 `LazyListScope.() -> Unit`，不是 @Composable 上下文，
    // 在里面调 `remember` 编译不过。
    val blocks = remember(state.transcript) {
        TurnLayout.blocks(
            state.transcript.map { TurnLayout.Entry(it.id, isUser = it.kind == ChatKind.USER) },
        )
    }
    // TurnLayout only stores ids. Resolve them through one index instead of scanning the
    // complete transcript once for every member of every block (which becomes quadratic
    // for long sessions).
    val transcriptById = remember(state.transcript) { state.transcript.associateBy { it.id } }

    // 「已经见过」的消息 id —— 用来判断某个成员是**本次新出现的**还是历史。
    //
    // ## 为什么需要它
    //
    // 一轮助手回合是**一个** `LazyColumn` item（见 TurnLayout），所以 item 级的
    // `animateItem` 只在整块位置变化时触发；**回合内部**新追加的工具卡/正文
    // 不产生新 item，于是没有任何进入动画 —— 观感就是文字"蹦"出来的。
    // 用户要的「新消息整条淡入/上移」缺的正是这一层。
    //
    // ## 为什么不能无条件播
    //
    // `LazyColumn` 会**回收组合**：往上滚到历史消息时，那一条是**重新进入组合**的。
    // 无条件播进入动画，历史消息就会在滚动中不停闪 —— 那比"没有动画"糟得多。
    // 所以判据必须是"这个 id 以前没见过"，而不是"它刚进入组合"。
    //
    // 集合本身放在**文件级**（见 `seenMessageIds`），**不能**放在这里的 `remember` 里 ——
    // 这一点是本轮踩过的坑，写下来免得下一个人又挪回去：
    //
    //   原来写的是 `remember(activeSessionId) { HashSet().apply { addAll(transcript) } }`。
    //   问题在于这个 `remember` 的寿命**只到本层组合被丢弃为止**。一旦 ChatList 这层
    //   组合被重建（工作区宿主重进组合、面板被重新挂载…），这个块会**再跑一次**，
    //   而 `addAll(当前的 transcript)` 会把**刚刚新到的那条消息也登记成"已见过"** ——
    //   于是它的 `add` 返回 false，`animate` 为假，**动画静默消失**：
    //   代码看着没错、编译通过、也不报错，只是不播。
    //
    // 所以：集合放文件级，并且**按会话只播种一次**（`seenSeededSession` 判重）。
    // 重建组合时 `seenIdsFor` 只会把已有集合还回来，不再重新登记。
    //
    // ⚠️ `add` 的返回值就是"以前没见过"，所以下面用 `remember(item.id) { seenIds.add(id) }`
    // 一次搞定"判断 + 登记"。它在组合期有副作用，但幂等、只做一次集合插入 ——
    // 换成 `LaunchedEffect` 就晚了：那一帧的 `isNew` 必须**同步**拿到。
    val seenIds = remember(state.activeSessionId) {
        seenIdsFor(state.activeSessionId, state.transcript)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = 14.dp),
            contentPadding = PaddingValues(top = topInset, bottom = bottomInset),
        ) {
            if (state.transcript.isEmpty()) {
                item { EmptyState() }
            }
            // ---- 顶上还有更早的历史时的占位 -----------------------------------
            //
            // 恢复历史是分页的（见 `SessionReader.PAGE_MESSAGES`）：这一条既是"上面还有"
            // 的提示，也是"正在取"的反馈 —— 没有它的话，用户滚到顶只会看到内容突然停住，
            // 像是记录缺了一块。
            if (state.transcriptHasOlder) {
                item(key = "older-loader", contentType = "older-loader") {
                    OlderLoader(loading = state.transcriptLoadingOlder)
                }
            }
            // ---- 按「块」渲染 -------------------------------------------------
            //
            // 一块 = 一个 `LazyColumn` item：
            //  · `USER` 自己一块（容器外）；
            //  · 其余全部并进**同一个助手回合容器**（反编译版 `beginConversation()`）——
            //    容器是**透明**的、padding 为 0，只用上下外边距把这一轮与相邻内容拉开，
            //    所以它不画框、不抢注意力，只是把"这一轮的东西"归到一起。
            //
            // 切块规则在 `TurnLayout`（纯逻辑、有单测）：判错不会编译失败，
            // 只会让间距/包裹关系不对。
            //
            // ⚠️ 每个成员仍在**容器内部**各自成一层组合（`key(item.id)`），
            // 手指追踪与长按菜单也挂在成员自己那一层 —— 于是菜单锚点与以前**完全一致**，
            // 不需要任何跨层坐标换算（那正是锚偏的常见来源）。
            items(
                items = blocks,
                key = { it.key },
                contentType = { block ->
                    when (block) {
                        is TurnLayout.Block.Standalone -> "message-user"
                        is TurnLayout.Block.Turn -> "message-turn"
                    }
                },
            ) { block ->
                // 新消息淡入 + 已有消息位置变化时平滑推移。
                //
                // ⚠️ `fadeOutSpec = null` 是刻意的，别加回来。
                // 默认的淡出会让"被移除的项"在动画期间**继续绘制**，于是当
                // `newSession()` 把 transcript 一次清空时，那几张还没淡完的卡片
                // 会和紧接着出现的 `EmptyState()` 叠在一起 —— 表现为卡片与
                // "想让 ZhiCode 做什么？"互相穿透、错位，看起来像渲染 bug。
                val memberIds = when (block) {
                    is TurnLayout.Block.Standalone -> listOf(block.id)
                    is TurnLayout.Block.Turn -> block.ids
                }
                val members = remember(memberIds, transcriptById) {
                    memberIds.mapNotNull { transcriptById[it] }
                }
                if (members.isEmpty()) return@items
                Box(
                    modifier = Modifier
                        .animateItem(fadeOutSpec = null)
                        .padding(end = 14.dp)
                        // 回合容器：反编译版是 `margins(0, dp(10), 0, dp(14))`。
                        .then(
                            if (block is TurnLayout.Block.Turn) {
                                Modifier.padding(top = 10.dp, bottom = 14.dp)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // ⚠️ 调试条与消息卡必须放进**同一个 Column** 里，不能并列在 Box 下。
                    // 这个 Box 是给 anchoredMenu 定位的（菜单要盖在卡片上），而 Box 的子项
                    // 是**叠放**而不是竖排 —— 直接并列会让调试条被卡片盖住。
                    Column(modifier = Modifier.fillMaxWidth()) {
                        members.forEach { item ->
                            // 手指位置追踪：挂在这一条自己的 Box 上，于是记录到的坐标就是
                            // 「相对这一条」的，与 anchoredMenu 的锚点是同一个坐标系。
                            val finger = rememberFingerTracker()
                            var fingerOffset by remember(item.id) { mutableStateOf<DpOffset?>(null) }
                            key(item.id) {
                                // ⚠️ 每一位成员外面这一层 Box 是**必须**的，不是多余的嵌套：
                                //
                                // 1. 它承载手指追踪（`finger.modifier`），记录到的坐标因此是
                                //    「相对这一条」的；
                                // 2. `anchoredMenu` 用 `Modifier.absoluteOffset` 定位，而
                                //    `absoluteOffset` 的坐标是相对**直接父节点**的 ——
                                //    若把它挂到回合 Column 下面（成为整个回合并列的子项），
                                //    第 2 条之后的菜单会整体偏掉前面所有成员的高度。
                                //
                                // 所以「手指追踪 + 菜单锚点」必须始终贴在**自己那一条**上。
                                // 本次新出现的成员播一次「淡入 + 轻微上移」；历史成员原样直出。
                                AnimatedMember(
                                    animate = remember(item.id) {
                                        val fresh = seenIds.add(item.id)
                                        // 探针：新消息应当 fresh=true。全是 false 就说明
                                        // 判据又退回"按组合生命周期算新的"了。
                                        ZhiFrameTrace.note("seen/item " + item.id + " fresh=" + fresh)
                                        fresh
                                    },
                                ) {
                                Box(modifier = Modifier.fillMaxWidth().then(finger.modifier)) {
                                    // ⚠️ 调试条与消息卡必须放进**同一个 Column** 里，不能并列在
                                    // 上面那个 Box 下：Box 的子项是**叠放**而不是竖排，
                                    // 直接并列会让调试条被卡片盖住。
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                    // ---- 主体调试模式：就地加料（不改下面任何卡片的渲染） ----
                                    if (debugMode) {
                                        MessageDebugStrip(item)
                                    }
                                    // 调试模式把细节**默认全展开**：思考面板、工具输出、处理步骤。
                                    //
                                    // 做法是"只改这一次渲染的输入"（item.copy(...)），不动 state ——
                                    // 于是折叠按钮仍然能点（点了会写到 state，而调试模式的强制展开会把它
                                    // 覆盖回展开 —— 这是刻意的：调试模式就是"全都摊开"的视图，
                                    // 想看折叠后的样子就把它关掉）。
                                    val shown = if (debugMode) item.fullyExpanded() else item
                                    when (shown.kind) {
                                        ChatKind.USER -> UserBubble(shown) {
                                            fingerOffset = finger.offset()
                                            onMessageActions(item)
                                        }
                                        ChatKind.ASSISTANT -> AssistantCard(
                                            item = shown,
                                            onToggleThinking = { onToggleThinking(item.id) },
                                            onLongPress = {
                                                fingerOffset = finger.offset()
                                                onMessageActions(item)
                                            },
                                        )
                                        ChatKind.TOOL_GROUP -> ToolBatch(
                                            item = shown,
                                            // 传**原始** id：折叠动作要写到 state 上，用 shown 的 id 会指向同一条
                                            // （id 不变），但语义上更清楚的是"动的是哪一条消息"。
                                            onToggleTool = onToggleTool,
                                            // 组键由界面从 toolId 推导（见 ToolGrouping），所以这里传的是
                                            // **哪一组**，而不是"整批的成员列表要收起"。
                                            onToggleGroup = { groupKey -> onToggleGroup(item.id, groupKey) },
                                            // `⋯` 是**单个工具**的操作，所以传的是那一行的 id，
                                            // 而不是整组（以前传整组，于是点单行弹出整组菜单）。
                                            onToolAction = { toolId, label -> onToolAction(item, toolId, label) },
                                        )
                                        ChatKind.ERROR -> ErrorCard(shown)
                                        ChatKind.INFO -> InfoCard(shown)
                                    }
                                    } // Column（调试条 + 消息卡，竖排）
                                    // 菜单挂在**成员自己的 Box** 里，并用手指位置作偏移 ——
                                    // 于是它从手指那一点长出来，且坐标空间与手指追踪同源。
                                    anchoredMenu(item.id, fingerOffset)
                                } // Box（手指追踪 + 菜单锚点）
                                } // AnimatedMember（一次性进入动画）
                            }
                        }
                    } // Column（整个回合：各成员依次竖排）
                }
            }
            // ---- 主体调试模式：把**完整任务清单**内联进对话流 ----
            //
            // 悬浮任务卡只显示前 maxTasks 条（多了会盖住对话），完整清单原本只在
            // 「任务清单」窗口里。调试模式把它摊在对话流末尾，于是"任务与消息的对应
            // 关系"一眼可见，也不必再开一个窗口。
            if (debugMode && debugTasks.isNotEmpty()) {
                item(key = "debug-tasks", contentType = "debug-task-list") {
                    InlineTaskList(debugTasks)
                }
            }
            // 尾部被淘汰过：末尾给一个明确的"回到最新"，否则用户往下滚只会停在历史中间。
            if (state.transcriptHasNewer) {
                item(key = "jump-to-latest", contentType = "jump-to-latest") {
                    JumpToLatest(onJumpToLatest = onLoadLatestState)
                }
            }
            // 任务进度与工作状态**不放在滚动区**（对应原版把它们挂在固定的
            // chatBottomHost 上），改由 AppScaffold 固定在输入器上方。
            item(key = "chat-bottom-spacer", contentType = "chat-spacer") {
                Box(modifier = Modifier.size(10.dp))
            }
        }
        // Miuix 滚动条：自动淡入淡出、可拖拽，比自绘指示条更贴近 HyperOS
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 主体调试模式下每条消息上方的**调试条**。
 *
 * 内容刻意全是"一眼能对上号"的元信息：类型、id、正文/思考字数、工具数与各状态计数、
 * 上下文脚注。它的用途是回答"这条为什么长这样"——比如卡片高度异常时，先看
 * `1200/3400`（正文/思考）就能立刻分清是正文太长还是思考面板展开着。
 *
 * 「源码」是一个**开关**而不是常显：源码往往比渲染结果长好几倍，
 * 常显会把真实排版挤走 —— 而这一模式的价值恰恰是看真实排版。
 */
@Composable
private fun MessageDebugStrip(item: ChatItem) {
    val scheme = MiuixTheme.colorScheme
    var showSource by remember(item.id) { mutableStateOf(false) }
    val running = item.tools.count { !it.completed && !it.awaitingPermission }
    val failed = item.tools.count { it.failed }
    val awaiting = item.tools.count { it.awaitingPermission }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.kind.name,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontWeight = FontWeight.Bold,
                color = when (item.kind) {
                    ChatKind.ERROR -> ZhiColors.red()
                    ChatKind.INFO -> ZhiColors.amber()
                    else -> scheme.primary
                },
            )
            Text(
                text = buildString {
                    append("  ")
                    append(item.body.length).append('/').append(item.thinking.length)
                    if (item.tools.isNotEmpty()) {
                        append(" t").append(item.tools.size)
                        if (running > 0) append(" ▶").append(running)
                        if (failed > 0) append(" ✗").append(failed)
                        if (awaiting > 0) append(" ⧗").append(awaiting)
                    }
                    if (item.streaming) append(" …")
                    if (item.contextTokens >= 0) append(" ctx").append(item.contextTokens)
                    append("  ").append(item.id)
                },
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariantSummary,
                // 元信息是**单行且不换行**的：它不该把消息卡往下推，
                // 超长时截断即可（要看全的在调试浮层的「对话条目」里）。
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 源码开关走 ZhiSmallPill（24dp 胶囊），不是 Miuix TextButton：
            // 后者最小高 40dp、字号走主题 button 档，放进这一行会把整条调试带撑得
            // 比消息卡还显眼，而调试带本该是"贴着卡片的一条注记"。
            ZhiSmallPill(
                label = if (showSource) "收起源码" else "源码",
                highlighted = showSource,
                onClick = { showSource = !showSource },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (showSource) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                cornerRadius = ZhiRadius.inner,
                insideMargin = PaddingValues(8.dp),
                colors = CardDefaults.defaultColors(
                    color = ZhiColors.cardInnerSurface(),
                    contentColor = scheme.onSurfaceVariantSummary,
                ),
            ) {
                Text(
                    text = item.body,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * 调试模式下的"全展开"视图。
 *
 * **只改这一次渲染的输入，不动 state**：思考面板按展开渲染、工具组里每条工具都摊开输出。
 * 这样调试视图与用户自己折叠/展开的状态互不干扰 —— 关掉调试模式，看到的就是用户原本的状态。
 *
 * 之所以不直接把 state 改掉（例如"打开调试时把所有 thinking 置为展开"）：
 * 那会在关掉调试模式后留下一个被改过的界面，而用户并没有点过任何折叠按钮。
 */
private fun ChatItem.fullyExpanded(): ChatItem = when (kind) {
    ChatKind.ASSISTANT -> copy(thinkingExpanded = true, streaming = streaming)
    ChatKind.TOOL_GROUP -> copy(
        groupCompleted = true,
        // 折叠组默认收起，调试模式要把**每一组**都摊开 —— 展开态记在
        // `expandedGroups` 上，所以这里按键集合给全（而不是像以前那样
        // 把每个成员的 `expanded` 置真：那个标志位管的是"这条工具的输出展开"，
        // 两件事不共用）。
        expandedGroups = ToolGrouping
            .group(tools.map { ToolGrouping.Entry(it.id, it.toolName) })
            .filterIsInstance<ToolGrouping.Segment.Group>()
            .map { it.key }
            .toSet(),
        // 只有"已完成且有输出"的工具才有可展开的内容 —— 与卡片自身的判断保持一致，
        // 否则运行中/等待授权的行会被强行展开，露出一片空白。
        tools = tools.map { tool ->
            if (tool.completed && tool.output.isNotBlank()) tool.copy(expanded = true) else tool
        },
    )
    else -> this
}

/**
 * 内联的**完整**任务清单（调试模式专用）。
 *
 * 与悬浮任务卡的区别：不过滤条数、不打折信息 —— 每条任务都显示状态、标题与详情。
 * 它是"没有藏起来的东西"这条要求里最直接的一块：任务清单原本只存在于另一个窗口里。
 */
@Composable
private fun InlineTaskList(tasks: List<AgentTask>) {
    val scheme = MiuixTheme.colorScheme
    val done = tasks.count { it.state == TaskState.DONE }
    Column(
        modifier = Modifier.fillMaxWidth().padding(end = 14.dp, top = 6.dp, bottom = 4.dp),
    ) {
        Text(
            text = "TASK_LIST  $done/${tasks.size}  （调试模式 · 完整清单）",
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = scheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            cornerRadius = ZhiRadius.card,
            insideMargin = PaddingValues(10.dp),
            colors = CardDefaults.defaultColors(
                color = ZhiColors.cardSurface(),
                contentColor = scheme.onSurface,
            ),
        ) {
            Column {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        ZhiHorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = when (task.state) {
                                TaskState.DONE -> ZhiIcons.done
                                TaskState.RUNNING -> ZhiIcons.pending
                                TaskState.PENDING -> ZhiIcons.awaiting
                            },
                            contentDescription = task.state.name,
                            tint = when (task.state) {
                                TaskState.DONE -> ZhiColors.green()
                                TaskState.RUNNING -> scheme.primary
                                TaskState.PENDING -> scheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = task.title,
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            fontWeight = if (task.state == TaskState.RUNNING) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.padding(start = 7.dp).weight(1f),
                        )
                        Text(
                            text = task.state.name,
                            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    // 详情是 Markdown（真实任务清单窗口里也是这么渲染的），
                    // 所以这里用同一个渲染器，长内容/表格/代码块都能照原样看到。
                    if (task.detail.isNotBlank()) {
                        ZhiMarkdown(
                            source = task.detail,
                            bodyFontSize = MiuixTheme.textStyles.body2.fontSize,
                            modifier = Modifier.padding(start = 20.dp, top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「已经见过」的消息 id —— 判断某个成员是**本次新出现**的还是历史。
 *
 * ## 为什么是文件级，而不是 `ChatList` 里的 `remember`
 *
 * 见调用点的说明：`remember` 只活到那层组合被丢弃为止，而重建组合时它会拿**当前的**
 * transcript 重新播种，把刚到的消息也算成"历史" —— 动画就这么静默消失了。
 * 放在文件级之后，集合的寿命等于进程，重建组合不会误登记。
 *
 * ## 代价（写清楚，别当成没想过）
 *
 *  · 只增不减，随一次会话的消息数增长。一条消息一个字符串，量级可忽略；
 *    换会话时不会清空，但 id 是唯一的，误判不会发生。
 *  · 只读主线程（组合期），所以不需要加锁。
 *
 * ⚠️ 上面那条"量级可忽略"的前提是**同一时刻只有一条会话被浏览**。真机上实测过
 * 一万条会话的用法之后，这个前提要补一句：**换会话时必须换一份集合**，
 * 否则跨会话累计的字符串是随"用过的会话数 × 每条的条数"增长的 ——
 * 那是唯一还在无界增长的容器，而它没有任何功能上的理由要跨会话保留
 * （`add` 的语义只是"这一条在本会话里以前见过吗"）。
 */
private var seenMessageIds = HashSet<String>()

/** 上一次播种 [seenMessageIds] 的会话 id；`null` = 还没有播种过。 */
private var seenSeededSession: String? = null

/**
 * 取「已经见过」的集合，必要时先播种。
 *
 * 播种 = 把**当时的** transcript 全部登记为已见过（它们是历史，不该补播动画）。
 * ⚠️ 只在会话**变了**的时候播种；同一个会话下反复调用只会把已有集合还回来 ——
 * 这一点正是上面那个 bug 的修复点。
 *
 * 换会话时**整个换掉**集合（而不是继续往同一个里加）：见 `seenMessageIds` 的说明。
 */
private fun seenIdsFor(
    sessionId: String?,
    transcript: List<ChatItem>,
): MutableSet<String> {
    if (seenSeededSession != sessionId) {
        seenSeededSession = sessionId
        seenMessageIds = HashSet()
        seenMessageIds.addAll(transcript.map { it.id })
        // 探针：正常一次会话只会看到一行。若这里反复刷，说明会话 id 在抖动
        // （那会让每条消息都被当成"新的"而集体淡入，是另一种病）。
        ZhiFrameTrace.note("seen/seed session=" + sessionId + " n=" + transcript.size)
    }
    return seenMessageIds
}

/**
 * 新成员进入时上移的距离。
 *
 * 取 8dp：够看出"它是从下面滑上来的"，又不会让整块内容看起来在跳。
 * 位移与淡入用同一条 [ZhiMotion.FADE_IN_MILLIS]（300ms + SinOut）——
 * 与 Miuix 的 `PopupDimEnter` 同一个量纲，跟全应用的"出现"节奏一致。
 */
private val MemberEnterRise = 8.dp

/**
 * 一条成员（消息 / 工具卡）的**一次性**进入动画：淡入 + 轻微上移。
 *
 * ## 为什么是"一次性"而不是普通的 AnimatedVisibility
 *
 * [animate] 传的是"**本次新出现**"，由调用点的 `seenIds` 决定（见那里的说明）。
 * 一旦为 true 就不再翻转 —— 而 [animate] 为 false 时本组件直接原样输出内容，
 * **连 layer 都不建**。于是：
 *
 *  · 历史消息（含滚动回来重新进入组合的）走零开销的那条路；
 *  · 只有真正新到的那一条才付一次动画与一个 graphicsLayer。
 *
 * 这也是为什么不写成 `AnimatedVisibility`：那个组件管的是"出现/消失"，
 * 而这里要的是"新出现的补一段位移"，两者语义不同。
 *
 * ## 为什么动画跑完就把 layer 摘掉
 *
 * `graphicsLayer` 会让这一条**各自单独成层**；消息列表里每条都常驻一层是没必要的
 * 开销（外面还套着模糊的捕获层），所以只在动画期间挂着，进度到 1 之后返回裸
 * `Modifier`。摘掉 modifier 节点不会重建内容，也不会丢掉子级的 `remember`。
 *
 * ## ⚠️⚠️ 进度必须在**绘制期**读，不能在组合期读
 *
 * 先看写错会怎样 —— 这是本工程真实踩过的坑，和 `AssistantCard` 里那个流式光标
 * 是**同一个错**（见 MessageCards.kt 的说明）。错误写法是：
 *
 *     val p = progress.value              // ← 组合期读
 *     ...
 *     Modifier.graphicsLayer { alpha = p }   // 值早就被读死了，这里读的只是局部变量
 *
 * `progress.value` 在组合里被读 → 这个动画的**每一帧**都让本组件重组一次。
 * 本组件是消息列表的**成员包装**，它重组就会把 `content()` 整条重新求值 ——
 * 也就是说，为了让一条消息淡入，整条消息（含 Markdown 正文）每帧重建。
 * 外面还有逐帧的列表布局在跑，两边叠起来就是"动画看不出来、只看到卡"。
 *
 * 正确写法是把读数放进 `graphicsLayer` 的 lambda：
 *
 *     Modifier.graphicsLayer {
 *         val v = progress.value          // ← 绘制期读，只让这一层重绘
 *         alpha = v
 *         translationY = (1f - v) * rise
 *     }
 *
 * `graphicsLayer { }` 的 lambda 在**绘制阶段**求值，读到的状态只登记在绘制作用域上，
 * 所以整个动画期间**组合一次都不跑**（只有放进/结束各一次）。
 *
 * ## 那 `done` 这个标志是干什么的
 *
 * 上面说"进度到 1 就摘 layer"，而摘不摘是**组合期**的决定。如果写成
 * `if (progress.value >= 1f) Modifier else …`，为了做这个判断又得在组合里读一次 ——
 * 等于把刚省下来的开销又请回来。所以改用一次性翻牌：动画 `animateTo` 返回之后
 * 才把 `done` 置真，**整个动画期间组合只跑首尾两次**。
 */
@Composable
private fun AnimatedMember(
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    if (!animate) {
        content()
        return
    }
    val progress = remember { Animatable(0f) }
    // 动画结束才翻的一次性标志：避免为了判断"要不要摘 layer"而在组合期读进度。
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(ZhiMotion.FADE_IN_MILLIS, easing = SinOutEasing),
        )
        done = true
    }
    // 位移量在组合期换算（density 几乎不会变）；进度只在绘制期的 lambda 里读。
    val rise = with(LocalDensity.current) { MemberEnterRise.toPx() }
    Box(
        modifier = if (done) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                // ⚠️ 这一行是整个组件性能的关键：绘制期读，动画期间不触发重组。
                val v = progress.value
                alpha = v
                translationY = (1f - v) * rise
            }
        },
    ) {
        content()
    }
}
