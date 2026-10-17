package com.zhizhu.zhicode.compose.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zhizhu.zhicode.compose.theme.ZhiAppTheme
import com.zhizhu.zhicode.compose.theme.ZhiThemeMode
import com.zhizhu.zhicode.compose.ui.pages.EditorPage

/**
 * 文本编辑器的宿主 Activity。
 *
 * ## 为什么编辑器要单独一个 Activity（而不是主界面里的一个页面）
 *
 * 这是前几轮反复没修好之后定下来的结论，理由只有一条：**同一个窗口里，
 * 返回与输入法都必须只有一个主人**。
 *
 * 编辑器原先住在主界面的 `NavDisplay` 里，于是：
 *
 * · 返回有三个候选接管者 —— 主界面自己的返回处理、`NavDisplay` 内部注册的
 *   `PredictiveBackHandler`、编辑页里的 `BackHandler`。真机日志里能看到
 *   `WindowOnBackDispatcher: setTopOnBackInvokedCallback` 被反复重新注册，
 *   而未保存确认框"有时不出现"就发生在这中间；
 * · 输入法与对话输入器共用一个窗口 —— 两边都在动焦点与 `softInputMode`，
 *   日志里每次 `show(ime())` 之后几百毫秒就有一次**应用自己发起的** `hide(ime())`。
 *
 * 现在这一屏只做四件事，和 ZalithLauncher2 的 `FileManagerActivity` 同一个形状：
 * 设置窗口（edge-to-edge 与状态栏）→ 建 ViewModel 读盘 → 装主题 → 独占返回。
 * 弹窗、加载错误、保存中都在这一屏内部，主界面完全不知道编辑器的存在。
 *
 * ## 返回是**框架级**的，不经过 Compose
 *
 * `onBackPressedDispatcher.addCallback` 在 `onCreate` 里注册一次，从此返回键、
 * 返回手势、预测性返回都只走这一条：[handleBack]。没有第二个候选者，
 * 所以"编辑后返回必弹确认框"是结构上成立的，不依赖任何组件的注册顺序。
 */
class EditorActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PATH = "com.zhizhu.zhicode.editor.EXTRA_PATH"

        /**
         * 打开编辑器的 Intent。
         *
         * 只传**路径**：正文、编码、是否可写全部由编辑器自己重新判定。
         * 把正文塞进 Intent 会撞上 `TransactionTooLargeException`
         * （1MB 左右的 binder 上限），而且会掩盖"文件在打开后被改过"这件事。
         */
        fun intent(context: Context, path: String): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_PATH, path)
    }

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge 必须在 super.onCreate 之前：它设的是窗口 decor 的布局策略，
        // 晚于内容视图创建就只在下一帧生效（会看到一次状态栏闪动）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyStatusBarAppearance()

        // 这一屏的窗口策略来自清单：adjustResize，且**不带** stateAlwaysHidden。
        // 这里不再做任何运行时切换 —— 之前那套"进编辑页就改窗口 softInputMode"的补丁
        // 之所以必要，正是因为编辑器和对话输入器挤在同一个窗口里。

        viewModel.load(intent.getStringExtra(EXTRA_PATH).orEmpty())

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        // 主题值只读一次：`stored()` 要走一次 SharedPreferences，放在 lambda 里
        // 会变成「每次根重组都读一次盘」。
        val themeMode = ZhiThemeMode.stored(this)
        setContent {
            // 与主界面同一个主题栈（ZhiThemeMode 是深浅的唯一判定处）。
            ZhiAppTheme(mode = themeMode) {
                EditorPage(viewModel = viewModel, onExit = { finishWithResult() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从别的窗口（输入法设置、分享面板…）回来时系统栏外观可能被改过，对齐一次。
        applyStatusBarAppearance()
    }

    /**
     * 唯一的返回入口。
     *
     * 顺序是刻意的：确认框开着 → 先收起确认框（"返回"在弹窗语义里就是取消）；
     * 保存中 → 先取消保存（别让用户卡在一个没有出口的进度上）；改过 → 问要不要保存；
     * 都没发生 → 直接关。
     */
    private fun handleBack() {
        val state = viewModel.state.value
        when {
            state.exitConfirm -> viewModel.cancelExitConfirm()
            state.saving -> viewModel.cancelSave()
            state.dirty -> viewModel.requestExitConfirm()
            else -> finishWithResult()
        }
    }

    /** 关掉这一屏，并把"有没有保存过"回给主界面（它据此刷新文件列表）。 */
    private fun finishWithResult() {
        setResult(if (viewModel.savedAtLeastOnce) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    /** 状态栏透明 + 图标明暗跟随应用主题；判定与落地都在 [ZhiThemeMode]。 */
    private fun applyStatusBarAppearance() {
        ZhiThemeMode.appliedTo(
            this,
            ZhiThemeMode.resolve(ZhiThemeMode.stored(this), ZhiThemeMode.systemDark(this)),
        )
    }
}
