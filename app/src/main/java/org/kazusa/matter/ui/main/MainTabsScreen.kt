package org.kazusa.matter.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.profile.ProfileScreen
import org.kazusa.matter.ui.search.HotSearchBoardState
import org.kazusa.matter.ui.search.SearchScreen
import org.kazusa.matter.ui.search.currentLatLon
import org.kazusa.matter.ui.search.hasCoarseLocationPermission
import org.kazusa.matter.ui.timeline.TimelineScreen
import org.kazusa.matter.ui.timeline.TimelineViewModel

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
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
    Search("发现", Icons.Filled.Explore, Icons.Outlined.Explore),
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
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit = onPostClick,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onSearchSubmit: (String) -> Unit = {},
    /** 打开搜索展示页（空关键词进入，展示搜索记录） */
    onOpenSearch: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    apiService: WeiboApiService = koinInject(),
    authPreferences: AuthPreferences = koinInject(),
    authRepository: AuthRepository = koinInject(),
    themePreferences: ThemePreferences = koinInject(),
    timelineViewModel: TimelineViewModel = koinViewModel(),
    groupViewModel: GroupViewModel = koinViewModel()
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { MainTab.entries.size })
    val coroutineScope = rememberCoroutineScope()

    // 跨导航与切 Tab 记忆主页瀑布流的精准滚动偏移行数与位置
    val timelineListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // 发现页同样由主框架持有滚动状态，才能与动态页共享底栏隐藏逻辑与沉浸式顶栏联动
    val searchListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }



    // 热搜榜状态：发现页列表与悬浮层里的「换榜」按钮共用同一份
    val hotSearchState = remember(apiService, coroutineScope) {
        HotSearchBoardState(apiService, coroutineScope)
    }

    // 切榜后把发现页列表平滑滚回顶部
    val scrollSearchListToTop: () -> Unit = remember(coroutineScope, searchListState) {
        {
            coroutineScope.launch {
                if (searchListState.firstVisibleItemIndex > 12) {
                    searchListState.scrollToItem(10)
                }
                searchListState.animateScrollToItem(0)
            }
        }
    }

    // 「同城」榜要经纬度才有数据（无定位时服务端只回一条「位置信息获取失败」），
    // 因此只在用户主动切到该榜时才申请 COARSE_LOCATION；授权后再读一次设备位置并切榜。
    val context = LocalContext.current
    var pendingRegionBoard by remember { mutableStateOf<HotSearchChannel?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val channel = pendingRegionBoard
        pendingRegionBoard = null
        if (channel != null) {
            coroutineScope.launch {
                // 授权后主动取一次位置（刚授权时系统往往还没有缓存，只查 lastKnown 会拿不到）
                hotSearchState.select(
                    channel,
                    latLon = if (granted) currentLatLon(context) else null
                )
                scrollSearchListToTop()
            }
        }
    }

    // 选榜：同城榜（服务端 filter_type=region）需要经纬度 ——
    // 没授权就先申请，授权结果回调里再切榜；已授权直接带位置切。
    // 悬浮层在调用前会先收起榜单菜单。
    val selectBoard: (HotSearchChannel) -> Unit = { channel ->
        if (channel.containerid.contains(REGION_FILTER) &&
            !hasCoarseLocationPermission(context)
        ) {
            pendingRegionBoard = channel
            locationPermissionLauncher.launch(
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            coroutineScope.launch {
                hotSearchState.select(channel, latLon = currentLatLon(context))
            }
            scrollSearchListToTop()
        }
    }

    // 主框架只关心少量时间线字段；订阅整个 uiState 会导致列表刷新/滚动时整屏重组，
    // 因此用 derivedStateOf 收窄到真正使用的字段。
    val timelineState by timelineViewModel.uiState.collectAsStateWithLifecycle()
    val pendingRestoreIndex by remember { derivedStateOf { timelineState.pendingRestoreIndex } }
    val currentGid by remember { derivedStateOf { timelineState.currentGid } }
    val unreadCount by remember { derivedStateOf { timelineState.unreadCount } }

    // 检测瀑布流是否在向下滚动（向下滚时隐藏底栏以充分利用屏幕，向上滚或在顶部时展示底栏）
    var isBottomBarVisible by remember { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val scrollTracker = remember(scrollThreshold) { BottomBarScrollTracker(scrollThreshold) }
    val currentPendingRestore by rememberUpdatedState(pendingRestoreIndex)
    val bottomBarScrollConnection = remember(pagerState, timelineListState, searchListState, scrollTracker) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // 动态页与发现页都参与「下滑隐藏 / 上滑显示」的沉浸式底栏联动
                    val activeListState = when (pagerState.currentPage) {
                        0 -> if (currentPendingRestore == null) timelineListState else null
                        1 -> searchListState
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
                        SearchScreen(
                            onSearchSubmit = onSearchSubmit,
                            hotSearchState = hotSearchState,
                            listState = searchListState,
                            backdrop = searchBackdrop,
                            bottomBarPadding = bottomBarPadding,
                            modifier = Modifier.fillMaxSize()
                        )
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
                            onLogout = onLogout,
                            backdrop = profileBackdrop,
                            bottomBarPadding = bottomBarPadding,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 悬浮底栏（胶囊 / 换榜按钮 / 榜单菜单）叠在分页内容之上；
            // 没有菜单遮罩时这一层不持有任何手势，点击会照常落到下面的内容区。
            MainBottomBarLayer(
                pagerState = pagerState,
                visible = isBottomBarVisible,
                hotSearchState = hotSearchState,
                unreadCount = unreadCount,
                backdrop = bottomBarBackdrop,
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
                        1 -> coroutineScope.launch {
                            if (searchListState.firstVisibleItemIndex > 12) {
                                searchListState.scrollToItem(10)
                            }
                            searchListState.animateScrollToItem(0)
                        }
                    }
                },
                onBoardSelected = selectBoard
            )
        }
    }
}
