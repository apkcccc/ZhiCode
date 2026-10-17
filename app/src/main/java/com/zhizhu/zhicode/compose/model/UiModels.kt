package com.zhizhu.zhicode.compose.model

import com.termux.app.zhicode.core.FileOps

// `ChatKind` 与 `ToolKind` 都在自己的文件里：它们在 `model` 包内，
// 而本文件还拖着一整套界面状态类 —— 纯逻辑层（ToolGrouping / TurnLayout）只需要那两个
// 枚举，不该为了它们把这一整份编译进秒级回路。


enum class RiskLevel { NORMAL, HIGH }

data class ToolActivity(
    val id: String,
    val toolName: String,
    val displayName: String,
    val summary: String = "",
    val completed: Boolean = false,
    val failed: Boolean = false,
    val exitCode: Int? = null,
    val elapsedMs: Long = 0L,
    val additions: Int = 0,
    val deletions: Int = 0,
    val output: String = "",
    /**
     * [output] 是不是**被截断过的预览**。
     *
     * ## 为什么需要这个标志
     *
     * 一条工具输出可能有几 MB（`Bash` 的完整 stdout、`Read` 的文件正文），而一次会话里
     * 可能有过几百条工具。把它们全部塞进 `transcript` 就等于**把整个会话的工具输出常驻内存**
     * —— 在 256MB 堆的设备上，这是打开长会话直接 OOM 的主因之一。
     *
     * 所以从历史恢复时 [output] 只带一份有界预览（见 `SessionReader.OUTPUT_PREVIEW_CHARS`），
     * 真正的全文在用户展开这一条时按 [id] 去存储里取（`SessionStore.readToolResult`）。
     *
     * ## 为什么不能靠"文本里有没有省略提示"来判断
     *
     * 命令自己的输出完全可能包含同样的提示文字。用一个独立的布尔字段表达"这份是预览"，
     * 界面才知道该不该发起那次读取 —— 靠内容判断会在恰好输出该文案时误判。
     */
    val outputTruncated: Boolean = false,
    /**
     * 写文件类工具的**统一 diff**（引擎算好的，仅供界面显示，不发给模型）。
     *
     * ## 为什么必须单独存一份，不能从 [output] 里捞
     *
     * 引擎的 `Write/Edit/MultiEdit/Delete` 返回的 `content` 是**一句自述**
     * （`Wrote 505 bytes to /data/.../x.txt`），真正的改动在
     * `ToolExecutionResult.diff` 里（`tools/UnifiedDiff.create(...)`），
     * 而 `ZhiCodeEngine.persistToolDiff` 也把它单独写进了 `tool_diff` 事件。
     *
     * 界面这边原来只认"输出文本本身长得像 diff"（`ToolText.isFileDiff(output)`）——
     * 于是**那块更暗的 diff 预览井在真机上从来没出现过**：数据早就送到门口了
     * （`ZhiEngineController` 一路 `diff = result.diff` 传进来），
     * 却在这一层被丢掉。参考实现的展开态是「diff 井 + 输出井」两块**都画**
     * （`addToolCard`：先 `colorDiff(item.diff)` 画在 `TERMINAL_BG` 上，再画 result），
     * 不是二选一。
     */
    val diff: String = "",
    /**
     * 工具带回来的**富内容预览**（`ToolExecutionResult.additionalContent`）。
     *
     * ## 与 [diff] 是并列关系，不是替代
     *
     * 两者都从 `ToolExecutionResult` 来、都不是从 [output] 里捞的，而且**可以同时存在**：
     * 展开态是「图片 → diff 井 → 输出井」三块都画（见 `MessageCards` 的 EXPANDED 分支）。
     *
     * ## 数据从哪来
     *
     * 目前只有 `Sandbox` 工具的 `screenshot` 会产出：沙箱把截图存到自己的私有目录后，
     * 读回字节、base64 编码，包成 `{type:image, source:{type:base64, media_type, data}, name}`
     * 塞进 `additionalContent`（`ZhiSandboxTool.screenshotResult`）。
     *
     * ## 曾经漏在哪
     *
     * 引擎一直把它当作 user 消息的一部分发给模型（`ZhiCodeEngine` 的
     * `additionalToolContent`），**但界面这一层从来没有接过** —— 于是"沙箱截的图
     * 模型看得到、用户看不到"。`UiCanvasTool` 里那句注释"包装成界面能直接消费的
     * 附加内容块"当时是**不成立的**。现在这条链是：工具产出 → 引擎透传 →
     * `ZhiEngineController` 用 `readChatImageBlocks` 解析 → 这里 → 工具卡渲染。
     *
     * ## 为什么只存 base64 字符串而不解码
     *
     * 与用户消息里的图同一个理由（见 [ChatImage]）：解码要几十毫秒且可能 OOM，
     * 必须交给界面层在 `Dispatchers.Default` 上做；这一层只做搬运。
     */
    val previews: List<ChatImage> = emptyList(),
    val expanded: Boolean = false,
    val awaitingPermission: Boolean = false,
    val kind: ToolKind = ToolKind.OTHER,
    /**
     * 本工具开始执行的时刻（`System.currentTimeMillis()`），0 表示还没有基准。
     *
     * ## 为什么需要它
     *
     * [elapsedMs] 是**跟着输出块**推过来的（引擎按 chunk 回调进度）。一个跑 30 秒
     * 都不吐字的命令，那个值就停在最后一次进度的位置上，界面上看起来像卡死了。
     * 有了起点，界面侧的定时刷新才能自己把秒数续下去（见 `ToolActions.displayElapsedMs`）。
     *
     * 从历史会话恢复出来的工具是**已完成**的，`startedAtMs` 保持 0 —— 那些工具的耗时
     * 取引擎存下来的值即可，不需要也不应该重新计时。
     */
    val startedAtMs: Long = 0L,
    /**
     * 运行中累积的标准输出 / 错误输出**字符数**。
     *
     * 只给运行中的标签用（`实时 00:12 · 标准输出 12.3 KB · 错误输出 0 B · 进程运行中`）。
     * 为什么要分开两份：只看 [output] 的总长说不出"错误输出有多少"，
     * 而恰恰是错误输出的体量在决定用户要不要去点开看。
     *
     * 工具结束后这两个值不再更新（结束时 [output] 换成了最终结果，与原版一致）。
     */
    val stdoutChars: Int = 0,
    val stderrChars: Int = 0,
    /**
     * 命令类工具的**原始命令行**（未截断、保留换行）。
     *
     * 折叠态显示的是 [summary] —— 那是 `ToolText.truncateCommand` + `shorten(190)` 的产物，
     * 主要为了"标题行不撑破"。但**展开态必须看得到完整命令**：一条 `&&` 串起来的
     * 多行脚本被截成前两行 + `…` 之后，用户根本无法核对它到底跑了什么
     * （参考实现同样是 `item.expanded ? command : truncateCommand(command)`）。
     */
    val command: String = "",
    /**
     * 这一条在折叠组副行里要显示的路径 / 模式（`ToolText.activityHint` 的产物）。
     *
     * 为什么不拿 [summary] 凑合：两者的字段回退规则是**不一样**的（`stat` 取 `path`、
     * `ls` 的默认值是 `.`），而且 `ReadMany` 在 [summary] 里没有分支（原版也没有），
     * 副行却要显示"第一个路径 · +N"。所以在登记工具时就按参考实现的
     * `toolActivityHint` 算好存下来，而不是渲染时猜。
     */
    val hint: String = "",
    /**
     * `ReadMany` 这一次读了几个文件（其余工具是 0）。
     *
     * 组表头要把 `ReadMany` 按**它实际带了几条路径**计入"读取 N 个文件"
     * （见 [ToolGrouping.label]）：算成 1 的话，一个读了 20 个文件的组会显示"读取 1 个文件"。
     */
    val readRequests: Int = 0,
)

