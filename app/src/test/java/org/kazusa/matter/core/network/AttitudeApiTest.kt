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
 * 点赞的请求形状：m 站 `attitudes/create|destroy` 已被微博风控收紧（登录态连写回
 * 「操作频繁」），微博与评论点赞统一改走 weibo.com 网页端表单协议——
 * 微博 `setLike`/`cancelLike`（`id`），评论 `updateLike`/`destroyLike`
 * （`object_id` + `object_type=comment`）。
 */
class AttitudeApiTest {

    private fun configResponse() = """{"ok":1,"data":{"login":true,"st":"token"}}"""

    /** mock 引导链：m 站取 st → weibo.com 取 XSRF（并轮换一次令牌，验证缓存跟随更新）。 */
    private fun mockClient(
        requests: MutableList<String>,
        writeResponse: () -> String = { """{"ok":1}""" },
        block: (path: String, body: Parameters, headers: io.ktor.http.Headers) -> Unit = { _, _, _ -> }
    ): HttpClient = HttpClient(MockEngine { request ->
        val path = request.url.host + request.url.encodedPath
        requests += "${request.method.value} $path"
        when {
            request.url.encodedPath == "/api/config" -> respond(configResponse())
            request.url.host == "weibo.com" && request.url.encodedPath == "/" ->
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            request.url.encodedPath.startsWith("/ajax/") -> {
                val body = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                block(request.url.encodedPath, body, request.headers)
                respond(writeResponse())
            }
            else -> respond("""{"ok":1}""")
        }
    })

    @Test
    fun consecutiveLikesReuseTheCachedCredentials() = runBlocking {
        val requests = mutableListOf<String>()
        val client = mockClient(requests)
        try {
            val api = WeiboApiService(client)
            assertTrue(api.setAttitude("1", true, cookie = "SUB=test") is ApiResult.Success)
            assertTrue(api.setAttitude("2", true, cookie = "SUB=test") is ApiResult.Success)

            // 一次点赞只有一个写请求：网页 XSRF 令牌只在首次取一次
            assertEquals(
                listOf(
                    "GET weibo.com/",
                    "POST weibo.com/ajax/statuses/setLike",
                    "POST weibo.com/ajax/statuses/setLike"
                ),
                requests
            )
        } finally { client.close() }
    }

    @Test
    fun postLikeAndUnlikeUseTheStatusesEndpointsWithId() = runBlocking {
        val bodies = mutableListOf<Pair<String, Parameters>>()
        val requests = mutableListOf<String>()
        val client = mockClient(requests) { path, body, _ -> bodies += path to body }
        try {
            val api = WeiboApiService(client)
            assertTrue(api.setAttitude("5000000000000001", true, cookie = "SUB=test") is ApiResult.Success)
            assertTrue(api.setAttitude("5000000000000001", false, cookie = "SUB=test") is ApiResult.Success)

            assertEquals(
                listOf(
                    "/ajax/statuses/setLike" to "5000000000000001",
                    "/ajax/statuses/cancelLike" to "5000000000000001"
                ),
                bodies.map { (path, form) -> path to form["id"] }
            )
        } finally { client.close() }
    }

    @Test
    fun commentLikeUsesTheCommentEndpointsWithObjectType() = runBlocking {
        val bodies = mutableListOf<Pair<String, Parameters>>()
        val requests = mutableListOf<String>()
        val client = mockClient(requests) { path, body, _ -> bodies += path to body }
        try {
            val api = WeiboApiService(client)
            assertTrue(
                api.setAttitude("7", true, cookie = "SUB=test", refererPostId = "5000000000000001")
                    is ApiResult.Success
            )
            assertTrue(
                api.setAttitude("7", false, cookie = "SUB=test", refererPostId = "5000000000000001")
                    is ApiResult.Success
            )

            assertEquals(
                listOf(
                    "/ajax/statuses/updateLike" to "7",
                    "/ajax/statuses/destroyLike" to "7"
                ),
                bodies.map { (path, form) -> path to form["object_id"] }
            )
            // 评论端点必须声明目标类型，否则服务端按微博 id 处理会报参数错误
            assertEquals(listOf("comment", "comment"), bodies.map { it.second["object_type"] })
        } finally { client.close() }
    }

    @Test
    fun xsrfTokenHeaderMatchesTheCookieAndFollowsRotation() = runBlocking {
        var xsrf: String? = null
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.host + request.url.encodedPath
            when {
                request.url.encodedPath == "/api/config" -> respond(configResponse())
                request.url.host == "weibo.com" && request.url.encodedPath == "/" ->
                    // 首次引导与点赞响应各轮换一次令牌：abc → xyz
                    respond(
                        """{"ok":1}""",
                        headers = headersOf("Set-Cookie", "XSRF-TOKEN=${if (requests.size == 1) "abc" else "zzz"}; Path=/")
                    )
                request.url.encodedPath == "/ajax/statuses/setLike" -> {
                    xsrf = request.headers["X-XSRF-TOKEN"]
                    respond(
                        """{"ok":1}""",
                        headers = headersOf("Set-Cookie", "XSRF-TOKEN=xyz; Path=/")
                    )
                }
                else -> respond("""{"ok":1}""")
            }
        })
        try {
            val api = WeiboApiService(client)
            assertTrue(api.setAttitude("1", true, cookie = "SUB=test") is ApiResult.Success)
            assertEquals("abc", xsrf)
            assertTrue(api.setAttitude("2", true, cookie = "SUB=test") is ApiResult.Success)
            // 第二次点赞用的是上次写响应里轮换出的新令牌，而不是过期值
            assertEquals("xyz", xsrf)
        } finally { client.close() }
    }

    @Test
    fun rateLimitedLikeIsReportedAs429AndBlocksTheFollowingWrite() = runBlocking {
        var writes = 0
        val requests = mutableListOf<String>()
        val client = mockClient(
            requests,
            writeResponse = { """{"ok":0,"msg":"操作频繁，请稍后再试"}""" }
        ) { _, _, _ -> writes += 1 }
        try {
            val api = WeiboApiService(client)
            val first = api.setAttitude("1", true, cookie = "SUB=test")
            assertTrue(first is ApiResult.Error && first.code == 429)
            assertEquals("操作频繁，请稍后再试", (first as ApiResult.Error).message)

            // 冷却期内直接拦下，不再给服务端记账（否则每点一次都在续冷却）
            val second = api.setAttitude("2", true, cookie = "SUB=test")
            assertTrue(second is ApiResult.Error && second.code == 429)
            assertEquals(1, writes)
        } finally { client.close() }
    }

    @Test
    fun missingXsrfTokenAsksForRelogin() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("""{"ok":1}""")
        })
        try {
            val result = WeiboApiService(client).setAttitude("1", true, cookie = "SUB=test")

            // 拿不到网页 XSRF 令牌时提示重新登录，而不是提交一个必然失败的写请求
            assertTrue(result is ApiResult.Error && result.code == 401)
            assertTrue((result as ApiResult.Error).message.contains("重新登录"))
        } finally { client.close() }
    }

    @Test
    fun likeWithoutUsableSessionFailsBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond("""{"ok":1}""")
        })
        try {
            assertTrue(WeiboApiService(client).setAttitude("1", true, cookie = "") is ApiResult.Error)
            assertEquals(0, requests)
        } finally { client.close() }
    }
}
