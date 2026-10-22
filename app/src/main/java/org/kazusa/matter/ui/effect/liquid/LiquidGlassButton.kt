package org.kazusa.matter.ui.effect.liquid

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow
import org.kazusa.matter.ui.effect.LocalBlurEffectsEnabled

internal val buttonSpecularLight: Highlight = Highlight(
    width = 0.6.dp,
    blurRadius = 0.dp,
    alpha = 0.60f,
    style = HighlightStyle.Default(
        color = Color.White.copy(alpha = 0.60f),
        angle = 45f,
        falloff = 1f
    )
)

// 细微白色边缘高光：精致纤细，保留可见的晶莹玻璃光泽轮廓，克制不刺眼
internal val buttonSpecularDark: Highlight = Highlight(
    width = 0.6.dp,
    blurRadius = 0.dp,
    alpha = 0.42f,
    style = HighlightStyle.Default(
        color = Color.White.copy(alpha = 0.50f),
        angle = 45f,
        falloff = 1f
    )
)

/**
 * 液态玻璃表面统一光学配方。
 *
 * 圆形图标按钮与胶囊容器共用同一份参数 —— 顶栏的标题胶囊、左侧导航按钮、右侧操作按钮
 * 必须看起来是「同一种玻璃」，只是形状不同；否则同一根顶栏上会同时出现两种质感。
 * 亮度按主题固定（不做逐帧回读），显隐由各调用方的 alpha 逐项缩放。
 */
@Immutable
internal data class LiquidGlassSurfaceSpec(
    val isDark: Boolean,
    /** 模糊半径（满显隐时） */
    val blurRadius: Dp,
    /** 透镜折射高度（满显隐时） */
    val refractionHeight: Dp,
    /** 透镜折射强度（满显隐时） */
    val refractionAmount: Dp,
    /** 外投影不透明度 */
    val shadowAlpha: Float,
    /** 内边缘高光不透明度 */
    val innerShadowAlpha: Float,
    /** 表面色（原样叠在模糊底图上） */
    val surfaceColor: Color,
    /** 设备不支持 RuntimeShader / 无 backdrop 时的退化表面色 */
    val fallbackSurfaceColor: Color,
)

@Composable
private fun rememberLiquidGlassSurfaceSpec(): LiquidGlassSurfaceSpec {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val lum = if (isDark) 0.1f else 0.9f
    return LiquidGlassSurfaceSpec(
        isDark = isDark,
        blurRadius = lerp(4.dp, 8.dp, lum),
        refractionHeight = 10.dp,
        refractionAmount = 20.dp,
        shadowAlpha = if (isDark) 0.10f else 0.05f,
        innerShadowAlpha = if (isDark) 0.04f else 0.08f,
        surfaceColor = if (isDark) {
            Color.Black.copy(alpha = lerp(0.12f, 0.45f, lum))
        } else {
            Color.White.copy(alpha = lerp(0.42f, 0.14f, lum))
        },
        fallbackSurfaceColor = when {
            !LocalBlurEffectsEnabled.current -> MaterialTheme.colorScheme.surfaceContainer
            isDark -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f)
            else -> Color.White.copy(alpha = 0.22f)
        },
    )
}

/**
 * 高光边缘按显隐比例淡入。
 * 若只在图层上降 alpha，边缘高光会在玻璃"已消失"时仍留一圈轮廓；
 * 这里显式缩放高光自身的不透明度，保证随之一起淡出。
 */
internal fun Highlight.withAlpha(factor: Float): Highlight =
    if (factor >= 1f) {
        this
    } else {
        Highlight(width = width, blurRadius = blurRadius, alpha = alpha * factor, style = style)
    }

/**
 * 液态玻璃图标按钮（Liquid Glass Icon Button）。
 *
 * 参照 iOS / VisionOS 及 Kyant0/AndroidLiquidGlass 与 JMComicX 的设计：
 * - 采用真实凸透镜折射物理着色器（lens）对底景进行高折射与边缘色散（chromatic dispersion）；
 * - 随重力方向倾斜动态旋转的高光高斯边缘光（BloomStroke）；
 * - 内阴影边缘厚度质感（innerShadow）；
 * - 设备不支持 RuntimeShader 或 backdrop 为空时自动退化为精美的透明质感胶囊表面。
 * - 默认高度与直径为 44dp，与沉浸式顶栏标题胶囊标准高度严格统一。
 *
 * [content] 里画在玻璃之上的图标 / 文字必须使用 [LiquidGlassIcon] / [LiquidGlassText]，
 * 才能拿到与顶栏标题一致的景深微阴影；直接使用 M3 的 `Icon` / `Text` 会显得"贴在玻璃外面"。
 */
