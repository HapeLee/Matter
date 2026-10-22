package org.kazusa.matter.ui.components

import androidx.compose.foundation.shape.CircleShape


import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.cache.UserAvatarCache
import org.kazusa.matter.data.model.VideoStreamInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kazusa.matter.core.util.TimeUtils
import org.kazusa.matter.core.util.WeiboTextFormatter
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.LikeState
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import androidx.compose.ui.graphics.RectangleShape
import org.kazusa.matter.ui.detail.PostDetailCache
import org.kazusa.matter.ui.detail.getCardSharedKey
import org.kazusa.matter.ui.detail.getRetweetSectionSharedKey
import org.kazusa.matter.ui.navigation.LocalSharedElementTarget
import org.kazusa.matter.ui.navigation.formatAvatarSharedKey
import org.kazusa.matter.ui.navigation.weiboCardSharedBounds
import org.kazusa.matter.ui.navigation.weiboPostAvatarSharedElement
import org.kazusa.matter.ui.navigation.weiboPostContentSharedBounds
import org.kazusa.matter.ui.navigation.weiboPostNameSharedElement
import org.kazusa.matter.ui.navigation.weiboRetweetContentSharedBounds
import org.kazusa.matter.ui.navigation.weiboPostSubtitleSharedElement
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun WeiboPostCard(
    post: WeiboStatusDto,
    modifier: Modifier = Modifier,
    isCard: Boolean = true,
    /**
     * 是否允许卡片头像参与共享元素动画。
     * 博主主页列表里的卡片需要关闭：主页顶部的大头像才是动画目标，
     * 否则同一 uid 的头像会在同一页面里出现多个共享元素。
     */
    enableAvatarSharedTransition: Boolean = true,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onCommentClick: (WeiboStatusDto) -> Unit = { onPostClick(it) },
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit = { _, _, _, _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {}
) {
    val morphState = LocalImageMorphState.current
    val sharedElementTarget = LocalSharedElementTarget.current
    val cardSharedKey = remember(post.idStr, post.mid, post.id) { post.getCardSharedKey() }
    val avatarSharedKey = remember(post.user?.id, enableAvatarSharedTransition) {
        if (enableAvatarSharedTransition) formatAvatarSharedKey(post.user?.id) else ""
    }

    // 打开正文：只做卡片容器的形变，头像作为卡片内容随之一起过渡
    val handlePostClick: (WeiboStatusDto) -> Unit = { clicked ->
        sharedElementTarget?.selectPost(clicked.getCardSharedKey())
        PostDetailCache.put(clicked)
        onPostClick(clicked)
    }
    val handleCommentClick: (WeiboStatusDto) -> Unit = { clicked ->
        sharedElementTarget?.selectPost(clicked.getCardSharedKey())
        PostDetailCache.put(clicked)
        onCommentClick(clicked)
    }
    // 点击卡片内的「原微博」区域：从转发区块本身起飞，
    // 不能复用原微博自己的 post_card_<id>，否则动画会从信息流里那张原微博卡片开始
    val handleRetweetClick: (WeiboStatusDto) -> Unit = { clicked ->
        sharedElementTarget?.selectPost(
            cardKey = clicked.getRetweetSectionSharedKey(),
            ownerKey = cardSharedKey
        )
        PostDetailCache.put(clicked)
        onPostClick(clicked)
    }
    // 点击头像进入主页：只让头像参与共享元素动画，卡片容器保持静态
    val handleUserClick: (Long?) -> Unit = { clickedUid ->
        if (avatarSharedKey.isNotBlank()) {
            sharedElementTarget?.selectAvatar(
                ownerKey = cardSharedKey,
                avatarKey = avatarSharedKey
            )
        }
        onUserClick(clickedUid)
    }

    if (isCard) {
        Card(
            onClick = {
                if (morphState?.isPresent != true) {
                    handlePostClick(post)
                }
            },
            modifier = modifier
                .fillMaxWidth()
                .weiboCardSharedBounds(
                    key = cardSharedKey,
                    shape = MaterialTheme.shapes.medium
                )
                .clip(MaterialTheme.shapes.medium),
            shape = MaterialTheme.shapes.medium, // M3E 20.dp 表现力圆角
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 0.dp,
                pressedElevation = 2.dp
            )
        ) {
            PostCardContent(
                post = post,
                isCard = true,
                avatarSharedKey = avatarSharedKey,
                sharedOwnerKey = cardSharedKey,
                onUserClick = handleUserClick,
                onPostClick = handlePostClick,
                onCommentClick = handleCommentClick,
                onRetweetClick = handleRetweetClick,
                onImageClick = onImageClick,
                onVideoClick = onVideoClick,
                onMentionClick = onMentionClick,
                onTopicClick = onTopicClick
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .weiboCardSharedBounds(
                    key = cardSharedKey,
                    shape = RectangleShape
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            PostCardContent(
                post = post,
                isCard = false,
                avatarSharedKey = avatarSharedKey,
                sharedOwnerKey = cardSharedKey,
                onUserClick = handleUserClick,
                onPostClick = handlePostClick,
                onCommentClick = handleCommentClick,
                onRetweetClick = handleRetweetClick,
                onImageClick = onImageClick,
                onVideoClick = onVideoClick,
                onMentionClick = onMentionClick,
                onTopicClick = onTopicClick
            )
        }
    }
}


@Composable
private fun PostCardContent(
    post: WeiboStatusDto,
    isCard: Boolean,
    avatarSharedKey: String,
    sharedOwnerKey: String,
    onUserClick: (Long?) -> Unit,
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit,
    onRetweetClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit
) {
    val morphState = LocalImageMorphState.current
    val sharedElementTarget = LocalSharedElementTarget.current
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 主微博内容区 (头像、昵称、正文、主贴多图/视频)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isCard) 16.dp else 16.dp,
                    end = if (isCard) 16.dp else 16.dp,
                    top = if (isCard) 14.dp else 8.dp,
                    bottom = if (post.retweetedStatus != null) 10.dp else 8.dp
                )
        ) {
            // 头部：头像与作者信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val avatar = post.user?.avatarLarge
                    ?: post.user?.profileImageUrl
                    ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
                // 记录头像地址与昵称：进入博主主页首帧即可复用同一地址与名字，
                // 避免共享元素飞行期间闪默认头像、或动画结束后昵称再跳一次
                SideEffect {
                    UserAvatarCache.put(post.user?.id, avatar, post.user?.screenName)
                    val verifyType = post.user.getVerifyType()
                    if (verifyType != WeiboVerifyType.NONE) {
                        UserAvatarCache.putVerify(post.user?.id, verifyType.ordinal)
                    }
                }
                // 缓存键与共享元素 key 对齐：卡片→正文时头像单独变形也不会因重新解码而闪
                val avatarRequest = rememberSharedAvatarRequest(avatar, avatarSharedKey)

                // 头像：卡片 → 正文 时作为卡片内部元素单独参与共享元素转场（38.dp → 44.dp 等比缩放）；
                // 头像 → 博主主页 时切换回 uid 维度的共享元素，两条路径互斥
                AsyncImage(
                    model = avatarRequest,
                    contentDescription = post.user?.screenName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .weiboPostAvatarSharedElement(
                            containerKey = sharedOwnerKey,
                            avatarSharedKey = avatarSharedKey,
                            avatarOwnerKey = sharedOwnerKey
                        )
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onUserClick(post.user?.id) }
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 昵称行：仅昵称 + M3大V图标
                    // 卡片 → 正文 时整行共享元素过渡：titleSmallEmphasized → titleMediumEmphasized 平滑放大
                    Row(
                        modifier = Modifier.weiboPostNameSharedElement(containerKey = sharedOwnerKey),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = post.user?.screenName ?: "微博用户",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val verifyType = post.user.getVerifyType()
                        if (verifyType != WeiboVerifyType.NONE) {
                            UserVerifyBadge(verifyType = verifyType, size = 15.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val subtitle = remember(post.createdAt, post.source, post.regionName) {
                        formatSubtitle(post.createdAt, post.source, post.regionName)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weiboPostSubtitleSharedElement(containerKey = sharedOwnerKey)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 微博正文 (支持长按文本自由复制，高亮并支持点击@博主、#话题#、网页链接与表情图标)
            // HTML 解码 + AnnotatedString 构建开销较大，按文本内容缓存，避免每次重组重跑
            val primaryColor = MaterialTheme.colorScheme.primary
            val contentFormatted = remember(post.textRaw, post.text, primaryColor, onMentionClick, onTopicClick) {
                WeiboTextFormatter.formatWeiboContent(
                    rawText = post.textRaw ?: post.text,
                    primaryColor = primaryColor,
                    onMentionClick = onMentionClick,
                    onTopicClick = onTopicClick
                )
            }
            // 两端字号一致，各自固定最终折行，转场保持字号并移动、淡化排版。
            SelectionContainer {
                Text(
                    text = contentFormatted.annotatedString,
                    inlineContent = contentFormatted.inlineContent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weiboPostContentSharedBounds(containerKey = sharedOwnerKey)
                )
            }

            // 媒体区：图片与视频按服务端下发的先后顺序混排在同一宫格里
            val mediaItems = post.mediaItems
            if (mediaItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusMediaGrid(
                    items = mediaItems,
                    ownerKey = sharedOwnerKey,
                    sharedContainerKey = sharedOwnerKey,
                    livePhotoMap = post.livePhotoVideoMap,
                    singleVideoHeight = 190.dp,
                    videoTitleFallback = post.videoTitleFallback(),
                    onImageClick = onImageClick,
                    onVideoClick = onVideoClick
                )
            }
        }

        // 嵌套转发微博：通栏背景变色设计（无多余分割线，自然背景过渡）
        post.retweetedStatus?.let { retweet ->
            RetweetCard(
                retweet = retweet,
                hostSharedKey = sharedOwnerKey,
                onPostClick = onRetweetClick,
                onImageClick = onImageClick,
                onVideoClick = onVideoClick,
                onMentionClick = onMentionClick,
                onTopicClick = onTopicClick
            )
        }

        // 底部操作栏：转发、评论、赞居左（按钮间带细竖分割线），弹性留白，收藏居右（无分割线）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = if (post.retweetedStatus != null) 2.dp else 0.dp,
                    bottom = if (isCard) 8.dp else 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WeiboActionButton(
                icon = Icons.Outlined.Repeat,
                count = post.repostsCount,
                label = "转发",
                onClick = {}
            )
            VerticalDivider(
                modifier = Modifier.height(8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            WeiboActionButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                count = post.commentsCount,
                label = "评论",
                onClick = {
                    if (morphState?.isPresent != true) {
                        onCommentClick(post)
                    }
                }
            )
            VerticalDivider(
                modifier = Modifier.height(8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            PostLikeActionButton(post = post)
            Spacer(modifier = Modifier.weight(1f))
            FavoriteActionButton(post = post)
        }
    }
}

@Composable
private fun RetweetCard(
    retweet: WeiboStatusDto,
    /** 外层转发卡片的唯一 key：既用于转发区块共享元素的来源去重，也用于图片形变的来源标识 */
    hostSharedKey: String,
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit = { _, _, _, _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val morphState = LocalImageMorphState.current
    val sharedElementTarget = LocalSharedElementTarget.current
    val retweetSectionKey = remember(retweet.idStr, retweet.mid, retweet.id) {
        retweet.getRetweetSectionSharedKey()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 转发区块自己就是形变来源，与「原微博卡片」的 key 分开，
            // 并用宿主卡片 key 锁定唯一来源（同一条原微博可能被多条微博转发）
            .weiboCardSharedBounds(
                key = retweetSectionKey,
                shape = RectangleShape,
                ownerKey = hostSharedKey
            )
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable {
                if (morphState?.isPresent != true) {
                    onPostClick(retweet)
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val authorName = retweet.user?.screenName ?: "原微博"
        val primaryColor = MaterialTheme.colorScheme.primary
        val authorLinkBaseSpan = MaterialTheme.typography.bodyMediumEmphasized.toSpanStyle()
        val bodyFormatted = remember(retweet.textRaw, retweet.text, primaryColor, onMentionClick, onTopicClick) {
            WeiboTextFormatter.formatWeiboContent(
                rawText = retweet.textRaw ?: retweet.text,
                primaryColor = primaryColor,
                onMentionClick = onMentionClick,
                onTopicClick = onTopicClick
            )
        }
        val fullAnnotated = remember(bodyFormatted, authorName, primaryColor, onMentionClick, authorLinkBaseSpan) {
            buildAnnotatedString {
                val authorLinkStyle = authorLinkBaseSpan.copy(color = primaryColor)
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = "mention",
                        styles = androidx.compose.ui.text.TextLinkStyles(style = authorLinkStyle),
                        linkInteractionListener = { onMentionClick(authorName) }
                    )
                ) {
                    append("@$authorName")
                }
                append(": ")
                append(bodyFormatted.annotatedString)
            }
        }

        // 打开宿主微博时（卡片 → 正文），本区块的文字 / 图片都只是「宿主卡片里的转发内容」；
        // 打开原微博时（转发区块 → 正文），它们就是目标正文的主正文 / 主图。
        // 两种身份必须使用不同的共享元素 key，否则会和宿主自己的主内容撞 key。
        val sharingHost = sharedElementTarget?.isContainerSelected(hostSharedKey) == true

        // 转发区块正文：与正文 / 主正文走同一套「固定排版 + 交叉淡化」处理，
        // 避免容器形变期间文字逐帧重新折行；
        // ownerKey 锁定宿主卡片，避免多条微博转发同一条时多张卡片一起动画
        SelectionContainer {
            Text(
                text = fullAnnotated,
                inlineContent = bodyFormatted.inlineContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weiboRetweetContentSharedBounds(
                    hostContainerKey = hostSharedKey,
                    sectionContainerKey = retweetSectionKey,
                    sharingSection = !sharingHost,
                    ownerKey = if (sharingHost) null else hostSharedKey
                )
            )
        }

        // 转发微博的媒体区：与主微博同一套混排宫格，图片与视频共用一条顺序
        val retweetMediaItems = retweet.mediaItems
        if (retweetMediaItems.isNotEmpty()) {
            // 打开宿主微博时匹配正文内的转发图片；打开原微博时匹配正文主图。
            Spacer(modifier = Modifier.height(8.dp))
            StatusMediaGrid(
                items = retweetMediaItems,
                // 与原微博卡片的图片宫格区分，避免两张卡片互相让位/抢收拢目标
                ownerKey = "retweet_$hostSharedKey",
                sharedContainerKey = if (sharingHost) {
                    hostSharedKey
                } else {
                    retweetSectionKey
                },
                sharedContainerOwnerKey = if (sharingHost) {
                    null
                } else {
                    hostSharedKey
                },
                sharedImagePart = if (sharingHost) {
                    "retweet_image"
                } else {
                    "image"
                },
                livePhotoMap = retweet.livePhotoVideoMap,
                singleVideoHeight = 170.dp,
                videoTitleFallback = retweet.videoTitleFallback(),
                onImageClick = onImageClick,
                onVideoClick = onVideoClick
            )
        }
    }
}

/**
 * 信息流卡片的点赞按钮。
 *
 * 点击后立即乐观更新，再异步写回服务端；失败时回滚并把错误提示给用户。
 * 页面未提供接口服务（例如预览场景）时按钮保持不可点击，避免空转。
 */
@Composable
private fun PostLikeActionButton(
    post: WeiboStatusDto,
    modifier: Modifier = Modifier
) {
    val repository = LocalLikeRepository.current
    // 请求交给会话级 scope：列表项被回收时点赞仍会完成，不会留下永久 pending
    val scope = LocalUserActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val morphState = LocalImageMorphState.current
    val likeState = repository?.postState(post, LocalPostLikeStates.current)
        ?: LikeState(post.isLiked, parseInteractionCount(post.attitudesCount))

    val onLikeClick: () -> Unit = {
        if (morphState?.isPresent != true && repository != null && !likeState.pending) {
            scope.launch {
                val message = repository.togglePost(post) ?: return@launch
                if (snackbarHostState != null) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    WeiboActionButton(
        icon = if (likeState.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        count = likeState.count?.toString() ?: post.attitudesCount,
        label = "赞",
        tint = if (likeState.liked) MaterialTheme.colorScheme.primary else null,
        busy = likeState.pending,
        enabled = repository != null,
        onClick = onLikeClick,
        modifier = modifier
    )
}

private fun formatSubtitle(createdAt: String, source: String?, regionName: String? = null): String {
    val friendlyTime = TimeUtils.formatWeiboTime(createdAt)
    val cleanSource = source?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
    val cleanRegion = regionName?.trim().orEmpty()
    return buildString {
        append(friendlyTime)
        if (cleanSource.isNotBlank()) {
            append(" · ")
            append(cleanSource)
        }
        if (cleanRegion.isNotBlank()) {
            append(" · ")
            append(cleanRegion)
        }
    }
}

private fun formatHtmlText(html: String): String {
    return html
        .replace("<br />", "\n")
        .replace("<br/>", "\n")
        .replace("<br>", "\n")
        .replace(Regex("<[^>]*>"), "")
        .trim()
}
