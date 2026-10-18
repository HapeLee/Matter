package org.kazusa.matter.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.SearchPageResult
import org.kazusa.matter.data.model.SearchResultItem
import org.kazusa.matter.data.model.SearchResultKind
import org.kazusa.matter.data.local.SearchHistoryEntity
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.SearchChipGroupCard
import org.kazusa.matter.ui.components.SearchInputField
import org.kazusa.matter.ui.components.SearchTopicCard
import org.kazusa.matter.ui.components.SearchUserCard
import org.kazusa.matter.ui.components.TopBarNavigationButton
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.effect.liquid.LiquidGlassFloatingMenu
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton

/**
 * 一个搜索分栏。
 *
 * 分栏**全部来自服务端**：微博 `container/getIndex`（`page_type=searchall`）响应里的
 * `data.cardlistInfo.cardlist_head_cards[0].channel_list[]`（每项含 `name` / `containerid`），
 * 即「搜索分类」是服务端下发的，客户端不硬编码。解析见
 * [org.kazusa.matter.data.model.WeiboCardlistInfoDto.cardlistHeadCards]。
 *
 * 服务端没有下发分栏时，退化为单个兜底分栏 [fallbackSearchTab]
 * （[containerId] == null，用默认的首屏 containerid 请求）。
 */
private class SearchTab(
    val label: String,
    /** 服务端给该分栏的 containerid；null = 使用默认「综合」containerid */
    val containerId: String?,
    /** 服务端 `tabs[].showDot`：是否在分栏上显示红点 */
    val showDot: Boolean = false
) {
    val state = SearchTabListState()
    val listState = LazyListState()
}

/** 服务端没给分栏时的兜底分栏。 */
private fun fallbackSearchTab(): SearchTab = SearchTab(label = "综合", containerId = null)

/**
 * 单个分栏的列表状态（各分栏独立分页 / 刷新 / 加载更多）。
 */
@Stable
private class SearchTabListState {
    var items by mutableStateOf<List<SearchResultItem>>(emptyList())
    var page by mutableIntStateOf(1)
    var isLoading by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
}

