package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession

/**
 * CometD 长轮询客户端的状态机（契约 2026-10 对 web.im.weibo.com 实测）：
 * 握手（successful 为布尔）→ 订阅 /im/&#60;uid&#62; → connect 循环；非 meta 频道的
 * 推送触发唤醒；402 Unknown client 重新握手；断线退避重连。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCometClientTest {

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private val session = UserSession(uid = "42", sub = "test", isLoggedIn = true)

    /**
     * 等待长轮询链路稳定：Ktor MockEngine 的执行往返发生在真实线程池，
     * `runCurrent` 推不完——这里小步快进虚拟时间，并给引擎线程真实毫秒。
     */
    private suspend fun TestScope.settle(totalVirtualMs: Long = 10_000) {
        val step = totalVirtualMs / 20
        repeat(20) {
            runCurrent()
            advanceTimeBy(step)
            runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
        }
        runCurrent()
    }

    /** 提取 Bayeux 消息里的 channel（请求体是 JSON 数组，首元素带 channel 字段）。 */
    private fun channelOf(body: ByteArray): String? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(body.decodeToString())
            .jsonArray.firstOrNull()?.jsonObject
            ?.get("channel")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
    } catch (_: Exception) {
        null
    }

    @Test
    fun pushOnNonMetaChannelWakesTheListener() = runTest {
        var wakes = 0
        val connects = mutableListOf<Int>()
        val client = HttpClient(MockEngine { request ->
            when (channelOf((request.body as OutgoingContent.ByteArrayContent).bytes())) {
                "/meta/handshake" -> respond("""[{"channel":"/meta/handshake","successful":true,"clientId":"c1"}]""")
                "/meta/subscribe" -> respond("""[{"channel":"/meta/subscribe","successful":true}]""")
                "/meta/connect" -> {
                    connects += connects.size
                    if (connects.size == 1) {
                        // 首次 connect 带一条 /im/42 推送 + meta 成功
                        respond(
                            """[{"channel":"/im/42","data":{"text":"hi"}},{"channel":"/meta/connect","successful":true,"advice":{"interval":0}}]"""
                        )
                    } else {
                        // 之后挂住（模拟长轮询等待），由测试结束 stop 收尾
                        delay(10_000_000)
                        respond("""[{"channel":"/meta/connect","successful":true}]""")
                    }
                }
                else -> respond("[]")
            }
        })
        val comet = ChatCometClient(client, { session })
        try {
            comet.start(backgroundScope) { wakes += 1 }
            settle()
            assertEquals(1, wakes)
            assertTrue(comet.isRunning())
        } finally {
            comet.stop()
            client.close()
        }
    }

    @Test
    fun unknownClientRehandshakesInsteadOfSpinning() = runTest {
        var handshakes = 0
        var connects = 0
        val client = HttpClient(MockEngine { request ->
            when (channelOf((request.body as OutgoingContent.ByteArrayContent).bytes())) {
                "/meta/handshake" -> {
                    handshakes += 1
                    respond("""[{"channel":"/meta/handshake","successful":true,"clientId":"c$handshakes"}]""")
                }
                "/meta/subscribe" -> respond("""[{"channel":"/meta/subscribe","successful":true}]""")
                "/meta/connect" -> {
                    connects += 1
                    if (connects <= 2) {
                        // 402：clientId 已失效，客户端应放弃本轮并重新握手
                        respond("""[{"channel":"/meta/connect","successful":false,"error":"402::Unknown client"}]""")
                    } else {
                        delay(10_000_000)
                        respond("""[{"channel":"/meta/connect","successful":true}]""")
                    }
                }
                else -> respond("[]")
            }
        })
        val comet = ChatCometClient(client, { session })
        try {
            comet.start(backgroundScope) {}
            // 402 后按退避逐轮重新「握手→订阅→connect」；语义断言不绑虚拟时钟边界：
            // 至少完成一轮重握手，且每轮恰好一次 connect（402 不会在同轮内自旋重试）
            settle()
            assertTrue("handshakes=$handshakes", handshakes >= 2)
            assertEquals(handshakes, connects)
        } finally {
            comet.stop()
            client.close()
        }
    }

    @Test
    fun stopEndsTheLoopAndNoFurtherRequestsAreMade() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            when (channelOf((request.body as OutgoingContent.ByteArrayContent).bytes())) {
                "/meta/handshake" -> {
                    requests += 1
                    respond("""[{"channel":"/meta/handshake","successful":true,"clientId":"c1"}]""")
                }
                "/meta/subscribe" -> {
                    requests += 1
                    respond("""[{"channel":"/meta/subscribe","successful":true}]""")
                }
                "/meta/connect" -> {
                    requests += 1
                    delay(10_000_000)
                    respond("""[{"channel":"/meta/connect","successful":true}]""")
                }
                else -> {
                    requests += 1
                    respond("[]")
                }
            }
        })
        val comet = ChatCometClient(client, { session })
        comet.start(backgroundScope) {}
        settle()
        // 停止前至少完成握手与订阅
        assertTrue(requests >= 2)
        val atStop = requests
        comet.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(atStop, requests)
        client.close()
    }

    @Test
    fun guestSessionDoesNotStartTheLoop() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond("[]")
        })
        val comet = ChatCometClient(client, { session.copy(uid = "guest") })
        try {
            comet.start(backgroundScope) {}
            settle()
            assertEquals(0, requests)
        } finally {
            comet.stop()
            client.close()
        }
    }
}
