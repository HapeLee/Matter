package org.kazusa.matter.ui.navigation

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 当前组合是否作为转场的「来源」参与共享元素注册。
 *
 * 「动态」与「热门」是同一个 `HorizontalPager` 的相邻页，为了滑动时不留白，
 * `beyondViewportPageCount = 1` 让两页**同时处于组合中**。同一条微博同时出现在两页时，
 * 两张卡片会各自注册一份完全相同的 key（`post_card_<id>`，头像则是
 * `user_avatar_<uid>`，视频是 `video_container_<mid>`）。
 *
 * Compose 的共享元素要求同一 key 在组合内只有一个来源节点：多份注册时它只能挑一个，
 * 而被挑中的很可能是另一页那张卡片——它的坐标在屏幕外，于是表现为
 * 「动画从侧边飞入 / 一进正文就跳变 / 收拢时飞向看不见的位置」。
 *
 * 因此分页容器按「当前页」给出该标记，只有可见页的节点参与注册；
 * 正文、主页、搜索等非分页场景默认 `true`，行为不变。
 */
val LocalSharedTransitionSourceActive = compositionLocalOf { true }

/**
 * 共享元素「触发源」选择器：
 *
 * 同一张页面里往往存在多个可以复用同一 key 的节点（例如同一博主的多条微博卡片、
 * 同一位博主的多张头像），Compose 的共享元素要求 key 在组合内唯一，否则会出现
 * 「同时有多个元素一起飞」的错乱。
 *
 * 因此共享元素只在「本次导航真正被点击的那一个元素」上生效：
 * - [containerKey]：卡片 / 视频 容器形变的目标 key；
 * - [avatarKey]：头像共享元素 key（按 uid 维度，source 与 destination 必须一致）；
 * - [avatarOwnerKey]：点击头像的那张卡片的唯一 key，用于在列表里锁定唯一来源。
 */
@Stable
class SharedElementTarget {
    var containerKey by mutableStateOf<String?>(null)
        private set

    /** 容器形变的来源宿主（转发区块属于某张转发卡片时用于去重） */
    private var containerOwnerKey by mutableStateOf<String?>(null)

    var avatarKey by mutableStateOf<String?>(null)
        private set

    var avatarOwnerKey by mutableStateOf<String?>(null)
        private set

    /**
     * 点击微博卡片：只选中卡片容器。
     *
     * 博主头像是卡片内容的组成部分，会随 sharedBounds 容器一起完成形变，
     * 不需要（也不应该）再单独抽成一个共享元素；
     * 头像在形变过程中不闪烁靠的是 Coil 缓存键对齐（rememberSharedAvatarRequest）。
     *
     * @param ownerKey 仅「卡片内的转发区块」需要传入：转发区块的共享键在信息流里
     *                 可能同时存在多份（多条微博转发了同一条原微博），
     *                 用宿主卡片 key 锁定唯一来源，避免多张卡片一起动画。
     */
    fun selectPost(cardKey: String, ownerKey: String? = null) {
        containerKey = cardKey.takeIf { it.isNotBlank() }
        containerOwnerKey = ownerKey?.takeIf { it.isNotBlank() }
        avatarKey = null
        avatarOwnerKey = null
    }

    /** 点击博主头像：只让头像参与共享元素动画，卡片容器保持静态 */
    fun selectAvatar(ownerKey: String, avatarKey: String) {
        containerKey = null
        containerOwnerKey = null
        this.avatarKey = avatarKey.takeIf { it.isNotBlank() }
        avatarOwnerKey = ownerKey.takeIf { it.isNotBlank() }
    }

    /** 点击视频：视频容器参与形变 */
    fun selectVideo(key: String) {
        containerKey = key.takeIf { it.isNotBlank() }
        containerOwnerKey = null
        avatarKey = null
        avatarOwnerKey = null
    }

    /**
     * @param ownerKey 元素自身的宿主 key（转发区块才有）；空表示普通卡片 / 目标页，
     *                 只需 key 命中即可参与。
     */
    fun isContainerSelected(key: String, ownerKey: String? = null): Boolean {
        if (key.isBlank() || containerKey != key) return false
        if (ownerKey.isNullOrBlank()) return true
        return ownerKey == containerOwnerKey
    }

    /**
     * @param ownerKey 卡片来源 key；为空表示当前节点是动画目标页（详情页 / 主页），
     *                 只要 key 命中即可参与，不需要再校验来源卡片。
     */
    fun isAvatarSelected(key: String, ownerKey: String? = null): Boolean {
        if (key.isBlank() || avatarKey != key) return false
        val owner = avatarOwnerKey
        if (ownerKey.isNullOrBlank()) return true
        return owner != null && owner == ownerKey
    }
}

