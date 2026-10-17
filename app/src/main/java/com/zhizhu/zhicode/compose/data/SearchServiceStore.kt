package com.zhizhu.zhicode.compose.data

import android.content.Context
import com.termux.app.zhicode.storage.ApiSettingsStore
import com.zhizhu.zhicode.compose.model.SearchService
import com.zhizhu.zhicode.compose.model.SearchServiceDraft
import com.zhizhu.zhicode.compose.model.SearchFieldName
import com.zhizhu.zhicode.compose.model.SearchServiceType
import com.zhizhu.zhicode.compose.model.SearchServicesState
import com.zhizhu.zhicode.compose.model.WebSearchProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索服务的读写适配层（形态对齐 RikkaHub 的「搜索服务」页）。
 *
 * ## 与 API 配置页是同一套骨架
 *
 * 列表 + 当前生效项 + 编辑表单 + 密钥单独加密存放 —— `ApiConfigStore` 已经跑通了，
 * 所以这里**照抄结构**而不是另发明一套：用户在两个页面看到的交互应当一致
 * （点卡片设为当前、右上角添加、编辑页可删）。Context 也是显式传参，同一个理由。
 *
 * ## 密钥为什么不进 config
 *
 * [SearchService.config] 是普通字段（实例地址、深度、主题…），落在 prefs；
 * 密钥走 [ApiSettingsStore.setSearchServiceKey] 的**加密槽**，按 service id 分槽。
 * 读回时**不回填**到表单（[SearchServiceDraft.from] 给空串）——
 * 与 API 配置同一条规矩：界面上不该出现已保存的密钥明文。
 *
 * ## 老配置迁移
 *
 * 上一版只有一个 `WebSearchProvider` 枚举 + 一个 Key。首次启动把它转成一条
 * [SearchService]，让老用户的配置不至于白填。迁移**只做一次**并打标记 ——
 * 否则用户删掉那条之后又会被"迁移"回来。
 * 迁移的输入由调用方显式传入（老配置存在引擎侧），这里不做二次读取。
 */
internal object SearchServiceStore {

    fun read(context: Context): SearchServicesState {
        val stored = ApiSettingsStore.readSearchServices(context)
        var services = parseServices(stored[0])
        var activeId = stored[1]
        // 一条都没有时给两条**免费**预设（DuckDuckGo 可用 + Bing 备用）。
        //
        // 为什么不在 `AppSettings` 里预置：那样"用户删光了"与"从没配过"就分不开，
        // 删完下次进页面又被塞回来。所以这里用一次性标记 —— 种过一次就不再种。
        if (services.isEmpty() && !ApiSettingsStore.isSearchSeedDone(context)) {
            services = SEED_SERVICES
            activeId = SEED_ACTIVE_ID
            write(context, services, activeId)
            ApiSettingsStore.markSearchSeedDone(context)
        }
        return SearchServicesState(
            services = services,
            // activeId 指向一条不存在的记录（被删了）时回落到第一条：
            // 否则「当前使用」会指向空，界面只显示"没选中"，而搜索会静默走默认后端。
            activeId = if (services.any { it.id == activeId }) activeId
            else services.firstOrNull()?.id.orEmpty(),
        )
    }

    /**
     * 首次进入时种下的两条预设。
     *
     * 选它们而不是 Tavily/Exa：那两家要密钥，而"打开设置就看到一个必须先去注册才能用的空框"
     * 是这条链路以前劝退用户的地方。DuckDuckGo 与 Bing RSS 都是免费公开入口，
     * 种下去就能直接搜 —— Bing 那条**停用**是因为它只是限流时的备份，
     * 两条都启用会让"当前使用"看起来有歧义。
     */
    private val SEED_SERVICES: List<SearchService> = listOf(
        SearchService(
            id = "search-duckduckgo",
            name = "DuckDuckGo",
            type = SearchServiceType.DUCKDUCKGO,
        ),
        SearchService(
            id = "search-bing",
            name = "Bing RSS",
            type = SearchServiceType.BING,
            enabled = false,
        ),
    )

    private const val SEED_ACTIVE_ID = "search-duckduckgo"

    /**
     * 保存（新增或按 [SearchServiceDraft.id] 覆盖）。
     *
     * 校验在模型层（[SearchServiceDraft.saveable]），这里只负责落盘与密钥槽。
     */
    fun save(context: Context, draft: SearchServiceDraft): Result<String> = runCatching {
        require(draft.saveable) {
            draft.nameError ?: draft.fieldError ?: draft.keyError ?: "配置有误"
        }
        val current = read(context)
        val id = draft.id?.takeIf { it.isNotBlank() }
            ?: "search-" + System.currentTimeMillis().toString(36)

        val service = SearchService(
            id = id,
            name = draft.name.trim(),
            type = draft.type,
            enabled = draft.enabled,
            // 只保留这个类型真正用得到的字段：换了类型之后旧字段留着会让列表副标题
            // 显示无关信息（从 SearXNG 改成 Tavily 后还挂着实例地址）。
            config = draft.config.filterKeys { key -> draft.type.fields.any { it.name == key } },
        )

        val next = current.services.filterNot { it.id == id } + service
        write(context, next, current.activeId.ifBlank { id })

        // 密钥：只有用户真的输入了才覆盖。留空 = 沿用原密钥
        // （否则「改个检索深度」会把 Key 悄悄清掉）。
        if (draft.type.needsKey && draft.apiKey.isNotBlank()) {
            ApiSettingsStore.setSearchServiceKey(context, id, draft.apiKey)
        }
        id
    }

