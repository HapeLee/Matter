package org.kazusa.matter.ui.hotfeed

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
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotFeedPage
import org.kazusa.matter.data.model.WeiboStatusDto

@OptIn(ExperimentalCoroutinesApi::class)
class HotFeedViewModelTest {
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

    private fun keep(viewModel: HotFeedViewModel): HotFeedViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("hotFeed", vm)
            stores += store
        }
    }

    private fun post(id: Long) = WeiboStatusDto(id = id)

    private val category = HotFeedCategory(id = "1", containerId = "102803", title = "热门")

    @Test
    fun pageFailureKeepsCursorAndHasMoreSoUserCanRetryTheSamePage() = runTest {
        val cursors = mutableListOf<Pair<String, String>>()
        var failNextPage = true
        val vm = keep(
            HotFeedViewModel(
                getCategories = { ApiResult.Success(listOf(category)) },
                getFeed = { _, sinceId, maxId ->
                    cursors += sinceId to maxId
                    when {
                        sinceId.isEmpty() -> ApiResult.Success(
                            HotFeedPage(
                                posts = listOf(post(1)),
                                nextSinceId = "s1",
                                nextMaxId = "m1",
                                hasMore = true
                            )
                        )
                        failNextPage -> {
                            failNextPage = false
                            ApiResult.Error(message = "网络断开")
                        }
                        else -> ApiResult.Success(
                            HotFeedPage(
                                posts = listOf(post(2)),
                                nextSinceId = "s2",
                                nextMaxId = "m2",
                                hasMore = false
                            )
                        )
                    }
                }
            )
        )

        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.posts.size)
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()

        val failed = vm.uiState.value
        // 分页失败不等于「到底了」：游标与 hasMore 都必须保留，并单独暴露错误
        assertEquals("网络断开", failed.pageErrorMessage)
        assertTrue(failed.hasMore)
        assertFalse(failed.isLoadingMore)
        assertEquals(listOf(1L), failed.posts.map { it.id })
        assertNull(failed.errorMessage)

        vm.retryLoadMore()
        advanceUntilIdle()

        val recovered = vm.uiState.value
        assertNull(recovered.pageErrorMessage)
        assertEquals(listOf(1L, 2L), recovered.posts.map { it.id })
        assertFalse(recovered.hasMore)
        // 重试用的仍是失败那一页的游标，没有跳过它
        assertEquals(listOf("" to "", "s1" to "m1", "s1" to "m1"), cursors)
    }

    @Test
    fun pageErrorBlocksAutomaticLoadMoreUntilRetried() = runTest {
        var pageCalls = 0
        val vm = keep(
            HotFeedViewModel(
                getCategories = { ApiResult.Success(listOf(category)) },
                getFeed = { _, sinceId, _ ->
                    if (sinceId.isEmpty()) {
                        ApiResult.Success(
                            HotFeedPage(
                                posts = listOf(post(1)),
                                nextSinceId = "s1",
                                nextMaxId = "m1",
                                hasMore = true
                            )
                        )
                    } else {
                        pageCalls += 1
                        ApiResult.Error(message = "网络断开")
                    }
                }
            )
        )
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, pageCalls)

        // 触底会反复触发 loadMore：有分页错误时不应自动重试
        repeat(3) {
            vm.loadMore()
            advanceUntilIdle()
        }
        assertEquals(1, pageCalls)
    }

    @Test
    fun firstPageFailureDoesNotMarkPaginationAsFinished() = runTest {
        val vm = keep(
            HotFeedViewModel(
                getCategories = { ApiResult.Success(listOf(category)) },
                getFeed = { _, _, _ -> ApiResult.Error(message = "网络断开") }
            )
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("网络断开", state.errorMessage)
        assertTrue(state.hasMore)
        assertNull(state.pageErrorMessage)
    }

    @Test
    fun refreshFailureKeepsCursorSoPaginationStillWorks() = runTest {
        var failFirstPage = false
        val vm = keep(
            HotFeedViewModel(
                getCategories = { ApiResult.Success(listOf(category)) },
                getFeed = { _, sinceId, _ ->
                    if (sinceId.isEmpty()) {
                        if (failFirstPage) {
                            ApiResult.Error(message = "网络断开")
                        } else {
                            ApiResult.Success(
                                HotFeedPage(
                                    posts = listOf(post(1)),
                                    nextSinceId = "s1",
                                    nextMaxId = "m1",
                                    hasMore = true
                                )
                            )
                        }
                    } else {
                        ApiResult.Success(
                            HotFeedPage(
                                posts = listOf(post(2)),
                                nextSinceId = "s2",
                                nextMaxId = "m2",
                                hasMore = false
                            )
                        )
                    }
                }
            )
        )
        advanceUntilIdle()

        failFirstPage = true
        vm.refresh()
        advanceUntilIdle()

        // 刷新失败：旧列表保留，游标也必须一起保留，否则之后再也翻不动
        assertEquals("网络断开", vm.uiState.value.errorMessage)
        assertEquals(listOf(1L), vm.uiState.value.posts.map { it.id })
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.uiState.value.posts.map { it.id })
    }
}
