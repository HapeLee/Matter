package org.kazusa.matter.ui.media.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalImageMorphState = staticCompositionLocalOf<ImageMorphState?> { null }

@Composable
fun rememberImageMorphState(): ImageMorphState {
    val density = LocalDensity.current
    return remember {
        ImageMorphState(
            progress = Animatable(0f),
            density = density
        )
    }
}

/**
 * 微博图片 Morph 形变动画全局状态（参考 legado-with-MD3 经典设计）：
 * - 单一 0..1 progress 进度源，避免 tween 与多段动画状态竞争
 * - 临界阻尼弹簧驱动 (dampingRatio = 1.0f, stiffness = 280f)
 * - 负责记录起点矩形、圆角半径、屏幕尺寸、图片列表与当前索引
 */
@Stable
class ImageMorphState(
    val progress: Animatable<Float, AnimationVector1D>,
    private val density: Density,
) {
    var isOpen by mutableStateOf(false)
        private set

    var dragging by mutableStateOf(false)

    var dragOffset by mutableStateOf(Offset.Zero)

    var dragVeilAlpha by mutableFloatStateOf(1f)

    var isCollapsing by mutableStateOf(false)
        private set

    var collapseScale by mutableFloatStateOf(1f)
        private set

    var collapseOffset by mutableStateOf(Offset.Zero)
        private set

    var currentDisplayUrl by mutableStateOf<String?>(null)

    var urls by mutableStateOf<List<String>>(emptyList())
        private set

    var initialIndex by mutableIntStateOf(0)
        private set

    var currentIndex by mutableIntStateOf(0)

    var startBounds by mutableStateOf(Rect.Zero)
        private set

    var startCornerRadiusPx by mutableFloatStateOf(0f)
        private set

    var imageRatio by mutableStateOf<Float?>(null)
        private set

    var screenBounds by mutableStateOf(Rect.Zero)
        private set

    private val slotBoundsMap = mutableMapOf<Int, Rect>()
    private val slotCornerRadiusMap = mutableMapOf<Int, Float>()
    private val slotRatioMap = mutableMapOf<Int, Float>()

    val expanded: Boolean get() = progress.value >= 1f

    val isPresent: Boolean get() = isOpen || progress.value > 0.001f

    val veilAlpha: Float get() = computeMorphVeil(progress.value) * dragVeilAlpha

    /**
     * 判断当前卡片缩略图插槽是否需要让位隐藏（alpha = 0）：
     * 在形变展开与收拢全程，被点击或当前正浏览的缩略图隐藏，
     * 避免返回动画时卡片内与飞行的图片同时出现（产生双重重叠）。
     */
    fun isSlotHidden(targetUrls: List<String>, index: Int): Boolean {
        return isPresent && urls == targetUrls && currentIndex == index
    }

    fun registerSlot(
        targetUrls: List<String>,
        index: Int,
        bounds: Rect,
        cornerRadiusPx: Float,
        ratio: Float? = null
    ) {
        if (!bounds.isEmpty && (urls.isEmpty() || urls == targetUrls)) {
            slotBoundsMap[index] = bounds
            slotCornerRadiusMap[index] = cornerRadiusPx
            if (ratio != null) {
                slotRatioMap[index] = ratio
            }
        }
    }

    fun reportScreenBounds(bounds: Rect) {
        if (screenBounds != bounds && !bounds.isEmpty) {
            screenBounds = bounds
        }
    }

    fun dragRangePx(): Float =
        (screenBounds.height * 0.45f).coerceAtLeast(with(density) { 150.dp.toPx() })

    fun onPageChanged(newPage: Int) {
        currentIndex = newPage
        val cachedRatio = urls.getOrNull(newPage)?.let { ImageRatioCache.get(it) } ?: slotRatioMap[newPage]
        if (cachedRatio != null) {
            imageRatio = cachedRatio
            slotRatioMap[newPage] = cachedRatio
        } else {
            val slotRect = slotBoundsMap[newPage]
            if (slotRect != null && slotRect.height > 0f) {
                imageRatio = slotRect.width / slotRect.height
            }
        }
    }

    /**
     * 打开大图形变动画
     * allBounds 与 allRadii 预加载九宫格全部插槽坐标与圆角，
     * 切图翻页后退出时能直接飞向对应槽位，杜绝重叠与错误裁切！
     */
    fun open(
        urls: List<String>,
        index: Int,
        bounds: Rect,
        cornerRadiusPx: Float,
        imageRatio: Float? = null,
        allBounds: Map<Int, Rect>? = null,
        allRadii: Map<Int, Float>? = null,
        scope: CoroutineScope
    ) {
        this.urls = urls
        this.initialIndex = index
        this.currentIndex = index
        this.startBounds = bounds
        this.startCornerRadiusPx = cornerRadiusPx
        this.imageRatio = imageRatio
        this.dragOffset = Offset.Zero
        this.dragVeilAlpha = 1f
        this.collapseScale = 1f
        this.collapseOffset = Offset.Zero
        this.currentDisplayUrl = null

        slotBoundsMap.clear()
        slotCornerRadiusMap.clear()
        slotRatioMap.clear()

        if (allBounds != null) {
            slotBoundsMap.putAll(allBounds)
        }
        slotBoundsMap[index] = bounds

        if (allRadii != null) {
            slotCornerRadiusMap.putAll(allRadii)
        }
        slotCornerRadiusMap[index] = cornerRadiusPx

        urls.forEachIndexed { i, url ->
            val cached = ImageRatioCache.get(url)
            if (cached != null) {
                slotRatioMap[i] = cached
            }
        }
        if (imageRatio != null) {
            slotRatioMap[index] = imageRatio
        }

        this.isCollapsing = false
        this.isOpen = true

        scope.launch {
            progress.stop()
            progress.animateTo(
                targetValue = 1f,
                animationSpec = MORPH_SETTLE_SPEC
            )
            progress.snapTo(1f)
        }
    }

    fun updateRatio(index: Int, ratio: Float) {
        if (ratio > 0f) {
            slotRatioMap[index] = ratio
            if (currentIndex == index) {
                imageRatio = ratio
            }
        }
    }

    fun updateCurrentZoom(scale: Float, offset: Offset, displayUrl: String? = null) {
        this.collapseScale = scale
        this.collapseOffset = offset
        if (displayUrl != null) {
            this.currentDisplayUrl = displayUrl
        }
    }

    /**
     * 收起大图形变动画：平滑收拢回信息流卡片原始位置
     * 支持在放大状态下直接飞回收拢（保留 scale 与 offset，无缝过渡，绝不跳闪）
     */
    fun collapse(
        scope: CoroutineScope,
        initialVelocity: Float = 0f,
        dragOffset: Offset = Offset.Zero,
        veilAlpha: Float = 1f,
        initialScale: Float? = null,
        initialOffset: Offset? = null,
        displayUrl: String? = null
    ) {
        if (initialScale != null) this.collapseScale = initialScale
        if (initialOffset != null) this.collapseOffset = initialOffset
        if (displayUrl != null) this.currentDisplayUrl = displayUrl
        this.dragOffset = dragOffset
        this.dragVeilAlpha = veilAlpha
        this.isCollapsing = true
        scope.launch {
            val v = computeMorphSettleVelocity(progress.value, 0f, initialVelocity)
            progress.animateTo(
                targetValue = 0f,
                animationSpec = MORPH_SETTLE_SPEC,
                initialVelocity = v
            )
            progress.snapTo(0f)
            isOpen = false
            isCollapsing = false
            this@ImageMorphState.dragOffset = Offset.Zero
            this@ImageMorphState.dragVeilAlpha = 1f
            this@ImageMorphState.collapseScale = 1f
            this@ImageMorphState.collapseOffset = Offset.Zero
            this@ImageMorphState.currentDisplayUrl = null
        }
    }

    fun flyingFrame(): FlyingImageFrame? {
        if (screenBounds.isEmpty) return null
        val curStart = slotBoundsMap[currentIndex] ?: startBounds
        if (curStart.isEmpty) return null
        val curRadius = slotCornerRadiusMap[currentIndex] ?: startCornerRadiusPx
        val curRatio = urls.getOrNull(currentIndex)?.let { ImageRatioCache.get(it) } ?: slotRatioMap[currentIndex] ?: imageRatio
        val targetEnd = computeTargetEndBounds(screenBounds, curRatio)

        // 若退出时处于缩放（放大或手势微缩）或平移状态，飞行的终点矩形直接使用当前真实的屏幕物理外包围盒，
        // 彻底消除从画廊层切入飞行层瞬间突然闪回 1.0x 原始尺寸的跳跃！
        val effectiveEnd = if (kotlin.math.abs(collapseScale - 1f) > 0.001f || collapseOffset != Offset.Zero) {
            val actualW = targetEnd.width * collapseScale
            val actualH = targetEnd.height * collapseScale
            val actualCx = targetEnd.center.x + collapseOffset.x
            val actualCy = targetEnd.center.y + collapseOffset.y
            Rect(
                actualCx - actualW / 2f,
                actualCy - actualH / 2f,
                actualCx + actualW / 2f,
                actualCy + actualH / 2f
            )
        } else {
            targetEnd
        }

        return computeFlyingImageFrame(
            progress = progress.value,
            start = curStart,
            end = effectiveEnd,
            startCornerRadiusPx = curRadius,
            endCornerRadiusPx = 0f,
            dragOffset = dragOffset
        )
    }
}
