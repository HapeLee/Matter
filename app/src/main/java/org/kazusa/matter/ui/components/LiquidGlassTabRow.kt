package org.kazusa.matter.ui.components

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import org.kazusa.matter.ui.effect.liquid.LiquidGlassText
import org.kazusa.matter.ui.effect.liquid.rememberLiquidCapsuleShape
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled

/**
 * 分栏数量不超过该值时分段等宽铺满（原设计外观）；
 * 超过则退化为「内容宽度 + 横向滚动」，避免分栏多时每个都挤成一条。
 *
 * 分栏数量由服务端决定（`container/getIndex` 的 `data.tabs`），所以这里必须能容纳任意数量。
 */
private const val MaxEqualWidthTabs = 4

/**
 * 液态玻璃分段选择器（搜索结果分栏等场景使用）。
 *
 * 与本项目其它玻璃组件保持同一套配方：`vibrancy + blur` 采样 [backdrop]，
 * 选中项用 `secondaryContainer` 胶囊高亮并以弹簧动画平移。
 * 设备不支持 RuntimeShader 或 [backdrop] 为空时自动退化为半透明容器表面。
 *
 * 分栏数量随服务端变化：
 * - `tabs.size <= [MaxEqualWidthTabs]`：等宽分段铺满（原外观）；
 * - 更多分栏：横向滚动，选中项会自动滚入视野并居中。
 *
 * @param tabs 分栏文案
 * @param selectedIndex 当前选中下标
 * @param onTabSelected 点击回调
 * @param backdrop 采样底图（一般为页面内容层挂载的 LayerBackdrop）
 * @param dotFlags 各分栏是否显示红点（对应服务端 `tabs[].showDot`）；长度不足按下标缺省为 false
 */
@Composable
fun LiquidGlassTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    dotFlags: List<Boolean> = emptyList()
) {
    if (tabs.isEmpty()) return

    // 设备能力探测结果恒定，记忆化以避免重复编译 RuntimeShader
    val isRuntimeShaderOk = rememberBlurEffectsEnabled()
    val isBlurActive = isRuntimeShaderOk && backdrop != null

    // 整条胶囊与选中指示块都用连续曲率，避免圆弧与直边接缝处的折角
    val pillShape = rememberLiquidCapsuleShape()
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurActive) surfaceContainer.copy(alpha = 0.55f) else surfaceContainer
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer

    val isScrollable = tabs.size > MaxEqualWidthTabs

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(pillShape)
            .then(
                if (isBlurActive && backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { pillShape },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                } else {
                    Modifier.background(containerColor, pillShape)
                }
            )
            .padding(4.dp)
    ) {
        if (isScrollable) {
            ScrollableTabs(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onTabSelected = onTabSelected,
                dotFlags = dotFlags,
                pillShape = pillShape,
                indicatorColor = indicatorColor
            )
        } else {
            EqualWidthTabs(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onTabSelected = onTabSelected,
                dotFlags = dotFlags,
                tabWidth = maxWidth / tabs.size,
                pillShape = pillShape,
                indicatorColor = indicatorColor
            )
        }
    }
}

/** 等宽分段：分栏少时铺满整条胶囊，外观与改造前一致。 */
@Composable
private fun EqualWidthTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    dotFlags: List<Boolean>,
    tabWidth: androidx.compose.ui.unit.Dp,
    pillShape: androidx.compose.ui.graphics.Shape,
    indicatorColor: androidx.compose.ui.graphics.Color
) {
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * selectedIndex,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.9f),
        label = "LiquidGlassTabIndicator"
    )

    Box(
        modifier = Modifier
            .offset(x = indicatorOffset)
            .width(tabWidth)
            .fillMaxHeight()
            .clip(pillShape)
            .background(indicatorColor)
    )

    Row(modifier = Modifier.fillMaxSize()) {
        tabs.forEachIndexed { index, label ->
            TabChip(
                label = label,
                selected = index == selectedIndex,
                showDot = dotFlags.getOrElse(index) { false },
                onClick = { onTabSelected(index) },
                pillShape = pillShape,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/** 分栏多时：内容宽度 + 横向滚动，指示器跟随选中项的实际位置与宽度。 */
@Composable
private fun ScrollableTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    dotFlags: List<Boolean>,
    pillShape: androidx.compose.ui.graphics.Shape,
    indicatorColor: androidx.compose.ui.graphics.Color
) {
    val scrollState = rememberScrollState()
    val chipOffsets = remember(tabs) { mutableStateMapOf<Int, Int>() }
    val chipWidths = remember(tabs) { mutableStateMapOf<Int, Int>() }
    val density = LocalDensity.current

    val targetOffset = chipOffsets[selectedIndex]?.let { with(density) { it.toDp() } } ?: 0.dp
    val targetWidth = chipWidths[selectedIndex]?.let { with(density) { it.toDp() } } ?: 0.dp

    val indicatorOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.9f),
        label = "LiquidGlassScrollIndicatorOffset"
    )
    val indicatorWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.9f),
        label = "LiquidGlassScrollIndicatorWidth"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(indicatorWidth)
                .fillMaxHeight()
                .clip(pillShape)
                .background(indicatorColor)
        )

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState)
        ) {
            tabs.forEachIndexed { index, label ->
                TabChip(
                    label = label,
                    selected = index == selectedIndex,
                    showDot = dotFlags.getOrElse(index) { false },
                    onClick = { onTabSelected(index) },
                    pillShape = pillShape,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .onGloballyPositioned { coordinates ->
                            chipOffsets[index] = coordinates.positionInParent().x.toInt()
                            chipWidths[index] = coordinates.size.width
                        }
                )
            }
        }
    }

    // 选中项自动滚入视野并尽量居中（分栏首次测量完成后再执行一次）
    LaunchedEffect(selectedIndex, chipOffsets.size) {
        val offset = chipOffsets[selectedIndex] ?: return@LaunchedEffect
        val width = chipWidths[selectedIndex] ?: return@LaunchedEffect
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        scrollState.animateScrollTo((offset - (viewport - width) / 2).coerceAtLeast(0))
    }
}

/** 单个分栏：文案 + 可选红点。 */
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    showDot: Boolean,
    onClick: () -> Unit,
    pillShape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "LiquidGlassTabContentColor"
    )

    Box(
        modifier = modifier
            .clip(pillShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassText(
            text = label,
            style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )

        if (showDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}
