package org.kazusa.matter.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.SearchPageResult
import org.kazusa.matter.data.model.SearchResultItem
import org.kazusa.matter.data.repository.SearchHistoryRepository

/**
 * 一个搜索分栏（服务端下发）。
 *
 * 不可变：[id] 由 containerid 派生，作为该分栏列表状态与滚动位置的稳定标识。
 */
@Immutable
internal data class SearchTab(
    val id: String,
    val label: String,
    /** 服务端给该分栏的 containerid；null = 使用默认「综合」containerid */
    val containerId: String?,
    /** 服务端 `tabs[].showDot`：是否在分栏上显示红点 */
    val showDot: Boolean = false
)

/** 服务端没给分栏时的兜底分栏。 */
internal const val FALLBACK_SEARCH_TAB_ID = "default"

internal fun fallbackSearchTab(): SearchTab =
    SearchTab(id = FALLBACK_SEARCH_TAB_ID, label = "综合", containerId = null)

/**
 * 单个分栏的列表状态（各分栏独立分页 / 刷新 / 加载更多）。
 *
 * 全部字段只读：界面只能读，写入统一经过 ViewModel 的请求流程，
 * 无法绕过请求身份校验直接改分页或 loading。
 */
@Immutable
internal data class SearchTabListState(
    val items: List<SearchResultItem> = emptyList(),
    val page: Int = 1,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val loaded: Boolean = false
) {
    /** 触底自动加载的前置条件：没有别的请求在跑、确有下一页，且没有未处理的错误。 */
    val canLoadMore: Boolean
        get() = loaded && hasMore && errorMessage == null &&
            !isLoading && !isRefreshing && !isLoadingMore
}

/** 搜索结果页状态；只读暴露，界面不再持有可变副本。 */
internal data class SearchResultUiState(
    val tabs: List<SearchTab> = listOf(fallbackSearchTab()),
    val tabStates: Map<String, SearchTabListState> =
        mapOf(FALLBACK_SEARCH_TAB_ID to SearchTabListState()),
    val currentQuery: String = "",
    val selectedIndex: Int = 0,
    /** 分栏集合被服务端结果替换时自增，用于重启「首次加载 / 触底加载」副作用。 */
    val tabsRevision: Int = 0
) {
    val safeIndex: Int
        get() = selectedIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))

    fun stateOf(tabId: String): SearchTabListState = tabStates[tabId] ?: SearchTabListState()
}

/**
 * 搜索结果页的状态。
 *
 * 挂在 `Screen.SearchResult` 这个 NavEntry 的 ViewModelStore 上：
 * 从结果页点进微博正文再返回时，分栏与各分栏已加载的条目都还在，不会重新请求。
 *
 * 请求生命周期规则：
 * - 每个分栏同一时间只允许一个请求，刷新与分页互相取代，不会并发；
 * - 每次请求带 token，关键词切换再叠加 generation：晚返回的旧响应会被丢弃，
 *   无法把旧关键词或旧分页的数据写进当前列表；
 * - 关键词与选中分栏写入 [SavedStateHandle]，进程被杀后可以恢复。
 *
 * 滚动位置属于界面状态，由界面按 [SearchTab.id] 用 saveable 保存，不再放在 ViewModel 里。
 */
