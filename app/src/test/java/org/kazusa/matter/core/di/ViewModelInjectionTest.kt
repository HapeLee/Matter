package org.kazusa.matter.core.di

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.viewmodel.factory.KoinViewModelFactory
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.data.repository.*
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.ui.saved.SavedPostsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelInjectionTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    @OptIn(KoinInternalApi::class) // 仅测试：使用与 Compose 相同的 Factory 验证真实 SavedStateHandle 注入。
    fun graphResolvesAndViewModelsFollowTheirNavigationEntry() = runTest {
        val app = koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(appModule)
        }
        val firstOwner = EntryOwner()
        val secondOwner = EntryOwner()
        try {
            // 解析生产依赖图，覆盖 Android Context、数据库、DAO、缓存与 Repository 的绑定。
            app.koin.get<AuthRepository>()
            app.koin.get<TimelineRepository>()
            app.koin.get<GroupRepository>()
            app.koin.get<BrowsingHistoryRepository>()
            app.koin.get<SearchHistoryRepository>()
            val repository = app.koin.get<FavoritesRepository> { parametersOf(UserSession(uid = "guest")) }
            fun resolve(owner: EntryOwner): SavedPostsViewModel = ViewModelProvider.create(
                owner.viewModelStore,
                KoinViewModelFactory(SavedPostsViewModel::class, app.koin.scopeRegistry.rootScope,
                    params = { parametersOf(repository) }),
                owner.extras
            )[SavedPostsViewModel::class]
            val first = resolve(firstOwner)
            first.select(UserPostCollection.FAVORITES)
            assertSame(first, resolve(firstOwner))
            val second = resolve(secondOwner)
            assertNotSame(first, second)
            assertEquals(UserPostCollection.LIKES, second.uiState.value.selected)
            advanceUntilIdle()
            firstOwner.viewModelStore.clear()
            val replacement = resolve(firstOwner)
            assertNotSame(first, replacement)
        } finally {
            firstOwner.viewModelStore.clear(); secondOwner.viewModelStore.clear()
            app.close()
        }
    }

    private class EntryOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        override val viewModelStore = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        val extras = MutableCreationExtras().apply {
            this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@EntryOwner
            this[VIEW_MODEL_STORE_OWNER_KEY] = this@EntryOwner
        }
        init {
            controller.performAttach()
            enableSavedStateHandles()
            controller.performRestore(null)
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }
}
