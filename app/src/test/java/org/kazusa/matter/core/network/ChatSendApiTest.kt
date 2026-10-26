package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私信发送的请求形状与响应语义（契约 2026-10 从 api.weibo.com/chat SPA bundle 核实并在真机验证）：
 * `direct_messages/new.json` 表单（uid/text/media_type/extensions/return_detail），
 * 成功认 `errno==0` / `ok==1` / `return_detail=1` 直接回消息明细三种形态，
 * 频控文案进入与其他写操作一致的本地冷却；
 * 图片 = `fileplatform/init.json`（**表单** extprops/length/name/type，响应顶层 fileToken + length）
 * → `webim/uploadx.json?is_chunk=1` 以 multipart 分块（filetoken/startloc/chunk/chunks）换顶层 fid
 * → 作为 fids 发送。
 *
 * 客户端与生产同构安装 CookieAuth：`cookieHeader(...)` 只把凭据挂到请求属性，
 * 由该插件写入 Cookie 头；裸 MockEngine 客户端会静默丢掉 Cookie，测不出真实行为。
 */
class ChatSendApiTest {

    private fun configResponse() = """{"ok":1,"data":{"login":true,"st":"token"}}"""

    /** 表单请求体是 [OutgoingContent.ByteArrayContent]，multipart 另做类型断言。 */
    private fun OutgoingContent.formParameters(): Parameters? =
        (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()?.let(::parseQueryString)

    @Test
    fun textMessagePostsFormToWebimNew() = runBlocking {
        var form: Parameters? = null
        var referer: String? = null
        var cookie: String? = null
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" -> {
                    form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    referer = request.headers["Referer"]
                    cookie = request.headers["Cookie"]
                    respond("""{"errno":0,"data":{"id":"777"}}""")
                }
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatMessage("123", "你好，这是一条私信", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Success)
            assertEquals("777", (result as ApiResult.Success).data.id)
            assertEquals("123", form?.get("uid"))
            assertEquals("你好，这是一条私信", form?.get("text"))
            assertEquals("0", form?.get("media_type"))
            assertEquals("1", form?.get("return_detail"))
            assertTrue(form?.get("extensions")?.contains("webchat") == true)
            assertEquals("https://api.weibo.com/chat/", referer)
            assertTrue(cookie?.contains("SUB=test") == true)
        } finally { client.close() }
    }

    @Test
    fun okFlagResponseShapeIsAlsoAccepted() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" ->
                    respond("""{"ok":1,"data":{"id":"8","created_at":"1700000000"}}""")
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatMessage("123", "hi", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Success)
            val sent = (result as ApiResult.Success).data
            assertEquals("8", sent.id)
            // 秒级时间戳归一到毫秒
            assertEquals(1_700_000_000_000L, sent.timestampMillis)
        } finally { client.close() }
    }

    @Test
    fun returnDetailOnlyResponseIsTreatedAsSuccess() = runBlocking {
        // return_detail=1 时服务端可能直接把新消息当响应体返回，没有 errno / ok。
        // 旧判定要求 ok==1，会把这条已经发出去的私信报成「发送失败」。
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" ->
                    respond(
                        """{"id":"9001","text":"hi","created_at":"Mon Oct 26 20:14:45 +0800 2026"}"""
                    )
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatMessage("123", "hi", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Success)
            val sent = (result as ApiResult.Success).data
            assertEquals("9001", sent.id)
            assertEquals(
                java.time.OffsetDateTime.parse("2026-10-26T20:14:45+08:00").toInstant().toEpochMilli(),
                sent.timestampMillis
            )
        } finally { client.close() }
    }

    @Test
    fun dataOnlyResponseWithoutOkFlagIsTreatedAsSuccess() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" ->
                    respond("""{"data":{"id":"42","created_at":1700000000}}""")
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatMessage("123", "hi", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Success)
            assertEquals("42", (result as ApiResult.Success).data.id)
        } finally { client.close() }
    }

    @Test
    fun errorTextWithoutMessageIdentityIsStillAFailure() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" ->
                    respond("""{"msg":"发送失败，请稍后再试"}""")
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatMessage("123", "hi", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Error)
            assertEquals("发送失败，请稍后再试", (result as ApiResult.Error).message)
        } finally { client.close() }
    }

    @Test
    fun rateLimitedSendIsReportedAs429AndBlocksTheFollowingSend() = runBlocking {
        var sends = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/2/direct_messages/new.json" -> {
                    sends += 1
                    respond("""{"errno":-1,"errmsg":"操作频繁，请稍后再试"}""")
                }
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val api = WeiboApiService(client)
            val first = api.sendChatMessage("123", "hi", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(first is ApiResult.Error && first.code == 429)
            assertEquals("操作频繁，请稍后再试", (first as ApiResult.Error).message)

            val second = api.sendChatMessage("123", "again", cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(second is ApiResult.Error && second.code == 429)
            assertEquals(1, sends)
        } finally { client.close() }
    }

    @Test
    fun sendWithoutUsableSessionFailsBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests += 1
            respond("""{"ok":1}""")
        }) {
            install(CookieAuth)
        }
        try {
            assertTrue(WeiboApiService(client).sendChatMessage("123", "hi", cookie = "") is ApiResult.Error)
            assertEquals(0, requests)
        } finally { client.close() }
    }

    @Test
    fun imageSendsThroughDmAttachmentUpload() = runBlocking {
        val requests = mutableListOf<String>()
        var initForm: Parameters? = null
        var uploadQuery: Parameters? = null
        var uploadIsMultipart = false
        var newForm: Parameters? = null
        val image = ByteArray(1024) { 'x'.code.toByte() }
        val client = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/fileplatform/init.json" -> {
                    // 服务端按表单取值：字段名是 length（写 size 会被判成 400），fileToken 在响应顶层
                    initForm = request.body.formParameters()
                    respond("""{"fileToken":"ft1","length":1024,"urlTag":1}""")
                }
                "/webim/uploadx.json" -> {
                    uploadQuery = request.url.parameters
                    uploadIsMultipart = request.body is MultiPartFormDataContent
                    respond("""{"fid":"5358423094332343"}""")
                }
                "/webim/2/direct_messages/new.json" -> {
                    newForm = request.body.formParameters()
                    respond("""{"errno":0,"data":{"id":"900","media_type":1,"attachment":{"fid":"5358423094332343"}}}""")
                }
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatImage("123", image, cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Success)
            assertEquals("900", (result as ApiResult.Success).data.id)
            // 引导 st → 初始化上传 → 分块上传 → 发送
            assertEquals(
                listOf(
                    "POST api.weibo.com/webim/fileplatform/init.json",
                    "POST api.weibo.com/webim/uploadx.json",
                    "POST api.weibo.com/webim/2/direct_messages/new.json"
                ),
                requests
            )
            // 决定「这是私信附件」的是 type + extprops.recipientId；字节数走 length
            assertEquals("dm_attachment_pic", initForm?.get("type"))
            assertEquals("1024", initForm?.get("length"))
            assertEquals("image.jpg", initForm?.get("name"))
            assertTrue(initForm?.get("extprops")?.contains("\"recipientId\":123") == true)
            // plupload 的分块协议：is_chunk=1，正文是 multipart
            assertEquals("209678993", uploadQuery?.get("source"))
            assertEquals("1", uploadQuery?.get("is_chunk"))
            assertTrue(uploadIsMultipart)
            // fids 就是上传返回的 fid（服务端可能带 1034: 命名空间，原样回传）
            assertEquals("5358423094332343", newForm?.get("fids"))
            assertEquals("1", newForm?.get("media_type"))
            assertEquals("分享图片", newForm?.get("text"))
        } finally { client.close() }
    }

    @Test
    fun imageUploadWithoutFidAbortsBeforeSending() = runBlocking {
        // 上传接口失败也是 HTTP 200，只回 error；此时没有 fid 可用，绝不能退化发一条纯文本
        val requests = mutableListOf<String>()
        val image = ByteArray(1024) { 'x'.code.toByte() }
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/fileplatform/init.json" -> respond("""{"fileToken":"ft1","length":1024}""")
                "/webim/uploadx.json" -> respond("""{"error":"parse chunks error!","error_code":20099}""")
                else -> respond("""{"errno":0,"fid":"f"}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatImage("123", image, cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Error)
            // 失败原因要能看出是哪一步、由服务端什么原因拒绝
            val message = (result as ApiResult.Error).message
            assertTrue(message.contains("parse chunks error!"))
            assertTrue(message.contains("20099"))
            assertEquals(
                listOf("/webim/fileplatform/init.json", "/webim/uploadx.json"),
                requests
            )
        } finally { client.close() }
    }

    @Test
    fun initRejectedByGatewayAbortsBeforeUpload() = runBlocking {
        // 实测的错误形态：字段名/编码不对时网关回 remote service error（HTTP 200 + error_code 10003）
        val requests = mutableListOf<String>()
        val image = ByteArray(1024) { 'x'.code.toByte() }
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/fileplatform/init.json" ->
                    respond("""{"error":"remote service error!","error_code":10003,"http_code":400}""")
                else -> respond("""{"fid":"f"}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatImage("123", image, cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Error)
            // 网关错误必须原样透出：只有「上传失败」等于没有诊断信息
            val message = (result as ApiResult.Error).message
            assertTrue(message.contains("remote service error!"))
            assertTrue(message.contains("10003"))
            assertEquals(listOf("/webim/fileplatform/init.json"), requests)
        } finally { client.close() }
    }

    @Test
    fun imageSendWithoutAttachmentInResponseIsReportedAsFailure() = runBlocking {
        // 服务端只回一条纯文本消息 = 附件被丢掉；这时不能当成功，否则对方收到一条没有图的「分享图片」
        val image = ByteArray(1024) { 'x'.code.toByte() }
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond(configResponse())
                "/webim/fileplatform/init.json" -> respond("""{"fileToken":"ft1","length":1024}""")
                "/webim/uploadx.json" -> respond("""{"fid":"5358423094332343"}""")
                "/webim/2/direct_messages/new.json" ->
                    respond("""{"errno":0,"data":{"id":"900","text":"分享图片"}}""")
                else -> respond("""{"ok":1}""")
            }
        }) {
            install(CookieAuth)
        }
        try {
            val result = WeiboApiService(client).sendChatImage("123", image, cookie = "SUB=test; XSRF-TOKEN=token")
            assertTrue(result is ApiResult.Error)
            assertEquals("图片发送失败：服务器未接住附件，请稍后重试", (result as ApiResult.Error).message)
        } finally { client.close() }
    }
}
