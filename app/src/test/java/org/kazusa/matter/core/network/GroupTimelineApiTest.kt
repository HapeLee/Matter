package org.kazusa.matter.core.network

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.TimelineResponse

/**
 * 分组时间线的接口路由：分组 id 来自 weibo.com `allGroups`，一律用同站的 web 接口读取。
 *
 * 路由规则按实测的 `mygroups?gid=…` 请求锁定：系统合成 gid 走 `unreadfriendstimeline` /
 * `friendstimeline`，其余（含自建分组、特别关注）走 `groupstimeline`。另外锁住两个曾经
 * 出过问题的细节：首屏不能带 `max_id`（空串会被判 400），`ok=1` + 空 `statuses` 是空分组
 * 的正常结果、不能当成接口失败去走 m 站兜底。
 */
class GroupTimelineApiTest {

    @Test
    fun routesGroupIdsLikeTheWebClient() {
        // 10001 + uid：全部关注
        assertEquals(GroupFeedRoute.PATH_ALL, resolveGroupFeedRoute("100011024125180916").path)
        // 11000 / 11001 / 11002 + uid：最新微博 / 原创 / 视频
        for (prefix in listOf("11000", "11001", "11002")) {
            val route = resolveGroupFeedRoute(prefix + "1024125180916")
            assertEquals(GroupFeedRoute.PATH_FILTERED, route.path)
            assertTrue(route.incremental)
            assertTrue(route.fid)
        }
        // 好友圈（10009）与其它系统分组走网页分组时间线
        for (gid in listOf("100091024125180916", "100096571287027", "100131024125180916")) {
            val route = resolveGroupFeedRoute(gid)
            assertEquals(GroupFeedRoute.PATH_GROUP, route.path)
            assertEquals(25, route.count)
            assertFalse(route.incremental)
            assertFalse(route.fid)
        }
        // 真实分组 id（自建分组 / 特别关注）：网页端同样是 groupstimeline
        for (gid in listOf("4249162129677215", "5141402056917762", "4151808052522725")) {
            val route = resolveGroupFeedRoute(gid)
            assertEquals(GroupFeedRoute.PATH_GROUP, route.path)
            assertEquals(25, route.count)
            assertFalse(route.incremental)
            assertFalse(route.fid)
        }
    }

