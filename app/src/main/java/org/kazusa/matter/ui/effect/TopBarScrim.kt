package org.kazusa.matter.ui.effect

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 顶栏渐变遮罩：以「底色 → 透明」的垂直 alpha 渐变替代 Haze 实时模糊。
 *
 * **峰值不透明度 = [color] 自身的 alpha × [topAlpha]**，不会被强制拉满。
 * 因此直接传半透明色即可得到对应的轻柔遮罩：
 * ```
 * alphaGradientScrim(Color.Black.copy(alpha = 0.1f))   // 顶部最黑处即 10%
 * ```
 *
 * @param color 遮罩底色；其 alpha 即峰值不透明度基准
 * @param topAlpha 对峰值再缩放一次，1f 表示完全采用 [color] 的 alpha
 */
@Composable
fun Modifier.alphaGradientScrim(
    color: Color = Color.Black.copy(alpha = 0.1f),
    topAlpha: Float = 1f
): Modifier {
    val peak = (color.alpha * topAlpha).coerceIn(0f, 1f)
    // 多段近似 ease-out 衰减：三点线性渐变在中段会有折角，半透明下更易看出色带
    val fractions = listOf(1f, 0.62f, 0.32f, 0.12f, 0f)
    return this.background(
        Brush.verticalGradient(
            colors = fractions.map { color.copy(alpha = peak * it) }
        )
    )
}
