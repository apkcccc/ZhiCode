package com.zhizhu.zhicode.compose.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zhizhu.zhicode.compose.data.SessionReader
import com.zhizhu.zhicode.compose.model.ChatImage
import com.zhizhu.zhicode.compose.theme.ZhiRadius
import com.zhizhu.zhicode.compose.ui.settings.rememberLastNonNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图片解码与显示。
 *
 * ## 为什么是 BitmapFactory 而不是图片加载库
 *
 * 工程没有 coil / glide 依赖，而构建走 `--offline`（不能临时拉新依赖），
 * 所以这里用平台自带的 `BitmapFactory`。对我们面对的场景——**用户自己刚发过的
 * 那几张图，字节已经在内存/会话文件里**——加载库的核心价值（网络、磁盘缓存、
 * 生命周期取消）都用不上，真正需要的是「别 OOM」。
 *
 * ## 三个必须自己处理的点
 *
 * 1. **降采样**：一张 4000×3000 的手机截图按原尺寸解码是 48 MB 的 ARGB_8888，
 *    而缩略图只要 200dp。`inSampleSize` 必须在解码**前**算好，所以先用
 *    `inJustDecodeBounds` 量一遍尺寸（这一步不分配像素）。
 * 2. **缓存**：`LazyColumn` 里滚出屏幕的项会被回收，`remember` 随之丢失；
 *    没有跨项缓存的话每次滚回来都要重解码（base64 → 字节 → 位图）。
 * 3. **失败要看得见**：解码失败时**回退成文件名**，而不是留一块空白 ——
 *    空白无法与"还在解码"区分，用户只会以为又坏了。
 */

/**
 * 目标边长与降采样倍数。
 *
 * 抽成**纯 Int 函数**是因为这是整条链路唯一真正会算错的地方，而它算错的两种后果
 * （倍数太小 → OOM；太大 → 图糊成马赛克）都只能靠肉眼发现。纯函数可以用单测钉死。
 *
 * 规则（与 Android 官方 `BitmapFactory` 文档的"高效加载大图"一致）：
 * 取**不超过目标的 2 的幂**。不用"四舍五入到最近的 2 的幂"，因为向下取整才是
 * 安全方向——宁可多留一点像素，也不能让解码结果比目标还小。
 *
 * @return 至少为 1；尺寸未知（<= 0）或目标为 0 时也返回 1
 */
