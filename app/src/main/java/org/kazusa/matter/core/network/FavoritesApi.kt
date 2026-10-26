package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.core.session.SessionExpiredBus

/**
 * 赞过与收藏的读取，以及收藏的写入。
 *
 * 收藏写入是账号级写操作，和点赞 / 关注受同一套风控，所以与网页端保持一致走
 * `weibo.com/ajax/statuses` 系列，不再用 m 站的 `statuses/createfav`；收藏列表也一并读 web，
 * 保持「同一资源同一套接口」。`赞过` 是没有写入的只读列表，仍用 m 站 `likes/byme`。
 */
internal class FavoritesApi(
    private val client: HttpClient,
    private val sessionExpiredBus: SessionExpiredBus? = null,
    /**
     * 网页域 JSON 写：由 [WeiboApiService] 注入，内含网页 XSRF 凭据、
     * 与点赞 / 关注共用的串行提交和频控冷却。
     */
    private val desktopJsonWrite: suspend (
        cookie: String,
        action: String,
        path: String,
        body: JsonObject,
        failureMessage: String
    ) -> ApiResult<Unit>
) {
    /**
     * 收藏列表。
     * 端点: `GET https://weibo.com/ajax/favorites/all_fav?page=&tid=0&with_total=true`
     * 响应 `data` 是微博数组（夹带 `deleted` 占位项），`total_page` 决定是否还有下一页。
     */
    suspend fun getFavorites(cookie: String, page: Int): ApiResult<UserPostsPage> = request {
        val response = client.get("https://weibo.com/ajax/favorites/all_fav") {
            cookieHeader(cookie)
            header("Referer", "https://weibo.com/")
            header("X-Requested-With", "XMLHttpRequest")
            parameter("page", page)
            parameter("tid", 0)
            parameter("with_total", true)
        }
        decodeFavoritesPage(readResponse(response, allowEmpty = true), page)
    }

    suspend fun getLikedPosts(cookie: String, page: Int): ApiResult<UserPostsPage> = request {
        val response = client.get("https://m.weibo.cn/api/likes/byme") {
            cookieHeader(cookie)
            header("Referer", "https://m.weibo.cn/collect")
            parameter("page", page)
        }
        decodeLikedPostsPage(readResponse(response, allowEmpty = true), page)
    }

    /**
     * 收藏 / 取消收藏。
     * 端点: `POST https://weibo.com/ajax/statuses/createFavorites` / `destoryFavorites`
     * （`destory` 是服务端路由本身的拼写）。请求体是 JSON `{"id": mid}`。
     */
    suspend fun setFavorite(cookie: String, id: String, favorite: Boolean): ApiResult<Unit> {
        if (id.isBlank()) return ApiResult.Error(message = "收藏目标无效")
        return desktopJsonWrite(
            cookie,
            if (favorite) "收藏" else "取消收藏",
            if (favorite) "/ajax/statuses/createFavorites" else "/ajax/statuses/destoryFavorites",
            JsonObject(mapOf("id" to JsonPrimitive(id))),
            if (favorite) "收藏失败" else "取消收藏失败"
        )
    }

    private suspend fun readResponse(response: HttpResponse, allowEmpty: Boolean = false): JsonObject {
        if (response.status.value !in 200..299) {
            throw FavoritesException(response.status.value, "微博请求失败：HTTP ${response.status.value}")
        }
        val body = try {
            response.jsonBody().jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw FavoritesException(401, "微博未返回有效数据，请重新登录后重试")
        }
        val emptyFavorites = allowEmpty && body["msg"]?.jsonPrimitive?.contentOrNull == "这里还没有内容"
        if (body["error_code"] != null ||
            (body["ok"]?.jsonPrimitive?.content != "1" && !emptyFavorites)) {
            if (body["ok"]?.jsonPrimitive?.intOrNull == -100) {
                // 读接口带着会话 Cookie 仍被判定未登录：上报全局会话失效
                sessionExpiredBus?.notifySessionExpired()
                throw FavoritesException(-100, SessionExpiredBus.MESSAGE)
            }
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
    // 网页端：`data` 直接是微博数组，`total_page` 在顶层
    val rows = when (val data = body["data"]) {
        is JsonArray -> data
        is JsonObject -> (data["data"] as? JsonArray) ?: (data["statuses"] as? JsonArray)
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    val posts = rows.mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        // 已取消收藏的微博留在列表里当占位（网页端显示成「取消收藏」的记录），不是正文
        val deleted = item["deleted"]?.jsonPrimitive?.booleanOrNull
            ?: (item["deleted"]?.jsonPrimitive?.intOrNull == 1)
        if (deleted) return@mapNotNull null
        val status = item["status"] as? JsonObject ?: item["mblog"] as? JsonObject ?: item
        runCatching {
            KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboStatusDto>(status)
                .copy(isFavorited = true)
        }.getOrNull()
    }.distinctBy { it.listKey }
    val totalPage = (body["total_page"] as? JsonPrimitive)?.intOrNull
        ?: ((body["data"] as? JsonObject)?.get("total_page") as? JsonPrimitive)?.intOrNull
    val nextPage = when {
        rows.isEmpty() -> null
        totalPage != null -> (page + 1).takeIf { page < totalPage }
        // 没有 total_page 时按「翻到空页为止」处理，避免把末页当终点漏掉尾页
        else -> page + 1
    }
    return UserPostsPage(posts, nextPage)
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
