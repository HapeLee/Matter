package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttitudeApiTest {

    private fun configResponse() = """{"ok":1,"data":{"login":true,"st":"token"}}"""

    @Test
    fun consecutiveLikesReuseTheCachedStToken() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            respond(if (path == "/api/config") configResponse() else """{"ok":1}""")
        })
        try {
            val api = WeiboApiService(client)
            assertTrue(api.setAttitude("1", true, cookie = "SUB=test") is ApiResult.Success)
            assertTrue(api.setAttitude("2", true, cookie = "SUB=test") is ApiResult.Success)

            // 一次点赞只有一个写请求：/api/config 只在首次取 st 时打一次
            assertEquals(
                listOf("/api/config", "/api/attitudes/create", "/api/attitudes/create"),
                paths
            )
        } finally { client.close() }
    }

    @Test
    fun rateLimitedLikeIsReportedAs429AndBlocksTheFollowingWrite() = runBlocking {
        var writes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/config") {
                respond(configResponse())
            } else {
                writes += 1
                respond("""{"ok":0,"msg":"操作频繁，请稍后再试"}""")
            }
        })
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
    fun rateLimitedConfigIsNotReportedAsExpiredLogin() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("""{"ok":0,"msg":"操作频繁，请稍后再试"}""")
        })
        try {
            val result = WeiboApiService(client).setAttitude("1", true, cookie = "SUB=test")

            // /api/config 被限流时不能提示「登录已失效」，否则用户会去重新登录而不是等待
            assertTrue(result is ApiResult.Error && result.code == 429)
            assertEquals("操作频繁，请稍后再试", (result as ApiResult.Error).message)
        } finally { client.close() }
    }

    @Test
    fun commentLikesUseTheOwningPostInTheReferer() = runBlocking {
        var referer: String? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/config") {
                respond(configResponse())
            } else {
                referer = request.headers["Referer"]
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).setAttitude(
                id = "7",
                isLike = true,
                cookie = "SUB=test",
                refererPostId = "5000000000000001"
            )
            assertTrue(result is ApiResult.Success)
            assertEquals("https://m.weibo.cn/detail/5000000000000001", referer)
        } finally { client.close() }
    }

    @Test
    fun plainPostLikesKeepTheirOwnReferer() = runBlocking {
        var referer: String? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/config") {
                respond(configResponse())
            } else {
                referer = request.headers["Referer"]
                respond("""{"ok":1}""")
            }
        })
        try {
            WeiboApiService(client).setAttitude("5000000000000001", true, cookie = "SUB=test")
            assertEquals("https://m.weibo.cn/detail/5000000000000001", referer)
        } finally { client.close() }
    }
}
