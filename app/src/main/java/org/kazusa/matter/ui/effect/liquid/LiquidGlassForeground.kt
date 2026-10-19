package org.kazusa.matter.ui.effect.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * 液态玻璃「前景景深」统一令牌。
 *
 * 玻璃表面本身是半透明模糊层，前景若只有纯色而没有阴影，会出现「贴纸感」：
 * 字与图标看起来浮在玻璃表面之外，且与旁边带阴影的顶栏标题语言不一致。
 * 因此凡是被画在玻璃表面之上的文字与图标，都必须走本文件的 [LiquidGlassText] /
 * [LiquidGlassIcon]，而不是直接用 M3 的 [Text] / [Icon]。
 *
 * 阴影策略（文字与图标**完全同源**）：
 * - **只在深色主题施加**：0.40 黑的柔化阴影 + 零位移，是各向同性的「压住底图」光晕，
 *   不模拟方向光，因此文字与图标看起来处在同一层玻璃上；
 * - **亮色主题不处理阴影**：亮色玻璃是浅底 + 深色前景，黑投影只会让字形 / 图标边缘糊出脏边，
 *   既没有景深收益又损失清晰度；
 * - 图标不再用「复制一份图标向下偏移」的硬剪影伪造投影，[LiquidGlassForegroundTokens.ShadowBlurRadiusPx]
 *   与 [LiquidGlassForegroundTokens.ShadowAlpha] 由文字与图标共用。
 */
object LiquidGlassForegroundTokens {
    /** 阴影不透明度：足够轻，只做「压住底图」的一点点景深。 */
    const val ShadowAlpha = 0.40f

    /** 阴影模糊半径（px）：文字与图标共用，保持极简，不产生明显光晕。 */
    const val ShadowBlurRadiusPx = 2f
}

/**
 * 当前是否需要给玻璃前景施加阴影：**仅深色主题**需要。
 *
 * 判定口径与玻璃表面配方（`rememberLiquidGlassSurfaceSpec`）保持一致，都以 `surface`
 * 亮度区分明暗，避免出现「玻璃按深色处理、前景按浅色处理」的割裂。
 */
@Composable
fun isLiquidGlassShadowEnabled(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 玻璃上文字 / 图标的统一浅阴影；亮色主题下调用方应直接跳过，不做任何处理。 */
val LiquidGlassTextShadow: Shadow = Shadow(
    color = Color.Black.copy(alpha = LiquidGlassForegroundTokens.ShadowAlpha),
    blurRadius = LiquidGlassForegroundTokens.ShadowBlurRadiusPx,
    offset = Offset.Zero
)

/** 玻璃上文字的浅阴影；深色主题返回统一阴影，亮色主题返回 null（不处理阴影）。 */
@Composable
fun liquidTextShadow(): Shadow? =
    if (isLiquidGlassShadowEnabled()) LiquidGlassTextShadow else null

/** 给任意文字样式套上统一的玻璃景深阴影；亮色主题原样返回，不改动调用方的样式。 */
@Composable
fun TextStyle.liquidGlassShadow(): TextStyle =
    if (isLiquidGlassShadowEnabled()) copy(shadow = LiquidGlassTextShadow) else this

/**
 * 图标阴影的柔化层：与文字阴影使用同一模糊半径与零位移，
 * 只把形状边缘化开，不产生方向的硬剪影。
 */
@Composable
private fun Modifier.liquidGlassShadowBlur(): Modifier {
    val radius = LiquidGlassForegroundTokens.ShadowBlurRadiusPx
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    return graphicsLayer { renderEffect = blur }
}

/**
 * 玻璃表面上的标准文字：深色主题自动套用 [liquidGlassShadow]，亮色主题不加阴影。
 *
 * 凡是在 [LiquidGlassCapsule] / [LiquidGlassIconButton] / 悬浮栏 / 玻璃菜单里显示文字，
 * 都应使用本组件，避免各页面各写一份 `style.copy(shadow = ...)` 而漏掉。
 */
@Composable
fun LiquidGlassText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style.liquidGlassShadow(),
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

/**
 * 带有浅阴影的液态玻璃图标。
 *
 * 阴影与 [LiquidGlassText] 完全同源：深色主题下是同一 alpha、同一模糊半径、零位移的柔化副本；
 * 亮色主题不画阴影副本，只画图标本体。
 */
@Composable
fun LiquidGlassIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isLiquidGlassShadowEnabled()) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = LiquidGlassTextShadow.color,
                modifier = Modifier.liquidGlassShadowBlur()
            )
        }
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

/** [LiquidGlassIcon] 的 [Painter] 版本，阴影处理与 [ImageVector] 版本一致。 */
@Composable
fun LiquidGlassIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isLiquidGlassShadowEnabled()) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = LiquidGlassTextShadow.color,
                modifier = Modifier.liquidGlassShadowBlur()
            )
        }
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}
