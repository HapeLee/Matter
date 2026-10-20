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
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.model.WeiboUserListPage
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.UserRepository

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModelTest {
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

    private fun keep(viewModel: UserProfileViewModel): UserProfileViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("profile", vm)
            stores += store
        }
    }

    private fun viewModel(source: FakeSource) = keep(
        UserProfileViewModel(
            uid = "123456",
            recordUser = {},
            userRepository = source,
            followRepository = FollowRepository(source::follow, source::unfollow)
        )
    )

    @Test
    fun switchingAlbumDiscardsPhotosFromThePreviousAlbum() = runTest {
        val albumA = UserAlbum(containerId = "A", title = "相册 A")
        val albumB = UserAlbum(containerId = "B", title = "相册 B")
        val vm = viewModel(
            FakeSource(
                photosResponse = { albumId, _ ->
                    if (albumId == "A") {
                        delay(1_000)
                        ApiResult.Success(AlbumPhotoPage(photos = listOf("a1"), hasMore = false))
                    } else {
                        ApiResult.Success(AlbumPhotoPage(photos = listOf("b1"), hasMore = false))
                    }
                }
            )
        )

        vm.openAlbum(albumA)
        runCurrent()
        assertTrue(vm.uiState.value.album.isPhotosLoading)

        // 相册 A 的照片还在路上就切到 B
        vm.openAlbum(albumB)
        advanceUntilIdle()

        assertEquals("B", vm.uiState.value.album.opened?.containerId)
        assertEquals(listOf("b1"), vm.uiState.value.album.photos)
        assertFalse(vm.uiState.value.album.isPhotosLoading)
        assertNull(vm.uiState.value.album.photosError)
    }

    @Test
    fun closingAlbumInvalidatesInFlightPhotos() = runTest {
        val vm = viewModel(
            FakeSource(
                photosResponse = { _, _ ->
                    delay(1_000)
                    ApiResult.Success(AlbumPhotoPage(photos = listOf("late"), hasMore = false))
                }
            )
        )

        vm.openAlbum(UserAlbum(containerId = "A", title = "相册 A"))
        runCurrent()
        vm.closeAlbum()
        advanceUntilIdle()

        assertNull(vm.uiState.value.album.opened)
        assertTrue(vm.uiState.value.album.photos.isEmpty())
        assertFalse(vm.uiState.value.album.isPhotosLoading)
    }

    @Test
    fun refreshSupersedesInFlightPagination() = runTest {
        val vm = viewModel(
            FakeSource(
                timelineResponse = { _, page ->
                    if (page == 1) {
                        ApiResult.Success(
                            UserTimelineResult(statuses = listOf(WeiboStatusDto(id = 1)), hasMore = true)
                        )
                    } else {
                        delay(1_000)
                        ApiResult.Success(
                            UserTimelineResult(statuses = listOf(WeiboStatusDto(id = 99)), hasMore = true)
                        )
                    }
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.uiState.value.posts.items.map { it.id })

        vm.load(page = 2)
        runCurrent()
        assertTrue(vm.uiState.value.posts.isLoadingMore)

        vm.load(page = 1, isRefresh = true)
        advanceUntilIdle()

        // 刷新取代分页：旧页结果不能追加，也不能改写页码
        assertEquals(listOf(1L), vm.uiState.value.posts.items.map { it.id })
        assertEquals(1, vm.uiState.value.posts.page)
        assertFalse(vm.uiState.value.posts.isLoadingMore)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun duplicatePaginationIsIgnoredWhileOneIsRunning() = runTest {
        var pageCalls = 0
        val vm = viewModel(
            FakeSource(
                timelineResponse = { _, page ->
                    if (page == 1) {
                        ApiResult.Success(
                            UserTimelineResult(statuses = listOf(WeiboStatusDto(id = 1)), hasMore = true)
                        )
                    } else {
                        pageCalls += 1
                        delay(1_000)
                        ApiResult.Success(
                            UserTimelineResult(statuses = listOf(WeiboStatusDto(id = 2)), hasMore = true)
                        )
                    }
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        vm.load(page = 2)
        vm.load(page = 2)
        vm.load(page = 2)
        advanceUntilIdle()

        assertEquals(1, pageCalls)
        assertEquals(listOf(1L, 2L), vm.uiState.value.posts.items.map { it.id })
        assertEquals(2, vm.uiState.value.posts.page)
    }

    @Test
    fun followFailureKeepsStateAndExposesError() = runTest {
        val vm = viewModel(FakeSource(followResponse = { ApiResult.Error(message = "关注失败") }))
        vm.loadIfNeeded()
        advanceUntilIdle()

        vm.follow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.header.user?.following == true)
        // 共享关注状态同样要回滚，否则列表页会读到失败后的错误状态
        assertFalse(vm.uiState.value.follow?.following == true)
        assertEquals("关注失败", vm.uiState.value.followActionError)
        assertFalse(vm.uiState.value.follow?.updating == true)

        vm.consumeFollowError()
        // 页面状态是合并后的 StateFlow，清空后要等一次派发才会反映到 uiState
        advanceUntilIdle()
        assertNull(vm.uiState.value.followActionError)
    }

    @Test
    fun followSuccessUpdatesFollowingAndClearsPending() = runTest {
        val vm = viewModel(FakeSource())
        vm.loadIfNeeded()
        advanceUntilIdle()

        vm.follow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.header.user?.following == true)
        assertTrue(vm.uiState.value.follow?.following == true)
        assertNull(vm.uiState.value.followActionError)
        assertFalse(vm.uiState.value.follow?.updating == true)

        vm.unfollow()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.header.user?.following == true)
        assertFalse(vm.uiState.value.follow?.following == true)
    }

    private class FakeSource(
        private val timelineResponse: suspend (String, Int) -> ApiResult<UserTimelineResult> = { _, _ ->
            ApiResult.Success(
                UserTimelineResult(statuses = listOf(WeiboStatusDto(id = 1)), hasMore = false)
            )
        },
        private val profileResponse: suspend (String) -> ApiResult<WeiboUserDto> = {
            ApiResult.Success(WeiboUserDto(screenName = "博主"))
        },
        private val detailResponse: suspend (String) -> ApiResult<UserProfileDetail> = {
            ApiResult.Success(UserProfileDetail())
        },
        private val albumsResponse: suspend (String) -> ApiResult<List<UserAlbum>> = {
            ApiResult.Success(emptyList())
        },
        private val photosResponse: suspend (String, String) -> ApiResult<AlbumPhotoPage> = { _, _ ->
            ApiResult.Success(AlbumPhotoPage())
        },
        private val videosResponse: suspend (String, String) -> ApiResult<VideoFeedPage> = { _, _ ->
            ApiResult.Success(VideoFeedPage())
        },
        private val articlesResponse: suspend (String, Int) -> ApiResult<ArticleFeedPage> = { _, _ ->
            ApiResult.Success(ArticleFeedPage())
        },
        private val followResponse: suspend (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) },
        private val unfollowResponse: suspend (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) },
        private val followingResponse: suspend (String, Int, FollowingListTab) -> ApiResult<WeiboUserListPage> =
            { _, _, _ -> ApiResult.Success(WeiboUserListPage()) },
        private val followersResponse: suspend (String, Int) -> ApiResult<WeiboUserListPage> =
            { _, _ -> ApiResult.Success(WeiboUserListPage()) }
    ) : UserRepository {
        override suspend fun profileTimeline(uid: String, page: Int) = timelineResponse(uid, page)
        override suspend fun profile(uid: String) = profileResponse(uid)
        override suspend fun profileDetail(uid: String) = detailResponse(uid)
        override suspend fun albums(uid: String) = albumsResponse(uid)
        override suspend fun albumPhotos(containerId: String, cursor: String) =
            photosResponse(containerId, cursor)
        override suspend fun videos(uid: String, cursor: String) = videosResponse(uid, cursor)
        override suspend fun articles(uid: String, page: Int) = articlesResponse(uid, page)
        override suspend fun follow(uid: String) = followResponse(uid)
        override suspend fun unfollow(uid: String) = unfollowResponse(uid)
        override suspend fun following(uid: String, page: Int, tab: FollowingListTab) =
            followingResponse(uid, page, tab)
        override suspend fun followers(uid: String, page: Int) = followersResponse(uid, page)
    }
}
