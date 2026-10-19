package org.kazusa.matter.ui.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 内容切换 / 加载态转数据态时的轻量淡入：key 变化就把 alpha 从 [initialAlpha] 补到 1。
 *
 * 关键点：`Animatable` 用 `remember(key)` 创建，**key 变化时在组合阶段就换成新的 0**。
 * 如果改成 `remember { Animatable(1f) }` + 在 `LaunchedEffect` 里 `snapTo(0)`，
 * 换 key 的第一帧仍会按旧的 alpha=1 画出来，下一帧才跳回 0 —— 视觉上就是「列表闪一下」。
 *
 * 只改绘制阶段的 alpha（`graphicsLayer`），不重组、不重建列表，因此可以直接套在
 * `LazyColumn` 外层或列表自身上 —— 相比 `Crossfade` 不会同时存在两份列表，
 * 也就不会出现两个 LazyColumn 抢同一个 `LazyListState` 的抖动。
 *
 * 用法：`Modifier.fadeInOnContentChange(key = uiState.selectedCategory?.containerId)`，
 * key 建议取「结构性身份」（分类 / 榜单 / 模式），不要取分页尺寸或刷新时间戳，
 * 否则下拉刷新、翻页都会整列表重新淡入，同样会被感知成闪烁。
 */
@Composable
fun Modifier.fadeInOnContentChange(
    key: Any?,
    initialAlpha: Float = 0f,
    durationMillis: Int = 220
): Modifier {
    val alpha = remember(key) { Animatable(initialAlpha) }
    LaunchedEffect(key) {
        alpha.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = durationMillis))
    }
    return this.graphicsLayer { this.alpha = alpha.value }
}
