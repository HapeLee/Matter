package org.kazusa.matter.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto

/**
 * 单条微博 / 评论的点赞展示状态。
 *
 * [count] 为 null 表示服务端下发的是 "1.2万" 这类非纯数字文案，
 * 此时只切换点赞态，计数继续展示原始文案。
 */
data class LikeState(
    val liked: Boolean,
    val count: Int?,
    val pending: Boolean = false
)

/**
 * 微博与评论点赞的唯一事实来源（会话级单例，属于数据层）。
 *
 * - 状态按 id 维度覆盖服务端初始值，以只读 [StateFlow] 暴露：
 *   信息流 / 搜索 / 主页 / 正文页读到的是同一份结果，页面不再各自复制 `isLiked / likeCount`；
 * - 请求由 [togglePost] / [toggleComment] 内部完成乐观更新与回滚；调用方只需在
 *   **生命周期长于列表项** 的 scope 里调用（见 `LocalUserActionScope`），
 *   列表项回收不会取消请求，也就不会留下永久 pending；
 * - 协程被取消（页面整体销毁、切换账号）时同样回滚，保证 pending 不会卡死按钮。
 */
class LikeRepository internal constructor(
    /** [refererPostId]：评论点赞时所属微博 id，用于拼出合法的 Referer；微博点赞为 null。 */
    private val setAttitude: suspend (id: String, isLike: Boolean, refererPostId: String?) -> ApiResult<Boolean>
) {
    constructor(apiService: WeiboApiService) : this({ id, isLike, refererPostId ->
        apiService.setAttitude(id, isLike, refererPostId = refererPostId)
    })

    private val _postStates = MutableStateFlow<Map<String, LikeState>>(emptyMap())

    /** 微博点赞覆盖（微博 id → 状态），只读。 */
    val postStates: StateFlow<Map<String, LikeState>> = _postStates.asStateFlow()

    private val _commentStates = MutableStateFlow<Map<Long, LikeState>>(emptyMap())

    /** 评论点赞覆盖（评论 id → 状态），只读。 */
    val commentStates: StateFlow<Map<Long, LikeState>> = _commentStates.asStateFlow()

    /**
     * 会话代次：切换账号时自增。
     *
     * 仅清空 Map 不够——旧账号的在途请求返回（或取消回滚）时仍会写回状态。
     * 所有写回点都要先校验代次，保证迟到的结果不会污染新会话。
     */
    private var sessionGeneration = 0

    fun keyOf(post: WeiboStatusDto): String =
        post.idStr?.takeIf { it.isNotBlank() }
            ?: post.mid?.takeIf { it.isNotBlank() }
            ?: post.id?.toString().orEmpty()

    /**
     * 解析展示状态。
     *
     * 界面从 [postStates] 收集到的快照通过 [states] 传入，保证是响应式的读；
     * 不传时退化为读当前值，供 ViewModel / 单测使用。
     */
    fun postState(
        post: WeiboStatusDto,
        states: Map<String, LikeState> = _postStates.value
    ): LikeState {
        val key = keyOf(post)
        if (key.isNotEmpty()) states[key]?.let { return it }
        return LikeState(
            liked = post.isLiked,
            count = parseInteractionCount(post.attitudesCount)
        )
    }

    /** 评论点赞展示状态；语义与 [postState] 一致。 */
    fun commentState(
        comment: WeiboCommentDto,
        states: Map<Long, LikeState> = _commentStates.value
    ): LikeState {
        val id = comment.id
        if (id != null) states[id]?.let { return it }
        return LikeState(
            liked = comment.isLiked,
            count = parseInteractionCount(comment.likeCount)
        )
    }

    /** 切换微博点赞；成功返回 null，失败返回可直接展示的提示文案。 */
    suspend fun togglePost(post: WeiboStatusDto): String? {
        val key = keyOf(post)
        if (key.isEmpty()) return "这条微博暂不支持点赞"
        val current = postState(post)
        if (current.pending) return null
        val session = sessionGeneration
        val base = current.copy(pending = false)
        val newLiked = !base.liked
        val optimistic = LikeState(newLiked, nextCount(base.count, newLiked), pending = true)
        _postStates.update { it + (key to optimistic) }
        return try {
            when (val result = setAttitude(key, newLiked, null)) {
                is ApiResult.Success -> {
                    if (session == sessionGeneration) {
                        _postStates.update { it + (key to optimistic.copy(pending = false)) }
                    }
                    null
                }

                is ApiResult.Error -> {
                    if (session == sessionGeneration) _postStates.update { it + (key to base) }
                    result.message.ifBlank { DEFAULT_ERROR }
                }

                ApiResult.Loading -> {
                    if (session == sessionGeneration) _postStates.update { it + (key to base) }
                    DEFAULT_ERROR
                }
            }
        } catch (cancelled: CancellationException) {
            // 请求被取消时没有结果可用，必须回滚，否则按钮会永久停在 pending；
            // 但如果会话已经切换，回滚不能把旧账号的状态写回新会话
            if (session == sessionGeneration) _postStates.update { it + (key to base) }
            throw cancelled
        }
    }

    /**
     * 切换评论点赞；语义与 [togglePost] 一致。
     *
     * [postId] 是评论所属微博的 id：写操作需要合法的 Referer，用评论 id 拼出来的
     * `detail/<评论id>` 并不存在，容易被微博判为异常请求。
     */
    suspend fun toggleComment(comment: WeiboCommentDto, postId: String? = null): String? {
        val id = comment.id ?: return "这条评论暂不支持点赞"
        val current = commentState(comment)
        if (current.pending) return null
        val session = sessionGeneration
        val base = current.copy(pending = false)
        val newLiked = !base.liked
        val optimistic = LikeState(newLiked, nextCount(base.count, newLiked), pending = true)
        _commentStates.update { it + (id to optimistic) }
        return try {
            when (val result = setAttitude(id.toString(), newLiked, postId)) {
                is ApiResult.Success -> {
                    if (session == sessionGeneration) {
                        _commentStates.update { it + (id to optimistic.copy(pending = false)) }
                    }
                    null
                }

                is ApiResult.Error -> {
                    if (session == sessionGeneration) _commentStates.update { it + (id to base) }
                    result.message.ifBlank { DEFAULT_ERROR }
                }

                ApiResult.Loading -> {
                    if (session == sessionGeneration) _commentStates.update { it + (id to base) }
                    DEFAULT_ERROR
                }
            }
        } catch (cancelled: CancellationException) {
            if (session == sessionGeneration) _commentStates.update { it + (id to base) }
            throw cancelled
        }
    }

    /**
     * 切换账号时清空。
     *
     * 同时推进会话代次：旧账号的在途请求（成功、失败、取消回滚）在写回前会校验代次，
     * 因此即使晚到也不会把状态写进新会话。
     */
    fun clear() {
        sessionGeneration += 1
        _postStates.value = emptyMap()
        _commentStates.value = emptyMap()
    }

    private fun nextCount(count: Int?, liked: Boolean): Int? = count?.let {
        if (liked) it + 1 else (it - 1).coerceAtLeast(0)
    }

    private companion object {
        const val DEFAULT_ERROR = "操作失败，请重试"
    }
}
