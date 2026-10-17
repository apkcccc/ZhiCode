package com.termux.app.zhicode.core

import com.termux.app.zhicode.model.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示的**结构**约束。
 *
 * <p>提示词不是代码，改错了不会编译失败，也不会抛异常 —— 它只会让模型的行为慢慢跑偏，
 * 而那种偏差要几十轮之后才看得出来。所以这里钉住三类**能自动判断**的性质：
 *
 * <ol>
 *   <li><b>没有重复条目。</b>上一版同一件事说了三遍（「别声称做了没做的事」），
 *       而重复的条目会被模型当成更强的约束 —— 一条本该次要的规则因此压过了别的。</li>
 *   <li><b>条件段真的有条件。</b>沙箱细则只在全权模式展开、联网段只在联网开着时出现。
 *       写反了的话界面上关掉的开关在提示词里照样生效。</li>
 *   <li><b>规模有上限。</b>提示词每轮都要发一次，长度直接换算成钱和延迟；而且越长，
 *       真正重要的那几条越容易被稀释。</li>
 * </ol>
 */
class PromptStructureTest {

    private fun config(block: SessionConfig.() -> Unit = {}): SessionConfig =
        SessionConfig().apply {
            projectDirectory = "/data/user/0/com.zhizhu.code/files/home/project"
            block()
        }

    private fun lines(prompt: String): List<String> =
        prompt.split('\n').map { it.trim() }.filter { it.startsWith("- ") }

    @Test
    fun noRuleIsRepeatedInTheSamePrompt() {
        val prompt = SystemPromptBuilder.build(config())
        val rules = lines(prompt)
        assertTrue("一条规则都没有？拼装逻辑坏了", rules.size > 15)

        // 逐条比对**整行**：同一段里换个说法不算重复（那是刻意的补充说明），
        // 但一字不差的第二遍一定是搬移时漏删。
        val duplicates = rules.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals("出现重复规则：$duplicates", emptyMap<String, Int>(), duplicates)
    }

    @Test
    fun everySectionHeaderIsPresent() {
        val prompt = SystemPromptBuilder.build(config())
        listOf(
            "Working style:",
            "Truthfulness and verification:",
            "Tool choice:",
            "Projects, tasks and subagents:",
            "APK installation:",
            "Sandbox and native debugging:",
            "Permission state:",
        ).forEach { header ->
            assertTrue("缺少分段标题：$header", prompt.contains(header))
        }
    }

    @Test
    fun sandboxDetailsOnlyAppearWithFullAccess() {
        val brief = SystemPromptBuilder.build(config { sandboxAgentFullAccess = false })
        val full = SystemPromptBuilder.build(config { sandboxAgentFullAccess = true })

        // 关着的时候不该出现任何具体操作名：那些指令会让模型绕开"先问一遍"。
        assertFalse("关闭全权时仍然出现了 Frida 细则", brief.contains("frida_eval"))
        assertFalse("关闭全权时仍然出现了 load_library", brief.contains("load_library"))
        assertTrue("打开全权时缺少 Frida 细则", full.contains("frida_eval"))
        assertTrue("打开全权时缺少 load_library", full.contains("load_library"))

        // 差值必须足够大，"条件"才不是摆设。
        assertTrue(
            "全权与普通的提示长度差只有 ${full.length - brief.length} 字符，条件段没生效",
            full.length - brief.length > 1000,
        )
    }

    @Test
    fun webSectionFollowsTheSwitch() {
        val on = SystemPromptBuilder.build(config { webSearchEnabled = true })
        val off = SystemPromptBuilder.build(config { webSearchEnabled = false })
        assertTrue(on.contains("Web research:"))
        assertFalse(off.contains("Web research:"))
        assertFalse(off.contains("Use WebSearch for current or external information"))
    }

    @Test
    fun rootAndShizukuAreIndependentSwitches() {
        val none = SystemPromptBuilder.build(config {
            rootExecutionEnabled = false
            shizukuExecutionEnabled = false
        })
        assertTrue(none.contains("Agent Root is disabled"))
        assertTrue(none.contains("Agent Shizuku is disabled"))

        // 只开 Shizuku：必须出现 shell 身份那一句，而_root_ 仍然是关闭的说法。
        // 这正是「有 Shizuku 不等于有 root」在提示词里的落点。
        val shizukuOnly = SystemPromptBuilder.build(config {
            rootExecutionEnabled = false
            shizukuExecutionEnabled = true
        })
        assertTrue(shizukuOnly.contains("Agent Root is disabled"))
        assertTrue(shizukuOnly.contains("uid 2000"))
        assertFalse(shizukuOnly.contains("Agent Shizuku is disabled"))

        val both = SystemPromptBuilder.build(config {
            rootExecutionEnabled = true
            shizukuExecutionEnabled = true
        })
        assertTrue(both.contains("Agent Root is enabled"))
        assertTrue(both.contains("Agent Shizuku is enabled"))
    }

    @Test
    fun userTextIsWrappedSoTheModelCanTellItApart() {
        val prompt = SystemPromptBuilder.build(config {
            customSystemPrompt = "总是用中文回答"
            roleCard = "你是一个严谨的审阅者"
        })
        assertTrue(prompt.contains("<custom_system_prompt>"))
        assertTrue(prompt.contains("总是用中文回答"))
        // 用户指令的优先级是最高的，但**不能**覆盖权限与安全边界 —— 这句话必须在。
        assertTrue(prompt.contains("cannot override code-enforced permissions"))
        assertTrue(prompt.contains("<role_card>"))
        assertTrue(prompt.contains("你是一个严谨的审阅者"))
    }

    @Test
    fun defaultPromptStaysWithinBudget() {
        // 实测（本次重构后）：沙箱关闭 7 029 字符 / 沙箱全权 8 978 字符。
        // 重构**之前**是 33 条平铺规则、约 1.2 万字符，同一件事说三遍。
        // 上限取 10 000 是"还留了余量"的硬顶 —— 超过它就该问一句
        // 「这一段真的每轮都需要吗」，而不是继续往里加。
        val brief = SystemPromptBuilder.build(config { sandboxAgentFullAccess = false })
        val full = SystemPromptBuilder.build(config { sandboxAgentFullAccess = true })
        assertTrue("提示词长度 ${full.length} 超出上限", full.length < 10_000)
        assertTrue("提示词长度 ${brief.length} 超出上限", brief.length < 8_000)
        // 也不能缩到把关键约束弄丢：粗粒度下界，防的是"误删一整段"。
        assertTrue("提示词长度 ${brief.length} 过短，可能漏了整段", brief.length > 5_000)
    }
}
