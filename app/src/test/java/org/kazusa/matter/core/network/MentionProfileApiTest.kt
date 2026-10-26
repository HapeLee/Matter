package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MentionProfileApiTest {
    @Test
    fun mentionResolvesExactUserCardBeforeOpeningProfile() = runBlocking {
        val containers = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val container = request.url.parameters["containerid"].orEmpty()
            containers += container
            if (container.startsWith("100103type=3")) respond("""{"ok":1,"data":{"cards":[{"card_type":11,"card_group":[
                {"card_type":10,"user":{"id":"222","screen_name":"其他博主"}},
                {"card_type":10,"user":{"id":"123","screen_name":"目标博主"}}
            ]}]}}""")
            else {
                // 解析出 uid 后走网页端资料接口
                assertEquals("/ajax/profile/info", request.url.encodedPath)
                assertEquals("123", request.url.parameters["uid"])
                respond("""{"ok":1,"data":{"user":{"id":123,"screen_name":"目标博主"}}}""")
            }
        })
        try {
            val result = WeiboApiService(client).getUserProfile("目标博主", "SUB=test") as ApiResult.Success
            assertEquals(123L, result.data.id)
            assertEquals(2, containers.size)
        } finally { client.close() }
    }

    @Test
    fun missingMentionDoesNotOpenAnUnrelatedSearchAuthor() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertTrue(request.url.parameters["containerid"].orEmpty().startsWith("100103type=3"))
            respond("""{"ok":1,"data":{"cards":[{"card_type":11,"card_group":[
                {"card_type":10,"user":{"id":"222","screen_name":"其他博主"}}
            ]}]}}""")
        })
        try {
            assertTrue(WeiboApiService(client).getUserProfile("目标博主", "SUB=test") is ApiResult.Error)
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
