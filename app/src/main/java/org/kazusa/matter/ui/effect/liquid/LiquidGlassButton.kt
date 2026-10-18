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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
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

val LiquidGlassTextShadow: androidx.compose.ui.graphics.Shadow = androidx.compose.ui.graphics.Shadow(
    color = Color.Black.copy(alpha = 0.40f),
    blurRadius = 2f,
    offset = Offset(0f, 0f)
)

/**
 * 当前景颜色较浅（luminance > 0.5f）时提供精巧的黑底文字阴影，保证在浅色背景或复杂底图下的清晰可读性。
 */
@Composable
fun liquidTextShadow(color: Color = LocalContentColor.current): androidx.compose.ui.graphics.Shadow? =
    if (color.luminance() > 0.5f) LiquidGlassTextShadow else null

/**
 * 带有微阴影的液态玻璃图标，在内容明亮（luminance > 0.5f）时自动投射精致细微的底部软阴影，与标题胶囊文字阴影视觉保持一致。
 */
@Composable
fun LiquidGlassIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val hasShadow = tint.luminance() > 0.5f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (hasShadow) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.40f),
                modifier = Modifier.offset(y = 0.8.dp)
            )
        }
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
fun LiquidGlassIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val hasShadow = tint.luminance() > 0.5f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (hasShadow) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.40f),
                modifier = Modifier.offset(y = 0.8.dp)
            )
        }
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint
        )
    }
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
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

    val buttonHighlight = if (isDark) buttonSpecularDark else buttonSpecularLight

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
            // 固定亮度参考值：原实现通过持续 GPU 回读底景亮度做自适应，
            // 会造成渲染管线阻塞与每帧重绘；此处改为固定值，保持初始观感且消除持续开销。
            val lum = if (isDark) 0.2f else 0.8f
            val amount = glassAlpha.coerceIn(0f, 1f)

            // 同 LiquidGlassCapsule：显隐改为让玻璃内部各效果随 amount 生长，
            // 图层自身保持不透明，避免半透明模糊层与清晰底图叠出双影。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            padding = maxOf(padding, 20.dp.toPx())
                            if (amount > 0f) {
                                vibrancy()
                                // 模糊半径随显隐增长（4.dp ~ 8.dp 为满值）
                                blur(lerp(4.dp.toPx(), 8.dp.toPx(), lum) * amount)
                                lens(
                                    refractionHeight = 10.dp.toPx() * amount,
                                    refractionAmount = 14.dp.toPx() * amount,
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
                                alpha = (if (isDark) 0.12f else 0.06f) * amount,
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 1.dp,
                                color = Color.White.copy(alpha = (if (isDark) 0.04f else 0.08f) * amount),
                            )
                        },
                        onDrawSurface = {
                            val scrollBonus = 0.20f * tintAlpha.coerceIn(0f, 1f)
                            if (isDark) {
                                // 暗色模式：轻透黑晶
                                val baseAlpha = lerp(0.16f, 0.36f, lum)
                                drawRect(Color.Black.copy(alpha = ((baseAlpha + scrollBonus).coerceAtMost(0.60f)) * amount))
                            } else {
                                // 浅色模式：通透白晶
                                val baseAlpha = lerp(0.36f, 0.18f, lum)
                                drawRect(Color.White.copy(alpha = ((baseAlpha + scrollBonus).coerceAtMost(0.60f)) * amount))
                                drawRect(surfaceContainerHigh.copy(alpha = (0.08f + scrollBonus * 0.4f) * amount))
                            }
                        },
                    ),
            )
        } else {
            val extraAlpha = 0.20f * tintAlpha.coerceIn(0f, 1f)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(
                    alpha = if (LocalBlurEffectsEnabled.current) {
                        ((if (isDark) 0.35f else 0.40f) + extraAlpha).coerceAtMost(0.75f)
                    } else 1f,
                ),
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
 * 具有 Surface Liquid Button 视觉质感与 Adaptive Luminance 动态明暗自适应感知的液态玻璃胶囊容器
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 更轻透的基础表面
    val surfaceColor = if (!LocalBlurEffectsEnabled.current) {
        MaterialTheme.colorScheme.surfaceContainer
    } else if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f)
    } else {
        Color.White.copy(alpha = 0.22f)
    }
    val buttonHighlight = if (isDark) buttonSpecularDark else buttonSpecularLight
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
            // 固定亮度参考值：移除逐帧 GPU 回读的亮度自适应采样，保持初始观感并消除持续重绘。
            val lum = if (isDark) 0.1f else 0.9f
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
                        shape = { CircleShape },
                        effects = {
                            if (amount > 0f) {
                                vibrancy()
                                // 模糊半径随显隐增长（4.dp ~ 8.dp 为满值）
                                blur(lerp(4.dp.toPx(), 8.dp.toPx(), lum) * amount)
                                // Surface Liquid Button 晶莹透镜折射
                                lens(
                                    refractionHeight = 10.dp.toPx() * amount,
                                    refractionAmount = 20.dp.toPx() * amount,
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
                                alpha = (if (isDark) 0.10f else 0.05f) * amount,
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 1.dp,
                                color = Color.White.copy(alpha = (if (isDark) 0.04f else 0.08f) * amount),
                            )
                        },
                        onDrawSurface = {
                            if (isDark) {
                                // 暗色模式：纯粹通透黑晶质感
                                drawRect(Color.Black.copy(alpha = lerp(0.12f, 0.45f, lum) * amount))
                            } else {
                                // 浅色模式：纯粹通透白晶质感
                                drawRect(Color.White.copy(alpha = lerp(0.42f, 0.14f, lum) * amount))
                            }
                        },
                    ),
            )
        } else {
            Surface(
                shape = CircleShape,
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