    fun delete(context: Context, serviceId: String): Result<Unit> = runCatching {
        val current = read(context)
        val next = current.services.filterNot { it.id == serviceId }
        val activeId = if (current.activeId == serviceId) next.firstOrNull()?.id.orEmpty()
        else current.activeId
        write(context, next, activeId)
        ApiSettingsStore.removeSearchServiceKey(context, serviceId)
    }

    /** 设为当前使用。 */
    fun select(context: Context, serviceId: String): Result<Unit> = runCatching {
        val current = read(context)
        require(current.services.any { it.id == serviceId }) { "这条服务已经不存在了" }
        write(context, current.services, serviceId)
    }

    /** 当前生效的服务（未配置任何服务时返回 null，搜索会回落到免费后端）。 */
    fun active(context: Context): SearchService? {
        val state = read(context)
        return state.services.firstOrNull { it.id == state.activeId && it.enabled }
    }

    /**
     * 首次启动时把老版「单个 provider + 单个 Key」迁成一条服务。
     *
     * [provider] / [searxngUrl] / [apiKey] 都由调用方从老配置里读好传进来 ——
     * 那些值存在引擎侧（SessionConfig 与密钥槽），适配层不该反向依赖引擎。
     */
    fun migrateLegacyIfNeeded(
        context: Context,
        provider: WebSearchProvider,
        searxngUrl: String,
        apiKey: String,
    ): Result<Unit> = runCatching {
        if (ApiSettingsStore.isSearchMigrationDone(context)) return@runCatching
        if (read(context).services.isNotEmpty()) {
            ApiSettingsStore.markSearchMigrationDone(context)
            return@runCatching
        }
        // 「自动（免费）」就是现在的默认行为，不必造一条记录出来。
        val type = provider.toSearchServiceType()
        if (type == null) {
            ApiSettingsStore.markSearchMigrationDone(context)
            return@runCatching
        }
        val id = "search-legacy"
        val config = buildMap<String, String> {
            if (type == SearchServiceType.SEARXNG && searxngUrl.isNotBlank()) {
                put(SearchFieldName.BASE_URL, searxngUrl)
            }
        }
        write(
            context,
            listOf(SearchService(id = id, name = type.label, type = type, config = config)),
            id,
        )
        if (type.needsKey && apiKey.isNotBlank()) {
            ApiSettingsStore.setSearchServiceKey(context, id, apiKey)
        }
        ApiSettingsStore.markSearchMigrationDone(context)
    }

    /** 这条服务是否已配好密钥（编辑页显示「已配置」与否）。 */
    fun hasKey(context: Context, serviceId: String): Boolean =
        runCatching { ApiSettingsStore.getSearchServiceKey(context, serviceId).isNotBlank() }
            .getOrDefault(false)

    private fun write(context: Context, services: List<SearchService>, activeId: String) {
        ApiSettingsStore.writeSearchServices(
            context,
            JSONArray().apply { services.forEach { put(it.toJson()) } }.toString(),
            activeId,
        )
    }

    private fun WebSearchProvider.toSearchServiceType(): SearchServiceType? = when (this) {
        WebSearchProvider.AUTO -> null
        WebSearchProvider.DUCKDUCKGO -> SearchServiceType.DUCKDUCKGO
        WebSearchProvider.BING -> SearchServiceType.BING
        WebSearchProvider.TAVILY -> SearchServiceType.TAVILY
        WebSearchProvider.EXA -> SearchServiceType.EXA
        WebSearchProvider.BRAVE -> SearchServiceType.BRAVE
        WebSearchProvider.SEARXNG -> SearchServiceType.SEARXNG
    }
}

// ---------------------------------------------------------------- 模型 ↔ JSON

private fun SearchService.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", type.name)
    put("enabled", enabled)
    put("config", JSONObject(config.toMap()))
}

/** 解析服务列表。**坏掉的一条跳过而不是整份失败** —— 少一条服务好过设置页打不开。 */
internal fun parseServices(json: String?): List<SearchService> {
    val array = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
    val out = mutableListOf<SearchService>()
    for (i in 0 until array.length()) {
        array.optJSONObject(i)?.let { obj ->
            val service = obj.toSearchService()
            if (service != null) out += service
        }
    }
    return out
}

private fun JSONObject.toSearchService(): SearchService? {
    val id = optString("id", "").trim()
    if (id.isEmpty()) return null
    val type = runCatching { SearchServiceType.valueOf(optString("type", "")) }.getOrNull() ?: return null
    val config = mutableMapOf<String, String>()
    optJSONObject("config")?.let { obj ->
        obj.keys().forEach { key -> config[key] = obj.optString(key, "") }
    }
    return SearchService(
        id = id,
        name = optString("name", "").ifBlank { type.label },
        type = type,
        enabled = optBoolean("enabled", true),
        config = config,
    )
}
