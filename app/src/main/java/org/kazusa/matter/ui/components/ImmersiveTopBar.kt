package org.kazusa.matter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import org.kazusa.matter.ui.effect.alphaGradientScrim
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow

/**
 * 顶栏滚动联动状态，由 [ImmersiveTopBar] 内部根据滚动位置计算并透传给插槽，
 * 用于联动 LiquidGlassCapsule 的玻璃强度、内边距与标题展开动画。
 */
@Immutable
data class TopBarScrollState(
    /** 目标可见度（0..1），直接由滚动位置映射、未经动画；适合「是否展开」这类离散判断 */
    val targetAlpha: Float,
    /** 动画后的实际透明度，用于绘制与内边距等连续联动 */
    val alpha: Float
) {
    /** 顶栏是否已被滚动内容「顶起」（标题等元素应随之展开） */
    val active: Boolean get() = targetAlpha > 0f
}

/**
 * 全 app 统一的顶栏，两种形态由是否传入滚动状态自动决定：
 *
 * **1. 沉浸式（传 [listState] 或 [scrollState]）**
 * - 透明 [TopAppBar] 之下叠一层「底色 → 透明」的 alpha 渐变遮罩 [alphaGradientScrim]；
 * - 遮罩随滚动位置淡入，滚过 [scrollDistance] 后完全不透明，保证标题始终可读；
 * - 遮罩透明度在绘制阶段读取，滚动过程中不驱动遮罩层重组。
 *
 * **2. 实心（两者都不传）**
 * - 直接铺一层 [solidColor] 实心底色，等价于传统实心顶栏（登录页、静态表单页等）。
 *
 * 组件只负责「一根顶栏」，既可放进 Scaffold 的 topBar 槽，也可作为内容层叠加
 * （如博主主页的沉浸 ZStack）。
 *
 * @param title 标题插槽，回调参数为滚动联动状态（如博主主页据此展开昵称）
 * @param modifier 施加在最外层 Box 上
 * @param listState 联动的列表滚动状态（LazyColumn 等）
 * @param scrollState 联动的滚动状态（Column + verticalScroll 等）
 * @param navigationIcon 左导航插槽，回调参数为滚动联动状态
 * @param actions 右操作插槽，回调参数为滚动联动状态
 * @param scrollDistance 遮罩从透明到完全不透明所需的滚动距离
 * @param fadeDurationMillis 遮罩淡入淡出时长
 * @param scrimColor 沉浸式渐变遮罩的底色，其 alpha 即峰值不透明度（默认轻柔淡黑）
 * @param solidColor 无滚动联动（实心形态）时的底色
 * @param barModifier 施加在 [TopAppBar] 自身上的 Modifier（如整体点击回到顶部）
 * @param animationLabel 动画调试标签
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveTopBar(
    title: @Composable (TopBarScrollState) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    scrollState: ScrollState? = null,
    navigationIcon: @Composable (TopBarScrollState) -> Unit = {},
    actions: @Composable RowScope.(TopBarScrollState) -> Unit = {},
    scrollDistance: Dp = 96.dp,
    fadeDurationMillis: Int = 180,
    scrimColor: Color = Color.Black.copy(alpha = 0.4f),
    solidColor: Color = MaterialTheme.colorScheme.surface,
    barModifier: Modifier = Modifier,
    animationLabel: String = "ImmersiveTopBarScrim"
) {
    val scrollDistancePx = with(LocalDensity.current) { scrollDistance.toPx() }
    val hasScrollSource = listState != null || scrollState != null

    // 目标可见度：位于顶部时为 0，滚过 scrollDistance 后为 1；
    // 无滚动状态时恒为 1（实心常显）。用 derivedStateOf 收窄依赖，
    // 滚动过程中只有本顶栏作用域参与计算。
    val targetAlphaState = remember(listState, scrollState, scrollDistancePx) {
        derivedStateOf {
            val scrolledPx = when {
                listState != null ->
                    if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE
                    else listState.firstVisibleItemScrollOffset.toFloat()
                scrollState != null -> scrollState.value.toFloat()
                else -> Float.MAX_VALUE
            }
            (scrolledPx / scrollDistancePx).coerceIn(0f, 1f)
        }
    }

    val animatedAlpha = animateFloatAsState(
        targetValue = targetAlphaState.value,
        animationSpec = tween(fadeDurationMillis),
        label = animationLabel
    )

    val scrollStateHolder = TopBarScrollState(
        targetAlpha = targetAlphaState.value,
        alpha = animatedAlpha.value
    )

    Box(modifier = modifier) {
        if (hasScrollSource) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 在绘制阶段读取透明度：遮罩层本身不随动画逐帧重组
                    .graphicsLayer { alpha = animatedAlpha.value }
                    .alphaGradientScrim(color = scrimColor)
            )
        } else {
            // 无滚动联动：铺实心底色，等价于传统实心顶栏
            Box(modifier = Modifier.matchParentSize().background(solidColor))
        }
        TopAppBar(
            title = { title(scrollStateHolder) },
            navigationIcon = { navigationIcon(scrollStateHolder) },
            actions = { actions(scrollStateHolder) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = barModifier
        )
    }
}

/**
 * 沉浸式液态玻璃顶栏统一设计规范（Tokens）。
 *
 * 以「动态（Timeline）」和「发现（Search）」为核心基准制定：
 * - 尺寸规范：标题胶囊与圆形图标按钮的标准高度/直径统一为 44dp；
 * - 边距规范：
 *   - 触顶时（alpha = 0）：胶囊玻璃完全淡出，标题贴齐屏幕左侧边距（start = 0dp），边缘按钮仅保留 4dp 边距；
 *   - 滚动后（alpha = 1）：胶囊玻璃随滚动遮罩优雅显现，标题平滑缩进（纯文本 16dp / 带图标 12dp），按钮缩进至 16dp；
 * - 排版规范：标准 titleLarge（28sp 行高）+ 上下各 8dp padding，精准达到 44dp 胶囊高度；
 * - 景深规范：前景色较亮时自动附加微阴影（[liquidTextShadow] 与 [LiquidGlassIcon]）。
 */
