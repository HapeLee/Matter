package org.kazusa.matter.ui.detail

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.repository.LikeState
import org.kazusa.matter.ui.components.LocalCommentLikeStates
import org.kazusa.matter.ui.components.LocalLikeRepository
import org.kazusa.matter.ui.components.LocalSnackbarHostState
import org.kazusa.matter.ui.components.LocalUserActionScope
import kotlinx.coroutines.launch

/**
 * 折叠状态下点「展开」实际会多出来的回复条数。
 *
 * hotflow 只下发前几条回复预览（常常少于 `total_number`），所以不能直接用 `total_number` 当文案数字，
 * 否则会出现「已经显示 2 条、按钮却写『展开 3 条回复』、点完只多 1 条」的错位。
 * 真正还没露出来的是两部分之和：已折叠掉的 + 服务端尚未下发的。
 */
private fun hiddenReplyCount(
    comment: WeiboCommentDto,
    state: CommentRepliesState,
    visibleCount: Int
): Int {
    val hiddenLoaded = (state.replies.size - visibleCount).coerceAtLeast(0)
    val notLoaded = ((comment.totalNumber ?: 0) - state.replies.size).coerceAtLeast(0)
    return hiddenLoaded + notLoaded
}

/**
 * 单条评论（含其嵌套回复）。
 *
 * 回复列表、游标与加载状态由 `PostDetailViewModel` 按评论 id 管理（见 [repliesProvider] /
 * [onLoadReplies]）；这里只保留「展开 / 收起」这类纯界面状态。
 *
 * [depth] 表示嵌套层级：0 是一级评论，>0 是回复。层级只影响缩进与字号，
 * 不影响数据结构，所以「回复的回复」仍会按各自的服务端 id 独立分页与点赞。
 */
@Composable
internal fun CommentThread(
    comment: WeiboCommentDto,
    /** 按评论读取其楼中楼状态；状态由详情 ViewModel 持有。 */
    repliesProvider: (WeiboCommentDto) -> CommentRepliesState,
    /** 请求下一页回复；请求与并发控制在详情 ViewModel 内完成。 */
    onLoadReplies: (WeiboCommentDto) -> Unit,
    postAuthorId: Long?,
    postAuthorName: String?,
    onUserClick: (Long?) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit,
    onImageClick: (List<String>, Int) -> Unit = { _, _ -> },
    depth: Int = 0,
    /** 当前微博开启了「博主精选评论」：此时楼中楼接口往往直接回「暂无数据」。 */
    approvalCommentsOnly: Boolean = false,
    /** 评论所属微博 id：点赞写操作的 Referer 需要它。 */
    postId: String? = null,
    modifier: Modifier = Modifier
) {
    val state = repliesProvider(comment)
    // 展开 / 收起是纯界面状态：只需存一个 Bool，不必把回复正文写进 saveable
    var expanded by rememberSaveable(comment.id ?: comment.listKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 点赞请求必须跑在会话级 scope 上：评论项划出屏幕被回收时请求仍会完成，
    // 不会把 pending 永久留在共享状态里
    val actionScope = LocalUserActionScope.current ?: scope
    val likeRepository = LocalLikeRepository.current
    val likeState = likeRepository?.commentState(comment, LocalCommentLikeStates.current)
        ?: LikeState(comment.isLiked, parseInteractionCount(comment.likeCount))
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val isReply = depth > 0

    fun showMessage(message: String) {
        if (message.isBlank()) return
        if (snackbarHostState != null) {
            actionScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleLike() {
        val repository = likeRepository ?: return
        if (likeState.pending) return
        actionScope.launch {
            val message = repository.toggleComment(comment, postId) ?: return@launch
            showMessage(message)
        }
    }

    // 精选评论下楼中楼可能整体不可见（接口直接回「暂无数据」），
    // 当评论自身也没带回复预览时，就不再给一个点开也空的「展开 N 条回复」入口
    val repliesHidden = approvalCommentsOnly && state.replies.isEmpty()

    val canFetch = comment.id != null && !state.repliesUnavailable && !repliesHidden && if (state.loadedPage) {
        state.hasMore
    } else {
        (comment.totalNumber ?: 0) > state.replies.size || (comment.maxId ?: 0) > 0
    }

    fun loadMore() {
        if (comment.id == null || state.loading) return
        onLoadReplies(comment)
    }

    val isPostAuthor = (comment.user?.id != null && comment.user.id == postAuthorId) ||
        (!comment.user?.screenName.isNullOrBlank() && comment.user.screenName == postAuthorName)
    Column(modifier = modifier) {
        CommentRow(
            comment = comment,
            likeState = likeState,
            isPostAuthor = isPostAuthor,
            isReply = isReply,
            onUserClick = onUserClick,
            onMentionClick = onMentionClick,
            onTopicClick = onTopicClick,
            onImageClick = onImageClick,
            onLikeClick = { toggleLike() },
            likeEnabled = comment.id != null
        )
        if (state.replies.isNotEmpty() || canFetch || state.loading || state.error != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isReply) 8.dp else 62.dp,
                        end = if (isReply) 8.dp else 16.dp,
                        bottom = 12.dp
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .animateContentSize()
            ) {
                val visibleReplies = if (expanded) state.replies else state.replies.take(3)
                visibleReplies.forEach { reply ->
                    androidx.compose.runtime.key(reply.listKey) {
                        CommentThread(
                            comment = reply,
                            repliesProvider = repliesProvider,
                            onLoadReplies = onLoadReplies,
                            postAuthorId = postAuthorId,
                            postAuthorName = postAuthorName,
                            onUserClick = onUserClick,
                            onMentionClick = onMentionClick,
                            onTopicClick = onTopicClick,
                            onImageClick = onImageClick,
                            depth = depth + 1,
                            approvalCommentsOnly = approvalCommentsOnly,
                            postId = postId
                        )
                    }
                }
                when {
                    state.loading -> Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在加载回复…", style = MaterialTheme.typography.labelMedium)
                    }
                    state.error != null -> {
                        CommentReplyAction(
                            text = "${state.error} · 点击重试",
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = { loadMore() },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    !expanded && (state.replies.size > 3 || canFetch) -> {
                        // hotflow 只下发前几条回复预览（常常少于 total_number），
                        // 所以按钮上要报的是「点了之后还会多出来几条」：已折叠掉的 + 还没下发的
                        val remaining = hiddenReplyCount(comment, state, visibleReplies.size)
                        CommentReplyAction(
                            text = if (remaining > 0) "展开其余 $remaining 条回复" else "展开回复",
                            onClick = {
                                expanded = true
                                if (canFetch && !state.loadedPage) loadMore()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    expanded && canFetch -> {
                        val notLoaded = ((comment.totalNumber ?: 0) - state.replies.size).coerceAtLeast(0)
                        CommentReplyAction(
                            text = if (notLoaded > 0) "加载其余 $notLoaded 条回复" else "加载更多回复",
                            onClick = { loadMore() },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
                if (expanded) {
                    CommentReplyAction(
                        text = "收起回复",
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { expanded = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

/**
 * 回复区底部的操作按钮（展开 / 加载更多 / 收起 / 重试）。
 *
 * 统一靠右对齐，做成带圆角的胶囊：外层 padding 是与回复列表之间的边距，
 * 内层 padding 决定胶囊本身的胖瘦，圆角裁剪放在 clickable 之前以保证水波纹也被裁成胶囊。
 */
@Composable
private fun CommentReplyAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmallEmphasized,
        color = contentColor,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    )
}