/**
 * 一条用户消息里带的图片。
 *
 * ## 为什么 base64 直接放在界面模型里
 *
 * 会话数据库里的 image 块本来就是 `source.data` = base64（引擎写的，见
 * `ZhiCodeEngine.buildUserContent`），这里**照原样搬过来**：
 * - 不存 Uri：`content://` 的读权限只在这个 Activity 生命周期内有效，
 *   恢复历史会话时那个 Uri 早就读不了了；
 * - 不存文件路径：得先把字节落盘、再处理清理，多一份生命周期要管。
 *
 * base64 比原图大约 33%，一张 1 MB 的截图在内存里是 1.4 MB 的字符串 ——
 * 对「用户自己发过的那几张图」这个量级是可接受的，换来的是恢复历史时
 * **一定能画出来**。
 */
data class ChatImage(
    /** base64（不带 `data:` 前缀，与引擎写进会话数据库的形态一致）。 */
    val data: String,
    /** `image/png` 这类 MIME；解码只做兜底提示，不参与格式判断。 */
    val mimeType: String,
    /** 文件名，用于解码失败时回退显示与无障碍描述。 */
    val name: String,
    /**
     * 非空表示这份图**存在库里、按需取**：`data` 此时是空串。
     *
     * <p>为什么要有它：一条长会话里的图片 base64 是几十 MB 量级，读一次历史就把它
     * 全部常驻，等于把堆占掉一大块。有了位置之后，界面在真正要画那一张时才去取
     * 一次（见 `SessionReader.imageData`），滚动时只留**已经画过的那些**。
     *
     * <p>为空表示 `data` 就是全部内容（用户刚选的待发图片、工具返回的截图），
     * 那是内存里本来就有的字节，取不到也不需要取。
     */
    val origin: ImageOrigin? = null,
)

data class ChatItem(
    val id: String,
    val kind: ChatKind,
    val title: String = "",
    val body: String = "",
    val thinking: String = "",
    val thinkingExpanded: Boolean = false,
    val processSteps: List<String> = emptyList(),
    val tools: List<ToolActivity> = emptyList(),
    // ⚠️ 这里**没有**批次级的汇总标签（曾经叫 `groupLabel`：「修改 1 处代码」这类）。
    //
    // 它没有任何渲染位置了：参考实现里，一个批次里的工具分成两种长相 ——
    // 单个工具就是**扁平一行**（没有标题、没有徽章），只有"连续的 read/search 且 ≥2"
    // 才折成一组，而那一组的标题直接说明干了什么（「正在搜索 2 个模式、读取 3 个文件」，
    // 见 `ToolGrouping.label`）。留着批次级标签只会让人以为还要在哪儿画它。
    val groupCompleted: Boolean = false,
    /**
     * 已经展开的**折叠组**（值是 `ToolGrouping.Segment.Group.key`，即首成员的 toolId）。
     *
     * 为什么要单独一份，而不是像以前那样拿"组里有没有成员 `expanded`"当整组展开：
     * 成员自己的 `expanded` 是**那条工具的输出**展开（点行尾的 `⌄`），
     * 而这里是**整组展开**（点组表头）—— 两件事共用一个标志位时，
     * 点开一条工具的输出会连带把整组摊开，而且收起时也不知道该收哪一层。
     */
    val expandedGroups: Set<String> = emptySet(),
    val contextTokens: Int = -1,
    val contextWindow: Int = 0,
    val streaming: Boolean = false,
    /**
     * 这条消息带的图片。
     *
     * ⚠️ 这个字段是「图片不显示」那个 bug 的修复点之一：以前用户消息**只有** [body]，
     * 图片字节存在 ViewModel 的 `attachmentPayloads` 里，而 `send()` 一进来就
     * `clearAttachments()` 把它清空 —— 气泡那边根本没有任何地方能拿到这张图。
     * 现在图片成为消息**自己**的一部分，发送那一刻就在，恢复历史时还在。
     */
    val images: List<ChatImage> = emptyList(),
)

data class SessionSummary(
    val id: String,
    val title: String,
    val project: String,
    val messageCount: Int,
    val updatedAtLabel: String,
    val busy: Boolean = false,
    val note: String = "",
)

enum class WorkspaceTab(val label: String) {
    CHAT("对话"), TERMINAL("终端"), FILES("文件")
}

enum class PermissionMode(val label: String, val detail: String) {
    ASK("每次询问", "每个工具调用都需要你确认"),
    ACCEPT_EDITS("自动编辑", "自动允许文件编辑，其他调用仍需确认"),
    PLAN("规划", "先产出计划，批准后再执行"),
    AUTO("自动", "由 ZhiCode 判断哪些调用需要确认"),
    DONT_ASK("不询问", "不再弹出确认，高风险操作仍会提示"),
    BYPASS("跳过权限", "跳过全部权限检查，仅限受信环境"),
}

