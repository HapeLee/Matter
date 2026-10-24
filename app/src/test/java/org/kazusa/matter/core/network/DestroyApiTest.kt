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

class DestroyApiTest {

    private fun configResponse() = """{"ok":1,"data":{"login":true,"st":"token"}}"""

    @Test
    fun commentDeletionPostsCidAndStToTheMobileEndpoint() = runBlocking {
        var form: Parameters? = null
        var xsrf: String? = null
        var referer: String? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/config") {
                respond(configResponse())
            } else {
                assertEquals("/comments/destroy", request.url.encodedPath)
                form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                xsrf = request.headers["X-XSRF-TOKEN"]
                referer = request.headers["Referer"]
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).destroyComment("99", cid = 123, cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            assertEquals("123", form?.get("cid"))
            assertEquals("token", form?.get("st"))
            assertEquals("token", xsrf)
            assertEquals("https://m.weibo.cn/detail/99", referer)
        } finally { client.close() }
    }

    @Test
    fun rateLimitedCommentDeletionEntersCooldownWithoutRetrying() = runBlocking {
        var deletes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/config") {
                respond(configResponse())
            } else {
                deletes += 1
                respond("""{"ok":0,"msg":"操作频繁，请稍后再试"}""")
            }
        })
        try {
            val api = WeiboApiService(client)
            val first = api.destroyComment("99", cid = 1, cookie = "SUB=test")
            assertTrue(first is ApiResult.Error && first.code == 429)
            val second = api.destroyComment("99", cid = 2, cookie = "SUB=test")
            assertTrue(second is ApiResult.Error && second.code == 429)
            assertEquals(1, deletes)
        } finally { client.close() }
    }

    @Test
    fun postDeletionUsesTheDesktopAjaxEndpointWithXsrfToken() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        var jsonBody: String? = null
        var xsrf: String? = null
        val client = HttpClient(MockEngine { request ->
            requests += request.method.value to request.url.host + request.url.encodedPath
            when {
                request.url.encodedPath == "/api/config" -> respond(configResponse())
                request.url.host == "weibo.com" && request.url.encodedPath == "/" ->
                    respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
                request.url.encodedPath == "/ajax/statuses/destroy" -> {
                    jsonBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    xsrf = request.headers["X-XSRF-TOKEN"]
                    respond("""{"ok":1}""")
                }
                else -> respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).destroyPost("99", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            // 先在网页域取 XSRF 令牌，再以 JSON 体提交删除
            assertEquals("""{"id":"99"}""", jsonBody)
            assertEquals("abc", xsrf)
            // 取移动端 st 的引导请求 → 网页域取 XSRF 令牌 → JSON 体提交删除
            assertEquals(
                listOf("GET m.weibo.cn/api/config", "GET weibo.com/", "POST weibo.com/ajax/statuses/destroy"),
                requests.map { (method, path) -> "$method $path" }
            )
        } finally { client.close() }
    }

    @Test
    fun deletionWithoutUsableSessionFailsBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond("""{"ok":1}""")
        })
        try {
            val api = WeiboApiService(client)
            assertTrue(api.destroyPost("99", cookie = "") is ApiResult.Error)
            assertTrue(api.destroyComment("99", cid = 1, cookie = "") is ApiResult.Error)
            assertEquals(0, requests)
        } finally { client.close() }
    }
}
