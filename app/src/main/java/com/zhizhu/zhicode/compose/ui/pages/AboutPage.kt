package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.BuildConfig
import com.zhizhu.zhicode.compose.model.OWN_LICENSE
import com.zhizhu.zhicode.compose.model.PROJECT_URL
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.settings.SettingsEntry
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsPlateColors
import com.zhizhu.zhicode.compose.ui.settings.SettingsSubPage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（形态参照 Miuix 示例 App 的 `AboutPage`）。
 *
 * ## 为什么大图标没有跟滚动做动画
 *
 * Miuix 示例的 `AboutPage` 让标题随滚动淡出，看着很漂亮。但它同时给标题挂了
 * 一层 `textureBlur(blurRadius = 150f)` —— 那是**每帧重采样一张全屏贴图**。
 * 一个"关于"页面为了让标题看起来更透而持续吃满 GPU，这笔账不划算。
 *
 * 这一版直接不做那层动画：信息（图标、名称、版本）本来是滚动到这一页第一眼就该
 * 看清的东西，让它随滚动消失反而是负担。等真要加回动效时，也只该用
 * `graphicsLayer` 的 alpha/scale（零重绘成本），并把滚动进度用 `derivedStateOf`
 * 收成一个跨阈值的布尔，而不是每帧读一次偏移量。
 *
 * ## 为什么版本号直接取 BuildConfig
 *
 * 这一页存在的意义之一就是"我装的是哪个版本"。写死字符串会在下一次发版时
 * 悄悄过期，而这正是用户来这一页最想确认的事。
 */
@Composable
fun AboutPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    SettingsSubPage(title = "关于", onBack = onBack) {
        AppIdentityBlock()

        SettingsGroup("项目") {
            SettingsEntry(
                title = "查看源码",
                valueText = "GitHub",
                onClick = { runCatching { uriHandler.openUri(PROJECT_URL) } },
                summary = "源码、问题反馈与构建说明都在仓库里",
                icon = ZhiIcons.link,
                plate = SettingsPlateColors.blue,
            )
        }

        SettingsGroup("许可") {
            SettingsEntry(
                title = "许可证",
                valueText = OWN_LICENSE,
                onClick = {
                    // 直接开仓库里的 LICENSE，而不是弹一段内嵌文本：
                    // 那才是分发物里真正生效的那一份，也避免"页面上写一份、仓库里另一份"。
                    runCatching { uriHandler.openUri("$PROJECT_URL/blob/main/LICENSE") }
                },
                summary = "本工程自身代码的许可",
                icon = ZhiIcons.lock,
                plate = SettingsPlateColors.green,
            )
            SettingsEntry(
                title = "第三方组件许可",
                // 副标题不写数量：它随提交变化，写死就会过期（这个仓库反复吃过这个亏）。
                valueText = "来源与许可",
                onClick = onOpenLicenses,
                summary = "分发物里包含哪些第三方代码、数据与二进制，各自是什么许可",
                icon = ZhiIcons.list,
                plate = SettingsPlateColors.purple,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "本工程的起点是 IQ Code（MIT）。协议层、Agent 主循环与工具集、沙箱宿主层的早期实现" +
                "都从它那里起步，之后被分批重写 —— 重写不免除署名义务，这份致谢是自愿保留的。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Text(
            text = "Copyright (c) 2026 zhizhu0002 · MIT",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * 应用图标 + 名称 + 版本。
 *
 * 图标的"降级方案"是有意的：本工程的启动图标是一个 PNG/XML 资源，
 * 但 Compose 侧拿不到 `painterResource` 之外的东西时不该崩。所以这里用一个
 * **纯粹由形状与颜色构成**的方块兜底 —— 它永远不会加载失败，也不额外吃一张位图。
 */
@Composable
private fun AppIdentityBlock() {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(84.dp),
            shape = RoundedCornerShape(20.dp),
            color = scheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = ZhiIcons.terminal,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Text(
            text = "ZhiCode",
            color = scheme.onBackground,
            fontSize = MiuixTheme.textStyles.title1.fontSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            // 版本直接来自构建产物：写死字符串会在下一次发版时悄悄过期，
            // 而这正是用户来这一页最想确认的事。
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Android 上的编码 Agent",
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
