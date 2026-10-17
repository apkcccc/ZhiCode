package com.termux.app.zhicode.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.zhicode.json.JsonItems;
import com.termux.app.zhicode.model.ApiProfile;
import com.termux.app.zhicode.model.SessionConfig;
import com.termux.app.zhicode.security.AndroidSecretStore;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 全局设置与 API 配置的持久化。
 *
 * <h3>秘密不在这里</h3>
 * 本类只存<b>不含密钥</b>的元数据（地址、模型名、协议）。API Key 一律走
 * {@link AndroidSecretStore}，落到 Android Keystore 里。这样即使设置被导出或备份，
 * 也不会连带泄露令牌 —— {@code ApiProfile} 里没有密钥字段，是刻意的。
 *
 * <h3>不内置任何 API 配置</h3>
 * 应用不预置任何厂商地址：所有配置都由用户自己新增。理由有两个：
 * 一是替某个服务做默认入口（尤其是带推广参数的）等于替它做导流；
 * 二是任何内置地址都会让「请求发到哪」变得不透明。
 * 所以配置列表可以为空，{@link #getActiveProfile()} 返回 null 表示还没有可用配置，
 * 而「未配置」由 {@code ApiUrlPolicy} 拦下来并给出明确提示。
 *
 * <h3>一次性清除内置端点</h3>
 * 早期版本内置过一条厂商配置（id 与名字里都写着官方）。升级时必须把它删干净，
 * 否则用户会继续被导向那个地址。见 {@link #purgeBundledEndpoint}。
 *
 * <h3>读取路径不应该写盘</h3>
 * 规范化结果只在<b>确实变化</b>时才落盘。原先每次 {@code getProfiles()} 都写一次，
 * 而调用方常在循环里取它 —— 既浪费，也让「读」有机会覆盖别的线程刚写下的内容。
 *
 * <h3>损坏的 profile 列表不得被静默覆盖</h3>
 * 原先解析失败只返回空列表，而 {@link #load()} 紧接着会走迁移逻辑并<b>写回</b>一份新列表，
 * 于是用户自建的 profile 全部消失。现在坏内容会先留档，且原因可查（{@link #lastError()}）。
 *
 * <h3>设置项只有一份清单</h3>
 * 会话级设置的读写由 {@link #SETTINGS} 一张表驱动，而不是「读一个方法、写另一个方法」
 * 两份键名清单。两份清单必须一直保持一致，而「加了新设置但只加了一半」的后果是
 * 「设置能存进去、重启后消失」—— 不报错、不崩，只表现为用户抱怨。
 */
public final class ApiSettingsStore {

    /** 当前 prefs 文件名。由品牌短标识派生，与其它模块拼出的名字必须一致。 */
    private static final String PREFS = TermuxConstants.BRAND_SLUG + "_settings";

    /**
     * 存储键。
     *
     * <p>这些键名是<b>持久化契约</b>：改一个就等于丢掉用户已有的设置。
     * 所以集中在一处，并且不允许顺手重命名。
     */
    private static final class Key {
        static final String PROFILES = "api_profiles_v1";
        static final String ACTIVE_PROFILE = "active_api_profile_id";
        static final String MIGRATED_PROFILE = "legacy_api_profile_id";
        static final String ROLE_CARDS = "role_cards_v1";
        static final String ACTIVE_ROLE_CARD = "active_role_card_id";
        static final String LAST_SESSION = "last_session_path";

        // 以下与 SessionConfig 的字段一一对应，见 SETTINGS。
        static final String PROTOCOL = "protocol";
        static final String BASE_URL = "base_url";
        /** 额外请求头（JSON 对象文本）。与 {@link SessionConfig#extraHeaders} 对应。 */
        static final String EXTRA_HEADERS = "extra_headers";
        static final String MODEL = "model";
        static final String VISION_ENABLED = "vision_enabled";
        static final String EFFORT = "effort";
        static final String CUSTOM_PROMPT = "custom_system_prompt";
        /**
         * 可自定义的提示词槽位（见 {@code model/PromptSlots.kt}）。
         *
         * <p>三个键都必须**存在即覆盖**：空串表示"用默认"，所以读到空串时不能当成"没配过"
         * 而回落到旧值 —— 那会让「恢复默认」这个动作在重启后反弹。
         */
        static final String INIT_PROMPT = "init_prompt";
        static final String SUMMARY_PROMPT = "summary_prompt";
        static final String COMPACT_PROMPT_HEAD = "compact_prompt_head";
        static final String ROLE_CARD = "role_card";
        static final String REASONING_SUMMARY = "reasoning_summary";
        static final String PRESERVE_REASONING = "preserve_reasoning_state";
        static final String TOOL_MODE = "tool_mode";
        static final String PERMISSION_MODE = "permission_mode";
        static final String SANDBOX_FULL_ACCESS = "sandbox_agent_full_access";
        static final String ROOT_EXECUTION = "root_execution_enabled";
        /** Shizuku 工具开关。与 Root 分开存：两者的前提条件不同，不能互相隐含。 */
        static final String SHIZUKU_EXECUTION = "shizuku_execution_enabled";
        static final String FORCED_KEEP_ALIVE = "forced_keep_alive_enabled";
        /** 终端是否强制字符模式输入（见 getTerminalCharMode 的说明）。 */
        static final String TERMINAL_CHAR_MODE = "terminal_char_mode";
        static final String PROJECT_DIRECTORY = "project_directory";
        static final String MAX_TOKENS = "max_tokens";
        static final String CONTEXT_WINDOW = "context_window_tokens";
        static final String AUTO_COMPACT = "auto_compact";
        static final String AUTO_COMPACT_RATIO = "auto_compact_ratio";
        static final String COMPACTION_VERSION = "context_compaction_logic_version";
        static final String WEB_SEARCH_ENABLED = "web_search_enabled";
        static final String WEB_SEARCH_PROVIDER = "web_search_provider";
        static final String WEB_SEARCH_MAX_RESULTS = "web_search_max_results";
        static final String WEB_SEARCH_SEARXNG_URL = "web_search_searxng_url";
        static final String WEB_FETCH_MAX_CHARS = "web_fetch_max_chars";
        static final String WEB_TIMEOUT = "web_timeout_ms";

        static final String BUNDLED_PURGE = "bundled_endpoint_purge_version";

        /** 纯界面设置（不属于 SessionConfig，见 getThemeMode 的说明）。 */
        static final String THEME_MODE = "theme_mode";

        /** 搜索服务列表（RikkaHub 形态：多实例 + 当前生效项）。 */
        static final String SEARCH_SERVICES = "search_services_v1";
        static final String ACTIVE_SEARCH_SERVICE = "active_search_service_id";
        /** 老配置（单个 provider）迁移成服务列表的一次性标记。 */
        static final String SEARCH_MIGRATION = "search_service_migration_v1";

        private Key() {}
    }

    /** 上下文压缩逻辑的版本，见 {@link #migrateCompactionRatio}。 */
    private static final int COMPACTION_LOGIC_VERSION = 2;

    /** 清除内置端点的一次性标记（版本号）。 */
    private static final int BUNDLED_PURGE_VERSION = 1;

    /** 第 1 版的默认压缩比例，仅用于识别「用户没动过」。 */
    private static final double LEGACY_COMPACT_RATIO = 0.78d;

    /**
     * 判定「这条记录是内置的」所用的名字片段。
     *
     * <p>内置记录的展示名一直是「… 官方 API」（早期是另一个产品名），所以名字里带这两个词
     * 就是它。刻意不写它的域名或 id：那等于把要删除的东西再抄一份，而匹配名字已经能覆盖
     * 两种来源（从旧版升级来的、以及从别的同源工程迁移来的）。
     *
     * <p>已知代价：用户若自己把某条配置改名成含这两个词的，会在这次清理里被删掉。
     * 只发生一次，而且丢的是一条可以重建的配置，不是用户数据。
     */
    private static final String[] BUNDLED_NAME_HINTS = {"官方", "official"};

    // ------------------------------------------------------------ 设置项表

    /**
     * 一个设置项的读写入口。
     *
     * <p>{@link #value} 同时充当「用户没设过时的默认值」：它返回的就是
     * {@link SessionConfig} 当前的字段值，所以默认值不必在表里再写一遍 ——
     * 也就不会出现「默认值在 SessionConfig 里改了，prefs 层还留着旧默认值」。
     */
    private interface Setting {
        Object value(SessionConfig config);

        void apply(SessionConfig config, Object value);
    }

    /** 普通项（String / Boolean / Integer）。 */
    private static Setting item(Function<SessionConfig, Object> get, BiConsumer<SessionConfig, Object> set) {
        return new Setting() {
            @Override
            public Object value(SessionConfig config) {
                return get.apply(config);
            }

            @Override
            public void apply(SessionConfig config, Object value) {
                set.accept(config, value);
            }
        };
    }

    /**
     * 字符串项，读写都过一遍 {@code normalize}。
     *
     * <p>读也规范化是必需的：磁盘上可能留着旧版本写下的未清理内容（多余空白、控制字符），
     * 只在写入时清理的话，这些值会一直原样流到 HTTP 请求体里。
     */
    private static Setting textItem(Function<SessionConfig, String> get, BiConsumer<SessionConfig, String> set,
                                    UnaryOperator<String> normalize) {
        return new Setting() {
            @Override
            public Object value(SessionConfig config) {
                return normalize.apply(orEmpty(get.apply(config)));
            }

            @Override
            public void apply(SessionConfig config, Object value) {
                set.accept(config, normalize.apply(orEmpty((String) value)));
            }
        };
    }

    /**
     * 会话级设置的读写清单。
     *
     * <p>顺序与界面上设置的排列一致，只为便于对照；prefs 本身无序。
     * 需要特殊处理的项<b>不在</b>这张表里：压缩比例是 double，
     * prefs 只支持按位存 long，另见 {@link #migrateCompactionRatio}。
     */
    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put(Key.PROTOCOL, item(c -> c.protocol, (c, v) -> c.protocol = (String) v));
        SETTINGS.put(Key.BASE_URL, textItem(c -> c.baseUrl, (c, v) -> c.baseUrl = v, ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.EXTRA_HEADERS,
                textItem(c -> c.extraHeaders, (c, v) -> c.extraHeaders = v, ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.MODEL, item(c -> c.model, (c, v) -> c.model = (String) v));
        SETTINGS.put(Key.EFFORT, item(c -> c.effort, (c, v) -> c.effort = (String) v));
        SETTINGS.put(Key.VISION_ENABLED, item(c -> c.visionEnabled, (c, v) -> c.visionEnabled = (Boolean) v));
        SETTINGS.put(Key.REASONING_SUMMARY,
                item(c -> c.reasoningSummary, (c, v) -> c.reasoningSummary = (String) v));
        SETTINGS.put(Key.PRESERVE_REASONING,
                item(c -> c.preserveReasoningState, (c, v) -> c.preserveReasoningState = (Boolean) v));
        SETTINGS.put(Key.CUSTOM_PROMPT, textItem(c -> c.customSystemPrompt,
                (c, v) -> c.customSystemPrompt = v, ApiSettingsStore::sanitizePrompt));
        // 提示词槽位用 textItem 而不是 sanitizePrompt：后者会按"提示词"的规则裁剪，
        // 而这三项是**用户自己的原话**，多留几个换行不该被吃掉。
        SETTINGS.put(Key.INIT_PROMPT, textItem(c -> c.initPrompt,
                (c, v) -> c.initPrompt = v, ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.SUMMARY_PROMPT, textItem(c -> c.summaryPrompt,
                (c, v) -> c.summaryPrompt = v, ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.COMPACT_PROMPT_HEAD, textItem(c -> c.compactPromptHead,
                (c, v) -> c.compactPromptHead = v, ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.ROLE_CARD, textItem(c -> c.roleCard,
                (c, v) -> c.roleCard = v, ApiSettingsStore::sanitizePrompt));
        SETTINGS.put(Key.TOOL_MODE, item(c -> c.toolMode, (c, v) -> c.toolMode = (String) v));
        SETTINGS.put(Key.PERMISSION_MODE, item(c -> c.permissionMode, (c, v) -> c.permissionMode = (String) v));
        SETTINGS.put(Key.SANDBOX_FULL_ACCESS,
                item(c -> c.sandboxAgentFullAccess, (c, v) -> c.sandboxAgentFullAccess = (Boolean) v));
        SETTINGS.put(Key.ROOT_EXECUTION,
                item(c -> c.rootExecutionEnabled, (c, v) -> c.rootExecutionEnabled = (Boolean) v));
        SETTINGS.put(Key.SHIZUKU_EXECUTION,
                item(c -> c.shizukuExecutionEnabled, (c, v) -> c.shizukuExecutionEnabled = (Boolean) v));
        SETTINGS.put(Key.FORCED_KEEP_ALIVE,
                item(c -> c.forcedKeepAliveEnabled, (c, v) -> c.forcedKeepAliveEnabled = (Boolean) v));
        SETTINGS.put(Key.PROJECT_DIRECTORY,
                item(c -> c.projectDirectory, (c, v) -> c.projectDirectory = (String) v));
        SETTINGS.put(Key.MAX_TOKENS, item(c -> c.maxTokens, (c, v) -> c.maxTokens = (Integer) v));
        SETTINGS.put(Key.CONTEXT_WINDOW,
                item(c -> c.contextWindowTokens, (c, v) -> c.contextWindowTokens = (Integer) v));
        SETTINGS.put(Key.AUTO_COMPACT, item(c -> c.autoCompact, (c, v) -> c.autoCompact = (Boolean) v));
        SETTINGS.put(Key.WEB_SEARCH_ENABLED,
                item(c -> c.webSearchEnabled, (c, v) -> c.webSearchEnabled = (Boolean) v));
        SETTINGS.put(Key.WEB_SEARCH_PROVIDER,
                item(c -> c.webSearchProvider, (c, v) -> c.webSearchProvider = (String) v));
        SETTINGS.put(Key.WEB_SEARCH_MAX_RESULTS,
                item(c -> c.webSearchMaxResults, (c, v) -> c.webSearchMaxResults = (Integer) v));
        // SearXNG 实例地址：不是密钥，跟着全局设置走。
        SETTINGS.put(Key.WEB_SEARCH_SEARXNG_URL,
                textItem(c -> c.webSearchBaseUrl, (c, v) -> c.webSearchBaseUrl = v,
                        ApiSettingsStore::trimToEmpty));
        SETTINGS.put(Key.WEB_FETCH_MAX_CHARS,
                item(c -> c.webFetchMaxChars, (c, v) -> c.webFetchMaxChars = (Integer) v));
        SETTINGS.put(Key.WEB_TIMEOUT, item(c -> c.webTimeoutMs, (c, v) -> c.webTimeoutMs = (Integer) v));
    }

    private final SharedPreferences prefs;
    private final AndroidSecretStore secrets;

    public ApiSettingsStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = new AndroidSecretStore(app);
        purgeBundledEndpoint();
    }

    // ------------------------------------------------------------ 一次性清理

    /**
     * 删除内置厂商配置，并清掉可能残留的全局端点。
     *
     * <p>要清两处，少一处都会「看起来删掉了、其实还在用」：
     * <ul>
     *   <li><b>配置列表</b>里的那条记录；</li>
     *   <li><b>全局端点</b> {@code base_url}。{@link #load()} 在列表非空时会用激活配置覆盖它，
     *       但列表为空时它会被直接当作请求地址 —— 那样就是删了记录却还在往旧地址发请求。</li>
     * </ul>
     *
     * <p>顺带删掉该记录的密钥槽：那条密钥是给那个服务用的，留着没有意义，
     * 而用户已经看不到它了，等于一个无法清理的残留密文。
     *
     * <p>只清一次（版本标记）。之后每次构造只多读一个 int，不做任何遍历。
     */
    private synchronized void purgeBundledEndpoint() {
        if (prefs.getInt(Key.BUNDLED_PURGE, 0) >= BUNDLED_PURGE_VERSION) return;

        List<ApiProfile> kept = new ArrayList<>();
        for (ApiProfile profile : readProfiles()) {
            if (!isBundledEndpoint(profile)) {
                kept.add(profile);
                continue;
            }
            try {
                secrets.removeApiKey(profile.id, profile.credentialRevision);
            } catch (Throwable ignored) {
                // 密钥槽删不掉也要继续：留一个读不到的密文，比留一条可用配置安全。
            }
        }

        String activeId = prefs.getString(Key.ACTIVE_PROFILE, "");
        if (findProfile(kept, activeId) == null) activeId = kept.isEmpty() ? "" : kept.get(0).id;
        persistProfiles(kept, activeId);

        // 全局端点一并清空。这是「记录没了，请求还发往旧地址」的唯一来源。
        prefs.edit()
                .putString(Key.BASE_URL, "")
                .putInt(Key.BUNDLED_PURGE, BUNDLED_PURGE_VERSION)
                .apply();
    }

    /** 是否内置厂商配置：看展示名，不看域名（不把要删的东西再抄一遍）。 */
    private static boolean isBundledEndpoint(ApiProfile profile) {
        String name = profile == null ? "" : orEmpty(profile.name).toLowerCase(Locale.US);
        for (String hint : BUNDLED_NAME_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 对外读取

    public synchronized SessionConfig load() {
        SessionConfig config = loadGlobal();
        List<ApiProfile> profiles = normalizedProfiles();
        if (profiles.isEmpty()) {
            // 一条配置都没有时，端点字段必须清空。这里不能只 return config：
            // 全局 base_url 可能是上一次内置配置留下的值，带着它返回就等于
            // 「列表里已经没有那条记录了，请求却还发往那个地址」。
            config.profileId = "";
            config.baseUrl = "";
            config.apiKey = "";
            return config;
        }
        ApiProfile active = activeProfileIn(profiles);
        applyProfile(config, active, apiKeyFor(active));
        return config;
    }

    public synchronized List<ApiProfile> getProfiles() {
        List<ApiProfile> copies = new ArrayList<>();
        for (ApiProfile profile : normalizedProfiles()) copies.add(profile.copy());
        return copies;
    }

    /** 当前生效配置；一条也没有时返回 {@code null}（界面据此显示「未配置」）。 */
    public synchronized ApiProfile getActiveProfile() {
        List<ApiProfile> profiles = getProfiles();
        return profiles.isEmpty() ? null : activeProfileIn(profiles);
    }

    public synchronized ApiProfile getProfile(String profileId) {
        ApiProfile profile = findProfile(getProfiles(), profileId);
        return profile == null ? null : profile.copy();
    }

    /** 当前生效配置的 id；一条也没有时返回空串。 */
    public synchronized String getActiveProfileId() {
        ApiProfile active = getActiveProfile();
        return active == null ? "" : active.id;
    }

    public synchronized void selectProfile(String profileId) {
        if (findProfile(getProfiles(), profileId) == null) {
            throw new IllegalArgumentException("未知的 API 配置: " + profileId);
        }
        prefs.edit().putString(Key.ACTIVE_PROFILE, profileId).apply();
    }

    /** 取某 profile 解析后的完整会话配置；profile 不存在返回 null。 */
    public synchronized SessionConfig resolveProfile(String profileId, SessionConfig base) {
        ApiProfile profile = findProfile(getProfiles(), profileId);
        if (profile == null) return null;
        SessionConfig resolved = base == null ? loadGlobal() : base.copy();
        applyProfile(resolved, profile, apiKeyFor(profile));
        return resolved;
    }

    /**
     * 列表里的激活项。
     *
     * <p>激活 id 失效时退回第一条即可：{@link #normalizedProfiles()} 读盘时已经修好并写回，
     * 所以这里只是一层兜底。
     */
    private ApiProfile activeProfileIn(List<ApiProfile> profiles) {
        ApiProfile active = findProfile(profiles, prefs.getString(Key.ACTIVE_PROFILE, ""));
        return active == null ? profiles.get(0) : active;
    }

    // ------------------------------------------------------------ 对外写入

    /**
     * 保存一条配置。
     *
     * <p>{@code replaceKey} 为真时才动密钥。为假表示「只改元数据」，此时密钥槽与
     * {@code credentialRevision} 必须原样保留，否则用户每改一次模型名就要重填一次密钥。
     */
    public synchronized ApiProfile saveProfile(ApiProfile draft, String apiKey, boolean replaceKey)
            throws Exception {
        if (draft == null) throw new IllegalArgumentException("需要 API 配置");
        List<ApiProfile> profiles = getProfiles();
        ApiProfile existing = findProfile(profiles, draft.id);
        ApiProfile saved = prepared(draft);

        if (existing == null) {
            profiles.add(saved);
        } else {
            saved.revision = existing.revision;
            saved.credentialRevision = existing.credentialRevision;
            // 端点三元组变了才算一次新的配置修订（上层据此知道要重建连接）。
            if (!sameEndpoint(existing, saved)) saved.revision++;
            replaceAt(profiles, saved);
        }

        if (replaceKey) {
            if (existing != null) saved.credentialRevision = existing.credentialRevision + 1;
            secrets.setApiKey(saved.id, saved.credentialRevision, trimToEmpty(apiKey));
        }

        String activeId = prefs.getString(Key.ACTIVE_PROFILE, saved.id);
        persistProfiles(profiles, activeId.isEmpty() ? saved.id : activeId);
        return saved.copy();
    }

    /**
     * 把一份草稿补成可落盘的记录。
     *
     * <p>先 {@code copy()}：后面要改字段，不能改到调用方手上那一份。
     * 修订号从 1 起，若这条已存在，调用方随后会用盘上那份覆盖掉。
     */
    private static ApiProfile prepared(ApiProfile draft) {
        ApiProfile defaults = new ApiProfile();
        ApiProfile saved = draft.copy();
        if (isBlank(saved.id)) saved.id = defaults.id;
        saved.name = firstNonBlank(saved.name, "API 配置");
        saved.protocol = firstNonBlank(saved.protocol, defaults.protocol);
        saved.baseUrl = trimToEmpty(saved.baseUrl);
        saved.extraHeaders = trimToEmpty(saved.extraHeaders);
        saved.defaultModel = trimToEmpty(saved.defaultModel);
        saved.revision = 1;
        saved.credentialRevision = 1;
        return saved;
    }

    /** 删掉一条配置。允许删到一条不剩 —— 「一条都没有」是合法的初始状态。 */
    public synchronized void deleteProfile(String profileId) {
        List<ApiProfile> profiles = getProfiles();
        ApiProfile profile = findProfile(profiles, profileId);
        if (profile == null) return;

        profiles.remove(profile);
        secrets.removeApiKey(profile.id, profile.credentialRevision);
        String activeId = prefs.getString(Key.ACTIVE_PROFILE, "");
        if (profile.id.equals(activeId)) activeId = profiles.isEmpty() ? "" : profiles.get(0).id;
        persistProfiles(profiles, activeId);
    }

    /**
     * 保存全局设置，并把相关改动落到当前生效的那条配置上。
     *
     * <p>密钥只有<b>真的变了</b>才写进新的 {@code credentialRevision}。
     * 每次保存都递增的话，用户改一下网页搜索开关就会让密钥槽搬家，
     * 而旧槽永远留着没人清理。
     */
    public synchronized void save(SessionConfig config) throws Exception {
        save(config, false);
    }

    /**
     * 与 {@link #save} 完全相同，但**同步落盘**（{@code commit()} 而不是 {@code apply()}）。
     *
     * <h2>为什么必须有这一条（用户：「当直接大退软件，这些保存恢复到未修改的样子」）</h2>
     *
     * {@code apply()} 只保证**内存**里立刻可见，磁盘写是丢给后台线程的。
     * 从最近任务里把应用**划掉**时进程被直接杀掉，那时没人等这个后台写，
     * 于是「改完设置 → 大退 → 重开」看到的就是上一次的值。这不是某个字段漏了保存，
     * 而是<b>所有</b>设置共用的写入方式本身不可靠 —— 本类此前 14 处写入全是
     * {@code apply()}、一处 {@code commit()} 都没有。
     *
     * <p>只在<b>用户明确改设置</b>这条路径上用它，并且调用方必须在后台线程
     * （见 {@code WorkspaceViewModel.persistSettingsNow}）：{@code commit()} 会阻塞到
     * 磁盘写完，压在点击那一帧上就是一次肉眼可见的卡顿。
     *
     * <p>{@link #save} 保持 {@code apply()} 不变：它的调用点（{@code configure()}）
     * 可能在主线程上，而且它写的是"与引擎对齐"的当前状态 —— 用户真正改的那一次
     * 已经由本方法落盘了。
     */
    public synchronized void saveDurable(SessionConfig config) throws Exception {
        save(config, true);
    }

    private synchronized void save(SessionConfig config, boolean durable) throws Exception {
        // 先清理再落盘：这一步在写 prefs 之前抛出，磁盘上不会留下半份状态。
        config.customSystemPrompt = sanitizePrompt(config.customSystemPrompt);

        List<ApiProfile> profiles = getProfiles();
        ApiProfile profile = findProfile(profiles, config.profileId);
        if (profile == null) profile = findProfile(profiles, prefs.getString(Key.ACTIVE_PROFILE, ""));
        if (profile == null) profile = profiles.get(0);

        ApiProfile updated = profile.copy();
        updated.protocol = firstNonBlank(config.protocol, updated.protocol);
        updated.baseUrl = trimToEmpty(config.baseUrl);
        updated.extraHeaders = trimToEmpty(config.extraHeaders);
        updated.defaultModel = firstNonBlank(config.model, updated.defaultModel);
        // 请求头变了也要算一次修改：修订号是「这份配置被改过」的标记，
        // 只认地址的话，改了头却不算改动，缓存里那份旧配置就会被继续用。
        if (!sameEndpoint(profile, updated)
                || !trimToEmpty(profile.extraHeaders).equals(updated.extraHeaders)) {
            updated.revision++;
        }

        String previousKey = apiKeyFor(profile);
        String nextKey = trimToEmpty(config.apiKey);
        if (!nextKey.equals(previousKey)) {
            updated.credentialRevision = profile.credentialRevision + 1;
            secrets.setApiKey(updated.id, updated.credentialRevision, nextKey);
        }

        replaceAt(profiles, updated);
        persistProfiles(profiles, updated.id, durable);
        applyProfile(config, updated, apiKeyFor(updated));
        saveGlobal(config, durable);
    }

    // ------------------------------------------------------------ 会话文件记忆

    public synchronized File getLastSessionFile() {
        String path = prefs.getString(Key.LAST_SESSION, "");
        if (isBlank(path)) return null;
        File file = new File(path.trim());
        return file.isFile() ? file : null;
    }

    public synchronized void setLastSessionFile(File file) {
        prefs.edit().putString(Key.LAST_SESSION, file == null ? "" : file.getAbsolutePath()).apply();
    }

    // ------------------------------------------------------------ 角色卡

    public synchronized JSONArray getRoleCards() {
        try {
            return new JSONArray(prefs.getString(Key.ROLE_CARDS, "[]"));
        } catch (Exception unreadable) {
            // 角色卡坏了就当没有：它不影响会话能否继续，而抛异常会让设置页打不开。
            return new JSONArray();
        }
    }

    public synchronized void saveRoleCards(JSONArray cards, String activeId) {
        prefs.edit()
                .putString(Key.ROLE_CARDS, cards == null ? "[]" : cards.toString())
                .putString(Key.ACTIVE_ROLE_CARD, orEmpty(activeId))
                .apply();
    }

    public synchronized String getActiveRoleCardId() {
        return prefs.getString(Key.ACTIVE_ROLE_CARD, "");
    }

    /**
     * 只写「强制后台保活」这一个开关，供 {@code KeepAliveService} 在自我停止时回调。
     *
     * <p>存在的理由是一个真实的缺陷：{@code KeepAliveService} 曾经自己拼出 prefs 文件名与
     * 键名直接写盘，而它写的是<b>旧版</b> prefs 文件（迁移用的那个只读来源）。结果是
     * 「用户从通知里停掉保活 → 开关并没有被关掉 → 下次启动界面仍显示开启」。
     * 键属于本类，写入就该由本类负责，否则键名一旦改动就会有第二次同样的漂移。
     *
     * <p>只改这一个键、不经过 {@link #save(SessionConfig)}：服务停止时只关心「开关归位」，
     * 重新读写整份设置既多余，也可能把界面尚未保存的草稿覆盖掉。
     */
    public static void setForcedKeepAliveEnabled(Context context, boolean enabled) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Key.FORCED_KEEP_ALIVE, enabled)
                .apply();
    }

    // ------------------------------------------------ 界面侧设置（非 SessionConfig）

    /**
     * 主题模式（{@code system} / {@code light} / {@code dark}）。
     *
     * <p>主题是**纯界面**概念，{@link SessionConfig} 里没有它的位置（引擎不关心
     * 界面长什么样），但那不代表它可以不落盘 —— 用户选的主题重启就丢，
     * 与「设置保存不完善」是同一件事。键由本类拥有，写入也由本类负责，
     * 与 {@link #setForcedKeepAliveEnabled} 同一个约定。
     */
    public static String getThemeMode(Context context, String fallback) {
        if (context == null) return fallback;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(Key.THEME_MODE, "");
        return isBlank(value) ? fallback : value.trim();
    }

    public static void setThemeMode(Context context, String mode) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        // commit() 而不是 apply()：主题是"改完就大退"时最容易被丢的那一类
        // （用户点一下开关就走）。代价是一次几毫秒的磁盘写，调用方在 IO 线程上。
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(Key.THEME_MODE, mode == null ? "" : mode.trim())
                .commit();
    }

    /**
     * 搜索服务的密钥（按服务各存一份），**加密**存放。
     *
     * <p>复用 {@link com.termux.app.zhicode.security.AndroidSecretStore} 的
     * profile 分槽：槽位 id 用 {@code websearch:<服务名>} 这种命名空间前缀，
     * 与真实的 API 配置 id 天然不会撞（那些是 UUID）。
     *
     * <p>为什么不塞进 prefs：那是**明文**。API 配置的密钥走的是加密槽，
     * 搜索服务没有理由比它宽松 —— 同一台设备上两处密钥、两种保护强度是最难解释的。
     * revision 固定 0：这里不需要「换密钥就搬家」那套语义。
     */
    public static void setWebSearchKey(Context context, String provider, String value) {
        if (context == null || isBlank(provider)) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            new com.termux.app.zhicode.security.AndroidSecretStore(app)
                    .setApiKey(webSearchSlot(provider), 0, trimToEmpty(value));
        } catch (Exception ignored) {
            // 与 AndroidSecretStore 的其它调用点一致：加密不可用时**不抛**给界面，
            // 由 getWebSearchKey 读回空值，用户看到的是「没配上」，而不是一次崩溃。
        }
    }

    public static String getWebSearchKey(Context context, String provider) {
        if (context == null || isBlank(provider)) return "";
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            return trimToEmpty(new com.termux.app.zhicode.security.AndroidSecretStore(app)
                    .getApiKey(webSearchSlot(provider), 0));
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static String webSearchSlot(String provider) {
        return "websearch:" + provider.trim().toLowerCase(java.util.Locale.US);
    }

    /**
     * 终端是否走「字符模式」输入。
     *
     * <p>背景：{@code TerminalView} 有两种输入类型（见 {@code onCreateInputConnection}）：
     * <ul>
     *   <li>{@code TYPE_NULL} —— 正常的软件输入法，但少数机型（三星旧键盘等）
     *       会在切换时残留状态；</li>
     *   <li>{@code TYPE_TEXT_VARIATION_VISIBLE_PASSWORD} —— 绕开上一条的 workaround，
     *       代价是**部分输入法把它当成安全/密码键盘**，用户看到的是「调出来的不是
     *       我常用的输入法」。</li>
     * </ul>
     *
     * <p>两者各有代价，没有普适答案，所以做成开关、默认走普通输入法
     * （用户反馈的是「有部分设备访问终端时不是软件输入法而是系统的安全输入法」——
     * 那正是第二个分支的后果）。
     */
    public static boolean getTerminalCharMode(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(Key.TERMINAL_CHAR_MODE, false);
    }

    public static void setTerminalCharMode(Context context, boolean enabled) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        // 同 setThemeMode：同步落盘，避免"改完大退"丢设置。
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Key.TERMINAL_CHAR_MODE, enabled)
                .commit();
    }

    // -------------------------------------------------------------- 搜索服务

    /**
     * 搜索服务列表（RikkaHub 形态）。
     *
     * <p>返回 {@code [服务 JSON 数组, 当前生效 id]}。刻意返回字符串而不是对象数组：
     * 界面侧的模型在 Kotlin 包（{@code compose.model}），引擎侧不该依赖它 ——
     * 中间这层 JSON 就是两边的契约，与 `McpConfigStore` 同一个做法。
     */
    public static String[] readSearchServices(Context context) {
        if (context == null) return new String[]{"[]", ""};
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        android.content.SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new String[]{
                prefs.getString(Key.SEARCH_SERVICES, "[]"),
                prefs.getString(Key.ACTIVE_SEARCH_SERVICE, ""),
        };
    }

    public static void writeSearchServices(Context context, String servicesJson, String activeId) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(Key.SEARCH_SERVICES, servicesJson == null ? "[]" : servicesJson)
                .putString(Key.ACTIVE_SEARCH_SERVICE, activeId == null ? "" : activeId)
                .apply();
    }

    public static boolean isSearchMigrationDone(Context context) {
        if (context == null) return true;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(Key.SEARCH_MIGRATION, false);
    }

    public static void markSearchMigrationDone(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Key.SEARCH_MIGRATION, true)
                .apply();
    }

    /**
     * 某条搜索服务的密钥（**加密**存放，按 service id 分槽）。
     *
     * <p>槽名带 {@code search:} 命名空间：搜索服务 id 与 API 配置 id 都在同一份
     * 加密存储里，没有前缀迟早会撞。
     */
    public static void setSearchServiceKey(Context context, String serviceId, String value) {
        if (context == null || isBlank(serviceId)) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            new com.termux.app.zhicode.security.AndroidSecretStore(app)
                    .setApiKey(searchSlot(serviceId), 0, trimToEmpty(value));
        } catch (Exception ignored) {
            // 与其它调用点一致：加密不可用时不抛给界面，读回空值即可。
        }
    }

    public static String getSearchServiceKey(Context context, String serviceId) {
        if (context == null || isBlank(serviceId)) return "";
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            return trimToEmpty(new com.termux.app.zhicode.security.AndroidSecretStore(app)
                    .getApiKey(searchSlot(serviceId), 0));
        } catch (RuntimeException failure) {
            return "";
        }
    }

    public static void removeSearchServiceKey(Context context, String serviceId) {
        if (context == null || isBlank(serviceId)) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            new com.termux.app.zhicode.security.AndroidSecretStore(app)
                    .removeApiKey(searchSlot(serviceId), 0);
        } catch (RuntimeException ignored) {
        }
    }

    private static String searchSlot(String serviceId) {
        return "search:" + serviceId.trim();
    }

    // ------------------------------------------------------------ 全局字段读写

    /** 从 prefs 读出整份全局设置。默认值就是 {@link SessionConfig} 的字段初值。 */
    private SessionConfig loadGlobal() {
        SessionConfig config = new SessionConfig();
        for (Map.Entry<String, Setting> entry : SETTINGS.entrySet()) {
            readSetting(prefs, entry.getKey(), entry.getValue(), config);
        }

        // double 在 prefs 里只能按位存 long；原样往返，避免精度问题。
        config.autoCompactRatio = Double.longBitsToDouble(
                prefs.getLong(Key.AUTO_COMPACT_RATIO, Double.doubleToRawLongBits(config.autoCompactRatio)));
        migrateCompactionRatio(config);
        return config;
    }

    private void saveGlobal(SessionConfig config) {
        saveGlobal(config, false);
    }

    /**
     * 写整份全局设置表。{@code durable} 为真时同步落盘。
     *
     * <p>这一处是「大退丢设置」的主犯：全部 14 组设置项都在 {@link #SETTINGS} 里，
     * 而它们只由本方法写出；写成 {@code apply()} 时，划掉应用就会整批丢。
     */
    private void saveGlobal(SessionConfig config, boolean durable) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Setting> entry : SETTINGS.entrySet()) {
            writeSetting(editor, entry.getKey(), entry.getValue(), config);
        }
        editor.putLong(Key.AUTO_COMPACT_RATIO, Double.doubleToRawLongBits(config.autoCompactRatio));
        editor.putInt(Key.COMPACTION_VERSION, COMPACTION_LOGIC_VERSION);
        if (durable) editor.commit();
        else editor.apply();
    }

    /** 按字段当前值的类型决定 prefs 的读法，见 {@link Setting#value}。 */
    private static void readSetting(SharedPreferences prefs, String key, Setting setting, SessionConfig config) {
        Object fallback = setting.value(config);
        if (fallback instanceof Boolean) setting.apply(config, prefs.getBoolean(key, (Boolean) fallback));
        else if (fallback instanceof Integer) setting.apply(config, prefs.getInt(key, (Integer) fallback));
        else setting.apply(config, prefs.getString(key, (String) fallback));
    }

    /**
     * 按字段当前值的类型决定 prefs 的写法。
     *
     * <p>类型不认识时直接抛：静默地把一个 double 当成字符串写进去，下次读出来的就是
     * 一个「看起来像数字的文本」，而且不会有任何报错提示。
     */
    private static void writeSetting(SharedPreferences.Editor editor, String key, Setting setting,
                                     SessionConfig config) {
        Object value = setting.value(config);
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value == null || value instanceof String) editor.putString(key, (String) value);
        else throw new IllegalStateException("设置项 " + key + " 的类型没有对应的写入方式: " + value.getClass());
    }

    /**
     * 压缩逻辑第 1 版的默认比例是 0.78，第 2 版改成 1.0（即只在真的满了才压缩）。
     *
     * <p>升级时必须把「用户没动过、仍然是 0.78」的那批值改写过来，否则老用户会一直用
     * 过时的保守比例。用户手工设过的值不动 —— 判断依据正是「等于旧默认值」。
     * 版本号与比例一起写回，避免每次启动都重算一遍。
     */
    private void migrateCompactionRatio(SessionConfig config) {
        if (prefs.getInt(Key.COMPACTION_VERSION, 0) >= COMPACTION_LOGIC_VERSION) return;
        if (Math.abs(config.autoCompactRatio - LEGACY_COMPACT_RATIO) < 0.000001d) {
            config.autoCompactRatio = 1.0d;
        }
        prefs.edit()
                .putInt(Key.COMPACTION_VERSION, COMPACTION_LOGIC_VERSION)
                .putLong(Key.AUTO_COMPACT_RATIO, Double.doubleToRawLongBits(config.autoCompactRatio))
                .apply();
    }

    // ------------------------------------------------------------ profile 列表

    /**
     * 从 prefs 读出配置列表，并修好其中的不一致。
     *
     * <p>只在<b>内容确实变化</b>时才写回：本方法由 {@link #getProfiles()} 调用，而调用方
     * 常在循环里取它；每次都写盘不仅浪费，还会让「读」有机会覆盖别的线程刚写下的内容。
     *
     * <p>这里不插入任何内置记录：列表可以为空。
     */
    private List<ApiProfile> normalizedProfiles() {
        List<ApiProfile> stored = readProfiles();
        // 完全没有记录时才走迁移：这时没有任何东西会丢。
        if (stored.isEmpty()) return migrateLegacyProfile(loadGlobal());

        String activeId = prefs.getString(Key.ACTIVE_PROFILE, "");
        if (findProfile(stored, activeId) == null) activeId = stored.get(0).id;
        boolean changed = !profilesJson(stored).equals(prefs.getString(Key.PROFILES, ""))
                || !activeId.equals(prefs.getString(Key.ACTIVE_PROFILE, ""));
        if (changed) persistProfiles(stored, activeId);
        return stored;
    }

    /**
     * 读配置列表，并按 id 去重（界面上出现两份同名配置只会让人误操作）。
     *
     * <p>关键区分：<b>没有记录</b>与<b>记录损坏</b>是两件事。前者可以安全迁移；
     * 后者若被当成前者，调用方紧接着写回新列表就会抹掉用户的数据。
     * 所以损坏时把原文留档到 {@code api_profiles_v1.corrupt} 并记下原因，
     * 而不是假装没有记录。
     */
    private List<ApiProfile> readProfiles() {
        ArrayList<ApiProfile> profiles = new ArrayList<>();
        String raw = prefs.getString(Key.PROFILES, "");
        if (isBlank(raw)) {
            recordError("");
            return profiles;
        }
        try {
            JSONArray array = new JSONArray(raw);
            Set<String> seenIds = new HashSet<>();
            for (JSONObject json : JsonItems.of(array)) {
                ApiProfile profile = ApiProfile.fromJson(json);
                if (seenIds.add(profile.id)) profiles.add(profile);
            }
            recordError("");
            return profiles;
        } catch (Exception broken) {
            String keptKey = Key.PROFILES + ".corrupt";
            if (!prefs.contains(keptKey)) prefs.edit().putString(keptKey, raw).apply();
            recordError("API 配置解析失败，原文已保存在 " + keptKey + "；原因: " + broken);
            return profiles;
        }
    }

    private void persistProfiles(List<ApiProfile> profiles, String activeId) {
        persistProfiles(profiles, activeId, false);
    }

    /**
     * 写配置列表。{@code durable} 为真时同步落盘（同 {@link #saveDurable} 的理由）。
     */
    private void persistProfiles(List<ApiProfile> profiles, String activeId, boolean durable) {
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putString(Key.PROFILES, profilesJson(profiles))
                .putString(Key.ACTIVE_PROFILE, activeId);
        if (durable) editor.commit();
        else editor.apply();
    }

    private static String profilesJson(List<ApiProfile> profiles) {
        JSONArray array = new JSONArray();
        for (ApiProfile profile : profiles) array.put(profile.toJson());
        return array.toString();
    }

    /**
     * 从「全局设置」迁移出第一份 profile。
     *
     * <p>只在<b>没有任何</b> profile 记录时才会被调用。旧版设置里填过地址或密钥的话，
     * 就把它落成一份「原 API 配置」，免得用户升级后以为配置丢了；什么都没填就一份也不建，
     * 等用户自己新增。
     */
    private List<ApiProfile> migrateLegacyProfile(SessionConfig legacy) {
        ArrayList<ApiProfile> profiles = new ArrayList<>();
        String legacyUrl = trimToEmpty(legacy.baseUrl);
        String legacyKey = trimToEmpty(secrets.getApiKey());

        if (!legacyUrl.isEmpty() || !legacyKey.isEmpty()) {
            ApiProfile profile = new ApiProfile();
            profile.name = "内置 API 配置";
            profile.protocol = firstNonBlank(legacy.protocol, profile.protocol);
            profile.baseUrl = legacyUrl;
            profile.defaultModel = firstNonBlank(legacy.model, profile.defaultModel);
            if (!legacyKey.isEmpty()) {
                try {
                    secrets.setApiKey(profile.id, profile.credentialRevision, legacyKey);
                } catch (Exception keyMoveFailed) {
                    // 密钥没搬过去也不致命：apiKeyFor() 对这条记录会回退读旧槽。
                    recordError("旧 API Key 搬迁失败: " + keyMoveFailed);
                }
            }
            profiles.add(profile);
            prefs.edit().putString(Key.MIGRATED_PROFILE, profile.id).apply();
        }
        persistProfiles(profiles, profiles.isEmpty() ? "" : profiles.get(0).id);
        return profiles;
    }

    // ------------------------------------------------------------ 小工具

    /** 把一条 profile 的字段铺到会话配置上。字段名与 {@link ApiProfile} 一一对应。 */
    private static void applyProfile(SessionConfig config, ApiProfile profile, String apiKey) {
        config.profileId = profile.id;
        config.profileRevision = profile.revision;
        config.credentialRevision = profile.credentialRevision;
        config.protocol = profile.protocol;
        config.baseUrl = profile.baseUrl;
        config.extraHeaders = profile.extraHeaders;
        config.model = profile.defaultModel;
        config.apiKey = apiKey;
    }

    /** 取某 profile 的密钥；该槽为空时，若它是从旧配置迁移来的，回退读旧槽。 */
    private String apiKeyFor(ApiProfile profile) {
        String key = secrets.getApiKey(profile.id, profile.credentialRevision);
        if (!key.isEmpty()) return key;
        boolean migrated = profile.id.equals(prefs.getString(Key.MIGRATED_PROFILE, ""));
        return migrated ? secrets.getApiKey() : "";
    }

    /** 列表里 id 相同的位置；没有就返回 -1。 */
    private static int indexOf(List<ApiProfile> profiles, String id) {
        if (id == null) return -1;
        for (int i = 0; i < profiles.size(); i++) {
            if (id.equals(profiles.get(i).id)) return i;
        }
        return -1;
    }

    private static ApiProfile findProfile(List<ApiProfile> profiles, String id) {
        int index = indexOf(profiles, id);
        return index < 0 ? null : profiles.get(index);
    }

    /** 用 {@code replacement} 顶掉同 id 的那条；没有就追加到末尾。 */
    private static void replaceAt(List<ApiProfile> profiles, ApiProfile replacement) {
        int index = indexOf(profiles, replacement.id);
        if (index < 0) profiles.add(replacement);
        else profiles.set(index, replacement);
    }

    /** 端点是否等价。不等价才算一次新的配置修订。 */
    private static boolean sameEndpoint(ApiProfile left, ApiProfile right) {
        return trimToEmpty(left.protocol).equals(trimToEmpty(right.protocol))
                && trimToEmpty(left.baseUrl).equals(trimToEmpty(right.baseUrl))
                && trimToEmpty(left.defaultModel).equals(trimToEmpty(right.defaultModel));
    }

    /**
     * 规范化用户自定义提示词。
     *
     * <p>统一换行符，并拒绝 NUL 与控制字符：这些字符会一路传到 HTTP 请求体里，
     * 在那儿表现为难以定位的协议错误。早一点在这里报出来，错误信息才对得上原因。
     */
    private static String sanitizePrompt(String value) {
        String input = orEmpty(value).replace("\r\n", "\n").replace('\r', '\n');
        if (input.indexOf('\0') >= 0) throw new IllegalArgumentException("自定义提示词不能包含 NUL 字符");

        StringBuilder clean = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t') {
                throw new IllegalArgumentException("自定义提示词包含不支持的控制字符");
            }
            clean.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        return clean.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------ 诊断

    private volatile String lastError = "";

    /**
     * 最近一次加载失败的原因；空串表示上次加载没有出错。
     *
     * <p>没有这条信息，「配置读不出来」与「还没有配置」在界面上长得一模一样。
     */
    public String lastError() {
        return lastError;
    }

    private void recordError(String reason) {
        lastError = orEmpty(reason);
    }
}
