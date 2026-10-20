package org.kazusa.matter.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * 关注状态跨页面共享：列表与博主主页读写的是同一个 [FollowRepository]。
 *
 * 对应回归场景：从「我的关注」进入主页取消关注，再返回列表，卡片不能还停在「已关注」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowStateSharingTest {

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

    private fun <T : ViewModel> keep(key: String, viewModel: T): T = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put(key, vm)
            stores += store
        }
    }

    private fun userList(repository: UserRepository, followRepository: FollowRepository) = keep(
        "list",
        UserListViewModel(
            uid = "100",
            kind = UserListKind.FOLLOWING,
            userRepository = repository,
            followRepository = followRepository
        )
    )

    private fun userProfile(repository: UserRepository, followRepository: FollowRepository) = keep(
        "profile",
        UserProfileViewModel(
            uid = TARGET,
            recordUser = {},
            userRepository = repository,
            followRepository = followRepository
        )
    )

    @Test
    fun unfollowOnProfileIsVisibleInTheFollowingList() = runTest {
        val repository = StubUserRepository(serverFollowing = true)
        val followRepository = FollowRepository(repository::follow, repository::unfollow)
        val list = userList(repository, followRepository)
        val profile = userProfile(repository, followRepository)
        profile.loadIfNeeded()
        advanceUntilIdle()

        assertTrue(profile.uiState.value.follow?.following == true)
        assertEquals(true, list.uiState.value.followingOf(TARGET))

        // 在主页取消关注
        profile.unfollow()
        advanceUntilIdle()

        assertFalse(profile.uiState.value.follow?.following == true)
        // 返回列表：卡片状态立刻跟着变，不需要等列表重新请求
        assertEquals(false, list.uiState.value.followingOf(TARGET))
    }

    @Test
    fun followInTheListIsVisibleOnTheProfile() = runTest {
        val repository = StubUserRepository(serverFollowing = false)
        val followRepository = FollowRepository(repository::follow, repository::unfollow)
        val list = userList(repository, followRepository)
        val profile = userProfile(repository, followRepository)
        profile.loadIfNeeded()
        advanceUntilIdle()

        assertFalse(profile.uiState.value.follow?.following == true)

        val card = repository.card
        list.onFollowClick(card, currentlyFollowing = false)
        advanceUntilIdle()

        assertEquals(true, list.uiState.value.followingOf(TARGET))
        assertTrue(profile.uiState.value.follow?.following == true)
    }

    /** 关注 / 粉丝列表与博主资料共用同一个 uid；只模拟本测试会用到的接口。 */
    private class StubUserRepository(
        private val serverFollowing: Boolean
    ) : UserRepository {

        val card = WeiboSearchCardInfo(
            user = WeiboUserDto(
                id = TARGET.toLong(),
                idStr = TARGET,
                screenName = "博主$TARGET",
                following = serverFollowing
            )
        )

        override suspend fun profile(uid: String): ApiResult<WeiboUserDto> = ApiResult.Success(
            WeiboUserDto(
                id = uid.toLongOrNull(),
                idStr = uid,
                screenName = "博主$uid",
                following = serverFollowing
            )
        )

        override suspend fun profileTimeline(uid: String, page: Int): ApiResult<UserTimelineResult> =
            ApiResult.Success(UserTimelineResult(statuses = emptyList(), hasMore = false))

        override suspend fun following(
            uid: String,
            page: Int,
            tab: FollowingListTab
        ): ApiResult<WeiboUserListPage> = ApiResult.Success(
            WeiboUserListPage(items = if (page == 1) listOf(card) else emptyList(), total = 1)
        )

        override suspend fun followers(uid: String, page: Int): ApiResult<WeiboUserListPage> =
            ApiResult.Success(WeiboUserListPage(items = emptyList(), total = 0))

        override suspend fun follow(uid: String): ApiResult<Unit> = ApiResult.Success(Unit)

        override suspend fun unfollow(uid: String): ApiResult<Unit> = ApiResult.Success(Unit)

        // 首屏会顺带拉一次资料详情，失败也不影响主页主体，这里给个空成功
        override suspend fun profileDetail(uid: String): ApiResult<UserProfileDetail> =
            ApiResult.Success(UserProfileDetail())
        override suspend fun albums(uid: String): ApiResult<List<UserAlbum>> = notUsed()
        override suspend fun albumPhotos(containerId: String, cursor: String): ApiResult<AlbumPhotoPage> =
            notUsed()

        override suspend fun videos(uid: String, cursor: String): ApiResult<VideoFeedPage> = notUsed()
        override suspend fun articles(uid: String, page: Int): ApiResult<ArticleFeedPage> = notUsed()

        private fun <T> notUsed(): T = throw UnsupportedOperationException("本测试不应调用该接口")
    }

    private companion object {
        const val TARGET = "200"
    }
}
