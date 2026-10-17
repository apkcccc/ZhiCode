package com.zhizhu.zhicode.compose.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.model.ApiConfigState
import com.zhizhu.zhicode.compose.model.ApiProfile
import com.zhizhu.zhicode.compose.model.ApiProfileDraft
import com.zhizhu.zhicode.compose.model.ApiProtocol
import com.zhizhu.zhicode.compose.ui.ZhiFieldError
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.compose.ui.ZhiMotion
import com.zhizhu.zhicode.compose.ui.ZhiIconButton
import com.zhizhu.zhicode.compose.theme.ZhiColors
import com.zhizhu.zhicode.compose.ui.ZhiTextField
import com.zhizhu.zhicode.compose.ui.settings.SettingsGroup
import com.zhizhu.zhicode.compose.ui.settings.SettingsLoadingHint
import com.zhizhu.zhicode.compose.ui.settings.SettingsPageKey
import com.zhizhu.zhicode.compose.ui.settings.SettingsPageStack
import com.zhizhu.zhicode.compose.ui.settings.SettingsSubPage
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** ZCode 的网关地址。**只用于「填入默认值」这个动作**，见下面的说明。 */
private const val ZCODE_GATEWAY = "https://zcode.z.ai/api/v1/zcode-plan"

/**
 * 预填的额外请求头。
 *
 * **只有版本号是这里该有的东西**。其余的头（`User-Agent`、`X-Title`、`X-Platform`、
 * `X-Os-Category`、`X-Release-Channel`、`X-Client-Language`、`X-Client-Timezone`、
 * `HTTP-Referer`、`X-Device-Mid`）现在由 `ZcodeWire` 按官方客户端的取值**在运行时**
 * 生成 —— 写进这里只会把它们冻在填入那一刻（语言、时区、系统版本、设备标识都会变），
 * 而且多一份会与代码里那份不一致的副本。
 *
 * 版本号留在明面上是因为它**会过期**：ZCode 发了新版，这个数字要跟着改，
 * 而它是"这个协议在跟谁说话"的一部分。它同时决定额度查询串里的 `app_version`
 * 与默认 `User-Agent`（两处必须说同一个版本）。
 */
private const val ZCODE_HEADERS_JSON = """{"X-ZCode-App-Version":"3.14.3"}"""

/**
 * ZCode 的说明与可粘贴取值。
 *
 * ## 为什么给出具体取值，以及为什么用「填入」而不是内置
 *
 * 那个网关要求请求带一组来源头才受理，取值由它自己的客户端决定（现在那份客户端是
 * 开源的，取值有据可查）。让用户自己猜是不现实的（名字、格式、大小写都得对），
 * 所以这里给成可用的文本。但**它是提示与预填，不是常量**：按下「填入 ZCode 默认值」
 * 之后值就落进用户自己的配置里，之后请求只读那份配置。
 *
 * 这与本仓库「不预置任何厂商地址」的策略有一处**刻意的张力**，取舍写在下面：
 * 不给这个地址，这个协议对用户就是不可用的（他得从别处找）；给了，它就变成"用户看见并
 * 主动应用的一份配置"。选了可用，同时把它留在明面上 —— 而不是藏在请求路径里。
 */
private val ZCODE_HEADERS_HINT = """
    网关地址：$ZCODE_GATEWAY

    其余请求头由应用在运行时按官方客户端的取值生成（版本、来源、语言、时区、设备标识），
    上面只预填了**版本号** —— 它连着额度查询的 app_version，ZCode 发新版时改这里。
    任何一条都可以在这里覆盖，例如：

    {"X-ZCode-App-Version":"3.14.3","User-Agent":"ZCode/3.14.3"}
""".trimIndent()

/**
 * API 配置窗口：列表页与编辑表单**共用一个弹窗**。
 *
 * [config] 的 `form` 非空即显示表单，否则显示列表 —— 对应原版的两张页面。
 *
 * 密钥处理：
 * - 已保存的密钥**永远不回填**到输入框（`ApiProfile.apiKey` 恒为空串）；
 * - 编辑时留空 = 沿用原密钥，输入内容 = 替换密钥。
 *   这个语义必须在界面上写清楚，否则用户会以为"留空等于清空密钥"而不敢动表单。
 */
