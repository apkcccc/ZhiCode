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
import com.zhizhu.zhicode.compose.ui.settings.SettingsFootnote
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
 * 主题设置**整页**。
 *
 * ## 形态：与 Miuix 示例 App 的设置页同构
 *
 * 参照 `miuix/example` 的 `SettingsPage.kt` / `AboutPage.kt`，一页分成
 * 「预览 → 外观 → 颜色 → 显示 → 缩放」几个 [SettingsGroup]（= `SmallTitle` +
 * 一张圆角 [Card]，卡里连续排 preference，**行与行之间不画分隔线**）。
 *
 * 三条从示例里直接搬过来的规则：
 *
 * 1. **成组的开关放同一张卡里**。每项各套一张卡会让整页碎成一堆便签；
 * 2. **级联项用 `AnimatedVisibility` 就地展开**（示例里 Key Color / Palette Style
 *    就是这么挂的），而不是另开一页或置灰 —— 关掉主开关时子项整块收起，
 *    不占版面也不留"能点但没用"的灰控件；
 * 3. **说明文字用 footnote 字号贴在该组卡下面**（示例里的 `SmallTitle` 之后的
 *    那张卡底部也常跟一句），不塞进每一行的 summary —— summary 一长，
 *    行高就不可控。
 *
 * ## 为什么预览卡放在最上面
 *
 * 主题的所有控件改的都是"看一眼才知道对不对"的东西。rikkahub 与示例都是先给一块
 * 可视化预览再给控件；顺序反过来用户会先读文字，读完仍然不知道当前长什么样。
 * 这里的预览不是截图，是**用当前色板真画**的一小块界面，所以永远与真实效果一致。
 *
 * ## 一个刻意**没有**的开关
 *
 * 截图里有「预测性返回手势」。Miuix 0.9.4 的 `NavDisplay` 把 predictive back 做成了
 * 内置能力且**没有关闭 API**，所以这里给的是我们真的能控的「边缘滑动返回」。
 * 做一个点了没用的开关，比不做更糟。
 */
@Composable
fun ThemePage(
    theme: ThemeSettings,
    onDismiss: () -> Unit,
    onChange: ((ThemeSettings) -> ThemeSettings) -> Unit,
) {
    SettingsSubPage(title = "主题设置", onBack = onDismiss) {
        ThemePreviewCard(theme)

        // ---- 外观：深浅三态 ----
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
            SettingsToggle(
                title = "纯黑（AMOLED）",
                checked = theme.amoled,
                onCheckedChange = { enabled -> onChange { it.copy(amoled = enabled) } },
                // 只在深色下有意义，所以把这句话写进 summary 而不是藏一个"有时候无效"的开关。
                summary = "深色模式下背板压成纯黑，省电且对比更高；浅色模式不受影响",
                icon = ZhiIcons.sandbox,
                plate = SettingsPlateColors.gray,
            )
        }

        // ---- 颜色：Monet 开关 + 预设色卡 + 风格级联 ----
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
            //
            // 它不放在 `SettingsGroup` 外面自己排：那样会得到一块既没有卡片底、
            // 又没有 SmallTitle 的裸控件，与上下两张卡的边距对不齐
            // （示例里所有可交互的东西都在卡里）。
            PresetRow(
                selectedId = theme.presetId,
                onSelect = { preset ->
                    // 点预设 = 放弃自定义色（那是"我改主意了"的语义）。
                    onChange { it.copy(presetId = preset.id, customSeedArgb = null) }
                },
            )
        }

        // 级联：只有 Monet 开着才有意义。收起时连卡片一起消失，
        // 否则会留下"一整张卡里没有任何可点的项"。
        AnimatedVisibility(
            visible = theme.dynamicColor,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                SettingsGroup("Monet 细节") {
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
                }
                // Spec2025 只被四个风格支持（TonalSpot / Neutral / Vibrant / Expressive），
                // 其余会自动降级 —— 这是 Miuix 明写在文档里的行为，这里如实转述。
                SettingsFootnote(
                    "Spec2025 仅 TonalSpot / Neutral / Vibrant / Expressive 支持，其余会自动降级为 Spec2021。",
                )
            }
        }

        // ---- 显示 ----
        SettingsGroup("显示") {
            val shaderSupported = isRuntimeShaderSupported()
            SettingsToggle(
                title = "模糊",
                checked = theme.blurEnabled,
                onCheckedChange = { enabled -> onChange { it.copy(blurEnabled = enabled) } },
                summary = if (shaderSupported) {
                    "启用顶栏、底栏与侧栏的背景虚化"
                } else {
                    "本机不支持（需要 Android 13 以上的 RuntimeShader），此项不生效"
                },
                // 不支持时置灰而不是隐藏：让"为什么没有虚化"这件事在界面上有答案。
                enabled = shaderSupported,
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
                enabled = theme.blurEnabled && shaderSupported,
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
        }

        // ---- 缩放单独一组：它是数值项，与开关混排会让卡片高度差得很明显 ----
        SettingsGroup("缩放") {
            SliderPreference(
                title = "界面缩放",
                summary = "调整全局显示比例；只影响界面，不改系统设置",
                value = theme.uiScale,
                valueText = formatUiScale(theme.uiScale),
                onValueChange = { scale -> onChange { it.copy(uiScale = scale) } },
                valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                // 3 步 = 每步 7.5%，覆盖 85% / 92.5% / 100% / 107.5% / 115%。
                steps = 3,
                showKeyPoints = true,
                keyPoints = listOf(UI_SCALE_MIN, 1.0f, UI_SCALE_MAX),
            )
        }

        SettingsFootnote("改动立刻生效并落盘，不需要保存。")
    }
}

/**
 * 预览卡：用**当前色板真画**一小块界面。
 *
 * 刻意不做成截图：截图会在改完设置之后仍然是旧的，而"预览与真实效果不一致"
 * 比没有预览更误导。这里画的元素覆盖了用户真正想确认的三件事：
 * 主色是什么、卡片与背板的层次、次级容器会不会脏。
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
            Text(
                text = if (theme.dynamicColor) "Monet · ${theme.paletteStyle}" else "应用内置色板",
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                modifier = Modifier.padding(top = 2.dp),
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
 * 预设色卡：**卡内的一行自定义控件**。
 *
 * 为什么不用 `OverlayDropdownPreference`：那一行只显示当前项的名字，而这一项的语义是
 * "这七种颜色长什么样" —— 名字（"暖阳橙"）远不如图块本身有用。示例 App 的色卡也是自绘。
 *
 * 为什么手搓 `Box` 而不是 `RadioButton`／`Chip`：Miuix 的单选控件画的是一个勾，
 * 看不出色差。
 *
 * ⚠️ 这一块必须在 `SettingsGroup` 的 `Card` **里面**（调用点就在那）：卡片自带圆角、
 * 卡片底与左右 12dp 边距，自己排会与上下的行对不齐。
 */
@Composable
private fun PresetRow(
    selectedId: String,
    onSelect: (ThemePreset) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "预设主题",
            color = scheme.onSurface,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            modifier = Modifier.padding(bottom = 10.dp),
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
