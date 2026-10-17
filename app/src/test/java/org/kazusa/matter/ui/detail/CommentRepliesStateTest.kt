package org.kazusa.matter.ui.detail

import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CommentRepliesStateTest {
    @Test
    fun pagingPreservesPreviewOrderAndDeduplicatesRootAndOverlappingReplies() {
        val state = CommentRepliesState(listOf(WeiboCommentDto(id = 101, text = "预览")))
        val next = state.withPage(
            CommentsDataDto(
                data = listOf(
                    WeiboCommentDto(id = 100, text = "根评论"),
                    WeiboCommentDto(id = 101, text = "完整正文"),
                    WeiboCommentDto(id = 102, text = "下一条")
                ),
                maxId = 200,
                maxIdType = 1
            ),
            rootId = 100
        )
        assertEquals(listOf(101L, 102L), next.replies.map { it.id })
        assertEquals("完整正文", next.replies.first().text)
        assertTrue(next.hasMore)
        assertEquals(200L, next.nextMaxId)
        assertEquals(1, next.nextMaxIdType)
    }

    @Test
    fun finalEmptyOrRepeatedCursorPagesStopPagination() {
        val state = CommentRepliesState(emptyList(), nextMaxId = 200)
        assertFalse(state.withPage(CommentsDataDto(maxId = 201), null).hasMore)
        assertFalse(state.withPage(CommentsDataDto(data = listOf(WeiboCommentDto(id = 1)), maxId = 200), null).hasMore)
        assertFalse(state.withPage(CommentsDataDto(data = listOf(WeiboCommentDto(id = 1)), maxId = 0), null).hasMore)
    }

    @Test
    fun repliesWithoutIdsArePreservedAndSavedStateRetainsExpansionAndCursor() {
        val replies = mergeCommentReplies(
            listOf(WeiboCommentDto(text = "甲")),
            listOf(WeiboCommentDto(text = "乙")),
            null
        )
        assertEquals(2, replies.size)
        val state = CommentRepliesState(replies, expanded = true, loadedPage = true, nextMaxId = 200, hasMore = true)
        assertEquals(state, Json.decodeFromString<CommentRepliesState>(Json.encodeToString(state)))
    }
}
