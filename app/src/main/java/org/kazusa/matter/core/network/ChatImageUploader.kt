package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody
import org.kazusa.matter.data.model.CHAT_WEBIM_SOURCE

/** 私信图片上传结果；失败时 [Failed.reason] 是可以直接展示给用户的原因。 */
internal sealed interface ChatImageUpload {
    data class Uploaded(val fid: String) : ChatImageUpload
    data class Failed(val reason: String) : ChatImageUpload
}

/**
 * 私信图片的 fid 上传，按网页版私信 SPA（`h5.sinaimg.cn/m/pcweibochat/js/app.js` 的 `initfile` +
 * plupload）实现，两段都在 api.weibo.com 网关下：
 *
 * 1. `POST /webim/fileplatform/init.json`，**表单**字段 `extprops` / `length` / `name` / `type`：
 *    - 字节数的字段名是 `length`，写成 `size` 会被网关判成 400 remote service error；
 *    - 编码必须是表单，JSON 体同样回 400；
 *    - `type=dm_attachment_pic` 搭配 `extprops={"uploadType":1,"recipientId":<对方 uid>}`
 *      决定上传物是「这条会话的私信附件」，缺了只会拿到普通媒体 id；
 *    响应 `{fileToken, length}`，其中 `length` 是分块大小（KB，实测 1024）。
 * 2. `POST /webim/uploadx.json?source=209678993&is_chunk=1`：multipart 分块，每块带
 *    `filetoken` / `startloc` / `chunk` / `chunks` 与文件本身；最后一块的响应里才有 `fid`。
 *
 * 返回的 `fid` 原样（可能带 `1034:` 前缀）作为 `direct_messages/new.json` 的 `fids`。
 * 网页端只对 `dm_attachment_video` 传 `sectioncheck`/`X-Up-Auth`，图片不需要。
 *
 * 两个接口的失败都是 HTTP 200 + 错误体，所以每个响应都要按字段判定，并把服务端文案与
 * 错误码带进 [ChatImageUpload.Failed.reason]——只回一句「上传失败」会让风控/参数问题无从下手。
 */
internal class ChatImageUploader(private val client: HttpClient) {

    suspend fun upload(
        credentials: WeiboWriteCredentials,
        peerUid: String,
        bytes: ByteArray
    ): ChatImageUpload {
        if (bytes.isEmpty()) return ChatImageUpload.Failed("图片内容为空，无法发送")
        val md5 = md5Hex(bytes)
        val (fileName, contentType) = imageIdentity(bytes)

        val initResponse = client.submitForm(
            url = "https://api.weibo.com/webim/fileplatform/init.json",
            formParameters = parameters {
                append("extprops", """{"uploadType":1,"recipientId":$peerUid}""")
                append("length", bytes.size.toString())
                append("name", fileName)
                append("type", "dm_attachment_pic")
            }
        ) {
            chatWebimHeaders(credentials.cookieHeader)
        }
        val init = runCatching { initResponse.jsonBody().jsonObject }.getOrNull()
        val fileToken = init?.webimString("fileToken")
        if (fileToken == null) {
            val reason = webimError(init, initResponse, "图片上传初始化失败")
            log("init failed: $reason ${init.toString().take(200)}")
            return ChatImageUpload.Failed(reason)
        }
        val chunkSize = ((init["length"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CHUNK_KB) * 1024)
            .coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
        val chunks = (bytes.size + chunkSize - 1) / chunkSize

        var fid: String? = null
        for (index in 0 until chunks) {
            val start = index * chunkSize
            val end = minOf(start + chunkSize, bytes.size)
            val response = client.submitFormWithBinaryData(
                url = "https://api.weibo.com/webim/uploadx.json?source=$CHAT_WEBIM_SOURCE&is_chunk=1",
                formData = formData {
                    append("source", CHAT_WEBIM_SOURCE)
                    append("filetoken", fileToken)
                    append("startloc", start.toString())
                    append("chunk", index.toString())
                    append("chunks", chunks.toString())
                    append("name", fileName)
                    append("filelength", bytes.size.toString())
                    append("filecheck", md5)
                    append("percent", (end * 100 / bytes.size).toString())
                    append(
                        "file",
                        bytes.copyOfRange(start, end),
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(
                                HttpHeaders.ContentDisposition,
                                "form-data; name=\"file\"; filename=\"$fileName\""
                            )
                        }
                    )
                }
            ) {
                chatWebimHeaders(credentials.cookieHeader)
            }
            val body = runCatching { response.jsonBody().jsonObject }.getOrNull()
            val chunkFid = body?.let { it.webimString("fid") ?: (it["data"] as? JsonObject)?.webimString("fid") }
            if (chunkFid != null) {
                fid = chunkFid
                continue
            }
            if (index == chunks - 1) {
                val reason = webimError(body, response, "图片上传失败")
                log("uploadx lost fid: $reason ${body.toString().take(200)}")
                return ChatImageUpload.Failed(reason)
            }
        }
        // 中间分块没有 fid 是正常的，只有收尾时仍为空才算失败
        return fid?.let(ChatImageUpload::Uploaded)
            ?: ChatImageUpload.Failed("图片上传失败：服务器未返回 fid")
    }

    /** 私信图片按真实字节判类型：扩展名与 Content-Type 交给服务端存证，猜错会被判成附件而非图片。 */
    private fun imageIdentity(bytes: ByteArray): Pair<String, String> {
        fun startsWith(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "image.jpg" to "image/jpeg"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image.png" to "image/png"
            startsWith(0x47, 0x49, 0x46) -> "image.gif" to "image/gif"
            else -> "image.jpg" to "image/jpeg"
        }
    }

    /**
     * 把 webim 的错误体折叠成一句可展示的原因：`服务端文案，错误码 N，HTTP N`。
     *
     * 网关会用 HTTP 200 包一层 `{"error":"remote service error!","error_code":10003}`，
     * 只看状态码会漏掉真正的原因，因此文案与错误码都要带出来。
     */
    private fun webimError(body: JsonObject?, response: HttpResponse, fallback: String): String {
        val pieces = buildList {
            body?.webimString("errmsg", "error", "error_description", "msg", "message")?.let(::add)
            body?.webimString("error_code", "errno")?.let { add("错误码 $it") }
            response.status.value.takeIf { it != 200 }?.let { add("HTTP $it") }
        }
        return if (pieces.isEmpty()) fallback else "$fallback：${pieces.joinToString("，")}"
    }

    private fun JsonObject.webimString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun md5Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 私信上传的请求头：网关按 Origin/Referer 判定来源，缺了会被当成匿名调用。 */
    private fun io.ktor.client.request.HttpRequestBuilder.chatWebimHeaders(cookie: String) {
        cookieHeader(cookie)
        header("Origin", "https://api.weibo.com")
        header("Referer", "https://api.weibo.com/chat/")
        header("X-Requested-With", "XMLHttpRequest")
    }

    /** 失败诊断：JVM 单测里 `android.util.Log` 是桩，包一层免得抛异常。 */
    private fun log(message: String) {
        runCatching { android.util.Log.w("WeiboApi", message) }
    }

    private companion object {
        /** init 未下发 `length` 时按网页端 plupload 的 256KB 对齐。 */
        const val DEFAULT_CHUNK_KB = 256
        const val MIN_CHUNK_BYTES = 64 * 1024
        const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
    }
}
