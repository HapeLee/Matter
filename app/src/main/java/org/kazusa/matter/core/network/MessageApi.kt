package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.kazusa.matter.data.model.ChatMessage
import org.kazusa.matter.data.model.ChatMessagePage
import org.kazusa.matter.data.model.MessageConversation
import org.kazusa.matter.data.model.MessageConversationPage
import org.kazusa.matter.data.model.MessageNotice
import org.kazusa.matter.data.model.MessageNoticePage
import org.kazusa.matter.data.model.MessageTab
import org.kazusa.matter.core.session.SessionExpiredBus

/**
 * 微博移动端消息（m.weibo.cn）接口。
 *
 * 这些端点就是微博 H5 消息页自己调用的地址，与项目现有的 m.weibo.cn Cookie 完全同域：
 * - 私信会话 `GET /message/msglist?page=N`
 * - 收到的评论 `GET /message/cmt?page=N`
 * - @我的微博 `GET /message/mentionsAt?page=N`
 * - 收到的赞 `GET /message/attitude?page=N`
 * - 聊天记录 `GET /api/chat/list?uid=…&count=…[&max_id=…]`
 *
 * 未登录统一返回 `{"ok":-100,"url":"<登录页>"}`，这里翻译成 401 并上报会话失效，
 * 由导航根统一跳登录页。
 * 接口没有公开文档，字段按「取第一个可用候选键」解析，缺字段降级为空值而不是整页抛异常。
 */
