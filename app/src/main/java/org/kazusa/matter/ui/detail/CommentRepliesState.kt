package org.kazusa.matter.ui.detail

import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto

/**
 * 一条评论的楼中楼（回复）分页状态。
 *
 * 由 `PostDetailViewModel` 按评论 id 持有，界面只读；不再作为可序列化对象塞进 saveable，
 * 避免把整份回复正文写进恢复状态。展开 / 折叠属于界面状态，留在 Composable 里。
 */
internal data class CommentRepliesState(
    val replies: List<WeiboCommentDto>,
    val loading: Boolean = false,
    val error: String? = null,
    val loadedPage: Boolean = false,
    val nextMaxId: Long = 0,
    val nextMaxIdType: Int = 0,
    val hasMore: Boolean = false,
    /**
     * 已确认这条评论拉不到任何回复（例如开启精选评论后楼中楼接口直接回「暂无数据」）。
     * 置位后不再展示「展开 N 条回复」，避免点了之后空空如也。
     */
    val repliesUnavailable: Boolean = false
) {
    fun withPage(page: CommentsDataDto, rootId: Long?): CommentRepliesState {
        val cursor = page.maxId ?: 0
        val merged = mergeCommentReplies(replies, page.data, rootId)
        return copy(
            replies = merged,
            loading = false,
            error = null,
            loadedPage = true,
            nextMaxId = cursor,
            nextMaxIdType = page.maxIdType ?: 0,
            hasMore = page.data.isNotEmpty() && cursor > 0 && cursor != nextMaxId,
            repliesUnavailable = merged.isEmpty() && page.data.isEmpty()
        )
    }
}

/** Keep previews when paging, remove the root and overlapping replies by server identity. */
internal fun mergeCommentReplies(
    existing: List<WeiboCommentDto>,
    incoming: List<WeiboCommentDto>,
    rootId: Long?
): List<WeiboCommentDto> {
    val replies = linkedMapOf<Any, WeiboCommentDto>()
    (existing + incoming).forEach { reply ->
        if (rootId == null || reply.id != rootId) replies[reply.id ?: reply] = reply
    }
    return replies.values.toList()
}

/** 评论行首次展示时的状态：只带接口下发的回复预览。 */
internal fun initialCommentReplies(comment: WeiboCommentDto): CommentRepliesState =
    CommentRepliesState(mergeCommentReplies(emptyList(), comment.comments, comment.id))
