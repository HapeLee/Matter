package org.kazusa.matter.core.di

import android.content.Context
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.network.KtorClientFactory
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.data.repository.GroupRepository
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.data.repository.TimelineRepository

interface AppContainer {
    val authPreferences: AuthPreferences
    val themePreferences: org.kazusa.matter.ui.theme.ThemePreferences
    val mediaPreferences: MediaPreferences
    val apiService: WeiboApiService
    val authRepository: AuthRepository
    val timelineRepository: TimelineRepository
    val groupRepository: GroupRepository
    val browsingHistoryRepository: BrowsingHistoryRepository
    val searchHistoryRepository: SearchHistoryRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    private val httpClient by lazy {
        KtorClientFactory.createClient()
    }

    override val authPreferences: AuthPreferences by lazy {
        AuthPreferences(context)
    }

    override val themePreferences: org.kazusa.matter.ui.theme.ThemePreferences by lazy {
        org.kazusa.matter.ui.theme.ThemePreferences(context)
    }

    override val mediaPreferences: MediaPreferences by lazy {
        MediaPreferences(context)
    }

    override val apiService: WeiboApiService by lazy {
        WeiboApiService(httpClient, authPreferences)
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepository(authPreferences, apiService)
    }

    private val timelineCacheManager by lazy {
        org.kazusa.matter.data.cache.TimelineCacheManager(context)
    }

    private val timelinePositionManager by lazy {
        org.kazusa.matter.data.cache.TimelinePositionManager(context)
    }

    override val timelineRepository: TimelineRepository by lazy {
        TimelineRepository(authPreferences, apiService, timelineCacheManager, timelinePositionManager)
    }

    override val groupRepository: GroupRepository by lazy {
        GroupRepository(authPreferences, apiService)
    }

    private val database by lazy {
        org.kazusa.matter.data.local.MatterDatabase.getInstance(context)
    }

    override val browsingHistoryRepository: BrowsingHistoryRepository by lazy {
        BrowsingHistoryRepository(
            dao = database.browsingHistoryDao(),
            authPreferences = authPreferences,
            json = KtorClientFactory.jsonConfig
        )
    }

    override val searchHistoryRepository: SearchHistoryRepository by lazy {
        SearchHistoryRepository(
            dao = database.searchHistoryDao(),
            authPreferences = authPreferences
        )
    }
}
