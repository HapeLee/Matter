package org.kazusa.matter.ui.profile

import androidx.activity.compose.BackHandler
import org.kazusa.matter.data.model.VideoStreamInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import org.kazusa.matter.ui.components.FollowButton
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.MatterChip
import org.kazusa.matter.ui.components.MatterMediaBadge
import org.kazusa.matter.ui.components.formatVideoDurationLabel
import org.kazusa.matter.ui.components.MatterTag
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleCapsule
import org.kazusa.matter.ui.components.TopBarTokens
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import org.kazusa.matter.ui.theme.MatterShapeTokens
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kazusa.matter.data.cache.UserAvatarCache
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.ui.components.BloggerSuffixTag
import org.kazusa.matter.ui.components.UserVerifyBadge
import org.kazusa.matter.ui.components.UserVipBadge
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.components.WeiboVerifyType
import org.kazusa.matter.ui.components.getVerifyType
import org.kazusa.matter.ui.effect.liquid.LiquidGlassFloatingMenu
import org.kazusa.matter.ui.components.DEFAULT_AVATAR_URL
import org.kazusa.matter.ui.components.rememberSharedAvatarRequest
import org.kazusa.matter.ui.navigation.formatAvatarSharedKey
import org.kazusa.matter.ui.navigation.weiboAvatarBadgeSharedElement
import org.kazusa.matter.ui.navigation.weiboAvatarSharedElement
import org.kazusa.matter.core.util.WeiboTextFormatter
import coil3.compose.AsyncImage
import org.kazusa.matter.ui.components.TopBarNavigationButton
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    uid: String,
    initialScreenName: String = "",
    /** 当前登录用户 uid，用于判断是否为本人主页（本人不展示关注按钮） */
    currentUid: String = "",
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 数据状态由 NavEntry 级 ViewModel 持有：rememberViewModelStoreNavEntryDecorator 保证
    // 「主页 → 微博正文 → 返回」这一程里 ViewModel 不被销毁，返回时列表首帧即有内容，
    // 滚动位置也才有落点可还原（列表为空时 LazyListState 会把下标压回 0）。
    val viewModel: UserProfileViewModel = koinViewModel { parametersOf(uid) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 页面状态按 tab 拆开：这里只做拍平，渲染代码保持原来的命名
    val header = uiState.header
    val userProfile = header.user
    val profileDetail = header.detail
    val isDetailLoading = header.isDetailLoading
    val detailLoadFailed = header.detailLoadFailed

    val postsState = uiState.posts
    val posts = postsState.items
    val totalCount = postsState.totalCount
    val currentPage = postsState.page
    val hasMore = postsState.hasMore
    val isInitialLoading = postsState.isInitialLoading
    val isLoadingMore = postsState.isLoadingMore
    val errorMessage = postsState.error
    val isRefreshing = uiState.isRefreshing
    val selectedTab = uiState.selectedTab

    val albumState = uiState.album
    val albums = albumState.items
    val isAlbumsLoading = albumState.isLoading
    val albumsError = albumState.error
    val openedAlbum = albumState.opened
    val albumPhotos = albumState.photos
    val albumPhotosHasMore = albumState.photosHasMore
    val isAlbumPhotosLoading = albumState.isPhotosLoading
    val albumPhotosError = albumState.photosError

    val videoState = uiState.video
    val videos = videoState.items
    val isVideosLoading = videoState.isLoading
    val videosHasMore = videoState.hasMore
    val videosError = videoState.error

    val articleState = uiState.article
    val articles = articleState.items
    val isArticlesLoading = articleState.isLoading
    val articlesHasMore = articleState.hasMore
    val articlesError = articleState.error

    // 只在本页生命周期内有效的瞬时 UI 状态（弹窗、资料弹层）
    var showDetailSheet by remember { mutableStateOf(false) }
    var showUnfollowDialog by remember(uid) { mutableStateOf(false) }
    val isSelfProfile = currentUid.isNotBlank() && currentUid == uid

    // 关注状态来自数据层的共享关注仓库：列表页点过的结果在这里同样生效
    val followState = uiState.follow
    val isFollowing = followState?.following == true
    val isFollowUpdating = followState?.updating == true
    val followActionError = uiState.followActionError


    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val pullRefreshState = rememberPullToRefreshState()
    // 数据在 ViewModel 里，返回时列表首帧就是满的，LazyListState 自带的下标+偏移恢复即可生效
    val listState = rememberSaveable(uid, saver = LazyListState.Saver) { LazyListState() }
    // 设备能力探测结果恒定，记忆化以避免每次重组重复探测（内部可能编译 RuntimeShader）。
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val backdrop = if (runtimeShaderSupported) {
        rememberLayerBackdrop()
    } else null

    // 详细资料失败时用基础 userInfo 里的所在地/性别兜底
    val displayedDetail = remember(profileDetail, userProfile) {
        (profileDetail ?: UserProfileDetail()).withFallback(userProfile)
    }

    // 微博和文章 tab 共用同一套卡片渲染
    val renderPostCard: @Composable (WeiboStatusDto) -> Unit = { post ->
        WeiboPostCard(
            post = post,
            // 主页顶部的大头像才是本次转场的共享元素目标，列表卡片头像不参与，
            // 否则同一 uid 会出现多个共享元素、多张卡片一起飞。
            enableAvatarSharedTransition = false,
            onUserClick = remember(uid, onUserClick) {
                { clickedUid: Long? ->
                    if (clickedUid != null && clickedUid.toString() != uid) {
                        onUserClick(clickedUid)
                    }
                }
            },
            onPostClick = onPostClick,
            onImageClick = onImageClick,
            onVideoClick = onVideoClick,
            onMentionClick = onMentionClick,
            onTopicClick = onTopicClick,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp)
        )
    }

    BackHandler(enabled = openedAlbum != null) {
        viewModel.closeAlbum()
    }

    // 首次进入才拉数据；从正文页返回时 ViewModel 还在，不会重复请求也不会回顶
    LaunchedEffect(uid) {
        viewModel.loadIfNeeded()
    }

    // 加载更多失败等一次性提示
    LaunchedEffect(uiState.message) {
        val text = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            onRefresh = { viewModel.refreshCurrentTab() },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                // 注意：首次加载刻意不再整页占位。
                // 顶栏博主信息（含大头像）是「头像 → 主页」共享元素的落点，
                // 若首帧被整页 Loading 顶掉，落点不存在，动画会直接断掉、退化为硬切。
                // 现在首帧就渲染带占位资料的顶栏，加载态只出现在下方的动态区域。
                errorMessage != null && posts.isEmpty() && userProfile == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MatterCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "博主主页加载失败",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                SelectionContainer {
                                    Text(
                                        text = errorMessage ?: "网络连接异常",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(errorMessage ?: "未知错误"))
                                            Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("复制错误详情")
                                    }
                                    Button(
                                        onClick = { viewModel.load(page = 1) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("点击重试")
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        // 右下角留出玻璃悬浮按钮的高度，避免遮住最后一条内容
                        contentPadding = PaddingValues(bottom = 96.dp + navBarBottom),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 沉浸式顶部博主主栏（封面、大头像与资料）
                        item {
                            ImmersiveProfileHeader(
                                uid = uid,
                                userProfile = userProfile,
                                initialScreenName = initialScreenName,
                                onCopy = { label, text ->
                                    clipboardManager.setText(AnnotatedString(text))
                                    Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
                                },
                                showFollowButton = userProfile != null && !isSelfProfile,
                                isFollowing = isFollowing,
                                isMutual = userProfile?.followMe == true,
                                isFollowUpdating = isFollowUpdating,
                                onFollowClick = {
                                    if (isFollowing) {
                                        showUnfollowDialog = true
                                    } else {
                                        viewModel.follow()
                                    }
                                }
                            )
                        }

                        if (selectedTab == ProfileTab.POSTS) {
                        // 动态数量标题栏
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val countText = totalCount?.let { " ($it)" } ?: if (posts.isNotEmpty()) " (${posts.size})" else ""
                                Text(
                                    text = "全部动态$countText",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 微博动态列表
                        // 首屏加载态只覆盖「动态区域」：顶栏博主信息始终在位，
                        // 这样共享元素动画可以立刻开始，不会先闪一屏 Loading 再跳出来
                        when {
                        isInitialLoading && posts.isEmpty() -> item {
                            ProfileTabLoading("正在加载动态…")
                        }
                        posts.isEmpty() -> {
                            item {
                                MatterCard(
                                    shape = MatterShapeTokens.listItem,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                        .padding(horizontal = 16.dp, vertical = 20.dp)
                                 ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = if (errorMessage != null) "未能加载微博动态" else "暂无微博动态",
                                            style = MaterialTheme.typography.titleMediumEmphasized,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (errorMessage != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            SelectionContainer {
                                                Text(
                                                    text = errorMessage!!,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(errorMessage!!))
                                                        Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("复制错误信息")
                                                }
                                                Button(
                                                    onClick = { viewModel.load(page = 1) }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("重试")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            items(posts, key = { it.listKey }, contentType = { "post" }) { post ->
                                renderPostCard(post)
                            }

                            // 底部加载更多
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        isLoadingMore -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "正在加载更多动态...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        hasMore -> {
                                            OutlinedButton(
                                                onClick = { viewModel.load(page = currentPage + 1) },
                                                shape = RoundedCornerShape(20.dp)
                                            ) {
                                                Text("加载更多动态")
                                            }
                                        }
                                        else -> {
                                            Text(
                                                text = "已加载全部动态",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        } // 动态区域 when
                        } else if (selectedTab == ProfileTab.ALBUM) {
                            val album = openedAlbum
                            if (album == null) {
                                when {
                                    isAlbumsLoading && albums.isEmpty() -> item {
                                        ProfileTabLoading("正在加载相册…")
                                    }

                                    albumsError != null && albums.isEmpty() -> item {
                                        ProfileTabError(
                                            message = albumsError!!,
                                            onRetry = { viewModel.loadAlbums(force = true) }
                                        )
                                    }

                                    albums.isEmpty() -> item {
                                        ProfileTabEmpty("暂无公开相册", "该博主还没有创建相册")
                                    }

                                    else -> {
                                        item {
                                            ProfileSectionTitle("相册 (${albums.size})")
                                        }
                                        albums.chunked(2).forEach { row ->
                                            item(key = "album-${row.first().containerId}") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                                        .padding(horizontal = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    row.forEach { albumItem ->
                                                        AlbumCoverCard(
                                                            album = albumItem,
                                                            onClick = { viewModel.openAlbum(albumItem) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    if (row.size == 1) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.closeAlbum() },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "返回相册列表",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = album.title,
                                                style = MaterialTheme.typography.titleMediumEmphasized,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            album.photoCount?.let { count ->
                                                Text(
                                                    text = "$count 张照片",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                when {
                                    isAlbumPhotosLoading && albumPhotos.isEmpty() -> item {
                                        ProfileTabLoading("正在加载照片…")
                                    }

                                    albumPhotosError != null && albumPhotos.isEmpty() -> item {
                                        ProfileTabError(
                                            message = albumPhotosError!!,
                                            onRetry = { viewModel.loadAlbumPhotos(reset = true) }
                                        )
                                    }

                                    albumPhotos.isEmpty() -> item {
                                        ProfileTabEmpty("相册为空", null)
                                    }

                                    else -> {
                                        albumPhotos.chunked(3).forEachIndexed { rowIndex, row ->
                                            item(key = "album-photo-$rowIndex") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                                        .padding(horizontal = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    row.forEachIndexed { columnIndex, url ->
                                                        val index = rowIndex * 3 + columnIndex
                                                        AlbumPhotoCell(
                                                            url = url,
                                                            onClick = { onImageClick(albumPhotos, index) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    repeat(3 - row.size) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                        item {
                                            ProfileLoadMore(
                                                isLoading = isAlbumPhotosLoading,
                                                hasMore = albumPhotosHasMore,
                                                onLoadMore = { viewModel.loadAlbumPhotos() },
                                                loadingText = "正在加载更多照片…",
                                                idleText = "已加载全部照片"
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (selectedTab == ProfileTab.VIDEO) {
                            when {
                                isVideosLoading && videos.isEmpty() -> item {
                                    ProfileTabLoading("正在加载视频…")
                                }

                                videosError != null && videos.isEmpty() -> item {
                                    ProfileTabError(
                                        message = videosError!!,
                                        onRetry = { viewModel.loadVideos() }
                                    )
                                }

                                videos.isEmpty() -> item {
                                    ProfileTabEmpty("暂无公开视频", "该博主还没有发布视频")
                                }

                                else -> {
                                    item {
                                        ProfileSectionTitle("视频 (${videos.size})")
                                    }
                                    videos.chunked(2).forEachIndexed { rowIndex, row ->
                                        item(key = "video-$rowIndex") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                                    .padding(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                row.forEach { videoPost ->
                                                    VideoGridCard(
                                                        post = videoPost,
                                                        onClick = {
                                                            val pageInfo = videoPost.pageInfo
                                                            val mediaInfo = pageInfo?.mediaInfo
                                                            val videoUrl = mediaInfo?.bestVideoUrl
                                                            if (videoUrl != null) {
                                                                val title = mediaInfo.videoTitle?.trim()?.ifBlank { null }
                                                                    ?: mediaInfo.kolTitle?.trim()?.ifBlank { null }
                                                                    ?: pageInfo.pageTitle?.trim()?.ifBlank { null }
                                                                    ?: WeiboTextFormatter.formatHtmlText(videoPost.text).take(40)
                                                                onVideoClick(
                                                                    videoUrl,
                                                                    title,
                                                                    mediaInfo.availableStreams,
                                                                    pageInfo.pagePic.orEmpty(),
                                                                    videoPost.idStr ?: videoPost.mid.orEmpty()
                                                                )
                                                            } else {
                                                                onPostClick(videoPost)
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                                if (row.size == 1) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                    item {
                                        ProfileLoadMore(
                                            isLoading = isVideosLoading,
                                            hasMore = videosHasMore,
                                            onLoadMore = { viewModel.loadVideos() },
                                            loadingText = "正在加载更多视频…",
                                            idleText = "已加载全部视频"
                                        )
                                    }
                                }
                            }
                        } else if (selectedTab == ProfileTab.ARTICLE) {
                            when {
                                isArticlesLoading && articles.isEmpty() -> item {
                                    ProfileTabLoading("正在加载文章…")
                                }

                                articlesError != null && articles.isEmpty() -> item {
                                    ProfileTabError(
                                        message = articlesError!!,
                                        onRetry = { viewModel.loadArticles() }
                                    )
                                }

                                articles.isEmpty() -> item {
                                    ProfileTabEmpty("暂无公开文章", "该博主还没有发布文章")
                                }

                                else -> {
                                    item {
                                        ProfileSectionTitle("文章 (${articles.size})")
                                    }
                                    items(articles, key = { it.listKey }, contentType = { "article" }) { article ->
                                        renderPostCard(article)
                                    }
                                    item {
                                        ProfileLoadMore(
                                            isLoading = isArticlesLoading,
                                            hasMore = articlesHasMore,
                                            onLoadMore = { viewModel.loadArticles() },
                                            loadingText = "正在加载更多文章…",
                                            idleText = "已加载全部文章"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右下角玻璃悬浮分栏按钮：展开后选择微博 / 相册 / 视频 / 文章
            if (!isInitialLoading && (userProfile != null || posts.isNotEmpty())) {
                LiquidGlassFloatingMenu(
                    items = ProfileTab.entries.map { it.title },
                    selectedIndex = selectedTab.ordinal,
                    onSelect = { index -> viewModel.selectTab(ProfileTab.entries[index]) },
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 16.dp),
                    icon = {
                        LiquidGlassIcon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "切换内容分栏"
                        )
                    },
                    emptyLabel = "分栏加载中…"
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).zIndex(10f)
            ) {
                // 沉浸式顶栏：透明 TopAppBar + 随滚动淡入的渐变遮罩
                ImmersiveTopBar(
                    listState = listState,
                    animationLabel = "ProfileTopBarScrim",
                    navigationIcon = { scroll ->
                        TopBarNavigationButton(
                            scroll = scroll,
                            backdrop = backdrop,
                            onClick = onBackClick,
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    },
                    title = {},
                    actions = { scroll ->
                        TopBarActionButton(
                            imageVector = Icons.Outlined.Badge,
                            contentDescription = "博主详细资料",
                            scroll = scroll,
                            backdrop = backdrop,
                            onClick = { showDetailSheet = true }
                        )
                    }
                )
            }
        }

        if (showDetailSheet) {
            BloggerDetailSheet(
                uid = uid,
                initialScreenName = initialScreenName,
                user = userProfile,
                detail = displayedDetail,
                isLoading = isDetailLoading,
                loadFailed = detailLoadFailed,
                onRetry = { viewModel.loadProfileDetail() },
                onDismiss = { showDetailSheet = false }
            )
        }

        if (showUnfollowDialog) {
            AlertDialog(
                onDismissRequest = { showUnfollowDialog = false },
                title = { Text("取消关注") },
                text = {
                    val name = userProfile?.screenName?.takeIf { it.isNotBlank() }
                        ?: initialScreenName
                    Text("确定不再关注 @$name 吗？")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUnfollowDialog = false
                            viewModel.unfollow()
                        }
                    ) {
                        Text("取消关注", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnfollowDialog = false }) {
                        Text("再想想")
                    }
                }
            )
        }

        followActionError?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.consumeFollowError() },
                title = { Text("操作失败") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeFollowError() }) {
                        Text("知道了")
                    }
                }
            )
        }
    }
}

/**
 * 沉浸式博主顶栏：
 * - 封面/渐变通栏直通状态栏；
 * - 浮动返回与刷新操作胶囊（毛玻璃微底，轻盈不突兀）；
 * - 80.dp 浮动大头像打破边界，紧凑自然展现昵称、认证、简介与数据统计；
 * - 顶栏与微博正文统一，滚动后浮现胶囊与渐进式模糊。
 */
@Composable
private fun ImmersiveProfileHeader(
    uid: String,
    userProfile: WeiboUserDto?,
    initialScreenName: String,
    onCopy: (label: String, text: String) -> Unit,
    showFollowButton: Boolean,
    isFollowing: Boolean,
    isMutual: Boolean,
    isFollowUpdating: Boolean,
    onFollowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val coverHeight = 180.dp + statusBarTop

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 顶部沉浸封面区（含状态栏安全区和浮动操作图标）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
        ) {
            // 品牌柔和渐变始终铺在最底层：
            // 首帧（资料未返回）就是它，封面图解码完成后在它上面淡入覆盖，
            // 既不出现空洞，也不会「凭空闪出一张图」
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                            )
                        )
                    )
            )

            val cover = userProfile?.coverImagePhone
            var coverLoaded by remember(cover) { mutableStateOf(false) }
            val coverAlpha by animateFloatAsState(
                targetValue = if (coverLoaded) 1f else 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "ProfileCoverFadeIn"
            )
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = "博主封面",
                    contentScale = ContentScale.Crop,
                    onSuccess = { coverLoaded = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(coverAlpha)
                )
            }

            // 底部半透明渐变阴影，使封面过渡到内容区更温润
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            )
                        )
                    )
            )
        }

        // 博主核心信息区（头像上浮重叠，紧凑雅致）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp)
        ) {
            // 首帧优先复用信息流里已经渲染过的头像地址，保证共享元素一开始就有画面
            val cachedAvatar = remember(uid) { UserAvatarCache.get(uid) }
            val avatarUrl = userProfile?.bestAvatar
                ?: cachedAvatar
                ?: DEFAULT_AVATAR_URL
            val avatarSharedKey = remember(uid) { formatAvatarSharedKey(uid) }
            // 与信息流卡片共用同一个缓存键：头像放大过程中不会白屏/闪烁，高清图加载完成后平滑替换
            val avatarRequest = rememberSharedAvatarRequest(avatarUrl, avatarSharedKey)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-36).dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 上浮大头像（80.dp，带精致边框轮廓）
                Box(contentAlignment = Alignment.BottomEnd) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(80.dp)
                    ) {
                        AsyncImage(
                            model = avatarRequest,
                            contentDescription = "博主头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                // 与信息流卡片头像使用同一个稳定 key，进入主页时头像连续放大
                                .weiboAvatarSharedElement(key = avatarSharedKey)
                                .clip(CircleShape)
                        )
                    }

                    // 首帧优先用列表卡片缓存下来的认证类型，角标才能和头像一起参与共享元素；
                    // 资料返回后再以服务端结果为准。
                    val liveVerifyType = userProfile.getVerifyType()
                    val cachedVerifyType = remember(uid) {
                        UserAvatarCache.getVerify(uid)?.let { ordinal ->
                            WeiboVerifyType.values().getOrNull(ordinal)
                        }
                    }
                    val verifyType = if (liveVerifyType != WeiboVerifyType.NONE) {
                        liveVerifyType
                    } else {
                        cachedVerifyType ?: WeiboVerifyType.NONE
                    }
                    // 认证角标随资料一起淡入 + 轻微放大，避免在头像角上硬闪出来
                    ProfileBadgeFadeIn(visible = verifyType != WeiboVerifyType.NONE) {
                        UserVerifyBadge(
                            verifyType = verifyType,
                            // 与列表角标使用完全相同的尺寸：
                            // 共享元素只做位置形变、不做内容缩放，避免首帧图标大小切换
                            size = 18.dp,
                            // 实心垫底替代圆环描边：Verified 是不规则花瓣轮廓，
                            // 只画描边会在花瓣与圆之间的空隙透出封面/头像
                            backgroundColor = MaterialTheme.colorScheme.surface,
                            backgroundPadding = 1.dp,
                            // 与头像共用一次点击选择，角标独立起飞并压在上层
                            modifier = Modifier.weiboAvatarBadgeSharedElement(
                                avatarKey = avatarSharedKey
                            )
                        )
                    }
                }

                // UID 胶囊
                MatterChip(
                    modifier = Modifier.padding(bottom = 6.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    onClick = { onCopy("UID", uid) }
                ) {
                    Text(
                        text = "UID: $uid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制UID",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // 昵称与认证说明
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).dp)
            ) {
                // 首帧（接口未返回）优先用信息流卡片上已经显示过的昵称，避免名字先占位再跳变
                val cachedName = remember(uid) { UserAvatarCache.getName(uid) }
                val displayName = userProfile?.screenName?.ifBlank { null }
                    ?: initialScreenName.ifBlank { null }
                    ?: cachedName
                    ?: "博主 $uid"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // VIP 角标只做透明度渐显：它跟在昵称后面，不需要参与布局动画
                        val vipAlpha by animateFloatAsState(
                            targetValue = if (userProfile != null) 1f else 0f,
                            animationSpec = tween(durationMillis = 240, delayMillis = 120, easing = FastOutSlowInEasing),
                            label = "ProfileVipBadgeFadeIn"
                        )
                        UserVipBadge(user = userProfile, modifier = Modifier.alpha(vipAlpha))
                    }

                    if (showFollowButton) {
                        FollowButton(
                            isFollowing = isFollowing,
                            isMutual = isMutual,
                            isUpdating = isFollowUpdating,
                            onClick = onFollowClick
                        )
                    }
                }

                // 认证说明：资料返回后淡入 + 自上而下展开（头部高度平滑生长）
                val bloggerTitle = userProfile?.verifiedReason?.trim()?.ifBlank { null }
                ProfileHeaderReveal(visible = !bloggerTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    BloggerSuffixTag(text = bloggerTitle.orEmpty())
                }

                // 简介
                val descriptionText = userProfile?.description?.trim()
                ProfileHeaderReveal(visible = !descriptionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            text = descriptionText.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 21.sp
                        )
                    }
                }

                // 统计数据栏：关注、粉丝、微博动态
                ProfileHeaderReveal(visible = userProfile != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    MatterCard(
                        shape = MatterShapeTokens.listItem,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatMetric(
                                label = "关注",
                                count = userProfile?.bestFriendsCount ?: "0",
                                modifier = Modifier.weight(1f)
                            )
                            ProfileStatDivider()
                            StatMetric(
                                label = "粉丝",
                                count = userProfile?.bestFollowersCount ?: "0",
                                modifier = Modifier.weight(1f)
                            )
                            ProfileStatDivider()
                            StatMetric(
                                label = "微博动态",
                                count = userProfile?.bestStatusesCount ?: "0",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
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
 * 角标类小元素的入场：淡入 + 轻微放大。
 *
 * 这里刻意包一层普通 @Composable 而不是直接写 `AnimatedVisibility`：
 * Row / Column 作用域各自都有一个同名的 `AnimatedVisibility` 重载，
 * 头部这种「Column 里套 Row 再套 Box」的嵌套会让带作用域的重载产生歧义。
 */
@Composable
private fun ProfileBadgeFadeIn(
    visible: Boolean,
    delayMillis: Int = 160,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 220, delayMillis = delayMillis)) +
            scaleIn(
                animationSpec = tween(
                    durationMillis = 260,
                    delayMillis = delayMillis,
                    easing = FastOutSlowInEasing
                ),
                initialScale = 0.6f
            ),
        exit = fadeOut(tween(durationMillis = 120))
    ) {
        content()
    }
}

/**
 * 主页头部「资料就绪后才出现」的区块入场：淡入 + 自上而下展开。
 *
 * 为什么不用 `animateContentSize` 包住整个头部：
 * 那会在每个子块自己展开动画之外再套一层尺寸追帧，两层曲线互相追，收尾时会留下空隙；
 * 直接让每个块各自展开，父容器高度自然随子块平滑变化，
 * 下方「全部动态」与列表也会被连续推下去，而不是被整块内容瞬间顶开。
 */
@Composable
private fun ProfileHeaderReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 260, delayMillis = 60, easing = FastOutSlowInEasing)
        ) + expandVertically(
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        ),
        exit = fadeOut(animationSpec = tween(durationMillis = 140)) + shrinkVertically(
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        ),
        modifier = modifier
    ) {
        Column(content = content)
    }
}

@Composable
private fun StatMetric(
    label: String,
    count: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 统计栏分隔线：与「个人中心」保持一致。 */
@Composable
private fun ProfileStatDivider() {
    VerticalDivider(
        modifier = Modifier.height(26.dp),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

/**
 * 博主详细资料 BottomSheet：顶部概览 + PC 端「资料」模块的全部公开字段。
 * 只展示接口真实下发的字段，缺省项不占位。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BloggerDetailSheet(
    uid: String,
    initialScreenName: String,
    user: WeiboUserDto?,
    detail: UserProfileDetail,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            // 博主概览
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(52.dp)
                ) {
                    AsyncImage(
                        model = user?.bestAvatar
                            ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif",
                        contentDescription = "博主头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user?.screenName?.ifBlank { null }
                                ?: initialScreenName.ifBlank { "博主 $uid" },
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        UserVipBadge(user = user)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "UID $uid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            user?.verifiedReason?.trim()?.ifBlank { null }?.let { reason ->
                Spacer(modifier = Modifier.height(10.dp))
                BloggerSuffixTag(text = reason)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.6.dp
            )
            Spacer(modifier = Modifier.height(16.dp))

            when {
                // 已有资料时刷新不闪加载态，避免 Sheet 内容闪烁
                !detail.hasProfileInfo && isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "正在加载博主详细资料…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                !detail.hasProfileInfo -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (loadFailed) "未能获取该博主的更多资料" else "该博主暂无更多公开资料",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新获取")
                        }
                    }
                }

                else -> ProfileDetailContent(detail = detail)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDetailContent(detail: UserProfileDetail) {
    // 除「动态」标签外，其余资料统一为无卡片的图标行：
    // 性别 / 星座 / IP属地 / 所在地 / 阳光信用 / 生日 / 教育 / 职业 / 注册时间
    val rows = buildList {
        detail.genderLabel?.let { add(ProfileInfoRowModel(Icons.Outlined.Person, "性别", it)) }
        detail.constellation?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.Star, "星座", it)) }
        // 接口的「IP属地：」前缀已在解析层剥离，这里使用本地标签 + 纯属地
        detail.ipLocation?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.Place, "IP属地", it)) }
        detail.location?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.LocationCity, "所在地", it)) }
        detail.sunshineCreditLevel?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.WbSunny, "阳光信用", it)) }
        detail.birthday?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.Cake, "生日", it)) }
        detail.school?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.School, "教育", it)) }
        detail.company?.takeIf { it.isNotBlank() }
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.Business, "职业", it)) }
        detail.registeredDate
            ?.let { add(ProfileInfoRowModel(Icons.Outlined.CalendarMonth, "注册于", it)) }
    }

    if (rows.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                ProfileInfoRow(
                    icon = row.icon,
                    label = row.label,
                    value = row.value
                )
            }
        }
    }

    if (detail.labels.isNotEmpty()) {
        if (rows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.6.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = "动态",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            detail.labels.forEach { label ->
                ProfileLabelChip(text = label)
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ProfileLabelChip(text: String) {
    MatterTag(
        text = text,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    )
}

private data class ProfileInfoRowModel(
    val icon: ImageVector,
    val label: String,
    val value: String
)

/** 详细资料请求失败时，用基础 userInfo 中已有的字段兜底。 */
private fun UserProfileDetail.withFallback(user: WeiboUserDto?): UserProfileDetail {
    if (user == null) return this
    return copy(
        location = location ?: user.location?.trim()?.ifBlank { null },
        gender = gender?.trim()?.ifBlank { null } ?: user.gender?.trim()?.ifBlank { null },
        description = description ?: user.description?.trim()?.ifBlank { null },
        registeredAt = registeredAt ?: user.createdAt?.trim()?.ifBlank { null },
        domain = domain ?: user.domain?.trim()?.ifBlank { null }
    )
}

@Composable
private fun ProfileSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ProfileTabLoading(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileTabEmpty(title: String, subtitle: String?) {
    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfileTabError(message: String, onRetry: () -> Unit) {
    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(20.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("重试")
            }
        }
    }
}

@Composable
private fun ProfileLoadMore(
    isLoading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    loadingText: String,
    idleText: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            hasMore -> {
                OutlinedButton(
                    onClick = onLoadMore,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("加载更多")
                }
            }

            else -> {
                Text(
                    text = idleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun AlbumCoverCard(
    album: UserAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (!album.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = album.coverUrl,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                )
            }
            album.photoCount?.let { count ->
                MatterMediaBadge(
                    text = "$count 张",
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AlbumPhotoCell(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun VideoGridCard(
    post: WeiboStatusDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pageInfo = post.pageInfo
    val mediaInfo = pageInfo?.mediaInfo
    val cover = pageInfo?.pagePic
    val duration = formatVideoDurationLabel(mediaInfo?.duration)
    val title = mediaInfo?.videoTitle?.trim()?.ifBlank { null }
        ?: mediaInfo?.kolTitle?.trim()?.ifBlank { null }
        ?: pageInfo?.pageTitle?.trim()?.ifBlank { null }
        ?: WeiboTextFormatter.formatHtmlText(post.text).take(40)

    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
            )
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            )
            duration?.let {
                MatterMediaBadge(
                    text = it,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

