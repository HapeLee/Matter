package org.kazusa.matter.ui.timeline

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.network.cookieAwareClient
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.kazusa.matter.data.cache.TimelineCacheManager
import org.kazusa.matter.data.cache.TimelinePositionManager
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.TimelineRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineFilteringTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val clients = mutableListOf<HttpClient>()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        clients.forEach(HttpClient::close)
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(handler: MockRequestHandler): TimelineViewModel {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AuthPreferences(context)
        preferences.clearSession()
        preferences.saveSession(uid = "owner-${System.nanoTime()}", rawCookie = "SUB=test")
        preferences.saveSelectedGroup("4249162129677215", "特别关注")
        val cache = TimelineCacheManager(context)
        cache.clearCache()
        val client = cookieAwareClient(handler).also(clients::add)
        val api = WeiboApiService(client)
        val auth = AuthRepository(preferences, api, object : WebSessionCookieStore {
            override suspend fun clearAll() = Unit
        })
        val follows = FollowRepository({ ApiResult.Success(Unit) }, { ApiResult.Success(Unit) })
        return TimelineViewModel(TimelineRepository(preferences, api, cache, TimelinePositionManager(context), follows), auth)
            .also { vm -> stores += ViewModelStore().also { it.put("timeline", vm) } }
    }

    private val initial = """{"ok":1,"statuses":[
        {"id":90,"user":{"id":2,"following":true}},
        {"id":80,"user":{"id":3,"following":false}}
        ],"max_id_str":"70"}"""

    @Test fun paginationFailureKeepsTheRawCursorAndRetriesTheSamePage() = runTest {
        var fail = true
        val cursors = mutableListOf<String?>()
        val vm = viewModel { request ->
            val cursor = request.url.parameters["max_id"]
            cursors += cursor
            when {
                cursor == null -> respond(initial)
                fail -> respond("""{"ok":0,"msg":"offline"}""")
                else -> respond("""{"ok":1,"statuses":[{"id":60,"user":{"following":true}}],"max_id":0}""")
            }
        }
        vm.uiState.first { !it.isLoading }
        assertEquals(listOf(90L), vm.uiState.value.posts.map { it.id })
        assertEquals(70L, vm.uiState.value.nextMaxId)
        vm.loadTimeline()
        val failed = vm.uiState.first { it.errorMessage != null }
        assertEquals(70L, failed.nextMaxId)
        assertTrue(failed.hasMore)
        assertEquals(1, failed.page)
        fail = false
        vm.loadTimeline()
        val completed = vm.uiState.first { it.posts.size == 2 }
        assertEquals(listOf(null, "70", "70"), cursors)
        assertEquals(listOf(90L, 60L), completed.posts.map { it.id })
        assertFalse(completed.hasMore)
    }

    @Test fun refreshBridgeFailurePreservesTheExistingListAndItsCursor() = runTest {
        var refreshing = false
        val vm = viewModel { request ->
            when {
                !refreshing -> respond(initial)
                request.url.parameters["max_id"] == null -> respond(
                    """{"ok":1,"statuses":[{"id":200,"user":{"following":true}}],"max_id":180}"""
                )
                else -> respond("""{"ok":0,"msg":"bridge failed"}""")
            }
        }
        vm.uiState.first { !it.isLoading }
        refreshing = true
        vm.loadTimeline(isRefresh = true)
        val failed = vm.uiState.first { it.errorMessage == "bridge failed" }
        assertEquals(listOf(90L), failed.posts.map { it.id })
        assertEquals(70L, failed.nextMaxId)
        assertTrue(failed.hasMore)
        assertFalse(failed.isRefreshing)
    }

    @Test fun refreshSupersedesSlowPaginationAndKeepsTheOldTailCursorWhenMerging() = runTest {
        var refreshing = false
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = viewModel { request ->
            when {
                request.url.parameters["max_id"] != null -> {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    respond("""{"ok":1,"statuses":[{"id":60}],"max_id":50}""")
                }
                refreshing -> respond("""{"ok":1,"statuses":[
                    {"id":200,"user":{"following":true}},
                    {"id":90,"user":{"following":true}}],"max_id":85}""")
                else -> respond(initial)
            }
        }
        vm.uiState.first { !it.isLoading }
        vm.loadTimeline()
        started.await()
        refreshing = true
        vm.loadTimeline(isRefresh = true)
        val fresh = vm.uiState.first { it.posts.firstOrNull()?.id == 200L }
        assertEquals(listOf(200L, 90L), fresh.posts.map { it.id })
        assertEquals(70L, fresh.nextMaxId)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(200L, 90L), vm.uiState.value.posts.map { it.id })
        assertEquals(70L, vm.uiState.value.nextMaxId)
    }

    @Test fun duplicateLoadEventsDoNotRequestTheSamePageConcurrently() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val vm = viewModel { request ->
            if (request.url.parameters["max_id"] == null) respond(initial) else {
                calls++
                started.complete(Unit)
                release.await()
                respond("""{"ok":1,"statuses":[],"max_id":0}""")
            }
        }
        vm.uiState.first { !it.isLoading }
        vm.loadTimeline()
        vm.loadTimeline()
        started.await()
        assertEquals(1, calls)
        release.complete(Unit)
        vm.uiState.first { !it.hasMore }
        assertEquals(1, calls)
    }

    @Test fun switchingGroupsRejectsTheOldFilteredPaginationResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = viewModel { request ->
            when {
                request.url.parameters["list_id"] == "5141402056917762" -> respond(
                    """{"ok":1,"statuses":[{"id":300,"user":{"following":true}}],"max_id":250}"""
                )
                request.url.parameters["max_id"] != null -> {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    respond("""{"ok":1,"statuses":[{"id":60}],"max_id":50}""")
                }
                else -> respond(initial)
            }
        }
        vm.uiState.first { !it.isLoading }
        vm.loadTimeline()
        started.await()
        vm.setGroup("5141402056917762", "新分组")
        val fresh = vm.uiState.first { it.posts.firstOrNull()?.id == 300L }
        assertEquals(250L, fresh.nextMaxId)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(300L), vm.uiState.value.posts.map { it.id })
        assertEquals(250L, vm.uiState.value.nextMaxId)
        assertFalse(vm.uiState.value.isLoadingMore)
    }
}