enum class EffortLevel(val label: String) {
    LOW("低"), MEDIUM("中"), HIGH("高"), MAX("最高"), AUTO("自动")
}

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

data class SlashCommand(val name: String, val hint: String)

/**
 * 附件条上的一个条目。
 *
 * 两种来源共用一个模型：
 * - 图片（`isImage = true`）：真正的字节放在 ViewModel 的载荷表里，不进不可变状态；
 *   发送时编成 base64 内容块。
 * - 文本（技能）：[textBody] 直接带内容。技能文件只有几 KB，放状态里代价可忽略，
 *   而它必须能被拼进提示词正文（见 `buildPromptWithTextAttachments`）。
 */
data class Attachment(
    val id: String,
    val label: String,
    val detail: String,
    val isImage: Boolean,
    val textBody: String? = null,
)

data class PermissionRequest(
    val id: String,
    val tool: String,
    val subtitle: String,
    val detail: String,
    val riskLevel: RiskLevel = RiskLevel.NORMAL,
)

/**
 * 计划审批的内容。对应原版"提交计划"窗口。
 *
 * [body] 是 **Markdown**，由 `ui/Markdown.kt` 渲染（标题 / 粗体 / 行内代码 / 列表 / 代码块）。
 */
data class PlanApproval(
    val id: String,
    val title: String,
    val body: String,
    val revision: Int,
    /** 计划文件绝对路径，展示在正文上方。 */
    val path: String = "",
    /** 底部的权限提示，例如「批准只确认计划；后续操作仍按你的权限模式：跳过权限」。 */
    val permissionNote: String = "",
)

data class ChoiceOption(val label: String, val detail: String = "", val checked: Boolean = false)

/**
 * 「自由复制」抽屉的内容。
 *
 * [title] 只用于抽屉顶栏（说清复制的是哪一类消息）；
 * [text] 是**原文**，与 `copyMessage` 写进剪贴板的那一份逐字相同。
 */
data class CopySheetState(
    val title: String,
    val text: String,
)

/**
 * 通用选择器的内容。对应原版"选择"窗口。
 *
 * 与旧版的区别：多了 [prompt]（弹窗顶部的加粗提问）与 [allowFreeForm]
 * （允许在选项之外写一段自由回答）。
 *
 * [multiSelect] / [submitLabel] / [cancelLabel] 是为「提问」门控加的：
 * 引擎的 `AskUserQuestion` 支持多选，并且在多问题时会分步展示，
 * 按钮文案要变成「下一步」而不是「提交」。
 *
 * ⚠️ 权限模式与推理强度**不再走这里**：它们现在是输入器里的下拉菜单
 * （见 `OverlayDropdownPreference`），选中即生效，不需要弹窗。
 */
data class ChoicePickerState(
    val title: String,
    val options: List<ChoiceOption>,
    val intent: ChoiceIntent = ChoiceIntent.GENERIC,
    val prompt: String = "",
    val allowFreeForm: Boolean = false,
    val freeFormHint: String = "其他回答…",
    val multiSelect: Boolean = false,
    val submitLabel: String = "提交",
    val cancelLabel: String = "取消",
    /**
     * 长按动作菜单要**锚**到哪一项（消息 id / 会话 id）。
     *
     * 只有 [ChoiceIntent.MESSAGE_ACTION] 与 [ChoiceIntent.SESSION_ACTION] 会写它，
     * 其余情形为 null（走居中对话框）。被长按的那一项按 id 认领它，菜单就在
     * 那一项自己的 Box 里弹出（见 `ui/Common.kt` 的 `ZhiAnchoredActionMenu`）——
     * 这样不必做任何坐标换算，也就不会因为滚动/内边距而锚偏。
     */
    val anchorId: String? = null,
) {
    /**
     * 是否按**贴住长按项的下拉菜单**渲染，而不是居中对话框。
     *
     * `anchorId` 也要求非空：万一哪天有调用点忘了传，宁可退回原来的对话框，
     * 也不能出现「菜单不显示、对话框也没有」的空洞。
     */
    val isActionMenu: Boolean
        get() = anchorId != null &&
            (intent == ChoiceIntent.MESSAGE_ACTION || intent == ChoiceIntent.SESSION_ACTION)
}

enum class ChoiceIntent {
    GENERIC,

    /**
     * 权限模式 / 推理强度。
     *
     * ⚠️ 界面上的入口已经是输入器里的**下拉菜单**（`OverlayDropdownPreference`，
     * 见 `setPermissionMode` / `setEffort`），不再走这个选择器。
     * 但 `/permissions`、`/effort` 两个斜杠命令仍然需要"弹一个列表让人挑"，
     * 所以这两条路径保留 —— 删掉就等于把斜杠命令一起废了。
     */
    PERMISSION_MODE,
    EFFORT,

    MESSAGE_ACTION,
    SESSION_ACTION,

    // ⚠️ 这里**没有** TOOL_ACTION，而且不能再加回来。
    //
    // 它曾经存在：单个工具的 `⋯` 把菜单塞进 `choicePicker`，再由界面按 anchorId 认领。
    // 但 [ChoicePickerState.isActionMenu] 只认上面这两个 intent，于是工具菜单**退化成
    // 屏幕中央的对话框** —— 一个只作用于某一行的动作，弹窗出现在屏幕正中。
    // 现在那个 `⋯` 是 Miuix 下拉菜单（与输入器底排同一组件），菜单内容仍是
    // `ToolActions.options(...)`，选中的文案直接走 `applyToolAction(itemId, toolId, label)`。
    // 也就是说：工具菜单不再需要经过本枚举，也不需要在这一层中转。

    /** 计划模式的目标澄清：选完（或自由回答）后才产出计划。 */
    PLAN_GOAL,

    /** 引擎 `AskUserQuestion` 发起的分步提问。 */
    QUESTION,

    /** 会话备注：没有选项，只靠自由输入提交（允许空串表示清除）。 */
    SESSION_NOTE,

