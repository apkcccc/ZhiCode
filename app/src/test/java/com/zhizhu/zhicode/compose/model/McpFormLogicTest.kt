package com.zhizhu.zhicode.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 表单与列表的纯逻辑：未保存判定、筛选、端点摘要、键值对序列化。
 *
 * 这几条对应的都是"只看界面会以为没问题"的场景：
 * - 改了字段却不提示未保存 → 返回时悄悄丢掉一段配置；
 * - 筛出 0 条说成"没有服务器" → 用户以为配置丢了；
 * - 两台同名服务器（项目级 / 用户级各一份）只看名字分不出来。
 */
class McpFormLogicTest {

    private fun draft(
        name: String = "filesystem",
        type: McpType = McpType.STDIO,
        command: String = "npx",
        argsText: String = "-y\n@modelcontextprotocol/server-filesystem\n/tmp",
        url: String = "",
        scope: McpScope = McpScope.USER,
        enabled: Boolean = true,
    ) = McpServerDraft(
        originalName = null,
        name = name,
        type = type,
        command = command,
        argsText = argsText,
        url = url,
        scope = scope,
        enabled = enabled,
    )

    @Test
    fun freshlyOpenedDraftIsClean() {
        // 默认 savedFingerprint 取的是构造时那组字段，所以新建的草稿**不是**脏的。
        // 若这里失败，打开添加表单就会立刻问"要放弃修改吗"。
        assertFalse(draft().dirty)
    }

    @Test
    fun anyFieldChangeMarksDirty() {
        val base = draft()
        assertTrue(base.copy(name = "other").dirty)
        assertTrue(base.copy(command = "/usr/bin/node").dirty)
        assertTrue(base.copy(argsText = "").dirty)
        assertTrue(base.copy(scope = McpScope.PROJECT).dirty)
        assertTrue(base.copy(enabled = false).dirty)
        assertTrue(base.copy(envText = """{"A":"1"}""").dirty)
    }

    @Test
    fun revertingChangesClearsDirty() {
        val base = draft()
        // 改回原值就算干净：指纹比较的是"现在 vs 打开时"，不是"有没有碰过"。
        assertFalse(base.copy(name = "other").copy(name = base.name).dirty)
    }

    @Test
    fun endpointLabelShowsCommandForStdio() {
        val label = draft().endpointLabel
        assertTrue(label.contains("npx"))
        assertTrue(label.contains("server-filesystem"))
    }

    @Test
    fun endpointLabelFallsBackWhenEmpty() {
        assertEquals("未填命令", draft(command = "").endpointLabel)
        assertEquals(
            "未填地址",
            draft(type = McpType.HTTP, command = "", url = "").endpointLabel,
        )
    }

    @Test
    fun validationBlocksIncompleteServers() {
        assertFalse(draft(name = "").saveable)
        assertFalse(draft(command = "").saveable)
        assertFalse(draft(type = McpType.HTTP, command = "", url = "").saveable)
        assertTrue(draft(type = McpType.HTTP, command = "", url = "http://127.0.0.1:8787/mcp").saveable)
        // env / headers 是原样 JSON 文本：半截的 JSON 必须报错而不是静默存进去。
        assertFalse(draft().copy(envText = "{oops").saveable)
        assertTrue(draft().copy(envText = """{"A":"1"}""").saveable)
    }

    @Test
    fun queryFiltersByNameCommandAndUrl() {
        val servers = listOf(
            McpServer("filesystem", McpType.STDIO, "npx", "", 2, McpScope.USER, true),
            McpServer("github", McpType.HTTP, "", "https://api.githubcopilot.com/mcp", 0, McpScope.PROJECT, false),
        )
        assertEquals(listOf("github"), servers.filterByQuery("github").map { it.name })
        // 命令与地址也参与匹配（用户记得命令、不一定记得名字）。
        assertEquals(listOf("filesystem"), servers.filterByQuery("npx").map { it.name })
        assertEquals(listOf("github"), servers.filterByQuery("githubcopilot").map { it.name })
        // 空筛选词 = 全部，不是"零条"。
        assertEquals(2, servers.filterByQuery("").size)
        assertEquals(0, servers.filterByQuery("nothing-here").size)
    }
}
