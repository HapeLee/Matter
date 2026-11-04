package org.kazusa.matter.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.WeiboStatusDto

data class FollowTimelinePage(
    val posts: List<WeiboStatusDto>,
    val nextMaxId: Long?,
    val hasMore: Boolean,
    val ownerUid: String
)

/** 悄悄关注、群微博和 V+ 的成员关系不能用普通 following 判断。 */
internal fun filtersUnfollowedAuthors(gid: String?): Boolean =
    gid?.take(5) !in setOf("10005", "10010", "10013")

internal fun keepFollowTimelinePost(
    post: WeiboStatusDto,
    ownUid: String,
    followingOf: (String?) -> Boolean? = { null }
): Boolean {
    val author = post.user ?: return true
    val authorId = author.idStr?.takeIf { it.isNotBlank() } ?: author.id?.toString()
    // 只判断外层作者。关注的人转发了陌生人的微博仍然应该出现。
    return authorId == ownUid || (followingOf(authorId) ?: author.following) != false
}

/**
 * 过滤后空页继续使用原始页游标，不把空的可见结果当成服务端结束。
 * 限制一次操作的请求数；达到上限返回可继续加载的页，避免后台无限追页。
 */
internal suspend fun loadFollowTimelinePage(
    gid: String?,
    ownUid: String,
    maxId: Long?,
    sessionIsCurrent: suspend () -> Boolean,
    followingOf: (String?) -> Boolean? = { null },
    request: suspend (Long?) -> ApiResult<TimelineResponse>
): ApiResult<FollowTimelinePage> {
    var cursor = maxId
    val visited = mutableSetOf<Long>()
    maxId?.let(visited::add)
    repeat(5) {
        currentCoroutineContext().ensureActive()
        if (!sessionIsCurrent()) return ApiResult.Error(message = "账号已切换，请重新加载")
        when (val result = request(cursor)) {
            is ApiResult.Success -> {
                currentCoroutineContext().ensureActive()
                if (!sessionIsCurrent()) return ApiResult.Error(message = "账号已切换，请重新加载")
                val page = result.data
                val raw = page.statuses.ifEmpty { page.data?.statuses.orEmpty() }
                val next = page.nextMaxId?.takeIf { it > 0 }
                val hasMore = page.hasMore && next != null
                if (hasMore && !visited.add(next)) {
                    return ApiResult.Error(message = "微博分页游标未推进，请重试")
                }
                val visible = raw.filter {
                    !filtersUnfollowedAuthors(gid) || keepFollowTimelinePost(it, ownUid, followingOf)
                }.distinctBy { it.listKey }
                if (visible.isNotEmpty() || !hasMore) {
                    return ApiResult.Success(FollowTimelinePage(visible, next, hasMore, ownUid))
                }
                cursor = next
            }
            is ApiResult.Error -> return result
            ApiResult.Loading -> return ApiResult.Error(message = "微博加载未完成，请重试")
        }
    }
    return ApiResult.Success(FollowTimelinePage(emptyList(), cursor, hasMore = true, ownUid))
}
