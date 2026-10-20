package org.kazusa.matter.ui.profile

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.FollowResult
import org.kazusa.matter.data.repository.FollowState
import org.kazusa.matter.data.repository.UserRepository

/** 列表内的一个分组（「我的关注」有多个，粉丝页只有一个）。 */
internal data class UserListSource(
    val key: String,
    val title: String,
    val emptyText: String,
    val followingTab: FollowingListTab? = null
)

/** 单个分组的列表状态（各分组独立分页 / 刷新 / 加载更多）。 */
internal data class UserListSectionState(
    val items: List<WeiboSearchCardInfo> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loaded: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
) {
    /**
     * 触底自动加载的前置条件：没有别的请求在跑，且确实还有下一页。
     *
     * 分页失败只置 [error]，不再把 [hasMore] 写成 false，所以这里必须显式拦掉错误态。
     */
    val canLoadMore: Boolean
        get() = loaded && hasMore && error == null &&
            !isInitialLoading && !isRefreshing && !isLoadingMore
}

/** 页面状态：选中分组、各分组列表、共享关注状态与关注相关的弹窗。 */
internal data class UserListScreenState(
    val sources: List<UserListSource> = emptyList(),
    val selectedIndex: Int = 0,
    val sections: Map<String, UserListSectionState> = emptyMap(),
    /** 关注状态来自数据层的 [FollowRepository]，与博主主页共享。 */
    val followStates: Map<String, FollowState> = emptyMap(),
    val pendingUnfollow: WeiboSearchCardInfo? = null,
    val followActionError: String? = null
) {
    val selectedSource: UserListSource
        get() = sources[selectedIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0))]

    fun sectionOf(key: String): UserListSectionState = sections[key] ?: UserListSectionState()

    fun followingOf(userKey: String?): Boolean? = userKey?.let { followStates[it]?.following }

    fun isFollowUpdating(userKey: String?): Boolean =
        userKey != null && followStates[userKey]?.updating == true
}

