package com.termux.app.zhicode.api;

import com.termux.app.zhicode.api.zcode.ZcodeWire;

/**
 * 本工程支持的传输协议，以及它们的线上名字。
 *
 * <h3>为什么是枚举</h3>
 * 在这之前，「协议名 → 传输实现」和「协议名 → 目录端点」是两张各自维护的
 * if 链，各自内联字符串。多一处内联就多一处写错名字的机会，而写错的表现是
 * 「报文格式对不上、服务端报解析错误」，很难联想到是协议名字打错了。
 * 收到一个枚举里之后，「认识的协议」只有一处定义。
 *
 * <h3>{@link #wireName()} 是持久化字符串</h3>
 * 它被写进用户设置（{@code api_profiles_v1} 里每个配置的 {@code protocol} 字段）
 * 和会话内存模型，所以这些字面量是**协议/历史语义的一部分**，不能随手改：
 * 改了之后旧配置会被判成「未知协议」而拒绝加载。
 *
 * <h3>别名</h3>
 * 只有一个真正的别名：{@code openai-compatible}（自建/第三方网关的常见写法）。
 * 它的报文格式与 {@link #OPENAI_CHAT} 完全一致，所以归到同一个枚举值，
 * 而不是另建一项 —— 另建一项会让 {@code switch} 出现永远走不到的分支。
 *
 * <h3>为什么 {@code codex-responses} 从别名升成了一等值</h3>
 * 它原先也是按别名处理的，理由是"报文格式与主名一致"。那个理由**不成立**：
 * 它与 {@link #OPENAI_RESPONSES} 在四处都不同 ——
 * <ul>
 *   <li>端点：{@code /responses} 而不是 {@code /v1/responses}；</li>
 *   <li>UA：自称 {@code codex_cli_rs/<ver>}；</li>
 *   <li>额外四个关联头：{@code originator} / {@code session-id} / {@code thread-id} /
 *       {@code x-client-request-id}；</li>
 *   <li>请求体：必须 {@code store:false}，且<b>不能</b>发 {@code max_output_tokens}。</li>
 * </ul>
 * 而 {@link ApiEndpointResolver#modelCatalogEndpoint} 还要为它单独排除模型目录
 * （Codex 后端没有 {@code /v1/models}）—— 别名做不到这一点。
 *
 * <p>传输实现仍然共用（{@link ModelProviders} 把两者都指向
 * {@code OpenAIResponsesProvider}），但那个类**必须**重读原始线上名才能分支，
 * 所以在枚举里也如实各占一项：让"哪些协议名是认识的"与"它们各自怎么走"
 * 都只有一处定义，而不是一半在枚举、一半靠 {@code equals} 猜。
 */
enum ApiProtocol {

    ANTHROPIC("anthropic"),
    OPENAI_CHAT("openai-chat"),
    OPENAI_RESPONSES("openai-responses"),

    /** Codex 变体。与 {@link #OPENAI_RESPONSES} 共用传输实现，但线上行为不同。 */
    CODEX_RESPONSES(OpenAIResponsesProvider.WIRE_CODEX_RESPONSES),

    /**
     * ZCode：Anthropic Messages 形状，但网关地址与身份头**全部来自用户配置**。
     *
     * <p>报文与事件流与 {@link #ANTHROPIC} 一致（因此复用它的
     * {@code StreamDecoder}），差别只在连接与请求头。之所以仍单列一项：
     * 它要额外读 {@code extraHeaders}、模型目录也不走 {@code /v1/models}，
     * 而且用户需要在下拉里看到自己在用哪一家。
     */
    ZCODE(ZcodeWire.WIRE_NAME),

    /**
     * **调试用**：不发网络请求，按脚本产出回复（见 {@link DebugScriptedProvider}）。
     *
     * <p>它是一个正常收录的协议值，所以能被写进一条普通的 API 配置记录里、
     * 也能被正常选中与切换；**但只有 debug 构建允许它真的跑起来** ——
     * {@link ModelProviders} 在 release 里对这个值照旧报「没有实现」，
     * 于是发布包即使读到了这样一条配置也只会明确失败，而不是偷偷走一条假路径。
     */
    DEBUG_SCRIPTED(DebugScriptedProvider.WIRE_NAME);

    /** 与 {@link #OPENAI_CHAT} 同义的历史写法（自建/第三方网关常用）。 */
    static final String ALIAS_OPENAI_COMPATIBLE = "openai-compatible";

    private final String wireName;

    ApiProtocol(String wireName) {
        this.wireName = wireName;
    }

    /** 写进设置、发给服务端判定用的字符串。 */
    String wireName() {
        return wireName;
    }

    /**
     * 把设置里读到的字符串解析成协议。
     *
     * <p>比较是**精确**的：不 trim、不忽略大小写。用户配置里出现
     * {@code " anthropic"} 这种值时，与其猜他的意思，不如按未知协议报错 ——
     * 静默接受一个大小写不同的名字，会让人以为「两种写法都行」。
     *
     * @return 对应协议；{@code null} 或未收录的写法返回 {@code null}
     */
    static ApiProtocol fromWire(String wire) {
        if (wire == null) return null;
        for (ApiProtocol protocol : values()) {
            if (protocol.wireName.equals(wire)) return protocol;
        }
        if (ALIAS_OPENAI_COMPATIBLE.equals(wire)) return OPENAI_CHAT;
        return null;
    }
}
