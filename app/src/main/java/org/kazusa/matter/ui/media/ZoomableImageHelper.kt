package org.kazusa.matter.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 大图缩放、长图贴边计算与惯性平移物理模拟工具。
 */
object ZoomableImageCalculator {

    /**
     * 计算能够使长图「占满宽度、左右贴边」的理想缩放倍率。
     *
     * @param containerWidth 视口宽度
     * @param containerHeight 视口高度
     * @param imageRatio 图片自身纵横比（宽 / 高）
     * @return 适合的双击放大倍率（长图优先贴边，常规图片使用 2.5x）
     */
    fun calculateTargetDoubleTapScale(
        containerWidth: Float,
        containerHeight: Float,
        imageRatio: Float
    ): Float {
        if (containerWidth <= 0f || containerHeight <= 0f || imageRatio <= 0f) {
            return 2.5f
        }
        val containerRatio = containerWidth / containerHeight
        // 如果图片的宽高比小于容器宽高比（即高度超过了屏幕长度的长图）
        if (imageRatio < containerRatio * 0.98f) {
            val fittedWidth = containerHeight * imageRatio
            if (fittedWidth > 0f) {
                val fillWidthScale = containerWidth / fittedWidth
                // 若贴边倍率显著大于 1.0x 则优先贴边，否则取 2.5x
                return if (fillWidthScale >= 1.25f) fillWidthScale else 2.5f
            }
        }
        return 2.5f
    }

    /**
     * 计算当前缩放比例下，图片在水平与垂直方向允许平移的最大偏移量（半程半径）。
     */
    fun calculateMaxPanOffset(
        containerWidth: Float,
        containerHeight: Float,
        imageRatio: Float,
        currentScale: Float
    ): Pair<Float, Float> {
        if (containerWidth <= 0f || containerHeight <= 0f || imageRatio <= 0f || currentScale <= 1f) {
            return 0f to 0f
        }
        val containerRatio = containerWidth / containerHeight
        val fittedWidth: Float
        val fittedHeight: Float
        if (imageRatio > containerRatio) {
            fittedWidth = containerWidth
            fittedHeight = containerWidth / imageRatio
        } else {
            fittedHeight = containerHeight
            fittedWidth = containerHeight * imageRatio
        }

        val displayedWidth = fittedWidth * currentScale
        val displayedHeight = fittedHeight * currentScale

        val maxOffsetX = max(0f, (displayedWidth - containerWidth) / 2f)
        val maxOffsetY = max(0f, (displayedHeight - containerHeight) / 2f)
        return maxOffsetX to maxOffsetY
    }

    /**
     * 根据双击触控位置与目标缩放倍率，计算平移锚点偏移。
     */
    fun calculateDoubleTapTargetOffset(
        tapPosition: Offset,
        containerWidth: Float,
        containerHeight: Float,
        imageRatio: Float,
        targetScale: Float
    ): Offset {
        if (targetScale <= 1.05f) return Offset.Zero

        val (maxOffsetX, maxOffsetY) = calculateMaxPanOffset(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            imageRatio = imageRatio,
            currentScale = targetScale
        )

        // 对于长图贴边，水平方向宽度完全占满（maxOffsetX 为 0 或极小），直接居中 0f
        val targetX = if (maxOffsetX <= 1f) {
            0f
        } else {
            ((containerWidth / 2f) - tapPosition.x) * (targetScale - 1f)
        }.coerceIn(-maxOffsetX, maxOffsetX)

        // 垂直方向锚定：以触控位置在屏幕上的相对比例计算平移
        val fractionY = if (containerHeight > 0f) (tapPosition.y / containerHeight).coerceIn(0f, 1f) else 0.5f
        val targetY = ((0.5f - fractionY) * (maxOffsetY * 2f)).coerceIn(-maxOffsetY, maxOffsetY)

        return Offset(targetX, targetY)
    }

