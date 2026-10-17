package org.kazusa.matter.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.launch
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.ui.effect.liquid.LegadoLiquidFloatingBottomBar
import org.kazusa.matter.ui.effect.liquid.LiquidGlassMorphMenu
import org.kazusa.matter.ui.effect.liquid.LiquidNavItem
import org.kazusa.matter.ui.effect.liquid.legadoBarWidth
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelHeight
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelLift
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelWidth
import org.kazusa.matter.ui.media.morph.MORPH_SETTLE_SPEC
import org.kazusa.matter.ui.search.HotSearchBoardState

/** 底栏行的声明高度：与胶囊的视觉高度对齐，用于内容避让与隐藏动画的距离。 */
internal val BAR_ROW_HEIGHT = 56.dp

/** 底栏与导航栏之间的留白。 */
internal val BAR_BOTTOM_PADDING = 12.dp

/** 悬浮胶囊与「换榜」按钮之间的间距。 */
private val BAR_BUTTON_GAP = 10.dp

/** 「换榜」按钮直径。 */
private val BAR_BUTTON_SIZE = 56.dp

/** 换榜按钮与屏幕边缘至少要留出的距离。 */
private val BAR_EDGE_MARGIN = 16.dp

/** 胶囊与右侧按钮同高 56dp，底部留白一致，自然同心对齐。 */
private val BAR_BUTTON_CENTER_INSET = 0.dp


/**
 * 悬浮底栏层（全屏 overlay）。
 *
 * 这一层**不再挂在 `Scaffold` 的 `bottomBar` 槽里**：槽高被写死、菜单又要靠 `requiredSize`
 * 硬撑出槽外，溢出的部分既画不准也点不到。现在整层直接铺在内容区之上，胶囊、换榜按钮、
 * 榜单菜单都是它的直接子节点，坐标与命中范围由同一份几何推出。
 */
