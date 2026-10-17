package com.zhizhu.zhicode.compose.model

import com.termux.app.zhicode.storage.ApiSettingsStore

/**
 * 设置页的 6 个分类，对齐原版 `MainActivity.showSettings()` 的分类 Tab。
 *
 * 顺序即 Tab 顺序，`entries` 直接拿来渲染，不要在 UI 里另写一份列表。
 */
enum class SettingsCategory(val label: String, val tabLabel: String) {
    APPEARANCE("外观", "外观"),
    MODEL_PERMISSION("模型与权限", "模型"),
    AGENT_SECURITY("Agent 与安全", "安全"),
    NETWORK("联网", "联网"),
    CONTEXT_PROJECT("上下文与项目", "上下文"),
    EXTENSIONS("扩展功能", "扩展"),
}

/**
 * 搜索服务（形态参考 RikkaHub 的「搜索服务」页：免费后端与密钥制后端并列，
 * 选中哪个哪个生效；密钥按服务各存一份，切换服务不丢）。
 *
 * [needsKey]/[needsBaseUrl] 决定设置页在选中该项时追加哪些输入框：
 * 密钥制服务缺 Key 时搜索会明确报错（提示去哪里配），而不是静默回落免费后端 ——
 * 用户配了 Tavily 却拿到 DuckDuckGo 的结果才是真正的坑。
 */
/**
 * 搜索服务配置里的字段名（**持久化契约**：改一个名字等于丢掉用户已填的配置）。
 *
 * <p>刻意放在**顶层**而不是 [SearchServiceType] 的 companion 里：枚举项的构造参数
 * 在 companion 初始化**之前**求值，`SearchFieldName.API_KEY` 在那个位置会编译失败
 * （"Companion object ... is uninitialized here"）。这不是风格问题，是语言规则。
 */
object SearchFieldName {
    const val API_KEY = "apiKey"
    const val BASE_URL = "baseUrl"
    const val DEPTH = "depth"
    const val TOPIC = "topic"
    const val MODEL = "model"
    const val LANGUAGE = "language"
    const val SUMMARY = "summary"
    const val URL_TEMPLATE = "urlTemplate"
    const val HEADERS = "headers"
    const val JSON_ITEMS_PATH = "itemsPath"
    const val JSON_TITLE_PATH = "titlePath"
    const val JSON_URL_PATH = "urlPath"
    const val JSON_TEXT_PATH = "textPath"
}

/**
 * 搜索服务**类型**目录（形态参考 RikkaHub 的「搜索服务」页）。
 *
 * ## 为什么从「一个枚举」改成「类型 + 多实例」
 *
 * 上一版是一个 `enum WebSearchProvider` 下拉：只能配一个、只有 Key 与实例地址两种
 * 字段。RikkaHub 的形态是**列表**：可以同时配多个服务、每个服务有自己的 Key 与
 * 专属选项（depth / topic / 语言 / 安全搜索 / 结果数），点其中一个设为当前使用。
 * 这一版对齐它。
 *
 * ## 字段声明放在枚举里，而不是散在 UI 里
 *
 * [fields] 描述「这个类型需要哪些输入框」。设置页照着它渲染，新增一个服务类型
 * 只需要在这里加一项 + 在 [WebSearchJson]/`WebSearchTool` 加一个解析/请求分支，
 * 不用碰 UI 代码 —— 否则每加一家都要在 UI 里再写一段 if。
 *
 * [keyUrl] 是「去哪里申请 Key」。RikkaHub 每个服务都带这个指引，
 * 值得照做：没有它，用户看到一个空 Key 框只能自己去找文档。
 */
enum class SearchServiceType(
    val label: String,
    val detail: String,
    /** 申请 Key 的页面；免密钥服务为 null。 */
    val keyUrl: String? = null,
    /** 这个类型需要的输入字段（除「名称」之外）。 */
    val fields: List<SearchField> = emptyList(),
) {
    DUCKDUCKGO("DuckDuckGo", "免费公开入口，无需密钥；偶尔会限流"),
    BING("Bing RSS", "免费走 Bing 的 RSS 结果，无需密钥"),
    SEARXNG(
        "SearXNG",
        "自建元搜索，实例需开启 JSON 输出（settings.yml 里 format=json）",
        fields = listOf(
            SearchField(SearchFieldName.BASE_URL, "实例地址", "如 https://searx.example.com", required = true),
            SearchField(SearchFieldName.LANGUAGE, "语言", "如 zh-CN / en（留空为实例默认）"),
        ),
    ),
    TAVILY(
        "Tavily",
        "为 LLM 优化的 AI 搜索，支持检索深度与主题",
        keyUrl = "https://tavily.com",
        fields = listOf(
            SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true),
            SearchField(SearchFieldName.DEPTH, "检索深度", "basic / advanced（advanced 更慢更贵但更全）", options = listOf("basic", "advanced")),
            SearchField(SearchFieldName.TOPIC, "主题", "general / news / finance", options = listOf("general", "news", "finance")),
        ),
    ),
    EXA(
        "Exa",
        "面向 AI 的语义搜索",
        keyUrl = "https://exa.ai",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),
    BRAVE(
        "Brave",
        "Brave Search API，独立索引",
        keyUrl = "https://brave.com/search/api/",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),
    PERPLEXITY(
        "Perplexity",
        "搜索增强模型，能直接给出带引用的回答",
        keyUrl = "https://www.perplexity.ai/settings/api",
        fields = listOf(
            SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true),
            SearchField(SearchFieldName.MODEL, "模型", "如 sonar / sonar-pro（留空用 sonar）"),
        ),
    ),
    LINKUP(
        "LinkUp",
        "可配检索深度的搜索 API",
        keyUrl = "https://www.linkup.so",
        fields = listOf(
            SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true),
            SearchField(SearchFieldName.DEPTH, "检索深度", "standard / deep", options = listOf("standard", "deep")),
        ),
    ),
    JINA(
        "Jina",
        "Jina Reader 的检索接口（s.jina.ai）",
        keyUrl = "https://jina.ai/reader/",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),
    FIRECRAWL(
        "Firecrawl",
        "抓取型服务，返回结构化正文",
        keyUrl = "https://firecrawl.dev",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),
    BOCHA(
        "博查 Bocha",
        "中文搜索服务，可选 AI 摘要",
        keyUrl = "https://open.bochaai.com",
        fields = listOf(
            SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true),
            SearchField(SearchFieldName.SUMMARY, "AI 摘要", "true / false", options = listOf("false", "true")),
        ),
    ),
    METASO(
        "秘塔 Metaso",
        "中文 AI 搜索",
        keyUrl = "https://metaso.cn",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),
    ZHIPU(
        "智谱 Zhipu",
        "智谱的联网搜索工具",
        keyUrl = "https://open.bigmodel.cn",
        fields = listOf(SearchField(SearchFieldName.API_KEY, "API Key", required = true, secret = true)),
    ),

    /**
     * 自定义 HTTP 检索。
     *
     * <p>RikkaHub 在这个位置是「Custom JS」（在沙箱里跑一段 JS）。本工程刻意**不新增
     * 依赖**，而 Android 上没有内置 JS 引擎（{@code javax.script} 不存在，
     * Rhino/Duktape 都是新依赖），所以换成不写代码的等价物：给一个 URL 模板
     * （`%s` 处填查询词）+ 结果数组的 JSON 路径。覆盖绝大多数自建/内部检索 API。
     */
    CUSTOM_HTTP(
        "自定义 HTTP",
        "自己填检索地址与结果字段名；%s 处会被替换成查询词",
        fields = listOf(
            SearchField(SearchFieldName.URL_TEMPLATE, "检索地址模板", "如 https://my.api/search?q=%s&n=%d", required = true),
            SearchField(SearchFieldName.HEADERS, "请求头 JSON", "如 {\"Authorization\":\"Bearer xxx\"}"),
            SearchField(SearchFieldName.JSON_ITEMS_PATH, "结果数组路径", "如 data.items（点号分隔；留空视为根数组）"),
            SearchField(SearchFieldName.JSON_TITLE_PATH, "标题字段", "默认 title"),
            SearchField(SearchFieldName.JSON_URL_PATH, "链接字段", "默认 url"),
            SearchField(SearchFieldName.JSON_TEXT_PATH, "摘要字段", "默认 text / content / snippet 依次尝试"),
        ),
    ),
    ;

    /** 是否需要密钥（决定编辑页要不要渲染 Key 输入框）。 */
    val needsKey: Boolean get() = fields.any { it.name == SearchFieldName.API_KEY && it.required }

}

