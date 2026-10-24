package org.kazusa.matter.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.TimelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TimelineViewModel(
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    /**
     * 当前滚动位置（行索引 + 像素偏移）。
     *
     * 仅用于持久化缓存的读写，**不进入 [uiState]**：滑动停止会高频更新该值，
     * 若放进可观察状态，会让时间线与主框架每次滑动结束都重组。
     */
    private data class ScrollSnapshot(val index: Int = 0, val offset: Int = 0)

    private var scrollSnapshot = ScrollSnapshot()

    @Volatile
    private var lastRequestedPage = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * setGroup 的缓存加载任务。与 loadJob 分开管理：
     * 切组时既要掐掉上一个分组的磁盘缓存加载，也要掐掉网络翻页，且两者互不误伤。
     */
    private var groupSwitchJob: kotlinx.coroutines.Job? = null

    init {
        // 恢复上一次记住的分组选择，并在读取完成后统一加载本地持久化缓存
        viewModelScope.launch {
            val (savedGid, savedName) = authRepository.lastGroupFlow.first()
            val effectiveGid = if (!savedGid.isNullOrBlank() && savedGid != "all") {
                if (savedGid == "special") {
                    authRepository.saveSelectedGroup(null, null)
                    null
                } else {
                    savedGid
                }
            } else null

            val effectiveName = if (effectiveGid != null) savedName ?: "分组微博" else "全部关注"

            _uiState.update {
                // 恢复期间用户可能已手动切组：不再用持久化的旧选择覆盖
                if (it.currentGid != null) it
                else it.copy(currentGid = effectiveGid, currentGroupName = effectiveName)
            }

            // 读取本地持久化缓存（微博列表与精准视口位置）
            val cachedData = timelineRepository.loadCache(effectiveGid)
            if (cachedData != null && cachedData.posts.isNotEmpty()) {
                val savedPos = timelineRepository.getScrollPosition(effectiveGid)
                val posts = cachedData.posts

                // 优先根据具体 postId 寻找在列表中的确切位置
                val targetIndex = resolveTimelineRestoreIndex(
                    posts, cachedData.lastReadPostId, savedPos.postId, savedPos.index
                )

                _uiState.update { state ->
                    // 缓存读取期间用户可能已切组：旧分组的缓存不允许写进新分组状态
                    if (state.currentGid != effectiveGid) state
                    else state.copy(
                        posts = posts,
                        page = cachedData.page,
                        hasMore = cachedData.hasMore,
                        lastReadPostId = cachedData.lastReadPostId,
                        pendingRestoreIndex = targetIndex,
                        pendingRestoreOffset = savedPos.offset,
                        isLoading = false,
                        isRefreshing = false
                    )
                }
                if (_uiState.value.currentGid == effectiveGid) {
                    scrollSnapshot = ScrollSnapshot(targetIndex, savedPos.offset)
                }
                // 用户要求："重新进入应用后保持最后浏览记忆位置，不需要主动更新微博，只在用户手动更新时更新"
                // 因此有缓存时不主动触发 loadTimeline 网络请求！
            } else {
                // 仅在首次安装或无任何本地缓存时，才发起首次网络加载
                loadTimeline(isRefresh = true)
            }
        }
    }

    fun setGroup(gid: String?, groupName: String?) {
        val name = groupName ?: "全部关注"
        val effectiveGid = if (gid == "all") null else gid
        if (_uiState.value.currentGid != effectiveGid) {
            lastRequestedPage = 0
            // 切组必须掐掉在途请求：否则上一个分组的翻页响应回来时会被写进新分组
            // （posts 混料、page 错位，并把这个错误状态盖进新分组的磁盘缓存）。
            loadJob?.cancel()
            // 上一次切组的磁盘缓存加载同样要掐掉：连切两个分组时，
            // 旧分组的缓存会晚到并写进新分组状态。
            groupSwitchJob?.cancel()
            _uiState.update {
                it.copy(
                    currentGid = effectiveGid,
                    currentGroupName = name,
                    posts = emptyList(),
                    isLoading = true,
                    page = 1,
                    hasMore = true,
                    lastReadPostId = null,
                    pendingRestoreIndex = 0,
                    pendingRestoreOffset = 0
                )
            }
            scrollSnapshot = ScrollSnapshot()
            groupSwitchJob = viewModelScope.launch {
                authRepository.saveSelectedGroup(effectiveGid, name)
                val cached = timelineRepository.loadCache(effectiveGid)
                if (cached != null && cached.posts.isNotEmpty()) {
                    val savedPos = timelineRepository.getScrollPosition(effectiveGid)
                    val posts = cached.posts

                    val targetIndex = resolveTimelineRestoreIndex(
                        posts, cached.lastReadPostId, savedPos.postId, savedPos.index
                    )

                    _uiState.update { state ->
                        // 双保险：取消之外再校验分组身份，迟到的旧分组缓存不写新分组
                        if (state.currentGid != effectiveGid) state
                        else state.copy(
                            posts = posts,
                            page = cached.page,
                            hasMore = cached.hasMore,
                            lastReadPostId = cached.lastReadPostId,
                            pendingRestoreIndex = targetIndex,
                            pendingRestoreOffset = savedPos.offset,
                            isLoading = false
                        )
                    }
                    if (_uiState.value.currentGid == effectiveGid) {
                        scrollSnapshot = ScrollSnapshot(targetIndex, savedPos.offset)
                    }
                } else {
                    loadTimeline(isRefresh = true)
                }
            }
        }
    }

    fun loadTimeline(isRefresh: Boolean = false) {
        val currentState = _uiState.value
        if (!isRefresh && (currentState.isLoading || currentState.isLoadingMore || currentState.isRefreshing)) return

        val nextPage = if (isRefresh) 1 else currentState.page + 1
        if (!isRefresh && nextPage <= lastRequestedPage && !currentState.hasMore) return
        lastRequestedPage = nextPage

        if (isRefresh) {
            loadJob?.cancel()
        }

        loadJob = viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, errorMessage = null)
                else it.copy(isLoadingMore = true, errorMessage = null)
            }

            if (isRefresh) {
                when (val result = timelineRepository.getTimeline(
                    gid = currentState.currentGid,
                    page = 1,
                    maxId = null
                )) {
                    is ApiResult.Success -> {
                        val firstPagePosts = result.data
                        val previousPosts = currentState.posts
                        val previousFirstPost = previousPosts.firstOrNull()
                        val previousFirstId = previousFirstPost?.let { it.idStr ?: it.id?.toString() }
                        val existingIds = previousPosts.mapNotNull { it.idStr ?: it.id?.toString() }.toSet()

                        var allIncomingNew: List<WeiboStatusDto> = emptyList()
                        var combined: List<WeiboStatusDto>
                        var targetPage = currentState.page
                        var newLastReadId: String? = null
                        val hasIncomingNew: Boolean
                        val tip: String?

                        if (previousPosts.isEmpty()) {
                            combined = firstPagePosts.distinctBy { it.listKey }
                            hasIncomingNew = combined.isNotEmpty()
                            targetPage = 1
                            newLastReadId = null
                            tip = if (combined.isNotEmpty()) "已更新 ${combined.size} 条新微博" else "暂时没有最新动态"
                        } else {
                            val overlapIdx = firstPagePosts.indexOfFirst { post ->
                                val id = post.idStr ?: post.id?.toString()
                                id != null && existingIds.contains(id)
                            }

                            if (overlapIdx != -1) {
                                allIncomingNew = firstPagePosts.take(overlapIdx)
                                if (allIncomingNew.isNotEmpty()) {
                                    hasIncomingNew = true
                                    newLastReadId = previousFirstId
                                    combined = (allIncomingNew + previousPosts).distinctBy { it.listKey }
                                    tip = "已更新 ${allIncomingNew.size} 条新微博"
                                } else {
                                    hasIncomingNew = false
                                    newLastReadId = currentState.lastReadPostId
                                    combined = previousPosts
                                    tip = "暂时没有最新动态"
                                }
                            } else {
                                var accumulated = firstPagePosts
                                var foundOverlap = false
                                var currentFetchPage = 2
                                val maxBridgePages = 3

                                while (!foundOverlap && currentFetchPage <= maxBridgePages && accumulated.isNotEmpty()) {
                                    val lastId = accumulated.lastOrNull()?.id ?: break
                                    val nextBatch = timelineRepository.getTimeline(
                                        gid = currentState.currentGid,
                                        page = currentFetchPage,
                                        maxId = lastId
                                    )
                                    if (nextBatch is ApiResult.Success && nextBatch.data.isNotEmpty()) {
                                        val batch = nextBatch.data
                                        val idx = batch.indexOfFirst { post ->
                                            val id = post.idStr ?: post.id?.toString()
                                            id != null && existingIds.contains(id)
                                        }
                                        if (idx != -1) {
                                            foundOverlap = true
                                            accumulated = (accumulated + batch.take(idx)).distinctBy { it.listKey }
                                            break
                                        } else {
                                            accumulated = (accumulated + batch).distinctBy { it.listKey }
                                            currentFetchPage++
                                        }
                                    } else {
                                        break
                                    }
                                }

                                if (foundOverlap) {
                                    allIncomingNew = accumulated
                                    hasIncomingNew = true
                                    newLastReadId = previousFirstId
                                    combined = (allIncomingNew + previousPosts).distinctBy { it.listKey }
                                    tip = "已补全 ${allIncomingNew.size} 条最新微博"
                                } else {
                                    combined = accumulated.distinctBy { it.listKey }
                                    hasIncomingNew = combined.isNotEmpty()
                                    targetPage = currentFetchPage - 1
                                    newLastReadId = null
                                    tip = "已为您刷新最新动态 (共 ${combined.size} 条)"
                                }
                            }
                        }

                        // 只有当分割线下方有已读微博且上方至少有 1 条新微博时，分割线才有效；如果在第 0 项则清除
                        if (newLastReadId != null && combined.indexOfFirst { (it.idStr ?: it.id?.toString()) == newLastReadId } <= 0) {
                            newLastReadId = null
                        }

                        val shouldScrollToTop = hasIncomingNew || previousPosts.isEmpty()
                        if (shouldScrollToTop) scrollSnapshot = ScrollSnapshot()
                        val snapshot = scrollSnapshot

                        // 异步持久化到磁盘缓存
                        viewModelScope.launch(Dispatchers.IO) {
                            timelineRepository.saveCache(
                                gid = currentState.currentGid,
                                posts = combined,
                                page = targetPage,
                                hasMore = true,
                                scrollIndex = snapshot.index,
                                scrollOffset = snapshot.offset,
                                lastReadPostId = newLastReadId
                            )
                        }

                        _uiState.update { state ->
                            state.copy(
                                posts = combined,
                                page = targetPage,
                                hasMore = true,
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                errorMessage = null,
                                unreadCount = 0,
                                refreshTip = tip,
                                lastReadPostId = newLastReadId,
                                scrollToTopTrigger = if (shouldScrollToTop) System.currentTimeMillis() else state.scrollToTopTrigger
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                errorMessage = result.message
                            )
                        }
                    }
                    ApiResult.Loading -> {}
                }
            } else {
                val maxId = currentState.posts.lastOrNull()?.id
                when (val result = timelineRepository.getTimeline(
                    gid = currentState.currentGid,
                    page = nextPage,
                    maxId = maxId
                )) {
                    is ApiResult.Success -> {
                        val newPosts = result.data
                        _uiState.update { state ->
                            val combined = (state.posts + newPosts).distinctBy { it.listKey }
                            val snapshot = scrollSnapshot

                            viewModelScope.launch(Dispatchers.IO) {
                                timelineRepository.saveCache(
                                    gid = state.currentGid,
                                    posts = combined,
                                    page = nextPage,
                                    hasMore = newPosts.isNotEmpty(),
                                    scrollIndex = snapshot.index,
                                    scrollOffset = snapshot.offset,
                                    lastReadPostId = state.lastReadPostId
                                )
                            }

                            state.copy(
                                posts = combined,
                                page = nextPage,
                                hasMore = newPosts.isNotEmpty(),
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                errorMessage = null
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                errorMessage = result.message
                            )
                        }
                    }
                    ApiResult.Loading -> {}
                }
            }
        }
    }

    fun onScrollPositionChanged(
        scrollIndex: Int,
        scrollOffset: Int,
        postId: String? = null,
        synchronous: Boolean = false
    ) {
        val currentPosts = _uiState.value.posts
        if (currentPosts.isEmpty()) return
        val currentGid = _uiState.value.currentGid

        // 记录用户当前浏览位置的微博 ID
        val effectivePostId = postId ?: currentPosts.getOrNull(scrollIndex)?.let { it.idStr ?: it.id?.toString() }

        // 滚动位置仅记录到内部快照用于持久化，不写入 uiState（避免滑动停止触发订阅方重组）
        scrollSnapshot = ScrollSnapshot(scrollIndex, scrollOffset)

        // 立即、同步或异步写入轻量级 SharedPreferences 存储（毫秒级落盘，绝无进程杀掉丢状态之忧）
        timelineRepository.saveScrollPosition(
            gid = currentGid,
            index = scrollIndex,
            offset = scrollOffset,
            postId = effectivePostId,
            synchronous = synchronous
        )
    }

    fun onPendingRestoreConsumed() {
        _uiState.update { it.copy(pendingRestoreIndex = null, pendingRestoreOffset = 0) }
    }

    fun consumeScrollToTop() {
        _uiState.update { it.copy(scrollToTopTrigger = 0L) }
    }

    fun dismissRefreshTip() {
        _uiState.update { it.copy(refreshTip = null) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            timelineRepository.clearCache()
            authRepository.logout()
            onLoggedOut()
        }
    }
}
