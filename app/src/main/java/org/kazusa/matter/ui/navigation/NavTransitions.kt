package org.kazusa.matter.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplay

/**
 * 导航进出场动画定义（参考 legado-with-MD3 经典动画曲线与时长参数）：
 * 1. 标准横向滑动+淡入淡出 (480ms FastOutSlowInEasing + 360ms LinearOutSlowInEasing)
 * 2. 离场页面后撤视差 TargetOffset = fullWidth / 4，进场页面初位移 FullWidth
 * 3. 返回时前景原地缩小至 0.8x 并淡出，上一页从左侧 1/4 视差恢复
 */
object NavTransitions {

    const val SLIDE_DURATION_MILLIS = 480
    const val FADE_DURATION_MILLIS = 360

    /**
     * 进入新页面转场动画 (从右往左滑入 + 渐显)
     * 参考 legado-with-MD3:
     * slideIntoContainer(towards = SlideDirection.Start, 480ms FastOutSlowInEasing, fullWidth) +
     * fadeIn(360ms LinearOutSlowInEasing)
     */
    fun <S> enterTransition(
        scope: AnimatedContentTransitionScope<S>
    ): EnterTransition {
        return scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_MILLIS,
                easing = FastOutSlowInEasing
            ),
            initialOffset = { fullWidth -> fullWidth }
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = FADE_DURATION_MILLIS,
                easing = LinearOutSlowInEasing
            )
        )
    }

    /**
     * 当前页面退出转场动画 (向左滑出 1/4 视差 + 渐隐)
     * 参考 legado-with-MD3:
     * slideOutOfContainer(towards = SlideDirection.Start, 480ms FastOutSlowInEasing, fullWidth / 4) +
     * fadeOut(360ms LinearOutSlowInEasing)
     */
    fun <S> exitTransition(
        scope: AnimatedContentTransitionScope<S>
    ): ExitTransition {
        return scope.slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_MILLIS,
                easing = FastOutSlowInEasing
            ),
            targetOffset = { fullWidth -> fullWidth / 4 }
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = FADE_DURATION_MILLIS,
                easing = LinearOutSlowInEasing
            )
        )
    }

    /**
     * 返回上级页面时的恢复进场动画 (从左侧 -1/4 视差滑入 + 渐显)
     * 支持 Android 14+ 预测性返回手势平滑插值追踪
     */
    fun <S> popEnterTransition(
        scope: AnimatedContentTransitionScope<S>
    ): EnterTransition {
        return scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_MILLIS,
                easing = FastOutSlowInEasing
            ),
            initialOffset = { fullWidth -> -fullWidth / 4 }
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = FADE_DURATION_MILLIS,
                easing = LinearOutSlowInEasing
            )
        )
    }

    /** Match legado: the foreground page shrinks in place and fades out. */
    fun popExitTransition(): ExitTransition =
        scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(SLIDE_DURATION_MILLIS, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(FADE_DURATION_MILLIS))

    fun <S> forward(scope: AnimatedContentTransitionScope<S>): ContentTransform =
        enterTransition(scope) togetherWith exitTransition(scope)

    fun <S> pop(scope: AnimatedContentTransitionScope<S>): ContentTransform =
        popEnterTransition(scope) togetherWith popExitTransition()

    /** All components share one duration, so gesture progress also controls the fade. */
    fun <S> predictivePop(scope: AnimatedContentTransitionScope<S>): ContentTransform =
        (scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(easing = FastOutSlowInEasing),
            initialOffset = { -it / 4 }
        ) + fadeIn(tween(easing = LinearOutSlowInEasing))) togetherWith
            (scaleOut(targetScale = 0.8f, animationSpec = tween(easing = FastOutSlowInEasing)) +
                fadeOut(tween()))

    /** Media transitions apply to the whole scene, including the page underneath. */
    val mediaMetadata: Map<String, Any> =
        NavDisplay.transitionSpec {
            mediaEnterTransition() togetherWith fadeOut(tween(280))
        } + NavDisplay.popTransitionSpec {
            fadeIn(tween(240)) togetherWith mediaExitTransition()
        } + NavDisplay.predictivePopTransitionSpec { _ ->
            fadeIn(tween()) togetherWith
                (scaleOut(targetScale = 0.70f, animationSpec = tween(easing = FastOutSlowInEasing)) +
                    fadeOut(tween()))
        }

    /**
     * 微博正文详情页容器变换（Card Morph）协同转场元数据：
     * 消除整页大范围横滑视差，改用柔和的交叉淡入淡出，
     * 使得处于 sharedBounds 顶层的卡片飞行动画成为视觉中心，
     * 避免横向位移与卡片形变相互拉扯，达成如打开大图般丝滑的沉浸感。
     */
    val detailMetadata: Map<String, Any> =
        NavDisplay.transitionSpec {
            fadeIn(animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
        } + NavDisplay.popTransitionSpec {
            fadeIn(animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
        } + NavDisplay.predictivePopTransitionSpec { _ ->
            fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 200))
        }


    /**
     * 图片查看器 / 视频全屏播放等沉浸式全屏媒体页面的缩放放大进场 (0.70x 自然放大展开)
     */
    fun mediaEnterTransition(): EnterTransition {
        return fadeIn(
            animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing)
        ) + scaleIn(
            initialScale = 0.70f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        )
    }

    /**
     * 沉浸式全屏媒体页面的缩放缩小退场 (0.70x 自然收拢缩回)
     */
    fun mediaExitTransition(): ExitTransition {
        return fadeOut(
            animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing)
        ) + scaleOut(
            targetScale = 0.70f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        )
    }
}