/** 搜索服务的一个输入字段。见 [SearchServiceType.fields]。 */
data class SearchField(
    val name: String,
    val label: String,
    val hint: String = "",
    val required: Boolean = false,
    /** 非空即「只能从这几个值里选」，界面用下拉而不是文本框。 */
    val options: List<String> = emptyList(),
    /** 敏感值（Key）：走加密存储，读回时**不回填**到表单。 */
    val secret: Boolean = false,
)

/**
 * 一条搜索服务配置（RikkaHub 的「已添加的服务」一条）。
 *
 * [config] 存非敏感字段（实例地址 / 深度 / 主题…），密钥单独走加密槽、不在这里，
 * 与 API 配置的密钥同一套规矩。
 */
data class SearchService(
    val id: String,
    val name: String,
    val type: SearchServiceType,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
) {
    /** 列表第二行：类型名 + 关键配置摘要。 */
    val subtitle: String
        get() = buildString {
            append(type.label)
            config[SearchFieldName.BASE_URL]?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            config[SearchFieldName.MODEL]?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
}

/** 搜索服务编辑表单草稿。 */
data class SearchServiceDraft(
    val id: String?,
    val name: String,
    val type: SearchServiceType,
    val enabled: Boolean,
    val config: Map<String, String>,
    /** 用户这次输入的密钥；编辑既有记录时留空 = 沿用原密钥。 */
    val apiKey: String = "",
) {
    val isEditing: Boolean get() = !id.isNullOrBlank()

    val nameError: String? get() = if (name.isBlank()) "请填写名称（用于在列表里区分）" else null

    /** 缺必填字段时要指出**是哪一项**，而不是只说「配置有误」。 */
    val fieldError: String? get() = type.fields
        .firstOrNull { it.required && it.name != SearchFieldName.API_KEY && config[it.name].isNullOrBlank() }
        ?.let { "请填写${it.label}" }

    val keyError: String?
        get() = if (type.needsKey && !isEditing && apiKey.isBlank()) "请填写 ${type.label} 的 API Key" else null

    val saveable: Boolean get() = nameError == null && fieldError == null && keyError == null

    companion object {
        fun from(service: SearchService) = SearchServiceDraft(
            id = service.id,
            name = service.name,
            type = service.type,
            enabled = service.enabled,
            config = service.config,
            // 密钥**不**回填：界面上不该出现已保存的密钥（与 API 配置同一条规矩）。
            apiKey = "",
        )

        fun blank() = SearchServiceDraft(
            id = null,
            name = "",
            type = SearchServiceType.TAVILY,
            enabled = true,
            config = emptyMap(),
        )
    }
}

/** 搜索服务页的状态。[form] 非空即编辑页，否则列表页。 */
data class SearchServicesState(
    val services: List<SearchService> = emptyList(),
    val activeId: String = "",
    val form: SearchServiceDraft? = null,
    /**
     * 载荷还没读完。见 `WorkspaceViewModel.openSearchServices`。
     *
     * 页面**先**推入（同步、在这一帧内），数据**后**读（IO 线程）。
     * 这段时间里 `services` 是空的 —— 而「一个服务都没有」本身是个合法状态，
     * 页面本来就得能画出来。所以 `loading` 的职责只有一个：别让用户把
     * 「正在读」误读成「配置丢了」。
     */
    val loading: Boolean = false,
)

/**
 * 联网搜索后端（历史类型）。
 *
 * @deprecated 已被 [SearchService] 取代：它只能配一个服务、只有 Key 与实例地址两种
 * 字段，而 RikkaHub 的形态是「多服务列表 + 每服务专属选项」。保留只是因为
 * [AppSettings.webSearchProvider] 这个**持久化键**还在被读取 ——
 * 老用户的配置会在首次启动时迁移成一条 [SearchService]。
 */
enum class WebSearchProvider(
    val label: String,
    val detail: String,
    val needsKey: Boolean = false,
    val needsBaseUrl: Boolean = false,
) {
    AUTO("自动（免费）", "按顺序尝试 DuckDuckGo → Bing"),
    DUCKDUCKGO("DuckDuckGo", "免费，无需密钥"),
    BING("Bing RSS", "免费，无需密钥"),
    TAVILY("Tavily", "为 LLM 优化的 AI 搜索，Key 从 tavily.com 获取", needsKey = true),
    EXA("Exa", "面向 AI 的语义搜索，Key 从 exa.ai 获取", needsKey = true),
    BRAVE("Brave", "Brave Search API，Key 从 brave.com/search/api 获取", needsKey = true),
    SEARXNG("SearXNG", "自建元搜索，填实例地址（需开 JSON 输出）", needsBaseUrl = true),
}

/** API 协议。对应参考图表单里「协议」值框的 `OpenAI Responses`。 */
enum class ApiProtocol(val label: String) {
    OPENAI_RESPONSES("OpenAI Responses"),
    OPENAI_CHAT("OpenAI Chat"),
    ANTHROPIC("Anthropic"),

    /**
     * Codex 变体（参考图表单里 `OpenAI Responses` 之后那一项）。
     *
     * 它**不是** `OPENAI_RESPONSES` 的别名，虽然共用同一个传输实现：
     * 端点、UA 与四个关联头都不一样（`/responses` 而非 `/v1/responses`、
     * 自称 `codex_cli_rs/<ver>`、外加 `originator`/`session-id`/`thread-id`/
     * `x-client-request-id`），请求体也不一样（必须 `store:false`，
     * 且**不能**发 `max_output_tokens`）。详见 `OpenAIResponsesProvider`。
     */
    CODEX_RESPONSES("Codex Responses"),

    /**
     * ZCode（Z.ai 的套餐网关）。
     *
     * 报文形状与 [ANTHROPIC] 相同，差别在连接与请求头：网关地址、授权码、
     * 以及它要求的**额外身份头**都由用户自己填（见 [ApiProfileDraft.extraHeaders]）。
     * 我们不内置它的地址，也不内置它的客户端标识 —— 理由见 `ZcodeWire` 的类注释。
     */
    ZCODE("ZCode"),

    /**
     * 调试用：不发网络请求，按脚本产出回复（含真实的工具调用）。
     *
     * 只在 debug 构建里出现在协议下拉与「新增配置」的候选里（见
     * [com.zhizhu.zhicode.compose.ui.dialogs.ApiConfigOverlay]）；发布包即使读到
     * 这样一条记录，引擎侧也会明确拒绝（见 `ModelProviders.debugScripted`）。
     */
    DEBUG_SCRIPTED("调试 · 本地模拟（无网络）"),
    ;

    /** 是否只在 debug 构建里可选。 */
    val debugOnly: Boolean get() = this == DEBUG_SCRIPTED
}

/**
 * 一条 API 配置记录。
 *
 * [apiKey] 会被保存，但按原版约定**不回填到编辑表单**（避免密钥在界面上暴露）；
 * 见 [ApiProfileDraft.from]。
 */
data class ApiProfile(
    val id: String,
    val name: String,
    val protocol: ApiProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 额外请求头（JSON 对象文本），空串表示不加。见 [ApiProfileDraft.extraHeaders]。 */
    val extraHeaders: String = "",
) {
    /** 参考图卡片第二行的 `协议名 · 模型名` 形式。 */
    val summary: String get() = "${protocol.label} · ${model.ifBlank { "未设置模型" }}"
}

/** 新增 / 编辑表单的草稿。 */
data class ApiProfileDraft(
    val id: String?,
    val name: String = "",
    val protocol: ApiProtocol = ApiProtocol.OPENAI_RESPONSES,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /**
     * 额外请求头（JSON 对象文本），空串表示不加。
     *
     * 目前只有 [ApiProtocol.ZCODE] 用得上：那个网关要求特定的身份头才受理。
     * 做成用户填而不是内置，是因为那些头的取值属于那个服务的客户端标识，
     * 写死在代码里等于替用户宣称一个身份，而且对方一改所有人都一起断。
     *
     * 只有 ZCode 协议下才显示这个输入框（见 `ApiConfigOverlay`）——
     * 对别的协议显示一个"额外请求头"框，只会让人以为那是必需项。
     */
    val extraHeaders: String = "",
    /** 编辑已有记录时为真：密钥框留空表示"沿用原密钥"。 */
    val isEditing: Boolean = false,
) {
    val nameError: String? get() = if (name.isBlank()) "请填写名称" else null
    val baseUrlError: String? get() = if (baseUrl.isBlank()) "请填写 API Base URL" else null
    val saveable: Boolean get() = nameError == null && baseUrlError == null

    companion object {
        fun blank(): ApiProfileDraft = ApiProfileDraft(id = null)

        /** 编辑已有记录：**故意不回填 apiKey**。 */
        fun from(profile: ApiProfile): ApiProfileDraft = ApiProfileDraft(
            id = profile.id,
            name = profile.name,
            protocol = profile.protocol,
            baseUrl = profile.baseUrl,
            apiKey = "",
            model = profile.model,
            extraHeaders = profile.extraHeaders,
            isEditing = true,
        )
    }
}

/**
 * API 配置页的状态。
 *
 * [form] 非空即为「新增 / 编辑」表单页，否则显示列表页——两张页面共用一个弹窗，
 * 对应参考图的两张截图。
 */
data class ApiConfigState(
    val profiles: List<ApiProfile>,
    val activeId: String,
    val form: ApiProfileDraft? = null,
    /** 载荷还没读完，见 `WorkspaceViewModel.openApiConfig`。 */
    val loading: Boolean = false,
)

/** 模型目录里的一项。[displayName] 可能与 [id] 相同，界面据此决定要不要重复显示。 */
data class ModelOption(val id: String, val displayName: String)

/**
 * 模型选择面板里的一行套餐额度。
 *
 * 字段都是**显示用的文本**（`remaining`/`total`/`resetLabel` 已格式化）：这样界面层
 * 不需要认识单位换算与倒计时分档，那些规则只有一处实现（见 `ZcodeWire`）。
 */
data class QuotaRow(
    val name: String,
    val remaining: String,
    val total: String,
    /** 剩余比例 0..1，用于进度条。 */
    val fraction: Float,
    val resetLabel: String,
)

/**
 * 「测试连接」的结果：一台服务器 + 它提供的工具。
 *
 * 与 [McpServer]（配置）分开：配置是**用户写的**，它是**服务器答的**。
 * 合成一个类型的话，没测过的服务器就没有工具清单，于是要么用空列表冒充
 * "这台服务器没有工具"，要么到处都是可空字段。
 */
data class McpServerStatus(
    val name: String,
    val connected: Boolean,
    /** 失败原因（`connected` 为 true 时是空串）。 */
    val error: String,
    val tools: List<McpToolInfo> = emptyList(),
)

/**
 * 服务器提供的一个工具。
 *
 * [enabled] / [approval] 是**用户设置**（存在 mcp.json 的 `tools` 里），
 * 与服务器返回的描述混在一起传上来：界面上一行里要同时显示"这是什么工具"
 * 与两个开关，分两次查会让这一行的状态有两个来源。
 */
data class McpToolInfo(
    val name: String,
    val description: String,
    /** JSON Schema 里的属性名，用来提示这个工具要哪些参数。 */
    val parameters: List<String> = emptyList(),
    /** 需要参数的子集（渲染时用不同颜色标出来）。 */
    val required: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val approval: Boolean = false,
) {
    companion object {
        /** 从引擎 `listServers()` 里的一项解析。字段缺失一律用安全的缺省值。 */
        fun parse(tools: org.json.JSONArray?): List<McpToolInfo> = buildList {
            if (tools == null) return@buildList
            for (index in 0 until tools.length()) {
                val tool = tools.optJSONObject(index) ?: continue
                val name = tool.optString("name", "")
                if (name.isEmpty()) continue
                val schema = tool.optJSONObject("inputSchema")
                val properties = schema?.optJSONObject("properties")
                val required = schema?.optJSONArray("required")
                add(
                    McpToolInfo(
                        name = name,
                        description = tool.optString("description", ""),
                        parameters = properties?.keys()?.asSequence()?.toList().orEmpty(),
                        required = buildSet {
                            if (required == null) return@buildSet
                            for (i in 0 until required.length()) {
                                required.optString(i, "").takeIf { it.isNotEmpty() }?.let { add(it) }
                            }
                        },
                        enabled = tool.optBoolean("enabled", true),
                        approval = tool.optBoolean("approval", false),
                    ),
                )
            }
        }
    }
}

/** 从 JSON 导入的结果。[added] 真正加进去的，[skipped] 同名跳过的，[invalid] 认不出的。 */
data class McpImportResult(
    val added: List<String>,
    val skipped: List<String>,
    val invalid: List<String>,
)

/** MCP 配置页状态：与 API 配置同样共用一个弹窗，`form` 非空即为表单页。 */
data class McpConfigState(
    val servers: List<McpServer>,
    val filePath: String,
    val form: McpServerDraft? = null,
    /**
     * 「测试连接」的结果，按服务器名索引。
     *
     * 只在用户点过测试之后才有内容 —— 引擎侧的测试会**真的起子进程/发 HTTP**，
     * 不能在打开页面时自动跑（见 `ZhiEngineController.mcpStatus`）。
     */
    val status: Map<String, McpServerStatus> = emptyMap(),
    /** 正在测试的服务器名（空集表示没有在测）。 */
    val testing: Set<String> = emptySet(),
    /** 「从 JSON 导入」对话框的文本。`null` = 对话框没开。 */
    val importText: String? = null,
    /** 导入对话框的错误提示（解析失败时才有）。 */
    val importError: String? = null,
    /** 载荷还没读完，见 `WorkspaceViewModel.openMcpConfig`。 */
    val loading: Boolean = false,
    /**
     * 列表页的筛选词（名称 / 命令 / 地址都参与匹配）。
     *
     * 与技能页同一个理由：服务器攒到十几台之后，找某一台要看半天。
     * 只在列表页有意义，不参与持久化。
     */
    val query: String = "",
) {
    /** 按 [query] 过滤后的服务器。 */
    val visibleServers: List<McpServer>
        get() {
            if (query.isBlank()) return servers
            return servers.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.command.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true) ||
                    it.type.label.contains(query, ignoreCase = true)
            }
        }

    /** 启用中的台数（列表页顶部汇总用）。 */
    val enabledCount: Int get() = servers.count { it.enabled }
}

