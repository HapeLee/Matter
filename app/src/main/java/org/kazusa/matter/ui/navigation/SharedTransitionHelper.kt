package org.kazusa.matter.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 类似打开大图 Morph 的临界阻尼弹性曲线 (Container Transform 弹簧参数)：
 * - 阻尼比 dampingRatio = 0.90f (收敛迅速，微弹感自然且无持续震颤)
 * - 刚度 stiffness = 380f (约 350~420ms 自然到位)
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val WeiboCardMorphBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = 0.90f,
        stiffness = 380f,
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

/**
 * 微博图片共享元素转场动画 Modifier 快捷扩展：
 * 在具有 SharedTransitionScope 与 AnimatedVisibilityScope 作用域的环境下，
 * 自动绑定以 key 为标识的平滑共享元素几何缩放与位移转场。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboSharedElement(key: String): Modifier {
    if (key.isBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboSharedElement.sharedElement(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope
            )
        }
    } else {
        this
    }
}

/**
 * 微博卡片与正文容器形变（Card Morph / Container Transform）转场修饰符：
 * 1. 列表页卡片 (Card) 与详情页正文 (PostDetailBody) 绑定同一 key；
 * 2. 在转场期间，容器 Bounds 由物理弹簧平滑驱动插值膨胀/收拢；
 * 3. 伴随 shape 动态剪裁（从列表 20.dp 圆角平滑过渡到详情全宽 0.dp），
 *    消除任何硬切与闪跳，呈现类似大图 Morph 的沉浸式展开体验。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboCardSharedBounds(
    key: String,
    shape: Shape = RectangleShape,
    zIndexInOverlay: Float = 1f
): Modifier {
    if (key.isBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboCardSharedBounds.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                enter = fadeIn(animationSpec = tween(280, easing = LinearOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(240, easing = FastOutSlowInEasing)),
                boundsTransform = WeiboCardMorphBoundsTransform,
                clipInOverlayDuringTransition = OverlayClip(clipShape = shape),
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                zIndexInOverlay = zIndexInOverlay
            )
        }
    } else {
        this
    }
}
