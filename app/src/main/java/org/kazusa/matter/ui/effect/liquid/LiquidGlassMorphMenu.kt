package org.kazusa.matter.ui.effect.liquid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.zIndex
import org.kazusa.matter.ui.media.morph.MORPH_SETTLE_SPEC
import org.kazusa.matter.ui.media.morph.morphPositionEase
import org.kazusa.matter.ui.media.morph.morphSizeEase

/** 菜单条目高度。 */
private val MORPH_ITEM_HEIGHT = 48.dp

/** 面板宽度（也是展开后的最终宽度）。 */
private val MORPH_PANEL_WIDTH = 200.dp

/** 面板上下内边距。 */
private val MORPH_PANEL_PADDING = 8.dp

/** 展开后的圆角（折叠态是整颗圆）。 */
private val MORPH_PANEL_CORNER = 22.dp

/** 面板最大高度，榜单再多也不会顶到状态栏。 */
private val MORPH_PANEL_MAX_HEIGHT = 340.dp

/** 展开过程中整体上移的距离：让菜单浮到按钮上方，而不是压在底栏上。 */
private val MORPH_PANEL_LIFT = 64.dp

/** 展开后的面板宽度。 */
val morphMenuPanelWidth: Dp = MORPH_PANEL_WIDTH

/** 展开后的面板高度：由条目数决定，上限 [MORPH_PANEL_MAX_HEIGHT]。 */
fun morphMenuPanelHeight(itemCount: Int): Dp =
    (MORPH_PANEL_PADDING * 2 + MORPH_ITEM_HEIGHT * itemCount.coerceAtLeast(1))
        .coerceAtMost(MORPH_PANEL_MAX_HEIGHT)

/** 展开时整体上移的距离：让菜单浮到按钮上方，而不是压在底栏上。 */
val morphMenuPanelLift: Dp = MORPH_PANEL_LIFT

