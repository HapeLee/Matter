package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishApiTest {

    private fun formOf(request: HttpRequestData): Parameters =
        parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

    @Test
    fun textOnlyPublishUsesTheWebFormAndOmitsPictureFields() = runBlocking {
        var form: Parameters? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                assertEquals("/ajax/statuses/update", request.url.encodedPath)
                form = formOf(request)
                assertEquals("abc", request.headers["X-XSRF-TOKEN"])
                assertEquals("https://weibo.com/", request.headers["Referer"])
                // 网页端成功响应没有 ok 字段（2026-10 抓包核实），必须按 data 判定
                respond("""{"data":{"id":1,"idstr":"1","mid":"1","text":"今天天气不错"}}""")
            }
        })
        try {
            val result = WeiboApiService(client)
                .publishStatus(text = " 今天天气不错 ", pictures = emptyList(), cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val body = requireNotNull(form)
            assertEquals("今天天气不错", body["content"])
            assertEquals("0", body["visible"])
            assertEquals("", body["share_id"])
            assertEquals("", body["vote"])
            assertEquals("", body["media"])
            assertNull(body["pic_id"])
            assertNull(body["need_transcode"])
        } finally {
            client.close()
        }
    }

    @Test
    fun picturesAreSentAsAJsonArrayWithTheirRealMimeType() = runBlocking {
        var form: Parameters? = null
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                form = formOf(request)
                respond("""{"data":{"id":2,"idstr":"2","mid":"2"}}""")
            }
        })
        try {
            val result = WeiboApiService(client).publishStatus(
                text = "配图",
                pictures = listOf(
                    StatusPicture("008veVWOgy1aaaa", "image/jpeg"),
                    StatusPicture("008veVWOgy1bbbb", "image/png")
                ),
                cookie = "SUB=test"
            )
            assertTrue(result is ApiResult.Success)
            val body = requireNotNull(form)
            assertEquals("1", body["need_transcode"])
            val array = Json.parseToJsonElement(requireNotNull(body["pic_id"])).jsonArray
            assertEquals(2, array.size)
            assertEquals("image/jpeg", array[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("008veVWOgy1aaaa", array[0].jsonObject["pid"]?.jsonPrimitive?.content)
            assertEquals("image/png", array[1].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("008veVWOgy1bbbb", array[1].jsonObject["pid"]?.jsonPrimitive?.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun emptyDraftWithoutPicturesIsRejectedLocally() = runBlocking {
        val client = HttpClient(MockEngine { error("must not reach the network") })
        try {
            val result = WeiboApiService(client).publishStatus("   ", emptyList(), "SUB=test")
            assertTrue(result is ApiResult.Error)
        } finally {
            client.close()
        }
    }

    @Test
    fun anonymousPublishReturns401WithoutARequest() = runBlocking {
        val client = HttpClient(MockEngine { error("must not reach the network") })
        try {
            val result = WeiboApiService(client)
                .publishStatus("文本", listOf(StatusPicture("pid-1")))
            assertTrue(result is ApiResult.Error)
            assertEquals(401, (result as ApiResult.Error).code)
        } finally {
            client.close()
        }
    }

    @Test
    fun serverMessageIsPassedThroughVerbatim() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "weibo.com" && request.url.encodedPath == "/") {
                respond("""{"ok":1}""", headers = headersOf("Set-Cookie", "XSRF-TOKEN=abc; Path=/"))
            } else {
                respond("""{"ok":0,"msg":"抱歉，此内容涉嫌违规"}""")
            }
        })
        try {
            val result = WeiboApiService(client).publishStatus("文本", emptyList(), "SUB=test")
            assertTrue(result is ApiResult.Error)
            // 违规文案不是频控，必须按原样返回给用户
            assertEquals("抱歉，此内容涉嫌违规", (result as ApiResult.Error).message)
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
            val result = WeiboApiService(client).publishStatus("文本", emptyList(), "SUB=test")
            assertEquals(429, (result as ApiResult.Error).code)
        } finally {
            client.close()
        }
    }
}
