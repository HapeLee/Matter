package org.kazusa.matter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import androidx.compose.ui.graphics.RectangleShape
import org.kazusa.matter.ui.detail.PostDetailCache
import org.kazusa.matter.ui.detail.getCardSharedKey
import org.kazusa.matter.ui.navigation.weiboCardSharedBounds
import coil.compose.AsyncImage

@Composable
fun WeiboPostCard(
    post: WeiboStatusDto,
    modifier: Modifier = Modifier,
    isCard: Boolean = true,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onCommentClick: (WeiboStatusDto) -> Unit = { onPostClick(it) },
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String) -> Unit = { _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {}
) {
    val morphState = LocalImageMorphState.current
    val cardSharedKey = remember(post.idStr, post.mid, post.id) { post.getCardSharedKey() }

    if (isCard) {
        Card(
            onClick = {
                if (morphState?.isPresent != true) {
                    PostDetailCache.put(post)
                    onPostClick(post)
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
                onUserClick = onUserClick,
                onPostClick = {
                    PostDetailCache.put(it)
                    onPostClick(it)
                },
                onCommentClick = {
                    PostDetailCache.put(it)
                    onCommentClick(it)
                },
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
                onUserClick = onUserClick,
                onPostClick = {
                    PostDetailCache.put(it)
                    onPostClick(it)
                },
                onCommentClick = {
                    PostDetailCache.put(it)
                    onCommentClick(it)
                },
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
    onUserClick: (Long?) -> Unit,
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit
) {
    val morphState = LocalImageMorphState.current
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

                AsyncImage(
                    model = avatar,
                    contentDescription = post.user?.screenName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onUserClick(post.user?.id) }
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 昵称行：仅昵称 + M3大V图标
                    Row(
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            SelectionContainer {
                Text(
                    text = contentFormatted.annotatedString,
                    inlineContent = contentFormatted.inlineContent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 配图列表 (高清大图，支持 picUrls、picIds、originalPic 与 bmiddlePic 多种返回结构)
            val imageUrls = post.allLargeImageUrls
            if (imageUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                val onGridImageClick = remember(imageUrls, onImageClick) {
                    { index: Int -> onImageClick(imageUrls, index) }
                }
                ImageGrid(
                    images = imageUrls,
                    onImageClick = onGridImageClick
                )
            }

            // 视频媒体卡片
            val videoUrl = post.pageInfo?.mediaInfo?.bestVideoUrl
            if (videoUrl != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val videoCover = post.pageInfo.pagePic ?: ""
                val videoTitle = post.pageInfo.pageTitle ?: WeiboTextFormatter.formatHtmlText(post.textRaw ?: post.text).take(20)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable { onVideoClick(videoUrl, videoTitle) },
                    contentAlignment = Alignment.Center
                ) {
                    if (videoCover.isNotBlank()) {
                        AsyncImage(
                            model = videoCover,
                            contentDescription = "视频封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // 嵌套转发微博：通栏背景变色设计（无多余分割线，自然背景过渡）
        post.retweetedStatus?.let { retweet ->
            RetweetCard(
                retweet = retweet,
                onPostClick = onPostClick,
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
            ActionButton(
                icon = Icons.Outlined.Repeat,
                count = post.repostsCount,
                defaultLabel = "转发",
                onClick = {}
            )
            VerticalDivider(
                modifier = Modifier.height(8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            ActionButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                count = post.commentsCount,
                defaultLabel = "评论",
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
            ActionButton(
                icon = Icons.Outlined.ThumbUp,
                count = post.attitudesCount,
                defaultLabel = "赞",
                onClick = {}
            )
            Spacer(modifier = Modifier.weight(1f))
            FavoriteActionButton(post = post)
        }
    }
}

@Composable
private fun RetweetCard(
    retweet: WeiboStatusDto,
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String) -> Unit = { _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val morphState = LocalImageMorphState.current
    Column(
        modifier = modifier
            .fillMaxWidth()
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

        SelectionContainer {
            Text(
                text = fullAnnotated,
                inlineContent = bodyFormatted.inlineContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val retweetImages = retweet.allLargeImageUrls
        if (retweetImages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            val onGridImageClick = remember(retweetImages, onImageClick) {
                { index: Int -> onImageClick(retweetImages, index) }
            }
            ImageGrid(
                images = retweetImages,
                onImageClick = onGridImageClick
            )
        }

        val videoUrl = retweet.pageInfo?.mediaInfo?.bestVideoUrl
        if (videoUrl != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val videoCover = retweet.pageInfo.pagePic ?: ""
            val videoTitle = retweet.pageInfo.pageTitle ?: WeiboTextFormatter.formatHtmlText(retweet.textRaw ?: retweet.text).take(20)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .clickable { onVideoClick(videoUrl, videoTitle) },
                contentAlignment = Alignment.Center
            ) {
                if (videoCover.isNotBlank()) {
                    AsyncImage(
                        model = videoCover,
                        contentDescription = "视频封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    count: String,
    defaultLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = defaultLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatCount(count, defaultLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

private fun formatCount(countStr: String, defaultLabel: String): String {
    if (countStr.isBlank() || countStr == "0") return defaultLabel
    val countInt = countStr.toIntOrNull() ?: return countStr
    return when {
        countInt >= 10_000 -> String.format("%.1f万", countInt / 10_000.0)
        else -> countInt.toString()
    }
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