    /**
     * 会话重命名：和 [SESSION_NOTE] 一样没有选项，只靠自由输入提交。
     *
     * 单独开一个 intent 而不是复用 [SESSION_NOTE]，是因为两者写盘时
     * `SessionReader.updateMetadata(file, note, titleOverride)` 的字段正好相反：
     * 备注只改 note（titleOverride 传空串 = 不覆盖），重命名只改 title（note 原样带回）。
     * 混用一个 intent 迟早会把标题写成备注。
     */
    SESSION_RENAME,
}

/**
 * 文件面板里的一行。
 *
 * <p>[modifiedAt] 是**修改时间**（毫秒，0 = 拿不到）。它在列表的第二行与
 * [size] 并排显示 —— 这一行原先只有一个字节数，而"最后一次改动是什么时候"
 * 是浏览代码目录时最常问的问题（小米文件管理器的列表行也是这两项）。
 * 拿不到时给 0，[FileFormat.time] 会把它显示成空串而不是编一个时间。
 */
data class FileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
)

data class OpenFile(
    val name: String,
    val path: String,
    val language: String,
    val content: String,
    /** 预览只读了前缀，不能把这份不完整正文保存回原文件。 */
    val truncated: Boolean = false,
    /** 当前正文使用的字符编码；保存时必须沿用它。 */
    val charsetName: String = "UTF-8",
)

/**
 * 文件面板的根（**两选一**）。
 *
 * ## 原先有第三个：「项目」
 *
 * 它指向设置里的**项目路径**，而项目路径默认就是 HOME —— 于是用户看到的是
 * 「项目」与「HOME」两个按钮指向**同一个目录**（截图里点了「项目」，面包屑却停在
 * `home`），纯冗余。所以按用户的要求把它删掉，只留 HOME 与共享存储。
 *
 * ⚠️ 代价说清：设置里改过**自定义项目路径**的用户，那个目录在文件面板里
 * 没有直达入口了（若它在 HOME 之下，仍可一层层点进去）。项目路径本身没有被删，
 * 它还给会话与引擎用，只是不再是文件面板的一个根。
 *
 * <p>⚠️ 共享存储那一路要**先给「所有文件访问权限」**才列得出东西，
 * 否则 `list()` 返回 null。界面据 [WorkspaceUiState.fileNote] 那条
 * 「无法读取（权限不足）」如实呈现，而不是显示"0 项"骗人。
 */
enum class FileRoot(val label: String) {
    HOME("HOME"),
    SHARED("共享存储"),
}

enum class FileSortKey(val label: String) {
    NAME("名称"), SIZE("大小"), MODIFIED("修改时间"), TYPE("类型")
}

data class FileListOptions(
    val query: String = "",
    val sortKey: FileSortKey = FileSortKey.NAME,
    val descending: Boolean = false,
)

/**
 * 「新建 / 重命名」共用的名字表单。
 *
 * <p>两者只差一件事：重命名有 [target]，新建没有。合成一个模型是为了让
 * 校验规则、错误显示、保存按钮的可用性只写一份 —— 分成两套的话，
 * 「名字里不能有 /」这类规则迟早只有一边生效。
 */
data class FileNameForm(
    val title: String,
    /** 重命名时被改的那条；新建时为 null。 */
    val target: FileEntry? = null,
    val draft: String = "",
    /**
     * **提交之后**由 `FileOps` 返回的失败原因（重名、没权限、目录不存在…）。
     *
     * <p>为什么要有这个字段：这些原因只有真去建/去改才知道，而原先它们被写进
     * `WorkspaceUiState.message` —— 那条反馈挂在 `MessageBar` 上，而 `MessageBar`
     * 是 `ChatArea` 的孩子。也就是说在**文件页上提交失败，界面上什么都不会出现**。
     * 现在它回填到表单里，弹窗保持打开、就地显示原因（小米的 `textinput_dialog`
     * 也是这个形状：输入框下面一行默认隐藏的错误行）。
     *
     * <p>改名字时会被清掉（见 `WorkspaceViewModel.updateFileNameDraft`），
     * 否则用户一改名字，上一次的"已经存在"还挂在那里。
     */
    val failure: String? = null,
) {
    /**
     * 校验结果，直接显示给用户。规则见 `FileOps.nameError`。
     *
     * <p>顺序是刻意的：先看 [failure]（那是文件系统说的），没有才现算名字规则 ——
     * 反过来的话，"重名"会被一句"名字不能为空"盖掉（用户明明填了名字）。
     */
    val error: String? get() = failure ?: FileOps.nameError(draft)

    val saveable: Boolean get() = error == null
}

/**
 * 删除确认。
 *
 * <p>[count] 是**会一起消失的条目数（含这些目标自己）**。删除一个目录会带走里面的
 * 全部内容，只说「确定删除 sub 吗？」等于没告诉用户代价。数字来自
 * `FileOps.countForDelete`（不跟符号链接进去 —— 跟进去会虚高），多选时按条累加。
 *
 * <p>目标从"一条"改成"一组"是为了长按多选（对齐小米文件管理器的选择模式）：
 * 多选态下的删除必须一次说清"删的是哪几条、一共会消失多少项"。
 */
data class FileDeletePrompt(
    val entries: List<FileEntry>,
    val count: Int,
) {
    /** 是不是"会带走别的东西"的那种删除。 */
    val destructive: Boolean get() = count > entries.size

    /**
     * 确认文案里的操作对象。
     *
     * <p>单条给名字（用户点的是它），多条给「选中的 N 项」—— 把五个名字拼进一句话
     * 会撑成三行，反而看不清删的是哪几个。
     */
    val label: String
        get() = if (entries.size == 1) "「${entries[0].name}」" else "选中的 ${entries.size} 项"
}

