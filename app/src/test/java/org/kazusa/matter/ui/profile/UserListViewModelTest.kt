package org.kazusa.matter.ui.profile

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.AlbumPhotoPage
import org.kazusa.matter.data.model.ArticleFeedPage
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.VideoFeedPage
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.model.WeiboUserListPage
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.UserRepository

@OptIn(ExperimentalCoroutinesApi::class)
class UserListViewModelTest {

    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun keep(viewModel: UserListViewModel): UserListViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("userList", vm)
            stores += store
        }
    }

    private fun card(id: Long) = WeiboSearchCardInfo(
        user = WeiboUserDto(id = id, idStr = id.toString(), screenName = "用户$id")
    )

    private fun userListViewModel(repository: UserRepository): UserListViewModel = keep(
        UserListViewModel(
            uid = "100",
            kind = UserListKind.FOLLOWING,
            userRepository = repository,
            followRepository = FollowRepository(repository::follow, repository::unfollow)
        )
    )

    @Test
    fun refreshSupersedesInFlightPagination() = runTest {
        var firstPageCalls = 0
        val repository = FakeUserRepository(
            onFollowing = { page, _ ->
                when (page) {
                    1 -> {
                        firstPageCalls += 1
                        // 第二次第一页（下拉刷新）返回与首次不同的内容，便于区分是否被旧分页污染
                        val id = if (firstPageCalls == 1) 1L else 7L
                        ApiResult.Success(WeiboUserListPage(items = listOf(card(id)), total = 2))
                    }

                    else -> {
                        // 分页很慢：刷新必须能取代它，且它的结果不能再追加到新列表
                        delay(1_000)
                        ApiResult.Success(WeiboUserListPage(items = listOf(card(2)), total = 2))
                    }
                }
            }
        )
        val vm = userListViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.uiState.value.sectionOf("following_all").items.map { it.userKey() })

        vm.loadMore()
        runCurrent()
        assertTrue(vm.uiState.value.sectionOf("following_all").isLoadingMore)

        // 分页在路上时下拉刷新：不能被静默丢弃
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value.sectionOf("following_all")
        assertEquals(listOf("7"), state.items.map { it.userKey() })
        assertFalse(state.isRefreshing)
        assertFalse(state.isLoadingMore)
        assertEquals(1, state.page)
    }

    @Test
    fun paginationFailureKeepsPageRetryable() = runTest {
        var failPage = true
        val repository = FakeUserRepository(
            onFollowing = { page, _ ->
                when {
                    page == 1 -> ApiResult.Success(WeiboUserListPage(items = listOf(card(1)), total = 2))
                    failPage -> ApiResult.Error(message = "网络断开")
                    else -> ApiResult.Success(WeiboUserListPage(items = listOf(card(2)), total = 2))
                }
            }
        )
        val vm = userListViewModel(repository)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        val failed = vm.uiState.value.sectionOf("following_all")
        assertEquals("网络断开", failed.error)
        // 失败不等于「到底了」：hasMore 必须保留，否则界面会显示成没有下一页
        assertTrue(failed.hasMore)

        // 触底不应在错误态下反复自动重试
        failPage = false
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.uiState.value.sectionOf("following_all").items.map { it.userKey() })

        // 页面上的「重试」必须真的能重试失败的那一页
        vm.retry()
        advanceUntilIdle()
        val recovered = vm.uiState.value.sectionOf("following_all")
        assertEquals(listOf("1", "2"), recovered.items.map { it.userKey() })
        assertNull(recovered.error)
    }

    @Test
    fun followOnAnotherUserIsNotBlockedByInFlightRequest() = runTest {
        val followCalls = mutableListOf<String>()
        val repository = FakeUserRepository(
            onFollowing = { _, _ ->
                ApiResult.Success(WeiboUserListPage(items = listOf(card(1), card(2))))
            },
            onFollow = { uid ->
                followCalls += uid
                delay(1_000)
                ApiResult.Success(Unit)
            }
        )
        val vm = userListViewModel(repository)
        advanceUntilIdle()

        vm.onFollowClick(card(1), currentlyFollowing = false)
        runCurrent()
        assertEquals(listOf("1"), followCalls)
        assertTrue(vm.uiState.value.isFollowUpdating("1"))
        // 乐观更新：请求还没回来，按钮已经切到目标状态
        assertEquals(true, vm.uiState.value.followingOf("1"))

        // 对 A 的请求还没回来时点 B：B 的请求必须照常发出，按钮也应进入进行中
        vm.onFollowClick(card(2), currentlyFollowing = false)
        runCurrent()
        assertEquals(listOf("1", "2"), followCalls)
        assertTrue(vm.uiState.value.isFollowUpdating("1"))
        assertTrue(vm.uiState.value.isFollowUpdating("2"))

        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.followingOf("1"))
        assertEquals(true, vm.uiState.value.followingOf("2"))
        assertFalse(vm.uiState.value.isFollowUpdating("1"))
        assertFalse(vm.uiState.value.isFollowUpdating("2"))
    }

    /** 只模拟列表 / 关注相关接口，其余接口在本测试里不会被调用。 */
    private class FakeUserRepository(
        private val onFollowing: suspend (page: Int, tab: FollowingListTab) -> ApiResult<WeiboUserListPage>,
        private val onFollow: suspend (uid: String) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    ) : UserRepository {
        override suspend fun profile(uid: String): ApiResult<WeiboUserDto> = notUsed()
        override suspend fun profileDetail(uid: String): ApiResult<UserProfileDetail> = notUsed()
        override suspend fun profileTimeline(uid: String, page: Int): ApiResult<UserTimelineResult> = notUsed()
        override suspend fun albums(uid: String): ApiResult<List<UserAlbum>> = notUsed()
        override suspend fun albumPhotos(containerId: String, cursor: String): ApiResult<AlbumPhotoPage> =
            notUsed()

        override suspend fun videos(uid: String, cursor: String): ApiResult<VideoFeedPage> = notUsed()
        override suspend fun articles(uid: String, page: Int): ApiResult<ArticleFeedPage> = notUsed()
        override suspend fun follow(uid: String): ApiResult<Unit> = onFollow(uid)
        override suspend fun unfollow(uid: String): ApiResult<Unit> = onFollow(uid)

        override suspend fun following(
            uid: String,
            page: Int,
            tab: FollowingListTab
        ): ApiResult<WeiboUserListPage> = onFollowing(page, tab)

        override suspend fun followers(uid: String, page: Int): ApiResult<WeiboUserListPage> =
            onFollowing(page, FollowingListTab.ALL)

        private fun <T> notUsed(): T = throw UnsupportedOperationException("本测试不应调用该接口")
    }
}
