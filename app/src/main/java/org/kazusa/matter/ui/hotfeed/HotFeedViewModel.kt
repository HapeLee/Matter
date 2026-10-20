package org.kazusa.matter.ui.hotfeed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.DEFAULT_HOT_FEED_CONTAINER_ID
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotFeedPage
import org.kazusa.matter.data.repository.HotFeedRepository
import org.kazusa.matter.data.model.WeiboStatusDto

/**
 * 热门页状态：分类列表 + 当前分类的微博流 + 分页/刷新标志。
 *
 * 游标（since_id / max_id）不参与渲染，留在 ViewModel 内部，避免每翻一页都触发重组。
 */
@Immutable
data class HotFeedUiState(
    val categories: List<HotFeedCategory> = emptyList(),
    val selectedCategory: HotFeedCategory? = null,
    val posts: List<WeiboStatusDto> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    /** 首屏错误：整页错误态 + 重试。 */
    val errorMessage: String? = null,
    /**
     * 分页错误：只影响列表底部。
     *
     * 与「没有下一页」分开：失败时保留 [hasMore] 与游标，用户可以在原地重试；
     * 只有服务端明确返回 `hasMore = false`（或空页）才关闭分页。
     */
    val pageErrorMessage: String? = null
)

/**
 * 「热门」页 ViewModel：进入时拉一次分类，默认选「热门」频道并加载第一页。
 *
 * 分类切换会取消在途请求并清空列表 —— 热门流按分类完全独立，混在一起会串流。
 */
class HotFeedViewModel internal constructor(
    private val getCategories: suspend () -> ApiResult<List<HotFeedCategory>>,
    private val getFeed: suspend (HotFeedCategory, String, String) -> ApiResult<HotFeedPage>
) : ViewModel() {

    constructor(repository: HotFeedRepository) : this(
        getCategories = repository::getCategories,
        getFeed = { category, sinceId, maxId -> repository.getFeed(category, sinceId, maxId) }
    )

    private val _uiState = MutableStateFlow(HotFeedUiState())
    val uiState: StateFlow<HotFeedUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var categoriesJob: Job? = null
    private var nextSinceId: String? = null
    private var nextMaxId: String? = null

    init {
        loadCategories()
    }

    private fun loadCategories() {
        // 分类与流各自持有请求，不能共用 loadJob：
        // 分类成功后会在同一个协程里发起首页请求，共用时首页会把分类请求自己取消掉
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            when (val result = getCategories()) {
                is ApiResult.Success -> {
                    val categories = result.data
                    val selected = categories.firstOrNull {
                        it.containerId == DEFAULT_HOT_FEED_CONTAINER_ID
                    } ?: categories.firstOrNull()
                    if (selected == null) {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "暂无可用热门分类")
                        }
                    } else {
                        _uiState.update {
                            it.copy(categories = categories, selectedCategory = selected)
                        }
                        loadFirstPage(selected, isRefresh = false)
                    }
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.message)
                }

                ApiResult.Loading -> Unit
            }
        }
    }

    /** 切换分类：清空旧流，重新从第一页开始。 */
    fun selectCategory(category: HotFeedCategory) {
        if (category == _uiState.value.selectedCategory) return
        nextSinceId = null
        nextMaxId = null
        _uiState.update {
            it.copy(
                selectedCategory = category,
                posts = emptyList(),
                isLoading = true,
                isRefreshing = false,
                isLoadingMore = false,
                hasMore = true,
                errorMessage = null,
                pageErrorMessage = null
            )
        }
        loadFirstPage(category, isRefresh = false)
    }

    /**
     * 下拉刷新：重新拉当前分类第一页。
     *
     * 这里不清游标：刷新失败时旧列表还在，游标必须一并保留，否则之后 [loadMore] 拿到空游标
     * 会直接返回，列表就再也翻不动了。刷新成功时 [loadFirstPage] 会用服务端下发的新游标整体替换。
     */
    fun refresh() {
        val category = _uiState.value.selectedCategory ?: return
        loadFirstPage(category, isRefresh = true)
    }

    /** 触底加载下一页。 */
    fun loadMore() {
        val state = _uiState.value
        val category = state.selectedCategory ?: return
        val cursor = nextSinceId ?: return
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return
        // 上一次分页失败后不自动重试，避免断网时反复轰炸接口
        if (state.pageErrorMessage != null) return

        // 先置位再发起：同一帧内连续触底 / 重复点击不会用同一个游标把这一页请求两遍
        _uiState.update { it.copy(isLoadingMore = true, pageErrorMessage = null) }
        loadJob = viewModelScope.launch {
            when (val result = getFeed(category, cursor, nextMaxId.orEmpty())) {
                is ApiResult.Success -> {
                    val page = result.data
                    nextSinceId = page.nextSinceId
                    nextMaxId = page.nextMaxId
                    _uiState.update { current ->
                        current.copy(
                            posts = (current.posts + page.posts).distinctBy { it.listKey },
                            isLoadingMore = false,
                            hasMore = page.hasMore,
                            pageErrorMessage = null
                        )
                    }
                }

                is ApiResult.Error -> _uiState.update {
                    // 失败只表示这一页没拉到：保留游标与 hasMore，底部给重试入口
                    it.copy(
                        isLoadingMore = false,
                        pageErrorMessage = result.message.ifBlank { "加载更多失败，请重试" }
                    )
                }

                ApiResult.Loading -> _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /** 分页失败后的原地重试；游标保持不变，重试的仍是失败的那一页。 */
    fun retryLoadMore() {
        if (_uiState.value.pageErrorMessage == null) return
        _uiState.update { it.copy(pageErrorMessage = null) }
        loadMore()
    }

    /** 首屏加载失败后重试（分类列表为空时连分类一起重拉）。 */
    fun retry() {
        val state = _uiState.value
        if (state.categories.isEmpty()) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadCategories()
            return
        }
        val category = state.selectedCategory ?: return
        // 与 refresh 同理：首屏重试失败时旧游标仍要可用
        loadFirstPage(category, isRefresh = false)
    }

    private fun loadFirstPage(category: HotFeedCategory, isRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    // 第一页请求会取代仍在路上的分页，避免旧页追加到刷新后的列表
                    isLoadingMore = false,
                    errorMessage = null,
                    pageErrorMessage = null
                )
            }
            when (val result = getFeed(category, "", "")) {
                is ApiResult.Success -> {
                    val page = result.data
                    nextSinceId = page.nextSinceId
                    nextMaxId = page.nextMaxId
                    _uiState.update {
                        it.copy(
                            posts = page.posts,
                            isLoading = false,
                            isRefreshing = false,
                            hasMore = page.hasMore,
                            errorMessage = null
                        )
                    }
                }

                is ApiResult.Error -> _uiState.update {
                    // 首屏错误由整页错误态 + 重试处理，不把「请求失败」写成「到底了」
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.message
                    )
                }

                ApiResult.Loading -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false)
                }
            }
        }
    }
}
