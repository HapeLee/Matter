package org.kazusa.matter.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.SearchPageResult
import org.kazusa.matter.data.model.SearchResultItem
import org.kazusa.matter.data.model.SearchResultKind
import org.kazusa.matter.data.model.WeiboSearchTabDto
import org.kazusa.matter.data.model.WeiboStatusDto

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelTest {
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

    private fun keep(viewModel: SearchResultViewModel): SearchResultViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("search", vm)
            stores += store
        }
    }

    private fun item(id: Long) = SearchResultItem(
        kind = SearchResultKind.POST,
        status = WeiboStatusDto(id = id),
        stableKey = "post_$id"
    )

    @Test
    fun lateResponseFromPreviousKeywordNeverReplacesTheCurrentSearch() = runTest {
        val vm = keep(
            SearchResultViewModel(
                initialQuery = "旧关键词",
                recordSearch = {},
                savedStateHandle = SavedStateHandle(),
                searchPage = { keyword, _, _ ->
                    if (keyword == "旧关键词") {
                        // 旧请求很慢，且带了服务端分栏
                        delay(1_000)
                        ApiResult.Success(
                            SearchPageResult(
                                items = listOf(item(1)),
                                tabs = listOf(WeiboSearchTabDto(title = "热门", containerid = "10086")),
                                defaultTabIndex = 0
                            )
                        )
                    } else {
                        ApiResult.Success(SearchPageResult(items = listOf(item(2))))
                    }
                }
            )
        )

        vm.loadTab(0, pageToLoad = 1)
        runCurrent()

        vm.submitSearch("新关键词")
        vm.loadTab(0, pageToLoad = 1)
        advanceUntilIdle()

        assertEquals("新关键词", vm.uiState.value.currentQuery)
        // 旧关键词的分栏不能落到当前搜索上
        assertEquals(1, vm.uiState.value.tabs.size)
        assertEquals(FALLBACK_SEARCH_TAB_ID, vm.uiState.value.tabs.single().id)
        assertEquals(listOf(2L), vm.stateOf(vm.uiState.value.tabs.single().id).items.map { it.status?.id })
    }

    @Test
    fun refreshSupersedesInFlightPaginationSoOldPageIsNotAppended() = runTest {
        val vm = keep(
            SearchResultViewModel(
                initialQuery = "kw",
                recordSearch = {},
                savedStateHandle = SavedStateHandle(),
                searchPage = { _, page, _ ->
                    when (page) {
                        1 -> ApiResult.Success(SearchPageResult(items = listOf(item(1))))
                        else -> {
                            delay(1_000)
                            ApiResult.Success(SearchPageResult(items = listOf(item(2))))
                        }
                    }
                }
            )
        )
        val tabId = vm.uiState.value.tabs.single().id

        vm.loadTab(0, pageToLoad = 1)
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.stateOf(tabId).items.map { it.status?.id })

        vm.loadTab(0, pageToLoad = 2)
        runCurrent()
        assertTrue(vm.stateOf(tabId).isLoadingMore)

        vm.loadTab(0, pageToLoad = 1, isRefresh = true)
        advanceUntilIdle()

        val state = vm.stateOf(tabId)
        assertEquals(listOf(1L), state.items.map { it.status?.id })
        assertFalse(state.isLoadingMore)
        assertFalse(state.isRefreshing)
        assertEquals(1, state.page)
    }

    @Test
    fun pageFailureKeepsPaginationOpenAndRetriesTheSamePage() = runTest {
        val pages = mutableListOf<Int>()
        var failNextPage = true
        val vm = keep(
            SearchResultViewModel(
                initialQuery = "kw",
                recordSearch = {},
                savedStateHandle = SavedStateHandle(),
                searchPage = { _, page, _ ->
                    pages += page
                    when {
                        page == 1 -> ApiResult.Success(SearchPageResult(items = listOf(item(1))))
                        failNextPage -> {
                            failNextPage = false
                            ApiResult.Error(message = "网络断开")
                        }
                        else -> ApiResult.Success(SearchPageResult(items = listOf(item(2))))
                    }
                }
            )
        )
        val tabId = vm.uiState.value.tabs.single().id

        vm.loadTab(0, pageToLoad = 1)
        advanceUntilIdle()
        vm.loadTab(0, pageToLoad = 2)
        advanceUntilIdle()

        val failed = vm.stateOf(tabId)
        // 失败 ≠ 没有下一页：hasMore 保留，错误可重试
        assertTrue(failed.hasMore)
        assertNotNull(failed.errorMessage)
        assertEquals(listOf(1L), failed.items.map { it.status?.id })

        vm.loadTab(0, pageToLoad = 2)
        advanceUntilIdle()

        val recovered = vm.stateOf(tabId)
        assertNull(recovered.errorMessage)
        assertEquals(listOf(1L, 2L), recovered.items.map { it.status?.id })
        assertEquals(listOf(1, 2, 2), pages)
    }

    @Test
    fun clearedQueryRestoresAsClearedInsteadOfFallingBackToRouteQuery() = runTest {
        val vm = keep(
            SearchResultViewModel(
                initialQuery = "route",
                recordSearch = {},
                // 用户清空搜索后保存下来的是空字符串，必须与「从未保存过」区分开
                savedStateHandle = SavedStateHandle(mapOf("search_query" to "")),
                searchPage = { _, _, _ -> ApiResult.Success(SearchPageResult()) }
            )
        )
        assertEquals("", vm.uiState.value.currentQuery)
    }

    @Test
    fun queryAndSelectedTabSurviveProcessRestore() = runTest {
        val handle = SavedStateHandle(mapOf("search_query" to "恢复的关键词"))
        val vm = keep(
            SearchResultViewModel(
                initialQuery = "route",
                recordSearch = {},
                savedStateHandle = handle,
                searchPage = { _, _, _ -> ApiResult.Success(SearchPageResult()) }
            )
        )
        assertEquals("恢复的关键词", vm.uiState.value.currentQuery)

        vm.submitSearch("新输入")
        assertEquals("新输入", handle.get<String>("search_query"))
        assertEquals(FALLBACK_SEARCH_TAB_ID, handle.get<String>("search_tab_id"))
    }
}
