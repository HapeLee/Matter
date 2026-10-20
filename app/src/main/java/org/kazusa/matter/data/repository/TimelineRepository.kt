package org.kazusa.matter.data.repository

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.cache.TimelineCacheData
import org.kazusa.matter.data.cache.TimelineCacheManager
import org.kazusa.matter.data.cache.TimelinePosition
import org.kazusa.matter.data.cache.TimelinePositionManager
import kotlinx.coroutines.flow.first

class TimelineRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService,
    private val cacheManager: TimelineCacheManager,
    private val positionManager: TimelinePositionManager
) {

    /**
     * 获取首页或指定分组的微博列表
     */
    suspend fun getTimeline(
        gid: String? = null,
        page: Int = 1,
        maxId: Long? = null
    ): ApiResult<List<WeiboStatusDto>> {
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader

        val result: ApiResult<TimelineResponse> = if (gid.isNullOrBlank() || gid == "all") {
            apiService.getFriendsTimeline(cookie = cookie, page = page, maxId = maxId)
        } else {
            apiService.getGroupTimeline(cookie = cookie, gid = gid, page = page, maxId = maxId)
        }

        return when (result) {
            is ApiResult.Success -> {
                val list = result.data.statuses.ifEmpty { result.data.data?.statuses.orEmpty() }
                // 必须按 listKey 去重：时间线 LazyColumn 用的是 `key = { it.listKey }`，
                // 而「特别关注」这类分组会返回同一条微博多份（既在普通关注流里、又被特别关注标记），
                // 重复 key 会让 LazyColumn 直接抛
                // "IllegalArgumentException: Key ... was already used"，表现为切换分组即闪退。
                ApiResult.Success(list.distinctBy { it.listKey })
            }
            is ApiResult.Error -> ApiResult.Error(result.code, result.message, result.throwable)
            ApiResult.Loading -> ApiResult.Loading
        }
    }

    /** 读取本地缓存；不是当前账号写下的缓存直接视为没有（账号隔离）。 */
    suspend fun loadCache(gid: String?): TimelineCacheData? =
        cacheManager.loadCache(gid, currentUid())

    fun getScrollPosition(gid: String?): TimelinePosition = positionManager.getPosition(gid)

    fun saveScrollPosition(
        gid: String?,
        index: Int,
        offset: Int,
        postId: String?,
        synchronous: Boolean = false
    ) = positionManager.savePosition(gid, index, offset, postId, synchronous)

    suspend fun saveCache(
        gid: String?,
        posts: List<WeiboStatusDto>,
        page: Int,
        hasMore: Boolean,
        scrollIndex: Int,
        scrollOffset: Int,
        lastReadPostId: String?
    ) {
        positionManager.savePosition(gid, scrollIndex, scrollOffset, lastReadPostId, synchronous = false)
        cacheManager.saveCache(
            gid = gid,
            uid = currentUid(),
            posts = posts,
            page = page,
            hasMore = hasMore,
            scrollIndex = scrollIndex,
            scrollOffset = scrollOffset,
            lastReadPostId = lastReadPostId
        )
    }

    suspend fun clearCache(gid: String? = null) {
        positionManager.clearPosition(gid)
        cacheManager.clearCache(gid)
    }

    /** 当前登录账号；访客是空串，与任何登录账号天然区分。 */
    private suspend fun currentUid(): String = authPreferences.sessionFlow.first().uid
}
