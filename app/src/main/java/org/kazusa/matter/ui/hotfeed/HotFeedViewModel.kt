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
    val errorMessage: String? = null
)

/**
 * 「热门」页 ViewModel：进入时拉一次分类，默认选「热门」频道并加载第一页。
 *
 * 分类切换会取消在途请求并清空列表 —— 热门流按分类完全独立，混在一起会串流。
 */
class HotFeedViewModel(
    private val repository: HotFeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HotFeedUiState())
    val uiState: StateFlow<HotFeedUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var nextSinceId: String? = null
    private var nextMaxId: String? = null

    init {
        loadCategories()
    }

    private fun loadCategories() {
        loadJob = viewModelScope.launch {
            when (val result = repository.getCategories()) {
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
                errorMessage = null
            )
        }
        loadFirstPage(category, isRefresh = false)
    }

    /** 下拉刷新：重新拉当前分类第一页。 */
    fun refresh() {
        val category = _uiState.value.selectedCategory ?: return
        nextSinceId = null
        nextMaxId = null
        loadFirstPage(category, isRefresh = true)
    }

    /** 触底加载下一页。 */
    fun loadMore() {
        val state = _uiState.value
        val category = state.selectedCategory ?: return
        val cursor = nextSinceId ?: return
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getFeed(category, cursor, nextMaxId.orEmpty())) {
                is ApiResult.Success -> {
                    val page = result.data
                    nextSinceId = page.nextSinceId
                    nextMaxId = page.nextMaxId
                    _uiState.update { current ->
                        current.copy(
                            posts = (current.posts + page.posts).distinctBy { it.listKey },
                            isLoadingMore = false,
                            hasMore = page.hasMore
                        )
                    }
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoadingMore = false, hasMore = false)
                }

                ApiResult.Loading -> _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
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
        nextSinceId = null
        nextMaxId = null
        loadFirstPage(category, isRefresh = false)
    }

    private fun loadFirstPage(category: HotFeedCategory, isRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null
                )
            }
            when (val result = repository.getFeed(category)) {
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
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        hasMore = false,
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
