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

// ---------------------------------------------------------------- 预设色卡配色

/**
 * 一个预设色卡上要画的四种颜色（riKKa 的 `PresetThemeButton` 同款四象限）。
 *
 * 全部是 **ARGB** 而不是 `Color`：本文件是纯逻辑（见文件头），
 * `androidx.compose.ui.graphics.Color` 会把 compose 拖进 `test-jvm-fast.sh`。
 */
data class ThemeSwatch(
    /** 中心那个圆点：强调色本身。 */
    val primary: Long,
    /** 左上象限。 */
    val primaryContainer: Long,
    /** 右上象限（色相 +40°）。 */
    val secondaryContainer: Long,
    /** 右下象限（色相 −50°）。 */
    val tertiaryContainer: Long,
)

/**
 * 由种子色推出 4 个配色。
 *
 * ## 为什么不用 `ThemeController` 真算一遍
 *
 * 色卡是**七张一起**画的。每张跑一次 HCT（Material Color Utilities）是七次
 * 全量调色板生成，而它们只在"深浅切换"或"没有发生任何变化"时被重算 ——
 * 用 `remember` 能挡住大部分开销，但仍有人会在下拉刷新、切页时白付一次。
 *
 * 这里改成一个纯算术的近似：容器色 = 种子色朝"底色"方向拉近，
 * 次级/三级容器各转一点色相。它不保证 WCAG 对比度，但**色卡只需要表达
 * "这个预设大概是什么颜色"**，而真实色板由 [com.zhizhu.zhicode.compose.theme.ZhiAppTheme]
 * 生成后用在界面上 —— 两者不共用数据，所以这里省得毫无风险。
 *
 * @param dark 深色下容器色要更暗（朝黑拉），浅色下更浅（朝白拉）。
 */
fun presetSwatch(seedArgb: Long, dark: Boolean): ThemeSwatch {
    val target = if (dark) 0xFF000000L else 0xFFFFFFFFL
    // 深色下容器色与种子色只差一档（否则在深色卡上糊成一团）；
    // 浅色下要拉得很开，否则彩色方块之间看不出区别。
    val nearMix = if (dark) 0.62f else 0.80f
    val midMix = if (dark) 0.70f else 0.86f
    val farMix = if (dark) 0.74f else 0.88f
    return ThemeSwatch(
        primary = seedArgb,
        primaryContainer = mixArgb(seedArgb, target, nearMix),
        secondaryContainer = mixArgb(shiftHue(seedArgb, 40f), target, midMix),
        tertiaryContainer = mixArgb(shiftHue(seedArgb, -50f), target, farMix),
    )
}

/** 两色按 [t] 线性混合（0 = [from]，1 = [to]），逐通道、保持 alpha。 */
internal fun mixArgb(from: Long, to: Long, t: Float): Long {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Long {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * k).toLong().coerceIn(0L, 255L)
    }
    return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * 旋转色相（单位：度），保持饱和度与明度。
 *
 * 色相环在 RGB 里不是线性的，所以必须经 HSL 转一次 —— 直接在通道上加减
 * 会让红色变成橙色但橙色变成白色（三个通道同时到顶）。
 */
internal fun shiftHue(argb: Long, degrees: Float): Long {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return argb // 灰阶没有色相可转
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h0 = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    val h = ((h0 + degrees) % 360f + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(v: Float): Long = ((v + m) * 255f).toLong().coerceIn(0L, 255L)
    return 0xFF000000L or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
}