val LocalSharedElementTarget = staticCompositionLocalOf<SharedElementTarget?> { null }

/**
 * 博主头像共享元素 key：按 uid 维度保持稳定，供信息流卡片、正文详情与博主主页复用。
 */
fun formatAvatarSharedKey(uid: Long?): String =
    uid?.let { formatAvatarSharedKey(it.toString()) }.orEmpty()

fun formatAvatarSharedKey(uid: String?): String {
    val id = uid?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
    return "user_avatar_$id"
}

/** 认证角标的共享元素 key：由头像 key 派生，保证与头像一一对应且不冲突。 */
fun formatAvatarBadgeSharedKey(avatarKey: String): String =
    if (avatarKey.isBlank()) "" else "$avatarKey#verify_badge"

/**
 * 共享元素内容交叉淡化曲线（容器形变期间，来源内容淡出、目标内容淡入）。
 *
 * 大图 Morph 只有一层内容在动，所以干净利落；容器形变不可避免要同时绘制两套内容，
 * 这里通过「入场缓起 + 出场更快」把两套内容同时半透明叠加的时间窗压到最短：
 * - 入场用 FastOutSlowIn：目标内容晚一点显形，避免一开场就与来源内容糊在一起；
 * - 出场 200ms 先退干净，剩下的时间交给已经稳定的目标内容跟随容器收尾。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val SharedElementEnterFade = fadeIn(
    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
)

@OptIn(ExperimentalSharedTransitionApi::class)
private val SharedElementExitFade = fadeOut(
    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
)

/**
 * 共享元素形变曲线，与打开大图 Morph 的 [org.kazusa.matter.ui.media.morph.MORPH_SETTLE_SPEC] 完全一致：
 * - 阻尼比 dampingRatio = 1.0f（临界阻尼，零过冲）
 * - 刚度 stiffness = 280f（约 350~420ms 自然到位）
 *
 * 之所以要严格对齐：
 * 1. 大图 Morph 之所以顺滑，是因为全程临界阻尼、没有终点回弹；
 * 2. 之前的 0.9 / 380 存在约 5% 过冲，容器在收尾时会略微涨过目标尺寸再回弹，
 *    卡片/正文这种含大量文字与图片的容器会因此出现「末尾文字重新折行再复原」的抖动；
 * 3. 卡片、视频、头像三类共享元素统一使用同一曲线，手感才和大图 Morph 一致。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val WeiboCardMorphBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = 1.0f,
        stiffness = 280f,
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

/**
 * 「卡片内部元素」在 overlay 里的绘制层级：
 * 高于卡片容器（1f，容器自身内容仍留在原位交叉淡化），低于视频浮层（2f）。
 * 三者被提升到 overlay 后是平级绘制的，层级不够就会被容器的内容盖住。
 */
private const val PostPartZIndexInOverlay = 1.5f

/**
 * 昵称、头像这类「字号/尺寸会变」的元素采用等比缩放：contentScale 走默认的
 * FillWidth（以宽度为准做等比缩放），两份内容各自按自己的字号排版，
 * 再统一缩放到同一份插值 Bounds，观感就是字号连续变大，而不是两套排版互相替换。
 *
 * 对齐改成 [Alignment.TopStart]：文字/头像都从左上角生长，
 * 避免默认 Center 缩放时整体向外撑开带来的横向抖动。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val PostPartScaleResizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
    alignment = Alignment.TopStart
)

/**
 * 卡片内部元素的交叉淡化：两套内容在这一层几乎像素重合（同一张头像、同一段文字），
 * 所以只需要一次短促的交接即可，不必像容器那样拉长窗口去遮掩排版差异。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val PostPartEnterFade = fadeIn(
    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
)

@OptIn(ExperimentalSharedTransitionApi::class)
private val PostPartExitFade = fadeOut(
    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
)

/**
 * 正文的两端可能有不同折行，只在转场前段交接，避免两套行首长时间重叠。
 * 排版由 ScaleToBounds 固定，淡化结束后继续随容器移动到位。
 */
private val PostContentEnterFade = fadeIn(
    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
)

private val PostContentExitFade = fadeOut(
    animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
)

/** 卡片内部 constituent 的命名空间后缀 */
const val POST_PART_AVATAR = "avatar"
const val POST_PART_NAME = "name"
const val POST_PART_SUBTITLE = "subtitle"
const val POST_PART_CONTENT = "content"