    /**
     * 针对惯性平移执行衰减动画，并在触碰边界时平滑回弹。
     */
    fun flingWithInertia(
        scope: CoroutineScope,
        animatable: Animatable<Float, AnimationVector1D>,
        initialVelocity: Float,
        maxBound: Float
    ) {
        if (maxBound <= 0f) {
            scope.launch {
                animatable.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
            return
        }

        val decaySpec = exponentialDecay<Float>(
            frictionMultiplier = 1.25f,
            absVelocityThreshold = 0.5f
        )

        scope.launch {
            var hitBoundary = false
            try {
                animatable.animateDecay(
                    initialVelocity = initialVelocity,
                    animationSpec = decaySpec
                ) {
                    if (value <= -maxBound || value >= maxBound) {
                        hitBoundary = true
                        throw CancellationException("Hit Pan Boundary")
                    }
                }
            } catch (_: CancellationException) {}

            if (hitBoundary || animatable.value < -maxBound || animatable.value > maxBound) {
                animatable.animateTo(
                    targetValue = animatable.value.coerceIn(-maxBound, maxBound),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    /**
     * 平滑弹性缩放动画。
     */
    fun animateZoom(
        scope: CoroutineScope,
        scaleAnim: Animatable<Float, AnimationVector1D>,
        offsetXAnim: Animatable<Float, AnimationVector1D>,
        offsetYAnim: Animatable<Float, AnimationVector1D>,
        targetScale: Float,
        targetOffset: Offset,
        onFinished: (() -> Unit)? = null
    ): kotlinx.coroutines.Job {
        return scope.launch {
            val j1 = launch {
                scaleAnim.animateTo(
                    targetValue = targetScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            val j2 = launch {
                offsetXAnim.animateTo(
                    targetValue = targetOffset.x,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            val j3 = launch {
                offsetYAnim.animateTo(
                    targetValue = targetOffset.y,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            j1.join()
            j2.join()
            j3.join()
            onFinished?.invoke()
        }
    }
}

/**
 * 大图查看器缩放状态与返回手势控制器接口。
 */
interface ZoomController {
    /** 当前是否处于放大状态（scale > 1.05f 且未在重置中） */
    val isZoomed: Boolean

    /** 平滑弹簧回弹重置至 1.0x 原始尺寸与原点 */
    fun resetZoom(onFinished: (() -> Unit)? = null)

    /** 预测返回手势开始 */
    fun onPredictiveBackStart()

    /** 预测返回手势进度联动：缩放与位移跟手向 1.0x 与原点平滑收拢 */
    fun onPredictiveBackProgress(progress: Float)

    /** 预测返回手势取消：平滑回弹恢复至手势开始前的放大倍数与偏移 */
    fun onPredictiveBackCancel()
}

/**
 * 默认缩放控制器实现。
 */
class DefaultZoomController(
    private val scope: CoroutineScope,
    private val scaleAnim: Animatable<Float, AnimationVector1D>,
    private val offsetXAnim: Animatable<Float, AnimationVector1D>,
    private val offsetYAnim: Animatable<Float, AnimationVector1D>
) : ZoomController {
    var isResetting: Boolean by mutableStateOf(false)
        private set

    override val isZoomed: Boolean
        get() = (scaleAnim.value > 1.05f) && !isResetting

    private var startScale = 1f
    private var startOffset = Offset.Zero
    private var resetJob: kotlinx.coroutines.Job? = null

    override fun resetZoom(onFinished: (() -> Unit)?) {
        isResetting = true
        resetJob?.cancel()
        resetJob = ZoomableImageCalculator.animateZoom(
            scope = scope,
            scaleAnim = scaleAnim,
            offsetXAnim = offsetXAnim,
            offsetYAnim = offsetYAnim,
            targetScale = 1f,
            targetOffset = Offset.Zero,
            onFinished = {
                isResetting = false
                onFinished?.invoke()
            }
        )
    }

    override fun onPredictiveBackStart() {
        startScale = scaleAnim.value
        startOffset = Offset(offsetXAnim.value, offsetYAnim.value)
    }

    override fun onPredictiveBackProgress(progress: Float) {
        if (startScale <= 1.05f) return
        val fraction = (progress * 1.5f).coerceIn(0f, 1f)
        val currentScale = 1f + (startScale - 1f) * (1f - fraction)
        val currentOffsetX = startOffset.x * (1f - fraction)
        val currentOffsetY = startOffset.y * (1f - fraction)
        scope.launch {
            scaleAnim.snapTo(currentScale)
            offsetXAnim.snapTo(currentOffsetX)
            offsetYAnim.snapTo(currentOffsetY)
        }
    }

    override fun onPredictiveBackCancel() {
        if (startScale <= 1.05f) return
        isResetting = false
        ZoomableImageCalculator.animateZoom(
            scope = scope,
            scaleAnim = scaleAnim,
            offsetXAnim = offsetXAnim,
            offsetYAnim = offsetYAnim,
            targetScale = startScale,
            targetOffset = startOffset
        )
    }
}

