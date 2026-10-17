package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.data.ThemeSettings
import com.zhizhu.zhicode.compose.model.ThemeMode
import com.zhizhu.zhicode.compose.theme.THEME_PRESETS
import com.zhizhu.zhicode.compose.theme.ThemePreset
import com.zhizhu.zhicode.compose.theme.UI_SCALE_MAX
import com.zhizhu.zhicode.compose.theme.UI_SCALE_MIN
import com.zhizhu.zhicode.compose.theme.formatUiScale
import com.zhizhu.zhicode.compose.theme.presetById
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.settings.SettingsChoice
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsPlateColors
import com.zhizhu.zhicode.compose.ui.settings.SettingsSubPage
import com.zhizhu.zhicode.compose.ui.settings.SettingsToggle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 主题设置**整页**（形态参照 Miuix 示例 App 的设置页 + rikkahub 的主题页）。
 *
 * ## 为什么是独立整页，而不是设置 hub 里的一个下拉
 *
 * 这一页有十几个控件，其中「预设色卡」与「Monet 风格」还需要按条件级联展开
 * （`AnimatedVisibility`，与示例 `SettingsPage.kt` 同一种做法）。
 * 塞进 hub 会把下面所有分组顶下去，而主题是**改一次就不再动**的东西。
 *
 * ## 为什么预览卡放在最上面
 *
 * 主题的所有控件改的都是"看一眼才知道对不对"的东西。rikkahub 与示例都是
 * 先给一块可视化预览再给控件；顺序反过来（先一堆开关）用户会先去读文字，
 * 读完仍然不知道当前长什么样。这里的预览不是截图，是**用当前色板真画出来**的
 * 一小块界面（强调色块 + 两张卡片 + 两个指示点），所以它永远与真实效果一致。
 *
 * ## 一个刻意**没有**的开关
 *
 * 截图里有「预测性返回手势」。Miuix 0.9.4 的 `NavDisplay` 把 predictive back
 * 做成了内置能力且**没有关闭 API**（核过 `NavDisplay.kt` 455–560 行），
 * 所以这里给的是我们真的能控的「边缘滑动返回」。做一个点了没用的开关，
 * 比不做更糟 —— 那是这个代码库反复警告过的反模式。
 */