/** 仅用于确认弹窗与操作失败提示的局部状态，避免把弹窗字段塞进列表状态里。 */
private data class UserListDialogState(
    val pendingUnfollow: WeiboSearchCardInfo? = null,
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
 *
 * 对外只暴露只读 [uiState]（StateFlow），界面不再持有可变状态：
 * 页面状态、分页标志、关注状态都由这里合并后一次性下发。
 */
class UserListViewModel(
    private val uid: String,
    kind: UserListKind,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository
) : ViewModel() {

    internal val sources: List<UserListSource> = userListSources(kind)

    private val selection = MutableStateFlow(0)
    private val sections = MutableStateFlow(sources.associate { it.key to UserListSectionState() })
    private val dialogs = MutableStateFlow(UserListDialogState())

    /**
     * 关注状态直接取自 [FollowRepository]：在博主主页取消关注后，这里收到的是同一份更新，
     * 不需要重新请求列表。
     */
    internal val uiState: StateFlow<UserListScreenState> = combine(
        selection,
        sections,
        followRepository.states,
        dialogs
    ) { index, sectionStates, followStates, dialogState ->
        UserListScreenState(
            sources = sources,
            selectedIndex = index,
            sections = sectionStates,
            followStates = followStates,
            pendingUnfollow = dialogState.pendingUnfollow,
            followActionError = dialogState.error
        )
    }.stateIn(
        scope = viewModelScope,
        // 页面状态不依赖订阅者存活：测试与「返回后立即读取」都要拿到最新值
        started = SharingStarted.Eagerly,
        initialValue = UserListScreenState(sources = sources, sections = sections.value)
    )

    /** 各分组独立的滚动位置；随 ViewModel 存活，返回时直接复用（纯视口状态，不进 StateFlow）。 */
    internal val listStates: Map<String, LazyListState> = sources.associate { it.key to LazyListState() }

    // 每个分组各自的在途请求：第一页（刷新 / 重试）会取消它，旧分页结果不能追加到新列表
    private val loadJobs = mutableMapOf<String, Job>()
    private val loadTokens = mutableMapOf<String, Int>()

    val isGuest: Boolean = uid.isBlank() || uid == "guest"

    init {
        prepare(sources.first())
    }

    fun selectSource(index: Int) {
        if (index !in sources.indices) return
        selection.value = index
        prepare(sources[index])
    }

    fun refresh() {
        load(sources[selection.value], targetPage = 1, refresh = true)
    }

    fun loadMore() {
        val source = sources[selection.value]
        val state = sectionOf(source.key)
        if (!state.canLoadMore) return
        load(source, targetPage = state.page + 1, refresh = false)
    }

    fun retry() {
        val source = sources[selection.value]
        val state = sectionOf(source.key)
        if (state.items.isEmpty()) {
            load(source, targetPage = 1, refresh = true)
        } else {
            load(source, targetPage = state.page + 1, refresh = false)
        }
    }

    fun onFollowClick(card: WeiboSearchCardInfo, currentlyFollowing: Boolean) {
        if (currentlyFollowing) {
            dialogs.update { it.copy(pendingUnfollow = card) }
        } else {
            updateFollow(card, follow = true)
        }
    }

    fun dismissUnfollowDialog() {
        dialogs.update { it.copy(pendingUnfollow = null) }
    }

    fun confirmUnfollow() {
        val card = dialogs.value.pendingUnfollow ?: return
        dialogs.update { it.copy(pendingUnfollow = null) }
        updateFollow(card, follow = false)
    }

    fun dismissActionError() {
        dialogs.update { it.copy(error = null) }
    }

    private fun sectionOf(key: String): UserListSectionState =
        sections.value[key] ?: UserListSectionState()

    private fun prepare(source: UserListSource) {
        if (isGuest) {
            updateSection(source.key) {
                it.copy(
                    loaded = true,
                    isInitialLoading = false,
                    error = "登录后才能查看关注和粉丝列表"
                )
            }
            return
        }
        val state = sectionOf(source.key)
        if (!state.loaded && !state.isInitialLoading) {
            load(source, targetPage = 1, refresh = false)
        }
    }

    /**
     * 拉取某个分组的一页。
     *
     * 请求身份规则与「我的微博」一致：
     * - 第一页（首次 / 刷新 / 重试）取消在途请求并推进 token，旧分页结果不能追加到新列表；
     * - 分页只在没有其它请求时发起，且不会因为刷新而被静默丢弃；
     * - 所有状态写回前校验 token，晚到的响应不会改写 items / page / hasMore。
     */
    private fun load(source: UserListSource, targetPage: Int, refresh: Boolean) {
        val prev = sectionOf(source.key)
        val firstPage = refresh || targetPage <= 1
        // 分页与其它请求互斥；第一页可以取代在途分页，因此不受这个闸门限制
        if (!firstPage && (prev.isInitialLoading || prev.isRefreshing || prev.isLoadingMore)) return

        if (isGuest) {
            loadJobs.remove(source.key)?.cancel()
            loadTokens[source.key] = (loadTokens[source.key] ?: 0) + 1
            updateSection(source.key) {
                it.copy(
                    loaded = true,
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    error = "登录后才能查看关注和粉丝列表"
                )
            }
            return
        }

        if (firstPage) loadJobs.remove(source.key)?.cancel()
        val token = (loadTokens[source.key] ?: 0) + 1
        loadTokens[source.key] = token

        updateSection(source.key) {
            it.copy(
                isInitialLoading = !refresh && targetPage <= 1,
                isRefreshing = refresh,
                isLoadingMore = !refresh && targetPage > 1,
                error = null
            )
        }

        loadJobs[source.key] = viewModelScope.launch {
            try {
                val result = source.followingTab?.let { tab ->
                    userRepository.following(uid, targetPage, tab)
                } ?: userRepository.followers(uid, targetPage)

                if (loadTokens[source.key] != token) return@launch

                when (result) {
                    is ApiResult.Success -> {
                        val pageItems = result.data.items
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
                        // 用服务端下发的 following 给共享关注状态打底（已有覆盖值时不覆盖）
                        merged.forEach { card ->
                            followRepository.seed(card.userKey(), card.user?.following)
                        }
                        sections.update {
                            it + (
                                source.key to UserListSectionState(
                                    items = merged,
                                    page = targetPage,
                                    hasMore = hasMore,
                                    loaded = true
                                )
                                )
                        }
                    }

                    is ApiResult.Error -> {
                        updateSection(source.key) {
                            it.copy(
                                loaded = true,
                                isInitialLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                error = result.message.ifBlank { "加载失败" },
                                // 失败只表示这一页没拉到，不能写成「没有下一页」：
                                // 自动加载由 error 拦下（见 canLoadMore），页面底部仍可原地重试
                                hasMore = prev.hasMore
                            )
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (loadTokens[source.key] == token) loadJobs.remove(source.key)
            }
        }
    }

    private fun updateFollow(card: WeiboSearchCardInfo, follow: Boolean) {
        val key = card.userKey() ?: return
        // 请求与乐观更新交给共享的关注仓库：同一 uid 的重复点击在那里被挡住，
        // 不同用户可以并行，也不会被另一个人的请求阻塞
        viewModelScope.launch {
            when (val result = followRepository.setFollowing(key, follow)) {
                FollowResult.Success -> if (!follow) removeFromFollowingLists(key)
                is FollowResult.Failure -> dialogs.update { it.copy(error = result.message) }
                FollowResult.Ignored -> Unit
            }
        }
    }

    private fun removeFromFollowingLists(key: String) {
        listOf("following_all", "following_latest").forEach { sourceKey ->
            sections.update { current ->
                val section = current[sourceKey] ?: return@update current
                val filtered = section.items.filterNot { it.userKey() == key }
                if (filtered.size == section.items.size) {
                    current
                } else {
                    current + (sourceKey to section.copy(items = filtered))
                }
            }
        }
    }

    private fun updateSection(
        key: String,
        transform: (UserListSectionState) -> UserListSectionState
    ) {
        sections.update { current ->
            current + (key to transform(current[key] ?: UserListSectionState()))
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