/** Skill 的作用域。目录约定必须与引擎 `SkillTool` 一致，见 `SkillStore`。 */
enum class SkillScope(val label: String) {
    PROJECT("项目级"),
    USER("用户级"),
}

/** 列表里的一条技能。 */
data class SkillEntry(
    val name: String,
    val scope: SkillScope,
    val path: String,
    val summary: String,
    val sizeLabel: String,
)

/**
 * 技能目录里的一个文件。
 *
 * 技能不只有 `SKILL.md`：参考文档、脚本、示例都可以放在同一目录里一起分发
 * （`SkillStore.delete` 用递归删除就是为此）。详情页把它们列出来。
 *
 * [relativePath] 是**相对技能目录**的路径（`SKILL.md`、`reference/api.md`），
 * 读写都用它当唯一标识 —— 只用 [name] 的话子目录里两个 `api.md` 会互相覆盖。
 */
data class SkillFile(
    val name: String,
    val relativePath: String,
    val sizeLabel: String,
    /** 是否是技能本体（`SKILL.md`）。列表里排最前，并用不同颜色标出来。 */
    val primary: Boolean,
)

/**
 * 技能目录的**文件树**（详情页渲染用）。
 *
 * 参考实现的详情页就是递归树：技能可以带子目录，平铺一层会把 `reference/api.md`
 * 显示成 `api.md`，用户看不出它在哪一层。
 * ⚠️ 递归是这里的**核心能力**，不是排版细节 —— 退化成平铺就等于丢掉了子目录。
 */