@Composable
fun ThemePage(
    theme: ThemeSettings,
    onDismiss: () -> Unit,
    onChange: ((ThemeSettings) -> ThemeSettings) -> Unit,
) {
    SettingsSubPage(title = "主题设置", onBack = onDismiss) {
        ThemePreviewCard(theme)

        // ---- 深浅三态：跟随系统 / 浅色 / 深色 ----
        //
        // 用三选而不是开关：截图里也是这样一条三态分段，而"跟随系统"没法用开关表达。
        SettingsGroup("外观") {
            SettingsChoice(
                title = "深浅模式",
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(theme.mode),
                onSelect = { index ->
                    onChange { it.copy(mode = ThemeMode.entries[index]) }
                },
                summary = "跟随系统时，系统的深色切换会立刻生效",
                icon = ZhiIcons.theme,
                plate = SettingsPlateColors.orange,
            )
        }

        // ---- 颜色：Monet 开关 + 预设 + 风格 ----
        SettingsGroup("颜色") {
            SettingsToggle(
                title = "启用 Monet 颜色",
                checked = theme.dynamicColor,
                onCheckedChange = { enabled -> onChange { it.copy(dynamicColor = enabled) } },
                summary = "用种子色生成整套色板；关闭时用应用内置的固定色板",
                icon = ZhiIcons.tune,
                plate = SettingsPlateColors.blue,
            )
            // 预设色卡在两种模式下都有用：Monet 开着时它是"选种子色"，
            // 关着时它是"选强调色的初值"。所以**不**按 dynamicColor 隐藏。
            SettingsPresetGrid(
                selectedId = theme.presetId,
                onSelect = { preset ->
                    // 点预设 = 放弃自定义色（那是"我改主意了"的语义）。
                    onChange { it.copy(presetId = preset.id, customSeedArgb = null) }
                },
            )
            AnimatedVisibility(
                visible = theme.dynamicColor,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    // 与示例同一组选项顺序与取值 —— 它们直接对应 Miuix 的枚举。
                    OverlayDropdownPreference(
                        title = "调色板风格",
                        items = ThemePaletteStyle.entries.map { it.name },
                        selectedIndex = ThemePaletteStyle.entries.indexOf(
                            ThemePaletteStyle.entries.firstOrNull { it.name == theme.paletteStyle }
                                ?: ThemePaletteStyle.TonalSpot,
                        ),
                        onSelectedIndexChange = { index ->
                            onChange { it.copy(paletteStyle = ThemePaletteStyle.entries[index].name) }
                        },
                    )
                    OverlayDropdownPreference(
                        title = "颜色规范",
                        items = ThemeColorSpec.entries.map { it.name },
                        selectedIndex = ThemeColorSpec.entries.indexOf(
                            ThemeColorSpec.entries.firstOrNull { it.name == theme.colorSpec }
                                ?: ThemeColorSpec.Spec2021,
                        ),
                        onSelectedIndexChange = { index ->
                            onChange { it.copy(colorSpec = ThemeColorSpec.entries[index].name) }
                        },
                    )
                    Text(
                        // Spec2025 只被四个风格支持（TonalSpot / Neutral / Vibrant / Expressive），
                        // 其余会自动降级 —— 这是 Miuix 明写在文档里的行为，这里如实转述。
                        text = "Spec2025 仅 TonalSpot / Neutral / Vibrant / Expressive 支持，其余会自动降级为 Spec2021。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ---- 显示 ----
        SettingsGroup("显示") {
            SettingsToggle(
                title = "模糊",
                checked = theme.blurEnabled,
                onCheckedChange = { enabled -> onChange { it.copy(blurEnabled = enabled) } },
                summary = if (isRuntimeShaderSupported()) {
                    "启用顶栏、底栏与侧栏的背景虚化"
                } else {
                    "本机不支持（需要 Android 13 以上的 RuntimeShader），此项不生效"
                },
                // 不支持时置灰而不是隐藏：让"为什么没有虚化"这件事在界面上有答案。
                enabled = isRuntimeShaderSupported(),
                icon = ZhiIcons.sandbox,
                plate = SettingsPlateColors.purple,
            )
            SettingsToggle(
                title = "悬浮底栏",
                checked = theme.floatingBar,
                onCheckedChange = { enabled -> onChange { it.copy(floatingBar = enabled) } },
                summary = "输入器与任务卡浮在内容之上；关闭后贴满宽度、去圆角",
                icon = ZhiIcons.chat,
                plate = SettingsPlateColors.blue,
            )
            SettingsToggle(
                title = "液态玻璃",
                checked = theme.liquidGlass,
                onCheckedChange = { enabled -> onChange { it.copy(liquidGlass = enabled) } },
                summary = "悬浮层更透、带边缘高光；关闭后是不透明的实心面板",
                // 没有虚化时"更透"只会露出下面的内容而看不清，所以这一项跟着模糊走。
                enabled = theme.blurEnabled && isRuntimeShaderSupported(),
                icon = ZhiIcons.layers,
                plate = SettingsPlateColors.green,
            )
            SettingsToggle(
                title = "边缘滑动返回",
                checked = theme.edgeSwipeBack,
                onCheckedChange = { enabled -> onChange { it.copy(edgeSwipeBack = enabled) } },
                summary = "从屏幕边缘向内滑动返回上一页；方向跟随布局方向",
                icon = ZhiIcons.back,
                plate = SettingsPlateColors.gray,
            )
            SliderPreference(
                title = "界面缩放",
                summary = "调整全局显示比例；只影响界面，不改系统设置",
                value = theme.uiScale,
                valueText = formatUiScale(theme.uiScale),
                onValueChange = { scale -> onChange { it.copy(uiScale = scale) } },
                valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                // 5 档 = 每档 7.5%，正好覆盖 85% / 92.5% / 100% / 107.5% / 115%。
                steps = 3,
                showKeyPoints = true,
                keyPoints = listOf(UI_SCALE_MIN, 1.0f, UI_SCALE_MAX),
            )
            SettingsToggle(
                title = "纯黑（AMOLED）",
                checked = theme.amoled,
                onCheckedChange = { enabled -> onChange { it.copy(amoled = enabled) } },
                summary = "深色模式下背板压成纯黑，省电且对比更高；浅色模式不受影响",
                icon = ZhiIcons.theme,
                plate = SettingsPlateColors.gray,
            )
        }

        Text(
            text = "改动立刻生效并落盘，不需要保存。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 预览卡：用**当前色板真画**一小块界面。
 *
 * 刻意不做成截图：截图会在改完设置之后仍然是旧的，而"预览与真实效果不一致"
 * 比没有预览更误导。这里画的三个元素（强调色块、一张卡片、一条次级容器）覆盖了
 * 用户真正想确认的三件事：主色是什么、卡片与背板的层次、次级容器会不会脏。
 */
@Composable
private fun ThemePreviewCard(theme: ThemeSettings) {
    val scheme = MiuixTheme.colorScheme
    val preset = remember(theme.presetId) { presetById(theme.presetId) }
    val accent = Color(theme.seedArgb)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = scheme.surfaceContainer),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = preset.name,
                color = scheme.onSurface,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            // 强调色块：Monet 开着时它是**生成结果**而不是种子色 ——
            // 那正是用户想看的（算法会把它调成什么）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (theme.dynamicColor) scheme.primaryContainer else accent),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.secondaryContainer),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHigh),
            )
            // 两个指示点：强调色 + 次级色（截图里的那两个圆点就是这个意思）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(scheme.primary, scheme.secondary).forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

/**
 * 预设色卡：一行四个，选中项有一圈描边。
 *
 * 为什么手搓 `Box` 而不是用 `RadioButton`／`Chip`：这里要显示的是**颜色**本身，
 * Miuix 的单选控件画的是一个勾，看不出色差；而示例的色卡也是自绘的。
 */
@Composable
private fun SettingsPresetGrid(
    selectedId: String,
    onSelect: (ThemePreset) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "预设主题",
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // 用固定行分组而不是 LazyVerticalGrid：只有 7 项，网格的滚动与测量开销不值得，
        // 而且在设置页的 LazyColumn 里嵌一个可滚动网格会让滑动打架。
        THEME_PRESETS.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { preset ->
                    PresetChip(
                        preset = preset,
                        selected = preset.id == selectedId,
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐最后一行的空位，让 4 列的宽度与前面几行一致。
                repeat(4 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PresetChip(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val color = Color(preset.seedArgb)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 2.dp,
                            color = scheme.onSurface,
                            shape = RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                .clickablePreset(onClick),
        )
        Text(
            text = preset.name,
            color = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 色卡上的点击。
 *
 * 单独抽出来只是为了不在调用处写一长串 `Modifier.clickable(...)`：
 * 这里**必须**有 `indication = null` 且不带水波纹 —— 色块本身是实色，
 * 涟漪会被颜色吃掉，看起来像"点下去闪了一下但什么都没发生"。
 */
@Composable
private fun Modifier.clickablePreset(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    ),
)
