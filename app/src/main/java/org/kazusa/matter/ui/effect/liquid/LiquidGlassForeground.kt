package org.kazusa.matter.ui.effect.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃「前景景深」统一令牌。
 *
 * 玻璃表面本身是半透明模糊层，前景若只有纯色而没有阴影，会出现「贴纸感」：
 * 字与图标看起来浮在玻璃表面之外，且与旁边带阴影的顶栏标题语言不一致。
 * 因此凡是被画在玻璃表面之上的文字与图标，都必须走本文件的 [LiquidGlassText] /
 * [LiquidGlassIcon]，而不是直接用 M3 的 [Text] / [Icon]。
 *
 * 阴影**无条件**施加：不区分浅色 / 深色主题、不判断前景亮度，全 app 统一一层浅阴影，
 * 保证任何底图与任何配色方案下玻璃前景的景深表现完全一致。
 */
object LiquidGlassForegroundTokens {
    /** 阴影不透明度：足够轻，只做「压住底图」的一点点景深。 */
    const val ShadowAlpha = 0.40f

    /** 文字阴影模糊半径（px）：保持极简，不产生明显光晕。 */
    const val TextShadowBlurRadius = 2f

    /** 图标阴影相对图标的垂直下移量：模拟光源在上方的柔和投影。 */
    val IconShadowOffsetY = 0.8.dp
}

/** 玻璃上文字 / 图标的统一浅阴影。 */
val LiquidGlassTextShadow: Shadow = Shadow(
    color = Color.Black.copy(alpha = LiquidGlassForegroundTokens.ShadowAlpha),
    blurRadius = LiquidGlassForegroundTokens.TextShadowBlurRadius,
    offset = Offset(0f, 0f)
)

/** 玻璃上文字的浅阴影；无条件返回，供直接写 `style.copy(shadow = ...)` 的场景使用。 */
fun liquidTextShadow(): Shadow = LiquidGlassTextShadow

/** 给任意文字样式套上统一的玻璃景深阴影。 */
fun TextStyle.liquidGlassShadow(): TextStyle = copy(shadow = LiquidGlassTextShadow)

/**
 * 玻璃表面上的标准文字：自动套用 [liquidGlassShadow]。
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
 * 带有浅阴影的液态玻璃图标：始终投射一层精致细微的底部软阴影，
 * 与 [LiquidGlassText] 的文字阴影视觉完全一致。
 */
@Composable
fun LiquidGlassIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = LiquidGlassForegroundTokens.ShadowAlpha),
            modifier = Modifier.offset(y = LiquidGlassForegroundTokens.IconShadowOffsetY)
        )
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
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = Color.Black.copy(alpha = LiquidGlassForegroundTokens.ShadowAlpha),
            modifier = Modifier.offset(y = LiquidGlassForegroundTokens.IconShadowOffsetY)
        )
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}
