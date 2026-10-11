package com.zhizhu.zhicode.compose.model

import org.json.JSONArray

/**
 * 一张存在会话库里的图的位置。
 *
 * `(sessionId, messageSeq, ordinal)` 正好是 `content_blocks` 的主键（见
 * `SessionStore.prepareBlock` 用内容块在消息里的**下标**当 `ordinal`），
 * 所以按它取那一块是一次主键查询。
 *
 * 之所以不能让图片字节跟着历史一起常驻：一条长会话里的图片 base64 是几十 MB
 * 量级，而屏幕上一次只有几张可见 —— 详见 [ChatImage.origin]。
 */
data class ImageOrigin(
    val sessionId: String,
    val messageSeq: Long,
    val ordinal: Int,
)

/**
 * 从一串内容块里取出图片块（纯函数，无 Android 依赖）。
 *
 * ## 块的结构
 *
 * ```
 * {"type":"image",
 *  "source":{"type":"base64","media_type":"image/png","data":"<base64>"},
 *  "name":"shot.png"}
 * ```
 *
 * ## 为什么放在 `model` 而不是 `data`
 *
 * 这个形状**有两个来源**，它们互不相关但字节结构完全一致：
 *
 * 1. **会话文件**里的用户消息（`ZhiCodeEngine.buildUserContent` 写入）——
 *    由 [com.zhizhu.zhicode.compose.data.SessionReader] 在读历史时调用；
 * 2. **工具的附加内容**（`ToolExecutionResult.additionalContent`）——
 *    由 `ZhiEngineController` 在工具结束时调用，把沙箱截图/画布文档带回界面。
 *
 * 两个调用方分处 `data` 与 `engine` 两个包，而 `engine` 的既有依赖方向是**只依赖
 * `model`**（它从不 import `data`）。把它留在 `data` 里会让引擎反向依赖数据层，
 * 所以移到这里，两边共用同一份实现 —— 也共用同一份单测
 * （[com.zhizhu.zhicode.compose.model.ChatImageBlocksTest]）。
 *
 * ## 三条必须守住的规则
 *
 * - **任何一块读不出来就跳过那一块**，不影响同一串里的其它图：一条坏数据不该让
 *   整条历史、或整张工具卡的图全部消失；
 * - **只认 base64**。`url` 类型的图要联网加载，与"离线也要能显示"的前提冲突，
 *   得有单独的决定 —— 在那之前不能悄悄当成 base64 去解。
 * - **有位置就算有图**：读历史时内容块里的 `data` 是空的（字节留在库里，见
 *   [ImageOrigin]），此时若只看 `data` 是否为空就会把用户发的图**整张丢掉** ——
 *   正是上面那条"跳过一块"规则最容易踩反的地方。
 *
 * ## 为什么不在这里解码
 *
 * 返回的 [ChatImage] 只**持有 base64 字符串**，不做 `Base64.decode` / `BitmapFactory`。
 * 解码发生在界面层（`ZhiImage.rememberDecodeState`）且跑在 `Dispatchers.Default` 上 ——
 * 这样这个函数可以在主线程被随意调用而没有解码开销。
 *
 * ## 明确不处理的类型
 *
 * `type == "ui_canvas"`（`UiCanvasTool` 的产出）**在这里被静默跳过**，这是有意的：
 * 画布是**实时**机制（`UiCanvasController.apply` 直接改活的视图树，
 * `UiCanvasStore.publishPreview` 负责广播），不是一份可以静态渲染的文档快照；
 * 要把它画进工具卡得另写一个"文档 → 预览"的渲染器，属于另一件事。
 *
 * @param origin 可选：给第 `i` 个块算一个"它在库里的位置"。传了表示调用方**会**
 *   在需要时自己去取字节，返回值里对应的图带 `data = ""`。解析工具附加内容时
 *   不传（那些字节本来就在内存里）。
 */
internal fun readChatImageBlocks(
    content: JSONArray,
    origin: ((Int) -> ImageOrigin?)? = null,
): List<ChatImage> {
    val images = mutableListOf<ChatImage>()
    for (i in 0 until content.length()) {
        val block = content.optJSONObject(i) ?: continue
        if (block.optString("type", "") != "image") continue
        val source = block.optJSONObject("source") ?: continue
        if (source.optString("type", "") != "base64") continue
        val data = source.optString("data", "")
        val place = origin?.invoke(i)
        if (data.isEmpty() && place == null) continue
        images.add(
            ChatImage(
                data = data,
                mimeType = source.optString("media_type", "image/png"),
                name = block.optString("name", "").ifBlank { "图片" },
                origin = place,
            ),
        )
    }
    return images
}
