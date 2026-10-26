package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody

/**
 * web.im.weibo.com 的 CometD（Bayeux 1.0）长轮询客户端，用于私信新消息的实时唤醒。
 *
 * 这是 web 版微博聊天（api.weibo.com/chat SPA）自己在用的收信通道（2026-10 从其 bundle
 * 核实并实测）：`POST https://web.im.weibo.com/im` 消息体为 Bayeux JSON 数组，流程
 * 握手（拿 `clientId` + `BAYEUX_BROWSER` Cookie）→ 订阅 `/im/<自己的uid>`（此步做身份
 * 校验，未登录返回 `403:auth fail:create_denied`）→ `/meta/connect` 循环挂起，
 * 有推送时服务端立刻返回。注意 Bayeux 的 `successful` 字段是布尔值。
 *
 * 主动降级的两个设计：
 * - **推送只当唤醒信号**：收到非 meta 频道（`channel` 不以 `/meta` 开头）的消息一律触发
 *   [start] 传入的 `onWake`
 *   （由调用方拉一次最新聊天记录），不解析推送体——推送的 JSON 结构没有可核实的样例，
 *   消息解析全部复用已验证的 `chat/list` 链路；
 * - **不做 CometD 发送**：web 版虽有 `publish("/im/req")`，发送仍走表单端点
 *   `direct_messages/new.json`（协议见 [WeiboApiService.sendChatMessage]）。
 *
 * 断线按服务端 `advice` 驱动：`402 Unknown client` 等会话失效时重新握手；网络异常按
 * 指数退避（5s 起、上限 60s）重连。`stop` 推进代次并取消协程，旧循环不会复活。
 */
