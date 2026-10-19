package org.kazusa.matter.ui.effect

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 顶栏渐变表面的峰值不透明度：32% 的页面底色。
 *
 * 取 32% 是为了「压得住内容，又不成一块实心色板」：顶栏仍然能透出滚动内容的轮廓，
 * 玻璃胶囊与图标继续浮在底图之上，而不是贴在一块纯色板上；
 * 同时也和改造前那层黑色遮罩的视觉重量对得上（40% 黑 → 32% surface）。
 */
const val TopBarGradientSurfaceAlpha = 0.32f

/**
 * 顶栏渐变表面：以「主题 surface → 透明」的垂直 alpha 渐变替代 Haze 实时模糊。
 *
 * 底色取主题 surface 而不是一层黑色阴影：滚动内容淡出成页面自己的底色，
 * 而不是被压暗一层，因此浅色主题下不会再出现一条灰色暗带，深色主题下也不会把
 * 已经偏暗的底图再压死。
 *
 * **峰值不透明度 = [color] 自身的 alpha × [topAlpha]**，不会被强制拉满。
 * 默认即 [TopBarGradientSurfaceAlpha]（32%）；需要更实一点直接传更不透明的 surface：
 * ```
 * alphaGradientSurface(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
 * ```
 *
 * @param color 渐变底色，通常为主题 surface；其 alpha 即峰值不透明度基准
 * @param topAlpha 对峰值再缩放一次，1f 表示完全采用 [color] 的 alpha
 */
@Composable
fun Modifier.alphaGradientSurface(
    color: Color = MaterialTheme.colorScheme.surface.copy(alpha = TopBarGradientSurfaceAlpha),
    topAlpha: Float = 1f
): Modifier {
    val peak = (color.alpha * topAlpha).coerceIn(0f, 1f)
    // 峰值留在顶部（状态栏 + 标题行），往下多段近似 ease-out 羽化到 0：
    // 三点线性渐变在中段会有折角，半透明下更容易看出色带。
    val fractions = listOf(1f, 0.88f, 0.55f, 0.22f, 0f)
    return this.background(
        Brush.verticalGradient(
            colors = fractions.map { color.copy(alpha = peak * it) }
        )
    )
}