/**
 * 「嵌套转发正文」的命名空间后缀。
 *
 * 同一段转发正文在两次转场里身份不同，key 必须跟着换：
 * - 转发区块自己就是来源（转发区块 → 原微博正文）：它就是目标正文的主正文，用 [POST_PART_CONTENT]；
 * - 外层容器才是来源（卡片 → 正文）：它只是外层容器里的「转发内容」，必须单独占一个命名空间，
 *   否则会与宿主容器自己的主正文撞 key，同一个组合里出现两份相同 key 的共享元素。
 */
const val POST_PART_RETWEET_CONTENT = "retweet_content"

/**
 * 「卡片 → 正文」时，卡片内部元素（头像 / 昵称 / 正文）的共享元素 key。
 *
 * 以容器 key（post_card_<id> 或 retweet_card_<id>）为前缀派生：
 * 1. 容器选中谁，内部元素就跟着谁走，天然与普通卡片、转发区块两条路径一一对应；
 * 2. 不同卡片之间天然互不相同，不会串到别的卡片的同名元素上。
 */
fun formatPostPartSharedKey(containerKey: String, part: String): String =
    if (containerKey.isBlank() || part.isBlank()) "" else "$containerKey#$part"

/**
 * 卡片内部元素共享元素转场的统一实现。
 *
 * 为什么要把卡片里的头像 / 昵称 / 正文也拆成共享元素：
 * 仅做容器形变时，容器的 [SharedTransitionScope.ResizeMode.RemeasureToBounds]
 * 只能保证外框连续，框内两份内容依旧分别按信息流卡片和正文各自的排版绘制——
 * 卡片昵称 titleSmall、正文昵称 titleMedium，头像 38.dp / 44.dp，
 * 加上两侧 padding 不同带来的纵向位移，两份内容其实是错位的，
 * 用户看到的观感便是「先糊一层再淡出来」。
 *
 * 抽成共享元素后，两个副本在每一帧都落在同一份插值 Bounds 上：
 * 位置天然重合，剩下的只有尺寸差异，交叉淡化因此读作「平稳放大」而非残影。
 *
 * @param containerKey 元素所属容器的 key（卡片 key 或转发区块 key）
 * @param part 元素命名空间，见 [POST_PART_AVATAR] / [POST_PART_NAME] / [POST_PART_CONTENT]
 * @param ownerKey 容器形变的宿主 key（转发区块专属）；普通卡片传空即可，随容器命中参与
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.postPartSharedBounds(
    containerKey: String,
    part: String,
    ownerKey: String?,
    resizeMode: SharedTransitionScope.ResizeMode,
    clipShape: Shape,
    boundsTransform: BoundsTransform = WeiboCardMorphBoundsTransform,
    exitFade: ExitTransition = PostPartExitFade,
    enterFade: EnterTransition = PostPartEnterFade
): Modifier {
    val partKey = formatPostPartSharedKey(containerKey, part)
    if (partKey.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isContainerSelected(containerKey, ownerKey)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@postPartSharedBounds.sharedBounds(
                sharedContentState = rememberSharedContentState(key = partKey),
                animatedVisibilityScope = animScope,
                enter = enterFade,
                exit = exitFade,
                boundsTransform = boundsTransform,
                resizeMode = resizeMode,
                clipInOverlayDuringTransition = OverlayClip(clipShape = clipShape),
                zIndexInOverlay = PostPartZIndexInOverlay
            )
        }
    } else {
        this
    }
}

/**
 * 卡片 / 正文作者昵称行共享元素：卡片的 titleSmallEmphasized 与正文的
 * titleMediumEmphasized 之间靠等比缩放连续过渡，大 V 角标也随行一起放大。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboPostNameSharedElement(
    containerKey: String,
    ownerKey: String? = null
): Modifier = postPartSharedBounds(
    containerKey = containerKey,
    part = POST_PART_NAME,
    ownerKey = ownerKey,
    resizeMode = PostPartScaleResizeMode,
    clipShape = RectangleShape
)

/**
 * 卡片 / 正文「时间 · 来源」副标题共享元素：labelSmall → labelMediumEmphasized 等比缩放。
 * 它与昵称行同属头部区块，不一起提升的话会在昵称行位移时留在原抖动一下。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboPostSubtitleSharedElement(
    containerKey: String,
    ownerKey: String? = null
): Modifier = postPartSharedBounds(
    containerKey = containerKey,
    part = POST_PART_SUBTITLE,
    ownerKey = ownerKey,
    resizeMode = PostPartScaleResizeMode,
    clipShape = RectangleShape
)

/**
 * 卡片 / 正文文本各自按 lookahead 的最终约束排版。
 * None 保持文字的实际字号，不随动画边界缩小或放大，也不逐帧重新折行。
 * TopStart 固定行首，以位移和短暂交叉淡化衔接两端排版。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboPostContentSharedBounds(
    containerKey: String,
    ownerKey: String? = null,
    part: String = POST_PART_CONTENT
): Modifier = postPartSharedBounds(
    containerKey = containerKey,
    part = part,
    ownerKey = ownerKey,
    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
        contentScale = ContentScale.None,
        alignment = Alignment.TopStart
    ),
    clipShape = RectangleShape,
    exitFade = PostContentExitFade,
    enterFade = PostContentEnterFade
)

/**
 * 嵌套转发区块里的正文共享元素。
 *
 * 转发正文在卡片与正文页两端都是 bodyMedium，只有「@作者:」前缀与容器宽度不同。
 * 和主正文一样提升成共享元素后，飞行期间字号与折行都固定不变、只随容器连续移动，
 * 不会出现「外框在形变、文字在里面逐帧重新折行」的观感；
 * 两端排版差异由 [PostContentEnterFade] / [PostContentExitFade] 的短促交叉淡化交接。
 *
 * @param hostContainerKey 承载该转发区块的外层容器 key（卡片 key 或正文容器 key）
 * @param sectionContainerKey 转发区块自身的 key
 * @param sharingSection 本次形变是否由「转发区块自己」发起
 * @param ownerKey 仅 [sharingSection] 为 true 时需要：锁定唯一一张宿主卡片，
 *                 避免多条微博转发同一条时多张卡片一起动画
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboRetweetContentSharedBounds(
    hostContainerKey: String,
    sectionContainerKey: String,
    sharingSection: Boolean,
    ownerKey: String? = null
): Modifier = weiboPostContentSharedBounds(
    containerKey = if (sharingSection) sectionContainerKey else hostContainerKey,
    ownerKey = ownerKey,
    part = if (sharingSection) POST_PART_CONTENT else POST_PART_RETWEET_CONTENT
)

/** 卡片与正文中的配图逐张匹配；命名空间区分主微博和嵌套转发的图片。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboPostImageSharedElement(
    containerKey: String,
    index: Int,
    shape: Shape,
    ownerKey: String? = null,
    part: String = "image"
): Modifier {
    if (containerKey.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isContainerSelected(containerKey, ownerKey)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboPostImageSharedElement.sharedElement(
                sharedContentState = rememberSharedContentState(
                    key = formatPostPartSharedKey(containerKey, "${part}_$index")
                ),
                animatedVisibilityScope = animScope,
                boundsTransform = WeiboCardMorphBoundsTransform,
                clipInOverlayDuringTransition = OverlayClip(clipShape = shape),
                zIndexInOverlay = PostPartZIndexInOverlay
            )
        }
    } else {
        this
    }
}

/**
 * 构造视频容器共享键，优先基于微博 ID，若无则基于 videoUrl 计算短 hash
 */
