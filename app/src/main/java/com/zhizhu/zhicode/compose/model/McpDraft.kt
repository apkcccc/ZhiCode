package com.zhizhu.zhicode.compose.model

/**
 * MCP 表单的**纯逻辑**：连接方式、作用范围、草稿与校验。
 *
 * ## 为什么单独一个文件
 *
 * 这些类型不依赖任何 `Ui*` 状态（不含 `WorkspaceUiState`、不碰磁盘、不碰 Android），
 * 所以能被 `test-jvm-fast.sh` 直接编译并断言 —— 未保存判定、筛选、端点摘要这些
 * "写错了不会编译失败"的规则只能靠单测钉住。放在 `SettingsModels.kt` 里就做不到：
 * 那个文件引用了 `WorkspaceUiState`，快路径编不动它。
 *
 * 界面状态（`McpConfigState` / `McpServerStatus` / `McpToolInfo`）仍在 `SettingsModels.kt`。
 */


/** MCP 服务器的连接方式。取值必须与引擎 `McpConfigStore.Server.type` 一致。 */
enum class McpType(val value: String, val label: String) {
    STDIO("stdio", "标准输入输出（stdio）"),
    HTTP("http", "Streamable HTTP"),
    SSE("sse", "旧版 SSE"),
    ;

    val needsCommand: Boolean get() = this == STDIO
}

/** MCP 服务器的作用范围。 */
enum class McpScope(val value: String, val label: String) {
    USER("user", "用户级（所有项目）"),
    PROJECT("project", "项目级（当前项目）"),
}


/** MCP 配置列表里的一条。[argsText] 是编辑态用的多行文本，展示时不用它。 */
data class McpServer(
    val name: String,
    val type: McpType,
    val command: String,
    val url: String,
    val argCount: Int,
    val scope: McpScope,
    val enabled: Boolean,
) {
    /** 列表行的副标题：让用户一眼看出这是本地进程还是远端地址。 */
    val summary: String
        get() = buildString {
            append(type.label)
            if (type.needsCommand) {
                append(" · ").append(command.ifBlank { "未填命令" })
                if (argCount > 0) append("（").append(argCount).append(" 个参数）")
            } else {
                append(" · ").append(url.ifBlank { "未填地址" })
            }
            append(" · ").append(scope.label)
        }
}

/**
 * 按筛选词过滤服务器（名称 / 命令 / 地址 / 传输方式都参与）。
 *
 * 抽成纯函数放在这里而不是写成 `McpConfigState` 的一个属性：这样快路径单测
 * 能直接断言它，不需要把整个 `WorkspaceUiState` 拖进编译。
 */
fun List<McpServer>.filterByQuery(query: String): List<McpServer> {
    if (query.isBlank()) return this
    return filter {
        it.name.contains(query, ignoreCase = true) ||
            it.command.contains(query, ignoreCase = true) ||
            it.url.contains(query, ignoreCase = true) ||
            it.type.label.contains(query, ignoreCase = true)
    }
}

/**
 * 新增 / 编辑 MCP 服务器的草稿。
 *
 * [argsText] / [envText] / [headersText] 都是**原始文本**而不是解析后的结构：
 * 用户正在打字时 JSON 经常是半截的，边打边解析会把人卡死（刚输入 `{` 就报错）。
 * 所以校验只发生在保存时，见 [envError] / [headersError]。
 */
data class McpServerDraft(
    val originalName: String?,
    val name: String = "",
    val type: McpType = McpType.STDIO,
    val command: String = "",
    val argsText: String = "",
    val url: String = "",
    val envText: String = "",
    val headersText: String = "",
    val scope: McpScope = McpScope.USER,
    val enabled: Boolean = true,
    /**
     * 打开表单那一刻的字段指纹（默认等于当前值 = 干净）。
     *
     * 只用于判断「有没有未保存的改动」：返回 / 关闭前先问一次，见 [dirty]。
     * 不用"用户改过就置脏"的布尔标志：那种标志在保存成功后必须由调用方记得清掉，
     * 漏一处就会让页面永远以为还有脏数据。
     */
    val savedFingerprint: String = fingerprintOf(name, type, command, argsText, url, envText, headersText, scope, enabled),
) {
    val isEditing: Boolean get() = originalName != null

    /** 与打开时相比有改动。 */
    val dirty: Boolean
        get() = fingerprintOf(name, type, command, argsText, url, envText, headersText, scope, enabled) != savedFingerprint

    /**
     * 一行「连到哪」的摘要：stdio 显示命令，HTTP/SSE 显示地址。
     *
     * 列表卡上必须有这一行 —— 两台都叫 `filesystem` 的服务器（项目级 + 用户级各一份）
     * 光看名字与传输方式标签分不出来，而它们的命令往往完全不同。
     */
    val endpointLabel: String
        get() = if (type.needsCommand) {
            buildString {
                // 命令为空时**不再拼参数**：没有可执行文件，`-y xxx` 这一串
                // 只会让人以为已经配好了。
                if (command.isBlank()) {
                    append("未填命令")
                } else {
                    append(command)
                    if (args.isNotEmpty()) append(" ").append(args.joinToString(" "))
                }
            }
        } else {
            url.ifBlank { "未填地址" }
        }

    val nameError: String? get() = if (name.isBlank()) "请填写服务器名称" else null

    val commandError: String?
        get() = if (type.needsCommand && command.isBlank()) "stdio 服务器必须填写启动命令" else null

    val urlError: String?
        get() = if (!type.needsCommand && url.isBlank()) "HTTP/SSE 服务器必须填写 URL" else null

    val envError: String? get() = jsonError(envText)
    val headersError: String? get() = jsonError(headersText)

    val saveable: Boolean
        get() = nameError == null && commandError == null && urlError == null &&
            envError == null && headersError == null

    /** 每行一个参数，与引擎侧 `List<String> args` 对应。 */
    val args: List<String>
        get() = argsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /**
         * 字段指纹。
         *
         * 拼成一个字符串而不是给 `data class` 加 equals 比较：这里要比较的是**打开时那一份**
         * 与**现在这一份**，`equals` 比的是两份实例，得先存一份副本才行。
         * 分隔符用 `\u0000`：用户在任何一个字段里都打不出它，不会出现
         * "两个不同组合拼出同一个串"的假阴性。
         */
        fun fingerprintOf(
            name: String,
            type: McpType,
            command: String,
            argsText: String,
            url: String,
            envText: String,
            headersText: String,
            scope: McpScope,
            enabled: Boolean,
        ): String = listOf(
            name, type.value, command, argsText, url, envText, headersText, scope.value, enabled.toString(),
        ).joinToString("\u0000")

        /**
         * JSON 校验。
         *
         * 空串是合法的（表示"不设置"）；非空但解析失败才报错，
         * 并把失败原因带出来——只说"格式不对"用户不知道哪不对。
         */
        fun jsonError(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return runCatching { org.json.JSONObject(trimmed) }
                .fold(onSuccess = { null }, onFailure = { "JSON 格式错误：${it.message ?: "无法解析"}" })
        }
    }
}