internal fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    if (targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    // 条件是"仍然偏大"，两边都满足才继续加倍：一张很扁的图不该按高度一直降到糊。
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

/**
 * 一次解码的产物：位图 + **原图尺寸**。
 *
 * <p>为什么要连原图尺寸一起带出来：缩略图要"同高不等宽"（见 [ZhiImageThumb]），
 * 而宽度必须由**原图长宽比**算出来 —— 只看解码后的位图是做不到的，因为
 * 降采样后的位图在还没解出来时不存在，而且它自己的尺寸是"降到多少就是多少"。
 * 长宽比只有 `inJustDecodeBounds` 那一遍能拿到。
 *
 * <p>尺寸在**头解析成功**时就有值，即使随后真正解码失败（例如内存不够）也拿得到 ——
 * 所以占位框从一开始就能按正确比例留好，不会等图出来再"跳"一下宽度。
 */
private class Decoded(val bitmap: ImageBitmap?, val sourceWidth: Int, val sourceHeight: Int) {
    /** 长宽比；尺寸未知时为 null。 */
    val aspect: Float?
        get() = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else null
}

/** base64 → 该分辨率下的位图 + 原图尺寸。整体失败时尺寸为 0。 */
private fun decode(base64: String, reqWidth: Int, reqHeight: Int): Decoded {
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
        ?: return Decoded(null, 0, 0)
    if (bytes.isEmpty()) return Decoded(null, 0, 0)

    // 第一遍：只量尺寸，不分配像素。
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Decoded(null, 0, 0)

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        // RGB_565 每像素 2 字节而不是 4：缩略图上肉眼无差别，内存直接减半。
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    // 即使位图没解出来，尺寸也照带 —— 占位框能按正确比例留好。
    return Decoded(bitmap?.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

/**
 * 跨重组、跨列表项的图片缓存。
 *
 * 用**字节数**做上限而不是条数：一张缩略图可能 20 KB，也可能 2 MB，
 * 按条数限制在两种极端的图上都会做出错误决定。
 *
 * <h3>容量为什么按堆算，而不是写死 8MB</h3>
 * 原先写死 `8 * 1024 * 1024`，而记账用的是 4 字节/像素。真机日志给出的实际尺寸是
 * **540×1200**（一张竖屏截图经 `sampleSizeFor(1080,2400,520,280)` 降采样后的结果）——
 * 一项就是 `540*1200*4 = 2.47MB`，**8MB 只装得下 3.24 项**。
 *
 * 于是滚动时：第 4 张图进来就把第 1 张挤出去，滚回去又要重新解码一次
 * （`Base64.decode` 约 1.4MB + 位图 1.24MB 的瞬时分配）。日志里
 * 「18 秒 24 次 GC、分配速率约 33MB/s」的抖动，这条路径贡献了一份。
 *
 * 现在按可用堆定容：`largeHeap` 之后上限是 512MB，取 1/12 约 42MB，
 * 足以留住十几张缩略图；同时封顶 48MB，免得在超大堆设备上无限膨胀。
 */
private val imageCache = object : LruCache<String, Decoded>(imageCacheBudgetBytes()) {
    override fun sizeOf(key: String, value: Decoded): Int {
        // 没有位图的条目（解码失败 / 只量到尺寸）没有真实内存占用，
        // 但不能记 0：LruCache 靠 size 决定淘汰，全 0 就永远不淘汰，
        // 这种条目会无限积累。给一个名义值让它仍然能被挤出去。
        val bitmap = value.bitmap ?: return 1024
        // 记账用 RGB_565 的**真实**占用（2 字节/像素，见 [decode] 里的 inPreferredConfig）。
        // 原先记 4 字节/像素是保守的（更早淘汰、更省内存），但同时让缓存能装的张数
        // 比实际能装的一半还少 —— 那正是"滚回去就重新解码"的来源。
        return bitmap.height * bitmap.width * 2
    }
}

/** 图片缓存预算：堆的 1/12，最少 8MB、最多 48MB。 */
private fun imageCacheBudgetBytes(): Int {
    val heap = runCatching { Runtime.getRuntime().maxMemory() }.getOrDefault(0L)
    if (heap <= 0L) return 8 * 1024 * 1024
    val twelfth = heap / 12
    return twelfth.coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
}

/** 缓存键：内容本身（base64 很长，取哈希）+ 目标尺寸。 */
private fun cacheKey(data: String, reqWidth: Int, reqHeight: Int): String =
    "${data.hashCode()}:${data.length}:${reqWidth}x$reqHeight"

/**
 * 缓存键：**按图的位置**，而不是按它的字节。
 *
 * 历史图片的内存里没有字节（见 [ChatImage.origin]），所以哈希一个不存在的字符串
 * 是没意义的；位置本身就是全局唯一的，而且比 base64 短得多。
 *
 * 尺寸仍然进 key：同一张图在缩略图和放大查看里是两个不同的位图。
 */
private fun cacheKeyFor(image: ChatImage, reqWidth: Int, reqHeight: Int): String {
    val origin = image.origin
    return if (origin == null) cacheKey(image.data, reqWidth, reqHeight)
    else "img:${origin.sessionId}:${origin.messageSeq}:${origin.ordinal}:${reqWidth}x$reqHeight"
}

/**
 * 拿到这张图真正要解码的字节。
 *
 * 历史图片的 base64 留在库里，只在真要画这一张时取一次 —— 这一步会读文件并解码，
 * 所以由调用方放在 `Dispatchers.Default` 上跑。缓存命中、滚动经过都不走这里。
 */
private suspend fun resolveImageData(image: ChatImage): String {
    if (image.data.isNotEmpty()) return image.data
    val origin = image.origin ?: return ""
    return withContext(Dispatchers.Default) { SessionReader.imageData(origin) }
}

/**
 * 解码一张图（带缓存与降采样）。
 *
 * @param reqWidth 期望宽（px）；给 0 表示按原尺寸解码
 * @return null 表示解码失败或还在解 —— 调用方要能区分这两者时用 [rememberDecodeState]
 */
@Composable
internal fun rememberDecodedImage(image: ChatImage, reqWidth: Int, reqHeight: Int): ImageBitmap? =
    rememberDecodeState(image, reqWidth, reqHeight).bitmap

/**
 * 解码状态。
 *
 * 之所以要把"失败"与"还在解"分开：两者都表现为没有位图，但**该显示的东西不同**
 * —— 还在解时留一块占位，失败时显示文件名。只看 `ImageBitmap?` 是分不出来的。
 *
 * [sourceWidth] / [sourceHeight] 是**原图**尺寸（不是解码结果），
 * 供缩略图按长宽比定宽用；还量不到时为 0。
 */
internal class DecodeState(
    val bitmap: ImageBitmap?,
    val failed: Boolean,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
) {
    /** 原图长宽比；尺寸未知时为 null。 */
    val aspect: Float?
        get() = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else null
}

@Composable
internal fun rememberDecodeState(image: ChatImage, reqWidth: Int, reqHeight: Int): DecodeState {
    val key = cacheKeyFor(image, reqWidth, reqHeight)
    // 缓存命中时同步返回，不闪一下占位。
    val cached = remember(key) { imageCache.get(key) }
    val state by produceState(
        initialValue = if (cached != null) DecodeState(cached.bitmap, false, cached.sourceWidth, cached.sourceHeight)
        else DecodeState(null, false),
        key1 = key,
    ) {
        if (cached != null) return@produceState
        // 历史图片的字节不在内存里（见 [ChatImage.origin]）：第一步是把它取回来。
        // 取不到就按失败处理 —— 界面上会显示文件名，而不是永远转圈。
        val data = resolveImageData(image)
        if (data.isEmpty()) {
            value = DecodeState(null, true)
            return@produceState
        }
        val decoded = withContext(Dispatchers.Default) { decode(data, reqWidth, reqHeight) }
        imageCache.put(key, decoded)
        value = DecodeState(decoded.bitmap, decoded.bitmap == null, decoded.sourceWidth, decoded.sourceHeight)
    }
    return state
}

// ------------------------------------------------------------------ 组件

/**
 * 由统一高度和原图长宽比算出这一张的宽度。
 *
 * 抽成纯函数有两个理由：
 *
 * 1. **`coerceIn` 在 min > max 时会抛 IllegalArgumentException**。调用点有两个
 *    （气泡行、待发方框），待发那个把上下限都钉在 56dp，气泡那个是 72..260 ——
 *    一旦有人改错顺序就是"打开对话直接崩"。这里先把上下限排好，把崩溃面消掉。
 * 2. 夹取是这段代码里唯一能算错的部分（算错的表现是全景图占满整屏、或长截图细成
 *    一条线），而这两种都只能靠肉眼发现，正适合用单测钉死。
 *
 * @param aspect 原图 宽/高；未知（null 或非正）时返回一个正方形占位宽度
 * @return 已夹进 `[min(minWidth,maxWidth), max(minWidth,maxWidth)]` 的宽度
 */
internal fun thumbWidthFor(height: Float, aspect: Float?, minWidth: Float, maxWidth: Float): Float {
    if (aspect == null || aspect <= 0f || !aspect.isFinite()) return height
    val low = minOf(minWidth, maxWidth)
    val high = maxOf(minWidth, maxWidth)
    return (height * aspect).coerceIn(low, high)
}

/**
 * 缩略图：**同高、宽度随长宽比**。
 *
 * ## 为什么要固定高度
 *
 * 早先这里是「框一个上限，图按自己的比例缩进去」，结果一排缩略图宽度高度全不一样，
 * 看着参差不齐 —— 用户的原话是"不能同高吗，写的好丑"。照片墙的常规做法就是
 * **统一高度、宽度按比例**，一行读起来才像一条带子。
 *
 * 宽度由 `原图长宽比 × 高度` 算出（见 [thumbWidthFor]），再夹进
 * [minWidth]..[maxWidth]：极宽的图（全景）会被夹住，否则一张图就占满整屏；
 * 极窄的图（长截图）也要夹住，否则会细成一条线看不出内容。
 *
 * ## 被夹住时用 Crop
 *
 * 比例算出的宽度**没被夹**时，框的长宽比与原图完全一致，`Crop` 与 `Fit` 结果相同
 * （都不裁）。被夹住时才真的裁 —— 此时宁可裁掉一点也要保证**视觉高度一致**：
 * 一排放下来高度参差正是要修的问题。极端长宽比（全景/长截图）裁中间，
 * 也仍然认得出是哪张图。
 *
 * > 这里与"缩略图一律不裁剪"的早期决定**相反**，是有意的：那条决定服务于
 * > "确认这张图是不是我发的那张"，而用户明确要求同一行同高 —— 同高与不裁剪
 * > 在极端长宽比下不可兼得，取同高。
 *
 * @param height 统一的缩略图高度（同一行里所有图共用）
 * @param maxWidth / @param minWidth 宽度夹取范围
 */
@Composable
internal fun ZhiImageThumb(
    image: ChatImage,
    height: Dp,
    maxWidth: Dp,
    minWidth: Dp = ThumbMinWidth,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // 传 dp 而不是 px：解码器要的是像素，但这里拿不到 density 以外的信息，
    // 而多留一倍余量（×2）比引入 LocalDensity 更简单也更保守 —— 高密度屏上
    // 少降一档，图更清楚，内存仍在有界范围内。
    val state = rememberDecodeState(image, (maxWidth.value * 2).toInt(), (height.value * 2).toInt())

    // 宽度按原图长宽比算；尺寸还没量到时先占一个方框。
    // 占位宽度必须**稳定**：先方框、量到比例后再变宽，会让整行在两三帧内抖一下，
    // 所以只在拿到比例时才改宽度，且在下一帧就定下来。
    val width = thumbWidthFor(height.value, state.aspect, minWidth.value, maxWidth.value).dp

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(ZhiRadius.inner))
            .background(scheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 解码中与失败都先占位：失败时多一行文件名，用户至少知道这里本来是什么。
            FallbackLabel(name = image.name, failed = state.failed, maxWidth = maxWidth)
        }
    }
}

/** 解码不出来时的兜底：文件名 + （失败时）一行说明。 */
@Composable
private fun FallbackLabel(name: String, failed: Boolean, maxWidth: Dp, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxWidth - 20.dp),
        )
        if (failed) {
            Text(
                text = "图片无法显示",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = scheme.onSurfaceVariantSummary,
            )
        }
    }
}