fun formatVideoSharedKey(mid: String?, videoUrl: String): String {
    val id = mid?.takeIf { it.isNotBlank() }
    return if (id != null) {
        "video_container_$id"
    } else if (videoUrl.isNotBlank()) {
        "video_container_${videoUrl.hashCode()}"
    } else {
        ""
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboSharedElement(key: String): Modifier {
    if (key.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
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
    ownerKey: String? = null,
    zIndexInOverlay: Float = 1f
): Modifier {
    if (key.isBlank()) return this
    // 相邻分页（动态 / 热门）会同时组合：只有可见页的那份才注册，否则同一条微博的两张卡片
    // 会向同一个 key 注册两份来源，形变可能从屏幕外那张卡片起飞。
    if (!LocalSharedTransitionSourceActive.current) return this
    // 只有本次导航真正被点击的卡片才参与形变，
    // 避免进入博主主页等场景时同 key 的多张卡片一起乱飞。
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isContainerSelected(key, ownerKey)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboCardSharedBounds.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                enter = SharedElementEnterFade,
                exit = SharedElementExitFade,
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

/**
 * 微博视频播放器与信息流视频卡片容器形变（Video Container Morph）转场修饰符：
 * 1. 列表/详情中的视频卡片与全屏 VideoPlayerScreen 视频渲染视口绑定相同的 video key；
 * 2. 转场期间，容器边界由弹性弹簧曲线连续插值，圆角从卡片的 8.dp 平滑变形到全屏的 0.dp；
 * 3. 伴随渐显/渐隐，让打开与退出视频播放器呈现如打开图片般的物理连续感。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboVideoSharedBounds(
    key: String,
    shape: Shape = RectangleShape,
    zIndexInOverlay: Float = 2f
): Modifier {
    if (key.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isContainerSelected(key)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboVideoSharedBounds.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                enter = SharedElementEnterFade,
                exit = SharedElementExitFade,
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

/**
 * 博主头像共享元素（Shared Element）修饰符，专用于「头像 → 博主主页」这条转场：
 * 1. 信息流卡片 38.dp / 正文详情 44.dp → 主页 74.dp 的圆形头像连续放大；
 * 2. 打开正文时不要使用本修饰符——头像是卡片内容的组成部分，
 *    会随 `sharedBounds` 容器一起完成形变，闪烁问题由 Coil 缓存键对齐解决；
 * 3. [ownerKey] 非空时表示这是「来源卡片」，只有被点击的那张卡片上的头像才参与动画，
 *    彻底避免同一博主多条微博卡片同时飞头像。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboAvatarSharedElement(
    key: String,
    ownerKey: String? = null
): Modifier {
    if (key.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isAvatarSelected(key, ownerKey)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboAvatarSharedElement.sharedElement(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animScope,
                boundsTransform = WeiboCardMorphBoundsTransform,
                clipInOverlayDuringTransition = OverlayClip(clipShape = CircleShape)
            )
        }
    } else {
        this
    }
}

/**
 * 认证角标共享元素：与头像共用同一次点击选择（[SharedElementTarget.isAvatarSelected]），
 * 但使用独立的共享 key，避免同一组合里出现重复 key。
 *
 * 头像与角标分别抬升到 overlay：角标 [zIndexInOverlay] 更高一档，飞行过程中始终压在头像上方。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboAvatarBadgeSharedElement(
    avatarKey: String,
    ownerKey: String? = null
): Modifier {
    if (avatarKey.isBlank()) return this
    if (!LocalSharedTransitionSourceActive.current) return this
    val target = LocalSharedElementTarget.current
    if (target != null && !target.isAvatarSelected(avatarKey, ownerKey)) return this
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalNavAnimatedVisibilityScope.current
    return if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            this@weiboAvatarBadgeSharedElement.sharedElement(
                sharedContentState = rememberSharedContentState(key = formatAvatarBadgeSharedKey(avatarKey)),
                animatedVisibilityScope = animScope,
                boundsTransform = WeiboCardMorphBoundsTransform,
                clipInOverlayDuringTransition = OverlayClip(clipShape = CircleShape),
                zIndexInOverlay = 1f
            )
        }
    } else {
        this
    }
}

/**
 * 微博列表卡片 / 正文页的博主头像共享元素，按本次导航的目的地在两条路径间二选一：
 * 1. 头像 → 博主主页：走 uid 维度的 [avatarSharedKey]（见 [weiboAvatarSharedElement]）；
 * 2. 卡片 → 正文：头像作为卡片内部元素单独参与共享元素转场，
 *    38.dp → 44.dp 靠等比缩放连续过渡，不再是「容器形变 + 内容交叉淡化」。
 *
 * 同一个节点只能挂一个共享元素修饰符，所以两条路径必须在这里合并判断；
 * [selectPost] 与 [selectAvatar] 互斥，同一时刻只有一条路径会命中。
 *
 * @param containerKey 头像所属容器的 key（卡片 key；正文页则是本次命中容器的 key）
 * @param avatarSharedKey uid 维度头像 key，博主主页列表可以传空来关闭主页路径
 * @param avatarOwnerKey 主页路径的来源卡片 key（锁定唯一来源，避免多条卡片一起飞头像）
 * @param containerOwnerKey 容器形变的宿主 key（转发区块专属）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.weiboPostAvatarSharedElement(
    containerKey: String,
    avatarSharedKey: String,
    avatarOwnerKey: String? = null,
    containerOwnerKey: String? = null
): Modifier {
    val target = LocalSharedElementTarget.current ?: return this
    if (avatarSharedKey.isNotBlank() && target.isAvatarSelected(avatarSharedKey, avatarOwnerKey)) {
        return weiboAvatarSharedElement(key = avatarSharedKey, ownerKey = avatarOwnerKey)
    }
    return postPartSharedBounds(
        containerKey = containerKey,
        part = POST_PART_AVATAR,
        ownerKey = containerOwnerKey,
        resizeMode = PostPartScaleResizeMode,
        clipShape = CircleShape
    )
}