/**
 * 「附加项目文件」选择器的状态：**它在浏览哪一个目录**。
 *
 * ## 为什么不是一份搜索结果
 *
 * 这里原先存的是 `attachHits: List<FileHit>` —— 一坨**递归搜索**出来的扁平结果。
 * 实测它有个很难看的失效：搜索从 HOME 起递归，而 HOME 下有 Termux 的
 * `storage/{pictures,dcim,downloads,…}` 软链（`FileSearch` 只按**名字**排除
 * `build`/`node_modules` 这类构建目录，`storage` 不在名单里），空查询的深度上限（2）
 * 又刚好够到 `storage/pictures/`。于是**面板一打开**、用户一个字都还没敲，
 * 列表里就灌满了 `storage/pictures/END…` 的截图 —— 标题写着「附加项目文件」，
 * 内容却是设备相册；而且 BFS 浅层优先，照片在第 3 层、项目源码在第 6~8 层，
 * 所以它们还排在真正的代码前面。
 *
 * 改成"浏览一个目录"之后这个问题从根上没有了：**一层一层走，不递归**，
 * 那六个软链只是"可以点进去的一个目录"。
 *
 * ## 三个字段的分工
 *
 * - [attachRoot]：[FileRoot] 与文件面板同一个枚举、同一套语义；
 * - [attachPath]：当前目录的绝对路径；
 * - [attachEntries]：**这一层**的子项（`FileBrowser.children` 的结果，
 *   与文件面板 `fileEntries` 是同一个数据来源）；
 * - [attachFilter]：只过滤 [attachEntries] 的**文本**，纯内存、不碰磁盘。
 *
 * ⚠️ 过滤不做递归搜索是本设计的一部分，不是省事：那正是上面那个 bug 的成因。
 */
data class AttachBrowserState(
    /** 当前根：项目 / HOME / 共享存储。 */
    val root: FileRoot = FileRoot.HOME,
    /** 当前目录的绝对路径。 */
    val path: String = "",
    /** 当前目录的**一层**子项。 */
    val entries: List<FileEntry> = emptyList(),
    /**
     * 目录内的名字过滤串。
     *
     * 只作用于 [entries]（已列出的这一层），不会去扫磁盘 ——
     * 所以输入框每敲一个键都是纯内存操作，也**不会**顺着软链爬进相册。
     */
    val filter: String = "",
    /**
     * 目录读不出来时的原因（权限不足 / 不存在）。
     *
     * 与文件面板的 `fileNote` 同源同义：区分「真的是空目录」与「列不出来」，
     * 否则只显示"0 项"，看起来像应用坏了。
     */
    val note: String = "",
) {
    /** 按 [filter] 过滤后的可见子项。 */
    val visibleEntries: List<FileEntry> get() {
        val needle = filter.trim()
        if (needle.isEmpty()) return entries
        return entries.filter { it.name.contains(needle, ignoreCase = true) }
    }
}

data class TerminalLine(val text: String, val tone: TerminalTone = TerminalTone.NORMAL)

enum class TerminalTone { NORMAL, DIM, PROMPT, ERROR, SUCCESS }

/** Agent 任务状态。对应原版 `AgentProgressView` 里 running/completed 的分类。 */
/**
 * Agent 任务状态。
 *
 * 以前这里带一个 `glyph: String`（`✓ ◐ ○`），UI 直接把它当文字画出来。
 * 那种做法依赖字体有没有这些字符，缺字就是方块；而且颜色与粗细无法统一。
 * 现在由 UI 层按状态选 Miuix 图标 / 进度指示器，模型层只表达语义。
 */
enum class TaskState { DONE, RUNNING, PENDING }

/**
 * Agent 任务清单里的一条。[detail] 是 **Markdown**，由 `ui/Markdown.kt` 渲染
 * （任务详情窗口里支持标题/粗体/行内代码/列表/代码块）。
 */
data class AgentTask(
    val title: String,
    val detail: String = "",
    val state: TaskState = TaskState.PENDING,
)

/** 一条已排队等待执行的预输入消息。[chatItemId] 是它在对话流里那条用户气泡的 id。 */
data class QueuedPrompt(val chatItemId: String, val text: String)

