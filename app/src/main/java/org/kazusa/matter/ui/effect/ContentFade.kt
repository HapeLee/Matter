package org.kazusa.matter.ui.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** [ContentFadeState] 的初始身份，用来区分「还没同步过」和「同步到了 null」。 */
private val UnsetContentKey = Any()

/**
 * 一组内容共享的淡入透明度。
 *
 * [fadeInOnContentChange] 每次进入组合都会新建一个 `Animatable`，适合「一个容器一份内容」；
 * 而正文页的评论 / 转发 / 赞是 `LazyColumn` 里的多个 item，逐个套用会让滚动时新进入
 * 组合的行也重新淡入一次（快速滑动时能看出来）。
 *
 * 这里把 alpha 提到页面级：同一个 alpha 驱动所有 item 的 `graphicsLayer`，
 * 只有内容身份变化时才整体淡入，滚动与翻页都不受影响；读取发生在绘制阶段，不触发重组。
 *
 * 归零必须走 [syncKey]（由 `SideEffect` 在组合提交阶段调用，早于本帧绘制），
 * 不能在协程里 `snapTo(0)`：后者会先按旧的 alpha=1 把新内容画一帧，下一帧才跳回 0，
 * 肉眼看到的就是评论「闪一下」。
 */
@Stable
class ContentFadeState {
    private var alphaState by mutableFloatStateOf(1f)
    private var contentKey: Any? = UnsetContentKey

    val alpha: Float get() = alphaState

    /** 组合提交阶段比对内容身份；换了内容才归零，同一份内容重复组合不会闪。 */
    fun syncKey(newKey: Any?) {
        if (contentKey == newKey) return
        contentKey = newKey
        alphaState = 0f
    }

    /** 补到 1；中途被新的内容变化打断也没关系，[syncKey] 会重新归零。 */
    suspend fun animateIn(durationMillis: Int = 220) {
        animate(
            initialValue = alphaState,
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis)
        ) { value, _ ->
            alphaState = value
        }
    }
}

@Composable
fun rememberContentFadeState(): ContentFadeState = remember { ContentFadeState() }

/** 用 [state] 的 alpha 驱动绘制；多个 item 传同一个 state 即为一组同时淡入。 */
fun Modifier.contentFade(state: ContentFadeState): Modifier =
    this.graphicsLayer { this.alpha = state.alpha }
