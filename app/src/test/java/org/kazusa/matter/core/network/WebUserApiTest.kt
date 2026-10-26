package org.kazusa.matter.core.network

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.WeiboUserDto

/**
 * 用户主页链路走网页域：资料 `ajax/profile/info`、微博列表 `ajax/statuses/mymblog`。
 */
class WebUserApiTest {

    @Test
    fun userTimelineUsesWebMymblog() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("weibo.com", request.url.host)
            assertEquals("/ajax/statuses/mymblog", request.url.encodedPath)
            assertEquals("6571287027", request.url.parameters["uid"])
            assertEquals("2", request.url.parameters["page"])
            assertEquals("0", request.url.parameters["feature"])
            respond(
                """{"ok":1,"data":{"list":[{"id":5000000000000001,"text":"第一条",
                   "user":{"id":6571287027,"screen_name":"某人"}}],"since_id":0,"total":"1"}}"""
            )
        }
        try {
            val result = WeiboApiService(client).getUserTimeline("6571287027", page = 2, cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val page = (result as ApiResult.Success<UserTimelineResult>).data
            assertEquals(listOf(5000000000000001L), page.statuses.map { it.id })
            assertEquals("某人", page.user?.screenName)
            assertEquals(1, page.total)
            assertTrue(page.hasMore)
        } finally {
            client.close()
        }
    }

    @Test
    fun emptyUserTimelineIsANormalEmptyPage() {
        val result = decodeWebUserStatusPage(
            """{"ok":1,"data":{"list":[],"since_id":0,"total":"0"}}"""
        ) as ApiResult.Success
        assertTrue(result.data.statuses.isEmpty())
        assertFalse(result.data.hasMore)
        assertEquals(0, result.data.total)
    }

    @Test
    fun userTimelineSurfacesServerError() {
        val error = decodeWebUserStatusPage("""{"ok":0,"msg":"用户不存在"}""") as ApiResult.Error
        assertEquals("用户不存在", error.message)
        assertTrue(decodeWebUserStatusPage("""{"ok":1}""") is ApiResult.Error)
    }

    @Test
    fun profileComesFromWebProfileInfo() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("weibo.com", request.url.host)
            assertEquals("/ajax/profile/info", request.url.encodedPath)
            assertEquals("6571287027", request.url.parameters["uid"])
            respond(
                """{"ok":1,"data":{"user":{"id":6571287027,"screen_name":"某人",
                   "followers_count":120,"friends_count":30,"description":"简介"},
                   "tabList":[]}}"""
            )
        }
        try {
            val result = WeiboApiService(client).getUserProfile("6571287027", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val user = (result as ApiResult.Success<WeiboUserDto>).data
            assertEquals(6571287027L, user.id)
            assertEquals("某人", user.screenName)
            assertEquals("120", user.followersCount)
            assertEquals("简介", user.description)
        } finally {
            client.close()
        }
    }
}
