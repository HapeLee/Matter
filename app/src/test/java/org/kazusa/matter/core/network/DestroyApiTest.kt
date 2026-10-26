package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除链路统一走网页域：
 * - 删微博 `ajax/statuses/destroy`（JSON `{"id": ...}`）；
 * - 删评论 `ajax/statuses/destroyComment`（**表单** `cid`，且 Referer 必须是详情页，缺一即 400）。
 */
class DestroyApiTest {

    @Test
    fun commentDeletionPostsCidAsFormWithDetailReferer() = runBlocking {
        var form: Parameters? = null
        var xsrf: String? = null
        var referer: String? = null
        var contentType: String? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                assertEquals("/ajax/statuses/destroyComment", request.url.encodedPath)
                assertEquals(HttpMethod.Post, request.method)
                contentType = request.body.contentType?.toString()
                form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                xsrf = request.headers["X-XSRF-TOKEN"]
                referer = request.headers["Referer"]
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).destroyComment("99", cid = 123, cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            // 表单而非 JSON，且 Referer 必须是详情页——实测这两条缺一个就 400
            assertTrue(contentType.orEmpty().contains("application/x-www-form-urlencoded"))
            assertEquals("123", form?.get("cid"))
            assertNull(form?.get("st"))
            assertEquals("abc", xsrf)
            assertEquals("https://weibo.com/detail/99", referer)
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitedCommentDeletionEntersCooldownWithoutRetrying() = runBlocking {
        var deletes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
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
        } finally {
            client.close()
        }
    }

    @Test
    fun postDeletionUsesTheDesktopAjaxEndpointWithXsrfToken() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        var jsonBody: String? = null
        var xsrf: String? = null
        val client = HttpClient(MockEngine { request ->
            requests += request.method.value to request.url.host + request.url.encodedPath
            when {
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
            assertEquals(
                listOf("GET weibo.com/", "POST weibo.com/ajax/statuses/destroy"),
                requests.map { (method, path) -> "$method $path" }
            )
        } finally {
            client.close()
        }
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
        } finally {
            client.close()
        }
    }
}
