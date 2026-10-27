package org.kazusa.matter.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.WeiboStatusDto

/**
 * 单条微博的转发展示状态。
 *
 * [count] 为 null 表示服务端下发的是「1.2万」这类非纯数字文案，此时沿用原始文案，
 * 只在提交期间做一次乐观 +1 并在失败时回滚。
 */
data class RepostState(
    val count: Int?,
    val pending: Boolean = false
)

/** 微博 id 的规范键：信息流 / 搜索 / 主页 / 正文对同一条微博必须算出同一个 key。 */
fun repostKeyOf(post: WeiboStatusDto): String =
    post.idStr?.takeIf { it.isNotBlank() }
        ?: post.mid?.takeIf { it.isNotBlank() }
        ?: post.id?.toString().orEmpty()

/**
 * 一次转发写入的全部参数。
 *
 * 转发语之外的三个开关都直接落到网页端表单（见 `WeiboApiService.repost`）：
 * [pictures] 是已上传成功的图片（`pic_id`），[alsoComment] 是「同时评论此微博」
 * （`is_comment=1`），[visibility] 是分享范围（`visible`）。
 */
data class RepostRequest(
    val text: String = "",
    val pictures: List<StatusPicture> = emptyList(),
    val alsoComment: Boolean = false,
    val visibility: StatusVisibility = StatusVisibility.PUBLIC
)

/**
 * 微博转发的唯一事实来源（会话级单例，属于数据层）。
 *
 * 与 [LikeRepository] 同构：状态按微博 id 覆盖服务端初始值，以只读 [StateFlow] 暴露，
 * 信息流 / 搜索 / 主页 / 正文读到的是同一份计数；写入本身经 [repost] 完成，
 * 成功后通过 [reposted] 通知关心转发列表的页面（正文页据此重拉「转发」分栏）。
 *
 * 计数是**乐观**的：微博对同一内容的重复转发可能不计入 `reposts_count`，
 * 因此服务端刷新后仍以返回值为准，这里只保证点击后数字与失败回滚一致。
 */
class RepostRepository internal constructor(
    private val repostWrite: suspend (id: String, request: RepostRequest) -> ApiResult<Unit>
) {
    constructor(apiService: WeiboApiService) : this({ id, request ->
        apiService.repost(
            id = id,
            text = request.text,
            pictures = request.pictures,
            alsoComment = request.alsoComment,
            visibility = request.visibility
        )
    })

    private val _postStates = MutableStateFlow<Map<String, RepostState>>(emptyMap())

    /** 转发计数覆盖（微博 id → 状态），只读。 */
    val postStates: StateFlow<Map<String, RepostState>> = _postStates.asStateFlow()

    private val _reposted = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 转发成功事件（被转发微博 id）；订阅方按 id 决定是否刷新自己的列表。 */
    val reposted: SharedFlow<String> = _reposted.asSharedFlow()

    /**
     * 会话代次：切换账号时自增。
     *
     * 只清空 Map 不够——旧账号的在途请求返回（或取消回滚）时还会写回状态，
     * 所有写回点都先校验代次，保证迟到结果不会污染新会话。
     */
    private var sessionGeneration = 0

    /**
     * 解析展示状态。
     *
     * 界面从 [postStates] 收集到的快照通过 [states] 传入，保证是响应式的读；
     * 不传时退化为读当前值，供 ViewModel / 单测使用。
     */
    fun state(
        post: WeiboStatusDto,
        states: Map<String, RepostState> = _postStates.value
    ): RepostState {
        val key = repostKeyOf(post)
        if (key.isNotEmpty()) states[key]?.let { return it }
        return RepostState(count = parseInteractionCount(post.repostsCount))
    }

    /** 转发一条微博；成功返回 null，失败返回可直接展示的提示文案。 */
    suspend fun repost(post: WeiboStatusDto, text: String): String? =
        repost(post, RepostRequest(text = text))

    /** 带图 / 同时评论 / 分享范围的完整转发，语义同 [repost]。 */
    suspend fun repost(post: WeiboStatusDto, request: RepostRequest): String? {
        val key = repostKeyOf(post)
        if (key.isEmpty()) return "这条微博暂不支持转发"
        val current = state(post)
        // 同一账号对同一条微博的重复提交在 UI 层已经禁用，这里再兜一层防止连点
        if (current.pending) return null
        val session = sessionGeneration
        val base = current.copy(pending = false)
        val optimistic = base.copy(
            count = base.count?.plus(1),
            pending = true
        )
        _postStates.update { it + (key to optimistic) }
        return try {
            when (val result = repostWrite(key, request)) {
                is ApiResult.Success -> {
                    if (session == sessionGeneration) {
                        _postStates.update { it + (key to optimistic.copy(pending = false)) }
                        _reposted.tryEmit(key)
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
            // 取消没有结果可用，必须回滚，否则计数会永久停在 +1 / 有页面卡在 pending
            if (session == sessionGeneration) _postStates.update { it + (key to base) }
            throw cancelled
        }
    }

    /** 切换账号时清空，并推进会话代次（见 [sessionGeneration]）。 */
    fun clear() {
        sessionGeneration += 1
        _postStates.value = emptyMap()
    }

    private companion object {
        const val DEFAULT_ERROR = "转发失败，请重试"
    }
}
