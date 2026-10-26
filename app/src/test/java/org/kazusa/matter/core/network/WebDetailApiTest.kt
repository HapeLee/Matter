package org.kazusa.matter.core.network

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.WeiboStatusDto

/**
 * 详情页链路走网页域 `weibo.com/ajax/statuses/…`。
 *
 * 这些端点与 m 站的差别只有信封：网页端把内容放在顶层（`{ok, ...微博字段}` 或 `{ok, data:[...]}`），
 * 参数也以网页详情页自己发出的请求为准（见各用例里的断言）。
 */
class WebDetailApiTest {

    @Test
    fun detailComesFromWebStatusShow() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("weibo.com", request.url.host)
            assertEquals("/ajax/statuses/show", request.url.encodedPath)
            assertEquals("5357831171867297", request.url.parameters["id"])
            assertEquals("zh-CN", request.url.parameters["locale"])
            assertEquals("true", request.url.parameters["isGetLongText"])
            respond(
                """{"ok":1,"id":5357831171867297,"idstr":"5357831171867297","text":"正文",
                   "user":{"id":1824015157,"screen_name":"蔡雅奇刑法"}}"""
            )
        }
        try {
            val result = WeiboApiService(client).getPostDetail("5357831171867297", cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val status = (result as ApiResult.Success<WeiboStatusDto>).data
            assertEquals(5357831171867297L, status.id)
            assertEquals("正文", status.text)
            assertEquals("蔡雅奇刑法", status.user?.screenName)
        } finally {
            client.close()
        }
    }

    @Test
    fun hotCommentsUseBuildCommentsWithFlowOne() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("/ajax/statuses/buildComments", request.url.encodedPath)
            assertEquals("5357831171867297", request.url.parameters["id"])
            // is_mix=0 + fetch_level=0 才是一级评论，缺了会拿到楼中楼
            assertEquals("0", request.url.parameters["is_mix"])
            assertEquals("0", request.url.parameters["fetch_level"])
            assertEquals("1", request.url.parameters["flow"])
            assertEquals("20", request.url.parameters["count"])
            assertNull(request.url.parameters["max_id"])
            respond(
                """{"ok":1,"data":[{"id":5358284531043660,"text":"第一条","user":{"id":1,"screen_name":"甲"}},
                   {"id":5358284483595855,"text":"第二条"}],"total_number":42,"max_id":1456657314135}"""
            )
        }
        try {
            val result = WeiboApiService(client)
                .getComments("5357831171867297", sortByHot = true, cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val page = (result as ApiResult.Success<CommentsDataDto>).data
            assertEquals(listOf(5358284531043660L, 5358284483595855L), page.data.map { it.id })
            assertEquals(42, page.totalNumber)
            assertEquals(1456657314135L, page.maxId)
        } finally {
            client.close()
        }
    }

    @Test
    fun timeOrderedCommentsUseFlowZeroAndSendCursor() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("0", request.url.parameters["flow"])
            assertEquals("1456657314135", request.url.parameters["max_id"])
            respond("""{"ok":1,"data":[],"total_number":0,"max_id":0}""")
        }
        try {
            val result = WeiboApiService(client).getComments(
                "5357831171867297",
                sortByHot = false,
                maxId = 1456657314135L,
                cookie = "SUB=test"
            )
            assertTrue(result is ApiResult.Success)
            assertTrue((result as ApiResult.Success<CommentsDataDto>).data.data.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun repliesSendParentCommentIdAndFetchLevelOne() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("/ajax/statuses/buildComments", request.url.encodedPath)
            // 楼中楼：id 传父评论 id，且必须 fetch_level=1 + is_mix=1
            assertEquals("5357831557745704", request.url.parameters["id"])
            assertEquals("1", request.url.parameters["fetch_level"])
            assertEquals("1", request.url.parameters["is_mix"])
            assertEquals("0", request.url.parameters["max_id"])
            respond(
                """{"ok":1,"data":[{"id":5357831557745705,"text":"子回复","rootid":5357831557745704}],
                   "total_number":8,"max_id":0}"""
            )
        }
        try {
            val result = WeiboApiService(client).getCommentReplies(
                "5357831171867297",
                commentId = 5357831557745704L,
                maxId = 0L,
                cookie = "SUB=test"
            )
            assertTrue(result is ApiResult.Success)
            val page = (result as ApiResult.Success<CommentsDataDto>).data
            assertEquals(1, page.data.size)
            assertEquals("子回复", page.data.single().text)
            assertEquals(8, page.totalNumber)
        } finally {
            client.close()
        }
    }

    @Test
    fun repostsParseFlatDataArray() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("/ajax/statuses/repostTimeline", request.url.encodedPath)
            assertEquals("5357831171867297", request.url.parameters["id"])
            assertEquals("2", request.url.parameters["page"])
            respond(
                """{"ok":1,"data":[{"id":5357969713660009,"text":"转发正文"}],
                   "total_number":48,"max_page":3,"next_cursor":5357969713660009}"""
            )
        }
        try {
            val result = WeiboApiService(client)
                .getReposts("5357831171867297", page = 2, cookie = "SUB=test")
            assertTrue(result is ApiResult.Success)
            val page = (result as ApiResult.Success<RepostsDataDto>).data
            assertEquals(listOf(5357969713660009L), page.data.map { it.id })
            assertEquals(48, page.totalNumber)
            assertEquals(3, page.max)
        } finally {
            client.close()
        }
    }
}
