package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.StatusVisibility

class RepostApiTest {

    @Test
    fun repostUsesTheWebFormProtocolWithCsrf() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                // 首次写操作先生成网页域 XSRF Cookie
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                // 2026-10 网页端抓包核实：路径是 normal_repost，不是 repost
                assertEquals("/ajax/statuses/normal_repost", request.url.encodedPath)
                val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                assertEquals("5000000000000001", form["id"])
                // 转发语两端空白在提交前去掉了
                assertEquals("转发语 & #话题#", form["comment"])
                assertEquals("0", form["is_repost"])
                assertEquals("0", form["comment_ori"])
                assertEquals("0", form["is_comment"])
                assertEquals("0", form["visible"])
                assertEquals("", form["pic_id"])
                assertEquals("", form["share_id"])
                assertEquals("abc", request.headers["X-XSRF-TOKEN"])
                assertEquals("https://weibo.com/", request.headers["Referer"])
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client)
                .repost("5000000000000001", " 转发语 & #话题# ", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            assertEquals(2, paths.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun pictureCommentAndVisibilityReachTheForm() = runBlocking {
        var form: io.ktor.http.Parameters? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).repost(
                id = "99",
                text = "转发语",
                pictures = listOf(StatusPicture("008pid", "image/webp")),
                alsoComment = true,
                visibility = StatusVisibility.PRIVATE,
                cookie = "SUB=test"
            )
            assertTrue(result is ApiResult.Success)
            val fields = requireNotNull(form)
            // 同时评论此微博 → is_comment=1；分享范围「仅自己可见」→ visible=1
            assertEquals("1", fields["is_comment"])
            assertEquals(StatusVisibility.PRIVATE.value, fields["visible"])
            // 转发配图与发微博同一份 pic_id 编码，并带 need_transcode
            assertEquals("""[{"type":"image/webp","pid":"008pid"}]""", fields["pic_id"])
            assertEquals("1", fields["need_transcode"])
        } finally {
            client.close()
        }
    }

    @Test
    fun emptyRepostTextIsAllowed() = runBlocking {
        var comment: String? = "unset"
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                comment = form["comment"]
                respond("""{"ok":1}""")
            }
        })
        try {
            val result = WeiboApiService(client).repost("99", "   ", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            // 直接转发：comment 为空串而不是被省略
            assertEquals("", comment)
        } finally {
            client.close()
        }
    }

    @Test
    fun httpRateLimitBecomesTheLocalCooldownError() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                respond("", status = HttpStatusCode.TooManyRequests)
            }
        })
        try {
            val result = WeiboApiService(client).repost("99", "转发语", cookie = "SUB=test")
            assertTrue(result is ApiResult.Error)
            assertEquals(429, (result as ApiResult.Error).code)
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitedBusinessFailureBecomes429() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                respond("""{"ok":0,"msg":"转发过于频繁"}""")
            }
        })
        try {
            val result = WeiboApiService(client).repost("99", "转发语", cookie = "SUB=test")
            assertTrue(result is ApiResult.Error)
            // 频控文案统一收敛成 429，交给写闸门冷却
            assertEquals(429, (result as ApiResult.Error).code)
        } finally {
            client.close()
        }
    }

    @Test
    fun blankTargetNeverReachesTheNetwork() = runBlocking {
        val client = HttpClient(MockEngine { error("Invalid target must not reach the network") })
        try {
            val api = WeiboApiService(client)
            assertTrue(api.repost("", "转发语", cookie = "SUB=test") is ApiResult.Error)
            // 未登录也不发请求
            val anonymous = api.repost("99", "转发语")
            assertTrue(anonymous is ApiResult.Error)
            assertEquals(401, (anonymous as ApiResult.Error).code)
            assertNull(anonymous.throwable)
        } finally {
            client.close()
        }
    }
}
