package org.kazusa.matter.ui.detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.WeiboCommentDto

/**
 * 单条评论的点赞展示状态。
 *
 * [count] 为 null 表示服务端下发的计数不是纯数字（例如 "1.2万"），
 * 此时列表直接展示原始文案，只有用户操作后才会切换成计算后的数字。
 */
@Immutable
internal data class CommentLikeState(
    val liked: Boolean,
    val count: Int?,
    val pending: Boolean = false
)

/**
 * 评论点赞的会话内状态。
 *
 * 同一条评论可能同时出现在「热评预览」和「展开后的回复列表」里，
 * 用 id 维度覆盖服务端初始值，保证两处展示一致。
 */
internal object CommentLikeStore {
    private val overrides = mutableStateMapOf<Long, CommentLikeState>()

    fun resolve(comment: WeiboCommentDto): CommentLikeState {
        val id = comment.id
        if (id != null) overrides[id]?.let { return it }
        return CommentLikeState(
            liked = comment.isLiked,
            count = parseInteractionCount(comment.likeCount)
        )
    }

    fun put(id: Long, state: CommentLikeState) {
        overrides[id] = state
    }

    /** 切换账号时清空，避免上一个账号的点赞状态串到新会话。 */
    fun clear() {
        overrides.clear()
    }
}