object TopBarTokens {
    /** 胶囊与圆形图标按钮的标准高度/直径：44dp */
    val ElementSize: Dp = 44.dp

    /** 标题胶囊外层默认垂直内边距：4dp */
    val CapsuleVerticalPadding: Dp = 4.dp

    /** 标题胶囊内部文字垂直内边距：上下各 8dp，配合 titleLarge 28sp 精准达到 44dp 胶囊高度 */
    val TitlePaddingTop: Dp = 8.dp
    val TitlePaddingBottom: Dp = 8.dp

    /** 纯文字标题与复合标题统一遵循 M3 标准水平内边距：16dp */
    val TitlePaddingStart: Dp = 16.dp
    val TitlePaddingEnd: Dp = 16.dp

    /** 标题与相邻图标的水平间距：8dp（标准 M3 图标与文本间距） */
    val TitleIconGap: Dp = 8.dp

    /**
     * 顶栏边缘按钮（左侧返回 / 右侧操作）在停顶与滚动时的边距基准：
     * - 停顶时（alpha = 0）：贴边仅留 4dp 极简空隙，图标悬浮在沉浸底图上；
     * - 滚动后（alpha = 1）：内收 12dp，最终与屏幕边缘保持 16dp 标准留白。
     */
    val ButtonEdgePaddingMin: Dp = 4.dp
    val ButtonEdgePaddingInset: Dp = 12.dp

    /** 计算操作按钮或导航按钮当前的边缘边距 */
    fun buttonEdgePadding(alpha: Float): Dp =
        ButtonEdgePaddingMin + ButtonEdgePaddingInset * alpha.coerceIn(0f, 1f)

    /**
     * 计算标题胶囊内部当前的左内边距：
     * - 纯文字标题与复合标题完全统一遵循 M3 标准；
     * - 触顶时（alpha = 0）：内边距为 0dp，标题内部最左侧元素（文字或返回图标）紧贴标题槽位左边界。
     *   由于 M3 TopAppBar 的 title 槽位自带 16dp 左间距，文字或图标离屏幕边缘严格为 16dp，与正文内容边缘对齐；
     * - 滚动后（alpha = 1）：内边距随玻璃外壳展开平滑插值至 16dp，使内容在玻璃胶囊内对称居中，
     *   此时玻璃胶囊左边界离屏幕边缘为 16dp，右侧操作按钮外边缘也离屏幕为 16dp。
     */
    fun titleStartPadding(alpha: Float): Dp =
        TitlePaddingStart * alpha.coerceIn(0f, 1f)
}

