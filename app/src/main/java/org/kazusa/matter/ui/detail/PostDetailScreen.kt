package org.kazusa.matter.ui.detail

import org.kazusa.matter.data.model.VideoStreamInfo
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.RectangleShape
import org.kazusa.matter.ui.navigation.LocalSharedElementTarget
import org.kazusa.matter.ui.navigation.weiboCardSharedBounds
import org.kazusa.matter.ui.navigation.weiboPostAvatarSharedElement
import org.kazusa.matter.ui.navigation.weiboPostContentSharedBounds
import org.kazusa.matter.ui.navigation.weiboPostNameSharedElement
import org.kazusa.matter.ui.navigation.weiboPostSubtitleSharedElement
import org.kazusa.matter.ui.navigation.weiboVideoSharedBounds
import org.kazusa.matter.ui.navigation.formatAvatarSharedKey
import org.kazusa.matter.ui.navigation.formatVideoSharedKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Verified
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.kazusa.matter.core.util.TimeUtils
import org.kazusa.matter.core.util.WeiboTextFormatter
import org.kazusa.matter.core.util.formatInteractionCount
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kazusa.matter.data.cache.UserAvatarCache
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.AuthorBadgeTag
import org.kazusa.matter.ui.components.BloggerSuffixTag
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImageGrid
import org.kazusa.matter.data.repository.LikeState
import org.kazusa.matter.ui.components.LocalLikeRepository
import org.kazusa.matter.ui.components.LocalPostLikeStates
import org.kazusa.matter.ui.components.MatterSnackbar
import org.kazusa.matter.ui.components.UserAvatarWithBadge
import org.kazusa.matter.ui.components.rememberSharedAvatarRequest
import org.kazusa.matter.ui.components.UserVerifyBadge
import org.kazusa.matter.ui.components.UserVipBadge
import org.kazusa.matter.ui.components.WeiboVerifyType
import org.kazusa.matter.ui.components.getVerifyType
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.material3.LocalContentColor
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.FavoriteButton
import org.kazusa.matter.ui.components.LocalSnackbarHostState
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleCapsule
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.TopBarTokens
import org.kazusa.matter.ui.components.WeiboActionButton
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

