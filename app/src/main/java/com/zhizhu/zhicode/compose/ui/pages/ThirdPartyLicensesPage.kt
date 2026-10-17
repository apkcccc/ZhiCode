package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.LicenseEntry
import com.zhizhu.zhicode.compose.model.PROJECT_URL
import com.zhizhu.zhicode.compose.model.THIRD_PARTY_LICENSES
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.settings.SettingsEntry
import com.zhizhu.zhicode.compose.ui.settings.SettingsPlateColors
import com.zhizhu.zhicode.compose.ui.settings.SettingsSubPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 第三方组件许可列表（形态参照 Miuix 示例 App 的 `LicensePage`）。
 *
 * ## 与示例的差别：不是每条都跳网页
 *
 * 示例每行的 `onClick` 打开该库主页。本工程的清单里有好几项**没有主页**
 * （静态链接进 .so 的 Dobby、bootstrap 归档、内联进源码的图标路径）——
 * 给它们一个点不开的行只是噪音。所以这里：
 *
 * - 收起时一行显示「名称 + 版本, 许可」（与示例同形态）；
 * - 点一下**就地展开**该条的目的与已知缺口，不跳转；
 * - 页脚给一次「完整声明」的入口 —— 仓库里的 `NOTICE` 才是法律意义上的那份。
 *
 * ## 展开态为什么用 [BasicComponent] 而不是 [SettingsEntry]
 *
 * `SettingsEntry` 行尾**总会**画一个 `›`，而那是"进入下一页"的语义。
 * 点了就地展开却看到下一页的箭头，是在骗用户。所以这里用裸 `BasicComponent`，
 * 行尾放一个 `＋/－` 字形表达"可展开"。
 *
 * ## 数据来源
 *
 * [THIRD_PARTY_LICENSES]（`model/Licenses.kt`）。它与根 `NOTICE` 由 `LicensesTest`
 * 逐条对齐 —— 手写两份、靠测试防漂移。
 */
@Composable
fun ThirdPartyLicensesPage(
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    SettingsSubPage(title = "第三方组件许可", onBack = onBack) {
        Text(
            text = "下列组件随本应用一起分发。点一条展开它的目的与已知缺口；" +
                "完整声明（含修改说明与尚未闭合的事项）见仓库根目录的 NOTICE。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        THIRD_PARTY_LICENSES.forEach { entry ->
            LicenseRow(entry)
        }
        SettingsEntry(
            title = "查看完整声明",
            valueText = "NOTICE",
            onClick = {
                runCatching { uriHandler.openUri("$PROJECT_URL/blob/main/NOTICE") }
            },
            summary = "含每一项的修改说明、核对方式与已知缺口",
            icon = ZhiIcons.link,
            plate = SettingsPlateColors.gray,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * 一行许可，可就地展开。
 *
 * 展开状态放在 `rememberSaveable` 里并按**名称**做 key：清单是静态的、行不重排，
 * 但设置整页会被页面栈重建（返回再进来），用 `remember` 会把展开态丢掉 ——
 * 而"我刚展开过那一条"是用户会记得的操作。
 */
@Composable
private fun LicenseRow(entry: LicenseEntry) {
    val scheme = MiuixTheme.colorScheme
    var expanded by rememberSaveable(entry.name) { mutableStateOf(false) }

    Column {
        BasicComponent(
            title = entry.name,
            titleColor = BasicComponentDefaults.titleColor(color = scheme.onBackground),
            // 与示例同形态：「版本, 许可」。版本没有时显示 `—` 而不是省略 ——
            // 空着会让人以为漏了。
            summary = "${entry.version}, ${entry.license}",
            summaryColor = BasicComponentDefaults.summaryColor(color = scheme.onSurfaceVariantSummary),
            endActions = {
                Icon(
                    painter = if (expanded) ZhiIcons.collapse else ZhiIcons.add,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            onClick = { expanded = !expanded },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = entry.detail,
                color = scheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

