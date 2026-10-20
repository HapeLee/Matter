package org.kazusa.matter.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.UserRepository

/** 「我的微博」页面状态；只读暴露，界面不再持有可变副本。 */
internal data class MyPostsUiState(
    val posts: List<WeiboStatusDto> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
) {
    /** 触底自动加载的前置条件：没有别的请求在跑，且确实还有下一页（失败后由错误拦下）。 */
    val canLoadMore: Boolean
        get() = hasMore && errorMessage == null &&
            !isInitialLoading && !isRefreshing && !isLoadingMore
}

/**
 * 「我的微博」列表状态。
 *
 * 与博主主页一致，挂在本页 NavEntry 的 ViewModelStore 上：点进正文再返回时列表与页码都还在。
 *
 * 分页失败与「没有下一页」被明确区分开：
 * - 请求失败只记录 [MyPostsUiState.errorMessage]，保留 [MyPostsUiState.hasMore] 与待请求页码，界面可以原地重试；
 * - 只有服务端确实返回空页（或已取满 total）才判定到底。
 */
internal class MyPostsViewModel internal constructor(
    private val uid: String,
    private val loadTimeline: suspend (page: Int) -> ApiResult<UserTimelineResult>
) : ViewModel() {

    constructor(uid: String, userRepository: UserRepository) :
        this(uid, { page -> userRepository.profileTimeline(uid, page) })

    val isGuest: Boolean = uid.isBlank() || uid == "guest"

    private val _uiState = MutableStateFlow(
        MyPostsUiState(isInitialLoading = !isGuest, hasMore = !isGuest)
    )
    val uiState: StateFlow<MyPostsUiState> = _uiState.asStateFlow()

    private var nextPage = 1
    private var started = false
    private var requestToken = 0
    private var loadJob: Job? = null

    /** 首次进入时调用；从正文页返回再组合不会重复请求。 */
    fun loadIfNeeded() {
        if (started) return
        started = true
        load(page = 1, refresh = false)
    }

    fun refresh() {
        if (isGuest) return
        load(page = 1, refresh = true)
    }

    fun loadMore() {
        if (isGuest || !_uiState.value.canLoadMore) return
        load(page = nextPage, refresh = false)
    }

    /** 失败后重试：首屏重新走初始加载（保留骨架/转圈），分页失败则重试同一页。 */
    fun retry() {
        if (_uiState.value.posts.isEmpty()) {
            load(page = 1, refresh = false)
        } else {
            load(page = nextPage, refresh = false)
        }
    }

    private fun load(page: Int, refresh: Boolean) {
        if (isGuest) {
            _uiState.update {
                it.copy(
                    isInitialLoading = false,
                    hasMore = false,
                    errorMessage = "登录后才能查看我的微博"
                )
            }
            return
        }
        // 同一时间只允许一个请求：刷新会取代仍在路上的分页
        loadJob?.cancel()
        requestToken += 1
        val token = requestToken
        _uiState.update {
            it.copy(
                isInitialLoading = !refresh && page <= 1,
                isRefreshing = refresh,
                isLoadingMore = !refresh && page > 1,
                errorMessage = null
            )
        }

        loadJob = viewModelScope.launch {
            try {
                when (val result = loadTimeline(page)) {
                    is ApiResult.Success -> {
                        if (token != requestToken) return@launch
                        val pagePosts = result.data.statuses
                        val firstPage = refresh || page <= 1
                        _uiState.update { current ->
                            val oldSize = current.posts.size
                            val merged = if (firstPage) {
                                pagePosts
                            } else {
                                (current.posts + pagePosts).distinctBy { it.listKey }
                            }
                            val total = result.data.total
                            current.copy(
                                posts = merged,
                                hasMore = when {
                                    pagePosts.isEmpty() -> false
                                    total != null -> merged.size < total
                                    firstPage -> true
                                    else -> merged.size > oldSize
                                }
                            )
                        }
                        if (_uiState.value.hasMore) nextPage = page + 1
                    }

                    is ApiResult.Error -> {
                        if (token != requestToken) return@launch
                        // 失败只是「这一次没拉到」，不代表分页结束：保留页码供用户重试
                        _uiState.update {
                            it.copy(errorMessage = result.message.ifBlank { "加载失败" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == requestToken) {
                    _uiState.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false
                        )
                    }
                }
            }
        }
    }
}
