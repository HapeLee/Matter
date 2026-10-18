package org.kazusa.matter.core.di

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.ui.saved.SavedPostsViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class AppModuleTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun networkAndJsonAreSharedAndTheNetworkClientClosesWithKoin() {
        val app = koinApplication { modules(appModule) }
        val client = app.koin.get<HttpClient>()
        try {
            assertSame(client, app.koin.get<HttpClient>())
            assertSame(app.koin.get<Json>(), app.koin.get<Json>())
            assertTrue(client.coroutineContext[Job]!!.isActive)
        } finally { app.close() }
        assertFalse(client.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun favoriteFactoriesNeverShareAccountState() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath == "/api/config")
                """{"ok":1,"data":{"login":true,"st":"token"}}""" else """{"ok":1}""")
        })
        val app = koinApplication {
            modules(appModule, module { single { WeiboApiService(client) } })
        }
        try {
            val firstSession = UserSession(uid = "first", sub = "test", isLoggedIn = true)
            val first = app.koin.get<FavoritesRepository> { parametersOf(firstSession) }
            val second = app.koin.get<FavoritesRepository> {
                parametersOf(firstSession.copy(uid = "second"))
            }
            assertTrue(first.toggle(WeiboStatusDto(id = 123)) is ApiResult.Success)
            assertEquals(true, first.state.value.overrides["123"])
            assertTrue(second.state.value.overrides.isEmpty())
            val fresh = app.koin.get<FavoritesRepository> { parametersOf(firstSession) }
            assertNotSame(first, fresh)
            assertTrue(fresh.state.value.overrides.isEmpty())
        } finally { app.close(); client.close() }
    }

    @Test
    fun savedPostsDefinitionAcceptsTheSessionRepositoryAndRestoredState() = runTest {
        val client = HttpClient(MockEngine { error("Guest must not make network requests") })
        val app = koinApplication { modules(appModule, module { single { WeiboApiService(client) } }) }
        val store = ViewModelStore()
        try {
            val repository = app.koin.get<FavoritesRepository> { parametersOf(UserSession(uid = "guest")) }
            val handle = SavedStateHandle(mapOf("collection" to UserPostCollection.FAVORITES.name))
            val vm = app.koin.get<SavedPostsViewModel> { parametersOf(repository, handle) }
            store.put("saved", vm)
            advanceUntilIdle()
            assertEquals(UserPostCollection.FAVORITES, vm.uiState.value.selected)
            assertNotNull(vm.uiState.value.lists.getValue(UserPostCollection.FAVORITES).error)
            vm.select(UserPostCollection.LIKES)
            assertEquals(UserPostCollection.LIKES.name, handle.get<String>("collection"))
        } finally { store.clear(); app.close(); client.close() }
    }
}
