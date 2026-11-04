package org.kazusa.matter.data.repository

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import kotlinx.coroutines.CancellationException
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
    private val positionManager: TimelinePositionManager,
    private val followRepository: FollowRepository
) {

    /**
     * 获取首页或指定分组的微博列表
     */
    suspend fun getTimeline(
        gid: String? = null,
        page: Int = 1,
        maxId: Long? = null
    ): ApiResult<FollowTimelinePage> {
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader

        return loadFollowTimelinePage(gid, session.uid, maxId, sessionIsCurrent = {
            val current = authPreferences.sessionFlow.first()
            current.uid == session.uid && current.cookieHeader == cookie
        }, followingOf = followRepository::followingOf) { cursor ->
            if (gid.isNullOrBlank() || gid == "all") {
                // 首页与「全部关注」分组是同一个网页端点（gid = 10001 + uid）。
                apiService.getFriendsTimeline(cookie = cookie, maxId = cursor, uid = session.uid)
            } else {
                apiService.getGroupTimeline(cookie = cookie, gid = gid, page = page, maxId = cursor)
            }
        }
    }

    /** 读取本地缓存；不是当前账号写下的缓存直接视为没有（账号隔离）。 */
    suspend fun loadCache(gid: String?): TimelineCacheData? {
        val uid = currentUid()
        val cached = cacheManager.loadCache(gid, uid) ?: return null
        if (currentUid() != uid) throw CancellationException("账号已切换")
        return cached.copy(posts = cached.posts.filter {
            !filtersUnfollowedAuthors(gid) || keepFollowTimelinePost(it, uid, followRepository::followingOf)
        })
    }

    suspend fun getScrollPosition(gid: String?): TimelinePosition = positionManager.getPosition(gid)

    fun saveScrollPosition(
        gid: String?,
        index: Int,
        offset: Int,
        postId: String?,
        synchronous: Boolean = false
    ) = positionManager.savePosition(gid, index, offset, postId, synchronous)

    suspend fun saveCache(
        gid: String?,
        ownerUid: String,
        posts: List<WeiboStatusDto>,
        page: Int,
        hasMore: Boolean,
        scrollIndex: Int,
        scrollOffset: Int,
        lastReadPostId: String?,
        nextMaxId: Long? = null
    ) {
        if (currentUid() != ownerUid) return
        positionManager.savePosition(gid, scrollIndex, scrollOffset, lastReadPostId, synchronous = false)
        cacheManager.saveCache(
            gid = gid,
            uid = ownerUid,
            posts = posts,
            page = page,
            hasMore = hasMore,
            scrollIndex = scrollIndex,
            scrollOffset = scrollOffset,
            lastReadPostId = lastReadPostId,
            nextMaxId = nextMaxId
        )
    }

    suspend fun clearCache(gid: String? = null) {
        positionManager.clearPosition(gid)
        cacheManager.clearCache(gid)
    }

    /** 当前登录账号；访客是空串，与任何登录账号天然区分。 */
    private suspend fun currentUid(): String = authPreferences.sessionFlow.first().uid
}
