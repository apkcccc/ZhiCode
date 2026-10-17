package com.zhizhu.zhicode.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 品牌口径：界面与引擎源码里**不许**再出现旧称。
 *
 * ## 为什么这条值得一个测试
 *
 * 改名是一次 grep 就能漏掉一半的工作：漏掉的那一条不会编译失败、不会运行报错，
 * 只会在某个用户点开某个弹窗时冒出来一个旧名字。而这类"只有肉眼能发现"的偏差
 * 在这个仓库里已经出现过好几次（见 `ToolText` 顶部关于工具名的说明）。
 *
 * 所以把口径写成断言：扫 `app/src/main/java` 下的 `.kt` / `.java`，
 * 出现旧称就失败，并**逐文件逐行**列出位置。
 *
 * ## 为什么扫源码而不是扫资源
 *
 * 本工程的界面文案**不在** `res/values/strings.xml` 里（Compose 直接写中文字面量，
 * 见各页面的 KDoc），所以扫资源等于什么都没扫。
 *
 * ## 为什么这个测试在快路径里也不慢
 *
 * 只读 ~300 个文本文件、做一次 `contains`，毫秒级。它不依赖任何 Android 类，
 * 所以能进 `test-jvm-fast.sh`（那里编 Kotlin 时**不带 android.jar**，
 * 带了的文件根本编不过 —— 这同时证明了本测试与 Android 无关）。
 */
class BrandTextTest {

    /** 旧称。写在常量里而不是散在断言中：将来若再改口径，只动这一行。 */
    private val retiredName = "智蛛"

    @Test
    fun mainSourcesNoLongerUseTheRetiredBrand() {
        val root = File("app/src/main/java")
        // 快路径从工程根目录起跑；万一从别处跑，先给出可读的失败原因，
        // 而不是让 forEachLine 抛一个 FileNotFoundException。
        assertTrue(
            "找不到源码目录：${root.absolutePath}（请在工程根目录运行测试）",
            root.isDirectory,
        )

        val offenders = StringBuilder()
        var scanned = 0
        root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .forEach { file ->
                scanned++
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains(retiredName)) {
                        offenders.append(file.path).append(':').append(index + 1)
                            .append("  ").append(line.trim()).append('\n')
                    }
                }
            }

        // 「一个文件都没扫到」与「扫到了但没有命中」是两件事：前者说明过滤条件写错了
        // （比如扩展名改了），那种情况下这条测试会永远通过 —— 比失败更糟。
        assertTrue("没有扫到任何源码文件，过滤条件失效了", scanned > 100)
        assertEquals(
            "源码里仍在使用旧称「$retiredName」：\n$offenders",
            0,
            offenders.length,
        )
    }

    /** `README.md` 与 `docs/` 是发行物的一部分，同样按同一口径要求。 */
    @Test
    fun docsNoLongerUseTheRetiredBrand() {
        val targets = buildList {
            File("README.md").takeIf { it.isFile }?.let { add(it) }
            File("docs").takeIf { it.isDirectory }?.let { dir ->
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.forEach { add(it) }
            }
        }
        assertTrue("没找到 README.md 或 docs/", targets.isNotEmpty())

        val offenders = targets.filter { file ->
            file.readText().contains(retiredName)
        }.map { it.path }

        assertTrue("文档里仍在使用旧称「$retiredName」：$offenders", offenders.isEmpty())
    }
}
