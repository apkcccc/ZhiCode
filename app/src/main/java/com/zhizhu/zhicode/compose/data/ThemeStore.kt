package com.zhizhu.zhicode.compose.data

import android.content.Context
import com.termux.app.zhicode.storage.ApiSettingsStore
import com.zhizhu.zhicode.compose.model.ThemeMode
import com.zhizhu.zhicode.compose.theme.THEME_PRESETS
import com.zhizhu.zhicode.compose.theme.UI_SCALE_DEFAULT
import com.zhizhu.zhicode.compose.theme.ZhiThemeMode
import com.zhizhu.zhicode.compose.theme.clampUiScale
import com.zhizhu.zhicode.compose.theme.presetById

/**
 * 主题与显示相关的全部设置（形状 + 落盘）。
 *
 * ## 为什么单独一个类，而不是继续塞进 `AppSettings`
 *
 * `AppSettings` 那一份是"设置页草稿"的数据源，而主题是**全局底座**：
 * 它在 `ZhiCodeApp` 组合的最外层就要用到，早于任何页面、也不经过
 * `SettingsDraft` 的生命周期。放在一起会让"打开设置→改一个值→未保存"
 * 与"立刻应用到全屏"这两件事纠缠。
 *
 * 另外它需要被**非 Compose** 的地方读到（沙箱悬浮窗的 `ZhiOverlayPalette`、
 * `EditorActivity`），而 `AppSettings` 只在 `WorkspaceUiState` 里存在。
 *
 * ## 每一项都必须真的生效
 *
 * 这个类里**没有**摆设开关。写进来之前先确认它有接入点：
 *
 * | 字段 | 接入点 |
 * | --- | --- |
 * | [mode] | `ZhiThemeMode.resolve` → `LocalZhiDark` |
 * | [dynamicColor] + [presetId] + [seedArgb] | `ZhiAppTheme` 走 `ThemeController` 的 Monet 模式 |
 * | [amoled] | 深色时背板与面板压成纯黑 |
 * | [blurEnabled] | `rememberGlass()` 关闭时**连 layerBackdrop 都不建** |
 * | [floatingBar] | `FloatingBottomShell` 关闭时满宽、去圆角与阴影 |
 * | [liquidGlass] | `Glass.surfaceColor` 的透明度与描边高光 |
 * | [edgeSwipeBack] | `AppScaffold` 里 `entry(swipeDismiss = …)` |
 * | [uiScale] | `LocalDensity` 缩放 |
 *
 * ⚠️ 刻意**没有**「预测性返回手势」开关：Miuix 的 `NavDisplay` 把 predictive back
 * 做成了内置能力且没有关闭 API（核过 `NavDisplay.kt` 455–560 行），
 * 做成开关就是一个点了没用的假开关 —— 那是这个代码库反复警告过的反模式。
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否用 Monet（种子色生成整套色板）。关闭时沿用工程既有的固定色板。 */
    val dynamicColor: Boolean = false,
    val presetId: String = THEME_PRESETS.first().id,
    /** 自定义强调色（ARGB）。为空表示"用预设的种子色"。 */
    val customSeedArgb: Long? = null,
    /**
     * Monet 的调色板风格与颜色规范。
     *
     * <p>存的是 Miuix 枚举的 `name` 而不是枚举本身：这一层不该依赖 compose 侧的库类型
     * （它还要能被非 Compose 的调用点读）。非法值在 [ZhiAppTheme] 侧回落到默认。
     */
    val paletteStyle: String = "TonalSpot",
    val colorSpec: String = "Spec2021",
    val amoled: Boolean = false,
    val blurEnabled: Boolean = true,
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = true,
    val edgeSwipeBack: Boolean = true,
    val uiScale: Float = UI_SCALE_DEFAULT,
) {
    val preset get() = presetById(presetId)

    /**
     * 实际使用的种子色。
     *
     * 自定义色优先于预设：用户在色轮上挑了一个颜色之后，再点预设色卡会**清掉**自定义
     * （那是"我改主意了"的语义），所以这里不需要处理两者冲突。
     */
    val seedArgb: Long get() = customSeedArgb ?: preset.seedArgb

    /** 纯黑只看深色模式：浅色下把背板压成黑色等于整屏不可读。 */
    val darkBackdropArgb: Long? get() = if (amoled) 0xFF000000L else preset.darkBackdropArgb
}

