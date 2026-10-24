package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody
import org.kazusa.matter.data.model.*

interface OnlineEmojiSource {
    suspend fun catalog(): ApiResult<List<InstalledEmojiPackage>>
    suspend fun packages(): ApiResult<List<OnlineEmojiPackage>>
    suspend fun download(pack: OnlineEmojiPackage): ApiResult<ByteArray>
}

/** One 同样使用的只读官方目录；不需要发送账号 Cookie 或客户端签名。 */
class WeiboEmojiApi(private val client: HttpClient) : OnlineEmojiSource {
    override suspend fun catalog(): ApiResult<List<InstalledEmojiPackage>> = request {
        val response = client.get("https://api.weibo.com/2/emotions.json") { parameter("source", "7501641714") }
        check(response.status == HttpStatusCode.OK) { "无法获取微博表情，请重试" }
        val list = response.jsonBody() as? JsonArray
            ?: error("微博表情目录格式异常")
        val groups = linkedMapOf<String, MutableList<OnlineEmoji>>()
        list.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            if (item["type"]?.jsonPrimitive?.contentOrNull != "face") return@forEach
            val code = item["phrase"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val image = item["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (!code.matches(Regex("\\[[^\\[\\]\\s]{1,64}\\]")) ||
                !image.startsWith("https://face.t.sinajs.cn/")) return@forEach
            val category = item["category"]?.jsonPrimitive?.contentOrNull.orEmpty()
            groups.getOrPut(category) { mutableListOf() }.add(OnlineEmoji(code, image))
        }
        check(groups.values.any { it.isNotEmpty() }) { "微博表情目录为空" }
        groups.map { (category, emojis) ->
            InstalledEmojiPackage(if (category.isBlank()) "catalog.default" else "catalog.${category.hashCode().toUInt().toString(16)}",
                "1", category.ifBlank { "微博表情" }, emojis.distinctBy { it.code })
        }
    }

    override suspend fun packages(): ApiResult<List<OnlineEmojiPackage>> = request {
        suspend fun fetch(type: Int): List<OnlineEmojiPackage> {
            val response = client.get("https://api.weibo.cn/2/expression/get") { parameter("type", type) }
            check(response.status == HttpStatusCode.OK) { "无法获取在线表情包，请重试" }
            val root = response.jsonBody().jsonObject
            val list = root["packages"] as? JsonArray ?: error("在线表情目录格式异常")
            return list.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val version = item["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = item["download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!safeEmojiPackageId(id) || !safeEmojiVersion(version) || !safeEmojiDownloadUrl(url)) null
                else OnlineEmojiPackage(id, version, url)
            }
        }
        val all = fetch(0)
        val hot = try { fetch(1) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        (hot + all).distinctBy { it.id }
    }

    override suspend fun download(pack: OnlineEmojiPackage): ApiResult<ByteArray> = request {
        require(safeEmojiDownloadUrl(pack.downloadUrl)) { "表情包地址无效" }
        val response = client.get(pack.downloadUrl) { header("Referer", WeiboRequestHeaders.CDN_REFERER) }
        check(response.status == HttpStatusCode.OK) { "表情包下载失败，请重试" }
        val channel = response.bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            check(size <= 8 * 1024 * 1024) { "表情包过大，无法下载" }
            chunks += buffer.copyOf(count)
        }
        check(size > 0) { "表情包内容为空" }
        ByteArray(size).also { bytes ->
            var offset = 0
            chunks.forEach { it.copyInto(bytes, offset); offset += it.size }
        }
    }

    private suspend fun <T> request(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ApiResult.Error(message = failure.message ?: "在线表情加载失败，请重试")
    }
}

internal fun safeEmojiPackageId(value: String): Boolean = value !in setOf(".", "..") && value.matches(Regex("[a-zA-Z0-9_.-]{1,120}"))
internal fun safeEmojiVersion(value: String): Boolean = value.matches(Regex("[0-9]{1,16}"))
internal fun safeEmojiDownloadUrl(value: String): Boolean =
    value.startsWith("https://face.t.sinajs.cn/emoji/package/") && value.substringBefore('?').endsWith(".zip")