sealed interface SkillFileNode {
    /** 一个文件。 */
    data class FileNode(val file: SkillFile) : SkillFileNode

    /** 一个子目录，[children] 是它下面的内容（递归）。 */
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode
}

/**
 * Skill 管理面板的状态。
 *
 * 页面栈只有**两层**（列表 → 详情），靠下面这些字段区分额外形态：
 * - `detail != null` → 技能详情（该技能目录下的文件树）
 * - `detail == null && createForm != null` → 手动添加（对话框）
 * - `editing != null` → 编辑某个文件（对话框）
 * - `fileDraft != null` → 新建文件（对话框）
 * - `urlDraft != null` → 从 URL 导入（对话框）
 * - 都为 null → 技能列表
 *
 * 用多个字段而不是一个 `mode` 枚举：各态需要携带的数据完全不同，
 * 塞进一个 sealed 层级只会让调用方多写一层 when。
 */
data class SkillsState(
    val skills: List<SkillEntry>,
    /** 列表页的筛选词。技能会越攒越多，找起来需要一个入口。 */
    val query: String = "",
    /** 正在查看的技能（详情页）。 */
    val detail: SkillDetail? = null,
    val createForm: SkillCreateDraft? = null,
    val editing: SkillEditTarget? = null,
    /** 「新建文件」表单，只在详情页里有意义。 */
    val fileDraft: SkillFileDraft? = null,
    /** 「从 URL 导入」表单。 */
    val urlDraft: SkillUrlDraft? = null,
    /** 载荷还没读完，见 `WorkspaceViewModel.openSkills`。 */
    val loading: Boolean = false,
) {
    /** 按 [query] 过滤后的列表。名称与说明都命中。 */
    val visibleSkills: List<SkillEntry>
        get() = if (query.isBlank()) skills
        else skills.filter {
            it.name.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true)
        }
}

