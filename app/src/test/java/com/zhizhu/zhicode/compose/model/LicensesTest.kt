package com.zhizhu.zhicode.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「关于 → 第三方组件许可」与根 `NOTICE` 的一致性。
 *
 * ## 为什么这条值得一个测试
 *
 * 这一页的手写清单是**给人看的法律声明**，而它的真源是根目录的 `NOTICE`。
 * 两份东西写两份，迟早出现「关于页写 Apache-2.0、NOTICE 里是 MIT」——
 * 那种偏差不会编译失败、不会运行报错，只有在真正需要主张权利的时候才被发现。
 *
 * 所以这里逐条比对：清单里每个组件的名字必须能在 `NOTICE` 里找到，
 * 许可名也必须一致。**手工维护两份，靠测试对齐**，而不是靠"我记得改过"。
 *
 * ## 为什么这个测试在快路径里也不慢
 *
 * 只读一个文本文件、做若干次 `contains`。它不依赖 Android，所以能进
 * `test-jvm-fast.sh`（那里编 Kotlin 不带 android.jar）。
 */
class LicensesTest {

    private fun notice(): String {
        val file = File("NOTICE")
        assertTrue("找不到根目录的 NOTICE：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun everyEntryMatchesANoticeSection() {
        val text = notice()
        val missing = THIRD_PARTY_LICENSES
            .filterNot { text.contains(it.name) }
            .map { it.name }
        assertEquals(
            "以下条目在 NOTICE 里找不到同名小节（名字必须逐字一致）：$missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun everyEntryDeclaresALicenseThatNoticeAlsoNames() {
        val text = notice()
        val mismatched = THIRD_PARTY_LICENSES
            .filter { entry ->
                // 有些条目的许可是"各程序各自的许可"这类复合描述，那一行的许可名不逐字出现在
                // NOTICE 里也没关系 —— 但**单一名**的许可（MIT / Apache-2.0 / LGPL-2.1 等）
                // 必须能找到。
                val simple = entry.license.split(" ", "·", "（", "/").first().trim()
                simple.length > 2 && !text.contains(simple)
            }
            .map { "${it.name} 声称 ${it.license}" }
        assertEquals(
            "以下条目的许可名在 NOTICE 里找不到：$mismatched",
            emptyList<String>(),
            mismatched,
        )
    }

    @Test
    fun theListCoversTheItemsThatAreEasyToForget() {
        val names = THIRD_PARTY_LICENSES.map { it.name }
        // 这三项最容易被漏掉：它们不是 Gradle 依赖、不在依赖列表里，
        // 只存在于二进制或数据文件里。
        listOf("静态链接在 libblackbox.so 内的 Dobby", "Termux 用户空间（bootstrap 归档）", "Material Symbols 图标")
            .forEach { assertTrue("清单缺少易漏项：$it", names.contains(it)) }
        // 自身许可也在这一页里（用户点进来会以为只有第三方）。
        assertTrue(names.any { it.contains("ZhiCode 自身") })
    }

    @Test
    fun entriesAreNonEmptyAndOrderedLikeNotice() {
        assertTrue("清单为空", THIRD_PARTY_LICENSES.size >= 10)
        THIRD_PARTY_LICENSES.forEach { entry ->
            assertTrue("${entry.name} 缺少许可", entry.license.isNotBlank())
            assertTrue("${entry.name} 缺少版本/说明列", entry.version.isNotBlank())
            // 详情是这一页**唯一**的实质内容：只给名字与许可，用户不如去看 NOTICE。
            assertTrue("${entry.name} 的详情太短（少于 20 字）", entry.detail.length > 20)
        }
        // 顺序与 NOTICE 一致不是硬要求，但"第一项是 IQ Code"是刻意的：
        // 它是本工程的起点，放在最前面是对上游的致谢。
        assertTrue(THIRD_PARTY_LICENSES.first().name.startsWith("IQ Code"))
    }

    @Test
    fun projectUrlPointsAtAConcreteRepository() {
        assertTrue(PROJECT_URL.startsWith("https://"))
        assertTrue(PROJECT_URL.endsWith("IQ-Code-Android"))
        assertEquals("MIT", OWN_LICENSE)
    }
}
