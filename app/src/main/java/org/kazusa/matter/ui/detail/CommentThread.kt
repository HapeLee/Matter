package org.kazusa.matter.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
internal data class CommentRepliesState(
    val replies: List<WeiboCommentDto>,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val loadedPage: Boolean = false,
    val nextMaxId: Long = 0,
    val nextMaxIdType: Int = 0,
    val hasMore: Boolean = false
) {
    fun withPage(page: CommentsDataDto, rootId: Long?): CommentRepliesState {
        val cursor = page.maxId ?: 0
        return copy(
            replies = mergeCommentReplies(replies, page.data, rootId),
            loading = false,
            error = null,
            loadedPage = true,
            nextMaxId = cursor,
            nextMaxIdType = page.maxIdType ?: 0,
            hasMore = page.data.isNotEmpty() && cursor > 0 && cursor != nextMaxId
        )
    }
}

private val commentRepliesSaver = Saver<CommentRepliesState, String>(
    save = { Json.encodeToString(it.copy(loading = false)) },
    restore = { Json.decodeFromString<CommentRepliesState>(it) }
)

@Composable
internal fun CommentThread(
    comment: WeiboCommentDto,
    postId: String,
    apiService: WeiboApiService,
    postAuthorId: Long?,
    postAuthorName: String?,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit,
    onCopyText: (String) -> Unit,
    isReply: Boolean = false
) {
    var state by rememberSaveable(comment.id, stateSaver = commentRepliesSaver) {
        mutableStateOf(CommentRepliesState(mergeCommentReplies(emptyList(), comment.comments, comment.id)))
    }
    val scope = rememberCoroutineScope()
    val canFetch = comment.id != null && if (state.loadedPage) {
        state.hasMore
    } else {
        (comment.totalNumber ?: 0) > state.replies.size || (comment.maxId ?: 0) > 0
    }

    fun loadMore() {
        val commentId = comment.id ?: return
        if (state.loading) return
        val cursor = state.nextMaxId
        val cursorType = state.nextMaxIdType
        state = state.copy(loading = true, error = null)
        scope.launch {
            when (val result = apiService.getCommentReplies(postId, commentId, cursor, cursorType)) {
                is ApiResult.Success -> state = state.withPage(result.data, commentId)
                is ApiResult.Error -> state = state.copy(loading = false, error = result.message)
                ApiResult.Loading -> state = state.copy(loading = false)
            }
        }
    }

    val isPostAuthor = (comment.user?.id != null && comment.user.id == postAuthorId) ||
        (!comment.user?.screenName.isNullOrBlank() && comment.user.screenName == postAuthorName)
    Column {
        CommentRow(
            comment = comment,
            isPostAuthor = isPostAuthor,
            isReply = isReply,
            onUserClick = onUserClick,
            onMentionClick = onMentionClick,
            onTopicClick = onTopicClick,
            onCopyText = onCopyText
        )
        if (state.replies.isNotEmpty() || canFetch || state.loading || state.error != null) {
            Column(
                modifier = Modifier
                    .padding(start = if (isReply) 8.dp else 62.dp, end = if (isReply) 8.dp else 16.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .animateContentSize()
            ) {
                val visibleReplies = if (state.expanded) state.replies else state.replies.take(3)
                visibleReplies.forEach { reply ->
                    androidx.compose.runtime.key(reply.id ?: reply) {
                        CommentThread(
                            comment = reply,
                            postId = postId,
                            apiService = apiService,
                            postAuthorId = postAuthorId,
                            postAuthorName = postAuthorName,
                            onUserClick = onUserClick,
                            onMentionClick = onMentionClick,
                            onTopicClick = onTopicClick,
                            onCopyText = onCopyText,
                            isReply = true
                        )
                    }
                }
                when {
                    state.loading -> Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在加载回复…", style = MaterialTheme.typography.labelMedium)
                    }
                    state.error != null -> {
                        Text(
                            text = "${state.error} · 点击重试",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { loadMore() }.padding(12.dp)
                        )
                    }
                    !state.expanded && (state.replies.size > 3 || canFetch) -> {
                        val total = maxOf(comment.totalNumber ?: 0, state.replies.size)
                        Text(
                            text = if (total > 0) "展开 $total 条回复" else "展开回复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                state = state.copy(expanded = true)
                                if (canFetch && !state.loadedPage) loadMore()
                            }.padding(12.dp)
                        )
                    }
                    state.expanded && canFetch -> {
                        Text(
                            text = "加载更多回复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { loadMore() }.padding(12.dp)
                        )
                    }
                }
                if (state.expanded) {
                    Text(
                        text = "收起回复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { state = state.copy(expanded = false) }.padding(12.dp)
                    )
                }
            }
        }
    }
}
