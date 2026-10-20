package org.kazusa.matter.ui.profile

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.WeiboStatusDto

@OptIn(ExperimentalCoroutinesApi::class)
class MyPostsViewModelTest {
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

    private fun keep(viewModel: MyPostsViewModel): MyPostsViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("myPosts", vm)
            stores += store
        }
    }

    private fun post(id: Long) = WeiboStatusDto(id = id)

    @Test
    fun paginationFailureKeepsHasMoreAndRetryRequestsTheSamePage() = runTest {
        val pages = mutableListOf<Int>()
        var failNextPage = true
        val vm = keep(
            MyPostsViewModel("uid") { page ->
                pages += page
                when {
                    page == 1 -> ApiResult.Success(
                        UserTimelineResult(statuses = listOf(post(1)), total = 5, hasMore = true)
                    )
                    failNextPage -> {
                        failNextPage = false
                        ApiResult.Error(message = "网络断开")
                    }
                    else -> ApiResult.Success(
                        UserTimelineResult(statuses = listOf(post(2)), total = 5, hasMore = true)
                    )
                }
            }
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        // 失败只记录错误，不能把「还有下一页」误判成到底
        assertEquals("网络断开", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.hasMore)
        assertEquals(listOf(1L), vm.uiState.value.posts.map { it.id })

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(listOf(1L, 2L), vm.uiState.value.posts.map { it.id })
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun emptyPageEndsPagination() = runTest {
        val vm = keep(
            MyPostsViewModel("uid") { page ->
                if (page == 1) {
                    ApiResult.Success(UserTimelineResult(statuses = listOf(post(1)), hasMore = true))
                } else {
                    ApiResult.Success(UserTimelineResult(statuses = emptyList(), hasMore = true))
                }
            }
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun guestSessionShowsLoginHintWithoutRequesting() = runTest {
        var requested = false
        val vm = keep(
            MyPostsViewModel("guest") {
                requested = true
                ApiResult.Success(UserTimelineResult())
            }
        )

        vm.loadIfNeeded()
        advanceUntilIdle()

        assertTrue(vm.isGuest)
        assertFalse(requested)
        assertEquals("登录后才能查看我的微博", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isInitialLoading)
    }
}
