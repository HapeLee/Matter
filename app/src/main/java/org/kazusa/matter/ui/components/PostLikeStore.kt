package org.kazusa.matter.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.util.parseInteractionCount
import org.kazusa.matter.data.model.WeiboStatusDto

/** 全局注入的接口服务，供列表卡片里的即时交互（如点赞）使用。 */
val LocalWeiboApiService = androidx.compose.runtime.staticCompositionLocalOf<WeiboApiService?> { null }

/**
 * 单条微博的点赞展示状态。
 *
 * [count] 为 null 表示服务端下发的是 "1.2万" 这类非纯数字文案，
 * 此时只切换点赞态，计数继续展示原始文案。
 */
@Immutable
data class PostLikeState(
    val liked: Boolean,
    val count: Int?,
    val pending: Boolean = false
)

/**
 * 微博点赞的会话内状态，按微博 id 维度覆盖列表数据。
 *
 * 同一条微博可能同时出现在信息流、搜索、主页、收藏等多个页面，
 * 这里用一份共享状态保证各页面展示一致；详情页点赞也会写回这里。
 */
object PostLikeStore {
    private val overrides = mutableStateMapOf<String, PostLikeState>()

    fun keyOf(post: WeiboStatusDto): String =
        post.idStr?.takeIf { it.isNotBlank() }
            ?: post.mid?.takeIf { it.isNotBlank() }
            ?: post.id?.toString().orEmpty()

    fun resolve(post: WeiboStatusDto): PostLikeState {
        val key = keyOf(post)
        if (key.isNotEmpty()) overrides[key]?.let { return it }
        return PostLikeState(
            liked = post.isLiked,
            count = parseInteractionCount(post.attitudesCount)
        )
    }

    fun put(key: String, state: PostLikeState) {
        if (key.isNotEmpty()) overrides[key] = state
    }

    /** 切换账号时清空，避免上一个账号的点赞状态串到新会话。 */
    fun clear() {
        overrides.clear()
    }
}
