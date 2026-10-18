package org.kazusa.matter.ui.detail

import android.content.Intent
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.RectangleShape
import org.kazusa.matter.ui.navigation.weiboCardSharedBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.CompositionLocalProvider
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
import org.kazusa.matter.ui.components.FavoriteButton
import org.kazusa.matter.ui.components.LocalSnackbarHostState
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleCapsule
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.TopBarTokens
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

import androidx.compose.runtime.mutableIntStateOf
import org.kazusa.matter.data.model.WeiboAttitudeDto

private enum class DetailSubTab(val label: String) {
    REPOSTS("转发"),
    COMMENTS("评论"),
    ATTITUDES("赞")
}

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
    onPostClick: (WeiboStatusDto) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val initialCachedPost = remember(postId) { PostDetailCache.get(postId) }
    var post by remember(postId) { mutableStateOf(initialCachedPost) }
    var comments by remember { mutableStateOf<List<WeiboCommentDto>>(emptyList()) }
    var reposts by remember { mutableStateOf<List<WeiboStatusDto>>(emptyList()) }
    var attitudes by remember { mutableStateOf<List<WeiboAttitudeDto>>(emptyList()) }

    var isLoading by remember(postId) { mutableStateOf(initialCachedPost == null) }
    var isCommentsLoading by remember { mutableStateOf(false) }
    var isRepostsLoading by remember { mutableStateOf(false) }
    var isAttitudesLoading by remember { mutableStateOf(false) }

    var commentsError by remember { mutableStateOf<String?>(null) }
    var repostsError by remember { mutableStateOf<String?>(null) }
    var attitudesError by remember { mutableStateOf<String?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sortByHot by remember { mutableStateOf(true) } // true: 按热度, false: 按时间

    var selectedTab by remember { mutableStateOf(DetailSubTab.COMMENTS) }
    var repostsPage by remember { mutableIntStateOf(1) }
    var attitudesPage by remember { mutableIntStateOf(1) }

    // 点赞状态维护
    var isLiked by remember(post) { mutableStateOf(post?.isLiked == true) }
    var likeCount by remember(post) {
        mutableIntStateOf(post?.attitudesCount?.toIntOrNull() ?: 0)
    }
    var isLiking by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val backdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null

    val showSnackbar: (String) -> Unit = remember(coroutineScope, snackbarHostState) {
        { message: String ->
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

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

    fun loadReposts(page: Int = 1, targetPostId: String? = null) {
        val idToQuery = targetPostId ?: post?.idStr ?: post?.id?.toString() ?: postId
        isRepostsLoading = true
        repostsError = null
        coroutineScope.launch {
            try {
                when (val res = apiService.getReposts(idToQuery, page = page)) {
                    is ApiResult.Success -> {
                        reposts = if (page == 1) res.data.data else (reposts + res.data.data).distinctBy { it.listKey }
                        repostsPage = page
                    }
                    is ApiResult.Error -> repostsError = "转发加载失败，请点击重试"
                    ApiResult.Loading -> {}
                }
            } finally {
                isRepostsLoading = false
            }
        }
    }

    fun loadAttitudes(page: Int = 1, targetPostId: String? = null) {
        val idToQuery = targetPostId ?: post?.idStr ?: post?.id?.toString() ?: postId
        isAttitudesLoading = true
        attitudesError = null
        coroutineScope.launch {
            try {
                when (val res = apiService.getAttitudes(idToQuery, page = page)) {
                    is ApiResult.Success -> {
                        attitudes = if (page == 1) res.data.data else (attitudes + res.data.data).distinctBy { it.listKey }
                        attitudesPage = page
                    }
                    is ApiResult.Error -> attitudesError = "点赞列表加载失败，请点击重试"
                    ApiResult.Loading -> {}
                }
            } finally {
                isAttitudesLoading = false
            }
        }
    }

    fun toggleLike() {
        if (isLiking) return
        val targetPost = post ?: return
        val idToLike = targetPost.idStr ?: targetPost.mid ?: targetPost.id?.toString() ?: postId
        val newLiked = !isLiked
        val prevLiked = isLiked
        val prevCount = likeCount
        isLiked = newLiked
        likeCount = if (newLiked) prevCount + 1 else (prevCount - 1).coerceAtLeast(0)
        isLiking = true
        coroutineScope.launch {
            try {
                when (val res = apiService.setAttitude(idToLike, newLiked)) {
                    is ApiResult.Success -> {
                        val updated = targetPost.copy(
                            isLiked = newLiked,
                            attitudesCount = likeCount.toString()
                        )
                        post = updated
                        PostDetailCache.put(updated)
                    }
                    is ApiResult.Error -> {
                        isLiked = prevLiked
                        likeCount = prevCount
                        showSnackbar(res.message.ifBlank { "操作失败，请重试" })
                    }
                    ApiResult.Loading -> {}
                }
            } finally {
                isLiking = false
            }
        }
    }


    LaunchedEffect(postId) {
        if (post == null) {
            isLoading = true
        }
        // 加载微博正文
        var loadedPost: WeiboStatusDto? = null
        when (val postRes = apiService.getPostDetail(postId)) {
            is ApiResult.Success -> {
                loadedPost = postRes.data
                post = loadedPost
                isLiked = loadedPost.isLiked
                likeCount = loadedPost.attitudesCount.toIntOrNull() ?: 0
                // 本地浏览历史：同一条微博只保留最近一次（重复进入会刷新时间并前移）
                historyRepository.recordPost(postRes.data)
            }
            is ApiResult.Error -> {
                if (post == null) {
                    errorMessage = postRes.message
                }
            }
            else -> {}
        }
        // 初始加载热门评论
        val targetId = loadedPost?.idStr ?: loadedPost?.id?.toString() ?: postId
        loadComments(sortByHot, targetId)
        isLoading = false
    }

    LaunchedEffect(selectedTab) {
        val targetId = post?.idStr ?: post?.id?.toString() ?: postId
        when (selectedTab) {
            DetailSubTab.COMMENTS -> if (comments.isEmpty() && !isCommentsLoading) loadComments(sortByHot, targetId)
            DetailSubTab.REPOSTS -> if (reposts.isEmpty() && !isRepostsLoading) loadReposts(1, targetId)
            DetailSubTab.ATTITUDES -> if (attitudes.isEmpty() && !isAttitudesLoading) loadAttitudes(1, targetId)
        }
    }

    // 若从外部点击“评论”进入，当正文准备好后自动平滑滚动到评论区头部
    LaunchedEffect(post != null, scrollToComments) {
        if (scrollToComments && post != null) {
            // Item 0 是正文，Item 1 是列表第一项
            listState.animateScrollToItem(1, 0)
        }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) { data ->
                    Snackbar(
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
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
                    post?.let { FavoriteButton(it, scroll = scroll, backdrop = backdrop) }
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
                            val detailSharedKey = remember(post?.idStr, post?.mid, post?.id, postId) {
                                post?.getCardSharedKey()?.takeIf { it.isNotBlank() } ?: formatPostDetailSharedKey(postId)
                            }
                            PostDetailBody(
                                post = post!!,
                                isLiked = isLiked,
                                likeCount = likeCount,
                                selectedTab = selectedTab,
                                onTabSelect = { tab ->
                                    selectedTab = tab
                                    coroutineScope.launch { listState.animateScrollToItem(1, 0) }
                                },
                                onLikeClick = { toggleLike() },
                                sortByHot = sortByHot,
                                onToggleSort = {
                                    val newSort = !sortByHot
                                    sortByHot = newSort
                                    loadComments(newSort)
                                },
                                onUserClick = onUserClick,
                                onImageClick = onImageClick,
                                onVideoClick = onVideoClick,
                                onMentionClick = onMentionClick,
                                onTopicClick = onTopicClick,
                                modifier = Modifier
                                    .weiboCardSharedBounds(
                                        key = detailSharedKey,
                                        shape = RectangleShape
                                    )
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                        }

                        when (selectedTab) {
                            DetailSubTab.REPOSTS -> {
                                if (isRepostsLoading && reposts.isEmpty()) {
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
                                } else if (repostsError != null && reposts.isEmpty()) {
                                    item {
                                        Text(
                                            text = repostsError!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { loadReposts(1) }
                                                .padding(24.dp)
                                        )
                                    }
                                } else if (reposts.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "暂无转发",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    items(reposts, key = { it.listKey }, contentType = { "repost" }) { repost ->
                                        RepostItemRow(
                                            repost = repost,
                                            onUserClick = onUserClick,
                                            onMentionClick = onMentionClick,
                                            onTopicClick = onTopicClick,
                                            onPostClick = onPostClick
                                        )
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            thickness = 0.6.dp
                                        )
                                    }
                                }
                            }

                            DetailSubTab.COMMENTS -> {
                                if (isCommentsLoading && comments.isEmpty()) {
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
                                } else if (commentsError != null && comments.isEmpty()) {
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
                                } else if (comments.isEmpty()) {
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
                                            onCopyText = remember(clipboardManager, showSnackbar) {
                                                { txt: String ->
                                                    clipboardManager.setText(AnnotatedString(txt))
                                                    showSnackbar("评论已复制")
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

                            DetailSubTab.ATTITUDES -> {
                                if (isAttitudesLoading && attitudes.isEmpty()) {
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
                                } else if (attitudesError != null && attitudes.isEmpty()) {
                                    item {
                                        Text(
                                            text = attitudesError!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { loadAttitudes(1) }
                                                .padding(24.dp)
                                        )
                                    }
                                } else if (attitudes.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "暂无赞",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    items(attitudes, key = { it.listKey }, contentType = { "attitude" }) { attitude ->
                                        AttitudeItemRow(
                                            attitude = attitude,
                                            onUserClick = onUserClick
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
    isLiked: Boolean,
    likeCount: Int,
    selectedTab: DetailSubTab,
    onTabSelect: (DetailSubTab) -> Unit,
    onLikeClick: () -> Unit,
    sortByHot: Boolean,
    onToggleSort: () -> Unit,
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

        // 底部操作栏：转发、评论、赞居左（参考微博卡片布局，带细竖分割线），弹性留白，右侧放排序按钮（仅图标）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = if (post.retweetedStatus != null) 2.dp else 0.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailActionButton(
                icon = Icons.Outlined.Repeat,
                count = post.repostsCount,
                defaultLabel = "转发",
                isSelected = selectedTab == DetailSubTab.REPOSTS,
                onClick = { onTabSelect(DetailSubTab.REPOSTS) }
            )
            VerticalDivider(
                modifier = Modifier.height(8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            DetailActionButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                count = post.commentsCount,
                defaultLabel = "评论",
                isSelected = selectedTab == DetailSubTab.COMMENTS,
                onClick = { onTabSelect(DetailSubTab.COMMENTS) }
            )
            VerticalDivider(
                modifier = Modifier.height(8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )
            DetailLikeActionButton(
                isLiked = isLiked,
                likeCount = likeCount,
                isSelected = selectedTab == DetailSubTab.ATTITUDES,
                onLikeClick = onLikeClick,
                onTabSelect = { onTabSelect(DetailSubTab.ATTITUDES) }
            )

            Spacer(modifier = Modifier.weight(1f))

            if (selectedTab == DetailSubTab.COMMENTS) {
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onToggleSort)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedContent(
                        targetState = sortByHot,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "SortIcon"
                    ) { isHot ->
                        Icon(
                            imageVector = if (isHot) Icons.Outlined.Whatshot else Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = if (isHot) "按热度" else "按时间",
                            tint = if (isHot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = if (sortByHot) "按热度" else "按时间",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sortByHot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            thickness = 0.8.dp
        )
    }
}

@Composable
private fun DetailActionButton(
    icon: ImageVector,
    count: String,
    defaultLabel: String,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tintColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }
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
            tint = tintColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatCount(count, defaultLabel),
            style = if (isSelected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = tintColor
        )
    }
}

@Composable
private fun DetailLikeActionButton(
    isLiked: Boolean,
    likeCount: Int,
    isSelected: Boolean,
    onLikeClick: () -> Unit,
    onTabSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val countStr = likeCount.toString()
    val tintColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }
    val iconTint = if (isLiked) {
        MaterialTheme.colorScheme.primary
    } else {
        tintColor
    }

    Row(
        modifier = modifier
            .height(38.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onTabSelect)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onLikeClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = if (isLiked) "取消点赞" else "点赞",
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = formatCount(countStr, "赞"),
            style = if (isSelected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = tintColor,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

@Composable
private fun RepostItemRow(
    repost: WeiboStatusDto,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPostClick(repost) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val avatar = repost.user?.avatarLarge
            ?: repost.user?.profileImageUrl
            ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

        AsyncImage(
            model = avatar,
            contentDescription = repost.user?.screenName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onUserClick(repost.user?.id) }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = repost.user?.screenName ?: "微博用户",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val verifyType = repost.user.getVerifyType()
                    if (verifyType != WeiboVerifyType.NONE) {
                        UserVerifyBadge(verifyType = verifyType, size = 14.dp)
                    }
                }

                val timeStr = remember(repost.createdAt) {
                    TimeUtils.formatWeiboTime(repost.createdAt)
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val primaryColor = MaterialTheme.colorScheme.primary
            val contentFormatted = remember(repost.textRaw, repost.text, primaryColor) {
                WeiboTextFormatter.formatWeiboContent(
                    rawText = repost.textRaw ?: repost.text,
                    primaryColor = primaryColor,
                    onMentionClick = onMentionClick,
                    onTopicClick = onTopicClick
                )
            }
            Text(
                text = contentFormatted.annotatedString,
                inlineContent = contentFormatted.inlineContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AttitudeItemRow(
    attitude: WeiboAttitudeDto,
    onUserClick: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(attitude.user?.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatar = attitude.user?.avatarLarge
            ?: attitude.user?.profileImageUrl
            ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

        AsyncImage(
            model = avatar,
            contentDescription = attitude.user?.screenName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = attitude.user?.screenName ?: "微博用户",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val verifyType = attitude.user.getVerifyType()
                if (verifyType != WeiboVerifyType.NONE) {
                    UserVerifyBadge(verifyType = verifyType, size = 14.dp)
                }
            }

            val subtitle = remember(attitude.createdAt, attitude.source) {
                val time = TimeUtils.formatWeiboTime(attitude.createdAt)
                val src = attitude.source?.let { " · 来自 $it" } ?: ""
                time + src
            }
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.ThumbUp,
            contentDescription = "赞",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp)
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