    @Test
    fun realGroupsUseWebGroupFeed() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("weibo.com", request.url.host)
            assertEquals("/ajax/feed/groupstimeline", request.url.encodedPath)
            assertEquals("4249162129677215", request.url.parameters["list_id"])
            assertEquals("25", request.url.parameters["count"])
            assertEquals("1", request.url.parameters["fast_refresh"])
            assertEquals("987654321", request.url.parameters["max_id"])
            assertNull(request.url.parameters["since_id"])
            assertNull(request.url.parameters["fid"])
            respond("""{"ok":1,"statuses":[{"id":"555","text":"自建分组"}]}""")
        }
        try {
            val result = WeiboApiService(client)
                .getGroupTimeline("SUB=test", "4249162129677215", page = 2, maxId = 987654321L)
            assertTrue(result is ApiResult.Success)
            val body = (result as ApiResult.Success<TimelineResponse>).data
            assertEquals(listOf(555L), body.statuses.map { it.id })
        } finally {
            client.close()
        }
    }

    @Test
    fun filteredGroupsSendFidAndSinceIdWhileGroupFeedsUseFastRefresh() = runBlocking {
        var client = cookieAwareClient { request ->
            assertEquals("/ajax/feed/friendstimeline", request.url.encodedPath)
            assertEquals("110001024125180916", request.url.parameters["list_id"])
            assertEquals("110001024125180916", request.url.parameters["fid"])
            assertEquals("0", request.url.parameters["since_id"])
            assertEquals("25", request.url.parameters["count"])
            assertNull(request.url.parameters["fast_refresh"])
            respond("""{"ok":1,"statuses":[{"id":"101","text":"最新"}],"total_number":2}""")
        }
        try {
            val result = WeiboApiService(client).getGroupTimeline("SUB=test", "110001024125180916")
            assertTrue(result is ApiResult.Success)
            val body = (result as ApiResult.Success<TimelineResponse>).data
            assertEquals(listOf(101L), body.statuses.map { it.id })
            assertEquals(2, body.totalNumber)
        } finally {
            client.close()
        }

        client = cookieAwareClient { request ->
            assertEquals("/ajax/feed/groupstimeline", request.url.encodedPath)
            assertEquals("100091024125180916", request.url.parameters["list_id"])
            assertEquals("1", request.url.parameters["fast_refresh"])
            assertNull(request.url.parameters["since_id"])
            assertNull(request.url.parameters["fid"])
            respond("""{"ok":1,"data":{"statuses":[{"id":"201","text":"互关"}]}}""")
        }
        try {
            val result = WeiboApiService(client).getGroupTimeline("SUB=test", "100091024125180916")
            assertTrue(result is ApiResult.Success)
            val body = (result as ApiResult.Success<TimelineResponse>).data
            assertEquals(listOf(201L), body.statuses.map { it.id })
        } finally {
            client.close()
        }
    }

    @Test
    fun firstPageOmitsMaxId() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("/ajax/feed/groupstimeline", request.url.encodedPath)
            // 首屏传 max_id 空串会被服务端判 400，必须完全不带这个参数
            assertNull(request.url.parameters["max_id"])
            assertEquals("1", request.url.parameters["fast_refresh"])
            respond("""{"ok":1,"statuses":[{"id":"321","text":"特别关注"}]}""")
        }
        try {
            val result = WeiboApiService(client).getGroupTimeline("SUB=test", "4249162129677215")
            assertTrue(result is ApiResult.Success)
            assertEquals(
                listOf(321L),
                (result as ApiResult.Success<TimelineResponse>).data.statuses.map { it.id }
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun emptyWebGroupFeedIsNotTreatedAsFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val client = cookieAwareClient { request ->
            calls += request.url.host + request.url.encodedPath
            // ok=1 + 空 statuses 表示该分组确实没有微博（空分组 total_number=0）
            respond("""{"ok":1,"statuses":[],"total_number":0}""")
        }
        try {
            val service = WeiboApiService(client)
            val result = service.getGroupTimeline("SUB=test", "5146471210681259")
            assertTrue(result is ApiResult.Success)
            val empty = (result as ApiResult.Success<TimelineResponse>).data
            assertTrue(empty.statuses.isEmpty())
            assertEquals(0, empty.totalNumber)
            // 空结果不触发 m 站兜底，也不把 gid 记成 mobileOnly
            service.getGroupTimeline("SUB=test", "5146471210681259")
            assertEquals(
                listOf("weibo.com/ajax/feed/groupstimeline", "weibo.com/ajax/feed/groupstimeline"),
                calls
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun failedWebGroupFeedSurfacesErrorWithoutMobileFallback() = runBlocking {
        val calls = mutableListOf<String>()
        val client = cookieAwareClient { request ->
            calls += request.url.host + request.url.encodedPath
            respond("""{"ok":0,"message":"请登录后使用"}""", status = io.ktor.http.HttpStatusCode.Forbidden)
        }
        try {
            val result = WeiboApiService(client).getGroupTimeline("SUB=test", "100091024125180916")
            assertTrue(result is ApiResult.Error)
            // 失败如实上报，不再回退 m 站
            assertEquals("加载分组动态失败: HTTP 403 Forbidden", (result as ApiResult.Error).message)
            assertEquals(listOf("weibo.com/ajax/feed/groupstimeline"), calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun homeTimelineUsesTheAllGroupsWebFeed() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("weibo.com", request.url.host)
            // 首页 = 「全部关注」分组：10001 + uid
            assertEquals("/ajax/feed/unreadfriendstimeline", request.url.encodedPath)
            assertEquals("10001999999999", request.url.parameters["list_id"])
            assertEquals("15", request.url.parameters["count"])
            assertEquals("0", request.url.parameters["since_id"])
            assertNull(request.url.parameters["max_id"])
            respond("""{"ok":1,"statuses":[{"id":"9001","text":"首页"}],"total_number":5}""")
        }
        try {
            val result = WeiboApiService(client).getFriendsTimeline("SUB=test", uid = "999999999")
            assertTrue(result is ApiResult.Success)
            val body = (result as ApiResult.Success<TimelineResponse>).data
            assertEquals(listOf(9001L), body.statuses.map { it.id })
            assertEquals(5, body.totalNumber)
        } finally {
            client.close()
        }
    }

    @Test
    fun pagingSendsCursorAndServerMessageIsSurfaced() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("987654321", request.url.parameters["max_id"])
            respond("""{"ok":0,"msg":"分组不存在"}""")
        }
        try {
            val result = WeiboApiService(client)
                .getGroupTimeline("SUB=test", "100091024125180916", page = 2, maxId = 987654321L)
            assertTrue(result is ApiResult.Error)
            val error = result as ApiResult.Error
            assertEquals("分组不存在", error.message)
            assertEquals(0, error.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun missingLoginIsRejectedWithoutNetwork() = runBlocking {
        val client = cookieAwareClient { throw AssertionError("未登录不应发起请求") }
        try {
            val result = WeiboApiService(client).getGroupTimeline("", "100091024125180916")
            assertTrue(result is ApiResult.Error)
            val error = result as ApiResult.Error
            assertEquals(401, error.code)
        } finally {
            client.close()
        }
    }
}