@Composable
fun ApiConfigPage(
    config: ApiConfigState?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onEdit: (ApiProfile) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDraftChange: ((ApiProfileDraft) -> ApiProfileDraft) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
) {
    if (config == null) return
    val form = config.form
    // 退出动画期间离场页仍在绘制，那时 form 已经是 null —— 见 rememberLastNonNull 的说明。
    val shownForm = rememberLastNonNull(form)
    // 二级整页（与设置主页同款骨架），列表/表单两态走**页面栈**：
    // 只写一个 when 分支的话两态会被硬切，没有任何转场动画。
    SettingsPageStack(
        // 路径含最底下那一页：栈要靠整条路径算层级与方向。
        path = listOf(
            SettingsPageKey("api.list", 0),
            if (form != null) SettingsPageKey("api.form", 1) else null,
        ).filterNotNull(),
        onBack = if (form == null) onDismiss else onCancelForm,
    ) { key ->
        // ⚠️ 分支必须看**正在渲染的那一页**（key），不能看当前状态。
        val draft = if (key.id == "api.form") shownForm else null
        if (draft == null) {
            SettingsSubPage(
                title = "API 配置记录",
                onBack = onDismiss,
                action = "新增" to onNew,
            ) {
                ApiProfileList(
                    config = config,
                    onEdit = onEdit,
                    onSelect = onSelect,
                    onDelete = onDelete,
                )
            }
        } else {
            SettingsSubPage(
                title = if (draft.isEditing) "编辑 API 配置" else "新增 API 配置",
                onBack = onCancelForm,
                action = "保存" to (if (draft.saveable) onSave else null),
            ) {
                ApiProfileForm(
                    draft = draft,
                    onChange = onDraftChange,
                )
            }
        }
    }
}

