package com.zhizhu.zhicode.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题预设与取值约束 —— **纯逻辑**部分。
 *
 * 这些性质出错都**不会编译失败**，表现只是"某个颜色不对劲"或"缩放滑到 200% 之后
 * 顶栏按钮消失" —— 前者要人肉看，后者只在特定机型上出现。所以钉在这里。
 *
 * <p>`ThemeSettings` 那一层的默认值与推导在 `ThemeSettingsTest` 里：它要 import
 * `model/ThemeMode`，而那个文件牵着一整套界面状态类型，进不了这条秒级快路径。
 */
class ThemeSpecTest {

    @Test
    fun presetIdsAreStableAndUnique() {
        val ids = THEME_PRESETS.map { it.id }
        assertEquals("预设 id 有重复", ids.size, ids.toSet().size)
        // id 是落盘键：改名等于让老用户的选择失效（读不到就回落默认）。
        // 这一条是契约，加预设只能往后加。
        assertEquals(
            listOf("default", "green", "purple", "amber", "coral", "sakura", "amoled"),
            ids,
        )
        // 第一个必须是默认项：`presetById` 找不到时回落它，用户点进来第一眼也该是它。
        assertEquals("default", THEME_PRESETS.first().id)
    }

    @Test
    fun unknownPresetFallsBackInsteadOfThrowing() {
        // 老版本写下的 id 读不到是常态，不能抛。
        assertEquals(THEME_PRESETS.first(), presetById(null))
        assertEquals(THEME_PRESETS.first(), presetById(""))
        assertEquals(THEME_PRESETS.first(), presetById("不存在的预设"))
        assertEquals("purple", presetById("purple").id)
    }

    @Test
    fun everyPresetHasAFullyOpaqueSeed() {
        THEME_PRESETS.forEach { preset ->
            // alpha 为 0 的种子色会让生成出来的整套色板变成透明，
            // 而那种崩法在界面上表现为"整个应用变白"，不查不知道是这里。
            assertEquals(
                "${preset.id} 的种子色不是不透明的：${preset.hex}",
                0xFF,
                ((preset.seedArgb shr 24) and 0xFF).toInt(),
            )
            assertEquals("${preset.id} 的 hex 长度不对", 7, preset.hex.length)
            assertTrue("${preset.id} 名字为空", preset.name.isNotBlank())
        }
    }

    @Test
    fun onlyTheAmoledPresetOverridesTheDarkBackdrop() {
        // 其它预设一律沿用工程既有的层级色：多一个"自带背板"的预设就意味着
        // 又多了一套要维护的对比度关系。
        val withBackdrop = THEME_PRESETS.filter { it.darkBackdropArgb != null }
        assertEquals(1, withBackdrop.size)
        assertEquals("amoled", withBackdrop.first().id)
        assertEquals(0xFF000000L, withBackdrop.first().darkBackdropArgb)
        assertNull(THEME_PRESETS.first { it.id == "default" }.darkBackdropArgb)
    }

    @Test
    fun uiScaleIsClampedAndSurvivesNaN() {
        assertEquals(UI_SCALE_MIN, clampUiScale(0.1f))
        assertEquals(UI_SCALE_MAX, clampUiScale(9f))
        assertEquals(1.0f, clampUiScale(1.0f))
        // Slider 的中间帧可能是 NaN —— 放过去会让 LocalDensity 出一套 NaN 尺寸，
        // 整屏测不出来（而不是画歪，是彻底不画）。
        assertEquals(UI_SCALE_DEFAULT, clampUiScale(Float.NaN))
    }

    @Test
    fun uiScaleFormatsAsIntegerPercent() {
        assertEquals("100%", formatUiScale(1.0f))
        assertEquals("85%", formatUiScale(UI_SCALE_MIN))
        assertEquals("115%", formatUiScale(UI_SCALE_MAX))
        // 超出范围也先夹再格式化：显示的数字必须与真正生效的缩放一致。
        assertEquals("115%", formatUiScale(3f))
    }

    @Test
    fun hexIsUppercaseSixDigits() {
        // 预览卡与调试页直接显示它，大小写混用会让同一个颜色看起来像两个。
        assertEquals("#3482FF", THEME_PRESETS.first().hex)
        assertTrue(ThemePreset("t", "t", 0xFF00BCD4L).hex == "#00BCD4")
    }
}
