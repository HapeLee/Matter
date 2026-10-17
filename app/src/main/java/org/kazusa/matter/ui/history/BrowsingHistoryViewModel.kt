package org.kazusa.matter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.kazusa.matter.data.local.BrowsingCategory
import org.kazusa.matter.data.local.BrowsingHistoryEntity
import org.kazusa.matter.data.repository.BrowsingHistoryRepository

data class BrowsingHistoryUiState(
    val items: List<BrowsingHistoryEntity> = emptyList(),
    val keyword: String = "",
    val categoryMask: Int = BrowsingCategory.ALL,
    val total: Int = 0,
    val isLoading: Boolean = true
) {
    /** 已加载数量小于总数说明还有下一页 */
    val hasMore: Boolean get() = items.size < total

    val isEmpty: Boolean get() = !isLoading && items.isEmpty()
}

/**
 * 浏览记录页状态。
 *
 * 数据全部来自本地 Room 表，Room 的 Flow 会在表变化时自动重发，
 * 所以删除 / 清空 / 新浏览都不需要手动刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowsingHistoryViewModel(
    private val repository: BrowsingHistoryRepository
) : ViewModel() {

    private val keyword = MutableStateFlow("")
    private val categoryMask = MutableStateFlow(BrowsingCategory.ALL)

    /** 本地查询用「放大 LIMIT」代替 OFFSET 翻页，避免刷新时列表跳动 */
    private val pageSize = MutableStateFlow(BrowsingHistoryRepository.DEFAULT_PAGE_SIZE)

    val uiState: StateFlow<BrowsingHistoryUiState> =
        combine(keyword, categoryMask, pageSize) { k, c, size -> Triple(k, c, size) }
            .flatMapLatest { (k, c, size) ->
                combine(
                    repository.observe(keyword = k, categoryMask = c, limit = size, offset = 0),
                    repository.observeCount(keyword = k, categoryMask = c)
                ) { items, total ->
                    BrowsingHistoryUiState(
                        items = items,
                        keyword = k,
                        categoryMask = c,
                        total = total,
                        isLoading = false
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BrowsingHistoryUiState()
            )

    fun onKeywordChange(value: String) {
        keyword.value = value
    }

    /** [BrowsingCategory.ALL] 表示不按分类过滤 */
    fun onCategoryChange(mask: Int) {
        categoryMask.value = mask
    }

    fun loadMore() {
        if (uiState.value.hasMore) {
            pageSize.value += BrowsingHistoryRepository.DEFAULT_PAGE_SIZE
        }
    }

    fun remove(item: BrowsingHistoryEntity) {
        viewModelScope.launch { repository.delete(listOf(item.id)) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clear() }
    }

    companion object {
        fun provideFactory(repository: BrowsingHistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BrowsingHistoryViewModel(repository) as T
                }
            }
    }
}
