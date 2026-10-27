package org.kazusa.matter.ui.main

import org.kazusa.matter.data.model.VideoStreamInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Scaffold
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.hotfeed.HotFeedScreen
import org.kazusa.matter.ui.hotfeed.HotFeedViewModel
import org.kazusa.matter.ui.navigation.LocalSharedTransitionSourceActive
import org.kazusa.matter.ui.profile.ProfileScreen
import org.kazusa.matter.ui.search.HotSearchBoardViewModel
import org.kazusa.matter.ui.search.HotSearchScreen
import org.kazusa.matter.ui.search.currentLatLon
import org.kazusa.matter.ui.search.hasCoarseLocationPermission
import org.kazusa.matter.ui.timeline.TimelineScreen
import org.kazusa.matter.ui.timeline.TimelineViewModel

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 服务端「同城」榜的标识（`filter_type=region`）。
 *
 * 只有这个榜必须带 `lat` / `lon` 才返回真实城市榜，因此用它判断要不要先申请定位权限；
 * 这是容器参数层面的判断，不涉及任何榜单文案的硬编码。
 */
private const val REGION_FILTER = "filter_type=region"

internal enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Timeline("动态", Icons.Filled.Home, Icons.Outlined.Home),
    Search("热门", Icons.Filled.Explore, Icons.Outlined.Explore),
    Profile("我的", Icons.Filled.Person, Icons.Outlined.Person)
}

/**
 * 主框架：三个 Tab 的横向分页 + 内容区之上的悬浮底栏层。
 *
 * `Scaffold` 只用来兜底背景色 / insets，**不再使用 `bottomBar` 槽**：槽的高度是写死的，
 * 而展开后的榜单菜单必须从按钮位置上长出去，靠 `requiredSize` 硬撑出槽外的节点虽然画得出来，
 * 却收不到触摸事件。底栏改用 [MainBottomBarLayer] 作为内容区的全屏 overlay 呈现，
 * 内容避让（[bottomBarPadding]）于是改为按同一份几何自行计算。
 */