internal class ChatCometClient(
    private val client: HttpClient,
    /** 读当前登录会话；返回未登录或 null 时长轮询不启动。 */
    private val sessionProvider: suspend () -> org.kazusa.matter.core.datastore.UserSession?,
    /** 重连日志（DI 注入 logcat 实现；测试注入内存实现，避免 android.util.Log 的 not-mocked 崩溃）。 */
    private val logger: (String) -> Unit = {}
) {

    private var loopJob: Job? = null

    /** 与 [loopJob] 配对的代次：stop 自增后，旧循环在下个挂起点自行退出。 */
    @Volatile
    private var generation = 0

    /** 握手下发的 `BAYEUX_BROWSER` Cookie，与后续请求一起带上（Bayeux 会话的一部分）。 */
    @Volatile
    private var bayeuxBrowser: String? = null

    fun isRunning(): Boolean = loopJob?.isActive == true

    /**
     * 读取当前会话并启动长轮询；未登录 / 无凭据时静默不启动（防御换号瞬间读到旧状态，
     * 聊天页本就需要登录才能进入）。
     */
    fun start(scope: CoroutineScope, onWake: () -> Unit) {
        stop()
        val gen = ++generation
        loopJob = scope.launch {
            val session = try {
                sessionProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (!session.isLoggedIn || session.uid.isBlank() || session.uid == "guest") return@launch
            runLoop(session.uid, session.cookieHeader, onWake, gen)
        }
    }

    fun stop() {
        generation += 1
        loopJob?.cancel()
        loopJob = null
        bayeuxBrowser = null
    }

    private suspend fun runLoop(uid: String, cookie: String, onWake: () -> Unit, gen: Int) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (gen == generation) {
            try {
                val clientId = handshake(cookie) ?: throw IllegalStateException("CometD 握手失败")
                if (!subscribe(clientId, uid, cookie)) {
                    // 403 create_denied：订阅被拒（身份校验失败），退避后整轮重来
                    throw IllegalStateException("CometD 订阅被拒")
                }
                backoffMs = INITIAL_BACKOFF_MS
                connectLoop(clientId, cookie, onWake, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 长轮询断连是常态（网络切换 / 服务端重启），退避重连即可
                logger("comet loop reconnect: ${e.message}")
            }
            if (gen != generation) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** `/meta/connect` 循环；正常返回（有推送或服务端超时）继续，会话失效时返回触发重握手。 */
    private suspend fun connectLoop(clientId: String, cookie: String, onWake: () -> Unit, gen: Int) {
        var messageId = 2
        while (gen == generation) {
            val response = postBayeux(
                channel = "/meta/connect",
                id = messageId++,
                extra = mapOf("connectionType" to JsonPrimitive("long-polling")),
                clientId = clientId,
                cookie = cookie,
                longPoll = true
            ) ?: return
            var needHandshake = false
            for (message in response.messages) {
                val channel = message.string("channel").orEmpty()
                if (channel.startsWith("/meta")) {
                    if (!message.isSuccessful()) {
                        // 402 Unknown client 等：clientId 已失效，重新握手
                        needHandshake = true
                    }
                    val interval = (message["advice"] as? JsonObject)?.int("interval")
                    if (interval != null && interval > 0) delay(interval.toLong())
                } else if (channel.isNotEmpty()) {
                    // /im/<uid> 的推送：新消息唤醒信号
                    onWake()
                }
            }
            if (needHandshake) return
        }
    }

    private suspend fun handshake(cookie: String): String? {
        val response = postBayeux(
            channel = "/meta/handshake",
            id = 1,
            extra = mapOf(
                "version" to JsonPrimitive("1.0"),
                "minimumVersion" to JsonPrimitive("1.0"),
                "supportedConnectionTypes" to JsonArray(listOf(JsonPrimitive("long-polling")))
            ),
            clientId = null,
            cookie = cookie,
            longPoll = false
        ) ?: return null
        bayeuxBrowser = response.bayeuxBrowser
        val handshake = response.messages.firstOrNull { it.string("channel") == "/meta/handshake" }
            ?: return null
        if (!handshake.isSuccessful()) return null
        return handshake.string("clientId")
    }

    private suspend fun subscribe(clientId: String, uid: String, cookie: String): Boolean {
        val response = postBayeux(
            channel = "/meta/subscribe",
            id = 1,
            extra = mapOf("subscription" to JsonPrimitive("/im/$uid")),
            clientId = clientId,
            cookie = cookie,
            longPoll = false
        ) ?: return false
        val result = response.messages.firstOrNull { it.string("channel") == "/meta/subscribe" }
            ?: return false
        return result.isSuccessful()
    }

    /** Bayeux 的 `successful` 是布尔，兼容个别实现返回 1。 */
    private fun JsonObject.isSuccessful(): Boolean {
        val primitive = this["successful"] as? JsonPrimitive ?: return false
        return primitive.booleanOrNull == true || primitive.intOrNull == 1 ||
            primitive.contentOrNull == "true"
    }

    private class BayeuxResponse(val messages: List<JsonObject>, val bayeuxBrowser: String?)

    private suspend fun postBayeux(
        channel: String,
        id: Int,
        extra: Map<String, JsonElement>,
        clientId: String?,
        cookie: String,
        longPoll: Boolean
    ): BayeuxResponse? {
        val message = LinkedHashMap<String, JsonElement>()
        message["channel"] = JsonPrimitive(channel)
        message["id"] = JsonPrimitive(id.toString())
        clientId?.let { message["clientId"] = JsonPrimitive(it) }
        message.putAll(extra)

        val browserCookie = bayeuxBrowser
        val fullCookie = if (browserCookie.isNullOrBlank()) cookie else "$browserCookie; $cookie"
        return try {
            val response = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(JsonArray(listOf(JsonObject(message))).toString())
                header("Cookie", fullCookie)
                header("Origin", "https://api.weibo.com")
                header("Referer", "https://api.weibo.com/chat/")
                header("X-Requested-With", "XMLHttpRequest")
                if (longPoll) {
                    // 服务端 advice 超时 170s：请求超时给足余量，宁可超时重发也不误断
                    timeout {
                        requestTimeoutMillis = CONNECT_TIMEOUT_MS
                        connectTimeoutMillis = 15_000
                    }
                }
            }
            if (response.status != HttpStatusCode.OK) return null
            val element = response.jsonBody()
            val messages = (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            BayeuxResponse(messages, response.bayeuxBrowserCookie())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 从 Set-Cookie 列表里找 BAYEUX_BROWSER（握手时下发，Bayeux 会话标识）。 */
    private fun HttpResponse.bayeuxBrowserCookie(): String? =
        headers.getAll("Set-Cookie").orEmpty()
            .firstOrNull { it.startsWith("BAYEUX_BROWSER=") }
            ?.substringBefore(';')

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private companion object {
        /** Bayeux 端点（pcweibochat SPA 里的常量）。 */
        const val ENDPOINT = "https://web.im.weibo.com/im"

        const val CONNECT_TIMEOUT_MS = 190_000L
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
