package org.kazusa.matter.ui.profile

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.WeiboSearchCardInfo

/** 列表内的一个分组（「我的关注」有多个，粉丝页只有一个）。 */
internal data class UserListSource(
    val key: String,
    val title: String,
    val emptyText: String,
    val followingTab: FollowingListTab? = null
)

/** 单个分组的列表状态（各分组独立分页 / 刷新 / 加载更多）。 */
internal data class UserListUiState(
    val items: List<WeiboSearchCardInfo> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loaded: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

internal fun userListSources(kind: UserListKind): List<UserListSource> = when (kind) {
    UserListKind.FOLLOWING -> listOf(
        UserListSource("following_all", "关注的人", "还没有关注任何人", FollowingListTab.ALL),
        UserListSource("following_latest", "最新发博", "关注的人最近还没有发博", FollowingListTab.LATEST_POST),
        UserListSource("following_recent", "经常访问", "还没有经常访问的人", FollowingListTab.RECENT_VISIT)
    )
    UserListKind.FOLLOWERS -> listOf(
        UserListSource("followers", "我的粉丝", "还没有粉丝")
    )
}

/**
 * 「我的关注 / 我的粉丝」列表状态持有者。
 *
 * 放在 ViewModel 里而不是 `remember`：
 * 1. 从列表进入博主主页再返回时，NavBackStackEntry 仍存活，列表数据与滚动位置无需重新加载；
 * 2. 若状态随组合销毁，返回时列表会先回到空态再请求第一页，
 *    共享元素在返回起点就找不到目标头像，表现为「返回起步卡一下」。
 */
class UserListViewModel(
    private val uid: String,
    kind: UserListKind,
    private val apiService: WeiboApiService
) : ViewModel() {

    internal val sources: List<UserListSource> = userListSources(kind)

    var selectedIndex by mutableIntStateOf(0)
        private set

    internal val states = mutableStateMapOf<String, UserListUiState>().apply {
        sources.forEach { put(it.key, UserListUiState()) }
    }

    /** 各分组独立的滚动位置；随 ViewModel 存活，返回时直接复用。 */
    internal val listStates: Map<String, LazyListState> = sources.associate { it.key to LazyListState() }

    // 关注状态覆盖：接口回来后在本地即时更新，避免整页刷新
    val followStates = mutableStateMapOf<String, Boolean>()
    var followUpdatingUid by mutableStateOf<String?>(null)
        private set
    var pendingUnfollow by mutableStateOf<WeiboSearchCardInfo?>(null)
        private set
    var followActionError by mutableStateOf<String?>(null)
        private set

    val isGuest: Boolean = uid.isBlank() || uid == "guest"

    init {
        prepare(sources.first())
    }

    fun selectSource(index: Int) {
        if (index !in sources.indices) return
        selectedIndex = index
        prepare(sources[index])
    }

    fun refresh() {
        load(sources[selectedIndex], targetPage = 1, refresh = true)
    }

    fun loadMore() {
        val source = sources[selectedIndex]
        val state = states.getValue(source.key)
        if (!state.loaded || !state.hasMore) return
        load(source, targetPage = state.page + 1, refresh = false)
    }

    fun retry() {
        val source = sources[selectedIndex]
        val state = states.getValue(source.key)
        if (state.items.isEmpty()) {
            load(source, targetPage = 1, refresh = true)
        } else {
            load(source, targetPage = state.page + 1, refresh = false)
        }
    }

    fun onFollowClick(card: WeiboSearchCardInfo, currentlyFollowing: Boolean) {
        if (currentlyFollowing) {
            pendingUnfollow = card
        } else {
            updateFollow(card, follow = true)
        }
    }

    fun dismissUnfollowDialog() {
        pendingUnfollow = null
    }

    fun confirmUnfollow() {
        val card = pendingUnfollow ?: return
        pendingUnfollow = null
        updateFollow(card, follow = false)
    }

    fun dismissActionError() {
        followActionError = null
    }

    private fun prepare(source: UserListSource) {
        if (isGuest) {
            states[source.key] = states.getValue(source.key).copy(
                loaded = true,
                isInitialLoading = false,
                error = "登录后才能查看关注和粉丝列表"
            )
            return
        }
        val state = states.getValue(source.key)
        if (!state.loaded && !state.isInitialLoading) {
            load(source, targetPage = 1, refresh = false)
        }
    }

    private fun load(source: UserListSource, targetPage: Int, refresh: Boolean) {
        val prev = states.getValue(source.key)
        if (prev.isInitialLoading || prev.isRefreshing || prev.isLoadingMore) return

        if (isGuest) {
            states[source.key] = prev.copy(
                loaded = true,
                isInitialLoading = false,
                error = "登录后才能查看关注和粉丝列表"
            )
            return
        }

        states[source.key] = prev.copy(
            isInitialLoading = !refresh && targetPage <= 1,
            isRefreshing = refresh,
            isLoadingMore = !refresh && targetPage > 1,
            error = null
        )

        viewModelScope.launch {
            val result = source.followingTab?.let { tab ->
                apiService.getFollowingUsers(uid, targetPage, tab = tab)
            } ?: apiService.getFollowerUsers(uid, targetPage)

            when (result) {
                is ApiResult.Success -> {
                    val pageItems = result.data.items
                    val firstPage = refresh || targetPage <= 1
                    val oldSize = prev.items.size
                    val merged = if (firstPage) {
                        pageItems
                    } else {
                        (prev.items + pageItems).distinctBy { it.userListKey() }
                    }
                    val total = result.data.total
                    val hasMore = when {
                        pageItems.isEmpty() -> false
                        total != null -> merged.size < total
                        firstPage -> true
                        else -> merged.size > oldSize
                    }
                    // 用服务端下发的 following 初始化关注状态
                    merged.forEach { card ->
                        val key = card.userKey()
                        val following = card.user?.following
                        if (!key.isNullOrBlank() && following != null && !followStates.containsKey(key)) {
                            followStates[key] = following
                        }
                    }
                    states[source.key] = UserListUiState(
                        items = merged,
                        page = targetPage,
                        hasMore = hasMore,
                        loaded = true
                    )
                }
                is ApiResult.Error -> {
                    states[source.key] = states.getValue(source.key).copy(
                        loaded = true,
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = result.message ?: "加载失败",
                        hasMore = if (targetPage > 1) false else prev.hasMore
                    )
                }
                ApiResult.Loading -> Unit
            }
        }
    }

    private fun updateFollow(card: WeiboSearchCardInfo, follow: Boolean) {
        val key = card.userKey() ?: return
        if (followUpdatingUid != null) return
        followUpdatingUid = key
        viewModelScope.launch {
            when (val result = if (follow) apiService.followUser(key) else apiService.unfollowUser(key)) {
                is ApiResult.Success -> {
                    followStates[key] = follow
                    if (!follow) removeFromFollowingLists(key)
                }
                is ApiResult.Error -> {
                    followActionError = result.message
                        ?: if (follow) "关注失败，请稍后重试" else "取消关注失败，请稍后重试"
                }
                ApiResult.Loading -> Unit
            }
            followUpdatingUid = null
        }
    }

    private fun removeFromFollowingLists(key: String) {
        listOf("following_all", "following_latest").forEach { sourceKey ->
            val sourceState = states[sourceKey] ?: return@forEach
            val filtered = sourceState.items.filterNot { it.userKey() == key }
            if (filtered.size != sourceState.items.size) {
                states[sourceKey] = sourceState.copy(items = filtered)
            }
        }
    }
}

internal fun WeiboSearchCardInfo.userKey(): String? =
    user?.id?.toString() ?: user?.idStr

internal fun WeiboSearchCardInfo.userListKey(): String =
    userKey()
        ?: itemid?.takeIf { it.isNotBlank() }
        ?: title
        ?: scheme
        ?: hashCode().toString()
