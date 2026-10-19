package org.kazusa.matter.core.di

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.KtorClientFactory
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.cache.TimelineCacheManager
import org.kazusa.matter.data.cache.TimelinePositionManager
import org.kazusa.matter.data.local.MatterDatabase
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.GroupRepository
import org.kazusa.matter.data.repository.HotFeedRepository
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.data.repository.TimelineRepository
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.history.BrowsingHistoryViewModel
import org.kazusa.matter.ui.hotfeed.HotFeedViewModel
import org.kazusa.matter.ui.login.LoginViewModel
import org.kazusa.matter.ui.profile.UserListKind
import org.kazusa.matter.ui.profile.UserListViewModel
import org.kazusa.matter.ui.saved.SavedPostsViewModel
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.ui.timeline.TimelineViewModel

val dataModule = module {
    single { AuthPreferences(androidContext()) }
    single { ThemePreferences(androidContext()) }
    single { MediaPreferences(androidContext()) }
    single<Json> { KtorClientFactory.jsonConfig }
    single<HttpClient> { KtorClientFactory.createClient() } onClose { it?.close() }
    single { WeiboApiService(get(), get()) }

    single { MatterDatabase.getInstance(androidContext()) }
    single { get<MatterDatabase>().browsingHistoryDao() }
    single { get<MatterDatabase>().searchHistoryDao() }
    single { TimelineCacheManager(androidContext()) }
    single { TimelinePositionManager(androidContext()) }

    single { AuthRepository(get(), get()) }
    single { TimelineRepository(get(), get(), get(), get()) }
    single { GroupRepository(get(), get()) }
    single { HotFeedRepository(get(), get()) }
    single { BrowsingHistoryRepository(get(), get(), get()) }
    single { SearchHistoryRepository(get(), get()) }

    // 由导航根按会话请求并记忆；不能声明为应用单例，否则收藏状态会跨账号复用。
    factory { parameters -> FavoritesRepository(parameters.get<UserSession>(), get()) }
}

val viewModelModule = module {
    viewModel { LoginViewModel(get()) }
    viewModel { TimelineViewModel(get(), get()) }
    viewModel { GroupViewModel(get()) }
    viewModel { HotFeedViewModel(get()) }
    viewModel { BrowsingHistoryViewModel(get()) }
    viewModel { parameters -> SavedPostsViewModel(parameters.get<FavoritesRepository>(), get()) }
    viewModel { parameters ->
        UserListViewModel(
            uid = parameters.get<String>(),
            kind = parameters.get<UserListKind>(),
            apiService = parameters.get<WeiboApiService>()
        )
    }
}

val appModule = module {
    includes(dataModule, viewModelModule)
}
