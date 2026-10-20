package org.kazusa.matter.ui.search

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.DEFAULT_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.HotSearchItem
import org.kazusa.matter.data.model.HotSearchSection
import org.kazusa.matter.data.model.REALTIME_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.repository.HotSearchRepository

@OptIn(ExperimentalCoroutinesApi::class)
class HotSearchBoardViewModelTest {
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

    private fun keep(viewModel: HotSearchBoardViewModel): HotSearchBoardViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("hotSearchBoard", vm)
            stores += store
        }
    }

    private fun board(title: String) = HotSearchBoard(
        channels = listOf(HotSearchChannel(name = "热搜", containerid = REALTIME_HOT_SEARCH_CONTAINER_ID)),
        sections = listOf(HotSearchSection(items = listOf(HotSearchItem(title = title))))
    )

    private val channelA = HotSearchChannel(name = "A 榜", containerid = "A")
    private val channelB = HotSearchChannel(name = "B 榜", containerid = "B")

    @Test
    fun emptyDefaultBoardFallsBackToRealtimeBoard() = runTest {
        val requested = mutableListOf<String>()
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { containerId, _ ->
                    requested += containerId
                    if (containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID) {
                        ApiResult.Success(HotSearchBoard())
                    } else {
                        ApiResult.Success(board("热门话题"))
                    }
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()

        assertEquals(
            listOf(DEFAULT_HOT_SEARCH_CONTAINER_ID, REALTIME_HOT_SEARCH_CONTAINER_ID),
            requested
        )
        assertEquals(REALTIME_HOT_SEARCH_CONTAINER_ID, vm.uiState.value.selectedContainerId)
        assertTrue(vm.uiState.value.hasLoaded)
        assertEquals("热门话题", vm.uiState.value.board.sections.single().items.single().title)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun failedDefaultBoardFallsBackToRealtimeBoard() = runTest {
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { containerId, _ ->
                    if (containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID) {
                        ApiResult.Error(message = "需要登录")
                    } else {
                        ApiResult.Success(board("热搜兜底"))
                    }
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()

        assertEquals(REALTIME_HOT_SEARCH_CONTAINER_ID, vm.uiState.value.selectedContainerId)
        assertEquals("热搜兜底", vm.uiState.value.board.sections.single().items.single().title)
        assertTrue(vm.uiState.value.hasLoaded)
    }

    @Test
    fun firstLoadFailureIsRetriedOnNextEntry() = runTest {
        var calls = 0
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { _, _ ->
                    calls += 1
                    ApiResult.Error(message = "网络断开")
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        // 默认榜 + 兜底热搜榜各一次，且没有标记成「已加载」
        assertEquals(2, calls)
        assertFalse(vm.uiState.value.hasLoaded)

        vm.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(4, calls)
    }

    @Test
    fun loadIfNeededOnlyLoadsOnce() = runTest {
        var calls = 0
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { _, _ ->
                    calls += 1
                    ApiResult.Success(board("热搜"))
                }
            )
        )

        vm.loadIfNeeded()
        advanceUntilIdle()
        vm.loadIfNeeded()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun switchingChannelDiscardsLateResponseFromPreviousBoard() = runTest {
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { containerId, _ ->
                    if (containerId == "A") {
                        delay(1_000)
                        ApiResult.Success(board("A 榜内容"))
                    } else {
                        ApiResult.Success(board("B 榜内容"))
                    }
                }
            )
        )

        vm.select(channelA)
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)

        vm.select(channelB)
        advanceUntilIdle()

        assertEquals("B", vm.uiState.value.selectedContainerId)
        assertEquals("B 榜内容", vm.uiState.value.board.sections.single().items.single().title)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun failedSwitchKeepsTheBoardThatIsAlreadyShown() = runTest {
        var failNext = false
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { containerId, _ ->
                    when {
                        containerId == "A" -> ApiResult.Success(board("A 榜内容"))
                        failNext -> ApiResult.Error(message = "网络断开")
                        else -> ApiResult.Success(board("B 榜内容"))
                    }
                }
            )
        )

        vm.select(channelA)
        advanceUntilIdle()
        assertEquals("A 榜内容", vm.uiState.value.board.sections.single().items.single().title)

        failNext = true
        vm.select(channelB)
        advanceUntilIdle()

        // 切榜失败不能把已有榜单清空
        assertEquals("B", vm.uiState.value.selectedContainerId)
        assertEquals("A 榜内容", vm.uiState.value.board.sections.single().items.single().title)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun refreshKeepsSelectedBoardAndUpdatesTimestamp() = runTest {
        var loadedAt = 0L
        val vm = keep(
            HotSearchBoardViewModel(
                FakeRepository { _, _ ->
                    loadedAt += 1
                    ApiResult.Success(board("第 $loadedAt 次"))
                }
            )
        )

        vm.select(channelA)
        advanceUntilIdle()
        val first = vm.uiState.value.lastLoadedAt
        assertTrue(first > 0)

        vm.refresh()
        advanceUntilIdle()

        assertEquals("A", vm.uiState.value.selectedContainerId)
        assertEquals("第 2 次", vm.uiState.value.board.sections.single().items.single().title)
        assertTrue(vm.uiState.value.lastLoadedAt >= first)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    private class FakeRepository(
        private val response: suspend (String, Pair<Double, Double>?) -> ApiResult<HotSearchBoard>
    ) : HotSearchRepository {
        override suspend fun board(
            containerId: String,
            latLon: Pair<Double, Double>?
        ): ApiResult<HotSearchBoard> = response(containerId, latLon)
    }
}
