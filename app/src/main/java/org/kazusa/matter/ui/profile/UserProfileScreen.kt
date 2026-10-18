package org.kazusa.matter.ui.profile

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleCapsule
import org.kazusa.matter.ui.components.TopBarTokens
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.ui.components.BloggerSuffixTag
import org.kazusa.matter.ui.components.UserVerifyBadge
import org.kazusa.matter.ui.components.UserVipBadge
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.components.WeiboVerifyType
import org.kazusa.matter.ui.components.getVerifyType
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.kazusa.matter.ui.components.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    uid: String,
    initialScreenName: String = "",
    apiService: WeiboApiService,
    historyRepository: BrowsingHistoryRepository,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var userProfile by remember { mutableStateOf<WeiboUserDto?>(null) }
    var posts by remember { mutableStateOf<List<WeiboStatusDto>>(emptyList()) }
    var totalCount by remember { mutableStateOf<Int?>(null) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    // 设备能力探测结果恒定，记忆化以避免每次重组重复探测（内部可能编译 RuntimeShader）。
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val backdrop = if (runtimeShaderSupported) {
        rememberLayerBackdrop()
    } else null

    fun loadData(page: Int = 1, isRefresh: Boolean = false) {
        if (page == 1) {
            if (isRefresh) isRefreshing = true else isInitialLoading = true
            errorMessage = null
        } else {
            isLoadingMore = true
        }

        coroutineScope.launch {
            if (page == 1) {
                // 并发获取博主资料与动态，大幅缩短加载等待时间
                val timelineDeferred = async { apiService.getUserTimeline(uid, page = 1) }
                val profileDeferred = async { apiService.getUserProfile(uid) }

                val timelineRes = timelineDeferred.await()
                val profileRes = profileDeferred.await()

                if (profileRes is ApiResult.Success) {
                    val p = profileRes.data
                    // 本地浏览历史：同一博主只保留最近一次访问
                    historyRepository.recordUser(p)
                    userProfile = if (userProfile == null) {
                        p
                    } else {
                        userProfile!!.copy(
                            screenName = p.screenName.ifBlank { userProfile!!.screenName },
                            profileImageUrl = p.profileImageUrl ?: userProfile!!.profileImageUrl,
                            avatarLarge = p.avatarLarge ?: userProfile!!.avatarLarge,
                            avatarHd = p.avatarHd ?: userProfile!!.avatarHd,
                            followersCount = p.followersCount.ifBlank { userProfile!!.followersCount },
                            friendsCount = p.friendsCount.ifBlank { userProfile!!.friendsCount },
                            followCount = p.followCount.ifBlank { userProfile!!.followCount },
                            statusesCount = p.statusesCount.ifBlank { userProfile!!.statusesCount },
                            description = p.description ?: userProfile!!.description,
                            verified = p.verified || userProfile!!.verified,
                            verifiedReason = p.verifiedReason ?: userProfile!!.verifiedReason,
                            coverImagePhone = p.coverImagePhone ?: userProfile!!.coverImagePhone
                        )
                    }
                }

                when (timelineRes) {
                    is ApiResult.Success -> {
                        val result = timelineRes.data
                        if (result.user != null) {
                            userProfile = if (userProfile == null) {
                                result.user
                            } else {
                                userProfile!!.copy(
                                    screenName = result.user.screenName.ifBlank { userProfile!!.screenName },
                                    profileImageUrl = result.user.profileImageUrl ?: userProfile!!.profileImageUrl,
                                    avatarLarge = result.user.avatarLarge ?: userProfile!!.avatarLarge,
                                    avatarHd = result.user.avatarHd ?: userProfile!!.avatarHd,
                                    followersCount = result.user.followersCount.ifBlank { userProfile!!.followersCount },
                                    friendsCount = result.user.friendsCount.ifBlank { userProfile!!.friendsCount },
                                    statusesCount = result.user.statusesCount.ifBlank { userProfile!!.statusesCount },
                                    description = result.user.description ?: userProfile!!.description,
                                    verified = result.user.verified || userProfile!!.verified,
                                    verifiedReason = result.user.verifiedReason ?: userProfile!!.verifiedReason
                                )
                            }
                        }

                        posts = result.statuses
                        currentPage = 1
                        hasMore = result.hasMore && result.statuses.isNotEmpty()
                        if (result.total != null && result.total > 0) {
                            totalCount = result.total
                        }
                        errorMessage = null
                    }
                    is ApiResult.Error -> {
                        errorMessage = timelineRes.message
                    }
                    else -> {}
                }
            } else {
                val timelineRes = apiService.getUserTimeline(uid, page = page)
                when (timelineRes) {
                    is ApiResult.Success -> {
                        val result = timelineRes.data
                        posts = posts + result.statuses
                        currentPage = page
                        hasMore = result.hasMore && result.statuses.isNotEmpty()
                        if (result.total != null && result.total > 0) {
                            totalCount = result.total
                        }
                    }
                    is ApiResult.Error -> {
                        Toast.makeText(context, timelineRes.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }

            isInitialLoading = false
            isRefreshing = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(uid) {
        loadData(page = 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            onRefresh = { loadData(page = 1, isRefresh = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isInitialLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "正在加载博主主页...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                errorMessage != null && posts.isEmpty() && userProfile == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            ),
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
                                        onClick = { loadData(page = 1) },
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
                        contentPadding = PaddingValues(bottom = 24.dp + navBarBottom),
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
                                }
                            )
                        }

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
                        if (posts.isEmpty()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ),
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
                                                    onClick = { loadData(page = 1) }
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
                        } else {
                            items(posts, key = { it.listKey }, contentType = { "post" }) { post ->
                                WeiboPostCard(
                                    post = post,
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
                                                onClick = { loadData(page = currentPage + 1) },
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
                    }
                }
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
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            scroll = scroll,
                            backdrop = backdrop,
                            onClick = { loadData(page = 1, isRefresh = true) }
                        )
                    }
                )
            }
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
            val cover = userProfile?.coverImagePhone
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = "博主封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
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
            } else {
                // 优雅的品牌柔和渐变作为默认封面
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
            }
        }

        // 博主核心信息区（头像上浮重叠，紧凑雅致）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp)
        ) {
            val avatarUrl = userProfile?.bestAvatar
                ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

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
                            model = avatarUrl,
                            contentDescription = "博主头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                .clip(CircleShape)
                        )
                    }

                    val verifyType = userProfile.getVerifyType()
                    if (verifyType != WeiboVerifyType.NONE) {
                        UserVerifyBadge(
                            verifyType = verifyType,
                            size = 22.dp,
                            modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                }

                // UID 胶囊
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clickable { onCopy("UID", uid) }
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
            }

            // 昵称与认证说明
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-24).dp)
            ) {
                val displayName = userProfile?.screenName?.ifBlank { null }
                    ?: initialScreenName.ifBlank { "博主 $uid" }

                Row(
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
                    UserVipBadge(user = userProfile)
                }

                val bloggerTitle = userProfile?.verifiedReason?.trim()?.ifBlank { null }
                if (!bloggerTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    BloggerSuffixTag(text = bloggerTitle)
                }

                // 简介
                val descriptionText = userProfile?.description?.trim()
                if (!descriptionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            text = descriptionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 21.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 统计数据栏：关注、粉丝、微博动态
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatMetric(
                        label = "关注",
                        count = userProfile?.bestFriendsCount ?: "0"
                    )
                    StatMetric(
                        label = "粉丝",
                        count = userProfile?.bestFollowersCount ?: "0"
                    )
                    StatMetric(
                        label = "微博动态",
                        count = userProfile?.bestStatusesCount ?: "0"
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
private fun StatMetric(label: String, count: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

