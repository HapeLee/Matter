package org.kazusa.matter.core.network

import org.kazusa.matter.data.model.CommentsDataDto
import org.junit.Assert.*
import org.junit.Test

/**
 * 评论 DTO 的容错与扁平页解析。
 *
 * 网页端 `ajax/statuses/buildComments` 的一级评论、楼中楼都返回 `{ok, data:[...], total_number, max_id}`，
 * 所以这里直接按 [CommentsDataDto] 校验扁平结构。
 */
class CommentRepliesResponseTest {
    @Test
    fun absentReplySentinelsDoNotHideTheMainCommentPage() {
        val page = KtorClientFactory.jsonConfig.decodeFromString<CommentsDataDto>(
            """{"data":[
                {"id":1,"text":"主评论","comments":false,"reply_comment":false,"reply_original_text":false,"source":false,"region_name":false},
                {"id":2,"text":"另一条","comments":null,"reply_comment":null},
                {"id":3,"text":"对象占位","comments":{},"reply_comment":[]}
            ]}"""
        )
        assertEquals(listOf(1L, 2L, 3L), page.data.map { it.id })
        assertTrue(page.data.all { it.comments.isEmpty() && it.replyComment == null })
        assertNull(page.data.first().replyOriginalText)
        assertNull(page.data.first().source)
        assertNull(page.data.first().regionName)
    }

    @Test
    fun malformedPreviewDoesNotHideItsParentOrValidSiblingReplies() {
        val page = KtorClientFactory.jsonConfig.decodeFromString<CommentsDataDto>(
            """{"data":[{"id":100,"text":"主评论","comments":[
                {"id":101,"text":"保留这条"},false,{"id":102,"text":{}},{"id":103,"text":"保留下一条"}
            ],"reply_comment":{"text":{}}}]}"""
        )
        val root = page.data.single()
        assertEquals("主评论", root.text)
        assertEquals(listOf(101L, 103L), root.comments.map { it.id })
        assertNull(root.replyComment)
    }

    @Test
    fun embeddedRepliesRetainNestedRepliesAndReplyTarget() {
        val page = KtorClientFactory.jsonConfig.decodeFromString<CommentsDataDto>(
            """{
                "data": [{
                    "id": "100", "text": "主评论", "total_number": "4", "max_id": "99",
                    "source": "来自广东", "region_name": "广东",
                    "comments": [{
                        "id": 101, "text": "回复正文", "like_count": 2, "source": "来自北京",
                        "reply_original_text": "正文",
                        "reply_comment": {"id": "100", "user": {"id": 1, "screen_name": "甲"}},
                        "comments": [{"id": "102", "text": "更深一层", "comments": null}]
                    }]
                }]
            }"""
        )
        val root = page.data.single()
        assertEquals(4, root.totalNumber)
        assertEquals(99L, root.maxId)
        assertEquals("广东", root.regionName)
        assertEquals("来自广东", root.source)
        val reply = root.comments.single()
        assertEquals(101L, reply.id)
        assertEquals("2", reply.likeCount)
        assertEquals("来自北京", reply.source)
        assertEquals("正文", reply.replyOriginalText)
        assertEquals("甲", reply.replyComment!!.user!!.screenName)
        assertEquals(102L, reply.comments.single().id)
        assertTrue(reply.comments.single().comments.isEmpty())
    }

    @Test
    fun flatReplyPageDecodesStringCursorAndCount() {
        val result = decodeCommentReplies(
            """{"ok":1,"data":[{"id":"101","text":"回复"}],"max_id":"555","max_id_type":"1","total_number":"10"}"""
        ) as ApiResult.Success
        assertEquals(101L, result.data.data.single().id)
        assertEquals(555L, result.data.maxId)
        assertEquals(1, result.data.maxIdType)
        assertEquals(10, result.data.totalNumber)
    }

    @Test
    fun wrappedReplyPageDecodesAnEmptyFinalPage() {
        val result = decodeCommentReplies("""{"ok":1,"data":{"data":[],"max_id":0}}""") as ApiResult.Success
        assertTrue(result.data.data.isEmpty())
        assertEquals(0L, result.data.maxId)
    }

    @Test
    fun serverErrorsAndMissingDataAreNotReportedAsEmptySuccess() {
        val denied = decodeCommentReplies("""{"ok":0,"msg":"请登录"}""") as ApiResult.Error
        assertEquals("请登录", denied.message)
        assertTrue(decodeCommentReplies("""{"ok":1}""") is ApiResult.Error)
        assertTrue(decodeCommentReplies("""{"ok":1,"data":{"ok":0,"msg":"失败","data":[]}}""") is ApiResult.Error)
    }
}
