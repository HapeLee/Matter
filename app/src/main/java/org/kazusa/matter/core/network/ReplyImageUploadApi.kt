package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody

/** 微博网页端的评论图片协议：原始文件 bytes + CRC32；不以表单或 Base64 上传。 */
internal class ReplyImageUploadApi(private val client: HttpClient, private val writes: WeiboWriteSession) {
    suspend fun upload(cookie: String, bytes: ByteArray): ApiResult<String> {
        if (cookie.isBlank()) return ApiResult.Error(code = 401, message = "请先登录后上传图片")
        if (bytes.isEmpty()) return ApiResult.Error(message = "图片内容为空")
        return try {
            writes.execute(cookie, "上传图片") { credentials ->
                val response = client.post("https://picupload.weibo.com/interface/upload.php") {
                    parameter("ent", "miniblog")
                    parameter("ori", "1")
                    parameter("cs", CRC32().apply { update(bytes) }.value.toString())
                    cookieHeader(credentials.cookieHeader)
                    header("Origin", "https://weibo.com")
                    header("Referer", "https://weibo.com/")
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
                if (response.status == HttpStatusCode.TooManyRequests) {
                    writes.reportRateLimited()
                    return@execute ApiResult.Error(code = 429, message = WeiboWriteErrors.RATE_LIMITED_MESSAGE)
                }
                if (response.status != HttpStatusCode.OK) {
                    return@execute ApiResult.Error(code = response.status.value, message = "图片上传失败，请重试")
                }
                val root = response.jsonBody() as? JsonObject
                    ?: return@execute ApiResult.Error(message = "图片上传返回无效数据")
                val message = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["retmsg"] as? JsonPrimitive)?.contentOrNull
                if (WeiboWriteErrors.isRateLimited(message)) {
                    writes.reportRateLimited()
                    return@execute ApiResult.Error(code = 429, message = WeiboWriteErrors.RATE_LIMITED_MESSAGE)
                }
                val flag = (root["ok"] as? JsonPrimitive)?.intOrNull ?: (root["ret"] as? JsonPrimitive)?.intOrNull
                if (flag == 0) return@execute ApiResult.Error(message = message ?: "图片上传失败，请重新选择")
                val id = findPictureId(root)
                if (id == null) ApiResult.Error(message = message ?: "图片上传未返回图片 ID，请重试")
                else ApiResult.Success(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gate: WriteGateException) {
            ApiResult.Error(code = gate.code, message = gate.message.orEmpty())
        } catch (_: Exception) {
            ApiResult.Error(message = "图片上传失败，请检查网络后重试")
        }
    }
}

private fun findPictureId(node: JsonElement, depth: Int = 0): String? {
    if (depth > 5 || node !is JsonObject) return null
    for (key in listOf("pic_id", "picid", "pic_pid", "pid")) {
        val id = (node.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonPrimitive)?.contentOrNull
        if (!id.isNullOrBlank() && id.matches(Regex("[a-zA-Z0-9_-]+"))) return id
    }
    return node.entries.firstNotNullOfOrNull { (key, value) ->
        if (key in setOf("data", "pic", "pics") || key.startsWith("pic_")) findPictureId(value, depth + 1) else null
    }
}