/** 技能详情页的数据：技能本身 + 它目录下的文件树。 */
data class SkillDetail(
    val entry: SkillEntry,
    val tree: List<SkillFileNode>,
    /**
     * `SKILL.md` 的正文（打开详情时读好的那份）。
     *
     * 详情页此前只有文件树：想知道这个技能到底教 Agent 做什么，得先点进编辑器。
     * 那一步在手机上要跨一次页面栈、还要等文件读盘，回看一份"我到底装了什么"很别扭。
     * 正文放这里只用于**只读预览**（可复制），编辑仍然走编辑器。
     *
     * 读失败时是空串 —— 界面据此显示"读不到正文"而不是一片空白。
     */
    val body: String = "",
)

/**
 * 「手动添加技能」的表单（**对话框**，不是整页）。
 *
 * 形状照参考实现：**一个「SKILL.md 内容」输入框 + 取消/保存**，没有独立的名字字段。
 * 内容就是 `SKILL.md` 本体，技能名（也就是目录名）由内容的 frontmatter 解析得出 ——
 * 用户手上拿到的本来就是一份完整的 SKILL.md，让它自己声明名字，
 * 目录名就不会和内容里的 `name` 不一致。
 *
 * 以前这一项是**单独一整页**、还要再开一层编辑器，加一个技能要跨两级页面；
 * 做成对话框之后一步到位。
 *
 * [name] 为空且 [content] 非空 = 「有内容但解析不出名字」，这是**错误态**：
 * 必须报出来并挡住提交，不能猜一个默认名（会建出用户没打算建的目录）。
 */
data class SkillCreateDraft(
    val content: String = "",
    val scope: SkillScope = SkillScope.PROJECT,
    /** 由 `SkillStore.nameFromContent` 从 [content] 的 frontmatter 解析得出。 */
    val name: String = "",
) {
    /** 内容非空但解析不出名字。空内容不算错（用户还没开始粘贴）。 */
    val nameMissing: Boolean get() = content.isNotBlank() && name.isBlank()

    /** 解析出来了、但字符集不合法（见 `SkillStore.isValidName`）。 */
    val nameInvalid: Boolean get() = name.isNotBlank() && !Regex("[A-Za-z0-9._-]{1,64}").matches(name)

    val saveable: Boolean get() = name.isNotBlank() && !nameInvalid
}

/**
 * 「新建文件」表单（详情页里往技能目录加一个附加文件）。
 *
 * 名字允许带子目录（`reference/api.md`）：技能本来就支持分组，界面不给出这个入口的话，
 * 树只能显示别处（导入、Agent）建出来的目录，用户在界面上永远造不出同样的结构。
 * 每一段仍然要过同一套字符集规则。
 */
