package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.MessageTab

class MessageApiTest {

    private fun json(raw: String) = KtorClientFactory.jsonConfig.parseToJsonElement(raw).jsonObject

    @Test
    fun conversationListKeepsNicknamePreviewAndUnread() {
        val page = parseConversationPage(
            json(
                """{"ok":1,"data":[
                    {"id":1,"user":{"id":42,"screen_name":"小明","avatar_large":"https://a/1.jpg"},
                     "text":"<a href=\"/status/1\">在吗</a>","created_at":"5分钟前","unread":2,
                     "scheme":"/message/chat?uid=42"},
                    {"id":2,"user":{"id":43,"screen_name":"小红"},"text":"&quot;好的&quot;","created_at":"12-25"}
                ]}"""
            )
        )
        assertEquals(listOf("42", "43"), page.conversations.map { it.peerUid })
        assertEquals("小明", page.conversations[0].peerName)
        assertEquals("https://a/1.jpg", page.conversations[0].peerAvatarUrl)
        // HTML 需要清成纯文本
        assertEquals("在吗", page.conversations[0].preview)
        assertEquals("5分钟前", page.conversations[0].timeText)
        assertEquals(2, page.conversations[0].unreadCount)
        assertEquals("\"好的\"", page.conversations[1].preview)
        assertTrue(page.hasMore)
    }

    @Test
    fun conversationWithoutUidFallsBackToSchemeAndDropsUnusableRows() {
        val page = parseConversationPage(
            json(
                """{"ok":1,"data":[
                    {"id":1,"user":{"screen_name":"没有 uid 也没有 scheme"},"text":"x"},
                    {"id":2,"user":{"screen_name":"从 scheme 取"},"text":"y","scheme":"/message/chat?uid=88"},
                    {"id":3,"user":{"id":9},"text":"没有昵称"}
                ]}"""
            )
        )
        assertEquals(listOf("88"), page.conversations.map { it.peerUid })
    }

    @Test
    fun noticeListReadsMaxPageAndExtractsStatusId() {
        val page = parseNoticePage(
            json(
                """{"ok":1,"data":{"data":[
                    {"id":11,"user":{"id":5,"screen_name":"阿蓝","avatar_large":"https://a/5.jpg"},
                     "text":"说得好","created_at":"1小时前","scheme":"/status/5001"},
                    {"id":12,"user":{"id":6,"screen_name":"阿绿"},
                     "text":"<span>赞了你</span>","created_at":"昨天",
                     "status":{"text":"原微博内容","id":"5002"}}
                ],"maxPage":3}}"""
            ),
            page = 1
        )
        assertEquals(listOf("11", "12"), page.notices.map { it.id })
        assertEquals("5001", page.notices[0].statusId)
        assertEquals("说得好", page.notices[0].text)
        // status 作为被评论的原微博摘要
        assertEquals("原微博内容", page.notices[1].sourceText)
        assertEquals("5002", page.notices[1].statusId)
        assertTrue(page.hasMore)
    }

    @Test
    fun noticePageBeyondMaxPageEndsPagination() {
        val page = parseNoticePage(
            json("""{"ok":1,"data":{"data":[],"maxPage":3}}"""),
            page = 3
        )
        assertTrue(page.notices.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun chatPageMarksOwnMessagesAndOffersOlderCursor() {
        val page = parseChatPage(
            json(
                """{"ok":1,"data":{"msgs":[
                    {"id":102,"text":"b","sender_id":1,"created_at":1735689600},
                    {"id":101,"text":"a","sender_id":2,"created_at":1735689500}
                ],"users":{}}}"""
            ),
            currentUid = "1",
            peerUid = "2",
            maxId = null,
            count = 2
        )
        assertEquals(listOf("102", "101"), page.messages.map { it.id })
        assertEquals(listOf(true, false), page.messages.map { it.fromMe })
        // 秒级时间戳归一到毫秒
        assertEquals(1_735_689_600_000L, page.messages[0].timestampMillis)
        assertEquals(101L, page.nextMaxId)
    }

    @Test
    fun shortChatPageHasNoOlderCursor() {
        val page = parseChatPage(
            json("""{"ok":1,"data":{"msgs":[{"id":101,"text":"a","sender_id":2,"created_at":1735689500}]}}"""),
            currentUid = "1",
            peerUid = "2",
            maxId = null,
            count = 20
        )
        assertNull(page.nextMaxId)
    }

    @Test
    fun notLoggedInResponseBecomes401() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("""{"ok":-100,"url":"https://passport.weibo.com/sso/signin"}""")
        })
        try {
            val result = MessageApi(client).conversations("SUB=test", page = 1)
            assertTrue(result is ApiResult.Error)
            assertEquals(401, (result as ApiResult.Error).code)
        } finally {
            client.close()
        }
    }

    @Test
    fun unknownResponseShapeIsReportedAsErrorInsteadOfEmptyList() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"ok":1}""") })
        try {
            val result = MessageApi(client).notices("SUB=test", MessageTab.COMMENT, page = 1)
            assertTrue(result is ApiResult.Error)
            assertTrue((result as ApiResult.Error).message.contains("格式"))
        } finally {
            client.close()
        }
    }

    @Test
    fun noticeRequestUsesTabSpecificPath() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"ok":1,"data":[]}""")
        })
        try {
            val api = MessageApi(client)
            api.notices("SUB=test", MessageTab.COMMENT, page = 1)
            api.notices("SUB=test", MessageTab.MENTION, page = 1)
            api.notices("SUB=test", MessageTab.LIKE, page = 1)
            assertEquals(
                listOf("/message/cmt", "/message/mentionsAt", "/message/attitude"),
                paths
            )
        } finally {
            client.close()
        }
    }
}
