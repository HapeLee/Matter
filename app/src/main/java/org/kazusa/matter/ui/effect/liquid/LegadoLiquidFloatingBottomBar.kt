// Portions derived from Kyant0/AndroidLiquidGlass & legado-with-MD3
package org.kazusa.matter.ui.effect.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import org.kazusa.matter.ui.effect.liquid.animation.DampedDragAnimation
import org.kazusa.matter.ui.effect.liquid.animation.inspectDragGestures
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow as BackdropShadow

/**
 * 悬浮液态玻璃底栏 (FloatingBottomBar)
 *
 * 架构特点：
 * - Layer 1：底栏外壳磨砂容器
 * - Layer 2：滑动的液态玻璃药丸 (具有折射、毛玻璃模糊、Material 表面色、无刺眼边框高亮)
 * - Layer 3：唯一的全局 Items Row，每个 Item 始终对应自己的图标与文本，基于药丸位置进行颜色与权重的平滑插值过渡，
 *   彻底杜绝幽灵重叠或切换时使用上一个 Item 图标的 Bug。
 * - 手势系统：统一在顶层分流拖拽（阻尼拖拽药丸）与点击（点按任意 Tab 直接切换，点按当前 Tab 回到顶部刷新）。
 */
/** 单个 Tab 的基准宽度：适当放宽热区与间距，避免相邻 Tab 误触。 */
private const val BAR_ITEM_WIDTH_DP = 60

/** 胶囊两端的横向内边距：使最外侧按钮到胶囊边缘的距离与按钮之间的间距保持均衡（均为 36dp）。 */
private val BAR_HORIZONTAL_PADDING = 18.dp

/** 胶囊在 [itemCount] 个 Tab 下的原始宽度（未压缩）。 */
fun legadoBarWidth(itemCount: Int): Dp = (BAR_ITEM_WIDTH_DP * itemCount).dp + BAR_HORIZONTAL_PADDING * 2

