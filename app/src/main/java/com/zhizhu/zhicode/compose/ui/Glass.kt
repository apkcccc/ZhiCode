package com.zhizhu.zhicode.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.zhizhu.zhicode.compose.theme.LocalThemeSettings
import com.zhizhu.zhicode.compose.theme.ZhiColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮层的「玻璃」效果（背景虚化 + 半透明底），带运行时门控。
 *
 * ## 为什么要门控
 * Miuix 的模糊是 `miuix-blur` 里的 `Modifier.textureBlur`，底层是 **RuntimeShader**，
 * **Android 上要求 API 33+**（官方文档明确写了这是整个库的硬下限）。
 * 工程 `minSdk = 24`，所以低版本设备必须整条路径跳过，否则会崩。
 * 判定用 Miuix 自己的能力检查 `isRuntimeShaderSupported()`，
 * 而不是自己判断 `Build.VERSION.SDK_INT`，这样非 Android 目标也能正确返回 true。
 *
 * ## 用法（两步）
 * 1. 在**被压住的内容**那层调用 [capture]，让它把背景内容录进 layer；
 * 2. 在**悬浮层**上调用 [blur] + [surfaceColor]。
 *
 * 不支持模糊时两个方法都是恒等变换（[surfaceColor] 返回原色），
 * 所以调用点不需要写任何 `if`。
 */
@Composable
fun rememberGlass(): Glass {
    val isDark = ZhiColors.isDark()
    // 用户可以在「主题设置 → 显示 → 模糊」里关掉虚化。关掉时**连 layerBackdrop 都不建**：
    // 那不只是"少画一层"，而是整条 RuntimeShader 采样路径都不再被走到 ——
    // 这是本页四个显示开关里最值钱的一个（虚化每帧都要重采样一张全屏贴图）。
    val enabled = LocalThemeSettings.current.blurEnabled
    val liquid = LocalThemeSettings.current.liquidGlass
    val supported = remember { isRuntimeShaderSupported() && enabled }
    // 关键：不支持时**连 layerBackdrop 都不创建**。
    // 只在 Modifier 上做恒等替换还不够 —— 光是把 blur 的代码路径执行到，
    // 在 API < 33 上就可能因为 RuntimeShader 缺失而崩。
    return if (supported) {
        // 捕获前先画一层不透明背景色：文档指出 layerBackdrop 只捕获自己的内容，
        // 若内容有透明区域，模糊会把颜色扩散进透明像素、出现明显色块。
        key(isDark) {
            val background = MiuixTheme.colorScheme.background
            // 深浅切换时必须重建 backdrop；否则 RuntimeShader 继续持有上一主题的
            // 背板快照，输入器和顶栏会出现蓝灰色旧色残留。
            val backdrop = rememberLayerBackdrop {
                drawRect(background)
                drawContent()
            }
            remember(backdrop, isDark, liquid) { Glass(backdrop, isDark, liquid) }
        }
    } else {
        remember(isDark, liquid) { Glass(null, isDark, liquid) }
    }
}

/** 由 [rememberGlass] 创建，集中承载门控逻辑。 */
class Glass internal constructor(
    internal val backdrop: LayerBackdrop?,
    private val dark: Boolean,
    /**
     * 是否启用「液态玻璃」观感：更透 + 描边更亮。
     *
     * <p>它**只在能虚化时才有意义**：不虚化却把底压实，露出来的是下面那层内容本身，
     * 读起来就是"面板没画完"。所以 [surfaceColor] 里它仍受 [supported] 门控。
     */
    private val liquid: Boolean,
) {
    /** 当前设备/Runtime 是否支持 RuntimeShader 模糊。 */
    val supported: Boolean get() = backdrop != null

    /** 加在被模糊层压住的内容上：把它的绘制录进 layer。 */
    fun capture(modifier: Modifier): Modifier =
        if (backdrop != null) modifier.layerBackdrop(backdrop) else modifier

    /** 加在悬浮层上：对已捕获的背景做高斯模糊。 */
    fun blur(modifier: Modifier, shape: Shape, radius: Float = 24f): Modifier =
        if (backdrop != null) {
            modifier.textureBlur(backdrop = backdrop, shape = shape, blurRadius = radius)
        } else {
            modifier
        }

    /**
     * 悬浮层的底色。
     *
     * 能模糊时压低不透明度，否则模糊会被不透明底色完全挡住、白做；
     * 不支持时保持原来的不透明底色，观感与改动前一致。
     */
    @Composable
    fun surfaceColor(color: Color): Color =
        if (backdrop != null) {
            // 浅色也必须保留透明度：alpha=1f 会把 textureBlur 的结果完全盖住，
            // 所以发送框和底部悬浮栏看起来就像普通白色卡片。约 78% 能保留白色
            // 的干净感，同时让后面的消息/代码块轮廓透出，立体感由 blur + 阴影共同完成。
            //
            // 「液态玻璃」再压一档：那是这个开关唯一的语义（更透 + 更薄的边缘），
            // 而它必须比不透明底线高 —— 否则面板在深色下会糊成一片读不出边界。
            val base = if (!dark) 0.78f else 0.72f
            val alpha = if (liquid) base - 0.12f else base
            color.copy(alpha = alpha)
        } else color

    /**
     * 悬浮层的描边高光。
     *
     * 与 [surfaceColor] 用同一个开关：玻璃的边缘高光与"更透"是同一件事的两面，
     * 只改其中一个会得到"很透但边缘发死"或者"不透却有一圈亮边"。
     */
    @Composable
    fun edgeHighlight(): Color {
        val alpha = when {
            backdrop == null -> 0f
            liquid && dark -> 0.16f
            liquid -> 0.78f
            dark -> 0.10f
            else -> 0.62f
        }
        return Color.White.copy(alpha = alpha)
    }
}