internal class MessageApi(
    private val client: HttpClient,
    private val sessionExpiredBus: SessionExpiredBus? = null
) {

    suspend fun conversations(
        cookie: String,
        page: Int
    ): ApiResult<MessageConversationPage> = request(cookie) {
        // 与网页端一致：只传 page，是否还有下一页由返回是否为空判断
        val response = client.get("$MESSAGE_BASE/message/msglist") {
            cookieHeader(cookie)
            header("Referer", "$MESSAGE_BASE/message")
            parameter("page", page)
        }
        parseConversationPage(readBody(response))
    }

    suspend fun notices(
        cookie: String,
        tab: MessageTab,
        page: Int
    ): ApiResult<MessageNoticePage> = request(cookie) {
        val path = when (tab) {
            MessageTab.COMMENT -> "cmt"
            MessageTab.MENTION -> "mentionsAt"
            MessageTab.LIKE -> "attitude"
            MessageTab.DIRECT -> error("私信分区不使用提醒接口")
        }
        val response = client.get("$MESSAGE_BASE/message/$path") {
            cookieHeader(cookie)
            header("Referer", "$MESSAGE_BASE/message")
            parameter("page", page)
        }
        parseNoticePage(readBody(response), page)
    }

    suspend fun chatHistory(
        cookie: String,
        currentUid: String,
        peerUid: String,
        maxId: Long? = null,
        count: Int = PAGE_SIZE
    ): ApiResult<ChatMessagePage> = request(cookie) {
        val response = client.get("$MESSAGE_BASE/api/chat/list") {
            cookieHeader(cookie)
            header("Referer", "$MESSAGE_BASE/message/chat?uid=$peerUid")
            parameter("uid", peerUid)
            parameter("count", count)
            if (maxId != null && maxId > 0) parameter("max_id", maxId)
        }
        parseChatPage(readBody(response), currentUid, peerUid, maxId, count)
    }

    private suspend fun <T> request(cookie: String, block: suspend () -> T): ApiResult<T> {
        if (cookie.isBlank()) return ApiResult.Error(code = 401, message = "登录后才能查看消息")
        return try {
            ApiResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MessageApiException) {
            ApiResult.Error(e.code, e.message.orEmpty())
        } catch (e: IllegalStateException) {
            ApiResult.Error(message = e.message ?: "消息接口返回的数据格式与预期不同")
        } catch (e: Exception) {
            ApiResult.Error(message = "消息加载失败，请检查网络后重试", throwable = e)
        }
    }

    private suspend fun readBody(response: HttpResponse): JsonObject {
        if (response.status.value !in 200..299) {
            throw MessageApiException(
                response.status.value,
                "消息接口请求失败：HTTP ${response.status.value}"
            )
        }
        val element = try {
            KtorClientFactory.jsonConfig.parseToJsonElement(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw MessageApiException(null, "消息接口返回的不是有效数据，可能登录已失效")
        }
        val body = element as? JsonObject
            ?: throw MessageApiException(null, "消息接口返回的数据格式无法识别")
        val ok = body.int("ok") ?: 0
        if (ok == NOT_LOGGED_IN) {
            // 请求一定带着会话 Cookie（request 入口已挡掉空 Cookie），-100 说明凭据已失效
            sessionExpiredBus?.notifySessionExpired()
            throw MessageApiException(401, SessionExpiredBus.MESSAGE)
        }
        if (ok <= 0) {
            throw MessageApiException(
                body.int("errno")?.takeIf { it != 0 },
                body.messageText("消息接口返回错误（$ok）")
            )
        }
        return body
    }
}

private const val MESSAGE_BASE = "https://m.weibo.cn"

private const val PAGE_SIZE = 20

/** 微博未登录时统一返回 `ok = -100`，并附带登录页 url。 */
private const val NOT_LOGGED_IN = -100

private class MessageApiException(val code: Int?, message: String) : Exception(message)

/**
 * 私信会话解析。
 *
 * 每项形如 `{id, user:{id, screen_name, avatar_large}, text, created_at, unread, scheme}`，
 * `text` 是 HTML，需要清成纯文本。拿不到 uid 或昵称的条目直接丢弃。
 * 是否还有下一页由「本页是否为空」判断（接口不返回总数）。
 */
internal fun parseConversationPage(body: JsonObject): MessageConversationPage {
    val items = body.dataArray()
        ?: throw IllegalStateException("消息接口返回的数据格式与预期不同，可能是微博接口已调整")
    val conversations = items.mapNotNull { it.asObjectOrNull()?.toConversation() }
        .distinctBy { it.peerUid }
    return MessageConversationPage(
        conversations = conversations,
        hasMore = items.isNotEmpty()
    )
}

/**
 * 互动提醒解析。
 *
 * 两种下发形态都接受：`{"data":[...]}` 与 `{"data":{"data":[...],"maxPage":N}}`。
 * 有 `maxPage` 时按页码判断是否到底，否则退回「本页是否为空」。
 */
internal fun parseNoticePage(body: JsonObject, page: Int): MessageNoticePage {
    val wrapper = body["data"].asObjectOrNull()
    val items = body.dataArray()
        ?: throw IllegalStateException("消息接口返回的数据格式与预期不同，可能是微博接口已调整")
    val maxPage = wrapper?.int("maxPage", "max_page")
    val notices = items.mapNotNull { it.asObjectOrNull()?.toNotice() }.distinctBy { it.id }
    return MessageNoticePage(
        notices = notices,
        hasMore = if (maxPage != null) page < maxPage else items.isNotEmpty()
    )
}

/**
 * 聊天记录解析。
 *
 * `data.msgs` 按最新在前下发，这里统一按时间倒序输出，界面用 `reverseLayout` 从底部展示。
 * 只有整页取满时才给出更早一页的 `max_id`。
 */
internal fun parseChatPage(
    body: JsonObject,
    currentUid: String,
    peerUid: String,
    maxId: Long?,
    count: Int
): ChatMessagePage {
    val data = body["data"].asObjectOrNull() ?: JsonObject(emptyMap())
    val items = (data["msgs"] as? JsonArray)
        ?: (body["data"] as? JsonArray)
        ?: JsonArray(emptyList())
    val messages = items.mapNotNull { it.asObjectOrNull()?.toChatMessage(currentUid) }
        .sortedByDescending { it.timestampMillis ?: Long.MIN_VALUE }
    val oldestId = messages.mapNotNull { it.id.toLongOrNull() }.minOrNull()
    return ChatMessagePage(
        messages = messages,
        nextMaxId = oldestId?.takeIf { items.size >= count && it > 0 && it != maxId }
    )
}

private fun JsonObject.toConversation(): MessageConversation? {
    val user = this["user"].asObjectOrNull()
    val scheme = string("scheme", "url")
    val uid = user?.string("id", "idstr")
        ?: scheme?.let { SCHEME_UID_PATTERN.find(it)?.groupValues?.get(1) }
        ?: return null
    val name = user?.string("screen_name", "name", "nickname") ?: return null
    return MessageConversation(
        peerUid = uid,
        peerName = name,
        peerAvatarUrl = user?.string("avatar_large", "profile_image_url", "avatar").orEmpty(),
        preview = htmlToText(string("text", "content", "preview").orEmpty()),
        timeText = string("created_at", "time", "date"),
        unreadCount = (int("unread", "unread_count") ?: 0).coerceAtLeast(0)
    )
}

private fun JsonObject.toNotice(): MessageNotice? {
    val user = this["user"].asObjectOrNull()
    val scheme = string("scheme", "url")
    val uid = user?.string("id", "idstr")
        ?: scheme?.let { SCHEME_UID_PATTERN.find(it)?.groupValues?.get(1) }
        ?: ""
    val peerName = user?.string("screen_name", "name", "nickname").orEmpty()
    val text = htmlToText(string("text", "content", "message").orEmpty())
    val source = firstObject("status", "mblog", "target", "retweeted_status")
    val id = string("id", "mid", "idstr")
        ?: scheme
        ?: return null
    if (text.isBlank() && source == null) return null
    return MessageNotice(
        id = id,
        peerUid = uid,
        peerName = peerName,
        peerAvatarUrl = user?.string("avatar_large", "profile_image_url", "avatar").orEmpty(),
        text = text,
        sourceText = source?.let { htmlToText(it.string("text", "content").orEmpty()) }?.takeIf { it.isNotBlank() },
        timeText = string("created_at", "time", "date"),
        statusId = scheme?.let { STATUS_ID_PATTERN.find(it)?.groupValues?.get(1) }
            ?: firstObject("status", "mblog")?.string("id", "mid", "idstr")
    )
}

private fun JsonObject.toChatMessage(currentUid: String): ChatMessage? {
    val id = string("id", "mid") ?: return null
    val senderId = string("sender_id", "sender")
        ?: firstObject("from_user", "sender")?.string("id", "idstr")
    val fromMe = when {
        senderId != null -> senderId == currentUid
        else -> bool("is_sender") ?: false
    }
    val timestamp = timestampMillis()
    // 时间戳能解析时不再保留原始文案，避免把时间戳数字当日期展示
    val rawTime = string("created_at", "time_text")
    return ChatMessage(
        id = id,
        text = htmlToText(string("text", "content").orEmpty()),
        fromMe = fromMe,
        timestampMillis = timestamp,
        timeText = if (timestamp == null) rawTime else null
    )
}

/** `data` 可能是数组，也可能是 `{data:[...]}`；两种都认。 */
private fun JsonObject.dataArray(): JsonArray? {
    (this["data"] as? JsonArray)?.let { return it }
    val wrapper = this["data"].asObjectOrNull()
    arrayOf("data", "items", "list").forEach { key ->
        (wrapper?.get(key) as? JsonArray)?.let { return it }
    }
    return null
}

private fun JsonObject.timestampMillis(): Long? {
    val text = firstPrimitive("created_at", "timestamp", "time", "date")
        ?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val value = text.toLongOrNull() ?: return null
    return when {
        value >= 1_000_000_000_000L -> value
        value >= 1_000_000_000L -> value * 1000
        else -> null
    }
}

/** 微博正文是 HTML：`<br>` 转换行，其余标签直接去掉，再做一次常见实体反转义。 */
private fun htmlToText(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace(BR_PATTERN, "\n")
        .replace(TAG_PATTERN, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}

private fun JsonObject.firstObject(vararg keys: String): JsonObject? {
    keys.forEach { key ->
        (this[key] as? JsonObject)?.let { return it }
    }
    return null
}

private fun JsonObject.firstPrimitive(vararg keys: String): JsonPrimitive? {
    keys.forEach { key ->
        (this[key] as? JsonPrimitive)?.let { return it }
    }
    return null
}

private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
}

private fun JsonObject.bool(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

private fun JsonObject.messageText(fallback: String): String =
    listOf("msg", "message", "error").firstNotNullOfOrNull {
        (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    } ?: fallback

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private val BR_PATTERN = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val TAG_PATTERN = Regex("<[^>]+>")
private val SCHEME_UID_PATTERN = Regex("[?&]uid=(\\d+)")
private val STATUS_ID_PATTERN = Regex("/(?:status|detail)/(\\d+)")