@Composable
fun LegadoLiquidFloatingBottomBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    positionFraction: (() -> Float)? = null,
    onItemReselected: ((Int) -> Unit)? = null,
    badge: (Int) -> (@Composable () -> Unit)? = { null },
    /**
     * 横向压缩系数：1 = 原始宽度，<1 时胶囊整体变窄（内部 Tab 等分自适应），
     * 用于「底栏保持居中、旁边还要塞下一颗按钮」的场景 —— 挤压胶囊让位，而不是把底栏挤偏。
     */
    widthScale: Float = 1f,
) {
    val isBlurEnabled = backdrop != null
    val isInLightTheme = MaterialTheme.colorScheme.surface.luminance() >= 0.5f
    val isDark = !isInLightTheme
    val buttonHighlight = if (isDark) buttonSpecularDark else buttonSpecularLight
    val surfaceContainer = MaterialTheme.colorScheme.surface
    val accentColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val tabLabelStyle = MaterialTheme.typography.labelSmallEmphasized

    val containerColor = if (isBlurEnabled) {
        surfaceContainer.copy(alpha = 0.40f)
    } else {
        surfaceContainer
    }

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    val barWidth = legadoBarWidth(items.size) * widthScale

    var isDraggingPill by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val selectedIndexUpdated by rememberUpdatedState(selectedIndex)
    val onSelectedUpdated by rememberUpdatedState(onItemSelected)
    val onReselectedUpdated by rememberUpdatedState(onItemReselected)

    val offsetAnimation = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier.width(barWidth),
        contentAlignment = Alignment.CenterStart
    ) {
        val totalWidth = constraints.maxWidth.toFloat()
        val horizontalPaddingPx = with(density) { BAR_HORIZONTAL_PADDING.toPx() }
        val contentWidth = (totalWidth - horizontalPaddingPx * 2).coerceAtLeast(0f)
        val tabWidth = contentWidth / tabsCount
        val tabWidthDp = with(density) { tabWidth.toDp() }

        val panelOffset by remember(density, totalWidth) {
            derivedStateOf {
                if (totalWidth == 0f) {
                    0f
                } else {
                    val fraction = (offsetAnimation.value / totalWidth).fastCoerceIn(-1f, 1f)
                    with(density) {
                        4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                    }
                }
            }
        }

        val dampedDragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }

        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                if (!isDraggingPill) {
                    // Pager already provides a continuous position; don't start a second press animation.
                    if (positionFraction != null) dampedDragAnimation.snapToValue(selectedIndex.toFloat())
                    else dampedDragAnimation.animateToValue(selectedIndex.toFloat())
                }
            }
        }

        val isInteractingWithPill = isDraggingPill || dampedDragAnimation.pressProgress > 0.01f

        val currentAnimatedPosition by remember(isInteractingWithPill, dampedDragAnimation, positionFraction) {
            derivedStateOf {
                if (isInteractingWithPill) {
                    dampedDragAnimation.value
                } else {
                    positionFraction?.invoke() ?: dampedDragAnimation.value
                }
            }
        }


        // 统一顶层手势控制器：区分轻触点击（切换Tab或重选刷新）与横向拖动（弹性拉伸拖拽药丸）
        var downX by remember { mutableFloatStateOf(0f) }
        var dragAccumulatedX by remember { mutableFloatStateOf(0f) }
        var isTouchDragging by remember { mutableStateOf(false) }

        val gestureModifier = Modifier.pointerInput(tabWidth, isLtr, tabsCount) {
            val touchSlop = 6.dp.toPx()
            val paddingPx = 4.dp.toPx()
            inspectDragGestures(
                onDragStart = { down ->
                    positionFraction?.let { dampedDragAnimation.snapToValue(it()) }
                    downX = down.position.x
                    dragAccumulatedX = 0f
                    isTouchDragging = false
                    isDraggingPill = true
                    dampedDragAnimation.press()
                },
                onDragEnd = {
                    dampedDragAnimation.release()
                    if (isTouchDragging) {
                        val targetIndex = dampedDragAnimation.targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                        val isReselect = targetIndex == selectedIndexUpdated
                        currentIndex = targetIndex
                        dampedDragAnimation.animateToValue(targetIndex.toFloat())
                        if (isReselect) {
                            onReselectedUpdated?.invoke(targetIndex) ?: onSelectedUpdated(targetIndex)
                        } else {
                            onSelectedUpdated(targetIndex)
                        }
                        animationScope.launch {
                            offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                        }
                    } else {
                        // 点按命中检测：根据手指落点精确选中对应 Tab
                        val contentX = (downX - paddingPx).coerceIn(0f, contentWidth)
                        val rawIndex = (contentX / tabWidth).toInt().coerceIn(0, tabsCount - 1)
                        val tappedIndex = if (isLtr) rawIndex else (tabsCount - 1 - rawIndex)
                        if (tappedIndex == selectedIndexUpdated) {
                            onReselectedUpdated?.invoke(tappedIndex) ?: onSelectedUpdated(tappedIndex)
                        } else {
                            currentIndex = tappedIndex
                            dampedDragAnimation.animateToValue(tappedIndex.toFloat())
                            onSelectedUpdated(tappedIndex)
                        }
                    }
                    isDraggingPill = false
                    isTouchDragging = false
                },
                onDragCancel = {
                    dampedDragAnimation.release()
                    dampedDragAnimation.animateToValue(currentIndex.toFloat())
                    isDraggingPill = false
                    isTouchDragging = false
                },
                onDrag = { _, dragAmount ->
                    dragAccumulatedX += abs(dragAmount.x)
                    if (dragAccumulatedX > touchSlop) {
                        isTouchDragging = true
                    }
                    if (isTouchDragging && tabWidth > 0f) {
                        dampedDragAnimation.updateValue(
                            (dampedDragAnimation.targetValue + dragAmount.x / tabWidth * (if (isLtr) 1f else -1f))
                                .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                }
            )
        }

        // Layer 1：底栏外壳磨砂背景容器
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (isBlurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(8.dp.toPx())
                                lens(
                                    refractionHeight = 10.dp.toPx(),
                                    refractionAmount = 20.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = { buttonHighlight },
                            shadow = {
                                BackdropShadow(
                                    radius = 8.dp,
                                    color = Color.Black,
                                    alpha = if (isDark) 0.10f else 0.05f,
                                )
                            },
                            innerShadow = {
                                InnerShadow(
                                    radius = 1.dp,
                                    color = Color.White.copy(alpha = if (isDark) 0.04f else 0.08f),
                                )
                            },
                            layerBlock = {
                                val progress = dampedDragAnimation.pressProgress
                                val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                    } else {
                        Modifier
                            .dropShadow(
                                shape = CircleShape,
                                shadow = Shadow(
                                    radius = 8.dp,
                                    color = Color.Black,
                                    alpha = if (isDark) 0.16f else 0.08f
                                )
                            )
                            .background(containerColor, CircleShape)
                    }
                )
                .height(56.dp)
        )

        // Layer 2：全局 Items Row（图标与文字由选中进度驱动平滑过渡，不依赖背景椭圆选中框）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer { translationX = panelOffset }
                .then(gestureModifier)
                .padding(horizontal = BAR_HORIZONTAL_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BadgedBox(badge = { badge(index)?.invoke() }) {
                        Box(Modifier.size(24.dp)) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    alpha = 1f - selectionFractionFor(currentAnimatedPosition, index)
                                },
                                tint = onSurfaceVariant
                            )
                            Icon(
                                imageVector = item.selectedIcon,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    alpha = selectionFractionFor(currentAnimatedPosition, index)
                                },
                                tint = accentColor
                            )
                        }
                    }
                    // 用双层文本交叉淡入淡出替代组合期插值颜色，避免滑动时逐帧重组
                    Box {
                        Text(
                            text = item.title,
                            style = tabLabelStyle,
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                alpha = 1f - selectionFractionFor(currentAnimatedPosition, index)
                            }
                        )
                        Text(
                            text = item.title,
                            style = tabLabelStyle,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                alpha = selectionFractionFor(currentAnimatedPosition, index)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 计算某个 Item 相对药丸位置 [position] 的选中比例（0f~1f）。
 * 供绘制阶段读取，避免在组合期订阅动画状态。
 */
private fun selectionFractionFor(position: Float, index: Int): Float =
    (1f - abs(position - index)).coerceIn(0f, 1f)