/** 快捷扩展：为顶栏右侧操作按钮设置标准滚动联动边距 */
fun Modifier.topBarActionPadding(scroll: TopBarScrollState): Modifier =
    this.padding(end = TopBarTokens.buttonEdgePadding(scroll.alpha))

/** 快捷扩展：为顶栏左侧导航按钮设置标准滚动联动边距 */
fun Modifier.topBarNavigationPadding(scroll: TopBarScrollState): Modifier =
    this.padding(start = TopBarTokens.buttonEdgePadding(scroll.alpha))

/**
 * 标准顶栏标题液态玻璃胶囊容器。
 *
 * 封装了统一的 44dp 高度、滚动联动透明度、触顶时贴边到滚动后居中内收的几何变换。
 */
@Composable
fun TopBarTitleCapsule(
    scroll: TopBarScrollState,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    LiquidGlassCapsule(
        backdrop = backdrop,
        onClick = onClick,
        glassAlpha = scroll.alpha,
        modifier = modifier.padding(vertical = TopBarTokens.CapsuleVerticalPadding),
    ) {
        val startPadding = TopBarTokens.titleStartPadding(scroll.alpha)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = startPadding,
                end = TopBarTokens.TitlePaddingEnd,
                top = TopBarTokens.TitlePaddingTop,
                bottom = TopBarTokens.TitlePaddingBottom,
            )
        ) {
            content()
        }
    }
}

/**
 * 标准顶栏标题文本胶囊（如「发现」、「关注分组」、「设置与外观」、「微博正文」）。
 *
 * 自动套用统一的 [MaterialTheme.typography.titleLarge]、[liquidTextShadow] 景深阴影与滚动内收动画。
 * - 若提供 [onBackClick]，自动在标题左侧包裹标准返回图标（类似微博正文：[< 标题]），且点击胶囊任意区域触发返回；
 * - 无需在 [ImmersiveTopBar] 中额外占用 [ImmersiveTopBar.navigationIcon] 导航槽，保持全屏胶囊语言统一。
 */
@Composable
fun TopBarTitleText(
    text: String,
    scroll: TopBarScrollState,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val actualClick = onBackClick ?: onClick

    TopBarTitleCapsule(
        scroll = scroll,
        backdrop = backdrop,
        modifier = modifier,
        onClick = actualClick,
    ) {
        // 直接调用而不是调用一个间接持有的 composable lambda：
        // 滚动内收动画会让标题胶囊每帧重组，若 lambda 实例每帧都新建，
        // 整棵图标子树（含其绘制）会跟着每帧重建，滚动时就会"卡一下"。
        when {
            leadingIcon != null -> leadingIcon()
            onBackClick != null -> LiquidGlassIcon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回"
            )
        }
        if (leadingIcon != null || onBackClick != null) {
            Spacer(modifier = Modifier.width(TopBarTokens.TitleIconGap))
        }
        val contentColor = LocalContentColor.current
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.headlineMediumEmphasized.copy(
                shadow = liquidTextShadow()
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(TopBarTokens.TitleIconGap))
            trailingIcon()
        }
    }
}

/**
 * 标准顶栏右侧操作图标按钮（如搜索、关闭、分享、刷新）。
 *
 * 统一 44dp 直径，包含滚动透明度渐变、4dp -> 16dp 边距内收，以及图标微阴影。
 */
@Composable
fun TopBarActionButton(
    imageVector: ImageVector,
    contentDescription: String?,
    scroll: TopBarScrollState,
    backdrop: LayerBackdrop? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    LiquidGlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        size = TopBarTokens.ElementSize,
        glassAlpha = scroll.alpha,
        tintAlpha = scroll.alpha,
        modifier = modifier.topBarActionPadding(scroll)
    ) {
        LiquidGlassIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

/**
 * 标准顶栏左侧导航图标按钮（如返回键）。
 *
 * 统一 44dp 直径，包含滚动透明度渐变、4dp -> 16dp 边距内收，以及图标微阴影。
 */
@Composable
fun TopBarNavigationButton(
    imageVector: ImageVector,
    contentDescription: String?,
    scroll: TopBarScrollState,
    backdrop: LayerBackdrop? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    LiquidGlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        size = TopBarTokens.ElementSize,
        glassAlpha = scroll.alpha,
        tintAlpha = scroll.alpha,
        modifier = modifier.topBarNavigationPadding(scroll)
    ) {
        LiquidGlassIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