data class WorkspaceUiState(
    val projectName: String = "home",
    val projectPath: String = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH,
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String = "",
    val transcript: List<ChatItem> = emptyList(),
    /**
     * 对话流上面还有没有**没读进来**的更早内容。
     *
     * 恢复历史是**分页**的（只读最近的若干条，见 `SessionReader.PAGE_MESSAGES`）：
     * 一次长会话的全部内容块是几十 MB 量级，整份读进内存再建界面模型会把应用直接
     * 撑爆。这个标志为真时，列表顶部显示一个「正在加载更早的消息…」占位，
     * 用户滚到顶就自动往前补一页。
     */
    val transcriptHasOlder: Boolean = false,
    /** 上一页的上界：本页最早一行的 seq。翻页时用它，<= 0 表示没有更早的内容。 */
    val transcriptOldestSeq: Long = -1L,
    /** 正在补更早的一页。 */
    val transcriptLoadingOlder: Boolean = false,
    /**
     * 往前补过多少页。每补一页 +1。
     *
     * 存在的理由是列表要能**区分两种"变长了"**：后面来了新消息（该吸底），
     * 还是前面补了历史（绝不能吸底 —— 那会把用户刚滚上去的内容一把甩掉）。
     * 靠"首项变了"去猜是不准的：重新打开同一条会话时首项也会变，而那次是该吸底的。
     */
    val transcriptPrependRevision: Int = 0,
    /**
     * 常驻窗口的**尾部**（最新那端）被淘汰过，最新消息已经不在内存里了。
     *
     * <p>为什么必须淘汰：恢复历史是分页的，但"往上滚"是**没有上限**的 —— 用户翻完
     * 整条会话，transcript 就把整条会话又装回内存，和分页之前一样多。真机日志上这条
     * 路径的表现是堆占用从 400MB 一路抬到 508MB/512MB（3% free），
     * 然后每次分配都要排 ~1s 的 GC 队，掉帧 899 帧、输入延迟 4.6s。
     *
     * <p>为真时列表底部给一个「回到最新」，由 [WorkspaceViewModel.jumpToLatest] 用最新
     * 一页**替换**常驻窗口 —— 替换而不是插入，所以不存在把同一条消息灌两遍的风险。
     */
    val transcriptHasNewer: Boolean = false,
    /**
     * 「回到最新」的次数。每跳一次 +1。
     *
     * <p>为什么不能只靠 [transcriptPrependRevision]：跳回最新是**替换**整份 transcript，
     * 当时的滚动位置会跟着失效，所以必须显式落底一次。而补历史那一侧的要求正好相反
     * （绝不能吸底），两者不能共用一个计数。
     */
    val transcriptJumpRevision: Int = 0,
    val tab: WorkspaceTab = WorkspaceTab.CHAT,
    val composerText: String = "",
    val composerBusy: Boolean = false,
    /**
     * 停止指令**已经发出**、但还没收到引擎的回合结束确认。
     *
     * <p>它和 [composerBusy] 不是同一件事，所以必须是两个字段：
     * `composerBusy` 是"引擎在跑"，`stopping` 是"我们在等它停"。
     *
     * <p>停止键的可见性看的是 `composerBusy || stopping`（见 Composer 的说明）：
     * 只要还在等确认，红色的停止键就**留在原地**，用户能再按一次重发中断。
     * 早先的做法是按下就 `composerBusy = false`，键当场消失，而命令可能还在跑
     * （`setsid` 子进程 + 上游超时 30 秒起、包管理器 1 小时），
     * 于是"停不下来"变成了"连按都没得按，只能清后台"。
     */
    val stopping: Boolean = false,
    /**
     * 预输入**队列**（复刻原版 `engine.steerPrompt` 的排队语义）。
     *
     * 任务运行中按发送键时：用户气泡**立刻**进对话流，内容追加到队尾，
     * [workingStatus] 改成「已预输入，等待当前回复完成…」；
     * 当前回合结束后依次执行队列里的每一条（原版 `handleQueuedPromptApplied`）。
     *
     * ⚠️ 必须是队列而不是单个槽位：连着发多条时，单槽位会把前一条覆盖掉（漏消息）。
     */
    val pendingInputs: List<QueuedPrompt> = emptyList(),
    val workingStatus: String? = null,
    /** 当前会话的 Agent 任务清单；悬浮卡只显示「当前窗口」，点开可看全部。 */
    val tasks: List<AgentTask> = emptyList(),
    /** 任务详情窗口是否打开。 */
    val taskListOpen: Boolean = false,
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val effort: EffortLevel = EffortLevel.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val profileName: String = "未配置",
    /** 当前 API 配置是否已有密钥。顶栏据此提示"还不能用"，避免发出去才发现报错。 */
    val apiKeyConfigured: Boolean = false,
    val modelLabel: String = "未设置",
    val contextTokens: Int = 0,
    val contextWindow: Int = 200_000,
    val deviceStatus: String = "",
    /** 内置 Termux 运行环境是否已安装就绪。由 [RuntimeInstaller.isInstalled] 真实探测，不再是常量。 */
    val runtimeReady: Boolean = false,
    /** 正在解压 / 配置内置 Termux 环境。 */
    val runtimeInstalling: Boolean = false,
    /** 初始化进度 0~100。 */
    val runtimeProgress: Int = 0,
    /** 初始化过程中的当前步骤文案。 */
    val runtimeMessage: String = "",
    /** 非空即「环境自检」弹窗打开。 */
    val environmentOpen: Boolean = false,
    /** 环境自检报告全文（可复制）。 */
    val environmentReport: String = "",
    val sidebarOpen: Boolean = false,
    /** 设置整页是否打开（像 miuix 示例的 SettingsPage：Scaffold+顶栏，非弹窗）。 */
    val settingsOpen: Boolean = false,
    val slashQuery: String? = null,
    val slashMatches: List<SlashCommand> = emptyList(),
    val permissionRequest: PermissionRequest? = null,
    val planApproval: PlanApproval? = null,
    val choicePicker: ChoicePickerState? = null,
    /**
     * 「自由复制」底部抽屉的内容；null 表示关着。
     *
     * ## 为什么不能只靠剪贴板
     *
     * 长按菜单里的「复制」是把**整条**文本一次性写进剪贴板。但用户真正想要
     * 往往是一段里的某几行（一条命令、一个报错、一段代码），而气泡里的文本
     * 又是不可选中的（`Text` 默认不可选，卡片还要接长按手势）。所以单开一个
     * 抽屉把**原文**摊开，让人自己划词。
     *
     * 内容在这里而不是在界面里：菜单回调只能拿到 `ChatItem`，抽屉要显示的那份文本
     * 与 [WorkspaceViewModel.copyMessage] 必须**同源**，否则会出现"复制的和看到的不一样"。
     */
    val copySheet: CopySheetState? = null,
    val attachments: List<Attachment> = emptyList(),
    val terminalLines: List<TerminalLine> = emptyList(),
    val filePath: String = "",
    val fileEntries: List<FileEntry> = emptyList(),
    /**
     * 文件列表为空时要显示的说明。
     *
     * 与 [AttachBrowserState.note] 同一个道理：目录**不存在**与目录**真的为空**
     * 在界面上都是"0 项"，但前者是故障、后者是正常。不区分的话，
     * 用户看到一个空的文件面板只会以为应用坏了。
     */
    val fileNote: String = "",
    val fileListOptions: FileListOptions = FileListOptions(),
    /** 文件面板的根：项目 / HOME / 共享存储。 */
    val fileRoot: FileRoot = FileRoot.HOME,
    /**
     * 待打开的编辑器目标（绝对路径）。
     *
     * 编辑器是独立 Activity（见 `compose/editor/EditorActivity`），所以主界面
     * **不需要**持有它的正文、脏状态或确认弹窗 —— 那些都在编辑器自己的
     * `EditorViewModel` 里。这里只留一个"请打开这个文件"的意图：
     * 界面层观察到它就去 `startActivity`，然后立刻清空（[WorkspaceViewModel.consumePendingEditorOpen]），
     * 否则重组会重复启动。
     */
    val pendingEditorOpen: String? = null,
    /** 非空即「新建 / 重命名」表单打开。 */
    val fileNameForm: FileNameForm? = null,
    /** 非空即删除确认打开。 */
    val fileDeletePrompt: FileDeletePrompt? = null,
    /**
     * 长按选中的那些条目（**以路径为键**）。
     *
     * <p>非空即「选择模式」：顶部路径行换成「已选择 N 项」+ 全选/取消全选，
     * 底部出现操作栏（重命名 / 附加到对话 / 删除），点一行变成"勾/取消勾"而不是打开。
     * 这套形状来自小米文件管理器的 action mode（它的字符串里有
     * `action_mode_select_all`=全选、`action_mode_deselect_all`=取消全选）。
     *
     * <p>⚠️ 用**路径**而不是整个 [FileEntry]：列表会因为重命名/删除重新加载，
     * 条目对象每次都是新的，存对象的话"选中"会在刷新后莫名其妙地丢掉。
     * 代价是路径本身变了（重命名）就选不中了 —— 而重命名后本来也该退出选择模式。
     *
     * <p>⚠️ 切根/换目录时必须清空（见 `switchFileRoot` / `navigateTo`）：
     * 留着的话，在 A 目录选中的东西会在 B 目录里被"删除"，而那是另一批文件。
     */
    val fileSelection: Set<String> = emptySet(),
    val fileSelectionMode: Boolean = false,
    /** 小米式文件操作暂存区：路径列表 + 是否在粘贴成功后删除源。 */
    val fileClipboard: List<String> = emptyList(),
    val fileClipboardMove: Boolean = false,
    /** 共享存储当前是否给过「所有文件访问权限」。界面据此提示怎么开。 */
    val sharedStorageGranted: Boolean = false,
    /**
     * 非空即「附加项目文件」面板打开（输入器 `+` 的第一项）。
     *
     * 面板里的一切（当前目录、这一层的子项、目录内过滤串）都在 [attachBrowser] 里 ——
     * 它是一台**浏览器**，不是一份搜索结果（为什么，见 [AttachBrowserState]）。
     *
     * ⚠️ 列目录是 IO，所以照样不能在 Composable 里现列：`FileBrowser.children` 由
     * ViewModel 在 IO 线程跑，结果落进 [attachBrowser]。
     */
    val attachPickerOpen: Boolean = false,
    /** 选择器的浏览位置与这一层的子项。 */
    val attachBrowser: AttachBrowserState = AttachBrowserState(),
    /**
     * 最后一次操作反馈（「已切换 API 配置」「保存失败：…」）。
     *
     * ⚠️ 它**必须被渲染出来**，见 `ui/MessageBar.kt`。这里记一段历史：
     * 它曾经由 Miuix Snackbar 消费，后来因为"浮层挡住底部输入器"被整个移除，
     * 于是 29 处 `copy(message = …)` 全都在往一个没人看的地方写 ——
     * 用户点了「保存」失败，界面上什么都不会发生。现在改为在悬浮输入器**上方**内联显示，
     * 既有反馈又不遮输入器。
     */
    val message: String? = null,
    /**
     * [message] 是不是一条**错误**（决定用红底还是中性的 Miuix 强调色）。
     *
     * <p>为什么不从文案里猜（比如含"失败"就当错误）：那种启发式一定会在
     * 「已重试失败的那条命令」这类正常提示上判错，而且文案改一个字就静默失效。
     * 判断必须由**知道语义的那一处**给出。
     */
    val messageIsError: Boolean = false,
    val busySessionIds: Set<String> = emptySet(),
    /** 设置页新增的持久化项（见 [AppSettings]）。 */
    val settings: AppSettings = AppSettings(),
    /** 非空即 API 配置窗口打开（列表或编辑表单）。 */
    val apiConfig: com.zhizhu.zhicode.compose.model.ApiConfigState? = null,
    val mcpConfig: com.zhizhu.zhicode.compose.model.McpConfigState? = null,
    /**
     * 非空即「搜索服务」整页打开（RikkaHub 形态：多服务列表 + 当前生效项）。
     *
     * 与 [apiConfig] 平级：两者都是「列表 + 编辑表单」的两级页面，走同一套页面栈规矩。
     */
    val searchServices: com.zhizhu.zhicode.compose.model.SearchServicesState? = null,
    val skills: com.zhizhu.zhicode.compose.model.SkillsState? = null,
    val roleCards: com.zhizhu.zhicode.compose.model.RoleCardsState? = null,
    val memory: com.zhizhu.zhicode.compose.model.MemoryState? = null,
    /**
     * 提示词设置页是否打开。
     *
     * <p>用**布尔**而不是像 [skills] 那样的载荷对象：这一页要编辑的三份文案就是
     * `settings.initPrompt` / `summaryPrompt` / `compactPromptHead` 本身，
     * 没有额外要从磁盘读的东西（与记忆页不同，那里要列文件、要知道每个文件多大）。
     * 再包一层状态对象，只会多一份要与 settings 同步的副本。
     */
    val promptsOpen: Boolean = false,
    val modelPicker: com.zhizhu.zhicode.compose.model.ModelPickerState? = null,
    /**
     * 非空即「UI 调试」整页打开（**仅 debug 构建**有入口，见设置页的「扩展」组）。
     *
     * 与 [settingsOpen] 平级而不是菜单式叠加：它是压在导航栈上的一层整页，
     * 从设置页进来时 [settingsOpen] 仍为真，于是返回会回到设置主页。
     */
    val uiDebugOpen: Boolean = false,
    /**
     * 「UI 调试」样例数据的重置令牌。
     *
     * 顶栏「重置」只改这个数字，页面用 `remember(token)` 重建样例数据 ——
     * 比把整份样例状态提到 ViewModel 里干净得多（调试数据不该进生产状态）。
     */
    val uiDebugResetToken: Int = 0,
    /**
     * **全局调试浮层**是否启用（debug 构建的「UI 调试」页里开关）。
     *
     * 打开后工作区右上角会出现仪表盘（[com.zhizhu.zhicode.compose.ui.debug.ZhiDebugHud]），
     * 实时显示面板/工具/输入器状态与**输入的 Markdown 实时预览**。
     * 与 [uiDebugOpen] 相互独立：整页是"静止地看组件"，浮层是"边用边看状态"。
     *
     * 刻意**不持久化**：它是调试设施，重启后回到关闭是符合预期的
     * （否则某次调试忘了关，下次打开会以为界面坏了）。
     */
    val debugOverlayEnabled: Boolean = false,
    /** 浮层是否展开（false = 贴边窄药丸，只显示几个计数）。 */
    val debugOverlayExpanded: Boolean = false,
    /**
     * **主体调试模式**：把应用主体本身变成调试面板（不另开一页）。
     *
     * 打开后，真实界面就地多出调试信息 —— 对话流每条消息带上类型/长度/工具计数与
     * 「Markdown 源码」开关、工具行默认全展开并显示退出码与全量输出、输入器下方
     * 实时渲染当前输入的 Markdown、面板顶部显示计数条。
     *
     * 与另外两个调试设施的分工：
     * - [uiDebugOpen]：独立的「UI 调试」整页，静止地看组件与状态；
     * - [debugOverlayEnabled]：工作区右上角的浮层仪表盘，边用边看汇总状态；
     * - 本项：**不额外占屏幕**，直接在真实控件上加料，所以最贴近"真实使用时长什么样"。
     *
     * 同样不持久化（调试设施，重启回默认）。
     */
    val debugAppMode: Boolean = false,
    /**
     * 打开主体调试模式**之前**生效的那条 API 配置 id。
     *
     * 打开时会切到「调试 · 本地模拟」这条配置；关掉时要切回来，否则用户
     * 下次正常发消息会继续走脚本化传输（而界面只显示"调试模式已关闭"），
     * 那是最难查的一类不一致。空串表示"没有可还原的配置"。
     */
    val debugPreviousProfileId: String = "",
    /** 非空即设置弹窗打开；所有编辑先落在这里，「保存」才写回上面的字段。 */
    val settingsDraft: SettingsDraft? = null,
    /**
     * "把对话流滚到底"的信号：**每次用户发出消息**就 +1。
     *
     * ## 为什么需要它
     *
     * 对话流是自动吸底的，但用户一往上翻历史就**暂停跟随**（见 `AutoFollowPolicy`），
     * 而且暂停状态住在 `ChatList` 自己的 `remember` 里 —— 界面外部没有任何入口能把它
     * 恢复成"跟随"。于是出现这么一种情形：用户往上翻看历史，直接在输入框里发一条，
     * 气泡与新回复全都落在屏幕**外**，界面上看不出"发出去了"。
     *
     * 参考实现（IQ Code `scrollChat()`）的做法就是发消息时**无条件**恢复跟随并滚到底：
     * 用户此刻的意图已经由"按下发送"表达得很清楚了。
     *
     * 用递增的计数而不是布尔：连发两条也要各触发一次；用布尔的话第二次没有"变化"，
     * 效果不会重放。
     */
    val scrollToBottomToken: Long = 0L,
    /**
     * 正在跑的**页面动作**（保存 / 创建 / 删除 / 粘贴 / 测试连接…）的键集合。
     *
     * <p>由 ViewModel 的 `runBusy` 统一登记（见 [ActionBusy]）：同一个键在飞时，
     * 第二次触发会被**静默丢弃**，而不是把同一件事做两遍 —— 双击「粘贴」复制两遍、
     * 双击「删除」弹出"删除失败"，都是这条规则缺失时的表现。
     *
     * <p>界面按 `state.isBusy("skills.save")` 禁用按钮并显示进度。空集合表示什么
     * 都不在跑，这也是绝大多数时刻的状态。
     */
    val inFlight: Set<String> = emptySet(),
    /**
     * 「设置 → 扩展」组显示的数量摘要（MCP / 技能 / 角色卡）。
     *
     * 由 ViewModel 在打开设置时于 IO 线程统计一次 —— 三个存储都在磁盘上，
     * 不能在界面里读（见 [ExtensionSummary] 的说明）。
     */
    val extensions: ExtensionSummary = ExtensionSummary(),
) {
    val activeSession: SessionSummary?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val contextFraction: Float
        get() = if (contextWindow <= 0) 0f else (contextTokens.toFloat() / contextWindow).coerceIn(0f, 1f)

    /** 某个页面动作是否在跑（见 [ActionBusy]）。 */
    fun isBusy(key: String): Boolean = key in inFlight
}

