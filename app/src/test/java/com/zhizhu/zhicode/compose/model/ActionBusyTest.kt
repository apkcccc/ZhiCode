package com.zhizhu.zhicode.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在飞动作的互斥语义（见 [ActionBusy]）。
 *
 * 这几条断言对应的都是真实踩过的问题：双击「粘贴」复制两遍、双击「创建技能」
 * 第二次走进"已存在"分支、双击「删除」第二次报"删除失败"。
 * 判定写错了不会编译失败，只会让用户看到自相矛盾的提示，所以逐条钉住。
 */
class ActionBusyTest {

    @Test
    fun secondAcquireOfSameKeyIsRejected() {
        val busy = ActionBusy()
        assertTrue(busy.acquire("files.paste"))
        // 同一动作在跑时，第二次触发必须被拒绝 —— 这才是"不做两遍"的关键。
        assertFalse(busy.acquire("files.paste"))
    }

    @Test
    fun differentKeysRunInParallel() {
        val busy = ActionBusy()
        assertTrue(busy.acquire("skills.save"))
        assertTrue(busy.acquire("memory.save"))
        assertTrue(busy.isBusy("skills.save"))
        assertTrue(busy.isBusy("memory.save"))
    }

    @Test
    fun releaseAllowsTheNextRun() {
        val busy = ActionBusy()
        assertTrue(busy.acquire("skills.create"))
        busy.release("skills.create")
        assertFalse(busy.isBusy("skills.create"))
        assertTrue(busy.acquire("skills.create"))
    }

    @Test
    fun releaseOfUnknownKeyIsHarmless() {
        val busy = ActionBusy()
        busy.release("never.acquired")
        assertFalse(busy.anyBusy())
    }

    @Test
    fun snapshotIsACopy() {
        val busy = ActionBusy()
        busy.acquire("files.delete")
        val snapshot = busy.snapshot()
        assertEquals(setOf("files.delete"), snapshot)
        // 快照必须脱离登记表：界面拿着旧快照时，后续 acquire 不能改到它。
        busy.acquire("files.paste")
        assertEquals(setOf("files.delete"), snapshot)
        assertEquals(setOf("files.delete", "files.paste"), busy.snapshot())
    }
}
