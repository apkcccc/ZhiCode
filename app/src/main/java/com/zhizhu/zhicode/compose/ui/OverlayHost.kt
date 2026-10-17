package com.zhizhu.zhicode.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.zhizhu.zhicode.compose.model.WorkspaceUiState
import com.zhizhu.zhicode.compose.state.WorkspaceViewModel
import com.zhizhu.zhicode.compose.ui.pages.overlays.AttachFileOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.ChoicePickerOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.CopySheetOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.EnvironmentOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.ModelPickerOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.PermissionOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.PlanApprovalOverlay
import com.zhizhu.zhicode.compose.ui.pages.overlays.TaskListOverlay

/**
 * 弹窗挂载宿主：从 `ZhiCodeScreen` 原地拆出，内容逐字未改。
 *
 * ---- Overlay 系列：必须位于 Scaffold 内容里才能找到 popupHost ----
 * 弹窗打开时，先铺一层「模糊背景」：
 * Miuix 的 blur 只能在**同一个窗口**内采样背景（LayerBackdrop 录的是本窗口
 * 的 GraphicsLayer），所以没法在弹窗自己的窗口里做模糊 —— 改成在主窗口里
 * 把整个工作区糊掉，弹窗再画在它上面。视觉效果与 MIUI 的模态背景一致。
 * 长按动作菜单由**被长按的那一项**自己渲染（贴它弹出），不是居中对话框。
 * 所以它既不该进 modalOpen（那会给整个工作区再加一层背景模糊，而弹层
 * 自己已经带窗口变暗），也不该再走 ChoicePickerOverlay。
 */
@Composable
internal fun ZhiOverlayHost(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    glassMain: Glass,
) {
    val anchoredActionMenu = state.choicePicker?.isActionMenu == true
    // 背景模糊层按用户要求移除：OverlayDialog 自带窗口变暗（windowDimming），
    // 再叠一层整屏 blur 属于重复的视觉噪音。保留 modalOpen 判断是为了
    // 后续若要按弹窗区分遮蔽策略时有个现成挂点。
    val modalOpen = state.permissionRequest != null ||
        state.planApproval != null ||
        (state.choicePicker != null && !anchoredActionMenu) ||
        state.environmentOpen ||
        state.attachPickerOpen ||
        state.taskListOpen

    PermissionOverlay(
        request = state.permissionRequest,
        onAllowOnce = { viewModel.resolvePermission(allow = true) },
        onAlwaysAllow = { viewModel.resolvePermission(allow = true, alwaysAllow = true) },
        onDeny = { viewModel.resolvePermission(allow = false) },
    )
    PlanApprovalOverlay(
        plan = state.planApproval,
        onApprove = { viewModel.resolvePlan(true) },
        onRevise = { viewModel.resolvePlan(false) },
        onReviseWithFeedback = viewModel::resolvePlanWithFeedback,
    )
    ChoicePickerOverlay(
        // 锚定菜单已经由被长按的条目自己画了，这里必须让位 ——
        // 否则长按后会出现「下拉菜单 + 居中对话框」同时可见。
        picker = if (anchoredActionMenu) null else state.choicePicker,
        onSubmit = viewModel::onSubmitSelection,
        onDismiss = viewModel::dismissChoicePicker,
        onSubmitFreeForm = viewModel::onSubmitFreeForm,
    )
    // 「自由复制」底部抽屉：把一条消息的原文摊开让人划词。
    // 放在这里而不是对话页里，与其它浮层同处一个挂载点 —— 它同样要在
    // Scaffold 的 popupHost 上渲染，而对话页在宽屏下只是三个面板之一。
    CopySheetOverlay(
        sheet = state.copySheet,
        onDismiss = viewModel::closeCopySheet,
        onCopyAll = viewModel::copySheetAll,
    )
    AttachFileOverlay(
        open = state.attachPickerOpen,
        browser = state.attachBrowser,
        onFilterChange = viewModel::updateAttachFilter,
        onNavigate = viewModel::attachNavigateTo,
        onSwitchRoot = viewModel::attachSwitchRoot,
        onPick = { entry -> viewModel.attachProjectFile(entry.path) },
        onDismiss = viewModel::closeAttachPicker,
    )
    TaskListOverlay(
        tasks = state.tasks,
        open = state.taskListOpen,
        onDismiss = viewModel::closeTaskList,
    )
    EnvironmentOverlay(
        open = state.environmentOpen,
        report = state.environmentReport,
        runtimeReady = state.runtimeReady,
        installing = state.runtimeInstalling,
        progress = state.runtimeProgress,
        message = state.runtimeMessage,
        onDismiss = viewModel::closeEnvironment,
        onInstall = viewModel::installRuntime,
        onRepair = viewModel::repairRuntime,
        onRefresh = viewModel::refreshEnvironmentReport,
        onCopy = { viewModel.copyEnvironmentReport() },
    )
    ModelPickerOverlay(
        picker = state.modelPicker,
        onDismiss = viewModel::closeModelPicker,
        onQueryChange = viewModel::setModelQuery,
        // 点一行：立刻选中并保存，面板不关（高亮就是在这一步跟过去的）
        onPickModel = viewModel::selectModel,
        // 换一份 API 记录：重新拉它自己的模型目录
        onSelectProfile = viewModel::selectModelPickerProfile,
        // 主按钮：选择在点击那一刻已经存好，这里只是应用手动输入的名字并收起面板
        onUse = viewModel::applySelectedModel,
        // 额度卡片的「刷新」：重拉模型与额度（参考图的额度卡片也带这个按钮）
        onRefreshQuota = viewModel::refreshModelCatalog,
        onAddApi = {
            viewModel.closeModelPicker()
            // 先打开配置页、再进空白表单：tab 栏已经承担了"切到已有配置"，
            // 这个按钮剩下的语义就是**新增**，停在列表页与按钮名字不符。
            viewModel.openApiConfig()
            viewModel.newApiProfile()
        },
    )
}
