package com.zhizhu.zhicode.compose.model

/**
 * 在飞动作的登记表：同一时刻同一个动作只允许跑一份。
 *
 * ## 解决什么问题
 *
 * 页面上很多按钮点下去会立刻发一次 IO（保存、创建、删除、粘贴、测试连接），
 * 而 IO 返回之前界面**没有任何可见变化**。用户会很自然地再点一次，
 * 于是同一件事跑了两遍：
 *
 * - 「粘贴」双击 = 同一批文件复制两次（第二次覆盖，看起来像成功，实际上多做了一倍读写）；
 * - 「创建技能」双击 = 第二次走进"已存在"分支，提示语与第一次自相矛盾；
 * - 「删除」双击 = 第二次在已经消失的路径上失败，弹出"删除失败"。
 *
 * 这些都不是界面卡了，而是**动作本身没有互斥**。
 *
 * ## 为什么是纯 Kotlin 对象而不是 Compose 状态
 *
 * 互斥是**业务规则**，与是否正在绘制无关：把这个判断放进 Composable 意味着
 * 换一个入口（侧栏、斜杠命令、Agent 工具）就绕过了它。所以登记表放在模型侧，
 * 由 ViewModel 在动作入口处 acquire / release；界面要显示"正在保存…"时，
 * 读的是 [WorkspaceUiState.inFlight] 这份快照。
 *
 * ## 为什么不用锁
 *
 * 所有 acquire / release 都发生在**主线程**（ViewModel 的公开入口与协程的
 * finally 都在 Main 调度器上），所以这里不需要同步原语；加锁只会让人误以为
 * 它可以在任意线程调用。
 */
class ActionBusy {

    private val keys = linkedSetOf<String>()

    /**
     * 尝试占用 [key]。
     *
     * @return true 表示由**本次调用**拿到，调用方必须在 finally 里 [release]；
     *         false 表示同一动作已经在跑，调用方应当直接返回（不要报错、不要提示，
     *         因为用户刚才那次已经在跑了）。
     */
    fun acquire(key: String): Boolean = keys.add(key)

    /** 释放 [key]。重复释放是无害的。 */
    fun release(key: String) {
        keys.remove(key)
    }

    /** [key] 是否在跑。 */
    fun isBusy(key: String): Boolean = key in keys

    /** 任意动作在跑（用于"不要同时开两个破坏性操作"这类全局判断）。 */
    fun anyBusy(): Boolean = keys.isNotEmpty()

    /** 当前快照，交给界面做禁用与进度提示。 */
    fun snapshot(): Set<String> = keys.toSet()

    /** 进程内唯一的登记表。 */
    companion object {
        /** 保存类动作的前缀：`skills.save`、`memory.save`、`api.save` … */
        const val SAVE = "save"

        /** 删除类动作的前缀。 */
        const val DELETE = "delete"
    }
}