internal class SearchResultViewModel internal constructor(
    initialQuery: String,
    private val recordSearch: suspend (keyword: String) -> Unit,
    private val savedStateHandle: SavedStateHandle,
    private val searchPage: suspend (keyword: String, page: Int, containerId: String?) -> ApiResult<SearchPageResult>
) : ViewModel() {

    /** [savedStateHandle] 必须由框架注入（Koin 从 CreationExtras 创建），不要用 `SavedStateHandle()`。 */
    constructor(
        initialQuery: String,
        apiService: WeiboApiService,
        searchHistoryRepository: SearchHistoryRepository,
        savedStateHandle: SavedStateHandle
    ) : this(initialQuery, searchHistoryRepository::record, savedStateHandle, { keyword, page, containerId ->
        apiService.searchWeiboPage(keyword = keyword, page = page, containerId = containerId)
    })

    private val _uiState = MutableStateFlow(
        SearchResultUiState(currentQuery = initialQueryOrSaved(initialQuery))
    )
    val uiState: StateFlow<SearchResultUiState> = _uiState.asStateFlow()

    private val savedTabId: String?
        get() = savedStateHandle[KEY_TAB_ID]

    /** 关键词切换时自增：旧关键词的在途响应即使回来也不再被采纳。 */
    private var queryGeneration = 0

    /** 每个分栏当前的请求 token，用于判定响应是否仍然有效。 */
    private val requestTokens = mutableMapOf<String, Int>()
    private val loadJobs = mutableMapOf<String, Job>()
    private var requestSeq = 0

    fun stateOf(tabId: String): SearchTabListState = _uiState.value.stateOf(tabId)

    fun selectTab(index: Int) {
        val tab = _uiState.value.tabs.getOrNull(index) ?: return
        _uiState.update { it.copy(selectedIndex = index) }
        savedStateHandle[KEY_TAB_ID] = tab.id
    }

    /** 清空搜索词 → 回到本地搜索记录态，同时终止所有在途请求。 */
    fun clearQuery() {
        if (_uiState.value.currentQuery.isEmpty()) return
        _uiState.update { it.copy(currentQuery = "") }
        savedStateHandle[KEY_QUERY] = ""
        resetTabs()
    }

    fun submitSearch(rawKeyword: String) {
        val trimmed = rawKeyword.trim()
        // 空关键词提交 = 回到「搜索记录」态
        if (trimmed.isBlank()) {
            clearQuery()
            return
        }
        // 提交即写入本地搜索记录（同关键词只更新时间）
        viewModelScope.launch { recordSearch(trimmed) }
        val current = _uiState.value
        if (trimmed == current.currentQuery) {
            loadTab(current.safeIndex, pageToLoad = 1, isRefresh = true)
            return
        }
        _uiState.update { it.copy(currentQuery = trimmed) }
        savedStateHandle[KEY_QUERY] = trimmed
        // 新关键词：分栏与旧结果整体作废，旧请求不能再写入任何状态
        resetTabs()
    }

    fun loadTab(index: Int, pageToLoad: Int, isRefresh: Boolean = false) {
        val state = _uiState.value
        val tab = state.tabs.getOrNull(index) ?: return
        val query = state.currentQuery.trim()
        if (query.isEmpty()) return
        val generation = queryGeneration
        val tabId = tab.id

        // 同一分栏同一时间只跑一个请求：刷新会取代仍在路上的分页，反之亦然
        loadJobs.remove(tabId)?.cancel()
        val token = beginRequest(tabId)
        updateTab(tabId) {
            when {
                pageToLoad != 1 -> it.copy(isLoadingMore = true, errorMessage = null)
                isRefresh -> it.copy(isRefreshing = true, errorMessage = null)
                else -> it.copy(isLoading = true, errorMessage = null)
            }
        }

        // containerId == null 说明当前是兜底「综合」栏，首屏响应可能带回服务端分栏
        val mayDiscoverTabs = pageToLoad == 1 && tab.containerId == null
        loadJobs[tabId] = viewModelScope.launch {
            try {
                val result = searchPage(query, pageToLoad, tab.containerId)
                if (!isCurrent(tabId, token, generation)) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        val pageData = result.data
                        if (mayDiscoverTabs && pageData.tabs.isNotEmpty()) {
                            applyServerTabs(pageData, requestTabId = tabId, generation = generation)
                        } else {
                            updateTab(tabId) { tabState ->
                                tabState.copy(
                                    items = if (pageToLoad == 1) {
                                        pageData.items
                                    } else {
                                        (tabState.items + pageData.items).distinctBy { it.stableKey }
                                    },
                                    page = pageToLoad,
                                    hasMore = pageData.items.isNotEmpty()
                                )
                            }
                        }
                    }

                    is ApiResult.Error -> updateTab(tabId) { tabState ->
                        // 失败只表示这一次没拉到，不写成「没有下一页」，界面可以重试。
                        // 已有内容时的刷新失败保持旧列表，不再弹整屏错误。
                        if (tabState.items.isEmpty() || pageToLoad > 1) {
                            tabState.copy(errorMessage = result.message.ifBlank { "加载失败，请重试" })
                        } else {
                            tabState
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (isCurrent(tabId, token, generation)) {
                    loadJobs.remove(tabId)
                    updateTab(tabId) {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            loaded = true
                        )
                    }
                }
            }
        }
    }

    /** 用服务端下发的分栏替换兜底分栏，并把首屏数据种到默认分栏上。 */
    private fun applyServerTabs(pageData: SearchPageResult, requestTabId: String, generation: Int) {
        // 关键词已切换时，旧关键词的分栏不能覆盖当前搜索
        if (generation != queryGeneration) return
        val serverTabs = pageData.tabs.mapIndexed { index, dto ->
            val containerId = dto.containerid?.takeIf { it.isNotBlank() }
            SearchTab(
                id = containerId ?: "tab_$index",
                label = dto.title?.takeIf { it.isNotBlank() } ?: "分栏",
                containerId = containerId,
                showDot = dto.showDot
            )
        }.distinctBy { it.id }
        if (serverTabs.isEmpty()) return

        val defaultIndex = pageData.defaultTabIndex.coerceIn(0, serverTabs.lastIndex)
        val newTabStates = serverTabs.associate { it.id to SearchTabListState() }.toMutableMap()
        newTabStates[serverTabs[defaultIndex].id] = SearchTabListState(
            items = pageData.items,
            page = 1,
            loaded = true,
            hasMore = pageData.items.isNotEmpty()
        )

        // 请求已经落地，失效掉旧 token，避免 finally 再按旧分栏收尾
        loadJobs.remove(requestTabId)
        requestTokens.remove(requestTabId)

        // 进程恢复时优先回到用户上次选中的分栏
        val restoredIndex = serverTabs.indexOfFirst { it.id == savedTabId }
        val newIndex = if (restoredIndex >= 0) restoredIndex else defaultIndex
        _uiState.update {
            it.copy(
                tabs = serverTabs,
                tabStates = newTabStates,
                selectedIndex = newIndex,
                tabsRevision = it.tabsRevision + 1
            )
        }
        savedStateHandle[KEY_TAB_ID] = serverTabs[newIndex].id
    }

    private fun resetTabs() {
        // 让上一轮关键词的所有在途请求立即失效
        queryGeneration += 1
        loadJobs.values.forEach(Job::cancel)
        loadJobs.clear()
        requestTokens.clear()

        _uiState.update {
            it.copy(
                tabs = listOf(fallbackSearchTab()),
                tabStates = mapOf(FALLBACK_SEARCH_TAB_ID to SearchTabListState()),
                selectedIndex = 0,
                tabsRevision = it.tabsRevision + 1
            )
        }
        savedStateHandle[KEY_TAB_ID] = FALLBACK_SEARCH_TAB_ID
    }

    private fun beginRequest(tabId: String): Int {
        val token = ++requestSeq
        requestTokens[tabId] = token
        return token
    }

    /**
     * 区分「没有保存值」和「保存了空字符串」：
     * 前者回落到导航参数，后者表示用户主动清空了搜索（应恢复成搜索记录态，而不是旧关键词）。
     */
    private fun initialQueryOrSaved(initialQuery: String): String =
        if (savedStateHandle.contains(KEY_QUERY)) {
            savedStateHandle.get<String>(KEY_QUERY).orEmpty()
        } else {
            initialQuery
        }

    private fun isCurrent(tabId: String, token: Int, generation: Int): Boolean =
        generation == queryGeneration && requestTokens[tabId] == token

    private fun updateTab(tabId: String, update: (SearchTabListState) -> SearchTabListState) {
        _uiState.update { state ->
            val current = state.tabStates[tabId] ?: SearchTabListState()
            state.copy(tabStates = state.tabStates + (tabId to update(current)))
        }
    }

    private companion object {
        const val KEY_QUERY = "search_query"
        const val KEY_TAB_ID = "search_tab_id"
    }
}