/** 同一玻璃表面从按钮形变到上方菜单，原按钮位置保留关闭入口。 */
@Composable
fun LiquidGlassMorphMenu(
    items: List<String>,
    selectedIndex: Int,
    progress: State<Float>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    collapsedSize: Dp = 56.dp,
    /** 榜单还没拉到时占位的那一行文案 */
    emptyLabel: String = ""
) {
    val isBlurSupported = rememberBlurEffectsEnabled()
    val blurActive = isBlurSupported && backdrop != null
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val lum = if (isDark) 0.2f else 0.8f
    val highlight = if (isDark) buttonSpecularDark else buttonSpecularLight
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val haptic = LocalHapticFeedback.current

    // 面板尺寸只在组合期算一次（条目数变了才变）
    val panelHeight = morphMenuPanelHeight(items.size)

    val morphShape = RoundedCornerShape(MORPH_PANEL_CORNER)

    // 根盒子恒为「面板 + 上移距离」的尺寸，且不随形变进度变化：
    // 收起态的按钮与展开态的面板都被摆在这个盒子内部，于是它的 bounds 同时覆盖两者，
    // 触摸命中范围与绘制范围天然一致（父级不必再为溢出留空间）。
    Box(modifier = modifier.size(MORPH_PANEL_WIDTH, panelHeight + MORPH_PANEL_LIFT)) {
        // 玻璃按当前形变尺寸测量，右下角始终停在盒子里的固定锚点上。
        Box(
            modifier = Modifier.morphContainerSize(
                progress, MORPH_PANEL_WIDTH, panelHeight, MORPH_PANEL_LIFT, collapsedSize
            )
        ) {
            // 玻璃：铺满当前容器（不裁切，保住外扩的阴影）
            if (blurActive) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = {
                                RoundedCornerShape(
                                    lerp(collapsedSize.value / 2f, MORPH_PANEL_CORNER.value,
                                        morphSizeEase(progress.value)).dp
                                )
                            },
                            effects = {
                                padding = maxOf(padding, 16.dp.toPx())
                                vibrancy()
                                blur(lerp(8.dp.toPx(), 16.dp.toPx(), lum))
                                lens(
                                    refractionHeight = 10.dp.toPx(),
                                    refractionAmount = 14.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            highlight = { highlight },
                            shadow = {
                                Shadow(
                                    radius = 8.dp,
                                    color = Color.Black,
                                    alpha = if (isDark) 0.12f else 0.06f
                                )
                            },
                            innerShadow = {
                                InnerShadow(
                                    radius = 1.dp,
                                    color = Color.White.copy(alpha = if (isDark) 0.04f else 0.08f)
                                )
                            },
                            onDrawSurface = {
                                if (isDark) {
                                    drawRect(Color.Black.copy(alpha = lerp(0.16f, 0.36f, lum)))
                                } else {
                                    drawRect(Color.White.copy(alpha = lerp(0.36f, 0.18f, lum)))
                                    drawRect(surfaceContainerHigh.copy(alpha = 0.08f))
                                }
                            }
                        )
                )
            } else {
                Box(
                    Modifier.matchParentSize().background(surfaceContainerHigh, morphShape)
                )
            }

            // 内容裁到玻璃轮廓；列表保持最终尺寸，形变时逐渐露出
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        shape = RoundedCornerShape(
                            lerp(collapsedSize.value / 2f, MORPH_PANEL_CORNER.value,
                                morphSizeEase(progress.value)).dp
                        )
                        clip = true
                    }
            ) {
                // 折叠态的按钮图标：停在右下角那颗「按钮」里，随形变提前淡出
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(collapsedSize)
                        .graphicsLayer { alpha = (1f - progress.value * 1.8f).coerceIn(0f, 1f) }
                        .then(
                            if (!expanded) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        onToggle()
                                    }
                                )
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides onSurface) {
                        icon()
                    }
                }

                // 菜单列表：按最终尺寸测量一次，随形变后期淡入。
                // 只在形变开始后才挂载 —— 它是一块铺满整个面板的滚动容器，收起态虽然完全透明，
                // 但正好盖在右下角那颗按钮上面：命中测试按倒序遇到它就会停下，按钮永远收不到点击。
                if (expanded || progress.value > 0.001f) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .requiredSize(MORPH_PANEL_WIDTH, panelHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = MORPH_PANEL_PADDING)
                            .graphicsLayer {
                                alpha = ((progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            }
                    ) {
                        if (items.isEmpty()) {
                            MorphMenuRow(
                                text = emptyLabel,
                                selected = false,
                                enabled = false,
                                textColor = onSurface,
                                checkColor = primary,
                                onClick = {}
                            )
                        } else {
                            items.forEachIndexed { index, text ->
                                MorphMenuRow(
                                    text = text,
                                    selected = index == selectedIndex,
                                    enabled = expanded,
                                    textColor = onSurface,
                                    checkColor = primary,
                                    onClick = { onItemClick(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
        // 面板移到上方后，原位置的玻璃按钮渐显为 X；收起完成后移除。
        if (expanded || progress.value > 0.001f) {
            LiquidGlassIconButton(
                onClick = onToggle,
                backdrop = backdrop,
                size = collapsedSize,
                enabled = expanded,
                // 关闭入口停在根盒子的右下角 = 按钮原来的位置
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭榜单菜单", tint = onSurface)
            }
        }
    }
}

/**
 * 按当前形变进度测量玻璃，并把它摆在 [panelWidth] × ([panelHeight] + [lift]) 的容器内部：
 * - 收起态（进度 0）：玻璃是 [collapsedSize] 见方，贴在容器右下角（= 按钮位置）；
 * - 展开态（进度 1）：玻璃长成整块面板，顶端对齐容器顶端（即按钮上方 [lift] 处）。
 *
 * 容器尺寸恒定（不随动画变化），玻璃始终在容器内部，因此任何一帧都不会画出容器的 bounds。
 */
private fun Modifier.morphContainerSize(
    progress: State<Float>,
    panelWidth: Dp,
    panelHeight: Dp,
    lift: Dp,
    collapsedSize: Dp
): Modifier = this.layout { measurable, _ ->
    val collapsed = collapsedSize.roundToPx()
    val containerWidth = panelWidth.roundToPx()
    val containerHeight = (panelHeight + lift).roundToPx()
    // 弹簧插值可能在两端轻微过冲，这里夹紧，避免玻璃被推到胶囊外面
    val p = progress.value.coerceIn(0f, 1f)
    val sizeProgress = morphSizeEase(p)
    val w = lerp(collapsed.toFloat(), panelWidth.toPx(), sizeProgress).roundToInt()
    val h = lerp(collapsed.toFloat(), panelHeight.toPx(), sizeProgress).roundToInt()
    val liftPx = (lift.toPx() * morphPositionEase(p)).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(containerWidth, containerHeight) {
        placeable.place(containerWidth - w, containerHeight - liftPx - h)
    }
}

@Composable
private fun MorphMenuRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    textColor: Color,
    checkColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MORPH_ITEM_HEIGHT)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = checkColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 独立的悬浮形变菜单按钮（放在 [BoxScope] 内使用）：
 * - 默认态：圆角液态玻璃按钮（图标可自定义，如切换分栏 / 换榜）；
 * - 点击后：原地向上形变为液态玻璃面板，展示选项列表（带单选对勾）；
 * - 点击空白处或按系统返回键收起，透明遮罩不压暗背景；
 * - 尺寸与动画曲线与全 app 液态形变规范对齐。
 */
@Composable
fun BoxScope.LiquidGlassFloatingMenu(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Sort,
            contentDescription = "切换分栏"
        )
    },
    emptyLabel: String = "加载中…"
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    val menuProgress = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = MORPH_SETTLE_SPEC,
        label = "FloatingMenuMorph"
    )

    Box(
        modifier = modifier
            .zIndex(if (expanded || menuProgress.value > 0.01f) 2f else 1f)
    ) {
        LiquidGlassMorphMenu(
            items = items,
            selectedIndex = selectedIndex,
            progress = menuProgress,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onItemClick = { index ->
                expanded = false
                onSelect(index)
            },
            backdrop = backdrop,
            icon = icon,
            emptyLabel = emptyLabel
        )
    }

    // 空白点击收起层：在页面内容之上、菜单之下，透明不压暗背景
    if (expanded || menuProgress.value > 0.001f) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(1.5f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = false }
                )
        )
    }
}
