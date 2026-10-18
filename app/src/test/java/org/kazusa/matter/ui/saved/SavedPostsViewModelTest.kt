package org.kazusa.matter.ui.saved

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.data.model.UserPostCollection.*
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoriteState

@OptIn(ExperimentalCoroutinesApi::class)
class SavedPostsViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    private fun keep(vm: SavedPostsViewModel): SavedPostsViewModel = vm.also {
        ViewModelStore().also { store -> store.put("saved", vm); stores += store }
    }

    @Test
    fun lateResponsesStayInTheirOwnTabAndSwitchingBackDoesNotReload() = runTest {
        val requests = mutableListOf<UserPostCollection>()
        val vm = keep(SavedPostsViewModel({ collection, _ ->
            requests += collection
            if (collection == LIKES) delay(1000)
            ApiResult.Success(UserPostsPage(listOf(WeiboStatusDto(id = if (collection == LIKES) 1 else 2)), null))
        }, MutableStateFlow(FavoriteState())))
        runCurrent()
        vm.select(FAVORITES)
        runCurrent()
        assertEquals(2L, vm.uiState.value.lists.getValue(FAVORITES).posts.single().id)
        advanceUntilIdle()
        assertEquals(FAVORITES, vm.uiState.value.selected)
        assertEquals(1L, vm.uiState.value.lists.getValue(LIKES).posts.single().id)
        vm.select(LIKES)
        advanceUntilIdle()
        assertEquals(listOf(LIKES, FAVORITES), requests)
    }

    @Test
    fun failedPaginationRetriesTheServerPageAndRetainsLoadedPosts() = runTest {
        val pages = mutableListOf<Int>()
        var failed = false
        val vm = keep(SavedPostsViewModel({ _, page ->
            pages += page
            if (page == 7 && !failed) { failed = true; ApiResult.Error(message = "offline") }
            else ApiResult.Success(UserPostsPage(
                if (page == 1) listOf(WeiboStatusDto(id = 1)) else listOf(WeiboStatusDto(id = 1), WeiboStatusDto(id = 2)),
                if (page == 1) 7 else null))
        }, MutableStateFlow(FavoriteState())))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.lists.getValue(LIKES).posts.size)
        assertEquals("offline", vm.uiState.value.lists.getValue(LIKES).error)
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 7, 7), pages)
        assertEquals(listOf(1L, 2L), vm.uiState.value.lists.getValue(LIKES).posts.map { it.id })
        assertNull(vm.uiState.value.lists.getValue(LIKES).nextPage)
    }

    @Test
    fun favoriteChangesReloadOnlyFavoritesAndKeepLikedPosts() = runTest {
        val favorites = MutableStateFlow(FavoriteState())
        val requests = mutableListOf<UserPostCollection>()
        val vm = keep(SavedPostsViewModel({ collection, _ ->
            requests += collection
            val posts = if (collection == FAVORITES && favorites.value.revision > 0) emptyList()
                else listOf(WeiboStatusDto(id = 1))
            ApiResult.Success(UserPostsPage(posts, null))
        }, favorites))
        advanceUntilIdle()
        vm.select(FAVORITES)
        advanceUntilIdle()
        vm.select(LIKES)
        favorites.value = FavoriteState(overrides = mapOf("1" to false), revision = 1)
        advanceUntilIdle()
        assertEquals(listOf(LIKES, FAVORITES, FAVORITES), requests)
        assertTrue(vm.uiState.value.lists.getValue(FAVORITES).posts.isEmpty())
        assertEquals(1L, vm.uiState.value.lists.getValue(LIKES).posts.single().id)
    }

    @Test
    fun refreshCancelsAnOlderResponseWithoutClearingTheNewLoadingState() = runTest {
        var requests = 0
        val vm = keep(SavedPostsViewModel({ _, _ ->
            val request = ++requests
            if (request == 1) try { delay(1000) } catch (_: CancellationException) { /* simulate a late response */ }
            ApiResult.Success(UserPostsPage(listOf(WeiboStatusDto(id = request.toLong())), null))
        }, MutableStateFlow(FavoriteState())))
        runCurrent()
        vm.refresh()
        advanceUntilIdle()
        val state = vm.uiState.value.lists.getValue(LIKES)
        assertEquals(2L, state.posts.single().id)
        assertFalse(state.refreshing)
    }

    @Test
    fun savedSelectionLoadsOnlyTheRestoredCollection() = runTest {
        val requests = mutableListOf<UserPostCollection>()
        val handle = SavedStateHandle(mapOf("collection" to FAVORITES.name))
        val vm = keep(SavedPostsViewModel({ collection, _ ->
            requests += collection
            ApiResult.Success(UserPostsPage(emptyList(), null))
        }, MutableStateFlow(FavoriteState()), handle))
        advanceUntilIdle()
        assertEquals(FAVORITES, vm.uiState.value.selected)
        assertEquals(listOf(FAVORITES), requests)
        vm.select(LIKES)
        assertEquals(LIKES.name, handle.get<String>("collection"))
    }

    @Test
    fun refreshFailureKeepsExistingPostsAndRetryStartsFromPageOne() = runTest {
        val pages = mutableListOf<Int>()
        val vm = keep(SavedPostsViewModel({ _, page ->
            pages += page
            if (pages.size == 2) ApiResult.Error(message = "offline")
            else ApiResult.Success(UserPostsPage(listOf(WeiboStatusDto(id = pages.size.toLong())), 2))
        }, MutableStateFlow(FavoriteState())))
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.lists.getValue(LIKES).refreshFailed)
        assertEquals(1L, vm.uiState.value.lists.getValue(LIKES).posts.single().id)
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 1, 1), pages)
        assertEquals(3L, vm.uiState.value.lists.getValue(LIKES).posts.single().id)
        assertNull(vm.uiState.value.lists.getValue(LIKES).error)
    }
}
