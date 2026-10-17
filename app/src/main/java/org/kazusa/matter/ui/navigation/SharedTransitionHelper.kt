package org.kazusa.matter.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 微博图片共享元素转场动画 Modifier 快捷扩展：
 * 在具有 SharedTransitionScope 与 AnimatedVisibilityScope 作用域的环境下，
 * 自动绑定以 key 为标识的平滑共享元素几何缩放与位移转场。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboSharedElement(key: String): Modifier {
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
