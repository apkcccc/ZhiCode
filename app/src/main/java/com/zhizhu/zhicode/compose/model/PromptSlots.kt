package com.zhizhu.zhicode.compose.model

/**
 * 可自定义的提示词槽位（形态参考 rikkahub 的「提示词」设置页）。
 *
 * ## 为什么只有这三个
 *
 * 本工程里"提示词"分散在四处，但只有这三处**该给用户改**：
 *
 * | 槽位 | 现状 | 为什么可以改 |
 * | --- | --- | --- |
 * | `/init` 指令 | 原先硬编码在 `WorkspaceViewModel` 的 `private const` | 它是"让模型整理项目说明"的一句话要求，写法因项目而异（有的项目只有 README） |
 * | 上下文压缩的**系统提示** | 原先硬编码在 `ZhiCodeEngine` | 它只影响摘要质量，不参与主对话，改坏了也不会影响权限与安全 |
 * | 上下文压缩的**开场段** | 原先硬编码在 `ContextCompactor` 的 `PROMPT_HEAD` | 同上；压缩结果会进上下文，用户有权决定摘要"按什么口径写" |
 *
 * **不在**这里的：主系统提示（`SystemPromptBuilder`）与权限/安全相关的任何句子。
 * 那些是行为契约 —— 允许用户改它们等于允许用户关掉「不许声称做了没做的事」，
 * 而这条恰恰是这个工具最不能松的一条。自定义诉求由"附加指令"
 * （`custom_system_prompt`，优先级最高但不能覆盖安全边界）满足。
 *
 * ## 空串 = 用默认
 *
 * 三个槽位在 `SessionConfig` 里都以**空串表示"没改过"**，而不是在设置里预填一份默认文案。
 * 理由有两层：
 * 1. 默认值将来会随版本改进。预填之后，老用户的库里就固化了一份旧文案，
 *    永远拿不到改进 —— 这与「设置没生效」是同一类伤害，只是更隐蔽。
 * 2. 空串让"恢复默认"变成一个**赋值**而不是"记得把默认文案抄回来"。
 *
 * 所以 [defaultValue] 是**唯一**的默认来源，引擎侧的常量全部指向它（或由它派生）。
 */
enum class PromptSlot(
    /** 落盘用的稳定 id。改名 = 丢掉用户已填的内容，所以它不随展示名变。 */
    val id: String,
    val title: String,
    /** 这个槽位的提示词里可以写什么、会被用在哪里。 */
    val detail: String,
    /** 这个槽位能用的变量（写在这里是为了让编辑页能原样展示）。 */
    val variables: String,
    val defaultValue: String,
) {
    INIT(
        id = "init",
        title = "/init 项目初始化指令",
        detail = "点「让 ZhiCode 完善」或输入 /init 时，作为这一轮的指令发给模型。",
        variables = "无变量。默认文案刻意限制它只读 README 与构建清单，不做全仓调研 —— " +
            "改宽之后 /init 会变成一个普通任务，耗时也长得多。",
        defaultValue = "This is the built-in fast /init maintenance command. Do not enter plan mode, " +
            "create tasks, launch subagents, or perform broad repository research. Read only the existing " +
            "ZhiCode.md/CLAUDE.md/README and primary build manifest or script when present, then directly " +
            "create or improve ZhiCode.md with concise build, test, architecture, conventions, and " +
            "repository-specific instructions. Preserve useful existing instructions and run at most " +
            "one small verification command.",
    ),

    COMPACT_SYSTEM(
        id = "compact_system",
        title = "压缩摘要 · 系统提示",
        detail = "压缩上下文时单独发给模型的一句系统提示。它不参与主对话，只影响摘要质量。",
        variables = "无变量。默认文案里「Respond with text only and never call tools」是必须的：" +
            "摘要请求一旦真的调了工具，返回的就不是摘要而是一段工具调用，压缩会直接失败。",
        defaultValue = "You are ZhiCode's context compactor. Produce a faithful continuation summary. " +
            "Respond with text only and never call tools.",
    ),

    COMPACT_HEAD(
        id = "compact_head",
        title = "压缩摘要 · 开场段",
        detail = "压缩请求正文的开头一段。它后面还会自动接上「保留近期 / 替换全部」的说明、" +
            "固定的九个小节标题，以及你在 /compact 里写的侧重点。",
        variables = "无变量。这一段负责说清「只输出文字、不许调工具、格式是 analysis + summary」。",
        defaultValue = "CRITICAL: Respond with TEXT ONLY. Do not call any tool. Tool calls will be rejected.\n" +
            "Your entire response must contain an <analysis> drafting block followed by one <summary> block.\n\n" +
            "Create a detailed, technically accurate continuity summary of the conversation prefix above. ",
    ),
    ;

    /** 用户填的值；空串表示沿用 [defaultValue]。 */
    fun resolve(customized: String?): String {
        val value = customized?.trim().orEmpty()
        return if (value.isEmpty()) defaultValue else value
    }

    companion object {
        fun byId(id: String): PromptSlot? = entries.firstOrNull { it.id == id }

        /**
         * 一行预览：提示词里换行很多，压成一行才看得出实际内容（rikkahub 同款做法）。
         *
         * 截断放在这里而不是界面里：界面各自截断就会出现"有的地方 60 字、有的地方 80 字"，
         * 而这一行的高度是固定的（超过一行会把行尾箭头挤上去）。
         */
        fun preview(value: String, maxChars: Int = 80): String {
            val flat = value.replace(Regex("\\s+"), " ").trim()
            if (flat.isEmpty()) return "（空）"
            return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "…"
        }
    }
}