/** 与上游原版 IQ Code 一致的斜杠命令表（/help 显示，面板按前缀过滤）。 */
val SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("/help", "查看全部 ZhiCode 指令"),
    SlashCommand("/compact", "模型语义压缩；可追加摘要侧重点"),
    SlashCommand("/context", "查看或设置上下文窗口，例如 /context 1m"),
    SlashCommand("/clear", "清空当前对话并开始新会话"),
    SlashCommand("/new", "创建一个新的已保存会话"),
    SlashCommand("/resume", "恢复本地已保存的历史会话"),
    SlashCommand("/model", "切换当前模型"),
    SlashCommand("/effort", "设置推理强度"),
    SlashCommand("/permissions", "设置工具调用权限模式"),
    SlashCommand("/root", "Agent Root：on / off / check"),
    SlashCommand("/shizuku", "Agent Shizuku（无 Root 的 shell 身份）：on / off / status"),
    SlashCommand("/keepalive", "强制后台保活：on / off / check"),
    SlashCommand("/mcp", "配置和管理 MCP 服务器；/mcp list · add · enable · disable · remove <名称>"),
    SlashCommand("/web", "联网搜索设置；也可直接输入 /web 搜索词"),
    SlashCommand("/terminal", "打开内置 Termux 终端"),
    SlashCommand("/sandbox", "打开 ZhiCode 沙箱；Agent 可安装、运行和调试虚拟 APK"),
    SlashCommand("/files", "打开项目文件与代码编辑器"),
    SlashCommand("/doctor", "检查 Termux 运行环境"),
    SlashCommand("/repair", "修复中断的 apt/dpkg 状态"),
    SlashCommand("/skills", "为下一条任务附加本地 Skill；带名称直接附加，例如 /skills pdf"),
    SlashCommand("/status", "查看模型、项目、上下文、运行时和会话状态"),
    SlashCommand("/stats", "查看当前会话与运行状态"),
    SlashCommand("/usage", "查看当前上下文使用情况"),
    SlashCommand("/copy", "复制最近一条 ZhiCode 回复"),
    SlashCommand("/plan", "进入计划模式；/plan off 退出"),
    SlashCommand("/config", "打开 ZhiCode 设置"),
    SlashCommand("/memory", "打开项目或用户 ZhiCode.md 记忆"),
    SlashCommand("/init", "让 ZhiCode 初始化或完善项目 ZhiCode.md"),
    SlashCommand("/tasks", "查看 Android Agent 的任务与会话文件"),
    SlashCommand("/agents", "管理内置、项目、用户和正在运行的子 Agent"),
    SlashCommand("/cancel", "停止当前正在执行的 Agent 回合"),
    SlashCommand("/runtime", "打开内置 Termux 运行环境控制"),
)
