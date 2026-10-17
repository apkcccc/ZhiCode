package com.zhizhu.zhicode.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词槽位的「空串 = 用默认」语义。
 *
 * ## 为什么这条要单测
 *
 * 三层代码（设置页、`SessionConfig`、引擎）都靠同一个约定：空串表示"用户没改过"。
 * 任何一层把空串当成"用户清空了，那就发空字符串给模型"，表现都只是
 * 「模型忽然不按 _/init_ 的规矩办事了」—— 而这种偏差不会抛异常、不会进日志，
 * 只会让输出质量慢慢变差。所以约定本身要被钉住。
 */
class PromptSlotsTest {

    @Test
    fun everySlotHasNonEmptyDefaults() {
        PromptSlot.entries.forEach { slot ->
            assertTrue("${slot.id} 的默认文案是空的", slot.defaultValue.isNotBlank())
            assertTrue("${slot.id} 缺少标题", slot.title.isNotBlank())
            assertTrue("${slot.id} 缺少说明", slot.detail.isNotBlank())
            assertTrue("${slot.id} 没有说明可用变量", slot.variables.isNotBlank())
        }
    }

    @Test
    fun emptyCustomizationFallsBackToTheDefault() {
        PromptSlot.entries.forEach { slot ->
            assertEquals(slot.defaultValue, slot.resolve(null))
            assertEquals(slot.defaultValue, slot.resolve(""))
            // 只有空白也算"没改过"：编辑框里打一个空格不该让整段默认文案消失。
            assertEquals(slot.defaultValue, slot.resolve("   \n\t "))
        }
    }

    @Test
    fun customTextWinsAndIsTrimmed() {
        PromptSlot.entries.forEach { slot ->
            assertEquals("自定义", slot.resolve("  自定义  "))
        }
    }

    @Test
    fun idsAreStableAndUnique() {
        val ids = PromptSlot.entries.map { it.id }
        assertEquals("id 有重复", ids.size, ids.toSet().size)
        // 落盘键，改名等于丢掉用户已填的内容 —— 这三个字面量是**契约**。
        assertEquals(listOf("init", "compact_system", "compact_head"), ids)
        assertNotNull(PromptSlot.byId("init"))
        assertNull(PromptSlot.byId("nope"))
    }

    @Test
    fun defaultsCarryTheSafetyClausesThatMustNotBeLost() {
        // /init 的默认文案刻意限制调研范围：改宽之后 /init 会变成普通任务，耗时也长得多。
        val init = PromptSlot.INIT.defaultValue
        assertTrue(init.contains("Do not enter plan mode"))
        assertTrue(init.contains("ZhiCode.md"))

        // 摘要请求一旦真的调了工具，返回的就不是摘要而是一段工具调用，压缩会直接失败。
        assertTrue(
            PromptSlot.COMPACT_SYSTEM.defaultValue.contains("never call tools"),
        )
        // 开场段必须仍然说清「只输出文字」「格式是 analysis + summary」。
        val head = PromptSlot.COMPACT_HEAD.defaultValue
        assertTrue(head.contains("TEXT ONLY"))
        assertTrue(head.contains("<analysis>"))
        assertTrue(head.contains("<summary>"))
    }

    @Test
    fun previewCollapsesWhitespaceToOneLine() {
        // 多行提示词压成一行才看得出实际内容，也才不会把行尾箭头顶上去。
        val flat = PromptSlot.preview("第一行\n\n 第二行\t第三行 ", maxChars = 100)
        assertFalse("预览里仍有换行", flat.contains('\n'))
        assertFalse("预览里仍有制表符", flat.contains('\t'))
        assertTrue(flat.startsWith("第一行 第二行"))

        // 超长要截断并给出省略号。
        val long = PromptSlot.preview("x".repeat(200), maxChars = 20)
        assertTrue(long.endsWith("…"))
        assertEquals(21, long.length)

        // 空内容是一个**可见**的占位，不是一个空白行。
        assertEquals("（空）", PromptSlot.preview("   "))
    }
}
