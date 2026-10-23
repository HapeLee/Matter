package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeiboEmojiApiTest {
    @Test
    fun catalogGroupsCurrentFacesAndRejectsExternalImages() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/2/emotions.json", request.url.encodedPath)
            assertNull(request.headers["Cookie"])
            respond("""[
                {"type":"face","phrase":"[微笑]","url":"https://face.t.sinajs.cn/smile.png","category":""},
                {"type":"face","phrase":"[微笑]","url":"https://face.t.sinajs.cn/smile.png","category":""},
                {"type":"face","phrase":"[哆啦A梦微笑]","url":"https://face.t.sinajs.cn/dora.png","category":"哆啦A梦"},
                {"type":"face","phrase":"[错误]","url":"https://example.com/no.png","category":""},
                {"type":"ani","phrase":"[动画]","url":"https://face.t.sinajs.cn/ani.gif","category":""}
            ]""")
        })
        try {
            val groups = (WeiboEmojiApi(client).catalog() as ApiResult.Success).data
            assertEquals(2, groups.size)
            assertEquals("catalog.default", groups.first().id)
            assertEquals(listOf("[微笑]"), groups.first().emojis.map { it.code })
            assertEquals("哆啦A梦", groups.last().title)
        } finally { client.close() }
    }

    @Test
    fun officialDirectoryUsesNoCredentialsAndMergesHotPackagesFirst() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["Authorization"])
            val hot = request.url.parameters["type"] == "1"
            respond(if (hot) """{"packages":[{"id":"com.sina.default","version":"2","download_url":"https://face.t.sinajs.cn/emoji/package/default.zip"}]}"""
            else """{"packages":[
                {"id":"com.sina.test","version":"1","download_url":"https://face.t.sinajs.cn/emoji/package/test.zip"},
                {"id":"../bad","version":"1","download_url":"https://face.t.sinajs.cn/emoji/package/test.zip"},
                {"id":"com.sina.bad","version":"1","download_url":"https://example.com/bad.zip"}
            ]}""")
        })
        try {
            val result = WeiboEmojiApi(client).packages() as ApiResult.Success
            assertEquals(listOf("com.sina.default", "com.sina.test"), result.data.map { it.id })
        } finally { client.close() }
    }

    @Test
    fun failedDirectoryDoesNotBecomeAnEmptySuccess() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"error":"unavailable"}""") })
        try { assertTrue(WeiboEmojiApi(client).packages() is ApiResult.Error) } finally { client.close() }
    }
}
