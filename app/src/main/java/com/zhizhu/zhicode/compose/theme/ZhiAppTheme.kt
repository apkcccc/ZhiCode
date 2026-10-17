package com.zhizhu.zhicode.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.zhizhu.zhicode.compose.data.ThemeSettings
import com.zhizhu.zhicode.compose.model.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 应用的唯一主题栈：深浅判定 + 配色（含 Monet 种子色）+ 文字字阶 + 界面缩放。
 *
 * ## 为什么要从 `ZhiCodeApp` 里抽出来
 *
 * 这一段原先长在 `ZhiCodeApp` 内部，于是**只有主界面那一棵组合树**在主题里。
 * 第二个 Activity（先有沙箱页、后有编辑器页）各自抄了一份 —— 抄漏一处就是
 * 「设置里选浅色、这一屏还是深色」那类问题；沙箱页当初正是这么错的。
 *
 * 现在只有这一个入口，两个 Activity 都调它。
 *
 * ## 顺序不能换
 *
 * 1. 先算深浅（[ZhiThemeMode.rememberCurrentDark] —— 全局唯一的判定处）；
 * 2. 再算色板（[rememberZhiColors]，Monet 那条路要在这里生成种子色板）；
 * 3. `CompositionLocalProvider(LocalZhiDark, LocalAmoled, LocalThemeSettings, LocalDensity)`。
 *    **必须早于任何 `ZhiColors` 取值**：那些层级色靠这两个 Local 判断深浅与纯黑，
 *    读早了会拿到默认值（浅色、非纯黑），于是深色模式下背板被设成浅灰、弹窗整片发白；
 * 4. 最后 `MiuixTheme`。`background` 被换成 [ZhiColors.backdrop]：Miuix 浅色方案里
 *    background / surface / surfaceContainer 都是 `#FFFFFF`，纯白铺满后板块分不出来。
 *
 * ## 性能：色板必须 `remember`
 *
 * 改之前这里每次重组都执行 `colors.copy(background = …)` 与 `zhiLightNeutrals()`
 * —— 两个都**新建一个 `Colors` 实例**（50 多个 `mutableStateOf` 字段）。
 * 而 `AppScaffold` 这一层在流式输出期间会反复重组，于是每帧都在造垃圾，
 * 还让 `MiuixTheme` 内部的 `updateColorsFrom` 每次都重跑一遍全量赋值。
 * 现在整段结果按「深浅 + 主题设置」缓存，设置不变就是**同一实例**。
 */
@Composable
fun ZhiAppTheme(theme: ThemeSettings, content: @Composable () -> Unit) {
    val isDark = ZhiThemeMode.rememberCurrentDark(theme.mode)
    // 应用内换主题时状态栏图标要跟着换：onResume 那条路径只在回到前台时补，
    // 期间图标会一直是相反的明暗。
    ZhiThemeMode.ApplySystemBars(isDark)

    CompositionLocalProvider(
        LocalZhiDark provides isDark,
        LocalAmoled provides theme.amoled,
        LocalThemeSettings provides theme,
        // 界面缩放：只改密度，不改布局代码。`fontScale` 必须原样带过去 ——
        // 否则用户系统的「字体大小」设置会在应用里失效。
        LocalDensity provides scaledDensity(theme.uiScale),
    ) {
        // ⚠️⚠️ 色板必须在**这两个 Local 之内**求值，这是修过的一个真实 bug。
        //
        // 原来 `val colors = rememberZhiColors(theme, isDark)` 写在
        // `CompositionLocalProvider` **之前**，于是色板函数里的
        // `ZhiColors.backdrop()` 读到的是 Provider **外层**的值：
        //   · `LocalZhiDark` → 默认 `false`；
        //   · `LocalAmoled` → 默认 `false`。
        // 结果：**深色模式下整个主题的 `background` 是浅色背板 `#F1F1F3`**，
        // 而面板/卡片（它们读的是 Provider 内的正确值）是深色 —— 板块之间的缝、
        // 顶栏透出的底色、页面切换时露出的那一层全是浅灰；AMOLED 开关同样完全无效
        // （因为 `background` 那一份永远按非纯黑算出来）。
        // 用户说的「主题还是有很多问题」里最主要的一条就是这个。
        MiuixTheme(
            colors = rememberZhiColors(theme, isDark),
            textStyles = zhiTextStyles(),
            content = content,
        )
    }
}

/**
 * 当前主题设置。
 *
 * 分成独立的 Local，而不是让每个调用点都从参数往下传：读它的地方分散在
 * 顶栏、输入器、`FloatingBottomShell`、`rememberGlass`、侧栏抽屉……
 * 逐个传参会让每一层的签名都长出一截，而它们其实只需要读。
 */
val LocalThemeSettings = androidx.compose.runtime.compositionLocalOf { ThemeSettings() }

/**
 * 生成最终色板。
 *
 * ## 两条路
 *
 * - **Monet 开**：交给 Miuix 的 `ThemeController`（内部是 Material Color Utilities
 *   的 HCT 算法）从种子色生成整套色板。我们只覆写 `background` ——
 *   它必须等于 [ZhiColors.backdrop]，否则状态栏那一条会与背板不同色。
 * - **Monet 关**：与改动前**逐色相同**（`darkColorScheme()`，或
 *   `lightColorScheme().zhiLightNeutrals()`）。这是这次主题改造的硬验收线：
 *   默认设置下用户看到的每一个像素都不该变。
 *
 * ## 为什么不用 `MiuixTheme(controller = …)`
 *
 * 那个重载会把 `colorSchemeMode` 一起提供出去（`MiuixTheme.isDynamicColor` 依赖它），
 * 但我们还要在**它之上**覆写 `background`，而 controller 那条路没有"再覆写一层"的出口。
 * 所以这里只借 controller 的 `currentColors()` 拿到生成结果，再自己 copy。
 */
