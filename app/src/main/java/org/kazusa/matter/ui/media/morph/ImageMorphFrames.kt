package org.kazusa.matter.ui.media.morph

import android.util.LruCache
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.withWeiboImageSize

val MORPH_SETTLE_SPEC = spring<Float>(
    dampingRatio = 1.0f,
    stiffness = 280f,
    visibilityThreshold = 0.0001f
)

/**
 * 原图纵横比内存缓存，供信息流与形变动画全局共享，杜绝测量尺寸闪烁与跨图裁剪错误
 */
object ImageRatioCache {
    private val cache = object : LruCache<String, Float>(500) {}
    fun get(url: String): Float? = cache.get(url)
    fun put(url: String, ratio: Float) {
        if (ratio > 0f) {
            cache.put(url, ratio)
        }
    }
}

/**
 * 为全屏形变大图、飞行图片与信息流缩略图构建统一稳定的 Coil 缓存键 ImageRequest：
 * - [dataUrl] 作为真正的请求地址与 memory/disk 缓存键；
 * - [placeholderUrl] 作为占位位图缓存键：信息流已解码的缩略图档位可被全屏原图直接复用，
 *   先显示缩略图再无缝替换为原图，杜绝重解码白屏与闪烁；
 * - 关闭 crossfade，消除多层占位图淡入淡出产生的画面闪烁。
 */
@Composable
fun rememberStableImageRequest(
    dataUrl: String,
    placeholderUrl: String = dataUrl
): ImageRequest {
    val context = LocalContext.current
    return remember(dataUrl, placeholderUrl) {
        ImageRequest.Builder(context)
            .data(dataUrl)
            .memoryCacheKey(dataUrl)
            .diskCacheKey(dataUrl)
            .placeholderMemoryCacheKey(placeholderUrl)
            .crossfade(false)
            .build()
    }
}

/**
 * 形变飞行图与画廊占位统一使用的缩略图档地址：
 * - 动图（.gif）必须保持原档位，否则会丢失动效；
 * - 其余图片降到缩略图档（bmiddle），可直接复用信息流宫格已解码的内存位图，
 *   保证飞行动画起始帧即有画面，杜绝原图未就绪导致的“动画丢失”。
 */
fun String.morphThumbnailUrl(): String =
    if (contains(".gif", ignoreCase = true)) this
    else withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: this

/**
 * 参考 legado-with-MD3 经典形变纯几何计算：
 * 1. 视觉面板使用单一线性或平滑曲线渐变；
 * 2. 封面/图片使用独立双段式缓动：
 *    - 位置分量 e1 = 1 - (1 - t)² （先快后慢，迅速移向目标中心）
 *    - 尺寸分量 e2 = t² （先慢后快，从内部向外自然张开）
 *    - 绝不用一条线性曲线硬等比缩放，避免“一张硬卡片在放大”的呆板感。
 */

const val MORPH_VEIL_START = 0.15f
const val MORPH_VEIL_END = 0.70f

@Immutable
data class FlyingImageFrame(
    val bounds: Rect,
    val cornerRadiusPx: Float,
    val alpha: Float = 1f
)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** 位置曲线：先快后慢 */
internal fun morphPositionEase(t: Float): Float = 1f - (1f - t) * (1f - t)

/** 尺寸曲线：两端平滑缓动（终点切线水平、导数为 0），完全贴合屏幕边缘，消除最后 5% 的距离跳跃 */
internal fun morphSizeEase(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * 计算飞行封面在进度 progress 下的屏幕坐标矩形与微圆角
 * 支持 dragOffset 拖拽偏移，从用户手指松开的位置顺滑飞回卡片槽位
 */
fun computeFlyingImageFrame(
    progress: Float,
    start: Rect,
    end: Rect,
    startCornerRadiusPx: Float,
    endCornerRadiusPx: Float = 0f,
    dragOffset: Offset = Offset.Zero
): FlyingImageFrame? {
    if (start.isEmpty || end.isEmpty) return null
    val t = progress.coerceIn(0f, 1f)
    val e1 = morphPositionEase(t)
    val e2 = morphSizeEase(t)

    // X 轴与 Y 轴位置从手指释放点 (end + dragOffset) 顺滑飞回卡片起点 start
    val endCx = end.center.x + dragOffset.x * t
    val endCy = end.center.y + dragOffset.y * t

    val cx = lerp(start.center.x, endCx, e1)
    val cy = lerp(start.center.y, endCy, e2)

    val initialDragScale = if (dragOffset != Offset.Zero) {
        (1f - (kotlin.math.abs(dragOffset.y) / 1800f)).coerceIn(0.65f, 1f)
    } else {
        1f
    }
    val targetW = end.width * lerp(1f, initialDragScale, t)
    val targetH = end.height * lerp(1f, initialDragScale, t)

    val w = lerp(start.width, targetW, e2)
    val h = lerp(start.height, targetH, e2)

    val maxRadius = minOf(w, h) * 0.5f
    val radius = lerp(startCornerRadiusPx, endCornerRadiusPx, e2).coerceAtMost(maxRadius)

    return FlyingImageFrame(
        bounds = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
        cornerRadiusPx = radius.coerceAtLeast(0f),
        alpha = if (t <= 0.001f) 0f else 1f
    )
}

/**
 * 背景遮罩（深黑 Veil）可见度曲线：
 * 前 15% 保持完全透明，70% 后完全不透明，过渡区间使用 smoothstep 平滑衔接。
 */
fun computeMorphVeil(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    if (t <= MORPH_VEIL_START) return 0f
    if (t >= MORPH_VEIL_END) return 1f
    return smoothstep((t - MORPH_VEIL_START) / (MORPH_VEIL_END - MORPH_VEIL_START))
}

/**
 * 临界阻尼弹簧临近终点的速度限制，防止手势抛掷甩脱
 */
fun computeMorphSettleVelocity(current: Float, target: Float, velocity: Float): Float {
    val distance = kotlin.math.abs(target - current)
    val limit = minOf(4f, kotlin.math.sqrt(280f) * distance)
    return velocity.coerceIn(-limit, limit)
}

/**
 * 计算全屏模式下 ContentScale.Fit 对应的真实几何目标矩形。
 * 只有飞行图片从起点精确生长到这个真实尺寸，全屏与画廊交接时才不会发生任何像素级突变。
 */
fun computeTargetEndBounds(screenBounds: Rect, imageRatio: Float?): Rect {
    if (screenBounds.isEmpty) return screenBounds
    val ratio = if (imageRatio != null && imageRatio > 0f) imageRatio else (screenBounds.width / screenBounds.height)
    val screenW = screenBounds.width
    val screenH = screenBounds.height
    val screenRatio = screenW / screenH

    return if (ratio >= screenRatio) {
        val targetH = screenW / ratio
        val top = screenBounds.top + (screenH - targetH) / 2f
        Rect(screenBounds.left, top, screenBounds.right, top + targetH)
    } else {
        val targetW = screenH * ratio
        val left = screenBounds.left + (screenW - targetW) / 2f
        Rect(left, screenBounds.top, left + targetW, screenBounds.bottom)
    }
}

/**
 * Android 14+ 预测性返回手势插值映射（参考 legado-with-MD3）：
 * 预览阶段最多缩回 45%，松手确认后顺滑收拢回信息流，划回屏幕边缘取消时自然弹回全屏。
 */
fun computePredictiveMorphProgress(startProgress: Float, backProgress: Float): Float =
    startProgress.coerceIn(0f, 1f) * (1f - 0.45f * backProgress.coerceIn(0f, 1f))