@Composable
private fun ApiProfileList(
    config: ApiConfigState,
    onEdit: (ApiProfile) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column {
        SettingsGroup("配置记录") {
            // 载荷在 IO 上读（见 openApiConfig）：空列表与「还没配过」长得一样，
            // 所以读取期间必须插在空态之前显式说明。
            if (config.loading) {
                SettingsLoadingHint()
            } else if (config.profiles.isEmpty()) {
                // 空列表是正常的初始状态（应用不再自带任何厂商配置），
                // 但只显示一行"没有数据"会让人以为坏了。写清下一步做什么。
                // 内边距与 preference 行对齐，避免这行看起来贴边。
                Text(
                    text = "还没有 API 配置。点右上角「新增」填写你自己服务的 Base URL、协议与模型名 —— " +
                        "应用不预置任何厂商地址，API Key 会存进系统加密存储。",
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            // 列表/表单两态共用一个整页栈；「新增」在顶栏，这里只负责行内容。
            // 每行不再各套一张卡：官方 SettingsPage 与 rikkahub 都是「一张分组卡里若干行」，
            // 散卡会让整页碎成一堆便签；「当前生效」那条仍靠左侧勾 + 主色标题区分。
            config.profiles.forEach { profile ->
                val active = profile.id == config.activeId
                // 「当前生效」那一条是靠主色标题 + 勾来区分的，两处同时变色就得一起淡变，
                // 否则会看到「勾已经出现了、标题还没变蓝」。
                val titleColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onBackground,
                    animationSpec = ZhiMotion.colorSpec,
                    label = "apiProfileTitle",
                )
                BasicComponent(
                    title = profile.name,
                    titleColor = BasicComponentDefaults.titleColor(color = titleColor),
                    summary = profile.summary,
                    summaryColor = BasicComponentDefaults.summaryColor(color = scheme.onSurfaceVariantSummary),
                    // 选中的那条用勾表示"当前生效"，未选中的给一个空位保持左对齐一致。
                    // 用 Miuix 的 Check 图标而不是 Checkbox：后者固定 26dp 且是圆的，
                    // 配 11~13sp 的行文字偏大（理由详见 Dialogs.kt 里的同一处注释）。
                    startAction = {
                        if (active) {
                            Icon(
                                painter = ZhiIcons.check,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(DropdownDefaults.CheckIconSize),
                            )
                        }
                    },
                    endActions = {
                        ZhiIconButton(
                            icon = ZhiIcons.edit,
                            description = "编辑",
                            onClick = { onEdit(profile) },
                            iconSize = 15.dp,
                        )
                        // 每条都可以删——包括那条从旧版本留下来的厂商配置。
                        // 以前这里拦着不画按钮，理由是"引擎会拒绝删除官方记录"；
                        // 现在没有官方记录这个概念了，禁止删除只剩下一个后果：
                        // 用户清不掉一个自己不要的地址。
                        ZhiIconButton(
                            icon = ZhiIcons.close,
                            description = "删除",
                            onClick = { onDelete(profile.id) },
                            iconSize = 15.dp,
                        )
                    },
                    onClick = { if (!active) onSelect(profile.id) },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            text = "新增或编辑后，密钥会存进系统加密存储（AndroidKeyStore）。" +
                "编辑已有配置时密钥框留空表示沿用原密钥。",
            color = scheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ApiProfileForm(
    draft: ApiProfileDraft,
    onChange: ((ApiProfileDraft) -> ApiProfileDraft) -> Unit,
) {
    // 地址是否走明文 HTTP：部分自建网关只有 http。这个开关对应引擎里的
    // usesCleartextTraffic 场景，正常应保持关闭。
    var allowCleartext by remember(draft.id) { mutableStateOf(draft.baseUrl.startsWith("http://")) }
    // 见 ZhiFieldError：没碰过的字段不飘红。key 取 draft.id，换记录时归零。
    var nameTouched by remember(draft.id) { mutableStateOf(false) }
    var baseUrlTouched by remember(draft.id) { mutableStateOf(false) }
    var visionEnabled by remember(draft.id) { mutableStateOf(true) }

    // 明文开关与地址前缀强绑定：勾上就把 http:// 规范成 http://，
    // 不勾就把 http:// 改成 https://。避免出现"地址是 http 但开关是关的"这种自相矛盾状态。
    LaunchedEffect(allowCleartext) {
        val url = draft.baseUrl
        if (allowCleartext && url.startsWith("https://")) {
            onChange { it.copy(baseUrl = "http://" + url.removePrefix("https://")) }
        } else if (!allowCleartext && url.startsWith("http://")) {
            onChange { it.copy(baseUrl = "https://" + url.removePrefix("http://")) }
        }
    }

    Column {
        // 表单与设置主页同形态：按「基本信息 / 连接 / 凭据 / 模型」分组卡排列，
        // 不再是一列散着的控件 —— 散控件正是「和整页设置割裂」的来源。
        SettingsGroup("基本信息") {
            ZhiTextField(
                value = draft.name,
                onValueChange = { value ->
                    nameTouched = true
                    onChange { it.copy(name = value) }
                },
                label = "名称",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // 协议：用 Miuix 的 OverlayDropdownPreference（行尾显示当前值 + 下拉箭头）。
            // 不用 SuperArrow —— 那个 API 在 Miuix 0.9.4 里已经不存在（`extra` 包已移除）。
            //
            // 「调试 · 本地模拟」只在 debug 构建里出现在候选里：它是一条不发网络、
            // 按脚本回话的路径，对用户没有意义，出现在发布包里只会让人以为能这么用。
            val protocols = remember {
                ApiProtocol.entries.filter {
                    !it.debugOnly || com.zhizhu.zhicode.compose.BuildConfig.DEBUG
                }
            }
            OverlayDropdownPreference(
                items = protocols.map { it.label },
                selectedIndex = protocols.indexOf(draft.protocol).coerceAtLeast(0),
                title = "协议",
                summary = null,
                onSelectedIndexChange = { index ->
                    protocols.getOrNull(index)?.let { protocol ->
                        onChange { it.copy(protocol = protocol) }
                    }
                },
            )
        }

        SettingsGroup("连接") {
            ZhiTextField(
                value = draft.baseUrl,
                onValueChange = { value ->
                    baseUrlTouched = true
                    onChange { it.copy(baseUrl = value) }
                },
                label = "Base URL",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            /*
             * ZCode 的「填入默认值」。
             *
             * 参考实现把网关地址与身份头内置在代码里、界面上写「无需填写，直连 ZCode 网关」。
             * 我们改成一键填入，差别是**值落在用户的配置里**而不是藏在代码里的常量：
             * 于是「请求发到哪、以谁的身份」在配置页看得见、改得动、排得动障。
             *
             * 一按同时填两样（网关 + 额外请求头）：它们是配套的，只填一样必然连不上，
             * 而分开两次填会让人以为步骤没做完。
             */
            if (draft.protocol == ApiProtocol.ZCODE) {
                BasicComponent(
                    title = "填入 ZCode 默认值",
                    summary = "一次填好网关地址与额外请求头（可再自行修改）",
                    onClick = {
                        baseUrlTouched = true
                        onChange { it.copy(baseUrl = ZCODE_GATEWAY, extraHeaders = ZCODE_HEADERS_JSON) }
                    },
                )
            }

            SwitchPreference(
                title = "允许明文 HTTP",
                summary = if (allowCleartext) "当前地址走 http（不加密）" else "仅使用 https",
                checked = allowCleartext,
                onCheckedChange = { allowCleartext = it },
            )
        }

        SettingsGroup("凭据") {
            ZhiTextField(
                value = draft.apiKey,
                onValueChange = { value -> onChange { it.copy(apiKey = value) } },
                // ZCode 的密钥就是它的授权码；别的协议叫 API Key。同一件事在两边
                // 用不同的名字，用户会以为还要另找一个"授权码"填在别处。
                label = when {
                    draft.isEditing -> "留空 = 沿用原密钥"
                    draft.protocol == ApiProtocol.ZCODE -> "填写 ZCode 授权码"
                    else -> "API Key"
                },
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        /*
         * 额外请求头：**只在 ZCode 协议下出现**。
         *
         * 那个网关要求带一组特定的身份头才受理，而那是它自己客户端的标识 ——
         * 我们不内置（内置等于替用户宣称一个身份，且对方一改所有人都一起断）。
         * 所以做成用户填，并把需要填的内容**直接写在提示里**：可见、可改、可排查。
         *
         * 对别的协议不显示这个框：它在那里只会让人以为"是不是还差一项没填"。
         */
        if (draft.protocol == ApiProtocol.ZCODE) {
            SettingsGroup("额外请求头（ZCode）") {
                ZhiTextField(
                    value = draft.extraHeaders,
                    onValueChange = { value -> onChange { it.copy(extraHeaders = value) } },
                    label = "JSON 对象，留空则不加",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = ZCODE_HEADERS_HINT,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        SettingsGroup("模型") {
            ZhiTextField(
                value = draft.model,
                onValueChange = { value -> onChange { it.copy(model = value) } },
                label = "模型名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            SwitchPreference(
                title = "发送图片（Vision）",
                // 一句话说明：SwitchPreference 的 summary 走 body2 字阶，长说明在
                // 行内被压缩裁切（中文字形切一半），长解释放不下就不该塞进 summary。
                summary = "用于不支持视觉的模型",
                checked = visionEnabled,
                onCheckedChange = { visionEnabled = it },
            )
        }

        // 表单级校验失败时直接说清是哪一项，而不是只把保存键置灰。
        // touched 语义见 ZhiFieldError：没碰过任何输入框的新建表单不该一进来就飘红。
        ZhiFieldError(
            message = draft.nameError ?: draft.baseUrlError,
            touched = nameTouched || baseUrlTouched,
        )
    }
}
