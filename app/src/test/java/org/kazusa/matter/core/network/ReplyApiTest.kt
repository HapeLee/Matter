package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReplyApiTest {
    @Test
    fun postAndCommentRepliesUseDifferentTargetsAndKeepTheOriginalText() = runBlocking {
        for (commentId in listOf(null, 25L)) {
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                    respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
                } else {
                    assertEquals("/ajax/comments/${if (commentId == null) "create" else "reply"}", request.url.encodedPath)
                    val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals("99", form["mid"])
                    assertEquals(commentId?.toString(), form["cid"])
                    assertEquals("正文 & #话题# @用户 😀", form["comment"])
                    // 网页路径用 XSRF 令牌，不再带 m 站的 st
                    assertNull(form["st"])
                    assertEquals("abc", request.headers["X-XSRF-TOKEN"])
                    assertEquals("https://weibo.com/detail/99", request.headers["Referer"])
                    respond("""{"ok":1,"data":{"id":"123","text":"正文"}}""")
                }
            })
            try {
                val result = WeiboApiService(client).createComment("99", " 正文 & #话题# @用户 😀 ", "SUB=test", commentId)
                assertTrue(result is ApiResult.Success)
                assertEquals(123L, (result as ApiResult.Success).data?.id)
                assertEquals(2, paths.size)
            } finally { client.close() }
        }
    }

    @Test
    fun successfulWriteWithoutUsableCommentDoesNotBecomeAFailure() = runBlocking {
        for (payload in listOf("null", "{}", "{\"id\":\"bad\"}")) {
            val client = HttpClient(MockEngine { request ->
                if (request.url.host == "weibo.com" && request.url.encodedPath == "/")
                    respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
                else respond("""{"ok":1,"data":$payload}""")
            })
            try {
                val result = WeiboApiService(client).createComment("99", "内容", "SUB=test")
                assertTrue(result is ApiResult.Success)
                assertNull((result as ApiResult.Success).data)
            } finally { client.close() }
        }
    }

    @Test
    fun imageUploadUsesRawBytesCrcAndImageReplyUsesWebCsrf() = runBlocking {
        val image = byteArrayOf(1, 2, 3, 4)
        var uploads = 0
        var writes = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/interface/upload.php" -> {
                    uploads++
                    assertEquals("miniblog", request.url.parameters["ent"])
                    assertEquals(java.util.zip.CRC32().apply { update(image) }.value.toString(), request.url.parameters["cs"])
                    assertArrayEquals(image, (request.body as OutgoingContent.ByteArrayContent).bytes())
                    respond("""{"ret":1,"data":{"pics":{"pic_1":{"pid":"image123"}}}}""")
                }
                "/ajax/comments/reply" -> {
                    writes++
                    assertEquals("weibo.com", request.url.host)
                    assertEquals("web+token", request.headers["X-XSRF-TOKEN"])
                    val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals("image123", form["pic_id"])
                    assertEquals("25", form["cid"])
                    assertEquals("图片评论", form["comment"])
                    respond("""{"ok":1}""")
                }
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        })
        try {
            val api = WeiboApiService(client)
            val cookie = "SUB=test; XSRF-TOKEN=web%2Btoken"
            assertEquals("image123", (api.uploadReplyImage(cookie, image) as ApiResult.Success).data)
            assertTrue(api.createComment("99", "", cookie, 25, "image123") is ApiResult.Success)
            assertEquals(1, uploads)
            assertEquals(1, writes)
        } finally { client.close() }
    }

    @Test
    fun invalidTargetsNeverSendARequest() = runBlocking {
        val client = HttpClient(MockEngine { error("Invalid target must not reach the network") })
        try {
            val api = WeiboApiService(client)
            assertTrue(api.createComment("", "内容", "SUB=test") is ApiResult.Error)
            assertTrue(api.createComment("99", "内容", "SUB=test", -1) is ApiResult.Error)
        } finally { client.close() }
    }
}