@Composable
private fun rememberZhiColors(theme: ThemeSettings, isDark: Boolean): Colors {
    val backdrop = ZhiColors.backdrop()

    if (!theme.dynamicColor) {
        return remember(isDark, backdrop) {
            val base = if (isDark) darkColorScheme() else lightColorScheme().zhiLightNeutrals()
            base.copy(background = backdrop)
        }
    }

    // Monet：控制器本身要 remember，否则每次重组都重建一套（含 HCT 计算）。
    val controller = remember(theme.seedArgb, theme.paletteStyle, theme.colorSpec, isDark) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.MonetSystem,
            keyColor = Color(theme.seedArgb),
            // 存的是枚举的 name（见 ThemeSettings 的说明），非法值回落到默认，
            // 而不是抛异常：老版本写下的名字读不到是常态。
            paletteStyle = ThemePaletteStyle.entries
                .firstOrNull { it.name == theme.paletteStyle } ?: ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.entries
                .firstOrNull { it.name == theme.colorSpec } ?: ThemeColorSpec.Spec2021,
            // 显式给 isDark：三条深浅模式（跟随系统/浅色/深色）都已经在
            // `ZhiThemeMode.resolve` 里收敛成这一个布尔，不必让控制器再读一次系统。
            isDark = isDark,
        )
    }
    val generated = controller.currentColors()
    return remember(generated, backdrop, isDark) {
        // Monet 的浅色板用它自己的中性色（带种子色的灰）——**不要**再套
        // `zhiLightNeutrals()`：那套灰是为"固定色板"调的，叠在生成色板上
        // 会让强调色与容器色对不上（生成算法保证的是它自己那一套对比度）。
        generated.copy(background = backdrop)
    }
}

/** 见 [ZhiAppTheme] 里的界面缩放说明。 */
@Composable
private fun scaledDensity(scale: Float): Density {
    val base = LocalDensity.current
    val clamped = clampUiScale(scale)
    // 1.0 时返回**同一个** Density 实例：绝大多数用户不会改这一项，
    // 为此多产生一个 Density 只会让下游白重组一次。
    return if (clamped == 1.0f) base else Density(base.density * clamped, base.fontScale)
}

/**
 * 把 Miuix **浅色**方案里那几个"反了方向"的灰，收进本工程自己的中性阶。
 *
 * ## 症状与根因
 *
 * 用户在两轮反馈里说的都是同一件事：**浅色下很多组件是一块发灰的脏色**（"组件颜色都会
 * 发灰，好丑"），而深色下没有。逐个调用点换色修不掉它，因为两种灰**同时存在**：
 *
 * * 本工程自己的中性阶（[ZhiColors]）：背板 `#F1F1F3` → 面板 `#FFFFFF` →
 *   卡片 `#FAFAFC` → 卡内小块 `#F7F7F8`。**越靠近内容越亮**，与深色主题
 *   （背板 `#28282A` → 卡片 `#323235`）同一个方向：卡片是"浮起来"的。
 * * Miuix 0.9.4 浅色方案的容器色（从 `miuix-ui` 字节码里取出的真实值）：
 *
 * | Miuix 字段 | 浅色实际值 | 谁在取 |
 * | --- | --- | --- |
 * | `background` / `surface` / `surfaceVariant` / `surfaceContainer` | `#FFFFFF` | 白 |
 * | `surfaceContainerHigh` / `surfaceContainerHighest` | `#E8E8E8`（**同一个值**） | 分组容器、代码块、次级 Card |
 * | `secondary` | `#E6E6E6` | 次级填充 |
 * | `secondaryVariant` / `secondaryContainer` | `#F0F0F0` | 次级容器 |
 * | `onBackgroundVariant` | `#8C93B0` | 弱化文字/图标 |
 *
 * `#E6E6E6`~`#E8E8E8` 比本工程的**背板** `#F1F1F3` 还暗一档，于是浅色下凡是走
 * Miuix 容器色的地方都成了**比背板更暗的灰块** —— 方向反了，看起来就是"脏"。
 *
 * ## 为什么只在入口重映射
 *
 * 逐个调用点换成 [ZhiColors] 也能修，但那有 200+ 个取值点，而且 Miuix **组件内部**
 * 取的色根本改不到。在这一个入口重映射则调用点一行都不用动。
 *
 * ## 只改浅色
 *
 * 深色方案这几个字段本来就是 `#242424` 一族，用户反馈里也只提浅色。
 */
private fun Colors.zhiLightNeutrals(): Colors = copy(
    surfaceContainerHigh = ZhiColors.CardLight,
    surfaceContainerHighest = ZhiColors.CardInnerLight,
    secondary = ZhiColors.SoftFillLight,
    secondaryVariant = ZhiColors.SoftFillLight,
    secondaryContainer = ZhiColors.SoftFillLight,
    onBackgroundVariant = ZhiColors.MutedLight,
)