/**
 * 独立的搜索结果页：顶部沉浸式液态玻璃顶栏 + 居中悬浮的液态玻璃分栏条，
 * 内容从分栏条下方穿行（挂 [layerBackdrop] 供玻璃采样）。
 *
 * 首屏用默认「综合」containerid 请求一次；响应若带 `data.tabs`，
 * 就用它替换掉兜底分栏并选中 `tab_default` 指定的那一栏 ——
 * 首屏那份数据正好属于「默认分栏」，直接复用，不额外多打一次请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultScreen(
    query: String,
    title: String = query,
    apiService: WeiboApiService,
    /** 本地搜索记录仓库：搜索词为空时展示记录，提交搜索时写入记录 */
    searchHistoryRepository: SearchHistoryRepository,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String) -> Unit = { _, _ -> },
    onTopicClick: (String) -> Unit = {},
    /** 点词条组里的词条（相关搜索 / 热门话题）→ 用该词条再搜一次 */
    onChipClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tabs = remember { mutableStateListOf(fallbackSearchTab()) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    // 分栏集合被服务端结果替换时自增，用于重启「首次加载 / 触底加载」副作用
    var tabsRevision by remember { mutableIntStateOf(0) }

    var currentQuery by rememberSaveable(query) { mutableStateOf(query) }
    var searchInput by rememberSaveable(query) { mutableStateOf(query) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()

    // 本地搜索记录：搜索词为空时替代结果列表展示
    val searchHistory by searchHistoryRepository
        .observe()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // 搜索记录列表自己的滚动状态：空词时顶栏遮罩跟着它联动
    val historyListState = rememberLazyListState()

    val backdrop = rememberScreenBackdrop()

    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
    val currentTab = tabs[safeIndex]
    val currentState = currentTab.state
    val currentListState = currentTab.listState

    /** 用服务端下发的分栏替换兜底分栏，并把首屏数据种到默认分栏上。 */
    fun applyServerTabs(pageData: SearchPageResult) {
        val serverTabs = pageData.tabs.map { dto ->
            SearchTab(
                label = dto.title?.takeIf { it.isNotBlank() } ?: "分栏",
                containerId = dto.containerid?.takeIf { it.isNotBlank() },
                showDot = dto.showDot
            )
        }
        if (serverTabs.isEmpty()) return

        val defaultIndex = pageData.defaultTabIndex.coerceIn(0, serverTabs.lastIndex)
        serverTabs[defaultIndex].state.apply {
            items = pageData.items
            page = 1
            loaded = true
            hasMore = pageData.items.isNotEmpty()
        }

        tabs.clear()
        tabs.addAll(serverTabs)
        selectedIndex = defaultIndex
        tabsRevision++
    }

    fun loadTab(index: Int, pageToLoad: Int, isRefresh: Boolean = false) {
        val tab = tabs.getOrNull(index) ?: return
        val state = tab.state
        if (pageToLoad == 1) {
            if (isRefresh) state.isRefreshing = true else state.isLoading = true
            state.errorMessage = null
        } else {
            state.isLoadingMore = true
        }
        // containerId == null 说明当前是兜底「综合」栏，首屏响应可能带回服务端分栏
        val mayDiscoverTabs = pageToLoad == 1 && tab.containerId == null

        coroutineScope.launch {
            when (
                val res = apiService.searchWeiboPage(
                    keyword = currentQuery.trim(),
                    page = pageToLoad,
                    containerId = tab.containerId
                )
            ) {
                is ApiResult.Success -> {
                    val pageData = res.data
                    if (mayDiscoverTabs && pageData.tabs.isNotEmpty()) {
                        applyServerTabs(pageData)
                    } else {
                        state.items = if (pageToLoad == 1) {
                            pageData.items
                        } else {
                            state.items + pageData.items
                        }
                        state.page = pageToLoad
                        state.hasMore = pageData.items.isNotEmpty()
                    }
                }
                is ApiResult.Error -> {
                    if (state.items.isEmpty()) state.errorMessage = res.message
                    state.hasMore = false
                }
                is ApiResult.Loading -> Unit
            }
            state.isLoading = false
            state.isRefreshing = false
            state.isLoadingMore = false
            state.loaded = true
        }
    }

    fun submitSearch(newKeyword: String) {
        val trimmed = newKeyword.trim()
        focusManager.clearFocus()
        keyboardController?.hide()
        // 空关键词提交 = 回到「搜索记录」态
        if (trimmed.isBlank()) {
            currentQuery = ""
            return
        }
        // 提交即写入本地搜索记录（同关键词只更新时间）
        coroutineScope.launch { searchHistoryRepository.record(trimmed) }
        if (trimmed == currentQuery) {
            loadTab(safeIndex, pageToLoad = 1, isRefresh = true)
        } else {
            currentQuery = trimmed
            tabs.clear()
            tabs.add(fallbackSearchTab())
            selectedIndex = 0
            tabsRevision++
        }
    }

    // 每个分栏首次选中或搜索词变更时才加载，切换 Tab 不会重复请求
    LaunchedEffect(safeIndex, currentQuery, tabsRevision) {
        if (currentQuery.isBlank()) return@LaunchedEffect
        val tab = tabs[safeIndex]
        if (!tab.state.loaded && !tab.state.isLoading && !tab.state.isRefreshing) {
            loadTab(safeIndex, pageToLoad = 1)
        }
    }

    // 触底加载更多（按当前分栏独立驱动）
    LaunchedEffect(safeIndex, currentQuery, tabsRevision) {
        if (currentQuery.isBlank()) return@LaunchedEffect
        val tab = tabs[safeIndex]
        snapshotFlow { tab.listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = tab.listState.layoutInfo.totalItemsCount
                if (
                    tab.state.loaded && tab.state.hasMore && total > 0 &&
                    lastVisible >= total - 3 &&
                    !tab.state.isLoading && !tab.state.isRefreshing && !tab.state.isLoadingMore
                ) {
                    loadTab(safeIndex, pageToLoad = tab.state.page + 1)
                }
            }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("清空搜索记录？") },
            text = { Text("将删除当前账号在本机的全部搜索记录，且不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        coroutineScope.launch { searchHistoryRepository.clear() }
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                // 空词态展示的是搜索记录列表，顶栏遮罩跟着它的滚动走
                listState = if (currentQuery.isBlank()) historyListState else currentListState,
                animationLabel = "SearchResultTopBarScrim",
                // 返回键走经典导航槽（不再塞进搜索胶囊）：胶囊内不再有额外的图标子树，
                // 滚动内收动画期间没有东西需要跟着每帧重建，也就不会卡顿。
                navigationIcon = { scroll ->
                    TopBarNavigationButton(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = onBackClick
                    )
                },
                title = { scroll ->
                    SearchInputField(
                        query = searchInput,
                        onQueryChange = { text ->
                            searchInput = text
                            // 文本被手动清空 → 退回搜索记录
                            if (text.isBlank() && currentQuery.isNotBlank()) currentQuery = ""
                        },
                        onSearch = { submitSearch(it) },
                        onClear = {
                            searchInput = ""
                            currentQuery = ""
                        },
                        focusRequester = focusRequester,
                        backdrop = backdrop,
                        placeholder = "搜索微博",
                        scrollAlpha = scroll.alpha,
                        // 左右边缘的滚动联动边距统一由 SearchInputField 内部负责，这里只负责撑满标题槽位
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()
        val navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 搜索词为空 → 本地搜索记录（不需要下拉刷新，也不展示分栏筛选）
            if (currentQuery.isBlank()) {
                SearchHistoryPanel(
                    items = searchHistory,
                    listState = historyListState,
                    topPadding = topPadding,
                    bottomPadding = navigationBarsBottom,
                    backdrop = backdrop,
                    onKeywordClick = { keyword ->
                        searchInput = keyword
                        submitSearch(keyword)
                    },
                    onRemove = { keyword ->
                        coroutineScope.launch { searchHistoryRepository.remove(keyword) }
                    },
                    onClearAll = { showClearHistoryDialog = true }
                )
            } else PullToRefreshBox(
                isRefreshing = currentState.isRefreshing,
                state = pullRefreshState,
                onRefresh = { loadTab(safeIndex, pageToLoad = 1, isRefresh = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    currentState.isLoading && currentState.items.isEmpty() -> {
                        CenteredHint { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                    }

                    currentState.errorMessage != null && currentState.items.isEmpty() -> {
                        CenteredHint {
                            SearchHint(
                                iconTint = MaterialTheme.colorScheme.error,
                                message = currentState.errorMessage ?: "加载搜索结果失败",
                                messageColor = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    currentState.items.isEmpty() -> {
                        CenteredHint {
                            SearchHint(
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                message = "未搜索到相关${currentTab.label}结果",
                                messageColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = currentListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                                .background(MaterialTheme.colorScheme.surface),
                            // 内容顶部让出顶栏高度，底部让出悬浮按钮与导航栏间距
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                top = topPadding + 8.dp,
                                end = 12.dp,
                                bottom = navigationBarsBottom + 80.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 按卡片种类分派：微博卡 / 用户卡 / 话题卡 / 词条组
                            items(
                                items = currentState.items,
                                key = { it.stableKey },
                                contentType = { it.kind }
                            ) { item ->
                                when (item.kind) {
                                    SearchResultKind.POST -> item.status?.let { post ->
                                        WeiboPostCard(
                                            post = post,
                                            onUserClick = onUserClick,
                                            onPostClick = onPostClick,
                                            onImageClick = onImageClick,
                                            onVideoClick = onVideoClick,
                                            onMentionClick = onTopicClick,
                                            onTopicClick = onTopicClick
                                        )
                                    }

                                    SearchResultKind.USER -> item.card?.let { card ->
                                        SearchUserCard(
                                            card = card,
                                            onUserClick = onUserClick,
                                            onClick = { onUserClick(card.user?.id) }
                                        )
                                    }

                                    SearchResultKind.TOPIC -> item.card?.let { card ->
                                        SearchTopicCard(
                                            card = card,
                                            onClick = {
                                                // 与正文本的话题点击保持一致：去掉首尾 `#`，由路由再补回
                                                val topic = card.title?.trim()?.trim('#')
                                                if (!topic.isNullOrBlank()) onTopicClick(topic)
                                            }
                                        )
                                    }

                                    SearchResultKind.CHIP_GROUP -> item.chipGroup?.let { group ->
                                        SearchChipGroupCard(
                                            group = group,
                                            // 词条自带 `keyword`（优先取 `scheme` 的 `q` 参数），直接作为新搜索词
                                            onChipClick = { chip -> onChipClick(chip.keyword) }
                                        )
                                    }
                                }
                            }

                            if (currentState.isLoadingMore) {
                                item(key = "load_more", contentType = "loading") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右下角悬浮分栏按钮：点击后原地形变为液态玻璃面板，展示选项列表（带单选对勾）
            // 只在有搜索结果时出现（空词态的搜索记录没有分栏可选）
            if (currentQuery.isNotBlank()) {
                LiquidGlassFloatingMenu(
                    items = tabs.map { it.label },
                    selectedIndex = safeIndex,
                    onSelect = { selectedIndex = it },
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 16.dp),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "切换搜索分栏"
                        )
                    },
                    emptyLabel = "分栏加载中…"
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun SearchHint(
    iconTint: Color,
    message: String,
    messageColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = messageColor
        )
    }
}

/**
 * 搜索词为空时展示的「搜索记录」面板。
 *
 * 列表头一行是标题 + 清空入口；每条记录可点击（用该关键词再搜一次）或单条删除。
 * 记录完全来自本地库（`search_history`），点击记录会重新走一次搜索并顺手把该词提到最前。
 */
@Composable
private fun SearchHistoryPanel(
    items: List<SearchHistoryEntity>,
    listState: LazyListState,
    topPadding: Dp,
    bottomPadding: Dp,
    backdrop: LayerBackdrop?,
    onKeywordClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding),
            contentAlignment = Alignment.Center
        ) {
            SearchHint(
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                message = "还没有搜索记录",
                messageColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = topPadding + 8.dp,
            end = 12.dp,
            bottom = bottomPadding + 20.dp
        )
    ) {
        item(key = "history_header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索记录",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClearAll) {
                    Text(text = "清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        items(items = items, key = { it.id }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onKeywordClick(entry.keyword) }
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry.keyword,
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemove(entry.keyword) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "删除这条搜索记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