@Composable
fun MainTabsScreen(
    onNavigateToGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSavedPosts: () -> Unit = {},
    onNavigateToMyPosts: () -> Unit = {},
    onNavigateToFollowing: () -> Unit = {},
    onNavigateToFollowers: () -> Unit = {},
    onNavigateToMessages: () -> Unit = {},
    /** 打开发微博编辑器（悬浮写作按钮） */
    onNavigateToComposer: () -> Unit = {},
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit = onPostClick,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onSearchSubmit: (String) -> Unit = {},
    /** 打开搜索展示页（空关键词进入，展示搜索记录） */
    onOpenSearch: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    authPreferences: AuthPreferences = koinInject(),
    authRepository: AuthRepository = koinInject(),
    themePreferences: ThemePreferences = koinInject(),
    timelineViewModel: TimelineViewModel = koinViewModel(),
    groupViewModel: GroupViewModel = koinViewModel(),
    hotFeedViewModel: HotFeedViewModel = koinViewModel(),
    // 热搜榜的状态由 ViewModel 持有：榜单切换由悬浮底栏右侧的分类菜单触发，与热搜列表共用同一份
    hotSearchViewModel: HotSearchBoardViewModel = koinViewModel()
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { MainTab.entries.size })
    val coroutineScope = rememberCoroutineScope()

    // 跨导航与切 Tab 记忆主页瀑布流的精准滚动偏移行数与位置
    val timelineListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // 热门 Tab 的展示模式：热门流 / 微博热搜（悬浮底栏左侧按钮切换）
    var discoverMode by rememberSaveable { mutableStateOf(DiscoverMode.HOT) }

    // 热门页的列表状态由页面内部持有（每份内容一份，支持交叉淡化），
    // 这里只镜像「当前可见的那一份」，让底栏隐藏逻辑与动态页完全一致（含“到顶立即显示”）
    val discoverListState = remember { mutableStateOf<LazyListState?>(null) }

    // 只把 State 往下传：分类菜单在悬浮层里读取，热门流每加载一页不会带着主框架重组
    val hotFeedState = hotFeedViewModel.uiState.collectAsStateWithLifecycle()

    // 「同城」榜要经纬度才有数据（无定位时服务端只回一条「位置信息获取失败」），
    // 因此只在用户主动切到该榜时才申请 COARSE_LOCATION；授权后再取一次位置并切榜。
    val context = LocalContext.current
    var pendingRegionBoard by remember { mutableStateOf<HotSearchChannel?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val channel = pendingRegionBoard
        pendingRegionBoard = null
        if (channel != null) {
            coroutineScope.launch {
                hotSearchViewModel.select(
                    channel,
                    latLon = if (granted) currentLatLon(context) else null
                )
            }
        }
    }

    // 热搜榜单切换：同城榜需要位置，没授权就先申请，回调里再切
    val selectHotSearchChannel: (HotSearchChannel) -> Unit = { channel ->
        if (channel.containerid.contains(REGION_FILTER) &&
            !hasCoarseLocationPermission(context)
        ) {
            pendingRegionBoard = channel
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            coroutineScope.launch {
                hotSearchViewModel.select(channel, latLon = currentLatLon(context))
            }
        }
    }

    // 左侧悬浮按钮：热搜 ↔ 热门。列表状态由各自页面持有，切换后自然从顶部开始
    val toggleDiscoverMode: () -> Unit = {
        discoverMode = if (discoverMode == DiscoverMode.HOT) {
            DiscoverMode.HOT_SEARCH
        } else {
            DiscoverMode.HOT
        }
    }

    // 主框架只关心少量时间线字段；订阅整个 uiState 会导致列表刷新/滚动时整屏重组，
    // 因此用 derivedStateOf 收窄到真正使用的字段。
    val timelineState by timelineViewModel.uiState.collectAsStateWithLifecycle()
    val pendingRestoreIndex by remember { derivedStateOf { timelineState.pendingRestoreIndex } }
    val currentGid by remember { derivedStateOf { timelineState.currentGid } }
    val unreadCount by remember { derivedStateOf { timelineState.unreadCount } }

    // 发布成功后回到动态页并刷新：新微博在时间线顶部，用户能立刻看到自己的内容。
    // 编辑器盖在本页之上时本页已经离开组合，所以按仓库的只增计数补处理（见 PublishRepository）。
    val publishRepository: PublishRepository = koinInject()
    val publishedCount by publishRepository.publishedCount.collectAsStateWithLifecycle()
    var handledPublishCount by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(publishedCount) {
        if (publishedCount > handledPublishCount) {
            handledPublishCount = publishedCount
            coroutineScope.launch { pagerState.animateScrollToPage(0) }
            timelineViewModel.loadTimeline(isRefresh = true)
        }
    }

    // 检测瀑布流是否在向下滚动（向下滚时隐藏底栏以充分利用屏幕，向上滚或在顶部时展示底栏）
    var isBottomBarVisible by remember { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val scrollTracker = remember(scrollThreshold) { BottomBarScrollTracker(scrollThreshold) }
    val currentPendingRestore by rememberUpdatedState(pendingRestoreIndex)
    val bottomBarScrollConnection = remember(pagerState, timelineListState, scrollTracker) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // 动态页与热门页走同一套规则：能拿到当前列表状态就顺带判断「是否在顶部」，
                    // 到顶立即显示底栏；否则按滚动增量（32dp 阈值）决定显示 / 隐藏
                    val activeListState = when (pagerState.currentPage) {
                        0 -> if (currentPendingRestore == null) timelineListState else null
                        1 -> discoverListState.value
                        else -> null
                    }
                    if (activeListState != null) {
                        if (activeListState.firstVisibleItemIndex == 0 &&
                            activeListState.firstVisibleItemScrollOffset == 0
                        ) {
                            isBottomBarVisible = true
                            scrollTracker.reset()
                        } else {
                            scrollTracker.onScroll(consumed.y)?.let { isBottomBarVisible = it }
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pagerState.currentPage, currentGid) {
        isBottomBarVisible = true
        scrollTracker.reset()
    }

    // Backdrop 分离架构：
    // 1. bottomBarBackdrop 挂载在 HorizontalPager 上，滑动切页时全实时采样下方左右滚动的真实页面内容，杜绝切页背景停滞；
    // 2. 各页面的顶栏拥有独立的 backdrop，专注于模糊采样自身列表，互不争抢 GraphicsLayer。
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val bottomBarBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val timelineBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val searchBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val profileBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null

    // 内容避让：底栏不在 Scaffold 的槽里，这里按悬浮层的同一份几何自行计算
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarPadding = BAR_ROW_HEIGHT + BAR_BOTTOM_PADDING + navigationBarBottom

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(bottomBarScrollConnection)
                    .then(if (bottomBarBackdrop != null) Modifier.layerBackdrop(bottomBarBackdrop) else Modifier)
            ) { page ->
                // 相邻分页会被预组合（beyondViewportPageCount = 1）：只有当前可见页参与
                // 共享元素注册，否则同一条微博在「动态」「热门」各注册一份相同 key，
                // 形变可能从屏幕外那张卡片起飞
                CompositionLocalProvider(
                    LocalSharedTransitionSourceActive provides (pagerState.currentPage == page)
                ) {
                    when (page) {
                        0 -> {
                            TimelineScreen(
                                viewModel = timelineViewModel,
                                groupViewModel = groupViewModel,
                                onUserClick = onUserClick,
                                onPostClick = onPostClick,
                                onCommentClick = onCommentClick,
                                onImageClick = onImageClick,
                                onVideoClick = onVideoClick,
                                onMentionClick = onMentionClick,
                                onTopicClick = onTopicClick,
                                onSearchClick = onOpenSearch,
                                onLogout = onLogout,
                                onNavigateToGroups = onNavigateToGroups,
                                listState = timelineListState,
                                backdrop = timelineBackdrop,
                                bottomBarPadding = bottomBarPadding,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        1 -> {
                            // 热搜 ↔ 热门切换：两个页面都自带列表状态，可以安全地同时组合，做真正的交叉淡化
                            AnimatedContent(
                                targetState = discoverMode,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(240)) togetherWith
                                        fadeOut(animationSpec = tween(160))
                                },
                                label = "DiscoverMode",
                                modifier = Modifier.fillMaxSize()
                            ) { mode ->
                                when (mode) {
                                    DiscoverMode.HOT -> {
                                        HotFeedScreen(
                                            viewModel = hotFeedViewModel,
                                            onUserClick = onUserClick,
                                            onPostClick = onPostClick,
                                            onCommentClick = onCommentClick,
                                            onImageClick = onImageClick,
                                            onVideoClick = onVideoClick,
                                            onMentionClick = onMentionClick,
                                            onTopicClick = onTopicClick,
                                            // 过渡期间只有当前模式参与顶栏模糊采样，避免两层同时写入同一张 backdrop
                                            backdrop = searchBackdrop.takeIf { mode == discoverMode },
                                            bottomBarPadding = bottomBarPadding,
                                            isActive = mode == discoverMode,
                                            onActiveListStateChanged = { state ->
                                                if (discoverListState.value !== state) {
                                                    discoverListState.value = state
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    DiscoverMode.HOT_SEARCH -> {
                                        HotSearchScreen(
                                            hotSearchViewModel = hotSearchViewModel,
                                            onSearchSubmit = onSearchSubmit,
                                            backdrop = searchBackdrop.takeIf { mode == discoverMode },
                                            bottomBarPadding = bottomBarPadding,
                                            isActive = mode == discoverMode,
                                            onActiveListStateChanged = { state ->
                                                if (discoverListState.value !== state) {
                                                    discoverListState.value = state
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                        2 -> {
                            ProfileScreen(
                                authPreferences = authPreferences,
                                authRepository = authRepository,
                                themePreferences = themePreferences,
                                onNavigateToGroups = onNavigateToGroups,
                                onNavigateToSettings = onNavigateToSettings,
                                onNavigateToAppearance = onNavigateToAppearance,
                                onNavigateToHistory = onNavigateToHistory,
                                onNavigateToSavedPosts = onNavigateToSavedPosts,
                                onNavigateToMyPosts = onNavigateToMyPosts,
                                onNavigateToFollowing = onNavigateToFollowing,
                                onNavigateToFollowers = onNavigateToFollowers,
                                onNavigateToMessages = onNavigateToMessages,
                                onLogout = onLogout,
                                backdrop = profileBackdrop,
                                bottomBarPadding = bottomBarPadding,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // 悬浮底栏（胶囊 + 左右两颗按钮）叠在分页内容之上；
            // 这一层不持有任何手势，点击会照常落到下面的内容区。
            MainBottomBarLayer(
                pagerState = pagerState,
                visible = isBottomBarVisible,
                unreadCount = unreadCount,
                backdrop = bottomBarBackdrop,
                discoverMode = discoverMode,
                hotFeedUiState = hotFeedState,
                hotSearchViewModel = hotSearchViewModel,
                onToggleDiscoverMode = toggleDiscoverMode,
                onHotCategorySelected = { category ->
                    hotFeedViewModel.selectCategory(category)
                },
                onHotSearchChannelSelected = selectHotSearchChannel,
                onReselect = { index ->
                    when (index) {
                        0 -> coroutineScope.launch {
                            val isAtTop = timelineListState.firstVisibleItemIndex == 0 &&
                                timelineListState.firstVisibleItemScrollOffset < 10
                            if (isAtTop) {
                                // 处于顶部时，再次点击触发刷新
                                timelineViewModel.loadTimeline(isRefresh = true)
                            } else {
                                // 处于滑动浏览中时，点击一次平滑回到顶部（不刷新）
                                if (timelineListState.firstVisibleItemIndex > 12) {
                                    timelineListState.scrollToItem(10)
                                }
                                timelineListState.animateScrollToItem(0)
                            }
                        }
                        1 -> {
                            // 热门页的列表状态在页面内部（切换会换新列表、从顶部开始），
                            // 再次点击 Tab 直接刷新当前模式的内容
                            if (discoverMode == DiscoverMode.HOT) {
                                hotFeedViewModel.refresh()
                            } else {
                                hotSearchViewModel.refresh()
                            }
                        }
                    }
                }
            )

            // 「发微博」入口：只在动态页、底栏可见时出现，停在底栏右上方。
            // 尺寸与底栏按钮一致，位置复用底栏几何（bottomBarPadding），避免两套避让。
            AnimatedVisibility(
                visible = pagerState.currentPage == 0 && isBottomBarVisible,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.8f, animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.8f, animationSpec = tween(140)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = bottomBarPadding + 12.dp)
            ) {
                LiquidGlassIconButton(
                    onClick = onNavigateToComposer,
                    backdrop = bottomBarBackdrop,
                    size = 52.dp,
                    tintAlpha = 1f
                ) {
                    LiquidGlassIcon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "发微博"
                    )
                }
            }
        }
    }
}

