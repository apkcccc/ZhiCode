package com.zhizhu.zhicode.compose.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhizhu.zhicode.compose.ui.ZhiIcons
import com.zhizhu.zhicode.shizuku.ShizukuBridge
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权行：显示当前走到哪一步，点一下就去把它走完。
 *
 * <h3>为什么是「一行 + 一个开关」，而不是一个开关搞定</h3>
 * 这里其实是**两件独立的事**：
 * <ol>
 *   <li>设置里的开关（`shizukuExecutionEnabled`）决定**模型能不能看到**这个工具；</li>
 *   <li>Shizuku 授权决定**命令能不能真的跑**（服务在不在、用户答不答应）。</li>
 * </ol>
 * 把它们合成一个开关，就会出现「开关打开了，但一调用就报授权错误」这种
 * 用户无法自查的状态。所以这一行专门负责后半件事，并把状态原样说出来。
 *
 * <h3>为什么状态要自己订阅，而不是由上层传进来</h3>
 * 授权结果来自**另一个进程**：用户可能在 Shizuku 管理端里授权完再切回来，
 * 也可能服务中途被杀。这两种变化都不会经过 ViewModel 的任何一个 setter，
 * 所以只能挂 Shizuku 自己的监听（绑定/解绑 + 授权结果），在回调里刷新。
 */
@Composable
internal fun ShizukuAccessRow() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ShizukuBridge.state(context)) }
    var requesting by remember { mutableStateOf(false) }
    /** 不能申请的原因；与 [state] 分开，因为它不是「状态」而是「这次点击的结论」。 */
    var hint by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        fun refresh() {
            state = ShizukuBridge.state(context)
        }
        val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ ->
            requesting = false
            refresh()
        }
        val onBinderDead = Shizuku.OnBinderDeadListener { refresh() }
        val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
        // 三个都兜住异常：管理端没装、provider 未注册时这些都在抛出异常，
        // 而那时设置页仍然要能正常显示。
        runCatching { Shizuku.addRequestPermissionResultListener(onPermission) }
        runCatching { Shizuku.addBinderDeadListener(onBinderDead) }
        runCatching { Shizuku.addBinderReceivedListenerSticky(onBinderReceived) }
        refresh()
        onDispose {
            runCatching { Shizuku.removeRequestPermissionResultListener(onPermission) }
            runCatching { Shizuku.removeBinderDeadListener(onBinderDead) }
            runCatching { Shizuku.removeBinderReceivedListener(onBinderReceived) }
        }
    }

    val summary = when {
        requesting -> "等待授权结果…"
        // 委托属性的智能转换不成立（`hint` 是 var by remember），所以显式兜空。
        hint != null -> hint.orEmpty()
        state == ShizukuBridge.GRANTED -> {
            val detail = ShizukuBridge.detail()
            ShizukuBridge.stateLabel(context) + (detail?.let { " · $it" } ?: "")
        }
        else -> ShizukuBridge.stateLabel(context) + " · Agent 还需要这一步才能真正执行命令"
    }

    SettingsEntry(
        title = "Shizuku 授权",
        valueText = summary,
        onClick = {
            hint = null
            when (ShizukuBridge.state(context)) {
                ShizukuBridge.GRANTED -> hint = "已授权，无需重复申请"
                // 服务不在时申请必然失败，先把人送到管理端去启动服务。
                ShizukuBridge.NOT_RUNNING -> {
                    if (!ShizukuBridge.openManager(context)) {
                        hint = "没有找到 Shizuku 管理端：请先安装 Shizuku"
                    }
                }
                ShizukuBridge.UNAVAILABLE -> {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuBridge.PROJECT_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { hint = "无法打开项目主页：${it.message ?: it}" }
                }
                else -> {
                    requesting = true
                    // requestPermission 会直接弹系统/管理端的授权框；返回值非空表示
                    // 这次**根本没能发起请求**（版本过低、服务掉了、拒绝过且不再询问）。
                    hint = ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)
                    if (hint != null) requesting = false
                }
            }
        },
        icon = ZhiIcons.terminal,
        plate = SettingsPlateColors.green,
    )
}

/**
 * 授权请求码。
 *
 * <p>值本身没有含义，只要与回调里比较的那一个相同即可 ——
 * 整个应用只有一个申请入口，所以不需要再区分来源。
 */
private const val SHIZUKU_REQUEST_CODE = 4271
