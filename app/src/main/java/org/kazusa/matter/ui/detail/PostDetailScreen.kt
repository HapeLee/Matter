package org.kazusa.matter.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.util.TimeUtils
import org.kazusa.matter.core.util.WeiboTextFormatter
import androidx.compose.ui.text.style.TextOverflow
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.ui.components.AuthorBadgeTag
import org.kazusa.matter.ui.components.BloggerSuffixTag
import org.kazusa.matter.ui.components.ImageGrid
import org.kazusa.matter.ui.components.UserAvatarWithBadge
import org.kazusa.matter.ui.components.UserVerifyBadge
import org.kazusa.matter.ui.components.UserVipBadge
import org.kazusa.matter.ui.components.WeiboVerifyType
import org.kazusa.matter.ui.components.getVerifyType
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.material3.LocalContentColor
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleCapsule
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.TopBarTokens
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    postId: String,
    scrollToComments: Boolean = false,
    apiService: WeiboApiService,
    historyRepository: BrowsingHistoryRepository,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var post by remember { mutableStateOf<WeiboStatusDto?>(null) }
    var comments by remember { mutableStateOf<List<WeiboCommentDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isCommentsLoading by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sortByHot by remember { mutableStateOf(true) } // true: 按热度, false: 按时间
    val listState = rememberLazyListState()

    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }
    val backdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null

    fun loadComments(hotSort: Boolean, targetPostId: String? = null) {
        val idToQuery = targetPostId ?: post?.idStr ?: post?.id?.toString() ?: postId
        isCommentsLoading = true
        commentsError = null
        coroutineScope.launch {
            try {
                when (val commentsRes = apiService.getComments(idToQuery, sortByHot = hotSort)) {
                    is ApiResult.Success -> comments = commentsRes.data.data
                    is ApiResult.Error -> commentsError = "评论加载失败，请点击重试"
                    ApiResult.Loading -> {}
                }
            } finally {
                isCommentsLoading = false
            }
        }
    }

    LaunchedEffect(postId) {
        isLoading = true
        // 加载微博正文
        var loadedPost: WeiboStatusDto? = null
        when (val postRes = apiService.getPostDetail(postId)) {
            is ApiResult.Success -> {
                loadedPost = postRes.data
                post = loadedPost
                // 本地浏览历史：同一条微博只保留最近一次（重复进入会刷新时间并前移）
                historyRepository.recordPost(postRes.data)
            }
            is ApiResult.Error -> {
                errorMessage = postRes.message
            }
            else -> {}
        }
        // 初始加载热门评论
        val targetId = loadedPost?.idStr ?: loadedPost?.id?.toString() ?: postId
        loadComments(sortByHot, targetId)
        isLoading = false
    }

    // 若从外部点击“评论”进入，当正文准备好后自动平滑滚动到评论区头部
    LaunchedEffect(post != null, scrollToComments) {
        if (scrollToComments && post != null) {
            // Item 0 是正文，Item 1 是评论区头部标题栏
            listState.animateScrollToItem(1, 0)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 沉浸式顶栏：透明 TopAppBar + 随滚动淡入的渐变遮罩
            ImmersiveTopBar(
                listState = listState,
                animationLabel = "DetailTopBarScrim",
                title = { scroll ->
                    TopBarTitleText(
                        text = "微博正文",
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                },
                actions = { scroll ->
                    TopBarActionButton(
                        imageVector = Icons.Default.Share,
                        contentDescription = "分享当前微博链接",
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = {
                            val currentPost = post
                            val postIdToUse = currentPost?.idStr ?: currentPost?.mid ?: currentPost?.id?.toString() ?: postId
                            val userId = currentPost?.user?.id?.toString().orEmpty()
                            val shareUrl = if (userId.isNotBlank()) {
                                "https://weibo.com/$userId/$postIdToUse"
                            } else {
                                "https://m.weibo.cn/status/$postIdToUse"
                            }
                            val author = currentPost?.user?.screenName?.let { "@$it: " } ?: ""
                            val snippet = (currentPost?.textRaw ?: currentPost?.text ?: "")
                                .replace(Regex("<[^>]*>"), "")
                                .trim()
                                .take(80)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "$author$snippet\n$shareUrl")
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "分享当前微博链接")
                            context.startActivity(shareIntent)
                        }
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                errorMessage != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = errorMessage ?: "加载失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                post != null -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = 16.dp)
                    ) {
                        // 专为沉浸式阅读设计的正文排版（大头像、作者大号、正文更大间距）
                        item {
                            PostDetailBody(
                                post = post!!,
                                onUserClick = onUserClick,
                                onImageClick = onImageClick,
                                onVideoClick = onVideoClick,
                                onMentionClick = onMentionClick,
                                onTopicClick = onTopicClick
                            )
                        }

                        // 评论栏标题与单图标排序切换（热度 🔥 / 时间 ⏳ 快速一键切换）
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "评论 (${comments.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // 单图标切换按钮：按热度显示火焰，按时间显示排序图标
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            val newSort = !sortByHot
                                            sortByHot = newSort
                                            loadComments(newSort)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    AnimatedContent(
                                        targetState = sortByHot,
                                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                                        label = "CommentSortIcon"
                                    ) { isHot ->
                                        Icon(
                                            imageVector = if (isHot) Icons.Outlined.Whatshot else Icons.AutoMirrored.Outlined.Sort,
                                            contentDescription = if (isHot) "按热度排序" else "按时间排序",
                                            tint = if (isHot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(
                                        text = if (sortByHot) "按热度" else "按时间",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (sortByHot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                thickness = 0.8.dp
                            )
                        }

                        if (isCommentsLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            if (commentsError != null) {
                                item {
                                    Text(
                                        text = commentsError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { loadComments(sortByHot) }
                                            .padding(24.dp)
                                    )
                                }
                            }
                            if (comments.isEmpty() && commentsError == null) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "暂无评论",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                val postAuthorId = post?.user?.id
                                val postAuthorName = post?.user?.screenName
                                items(comments, key = { it.listKey }, contentType = { "comment" }) { comment ->
                                    CommentThread(
                                        comment = comment,
                                        postId = post?.idStr ?: post?.id?.toString() ?: postId,
                                        apiService = apiService,
                                        postAuthorId = postAuthorId,
                                        postAuthorName = postAuthorName,
                                        onUserClick = onUserClick,
                                        onMentionClick = onMentionClick,
                                        onTopicClick = onTopicClick,
                                        onCopyText = remember(clipboardManager, context) {
                                            { txt: String ->
                                                clipboardManager.setText(AnnotatedString(txt))
                                                Toast.makeText(context, "评论已复制", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        thickness = 0.6.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 专为正文详情页定制的优雅排版：
 * - 44.dp 大头像，呼吸感充足的头部信息
 * - 纯净舒适的字体行距与排版层次
 * - 原博转发通栏变色，下附操作统计栏与细致分割线
 */
@Composable
private fun PostDetailBody(
    post: WeiboStatusDto,
    onUserClick: (Long?) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 主微博作者与正文内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 作者头部
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
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onUserClick(post.user?.id) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 昵称行：仅昵称 + M3大V图标
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = post.user?.screenName ?: "微博用户",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val verifyType = post.user.getVerifyType()
                        if (verifyType != WeiboVerifyType.NONE) {
                            UserVerifyBadge(verifyType = verifyType, size = 16.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val subtitle = remember(post.createdAt, post.source, post.regionName) {
                        formatSubtitle(post.createdAt, post.source, post.regionName)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 微博正文 (宽松舒适的阅读体验，高亮并支持点击@博主、#话题#、网页链接与表情图标)
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
                Spacer(modifier = Modifier.height(12.dp))
                val onGridImageClick = remember(imageUrls, onImageClick) {
                    { index: Int -> onImageClick(imageUrls, index) }
                }
                ImageGrid(
                    images = imageUrls,
                    onImageClick = onGridImageClick
                )
            }

            // 视频卡片
            val videoUrl = post.pageInfo?.mediaInfo?.bestVideoUrl
            if (videoUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val videoCover = post.pageInfo.pagePic ?: ""
                val videoTitle = post.pageInfo.pageTitle ?: WeiboTextFormatter.formatHtmlText(post.textRaw ?: post.text).take(20)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
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
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
        }

        // 嵌套原微博：全宽背景变色沉浸呈现
        post.retweetedStatus?.let { retweet ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val authorName = retweet.user?.screenName ?: "原微博"
                val retweetPrimaryColor = MaterialTheme.colorScheme.primary
                val authorLinkBaseSpan = MaterialTheme.typography.bodyMediumEmphasized.toSpanStyle()
                val bodyFormatted = remember(retweet.textRaw, retweet.text, retweetPrimaryColor, onMentionClick, onTopicClick) {
                    WeiboTextFormatter.formatWeiboContent(
                        rawText = retweet.textRaw ?: retweet.text,
                        primaryColor = retweetPrimaryColor,
                        onMentionClick = onMentionClick,
                        onTopicClick = onTopicClick
                    )
                }
                val fullAnnotated = remember(bodyFormatted, authorName, retweetPrimaryColor, onMentionClick, authorLinkBaseSpan) {
                    buildAnnotatedString {
                        val authorLinkStyle = authorLinkBaseSpan.copy(color = retweetPrimaryColor)
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
                    Spacer(modifier = Modifier.height(10.dp))
                    val onGridImageClick = remember(retweetImages, onImageClick) {
                        { index: Int -> onImageClick(retweetImages, index) }
                    }
                    ImageGrid(
                        images = retweetImages,
                        onImageClick = onGridImageClick
                    )
                }
            }
        }

        // 底部转发、评论、点赞统计栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailActionButton(
                icon = Icons.Outlined.Repeat,
                count = post.repostsCount,
                defaultLabel = "转发",
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                modifier = Modifier.height(14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            DetailActionButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                count = post.commentsCount,
                defaultLabel = "评论",
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                modifier = Modifier.height(14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            DetailActionButton(
                icon = Icons.Outlined.ThumbUp,
                count = post.attitudesCount,
                defaultLabel = "赞",
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            thickness = 0.8.dp
        )
    }
}

@Composable
private fun DetailActionButton(
    icon: ImageVector,
    count: String,
    defaultLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = defaultLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatCount(count, defaultLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

@Composable
internal fun CommentRow(
    comment: WeiboCommentDto,
    isPostAuthor: Boolean = false,
    isReply: Boolean = false,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val replyTarget = comment.replyComment?.user
    val replyText = comment.replyOriginalText?.takeIf { it.isNotBlank() } ?: comment.text
    val cleanText = remember(comment.text) { WeiboTextFormatter.formatHtmlText(comment.text) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val commentFormatted = remember(replyText, primaryColor, onMentionClick, onTopicClick) {
        WeiboTextFormatter.formatWeiboContent(
            rawText = replyText,
            primaryColor = primaryColor,
            onMentionClick = onMentionClick,
            onTopicClick = onTopicClick
        )
    }
    val metadata = remember(comment) { formatCommentMetadata(comment) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCopyText(cleanText) }
            .padding(horizontal = if (isReply) 10.dp else 16.dp, vertical = if (isReply) 8.dp else 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatar = comment.user?.avatarLarge
                ?: comment.user?.profileImageUrl
                ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

            AsyncImage(
                model = avatar,
                contentDescription = comment.user?.screenName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (isReply) 30.dp else 38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onUserClick(comment.user?.id) }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = comment.user?.screenName ?: "微博网友",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { onUserClick(comment.user?.id) }
                        )
                        if (isPostAuthor) {
                            AuthorBadgeTag()
                        }
                        val verifyType = comment.user.getVerifyType()
                        if (verifyType != WeiboVerifyType.NONE) {
                            UserVerifyBadge(verifyType = verifyType, size = 13.dp)
                        }
                    }
                    if (comment.likeCount != "0" && comment.likeCount.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ThumbUp,
                                contentDescription = "赞",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = comment.likeCount,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (metadata.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

            }
        }

        Column(modifier = Modifier.padding(start = if (isReply) 38.dp else 46.dp)) {
            Spacer(modifier = Modifier.height(if (isReply) 6.dp else 8.dp))

            if (!replyTarget?.screenName.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "回复 ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "@${replyTarget.screenName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            if (replyTarget.id != null) onUserClick(replyTarget.id)
                            else onMentionClick(replyTarget.screenName)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            SelectionContainer {
                Text(
                    text = commentFormatted.annotatedString,
                    inlineContent = commentFormatted.inlineContent,
                    style = if (isReply) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatCommentMetadata(comment: WeiboCommentDto): String {
    val source = WeiboTextFormatter.formatHtmlText(comment.source)
    val region = WeiboTextFormatter.formatHtmlText(comment.regionName)
    val locationPrefix = Regex("""^(?:(?:IP属地|发布于|来自)\s*[:：]?\s*)+""")
    val sourceIsLocation = locationPrefix.containsMatchIn(source)
    val location = locationPrefix.replace(region.ifBlank { if (sourceIsLocation) source else "" }, "").trim()
    return listOfNotNull(
        comment.createdAt.takeIf { it.isNotBlank() }?.let(TimeUtils::formatWeiboTime),
        location.takeIf { it.isNotBlank() },
        source.takeIf { it.isNotBlank() && !sourceIsLocation && it != region }
    ).joinToString(" · ")
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