@Composable
internal fun MainBottomBarLayer(
    pagerState: PagerState,
    visible: Boolean,
    hotSearchState: HotSearchBoardState,
    unreadCount: Int,
    backdrop: LayerBackdrop?,
    onReselect: (Int) -> Unit,
    onBoardSelected: (HotSearchChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val selectedIndex = pagerState.currentPage
    val searchPage = MainTab.Search.ordinal

    // 榜单菜单的展开状态只服务于这一层：返回键收起、切 Tab 收起都在层内处理
    var menuExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = menuExpanded) { menuExpanded = false }
    LaunchedEffect(selectedIndex) { menuExpanded = false }

    // 下滑隐藏：整层一起位移淡出（距离含导航栏，保证完全移出屏幕）
    val barHeightPx = with(density) {
        (BAR_ROW_HEIGHT + BAR_BOTTOM_PADDING + navigationBarBottom).toPx()
    }
    val barTranslationY by animateFloatAsState(
        targetValue = if (visible) 0f else barHeightPx + 150f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
        label = "BottomBarTranslation"
    )

    // 0 = 圆形按钮，1 = 榜单面板；驱动同一块玻璃的形变（几何在绘制阶段求，不逐帧重组）
    val menuProgress = animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        animationSpec = MORPH_SETTLE_SPEC,
        label = "HotBoardMenuMorph"
    )

    val navItems = remember {
        MainTab.entries.map {
            LiquidNavItem(
                title = it.title,
                icon = it.unselectedIcon,
                selectedIcon = it.selectedIcon
            )
        }
    }

    val positionFraction = remember(pagerState) {
        { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    // 底栏恒居中，旁边那颗按钮必须自己找位置：
    // 屏幕半宽 − 胶囊半宽 − 间距 − 按钮直径 ≥ 16dp 边距，不够就把胶囊横向挤压（变窄）让位
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val fullCapsuleWidth = legadoBarWidth(navItems.size)
    val maxCapsuleHalf = screenWidthDp / 2 - BAR_BUTTON_GAP - BAR_BUTTON_SIZE - BAR_EDGE_MARGIN
    val capsuleScaleTarget = (maxCapsuleHalf.value / (fullCapsuleWidth.value / 2f)).coerceIn(0.7f, 1f)

    val isSearchActive = selectedIndex == searchPage

    // 1 = 原始宽度（按钮收起）；<1 = 被按钮挤压
    // 阻尼设为 0.66，挤压与还原时伴随果冻般 Q 弹的有机形变
    val capsuleScale by animateFloatAsState(
        targetValue = if (isSearchActive) capsuleScaleTarget else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.66f),
        label = "BottomBarCapsuleScale"
    )

    // 按钮停在胶囊右缘外侧：胶囊半宽 + 间距 + 自身半径
    val buttonOffsetX = fullCapsuleWidth * capsuleScale / 2 + BAR_BUTTON_GAP + BAR_BUTTON_SIZE / 2

    // 1 = 已完全显示；0 = 隐去收起。
    // 采用生动饱满的果冻弹簧（阻尼 0.62），保留 overshoot（过冲回弹）带来鲜活 Q 弹质感
    val buttonReveal by animateFloatAsState(
        targetValue = if (isSearchActive) 1f else 0f,
        animationSpec = if (isSearchActive) {
            spring(stiffness = 280f, dampingRatio = 0.62f)
        } else {
            spring(stiffness = 340f, dampingRatio = 0.68f)
        },
        label = "HotBoardButtonReveal"
    )

    // 菜单玻璃的占位盒 = 面板 + 展开时的上移距离。占位盒的右下角对齐到按钮，
    // 于是按钮与展开后的面板都落在盒子内部，也就都落在这一层（全屏）的 bounds 里。
    val menuBoxWidth = morphMenuPanelWidth
    val menuBoxHeight = morphMenuPanelHeight(hotSearchState.channels.size) + morphMenuPanelLift
    // 揭示动画绕「按钮中心」缩放（盒子右下角往内各 28dp）
    val menuTransformOrigin = remember(menuBoxWidth, menuBoxHeight) {
        TransformOrigin(
            pivotFractionX = (menuBoxWidth - BAR_BUTTON_SIZE / 2) / menuBoxWidth,
            pivotFractionY = (menuBoxHeight - BAR_BUTTON_SIZE / 2) / menuBoxHeight
        )
    }

    // 菜单展开状态只在处于发现页且菜单展开或展开过渡中才提权至 zIndex = 2f；
    // 切走或正常收回时 strictly zIndex = 0f，且按钮在胶囊右侧外独立缩放淡出，绝不横向划进或绘制在底栏上方。
    val menuElevated = isSearchActive && (menuExpanded || menuProgress.value > 0.01f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = barTranslationY
                alpha = (1f - (barTranslationY / barHeightPx)).coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Layer 1：换榜按钮 + 榜单菜单
        // 未展开时 zIndex = 0f（在底栏下方，进出动画在右侧独立缩放淡出，不再横向滑进底栏）；
        // 展开时提升到 zIndex = 2f（在底栏与遮罩之上显示完整菜单与关闭按钮）。
        if (buttonReveal > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 盒子右下角 = 按钮右下角：到屏幕右缘 / 底边的距离由胶囊位置推出。
                    // 用 offset 而不是 padding：胶囊从原始宽度缩到目标宽度的动画途中，
                    // 到右缘的距离会短暂变成负数（按钮本来就允许被挤出屏幕一点），padding 不接受负值。
                    .offset(
                        x = -(screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2),
                        y = -(navigationBarBottom + BAR_BOTTOM_PADDING + BAR_BUTTON_CENTER_INSET)
                    )
                    .zIndex(if (menuElevated) 2f else 0f)
                    .graphicsLayer {
                        transformOrigin = menuTransformOrigin
                        val p = buttonReveal
                        alpha = p.coerceIn(0f, 1f)
                        // 保留弹性过冲，使弹出与缩放呈现真实饱满的 Q 弹果冻质感
                        val scale = if (isSearchActive) {
                            (0.15f + 0.85f * p).coerceAtLeast(0f)
                        } else {
                            p.coerceAtLeast(0f)
                        }
                        scaleX = scale
                        scaleY = scale

                        // 从右下方斜向弹入并带有轻微过冲回弹，全程在胶囊右侧的安全间距外运动，绝不穿行或覆盖底栏
                        val offsetFactor = 1f - p
                        translationX = offsetFactor * 14.dp.toPx()
                        translationY = offsetFactor * 18.dp.toPx()
                    }
            ) {
                HotBoardGlassMenu(
                    channels = hotSearchState.channels,
                    selectedContainerId = hotSearchState.selectedContainerId,
                    menuProgress = menuProgress,
                    expanded = menuExpanded,
                    backdrop = backdrop,
                    onToggle = { menuExpanded = !menuExpanded },
                    onSelect = { channel ->
                        menuExpanded = false
                        onBoardSelected(channel)
                    }
                )
            }
        }

        // Layer 2：胶囊底栏本体（zIndex = 1f，在换榜按钮滑入滑出时保持在上方）
        LegadoLiquidFloatingBottomBar(
            items = navItems,
            widthScale = capsuleScale,
            selectedIndex = selectedIndex,
            positionFraction = positionFraction,
            onItemSelected = { index ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(index)
                }
            },
            onItemReselected = onReselect,
            backdrop = backdrop,
            badge = { index ->
                if (index == 0 && unreadCount > 0) {
                    {
                        Badge {
                            Text(
                                text = if (unreadCount > 99) "99+" else unreadCount.toString()
                            )
                        }
                    }
                } else null
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = BAR_BOTTOM_PADDING)
                .zIndex(1f)
        )

        // Layer 3：空白点击收起层（zIndex = 1.5f，在底栏之上、菜单之下）。
        // 仅在发现页且菜单展开或过渡时生效，点击空白处收起菜单，透明不压暗背景。
        if (isSearchActive && (menuExpanded || menuProgress.value > 0.001f)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1.5f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuExpanded = false }
                    )
            )
        }
    }
}

/**
 * 换榜按钮 ↔ 榜单菜单：同一块液态玻璃的形变容器。
 *
 * 榜名列表全部来自服务端 [HotSearchChannel]，`containerid` 相同的那条打勾，
 * 客户端不写死任何榜名。
 */
@Composable
private fun HotBoardGlassMenu(
    channels: List<HotSearchChannel>,
    selectedContainerId: String?,
    menuProgress: State<Float>,
    expanded: Boolean,
    backdrop: LayerBackdrop?,
    onToggle: () -> Unit,
    onSelect: (HotSearchChannel) -> Unit
) {
    LiquidGlassMorphMenu(
        items = channels.map { it.name },
        selectedIndex = channels.indexOfFirst { it.containerid == selectedContainerId },
        progress = menuProgress,

        expanded = expanded,
        onToggle = onToggle,
        onItemClick = { index -> channels.getOrNull(index)?.let(onSelect) },
        backdrop = backdrop,
        emptyLabel = "榜单加载中…",
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "切换热搜榜单"
            )
        }
    )
}