@Composable
fun LiquidGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    size: Dp = 44.dp,
    tintAlpha: Float = 0f,
    enabled: Boolean = true,
    glassAlpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    // 设备能力探测结果恒定，记忆化以避免每次重组重复探测（内部可能编译 RuntimeShader）。
    val isBlurSupported = rememberBlurEffectsEnabled()
    val blurActive = isBlurSupported && backdrop != null
    val spec = rememberLiquidGlassSurfaceSpec()
    // 圆形按钮同样走 Continuous 胶囊：正方形上得到的是连续曲率的圆（略偏 squircle）
    val shape = rememberLiquidCapsuleShape()
    val haptic = LocalHapticFeedback.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPressed) {
        scale.animateTo(
            targetValue = if (isPressed) 0.92f else 1f,
            animationSpec = spring(stiffness = 500f, dampingRatio = 0.65f),
        )
    }

    val buttonHighlight = if (spec.isDark) buttonSpecularDark else buttonSpecularLight

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onClick()
                }
            )
    ) {
        if (blurActive && glassAlpha > 0f) {
            val amount = glassAlpha.coerceIn(0f, 1f)

            // 同 LiquidGlassCapsule：显隐改为让玻璃内部各效果随 amount 生长，
            // 图层自身保持不透明，避免半透明模糊层与清晰底图叠出双影。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            padding = maxOf(padding, 20.dp.toPx())
                            if (amount > 0f) {
                                vibrancy()
                                // 模糊半径随显隐增长
                                blur(spec.blurRadius.toPx() * amount)
                                lens(
                                    refractionHeight = spec.refractionHeight.toPx() * amount,
                                    refractionAmount = spec.refractionAmount.toPx() * amount,
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            }
                        },
                        highlight = { buttonHighlight.withAlpha(amount) },
                        shadow = {
                            Shadow(
                                radius = 8.dp,
                                color = Color.Black,
                                alpha = spec.shadowAlpha * amount,
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 1.dp,
                                color = Color.White.copy(alpha = spec.innerShadowAlpha * amount),
                            )
                        },
                        onDrawSurface = {
                            // tintAlpha 只服务于「额外需要更实一点的悬浮按钮」（如底栏两侧），
                            // 顶栏的标题/导航/操作按钮一律不传，从而与标题胶囊完全同亮度。
                            val bonus = 0.20f * tintAlpha.coerceIn(0f, 1f)
                            drawRect(
                                spec.surfaceColor.copy(
                                    alpha = (spec.surfaceColor.alpha + bonus).coerceAtMost(0.75f) * amount
                                )
                            )
                        },
                    ),
            )
        } else {
            // 退化表面与胶囊容器共用同一份颜色，保证无模糊能力时两种玻璃也不分家
            Surface(
                shape = shape,
                color = spec.fallbackSurfaceColor,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = glassAlpha.coerceIn(0f, 1f) },
            ) {}
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/**
 * 具有 Surface Liquid Button 视觉质感与 Adaptive Luminance 动态明暗自适应感知的液态玻璃胶囊容器。
 *
 * 与 [LiquidGlassIconButton] 相同：[content] 中的文字 / 图标应使用 [LiquidGlassText] / [LiquidGlassIcon]，
 * 以保持全 app 玻璃前景景深一致。
 */
@Composable
fun LiquidGlassCapsule(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    onClick: (() -> Unit)? = null,
    glassAlpha: Float = 1f,
    content: @Composable RowScope.() -> Unit,
) {
    // 设备能力探测结果恒定，记忆化以避免每次重组重复探测。
    val isBlurSupported = rememberBlurEffectsEnabled()
    val blurActive = isBlurSupported && backdrop != null
    val spec = rememberLiquidGlassSurfaceSpec()
    // 胶囊同样使用连续曲率，与圆形按钮、面板保持同一种「平滑」边缘
    val shape = rememberLiquidCapsuleShape()
    // 更轻透的基础表面（与圆形按钮共用同一份退化色）
    val surfaceColor = spec.fallbackSurfaceColor
    val buttonHighlight = if (spec.isDark) buttonSpecularDark else buttonSpecularLight
    val haptic = LocalHapticFeedback.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPressed) {
        if (onClick != null) {
            scale.animateTo(
                targetValue = if (isPressed) 0.94f else 1f,
                animationSpec = spring(stiffness = 500f, dampingRatio = 0.65f),
            )
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onClick()
                        }
                    )
                } else Modifier
            )
    ) {
        if (blurActive && glassAlpha > 0f) {
            val amount = glassAlpha.coerceIn(0f, 1f)

            // 玻璃显隐**不能**靠图层整体降透明度：那会让下方未被模糊的清晰内容透上来，
            // 与模糊层叠成双影，观感就是"没糊透"。
            // 改为图层始终不透明，让模糊 / 折射 / 高光 / 表面色一起随 amount 从 0 长到满值，
            // 过渡全程都是"完全不透明的模糊层"，任何滚动位置都不会出现双影。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            if (amount > 0f) {
                                vibrancy()
                                // 模糊半径随显隐增长
                                blur(spec.blurRadius.toPx() * amount)
                                // Surface Liquid Button 晶莹透镜折射
                                lens(
                                    refractionHeight = spec.refractionHeight.toPx() * amount,
                                    refractionAmount = spec.refractionAmount.toPx() * amount,
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            }
                        },
                        highlight = { buttonHighlight.withAlpha(amount) },
                        shadow = {
                            Shadow(
                                radius = 8.dp,
                                color = Color.Black,
                                alpha = spec.shadowAlpha * amount,
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 1.dp,
                                color = Color.White.copy(alpha = spec.innerShadowAlpha * amount),
                            )
                        },
                        onDrawSurface = {
                            drawRect(spec.surfaceColor.copy(alpha = spec.surfaceColor.alpha * amount))
                        },
                    ),
            )
        } else {
            Surface(
                shape = shape,
                color = surfaceColor,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = glassAlpha.coerceIn(0f, 1f) },
            ) {}

        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                content()
            }
        }
    }
}