// ------------------------------------------------------------------ 输入器里的附件缩略图

/**
 * 输入器里的待发图片：缩略图 + 右上角圆形删除键。
 *
 * 参考图里就是这个形态（图片本身就是那个"芯片"，X 叠在右上角），
 * 而工程原来的 `AttachmentChip` 是「图标 + 文件名」，图片和文件长得一样 ——
 * 选了三四张截图之后，根本认不出哪张是哪张，只能靠文件名猜。
 *
 * 尺寸固定成小方块：删除键要压在图片**上面**，图片自适应大小的话，
 * X 的位置会随图变形，看起来像没对齐。
 */
@Composable
internal fun ZhiPendingImageChip(
    image: ChatImage,
    onRemove: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    Box(modifier = Modifier.size(PendingImageSize)) {
        ZhiImageThumb(
            image = image,
            height = PendingImageSize,
            maxWidth = PendingImageSize,
            // 上下限都钉在同一个值上 = **固定正方形**。待发区里不需要"按比例不等宽"，
            // 反而必须等宽：删除键压在右上角，宽度一变 X 的位置就会跟着飘。
            minWidth = PendingImageSize,
            onClick = onClick,
        )
        // 删除键：走工程统一的 ZhiFilledIconButton（square = false 时 Miuix 用
        // size/2 作圆角，就是正圆），而不是自己拼 Surface + CircleShape ——
        // 自己拼的那个没有 Miuix 的按压反馈，点起来像没反应。
        ZhiFilledIconButton(
            icon = ZhiIcons.close,
            description = "移除图片",
            onClick = onRemove,
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            iconSize = 11.dp,
            size = 20.dp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 输入器里待发图片的边长。与附件条高度同一量级。 */
private val PendingImageSize = 56.dp

// ------------------------------------------------------------------ 放大查看

/**
 * 点缩略图后的放大查看。
 *
 * <p>形态照搬原版 `showFullImage()`：**全屏黑底 + `FIT_CENTER` + 右上角 ×**。
 * 之前是一个 `maxWidth = 320dp` 的普通弹窗，问题有两个：
 *
 * <p>1. `Image(fillMaxWidth) + ContentScale.Fit` 只约束了宽，**高没有约束** ——
 *    而手机上的 Miuix 弹窗对内容高是不设上限的（`heightIn(max)` 只在
 *    `isLargeScreen` 分支生效，核过 0.9.4 的 `DialogContentLayout`）。
 *    于是竖屏截图按比例撑到六七百 dp 高，直接越过屏幕，"显示过大"。
 * <p>2. 弹窗自带圆角、内边距与 `squircleSurface` 底，一张截图被包在一张
 *    小卡片里，四周留着一圈弹窗底色 —— 就是"预览做得很奇怪"。
 *
 * <p>仍然走 [OverlayDialog]（工程约定：模态一律 Miuix overlay），但把
 * 外边距 / 内边距 / 圆角全部清零、背景给纯黑，让弹窗本身退成一个全屏层；
 * 图片按 `ContentScale.Fit` 塞满整屏 —— 与原版 `FrameLayout(-1,-1) + FIT_CENTER`
 * 的观感一致。点按任意处或按返回都关闭。
 */
@Composable
internal fun ZhiImageViewer(image: ChatImage?, onDismiss: () -> Unit) {
    // 退场时 `image` 已经是 null，内容不能跟着空掉（见 rememberLastNonNull）。
    val shownImage = rememberLastNonNull(image)
    OverlayDialog(
        show = image != null,
        onDismissRequest = onDismiss,
        // 全屏：外边距 / 内边距 / 圆角全清零，背景纯黑。
        // maxWidth 也要放开 —— 手机上虽然用不到（宽度本来就被屏幕夹住），
        // 平板/分屏下不放开的话还是一张 560dp 的小卡。
        maxWidth = ViewerMaxDimension,
        outsideMargin = ViewerZeroInsets,
        insideMargin = ViewerZeroInsets,
        cornerRadius = 0.dp,
        backgroundColor = Color.Black,
        enableWindowDim = false,
        // 状态栏/导航栏的避让由下面自己的 padding 负责：全屏看图时
        // 内容顶到状态栏下面才是"全屏"，× 按钮单独避让即可。
        defaultWindowInsetsPadding = false,
    ) {
        val shown = shownImage ?: return@OverlayDialog
        // 放大查看不做降采样上限（按 2048 解），但仍是 Fit：长截图在屏幕上
        // 本来就只能看到缩略形态，硬裁会把内容藏起来。
        val state = rememberDecodeState(shown, 2048, 2048)
        val bitmap = state.bitmap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 避让状态栏：× 按钮不能顶进刘海/挖孔里。
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = shown.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FallbackLabel(
                    name = shown.name,
                    failed = state.failed,
                    maxWidth = ViewerMaxDimension,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            ZhiFilledIconButton(
                icon = ZhiIcons.close,
                description = "关闭图片",
                onClick = onDismiss,
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
                iconSize = 16.dp,
                size = 40.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp),
            )
        }
    }
}

/** 全屏查看层的尺寸上限（够大，实际都会被屏幕夹住）。 */
private val ViewerMaxDimension = 4096.dp

/**
 * 全屏查看层的边距/内边距清零。不写 `DpSize(0.dp, 0.dp)` 字面量是刻意的：
 * 弹窗外边距统一走 DialogWideOutsideMargin 是工程约定（LayoutConsistencyTest
 * 守着），而这里本来就不是"弹窗边距"——是全屏层的 0，单独起名两不误。
 */
private val ViewerZeroInsets = DpSize(0.dp, 0.dp)

// ------------------------------------------------------------------ 气泡里的图片行

/**
 * 气泡里的一行图片缩略图：**横向可滑动**。
 *
 * ## 为什么是可滑动而不是"最多 N 张 + K"
 *
 * 这里原先写的是 `take(4)` 加一个「+K」角标。它的问题不是不好看，而是**看不全**：
 * 第 5 张起只能看到"还有 3 张"，想确认自己到底发了哪几张就得退出应用去看相册。
 * 用户发的往往就是一串对比截图，恰恰需要左右翻着比对。
 *
 * ## 横向滚动会不会抢走"滚对话"的手势
 *
 * 不会，这是两个轴：Compose 的 `horizontalScroll` 只消费横向拖拽，竖向拖拽会穿透到
 * 外层的 `LazyColumn`。所以当初"怕和对话滚动打架"那个顾虑是不成立的 ——
 * 唯一要守的是别在**同一轴**上再套一层滚动。
 */
@Composable
internal fun ZhiImageRow(images: List<ChatImage>, onOpen: (ChatImage) -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            ZhiImageThumb(
                image = image,
                height = BubbleImageHeight,
                maxWidth = BubbleImageMaxWidth,
                onClick = { onOpen(image) },
            )
        }
    }
}

/**
 * 气泡里缩略图的**统一高度**。
 *
 * 140dp 是为了**长截图**：再高的话一张竖屏长图就把整条对话顶出屏幕。
 * 同一行里所有图共用这个高度，宽度按各自长宽比算 —— 这才是一条整齐的图片带。
 */
private val BubbleImageHeight = 140.dp

/**
 * 单张缩略图的宽度上限。
 *
 * 全景图按比例算出来可能有四五百 dp，一张就占满整屏、后面的图全被推到屏幕外，
 * 横向滑动就失去意义了。夹到 260dp 之后一行至少能看到一张半。
 */
private val BubbleImageMaxWidth = 260.dp

/**
 * 单张缩略图的宽度下限。
 *
 * 长截图（例如 1:4）按比例算出来只有 35dp 宽，细成一条线看不出内容。
 * 夹到 72dp 至少能认出是哪张，配合 `Crop` 也仍然同高。
 */
private val ThumbMinWidth = 72.dp