import org.kazusa.matter.data.model.WeiboAttitudeDto
import org.kazusa.matter.ui.effect.ContentFadeState
import org.kazusa.matter.ui.effect.contentFade
import org.kazusa.matter.ui.effect.rememberContentFadeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    postId: String,
    scrollToComments: Boolean = false,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 业务状态全部来自 NavEntry 级 ViewModel：请求生命周期、并发与回滚都在里面，
    // 这里只做读取；页面重建不会丢失分页 / 排序 / 点赞等状态。
    val viewModel: PostDetailViewModel = koinViewModel { parametersOf(postId) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 楼中楼状态按评论 id 存在 ViewModel 里；这里收集一次，按 key 取用
    val repliesStates by viewModel.repliesStates.collectAsStateWithLifecycle()

    val post = uiState.post
    val selectedTab = uiState.selectedTab
    val isLoading = uiState.isLoading
    val isRefreshing = uiState.isRefreshing
    val errorMessage = uiState.errorMessage
    val comments = uiState.comments
    // 博主开启了「精选评论」时接口只下发精选过的评论，需要在评论区给出说明
    val commentsApprovalOnly = uiState.commentsApprovalOnly
    val isCommentsLoading = uiState.isCommentsLoading
    // 评论首屏请求是否已结束：未结束前一律铺骨架屏
    val hasLoadedComments = uiState.hasLoadedComments
    // 内容版本（Compose key 用，不参与业务判断）：评论块整体替换 → 淡入一次；
    // 下拉刷新 → 重建评论子树，丢弃旧的展开 / 分页缓存
    val commentsVersion = uiState.contentVersion.comments
    val commentsSubtreeVersion = uiState.contentVersion.commentsSubtree
    val reposts = uiState.reposts
    val isRepostsLoading = uiState.isRepostsLoading
    val attitudes = uiState.attitudes
    val isAttitudesLoading = uiState.isAttitudesLoading
    val sortByHot = uiState.sortByHot // true: 按热度, false: 按时间

    // 点赞状态只有一份：共享仓库（信息流里点过赞时这里直接读到同一结果）
    val likeRepository = LocalLikeRepository.current
    val postLikeStates = LocalPostLikeStates.current
    val likeState = post?.let { target ->
        likeRepository?.postState(target, postLikeStates)
            ?: LikeState(target.isLiked, parseInteractionCount(target.attitudesCount))
    } ?: LikeState(liked = false, count = null)
    val isLiked = likeState.liked
    val likeCount = likeState.count ?: 0

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    // 子页签内容（评论 / 转发 / 赞）共用的淡入透明度，结构整体切换时统一补一次
    val contentFade = rememberContentFadeState()

    val context = LocalContext.current
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val backdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null

    LaunchedEffect(viewModel) { viewModel.load() }

    // 一次性提示（点赞失败 / 刷新失败）由 ViewModel 发出
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // 子页签内容整块替换时淡入一次：切换页签、评论首屏到达、切换排序。
    // 翻页 / 展开楼中楼 / 下拉刷新不在这里面，滚动过程中新进组合的行也不会重新淡入。
    // 归零必须发生在 SideEffect（组合已提交、本帧还没绘制）：放到协程里归零会先按
    // 旧的 alpha=1 画一帧，用户看到的就是评论闪一下。
    SideEffect {
        contentFade.syncKey(selectedTab to commentsVersion)
    }
    LaunchedEffect(selectedTab, commentsVersion) {
        contentFade.animateIn()
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
                    MatterSnackbar(
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
                        text = "正文",
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 加载失败时内容不可滚动，补一个可滚动容器让下拉刷新手势依然生效
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (errorMessage ?: "加载失败") + "\n下拉重试",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                post != null -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface)
                            .animateContentSize(),
                        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = 16.dp)
                    ) {
                        // 专为沉浸式阅读设计的正文排版（大头像、作者大号、正文更大间距）
                        item {
                            // 形变来源可能是「原微博自己的卡片」，也可能是「转发卡片内的原微博区块」，
                            // 后者使用独立的 retweet_card_<id> key，这里按本次导航选中的 key 收尾，
                            // 避免点击转发区域时动画从列表里那张原微博卡片起飞。
                            val selectedContainerKey = LocalSharedElementTarget.current?.containerKey
                            val detailSharedKey = remember(post?.idStr, post?.mid, post?.id, postId, selectedContainerKey) {
                                val postKeyId = post?.idStr?.takeIf { it.isNotBlank() }
                                    ?: post?.mid?.takeIf { it.isNotBlank() }
                                    ?: post?.id?.toString()?.takeIf { it.isNotBlank() }
                                    ?: postId
                                val retweetSectionKey = formatRetweetSectionSharedKey(postKeyId)
                                if (retweetSectionKey.isNotBlank() && selectedContainerKey == retweetSectionKey) {
                                    retweetSectionKey
                                } else {
                                    post?.getCardSharedKey()?.takeIf { it.isNotBlank() }
                                        ?: formatPostDetailSharedKey(postId)
                                }
                            }
                            PostDetailBody(
                                post = post!!,
                                // 本次命中的容器 key：卡片内的头像/昵称/正文都以它为前缀派生自己的共享元素 key，
                                // 保证「卡片 → 正文」与「转发区块 → 正文」两条路径都能对称收尾
                                containerSharedKey = detailSharedKey,
                                isLiked = isLiked,
                                likeCount = likeCount,
                                selectedTab = selectedTab,
                                onTabSelect = { tab ->
                                    viewModel.selectTab(tab)
                                    coroutineScope.launch { listState.animateScrollToItem(1, 0) }
                                },
                                onLikeClick = { viewModel.toggleLike() },
                                sortByHot = sortByHot,
                                onToggleSort = { viewModel.toggleSort() },
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
                                if (reposts.isEmpty()) {
                                    // 转发数为 0 时服务端可能直接返回 ok=0（空内容/风控），
                                    // 这里统一按「没有可显示的内容」展示，并提供手动刷新
                                    item {
                                        EmptyMessage(
                                            message = if (isRepostsLoading) "正在加载…" else "没有可显示的内容",
                                            isLoading = isRepostsLoading,
                                            buttonText = "刷新",
                                            buttonEnabled = !isRepostsLoading,
                                            onButtonClick = { viewModel.loadReposts() },
                                            modifier = Modifier.contentFade(contentFade)
                                        )
                                    }
                                } else {
                                    items(reposts, key = { it.listKey }, contentType = { "repost" }) { repost ->
                                        RepostItemRow(
                                            repost = repost,
                                            onUserClick = onUserClick,
                                            onMentionClick = onMentionClick,
                                            onTopicClick = onTopicClick,
                                            onPostClick = onPostClick,
                                            modifier = Modifier.contentFade(contentFade)
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
                                if (comments.isEmpty()) {
                                    // 首屏没有内容时先铺骨架屏，避免「一个转圈 → 一屏文字」的硬切；
                                    // 已有内容时（排序/刷新中）继续显示旧列表，不退回占位。
                                    // 请求还没结束（含命中缓存先铺正文、评论尚未发起的那段时间）
                                    // 也算加载中，否则会先闪一下「没有可显示的内容」再变骨架屏。
                                    item(key = "comments_placeholder", contentType = "comment_placeholder") {
                                        if (isCommentsLoading || !hasLoadedComments) {
                                            CommentSkeletonList(modifier = Modifier.contentFade(contentFade))
                                        } else {
                                            EmptyMessage(
                                                // 开启精选评论后接口只下发精选内容，空列表不代表加载失败
                                                message = if (commentsApprovalOnly) "博主还没有精选评论" else "没有可显示的内容",
                                                buttonText = "刷新",
                                                onButtonClick = { viewModel.loadComments() },
                                                modifier = Modifier.contentFade(contentFade)
                                            )
                                        }
                                    }
                                } else {
                                    if (commentsApprovalOnly) {
                                        item(key = "comments_featured_header", contentType = "comment_header") {
                                            FeaturedCommentsHeader(modifier = Modifier.contentFade(contentFade))
                                        }
                                    }
                                    val postAuthorId = post?.user?.id
                                    val postAuthorName = post?.user?.screenName
                                    items(
                                        comments,
                                        key = { it.listKey },
                                        contentType = { "comment" }
                                    ) { comment ->
                                        // 刷新后重建评论子树：楼中楼分页已由 ViewModel 清空，
                                        // 这里同时把「展开/收起」的界面状态归零
                                        androidx.compose.runtime.key(commentsSubtreeVersion) {
                                            CommentThread(
                                                comment = comment,
                                                // 楼中楼的分页、游标与加载态都在 ViewModel 里按评论 id 管理
                                                repliesProvider = { comment ->
                                                    repliesStates[viewModel.repliesKeyOf(comment)]
                                                        ?: initialCommentReplies(comment)
                                                },
                                                onLoadReplies = viewModel::loadCommentReplies,
                                                postAuthorId = postAuthorId,
                                                postAuthorName = postAuthorName,
                                                onUserClick = onUserClick,
                                                onMentionClick = onMentionClick,
                                                onTopicClick = onTopicClick,
                                                onImageClick = onImageClick,
                                                approvalCommentsOnly = commentsApprovalOnly,
                                                modifier = Modifier.contentFade(contentFade)
                                            )
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            thickness = 0.6.dp
                                        )
                                    }
                                    if (commentsApprovalOnly) {
                                        // 精选开启时评论条数远少于 total_number，补一句说明避免误以为加载不全
                                        item(key = "comments_featured_footer", contentType = "comment_header") {
                                            FeaturedCommentsFooter(modifier = Modifier.contentFade(contentFade))
                                        }
                                    }
                                }
                            }

                            DetailSubTab.ATTITUDES -> {
                                if (attitudes.isEmpty()) {
                                    item {
                                        EmptyMessage(
                                            message = if (isAttitudesLoading) "正在加载…" else "没有可显示的内容",
                                            isLoading = isAttitudesLoading,
                                            buttonText = "刷新",
                                            buttonEnabled = !isAttitudesLoading,
                                            onButtonClick = { viewModel.loadAttitudes() },
                                            modifier = Modifier.contentFade(contentFade)
                                        )
                                    }
                                } else {
                                    items(attitudes, key = { it.listKey }, contentType = { "attitude" }) { attitude ->
                                        AttitudeItemRow(
                                            attitude = attitude,
                                            onUserClick = onUserClick,
                                            modifier = Modifier.contentFade(contentFade)
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
    /**
     * 本次导航命中的容器共享键（post_card_<id> 或 retweet_card_<id>）。
     * 站内元素（头像 / 昵称 / 正文）的共享元素 key 均由它派生，与来源卡片一一对应。
     */
    containerSharedKey: String,
    isLiked: Boolean,
    likeCount: Int,
    selectedTab: DetailSubTab,
    onTabSelect: (DetailSubTab) -> Unit,
    onLikeClick: () -> Unit,
    sortByHot: Boolean,
    onToggleSort: () -> Unit,
    onUserClick: (Long?) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sharedElementTarget = LocalSharedElementTarget.current
    // 正文页图片形变的来源标识：与信息流卡片区分，避免同一条微博在两处互相抢动画
    val imageOwnerKey = remember(post.idStr, post.mid, post.id) {
        "detail_${post.getCardSharedKey()}"
    }
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
                // 头像共享元素 key：卡片 → 正文 时头像作为卡内元素单独变形（44.dp ← 38.dp）；
                // 正文 → 博主主页 时切换到 uid 维度 key 放大到 74.dp；两端缓存键始终对齐，避免加载闪烁。
                val avatarSharedKey = remember(post.user?.id) { formatAvatarSharedKey(post.user?.id) }
                val avatarSourceOwnerKey = remember(post.idStr, post.mid, post.id) {
                    "detail_${post.getCardSharedKey()}"
                }
                SideEffect { UserAvatarCache.put(post.user?.id, avatar, post.user?.screenName) }
                val avatarRequest = rememberSharedAvatarRequest(avatar, avatarSharedKey)

                AsyncImage(
                    model = avatarRequest,
                    contentDescription = post.user?.screenName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .weiboPostAvatarSharedElement(
                            containerKey = containerSharedKey,
                            avatarSharedKey = avatarSharedKey,
                            avatarOwnerKey = avatarSourceOwnerKey
                        )
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            sharedElementTarget?.selectAvatar(
                                ownerKey = avatarSourceOwnerKey,
                                avatarKey = avatarSharedKey
                            )
                            onUserClick(post.user?.id)
                        }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 昵称行：仅昵称 + M3大V图标（共享元素：承接卡片的 titleSmallEmphasized 放大）
                    Row(
                        modifier = Modifier.weiboPostNameSharedElement(containerKey = containerSharedKey),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weiboPostSubtitleSharedElement(containerKey = containerSharedKey)
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
            // 正文通过共享元素与卡片对齐，过渡期间保持文字清晰。
            SelectionContainer {
                Text(
                    text = contentFormatted.annotatedString,
                    inlineContent = contentFormatted.inlineContent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weiboPostContentSharedBounds(containerKey = containerSharedKey)
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
                    ownerKey = imageOwnerKey,
                    sharedContainerKey = containerSharedKey,
                    livePhotoMap = post.livePhotoVideoMap,
                    onImageClick = onGridImageClick
                )
            }

            // 视频卡片
            val mediaInfo = post.pageInfo?.mediaInfo
            val videoUrl = mediaInfo?.bestVideoUrl
            val streams = mediaInfo?.availableStreams.orEmpty()
            if (videoUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val videoCover = post.pageInfo.pagePic ?: ""
                val videoTitle = post.pageInfo.pageTitle ?: WeiboTextFormatter.formatHtmlText(post.textRaw ?: post.text).take(20)
                val videoMid = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                val videoSharedKey = remember(videoMid, videoUrl) { formatVideoSharedKey(videoMid, videoUrl) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .weiboVideoSharedBounds(key = videoSharedKey, shape = RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable {
                            sharedElementTarget?.selectVideo(videoSharedKey)
                            onVideoClick(videoUrl, videoTitle, streams, videoCover, videoMid)
                        },
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
                        ownerKey = "detail_retweet_${retweet.getCardSharedKey()}",
                        sharedContainerKey = containerSharedKey,
                        sharedImagePart = "retweet_image",
                        livePhotoMap = retweet.livePhotoVideoMap,
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
            WeiboActionButton(
                icon = Icons.Outlined.Repeat,
                count = post.repostsCount,
                label = "转发",
                emphasized = selectedTab == DetailSubTab.REPOSTS,
                onClick = { onTabSelect(DetailSubTab.REPOSTS) }
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
                emphasized = selectedTab == DetailSubTab.COMMENTS,
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
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onToggleSort)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (sortByHot) Icons.Outlined.Whatshot else Icons.AutoMirrored.Outlined.Sort,
                        contentDescription = if (sortByHot) "按热度" else "按时间",
                        tint = if (sortByHot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (sortByHot) "按热度" else "按时间",
                        style = MaterialTheme.typography.labelSmall,
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

/**
 * 正文页的赞按钮：整行切到「赞」子页签，只有图标区域执行点赞 / 取消点赞。
 *
 * 与信息流卡片（整块点赞）不同，这里的两个动作不能并成一个，因此用
 * [WeiboActionButton] 的 `onIconClick` 表达图标独立点击区。
 */
@Composable
private fun DetailLikeActionButton(
    isLiked: Boolean,
    likeCount: Int,
    isSelected: Boolean,
    onLikeClick: () -> Unit,
    onTabSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    WeiboActionButton(
        icon = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        count = likeCount.toString(),
        label = "赞",
        onClick = onTabSelect,
        iconTint = if (isLiked) MaterialTheme.colorScheme.primary else null,
        emphasized = isSelected,
        onIconClick = onLikeClick,
        modifier = modifier
    )
}

/**
 * 评论区顶部的「博主精选评论」分组头。
 *
 * 博主开启精选评论后，接口只下发被精选的评论（实测一条 1041 条评论的微博只返回 1 条），
 * 不加说明的话用户会以为评论区加载失败或被截断。
 */
@Composable
private fun FeaturedCommentsHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Verified,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "博主精选评论",
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 精选评论列表的收尾说明。 */
@Composable
private fun FeaturedCommentsFooter(modifier: Modifier = Modifier) {
    Text(
        text = "以上为博主精选的评论",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
}

/**
 * 评论区首屏骨架屏。
 *
 * 首屏评论要等一次网络往返，只放一个居中的转圈会让画面「先空→再满屏文字」地硬切；
 * 这里用三条与 [CommentRow] 同构的灰条占位，配合呼吸式透明度，
 * 让「加载中 → 有数据」的过渡更连续。
 */
@Composable
private fun CommentSkeletonList(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "commentSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "commentSkeletonPulse"
    )
    val firstLines = listOf(0.92f, 0.78f, 0.86f)
    val secondLines = listOf(0.62f, 0.48f, 0.7f)
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(3) { index ->
            CommentSkeletonRow(
                pulse = pulse,
                firstLineFraction = firstLines[index],
                secondLineFraction = secondLines[index]
            )
        }
    }
}

@Composable
private fun CommentSkeletonRow(
    pulse: Float,
    firstLineFraction: Float,
    secondLineFraction: Float
) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // alpha 走绘制阶段，脉冲动画不会引起这三行的重组
            .graphicsLayer { alpha = pulse }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(barColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBar(color = barColor, width = 88.dp, height = 12.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBar(color = barColor, width = 52.dp, height = 9.dp)
            Spacer(modifier = Modifier.height(14.dp))
            SkeletonBar(color = barColor, fraction = firstLineFraction)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBar(color = barColor, fraction = secondLineFraction)
        }
    }
}

@Composable
private fun SkeletonBar(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    fraction: Float = 1f,
    height: Dp = 11.dp
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction))
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color)
    )
}

@Composable
private fun RepostItemRow(
    repost: WeiboStatusDto,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
    onUserClick: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
    /** 点赞状态由 [LikeRepository] 统一提供，行内不再自行维护副本 */
    likeState: LikeState,
    isPostAuthor: Boolean = false,
    isReply: Boolean = false,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onImageClick: (List<String>, Int) -> Unit = { _, _ -> },
    onLikeClick: () -> Unit = {},
    likeEnabled: Boolean = true,
    /** 整行点击（目前只用于提供按压水波纹反馈，可后续接入“回复评论”等动作）。 */
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val replyTarget = comment.replyComment?.user
    val rawReplyText = comment.replyOriginalText?.takeIf { it.isNotBlank() } ?: comment.text
    // 纯图片评论的正文是「图片评论 + 查看图片链接」，配图已经单独渲染，这里去掉冗余文案
    val replyText = if (comment.imageUrls.isEmpty()) {
        rawReplyText
    } else {
        rawReplyText.replace("图片评论", "").trim()
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val likeCountText = likeState.count?.let { formatInteractionCount(it, "") }
        ?: comment.likeCount.takeIf { it.isNotBlank() && it != "0" }.orEmpty()
    val likeTint = if (likeState.liked) {
        primaryColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
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
            // 空 onClick 也保留 clickable：整行按下时有水波纹反馈（内部头像/昵称/点赞等会各自消费点击）
            .clickable(onClick = onClick)
            .padding(horizontal = if (isReply) 10.dp else 16.dp, vertical = if (isReply) 8.dp else 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatar = comment.user?.avatarLarge
                ?: comment.user?.profileImageUrl
                ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
            // 微博头像 URL 带 KID/Expires/ssig 签名，每次接口返回都不同。
            // 直接拿 URL 当模型时，展开/翻页后同一张头像会被当成新图重新加载而闪一下；
            // 改用 uid 维度的稳定缓存键：命中内存缓存，并在加载期间用缓存图占位。
            val avatarCacheKey = remember(comment.user?.id) { formatAvatarSharedKey(comment.user?.id) }
            val avatarRequest = rememberSharedAvatarRequest(avatar, avatarCacheKey)

            AsyncImage(
                model = avatarRequest,
                contentDescription = comment.user?.screenName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (isReply) 32.dp else 38.dp)
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
                            // 嵌套回复降一档排版，避免楼中楼的昵称和主评论一样抢眼
                            style = if (isReply) {
                                MaterialTheme.typography.labelMediumEmphasized
                            } else {
                                MaterialTheme.typography.titleSmallEmphasized
                            },
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
                    Row(
                        modifier = Modifier
                            // 对称内边距让水波纹胶囊左右等宽，否则波纹会在计数文字处被"切掉"一块
                            .clip(CircleShape)
                            .clickable(enabled = likeEnabled && !likeState.pending, onClick = onLikeClick)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (likeState.pending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.dp,
                                color = likeTint
                            )
                        } else {
                            Icon(
                                imageVector = if (likeState.liked) {
                                    Icons.Filled.ThumbUp
                                } else {
                                    Icons.Outlined.ThumbUp
                                },
                                contentDescription = if (likeState.liked) "取消点赞" else "点赞",
                                tint = likeTint,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (likeCountText.isNotEmpty()) {
                            Text(
                                text = likeCountText,
                                style = MaterialTheme.typography.labelSmall,
                                color = likeTint
                            )
                        }
                    }
                }

                // 时间/来源与昵称同属一个 Column：整块（昵称 + 时间）与头像居中对齐
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

        Column(modifier = Modifier.padding(start = if (isReply) 40.dp else 46.dp)) {
            Spacer(modifier = Modifier.height(8.dp))

            if (!replyTarget?.screenName.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "回复 ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "@${replyTarget.screenName}",
                        style = MaterialTheme.typography.bodyMedium,
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

            if (commentFormatted.annotatedString.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = commentFormatted.annotatedString,
                        inlineContent = commentFormatted.inlineContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            val commentImages = comment.imageUrls
            if (commentImages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                ImageGrid(
                    images = commentImages,
                    ownerKey = remember(comment.listKey) { "comment_${comment.listKey}" },
                    onImageClick = { index -> onImageClick(commentImages, index) }
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
