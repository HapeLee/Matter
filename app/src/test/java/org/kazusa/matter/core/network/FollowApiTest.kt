package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关注 / 取关的请求形状：m 站 `friendships/destroy` 已下线（`errno 404`「链接…无效」），
 * 关注与取关统一走 weibo.com 网页端表单协议。注意取关端点是微博上游的拼写
 * `destory`（正确拼写的路由不存在），参数也不对称：关注 `friend_uid`，取关 `uid`。
 */
class FollowApiTest {

    private fun configResponse() = """{"ok":1,"data":{"login":true,"st":"token"}}"""

    @Test
    fun followPostsFriendUidToTheDesktopEndpoint() = runBlocking {
        val requests = mutableListOf<String>()
        var form: Parameters? = null
        var xsrf: String? = null
        val client = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.encodedPath == "/api/config" -> respond(configResponse())
                request.url.host == "weibo.com" && request.url.encodedPath == "/" ->
                    respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
                request.url.encodedPath == "/ajax/friendships/create" -> {
                    form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    xsrf = request.headers["X-XSRF-TOKEN"]
                    respond("""{"ok":1}""")
                }
                else -> respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).followUser("123", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            assertEquals("123", form?.get("friend_uid"))
            assertEquals("abc", xsrf)
            // 取移动端 st 的引导请求 → 网页域取 XSRF 令牌 → 表单提交关注
            assertEquals(
                listOf("GET weibo.com/", "POST weibo.com/ajax/friendships/create"),
                requests
            )
        } finally { client.close() }
    }

    @Test
    fun unfollowPostsUidToTheUpstreamDestoryTypoEndpoint() = runBlocking {
        val requests = mutableListOf<String>()
        var form: Parameters? = null
        var xsrf: String? = null
        val client = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when {
                request.url.encodedPath == "/api/config" -> respond(configResponse())
                request.url.host == "weibo.com" && request.url.encodedPath == "/" ->
                    respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
                request.url.encodedPath == "/ajax/friendships/destory" -> {
                    form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    xsrf = request.headers["X-XSRF-TOKEN"]
                    respond("""{"ok":1}""")
                }
                else -> respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).unfollowUser("123", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            assertEquals("123", form?.get("uid"))
            assertEquals("abc", xsrf)
            assertEquals(
                listOf("GET weibo.com/", "POST weibo.com/ajax/friendships/destory"),
                requests
            )
        } finally { client.close() }
    }

    @Test
    fun unfollowWithoutUsableSessionFailsBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond("""{"ok":1}""")
        })
        try {
            assertTrue(WeiboApiService(client).unfollowUser("123", cookie = "") is ApiResult.Error)
            assertEquals(0, requests)
        } finally { client.close() }
    }
}
