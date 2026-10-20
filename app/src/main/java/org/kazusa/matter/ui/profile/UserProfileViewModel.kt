package org.kazusa.matter.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.FollowResult
import org.kazusa.matter.data.repository.FollowState
import org.kazusa.matter.data.repository.UserRepository
import org.kazusa.matter.data.repository.mergeUserProfile

/** 主页 tab：微博 / 相册 / 视频 / 文章 */
internal enum class ProfileTab(val title: String) {
    POSTS("微博"),
    ALBUM("相册"),
    VIDEO("视频"),
    ARTICLE("文章")
}

/** 头部资料：博主基本信息 + PC 端「资料」模块（生日 / 教育 / 职业 / 阳光信用等）。 */
internal data class ProfileHeaderState(
    val user: WeiboUserDto? = null,
    val detail: UserProfileDetail? = null,
    val isDetailLoading: Boolean = false,
    val detailLoadFailed: Boolean = false
)

/** 「微博」tab：动态列表与分页。 */
internal data class ProfilePostsState(
    val items: List<WeiboStatusDto> = emptyList(),
    val totalCount: Int? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

/** 「相册」tab：相册列表 + 当前打开相册的照片分页。 */
internal data class ProfileAlbumState(
    val items: List<UserAlbum> = emptyList(),
    val loaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val opened: UserAlbum? = null,
    val photos: List<String> = emptyList(),
    val photoCursor: String = "0",
    val photosHasMore: Boolean = false,
    val isPhotosLoading: Boolean = false,
    val photosError: String? = null
)

/** 「视频」tab：游标分页。 */
internal data class ProfileVideoState(
    val items: List<WeiboStatusDto> = emptyList(),
    val cursor: String = "0",
    val loaded: Boolean = false,
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null
)

/** 「文章」tab：页码分页。 */
internal data class ProfileArticleState(
    val items: List<WeiboStatusDto> = emptyList(),
    val page: Int = 1,
    val loaded: Boolean = false,
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null
)

/**
 * 博主主页的页面状态；按 tab 拆成几块，避免一个 30+ 字段的巨型状态。
 *
 * 各 tab 的列表相互独立：切 tab 只换 [selectedTab]，
 * 各子状态内部自带加载 / 错误 / 游标，互不干扰。
 * [isRefreshing] 是页面级的：下拉刷新作用于当前 tab，所以不放进任一 tab 的子状态。
 */
internal data class UserProfileUiState(
    val header: ProfileHeaderState = ProfileHeaderState(),
    val posts: ProfilePostsState = ProfilePostsState(),
    val album: ProfileAlbumState = ProfileAlbumState(),
    val video: ProfileVideoState = ProfileVideoState(),
    val article: ProfileArticleState = ProfileArticleState(),
    val selectedTab: ProfileTab = ProfileTab.POSTS,
    /** 下拉刷新指示器（作用于当前 tab）。 */
    val isRefreshing: Boolean = false,
    /** 一次性提示（如加载更多失败），由界面取走后清空。 */
    val message: String? = null,
    // ---- 关注（来自共享仓库）----
    val follow: FollowState? = null,
    val followActionError: String? = null
)

/**
 * 博主主页的数据状态。
 *
 * 由 Nav3 的 `rememberViewModelStoreNavEntryDecorator` 按 NavEntry 提供 ViewModelStore，
 * 所以「主页 → 微博正文 → 返回」这一程里本类不会被销毁：资料、动态列表、分页位置、当前分栏
 * 都还在，返回时列表首帧就有内容，滚动位置才有落点可还原（否则空列表会把下标压回 0）。
 *
 * 弹栈离开主页时随 ViewModelStore 一起回收，不像进程级缓存那样把旧数据长期留在内存里。
 */
internal class UserProfileViewModel internal constructor(
    private val uid: String,
    private val recordUser: suspend (WeiboUserDto) -> Unit,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository
) : ViewModel() {

    constructor(
        uid: String,
        userRepository: UserRepository,
        historyRepository: BrowsingHistoryRepository,
        followRepository: FollowRepository
    ) : this(
        uid = uid,
        recordUser = { user -> historyRepository.recordUser(user) },
        userRepository = userRepository,
        followRepository = followRepository
    )

    private val _uiState = MutableStateFlow(UserProfileUiState())
    private val _followActionError = MutableStateFlow<String?>(null)

    /**
     * 页面状态 = 业务状态 + 共享关注状态 + 本页操作提示。
     *
     * 关注状态合并进来后，列表页点过的结果可以直接反映在按钮上，
     * 界面只需要收集一个 Flow。初始值取构造时的仓库快照，避免首帧先画成「关注」再跳变。
     */
    val uiState: StateFlow<UserProfileUiState> = combine(
        _uiState,
        followRepository.states,
        _followActionError
    ) { state, follows, followError ->
        state.copy(follow = follows[uid], followActionError = followError)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserProfileUiState(
            follow = followRepository.followingOf(uid)?.let { FollowState(it) }
        )
    )

    private var hasLoadedOnce = false

    // 动态列表请求身份：第一页取代在途分页，旧页结果不能追加到新列表
    private var postsJob: Job? = null
    private var postsToken = 0

    // 相册照片请求身份：切换 / 关闭相册时作废旧请求，避免照片写进新相册
    private var albumPhotosJob: Job? = null
    private var albumPhotosToken = 0

    /** 首次进入本页时拉取资料与第一页动态；从正文页返回时 ViewModel 仍在，不会重复请求 */
    fun loadIfNeeded() {
        if (hasLoadedOnce) return
        hasLoadedOnce = true
        load(page = 1)
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 关注；请求与 pending 都在 ViewModel，确认弹窗仍留在界面。 */
    fun follow() = updateFollow(target = true)

    /** 取消关注；语义与 [follow] 一致。 */
    fun unfollow() = updateFollow(target = false)

    fun consumeFollowError() {
        _followActionError.value = null
    }

    private fun updateFollow(target: Boolean) {
        _followActionError.value = null
        viewModelScope.launch {
            // 请求、乐观更新、失败回滚与重复点击去重都在共享仓库里，列表页读的是同一份状态
            when (val result = followRepository.setFollowing(uid, target)) {
                FollowResult.Success -> updateHeader {
                    it.copy(user = it.user?.copy(following = target))
                }

                is FollowResult.Failure -> _followActionError.value = result.message
                FollowResult.Ignored -> Unit
            }
        }
    }

    fun loadProfileDetail() {
        viewModelScope.launch {
            updateHeader { it.copy(isDetailLoading = true, detailLoadFailed = false) }
            when (val detailRes = userRepository.profileDetail(uid)) {
                is ApiResult.Success -> updateHeader { it.copy(detail = detailRes.data) }
                is ApiResult.Error -> updateHeader { it.copy(detailLoadFailed = true) }
                else -> Unit
            }
            updateHeader { it.copy(isDetailLoading = false) }
        }
    }

    fun loadAlbums(force: Boolean = false) {
        val album = _uiState.value.album
        if (album.isLoading) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (album.loaded && !force) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        updateAlbum { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val res = userRepository.albums(uid)) {
                is ApiResult.Success -> updateAlbum {
                    it.copy(items = res.data, loaded = true)
                }

                is ApiResult.Error -> updateAlbum { it.copy(error = res.message) }
                else -> Unit
            }
            updateAlbum { it.copy(isLoading = false) }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun loadAlbumPhotos(reset: Boolean = false) {
        val album = _uiState.value.album
        val opened = album.opened ?: run {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (reset) {
            // 重置（切换相册 / 下拉刷新）：作废在途请求，旧游标的结果不能再写进新列表
            albumPhotosJob?.cancel()
            albumPhotosJob = null
            albumPhotosToken += 1
            updateAlbum { it.copy(isPhotosLoading = false) }
        } else if (album.isPhotosLoading) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (!reset && !album.photosHasMore) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (reset) {
            updateAlbum {
                it.copy(
                    photos = emptyList(),
                    photoCursor = "0",
                    photosHasMore = true,
                    photosError = null
                )
            }
        }
        updateAlbum { it.copy(isPhotosLoading = true) }
        val cursor = _uiState.value.album.photoCursor
        val albumId = opened.containerId
        val token = ++albumPhotosToken
        albumPhotosJob = viewModelScope.launch {
            try {
                when (val res = userRepository.albumPhotos(albumId, cursor)) {
                    is ApiResult.Success -> {
                        // 双重校验：请求身份 + 当前打开的相册，晚到的旧相册结果直接丢弃
                        if (!isCurrentAlbumRequest(token, albumId)) return@launch
                        updateAlbum {
                            it.copy(
                                photos = (it.photos + res.data.photos).distinct(),
                                photoCursor = res.data.nextCursor,
                                photosHasMore = res.data.hasMore
                            )
                        }
                    }

                    is ApiResult.Error -> {
                        if (!isCurrentAlbumRequest(token, albumId)) return@launch
                        updateAlbum { it.copy(photosError = res.message) }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (isCurrentAlbumRequest(token, albumId)) {
                    updateAlbum { it.copy(isPhotosLoading = false) }
                }
                if (token == albumPhotosToken) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private fun isCurrentAlbumRequest(token: Int, albumId: String): Boolean =
        token == albumPhotosToken && _uiState.value.album.opened?.containerId == albumId

    fun openAlbum(album: UserAlbum) {
        updateAlbum {
            it.copy(
                opened = album,
                photos = emptyList(),
                photoCursor = "0",
                photosHasMore = true,
                photosError = null
            )
        }
        // reset 会取消旧相册的在途请求并推进 token，旧结果写不进新相册
        loadAlbumPhotos(reset = true)
    }

    fun closeAlbum() {
        // 关闭相册同样要取消请求：否则旧结果会落到下一次打开的相册里
        albumPhotosJob?.cancel()
        albumPhotosJob = null
        albumPhotosToken += 1
        updateAlbum {
            it.copy(
                isPhotosLoading = false,
                opened = null,
                photos = emptyList(),
                photoCursor = "0",
                photosHasMore = false,
                photosError = null
            )
        }
    }

    fun loadVideos(reset: Boolean = false) {
        val video = _uiState.value.video
        if (video.isLoading) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (!reset && video.loaded && !video.hasMore) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (reset) {
            updateVideo {
                it.copy(
                    items = emptyList(),
                    cursor = "0",
                    hasMore = true,
                    loaded = false,
                    error = null
                )
            }
        }
        updateVideo { it.copy(isLoading = true) }
        val cursor = _uiState.value.video.cursor
        viewModelScope.launch {
            when (val res = userRepository.videos(uid, cursor)) {
                is ApiResult.Success -> updateVideo {
                    it.copy(
                        items = (it.items + res.data.videos).distinctBy { videoItem -> videoItem.listKey },
                        cursor = res.data.nextCursor,
                        hasMore = res.data.hasMore,
                        loaded = true
                    )
                }

                is ApiResult.Error -> updateVideo { it.copy(error = res.message) }
                else -> Unit
            }
            updateVideo { it.copy(isLoading = false) }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun loadArticles(reset: Boolean = false) {
        val article = _uiState.value.article
        if (article.isLoading) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (!reset && article.loaded && !article.hasMore) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        val page = if (reset || !article.loaded) 1 else article.page + 1
        if (reset) {
            updateArticle {
                it.copy(
                    items = emptyList(),
                    page = 1,
                    hasMore = true,
                    loaded = false,
                    error = null
                )
            }
        }
        updateArticle { it.copy(isLoading = true) }
        viewModelScope.launch {
            when (val res = userRepository.articles(uid, page)) {
                is ApiResult.Success -> updateArticle {
                    it.copy(
                        items = (it.items + res.data.articles).distinctBy { articleItem -> articleItem.listKey },
                        page = page,
                        hasMore = res.data.hasMore,
                        loaded = true
                    )
                }

                is ApiResult.Error -> updateArticle { it.copy(error = res.message) }
                else -> Unit
            }
            updateArticle { it.copy(isLoading = false) }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun selectTab(tab: ProfileTab) {
        val current = _uiState.value
        if (current.selectedTab == tab) return
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab != ProfileTab.ALBUM) closeAlbum()
        when (tab) {
            ProfileTab.POSTS -> Unit
            ProfileTab.ALBUM -> loadAlbums()
            ProfileTab.VIDEO -> if (!current.video.loaded) loadVideos() else Unit
            ProfileTab.ARTICLE -> if (!current.article.loaded) loadArticles() else Unit
        }
    }

    /**
     * 拉取博主动态。
     *
     * 请求身份规则与搜索页一致：
     * - 第一页（首次 / 刷新 / 重试）取消在途请求并推进 token，旧分页结果不能追加到新列表；
     * - 分页只在没有其它请求、且确实还有下一页时发起，同一页不会并发；
     * - 所有状态写回前校验 token，晚到的响应不会改写 posts / page / hasMore。
     */
    fun load(page: Int = 1, isRefresh: Boolean = false) {
        val isFirstPage = page == 1
        if (isFirstPage) {
            postsJob?.cancel()
            postsJob = null
            postsToken += 1
            updatePosts { it.copy(isLoadingMore = false, isInitialLoading = !isRefresh, error = null) }
            _uiState.update { it.copy(isRefreshing = isRefresh) }
        } else {
            val current = _uiState.value
            if (current.posts.isInitialLoading || current.isRefreshing || current.posts.isLoadingMore ||
                !current.posts.hasMore
            ) {
                return
            }
            updatePosts { it.copy(isLoadingMore = true) }
        }
        val token = postsToken
        postsJob = viewModelScope.launch {
            try {
                if (isFirstPage) requestFirstPage(token) else requestPage(token, page)
            } finally {
                if (token == postsToken) {
                    updatePosts { it.copy(isInitialLoading = false, isLoadingMore = false) }
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private suspend fun requestFirstPage(token: Int) {
        // 详细资料走 PC 端登录态接口，独立请求；即便失败也不影响主页主体内容
        loadProfileDetail()

        // 并发获取博主资料与动态，大幅缩短加载等待时间
        val (timelineRes, profileRes) = coroutineScope {
            val timelineDeferred = async { userRepository.profileTimeline(uid, 1) }
            val profileDeferred = async { userRepository.profile(uid) }
            timelineDeferred.await() to profileDeferred.await()
        }
        // 期间已经发起了更新的请求：这一份结果整体作废
        if (token != postsToken) return

        if (profileRes is ApiResult.Success) {
            val p = profileRes.data
            // 本地浏览历史：同一博主只保留最近一次访问
            recordUser(p)
            updateHeader { it.copy(user = mergeUserProfile(it.user, p)) }
            // 关注状态打底：本会话里已经点过的结果优先，不被服务端的旧值覆盖
            followRepository.seed(uid, p.following)
        }

        when (timelineRes) {
            is ApiResult.Success -> {
                val result = timelineRes.data
                result.user?.let { user ->
                    updateHeader { it.copy(user = mergeUserProfile(it.user, user)) }
                }
                updatePosts { current ->
                    current.copy(
                        items = result.statuses,
                        page = 1,
                        hasMore = result.hasMore && result.statuses.isNotEmpty(),
                        totalCount = if (result.total != null && result.total > 0) {
                            result.total
                        } else {
                            current.totalCount
                        },
                        error = null
                    )
                }
            }

            is ApiResult.Error -> updatePosts { it.copy(error = timelineRes.message) }
            else -> {}
        }
    }

    private suspend fun requestPage(token: Int, page: Int) {
        when (val timelineRes = userRepository.profileTimeline(uid, page)) {
            is ApiResult.Success -> {
                if (token != postsToken) return
                val result = timelineRes.data
                updatePosts { current ->
                    current.copy(
                        items = (current.items + result.statuses).distinctBy { it.listKey },
                        page = page,
                        hasMore = result.hasMore && result.statuses.isNotEmpty(),
                        totalCount = if (result.total != null && result.total > 0) {
                            result.total
                        } else {
                            current.totalCount
                        }
                    )
                }
            }

            is ApiResult.Error -> {
                if (token == postsToken) {
                    _uiState.update { it.copy(message = timelineRes.message) }
                }
            }

            else -> {}
        }
    }

    fun refreshCurrentTab() {
        when (_uiState.value.selectedTab) {
            ProfileTab.POSTS -> load(page = 1, isRefresh = true)
            ProfileTab.ALBUM -> {
                _uiState.update { it.copy(isRefreshing = true) }
                if (_uiState.value.album.opened != null) {
                    loadAlbumPhotos(reset = true)
                } else {
                    loadAlbums(force = true)
                }
            }

            ProfileTab.VIDEO -> {
                _uiState.update { it.copy(isRefreshing = true) }
                loadVideos(reset = true)
            }

            ProfileTab.ARTICLE -> {
                _uiState.update { it.copy(isRefreshing = true) }
                loadArticles(reset = true)
            }
        }
    }

    private fun updateHeader(transform: (ProfileHeaderState) -> ProfileHeaderState) {
        _uiState.update { it.copy(header = transform(it.header)) }
    }

    private fun updatePosts(transform: (ProfilePostsState) -> ProfilePostsState) {
        _uiState.update { it.copy(posts = transform(it.posts)) }
    }

    private fun updateAlbum(transform: (ProfileAlbumState) -> ProfileAlbumState) {
        _uiState.update { it.copy(album = transform(it.album)) }
    }

    private fun updateVideo(transform: (ProfileVideoState) -> ProfileVideoState) {
        _uiState.update { it.copy(video = transform(it.video)) }
    }

    private fun updateArticle(transform: (ProfileArticleState) -> ProfileArticleState) {
        _uiState.update { it.copy(article = transform(it.article)) }
    }
}