/**
 * 主题设置的读写。
 *
 * ## 老键兼容
 *
 * 深浅三态原本存在 `theme_mode` 这一个键里（见 `ApiSettingsStore.getThemeMode`）。
 * 那是**已经发出去的格式**，所以这里继续读它，而不是另起一个键然后让老用户的选择失效。
 * 其余字段都是新键，读不到就用默认值 —— 也就是"与今天完全一致"的行为。
 */
internal object ThemeStore {

    private const val PREFIX = "theme_"
    private const val DYNAMIC = PREFIX + "dynamic_color"
    private const val PRESET = PREFIX + "preset_id"
    private const val CUSTOM_SEED = PREFIX + "custom_seed_argb"
    private const val PALETTE = PREFIX + "palette_style"
    private const val SPEC = PREFIX + "color_spec"
    private const val AMOLED = PREFIX + "amoled"
    private const val BLUR = PREFIX + "blur_enabled"
    private const val FLOATING_BAR = PREFIX + "floating_bar"
    private const val LIQUID_GLASS = PREFIX + "liquid_glass"
    private const val EDGE_SWIPE = PREFIX + "edge_swipe_back"
    private const val UI_SCALE = PREFIX + "ui_scale"

    fun read(context: Context): ThemeSettings {
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return ThemeSettings()
        return ThemeSettings(
            // 深浅三态继续用**老键**（`ApiSettingsStore` 的 `theme_mode`）：
            // 那是已经发出去的格式，另起一个键等于让老用户的选择失效。
            mode = runCatching { ZhiThemeMode.stored(context) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(DYNAMIC, false),
            presetId = prefs.getString(PRESET, "")?.takeIf { it.isNotBlank() } ?: THEME_PRESETS.first().id,
            // 0 表示"没有自定义"（合法颜色的 ARGB 永远非 0）。
            customSeedArgb = prefs.getLong(CUSTOM_SEED, 0L).takeIf { it != 0L },
            paletteStyle = prefs.getString(PALETTE, "")?.takeIf { it.isNotBlank() } ?: "TonalSpot",
            colorSpec = prefs.getString(SPEC, "")?.takeIf { it.isNotBlank() } ?: "Spec2021",
            amoled = prefs.getBoolean(AMOLED, false),
            blurEnabled = prefs.getBoolean(BLUR, true),
            floatingBar = prefs.getBoolean(FLOATING_BAR, true),
            liquidGlass = prefs.getBoolean(LIQUID_GLASS, true),
            edgeSwipeBack = prefs.getBoolean(EDGE_SWIPE, true),
            uiScale = clampUiScale(prefs.getFloat(UI_SCALE, UI_SCALE_DEFAULT)),
        )
    }

    /**
     * 落盘。
     *
     * 用 `commit()` 而不是 `apply()`：主题是"点一下就走"的那一类设置，
     * `apply()` 只保证内存可见，从最近任务里划掉进程时没人等那次后台写 ——
     * 这正是本工程此前"设置记不住"的根因（见 `ApiSettingsStore` 里那段说明）。
     * 调用方在 IO 线程上，几毫秒的写不值得省。
     */
    fun write(context: Context, settings: ThemeSettings) {
        runCatching {
            // 深浅三态也回到老键上：`ZhiThemeMode.stored` 是它唯一的读方。
            ApiSettingsStore.setThemeMode(context, settings.mode.name.lowercase())
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DYNAMIC, settings.dynamicColor)
                .putString(PRESET, settings.presetId)
                .putLong(CUSTOM_SEED, settings.customSeedArgb ?: 0L)
                .putString(PALETTE, settings.paletteStyle)
                .putString(SPEC, settings.colorSpec)
                .putBoolean(AMOLED, settings.amoled)
                .putBoolean(BLUR, settings.blurEnabled)
                .putBoolean(FLOATING_BAR, settings.floatingBar)
                .putBoolean(LIQUID_GLASS, settings.liquidGlass)
                .putBoolean(EDGE_SWIPE, settings.edgeSwipeBack)
                .putFloat(UI_SCALE, clampUiScale(settings.uiScale))
                .commit()
        }
    }

    private const val PREFS_NAME = "zhi_theme"
}
