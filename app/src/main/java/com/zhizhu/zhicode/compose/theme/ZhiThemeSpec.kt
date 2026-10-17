package com.zhizhu.zhicode.compose.theme

/**
 * 主题预设与取值约束 —— **纯逻辑**，刻意不 import 任何 `androidx.compose` 或 `android.*`。
 *
 * ## 为什么这里没有 `ThemeSettings`
 *
 * `ThemeSettings` 要带 `ThemeMode`（深浅三态，住在 `model/UiModels.kt`），
 * 而那个文件牵着一整套界面状态类型。把它们拖进来，这个文件就不能再进
 * `test-jvm-fast.sh` 的纯逻辑快路径（那里编 Kotlin **不带** compose 也不带 android.jar）。
 *
 * 所以分工是：
 * - 本文件 = 预设表 + 取值约束（可被秒级单测覆盖）；
 * - `data/ThemeStore.kt` = `ThemeSettings` 的形状与落盘。
 *
 * 这也顺带守住一条：**预设的颜色值不许依赖运行环境**。一旦某个预设要读
 * `isSystemInDarkTheme()` 之类的信号，它就该被拆出去，而不是把 Android 拖进来。
 */
data class ThemePreset(
    /**
     * 落盘用的稳定 id。
     *
     * <p>**改名等于让老用户的选择失效**（读不到就回落默认），所以它不随展示名变。
     */
    val id: String,
    val name: String,
    /**
     * 种子色 ARGB。Monet 打开时用它生成整套色板
     * （`ThemeController(ColorSchemeMode.MonetXxx, keyColor = …)`）；
     * 关闭时它只用于"自定义强调色"的初值与预览。
     */
    val seedArgb: Long,
    /**
     * 预设**自带的**深色背板色；null 表示沿用本工程既有的层级色。
     *
     * <p>只有纯黑预设会用到它：AMOLED 的观感差别主要在"背板真的是 #000000"，
     * 这一项不能由种子色推出来。
     */
    val darkBackdropArgb: Long? = null,
) {
    /** `#RRGGBB` 形式，给预览与调试页显示用。 */
    val hex: String get() = String.format("#%06X", seedArgb and 0xFFFFFF)
}

/**
 * 预设表。
 *
 * 取值参照 Miuix 示例的 7 个 `KeyColors`（蓝/绿/紫/黄/橙/粉/青）+ rikkahub 的
 * 预设主题思路：每个预设只是**一个种子色**，整套色板交给 Monet 算法生成 ——
 * 手写 30 个色位不可能保证对比度，而生成的可以。
 *
 * 头一项 `default` 刻意是 Miuix 的品牌蓝（`0xFF3482FF`，与示例的 `Blue` 同值）：
 * 用户点进来看到的第一个色块，应当就是"应用原本长什么样"。
 */
val THEME_PRESETS: List<ThemePreset> = listOf(
    ThemePreset("default", "默认蓝", 0xFF3482FF),
    ThemePreset("green", "森野绿", 0xFF36D167),
    ThemePreset("purple", "暮光紫", 0xFF7C4DFF),
    ThemePreset("amber", "暖阳橙", 0xFFFFB21D),
    ThemePreset("coral", "珊瑚红", 0xFFFF5722),
    ThemePreset("sakura", "樱花粉", 0xFFE91E63),
    // 纯黑：种子仍取青色（强调色需要一个颜色），但背板被钉成 #000000。
    ThemePreset("amoled", "纯黑", 0xFF00BCD4, darkBackdropArgb = 0xFF000000L),
)

/** 找不到就回落第一个（`default`），**不抛异常**：老版本写下的 id 读不到是常态。 */
fun presetById(id: String?): ThemePreset =
    THEME_PRESETS.firstOrNull { it.id == id } ?: THEME_PRESETS.first()

/** 界面缩放的合法区间。 */
const val UI_SCALE_MIN = 0.85f
const val UI_SCALE_MAX = 1.15f

/** 默认缩放 —— 1.0 表示"就是现在这样"。 */
const val UI_SCALE_DEFAULT = 1.0f

/**
 * 夹取缩放值。
 *
 * 上界只有 1.15：再大就会在 360dp 宽的机器上把顶栏按钮挤掉（本工程的最小可用宽度
 * 是 24dp 图标 × 4 + 标题）。下界 0.85 是"还能看清"的极限。
 *
 * <p>`NaN` 也在这里被吃掉 —— 它来自 NumberPicker/Slider 的中间帧，放过去会让
 * `LocalDensity` 出一套 NaN 尺寸，整屏测不出来。
 */
fun clampUiScale(value: Float): Float = when {
    value.isNaN() -> UI_SCALE_DEFAULT
    else -> value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
}

/** 缩放显示成百分比（`100%`），四舍五入到整数。 */
fun formatUiScale(value: Float): String {
    val pct = (clampUiScale(value) * 100f).toInt()
    return "$pct%"
}
