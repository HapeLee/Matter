package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.UserPostsPage

/** 与移动网页端保持一致，复用 Matter 的 m.weibo.cn Cookie 会话。 */
internal class FavoritesApi(
    private val client: HttpClient,
    private val writeSession: WeiboWriteSession
) {
    suspend fun getFavorites(cookie: String, page: Int): ApiResult<UserPostsPage> = request {
        val response = client.get("https://m.weibo.cn/api/container/getIndex") {
            header("Cookie", cookie)
            header("Referer", "https://m.weibo.cn/collect")
            parameter("containerid", "230259")
            parameter("openApp", "0")
            parameter("page", page)
        }
        decodeFavoritesPage(readResponse(response, allowEmpty = true), page)
    }

    suspend fun getLikedPosts(cookie: String, page: Int): ApiResult<UserPostsPage> = request {
        val response = client.get("https://m.weibo.cn/api/likes/byme") {
            header("Cookie", cookie)
            header("Referer", "https://m.weibo.cn/collect")
            parameter("page", page)
        }
        decodeLikedPostsPage(readResponse(response, allowEmpty = true), page)
    }

    suspend fun setFavorite(cookie: String, id: String, favorite: Boolean): ApiResult<Unit> = request {
        val path = if (favorite) "createfav" else "destroyfav"
        // 与点赞、关注共用凭据缓存、串行提交和频控冷却：微博按账号对写操作限流，
        // 各写各的会让一次频控把三个入口全部拖进冷却。
        writeSession.execute(
            cookie = cookie,
            action = if (favorite) "收藏" else "取消收藏"
        ) { credentials ->
            val response = client.submitForm(
                url = "https://m.weibo.cn/api/statuses/$path",
                formParameters = parameters {
                    append("id", id)
                    append("st", credentials.token)
                }
            ) {
                header("Cookie", credentials.cookieHeader)
                header("X-XSRF-TOKEN", credentials.token)
                header("Origin", "https://m.weibo.cn")
                header("Referer", "https://m.weibo.cn/")
                header("X-Requested-With", "XMLHttpRequest")
            }
            if (response.status.value == WeiboWriteErrors.CODE_RATE_LIMITED) {
                writeSession.reportRateLimited()
                throw FavoritesException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            }
            val body = readResponse(response)
            if (body["ok"]?.jsonPrimitive?.content != "1") {
                val message = body.errorMessage("收藏操作失败，请重试")
                if (WeiboWriteErrors.isRateLimited(message)) {
                    writeSession.reportRateLimited()
                    throw FavoritesException(
                        WeiboWriteErrors.CODE_RATE_LIMITED,
                        WeiboWriteErrors.RATE_LIMITED_MESSAGE
                    )
                }
                // 多为 st 失效：丢掉缓存凭据，下次重新获取
                writeSession.invalidate()
                throw FavoritesException(null, message)
            }
        }
    }

    private suspend fun readResponse(response: HttpResponse, allowEmpty: Boolean = false): JsonObject {
        if (response.status.value !in 200..299) {
            throw FavoritesException(response.status.value, "微博请求失败：HTTP ${response.status.value}")
        }
        val body = try {
            KtorClientFactory.jsonConfig.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw FavoritesException(401, "微博未返回有效数据，请重新登录后重试")
        }
        val emptyFavorites = allowEmpty && body["msg"]?.jsonPrimitive?.contentOrNull == "这里还没有内容"
        if (body["error_code"] != null ||
            (body["ok"]?.jsonPrimitive?.content != "1" && !emptyFavorites)) {
            throw FavoritesException(body["error_code"]?.jsonPrimitive?.intOrNull,
                body.errorMessage("微博请求失败，请重新登录后重试"))
        }
        return body
    }

    private suspend fun <T> request(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: WriteGateException) {
        // 本地频控冷却中或凭据不可用：保留错误码，便于上层提示而不是当成网络失败
        ApiResult.Error(e.code, e.message.orEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: FavoritesException) {
        ApiResult.Error(e.code, e.message.orEmpty())
    } catch (e: Exception) {
        ApiResult.Error(message = "微博请求失败，请检查网络后重试", throwable = e)
    }
}

private class FavoritesException(val code: Int?, message: String) : Exception(message)

private fun JsonObject.errorMessage(fallback: String): String =
    listOf("message", "msg", "error").firstNotNullOfOrNull {
        (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    } ?: fallback

internal fun decodeFavoritesPage(body: JsonObject, page: Int): UserPostsPage {
    // 接口在列表末尾可能返回 ok=0 + msg=这里还没有内容，而不是空 cards。
    if (body["ok"]?.jsonPrimitive?.content == "0" &&
        body["msg"]?.jsonPrimitive?.contentOrNull == "这里还没有内容") {
        return UserPostsPage(emptyList(), null)
    }
    val container = body["data"] as? JsonObject
        ?: throw IllegalArgumentException("收藏列表缺少数据")
    val rows = (container["cards"] as? JsonArray)
        ?: throw IllegalArgumentException("收藏列表缺少数据")
    fun collect(cards: JsonArray): List<WeiboStatusDto> = cards.flatMap { row ->
        val item = row as? JsonObject ?: return@flatMap emptyList()
        val status = item["mblog"] as? JsonObject
        when {
            status != null -> listOf(KtorClientFactory.jsonConfig
                .decodeFromJsonElement<WeiboStatusDto>(status).copy(isFavorited = true))
            item["card_group"] is JsonArray -> collect(item["card_group"]!!.jsonArray)
            else -> emptyList()
        }
    }
    val posts = collect(rows).distinctBy { it.listKey }
    val info = container["cardlistInfo"] as? JsonObject
    val nextPage = info?.get("page")?.jsonPrimitive?.intOrNull
    return UserPostsPage(posts, if (rows.isEmpty()) null else nextPage?.takeIf { it > page })
}

internal fun decodeLikedPostsPage(body: JsonObject, page: Int): UserPostsPage {
    if (body["ok"]?.jsonPrimitive?.content == "0" &&
        body["msg"]?.jsonPrimitive?.contentOrNull == "这里还没有内容") {
        return UserPostsPage(emptyList(), null)
    }
    val data = body["data"] as? JsonObject ?: throw IllegalArgumentException("点赞列表缺少数据")
    require("statuses" in data || "page" in data) { "点赞列表缺少数据" }
    // 翻到底时 statuses 可能为 null；不足一整页也可能仍有下一页。
    val statuses = data["statuses"]
    require(statuses == null || statuses is JsonNull || statuses is JsonArray) { "点赞列表数据格式错误" }
    val rows = statuses as? JsonArray ?: JsonArray(emptyList())
    val posts = rows.map { KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboStatusDto>(it) }
        .distinctBy { it.listKey }
    val nextPage = data["page"]?.jsonPrimitive?.intOrNull
    return UserPostsPage(posts, if (rows.isEmpty()) null else nextPage?.takeIf { it > page })
}
