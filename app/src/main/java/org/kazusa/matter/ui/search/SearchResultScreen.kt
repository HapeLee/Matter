package org.kazusa.matter.ui.search

import org.kazusa.matter.ui.theme.smoothRoundedCorner

import org.kazusa.matter.data.model.VideoStreamInfo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import org.kazusa.matter.ui.effect.fadeInOnContentChange
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

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
    /** 本地搜索记录仓库：搜索词为空时展示记录，提交搜索时写入记录 */
    searchHistoryRepository: SearchHistoryRepository,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit = { _, _, _, _, _ -> },
    onTopicClick: (String) -> Unit = {},
    /** 点词条组里的词条（相关搜索 / 热门话题）→ 用该词条再搜一次 */
    onChipClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 分栏与各分栏的数据由 NavEntry 级 ViewModel 持有：点进正文再返回时不会重新请求。
    // 滚动位置属于界面状态，按稳定分栏 id 存在本页的 saveable 里。
    val viewModel: SearchResultViewModel = koinViewModel { parametersOf(query) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabs = uiState.tabs
    val tabsRevision = uiState.tabsRevision
    val currentQuery = uiState.currentQuery

    var searchInput by rememberSaveable(query) { mutableStateOf(query) }
    // 进程恢复后 ViewModel 里的关键词可能比导航参数新（也可能已被用户清空），输入框要跟上
    LaunchedEffect(currentQuery) {
        if (currentQuery != searchInput) searchInput = currentQuery
    }
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
    val historyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val backdrop = rememberScreenBackdrop()

    val safeIndex = uiState.safeIndex
    val currentTab = tabs[safeIndex]
    val currentState = uiState.stateOf(currentTab.id)
    // 分栏是服务端下发的，位置随会话变化，所以滚动位置按「关键词 + 分栏 id」存在 saveable 里
    val tabScrollStates = rememberSaveable(saver = searchTabScrollSaver) {
        mutableStateMapOf<String, LazyListState>()
    }
    val scrollKey = "${currentQuery}##${currentTab.id}"
    val currentListState = remember(scrollKey) {
        tabScrollStates.getOrPut(scrollKey) { LazyListState() }
    }

    fun submitSearch(newKeyword: String) {
        focusManager.clearFocus()
        keyboardController?.hide()
        viewModel.submitSearch(newKeyword)
    }

    // 每个分栏首次选中或搜索词变更时才加载，切换 Tab 不会重复请求
    LaunchedEffect(safeIndex, currentQuery, tabsRevision) {
        if (currentQuery.isBlank()) return@LaunchedEffect
        val state = viewModel.uiState.value.stateOf(tabs[safeIndex].id)
        if (!state.loaded && !state.isLoading && !state.isRefreshing) {
            viewModel.loadTab(safeIndex, pageToLoad = 1)
        }
    }

    // 触底加载更多（按当前分栏独立驱动）
    LaunchedEffect(safeIndex, currentQuery, tabsRevision) {
        if (currentQuery.isBlank()) return@LaunchedEffect
        val tab = tabs[safeIndex]
        snapshotFlow { currentListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val state = viewModel.uiState.value.stateOf(tab.id)
                val total = currentListState.layoutInfo.totalItemsCount
                if (state.canLoadMore && total > 0 && lastVisible >= total - 3) {
                    viewModel.loadTab(safeIndex, pageToLoad = state.page + 1)
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
                            if (text.isBlank() && currentQuery.isNotBlank()) viewModel.clearQuery()
                        },
                        onSearch = { submitSearch(it) },
                        onClear = {
                            searchInput = ""
                            viewModel.clearQuery()
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
                onRefresh = { viewModel.loadTab(safeIndex, pageToLoad = 1, isRefresh = true) },
                modifier = Modifier
                    .fillMaxSize()
                    // 切分栏 / 首次拉到数据时淡入，避免加载态与数据态硬切
                    .fadeInOnContentChange(key = safeIndex to currentState.items.isNotEmpty())
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

                            // 分页失败：明确区分「这一次没拉到」与「没有下一页」，给一次原地重试
                            val pageError = currentState.errorMessage
                            if (pageError != null && !currentState.isLoadingMore) {
                                item(key = "load_error", contentType = "error") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = pageError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        TextButton(
                                            onClick = {
                                                viewModel.loadTab(safeIndex, pageToLoad = currentState.page + 1)
                                            }
                                        ) {
                                            Text("重试")
                                        }
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
                    onSelect = { viewModel.selectTab(it) },
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 16.dp),
                    icon = {
                        LiquidGlassIcon(
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

/**
 * 各个分栏的滚动位置（首行下标 + 像素偏移）。
 *
 * key 是 `关键词##分栏id`：分栏由服务端下发、数量与顺序会变，用 id 而不是下标做标识，
 * 分栏重排后不会把滚动位置串到别的分栏上；换关键词后旧位置自然失效。
 */
private val searchTabScrollSaver = listSaver<SnapshotStateMap<String, LazyListState>, Any>(
    save = { states ->
        states.entries.flatMap { (key, listState) ->
            listOf(key, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    },
    restore = { values ->
        mutableStateMapOf<String, LazyListState>().apply {
            var cursor = 0
            while (cursor + 2 < values.size) {
                val key = values[cursor] as? String
                val firstIndex = values[cursor + 1] as? Int
                val offset = values[cursor + 2] as? Int
                if (key != null && firstIndex != null && offset != null) {
                    put(key, LazyListState(firstIndex, offset))
                }
                cursor += 3
            }
        }
    }
)

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
                    .clip(smoothRoundedCorner(12.dp))
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