data class SkillFileDraft(
    val fileName: String = "",
    val content: String = "",
) {
    /** 拆成每一段；末段的判断与 `SkillStore.isValidFileName` 保持一致。 */
    private val segments: List<String> get() = fileName.trim().split('/')

    val nameError: String? = when {
        fileName.isBlank() -> null // 还没开始输入
        fileName.startsWith("/") || fileName.endsWith("/") -> "路径不能以 / 开头或结尾"
        segments.any { it.isEmpty() } -> "路径里不能有连续的 //"
        segments.any { it == "." || it == ".." } -> "路径里不能有 . 或 .. 这样的段"
        segments.any { !FILE_NAME_SEGMENT.matches(it) } -> "每一段只能包含字母、数字、. _ -（1–64 个字符）"
        // SKILL.md 是技能本体，走「编辑」改它；用「新建文件」建它会走名字一致性校验
        // （空内容没有 frontmatter 的 name），到时候报的错离用户很远。
        fileName.trim().equals("SKILL.md", ignoreCase = true) -> "SKILL.md 是技能本体，请用「编辑」改它"
        else -> null
    }

    val saveable: Boolean get() = fileName.isNotBlank() && nameError == null

    private companion object {
        val FILE_NAME_SEGMENT = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/**
 * 「从 URL 导入」表单。
 *
 * 支持任意 http(s) 地址，不绑定某个代码托管站：地址指向**一份 SKILL.md**
 * 就进「手动添加」让用户确认；指向**一个 zip** 就把里面的每个 SKILL.md 解开逐个导入。
 * 解析与下载都在 `SkillImport` 里，这里只放界面状态。
 */
data class SkillUrlDraft(
    val url: String = "",
    val scope: SkillScope = SkillScope.PROJECT,
    /** 下载中。这段时间要禁用「导入」，否则连点会并发拉好几份。 */
    val loading: Boolean = false,
) {
    /** 只挡明显不是地址的输入；真正的合法性由下载那一步判（并给出具体原因）。 */
    val ready: Boolean
        get() {
            val text = url.trim().lowercase(java.util.Locale.US)
            return !loading && (text.startsWith("http://") || text.startsWith("https://")) &&
                text.length > "https://".length
        }
}

/**
 * 正在编辑的文件（**对话框**，不是整页）。
 *
 * 技能不只有 `SKILL.md`，所以这里带的是**相对路径**而不是"这个技能的内容"。
 */
data class SkillEditTarget(
    val name: String,
    val scope: SkillScope,
    /** 技能目录里的相对路径，通常是 `SKILL.md`，也可以是 `reference/api.md`。 */
    val relativePath: String,
    /** 编辑中的内容。保存前只在内存里，不落盘。 */
    val body: String,
    /**
     * 写 `SKILL.md` 时的名字冲突原因（null = 没问题）。
     *
     * 由 `WorkspaceViewModel` 在每次改动内容后重算 —— 判定要用 `SkillStore` 的
     * frontmatter 解析，而 model 层不依赖 data 层。放在这里是为了让对话框能**实时**
     * 提示"名字对不上"，而不是等用户点了保存才报错。
     */
    val nameError: String? = null,
    /**
     * 打开编辑器时磁盘上的那份正文（默认等于 [body] = 干净）。
     *
     * 只用于判断「有没有未保存的改动」：见 [dirty]。保存成功后 ViewModel 会把这份
     * 基准同步成新内容，于是界面不会一直以为还有脏数据。
     */
    val savedBody: String = body,
) {
    /** 内容长度提示用。空内容不算错误，只提示一句。 */
    val empty: Boolean get() = body.isBlank()

    /** 有过未保存的改动（返回 / 关闭前需要确认）。 */
    val dirty: Boolean get() = body != savedBody

    val fileName: String get() = relativePath.substringAfterLast('/')

    val saveable: Boolean get() = nameError == null
}

/**
 * 一张角色卡。
 *
 * 内容会作为 `<role_card>` 块注入系统提示词（见引擎 `SystemPromptBuilder`），
 * 所以它是"长期生效的人设指令"，不是一次性提示词。
 */
data class RoleCard(val id: String, val name: String, val content: String) {
    /** 列表副标题：显示内容摘要，让用户不必点进去就知道卡里写了什么。 */
    val summary: String
        get() {
            val flattened = content.replace('\n', ' ').trim()
            if (flattened.isEmpty()) return "空内容"
            return if (flattened.length <= 48) flattened else flattened.take(48) + "…"
        }
}

/** 角色卡面板状态：`editor != null` 即编辑页，否则列表页。 */
data class RoleCardsState(
    val cards: List<RoleCard>,
    val activeId: String,
    val editor: RoleCardEditor? = null,
    /** 载荷还没读完，见 `WorkspaceViewModel.openRoleCards`。 */
    val loading: Boolean = false,
)

/** 新增 / 编辑角色卡的草稿。[id] 为空表示新增。 */
data class RoleCardEditor(
    val id: String = "",
    val name: String = "",
    val content: String = "",
    /**
     * 打开编辑器时那一份**已保存**的名字与内容（默认等于当前值 = 干净）。
     *
     * 只用于判断「有没有没保存的改动」：编辑过程中的 `updateRoleCardDraft` 只改
     * [name] / [content]，于是 `dirty` 自然变真。没有它就只能靠"用户点过保存"
     * 这类间接信号，而那条信号在保存失败时是错的。
     */
    val savedName: String = name,
    val savedContent: String = content,
) {
    val isEditing: Boolean get() = id.isNotBlank()

    /** 与打开时相比有过改动（返回前需要确认）。 */
    val dirty: Boolean get() = name != savedName || content != savedContent

    /** 名称是唯一必填项：内容留空是允许的（等于临时停用这张卡的效果）。 */
    val nameError: String? get() = if (name.isBlank()) "请填写角色名称" else null
    val saveable: Boolean get() = nameError == null
}

/** 记忆文件的作用域。路径约定见 `MemoryStore`（必须与 `/init` 指令的目标一致）。 */
enum class MemoryScope(val label: String) {
    PROJECT("项目 ZhiCode.md"),
    USER("用户 ZhiCode.md"),
}

/**
 * 一个记忆文件在列表里的展示信息。
 */
data class MemoryFile(
    val scope: MemoryScope,
    val path: String,
    val exists: Boolean,
    val sizeLabel: String,
)

/** 记忆面板状态：`editing != null` 即编辑器，否则列表。 */
data class MemoryState(
    val files: List<MemoryFile>,
    val editing: MemoryEditor? = null,
    /** 载荷还没读完，见 `WorkspaceViewModel.openMemory`。 */
    val loading: Boolean = false,
)

/** 正在编辑的记忆文件。新建一个尚不存在的文件时 [exists] 为 false。 */
data class MemoryEditor(
    val scope: MemoryScope,
    val path: String,
    val body: String,
    val exists: Boolean,
    /** 打开时磁盘上的正文（默认等于 [body] = 干净）。见 [dirty]。 */
    val savedBody: String = body,
) {
    val title: String get() = if (exists) "编辑 ${scope.label}" else "新建 ${scope.label}"
    val empty: Boolean get() = body.isBlank()

    /** 有过未保存的改动（返回 / 关闭前需要确认）。 */
    val dirty: Boolean get() = body != savedBody
}




/**
 * 模型选择面板的状态。
 *
 * 原版的这个面板是**异步填充**的：先开着窗显示"正在从当前 API 获取模型…"，
 * 目录回来后再把列表填进去。所以 [loading] / [status] / [models] 必须是状态而不是
 * 一次性参数——网络慢的时候界面得先立起来。
 *
 * [query] 是手动输入框的内容。目录拉不到时用户就靠它兜底，因此它不能只在弹窗内部
 * 存活（比如转屏或重组时被清掉）。
 */
data class ModelPickerState(
    val profileName: String,
    val currentModel: String,
    val query: String = "",
    val loading: Boolean = true,
    val status: String = "正在从当前 API 获取模型…",
    val models: List<ModelOption> = emptyList(),
    /**
     * 套餐额度行（目前只有 ZCode 会填）。空表示不显示额度卡片。
     *
     * 每行的数字与倒计时**已经是成品文案**（由协议层算好）：分档规则属于协议知识，
     * 界面再算一遍迟早会和那边不一致。
     */
    val quota: List<QuotaRow> = emptyList(),
    /**
     * 额度读取失败的原因（空表示没失败）。
     *
     * 与 [quota] 分开而不是共用一个字段：额度成功时它是空的，失败时 [quota] 是空的 ——
     * 但"没额度"和"读不到额度"是两件事，卡片要说的话也不一样（前者不显示卡片，
     * 后者要说明为什么读不到并留一个「刷新」）。
     */
    val quotaError: String = "",
    /** [models] 旁边的一句补充，如「（仅套餐可用模型）」。 */
    val modelsNote: String = "",
    /**
     * 已配置的 API 记录，用于面板里的快速切换 tab 栏。
     *
     * <p>**必须在这里另存一份，不能现读 `WorkspaceUiState.apiConfig`**：那个字段的语义
     * 是"API 配置弹窗当前打开"，面板显示时为 null（`closeApiConfig()` 会清掉），
     * 所以从它取值会永远拿不到东西。
     */
    val profiles: List<ModelProfileTab> = emptyList(),
    /** 当前生效的 API 记录 id，与 [profiles] 配套。 */
    val activeProfileId: String = "",
) {
    /**
     * 当前生效的 API 在 [profiles] 里的下标；找不到时为 0。
     *
     * <p>做成**派生属性**而不是在组合函数里现算：`indexOfFirst` 在"没找到"时返回 -1，
     * 而 Miuix 的 `TabRow` 需要一个合法下标 —— 直接把 -1 传下去会没有任何 tab 被选中，
     * 看上去像控件坏了。这里的 0 兜底和边界情况都是能脱离 Compose 单测的。
     */
    val activeProfileIndex: Int
        get() = profiles.indexOfFirst { it.id == activeProfileId }.coerceAtLeast(0)

    /** 是否该显示切换 tab 栏：只有一条时它是死控件（切不了任何东西）。 */
    val showProfileTabs: Boolean get() = profiles.size > 1
}

/**
 * 面板 tab 栏里的一条 API 记录。
 *
 * <p>只留切换需要的两样东西（id 与显示名），不直接把 `ApiProfile` 放进面板状态：
 * 那个类型带着 `apiKey` —— 面板没有任何理由持有密钥，少一个地方碰它少一处泄漏面。
 */
data class ModelProfileTab(val id: String, val name: String)

/**
 * 首次进入时的 API 配置列表：**空**。
 *
 * <p>以前这里预置一条厂家的「官方 API」。那个做法已经取消，理由有两个：
 * 一是它等于替某个具体服务做默认入口（并且带推广参数），这该由用户自己选；
 * 二是任何内置地址都会让「请求到底发到哪里」变得不透明。
 *
 * <p>所以应用不再自带任何地址与模型名，全部由用户在设置里新增。
 * 列表允许为空：[AppSettings.apiProfiles] 与 [AppSettings.activeProfileId] 都跟着空，
 * 界面据此显示「还没有 API 配置」，而真正发请求前会由引擎侧拦下来并给出明确错误。
 */
val DEFAULT_API_PROFILES: List<ApiProfile> = emptyList()

/**
 * 原版设置页里**新增的**持久化项。
 *
 * ⚠️ 刻意**不**在这里放 `themeMode` / `permissionMode` / `effort` / `contextWindow`
 * / `projectPath` / `modelLabel` 等字段：它们已经是 `WorkspaceUiState` 的成员，
 * 再放一份就会出现"两份真相"，保存时到底谁覆盖谁很难说清。
 * 这些字段由 [SettingsDraft] 在打开弹窗时快照、保存时写回。
 */
data class AppSettings(
    val visionEnabled: Boolean = true,
    val sandboxAgentFullAccess: Boolean = false,
    val rootExecutionEnabled: Boolean = false,
    /** Shizuku 工具开关：以 shell 身份（uid 2000）跑 pm/am/dumpsys 这类系统命令。与 Root 独立。 */
    val shizukuExecutionEnabled: Boolean = false,
    val forcedKeepAliveEnabled: Boolean = false,
    /** 终端强制字符模式输入（默认关：走正常软件输入法）。见 ApiSettingsStore。 */
    val terminalCharMode: Boolean = false,
    val webSearchEnabled: Boolean = true,
    val webSearchProvider: WebSearchProvider = WebSearchProvider.AUTO,
    val webSearchMaxResults: Int = 5,
    val webSearchTimeoutSec: Int = 15,
    /** 密钥制搜索服务的 Key，按服务各存一份；切换服务不丢。 */
    val webSearchKeys: Map<WebSearchProvider, String> = emptyMap(),
    /** SearXNG 实例地址（如 https://searx.example.com），仅 SearXNG 服务用到。 */
    val webSearchSearxngUrl: String = "",
    val autoCompact: Boolean = true,
    /** 自动压缩上限，取值 50..100（百分比）。 */
    val autoCompactPercent: Int = 80,
    val customSystemPrompt: String = "",
    /** API 配置记录（列表 + 当前选用）。列表可以为空 = 还没有配过。 */
    val apiProfiles: List<ApiProfile> = DEFAULT_API_PROFILES,
    val activeProfileId: String = "",
)

/** 结果数合法区间。原版为 1–10；应用户要求放宽到 50（密钥制服务扛得住这个量）。 */
const val WEB_RESULTS_MIN = 1
const val WEB_RESULTS_MAX = 50

/** 联网超时合法区间（秒），原版为 5000–60000ms。 */
const val WEB_TIMEOUT_MIN_SEC = 5
const val WEB_TIMEOUT_MAX_SEC = 60

/** 自动压缩上限合法区间（百分比）。 */
const val COMPACT_PERCENT_MIN = 50
const val COMPACT_PERCENT_MAX = 100

/** 上下文窗口候选值，对应原版 hint "128k / 200k / 1m / 1.5m"。 */
val CONTEXT_WINDOW_PRESETS: List<Int> = listOf(128_000, 200_000, 1_000_000, 1_500_000)

fun clampWebResults(value: Int): Int = value.coerceIn(WEB_RESULTS_MIN, WEB_RESULTS_MAX)

fun clampWebTimeoutSec(value: Int): Int = value.coerceIn(WEB_TIMEOUT_MIN_SEC, WEB_TIMEOUT_MAX_SEC)

fun clampCompactPercent(value: Int): Int = value.coerceIn(COMPACT_PERCENT_MIN, COMPACT_PERCENT_MAX)

/**
 * 解析上下文窗口输入，支持原版的 `128k` / `200k` / `1m` / `1.5m` 写法。
 * 解析不出来或非正数时返回 null，由调用方决定保留旧值。
 */
fun parseTokenCount(raw: String): Int? {
    val text = raw.trim().lowercase()
    if (text.isEmpty()) return null
    val multiplier = when {
        text.endsWith("m") -> 1_000_000.0
        text.endsWith("k") -> 1_000.0
        else -> 1.0
    }
    val number = text.removeSuffix("m").removeSuffix("k").trim().toDoubleOrNull() ?: return null
    val tokens = (number * multiplier).toInt()
    return if (tokens > 0) tokens else null
}

/** 把 token 数格式化回 `200k` / `1m` / `1.5m` 这种紧凑写法。 */
fun formatTokenCountShort(tokens: Int): String {
    if (tokens <= 0) return "0"
    return when {
        tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}m"
        tokens >= 1_000_000 -> {
            val millions = tokens / 1_000_000.0
            // 去掉多余的小数位：1.5m 而不是 1.50m
            val rounded = String.format(java.util.Locale.US, "%.2f", millions).trimEnd('0').trimEnd('.')
            "${rounded}m"
        }
        tokens % 1_000 == 0 -> "${tokens / 1_000}k"
        else -> tokens.toString()
    }
}

/** 十进制百分比 ↔ 0..1 浮点，供 `SliderPreference` 使用。 */
fun percentToFraction(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)

fun fractionToPercent(fraction: Float): Int =
    clampCompactPercent(Math.round(fraction * 100f).toInt())

/**
 * 设置弹窗的编辑草稿。
 *
 * **draft 语义**：弹窗打开时用 [from] 从当前 state 快照一份，用户所有改动都落在
 * draft 上；「保存」才用 [applyTo] 写回 state，「取消」直接丢弃 draft。
 * 这样"取消"是真正无副作用的，而不是先改后回滚。
 *
 * 这里同时持有 state 里的既有字段（主题/权限/推理/上下文/项目路径/提示词）
 * 和 [AppSettings] 里的新增字段，是为了让弹窗只读一份数据、不必到处传 state。
 */
data class SettingsDraft(
    val themeMode: ThemeMode,
    val permissionMode: PermissionMode,
    val effort: EffortLevel,
    val contextWindow: Int,
    val projectPath: String,
    val customSystemPrompt: String,
    /** 只读展示用：当前 API 记录名 + 模型名（对应参考图的「deepseek · openai-chat · deepseek-flash」）。 */
    val profileName: String,
    val modelLabel: String,
    val visionEnabled: Boolean,
    val sandboxAgentFullAccess: Boolean,
    val rootExecutionEnabled: Boolean,
    val forcedKeepAliveEnabled: Boolean,
    val terminalCharMode: Boolean,
    val webSearchEnabled: Boolean,
    val webSearchProvider: WebSearchProvider,
    val shizukuExecutionEnabled: Boolean,
    val webSearchMaxResults: Int,
    val webSearchTimeoutSec: Int,
    val webSearchKeys: Map<WebSearchProvider, String>,
    val webSearchSearxngUrl: String,
    val autoCompact: Boolean,
    val autoCompactPercent: Int,
    /** 当前分类 Tab。放在 draft 里，取消时一并丢弃，重新打开总是回到第一类。 */
    val category: SettingsCategory = SettingsCategory.APPEARANCE,
) {
    companion object {
        fun from(state: WorkspaceUiState): SettingsDraft {
            val s = state.settings
            return SettingsDraft(
                themeMode = state.themeMode,
                permissionMode = state.permissionMode,
                effort = state.effort,
                contextWindow = state.contextWindow,
                projectPath = state.projectPath,
                customSystemPrompt = s.customSystemPrompt,
                profileName = state.profileName,
                modelLabel = state.modelLabel,
                visionEnabled = s.visionEnabled,
                sandboxAgentFullAccess = s.sandboxAgentFullAccess,
                rootExecutionEnabled = s.rootExecutionEnabled,
                shizukuExecutionEnabled = s.shizukuExecutionEnabled,
                forcedKeepAliveEnabled = s.forcedKeepAliveEnabled,
                terminalCharMode = s.terminalCharMode,
                webSearchEnabled = s.webSearchEnabled,
                webSearchProvider = s.webSearchProvider,
                webSearchMaxResults = s.webSearchMaxResults,
                webSearchTimeoutSec = s.webSearchTimeoutSec,
                webSearchKeys = s.webSearchKeys,
                webSearchSearxngUrl = s.webSearchSearxngUrl,
                autoCompact = s.autoCompact,
                autoCompactPercent = s.autoCompactPercent,
            )
        }
    }

    /** 保存时写回 state。数值字段在这里统一 clamp，UI 层不需要各自校验。 */
    /**
     * 把 draft 写回 state。
     *
     * [keepDraft] 是给设置页的自动保存用的：那一屏的 draft 同时是显示源，
     * 写回后不能清掉，否则页面会立刻被 onDismiss 之外的东西关掉。
     * `false` 时清掉 draft —— 那是「提交并关闭」的旧语义，现在只剩测试与直接调用会走。
     */
    fun applyTo(state: WorkspaceUiState, keepDraft: Boolean = false): WorkspaceUiState = state.copy(
        themeMode = themeMode,
        permissionMode = permissionMode,
        effort = effort,
        contextWindow = contextWindow,
        projectPath = projectPath.ifBlank { state.projectPath },
        settings = AppSettings(
            visionEnabled = visionEnabled,
            sandboxAgentFullAccess = sandboxAgentFullAccess,
            rootExecutionEnabled = rootExecutionEnabled,
            shizukuExecutionEnabled = shizukuExecutionEnabled,
            forcedKeepAliveEnabled = forcedKeepAliveEnabled,
            terminalCharMode = terminalCharMode,
            webSearchEnabled = webSearchEnabled,
            webSearchProvider = webSearchProvider,
            webSearchMaxResults = clampWebResults(webSearchMaxResults),
            webSearchTimeoutSec = clampWebTimeoutSec(webSearchTimeoutSec),
            webSearchKeys = webSearchKeys,
            webSearchSearxngUrl = webSearchSearxngUrl.trim(),
            autoCompact = autoCompact,
            autoCompactPercent = clampCompactPercent(autoCompactPercent),
            customSystemPrompt = customSystemPrompt,
        ),
        settingsDraft = if (keepDraft) this else null,
    )
}

/**
 * 「设置 → 扩展」那一组要显示的数量摘要。
 *
 * ## 为什么需要它
 *
 * 扩展组此前是四条纯入口行（MCP / 技能 / 角色卡 / 记忆），一行里没有任何
 * "我现在装了什么"的信息 —— 想确认某个 MCP 服务器还在不在，得点进去再退出来。
 * 参考实现（rikkahub）的扩展入口行都带当前值，这里补上同样的信息。
 *
 * ## 为什么不是每次重组去读
 *
 * 三个存储都在磁盘上（`mcp.json` / 技能目录 walk / `role_cards.json`），
 * 在界面里读会同时命中"主线程 IO"与"每次重组都读盘"两条禁忌。所以由
 * `WorkspaceViewModel` 在打开设置时于 IO 线程统计一次，结果落进状态。
 */
data class ExtensionSummary(
    val mcpTotal: Int = 0,
    val mcpEnabled: Int = 0,
    /** 已测过且连上的台数；0 表示还没测过任何一台（不是"全挂了"）。 */
    val mcpConnected: Int = 0,
    val skillTotal: Int = 0,
    val projectSkills: Int = 0,
    val userSkills: Int = 0,
    val roleCardTotal: Int = 0,
    /** 是否已经统计过一次（false 时界面显示"正在统计"而不是 0）。 */
    val loaded: Boolean = false,
)
