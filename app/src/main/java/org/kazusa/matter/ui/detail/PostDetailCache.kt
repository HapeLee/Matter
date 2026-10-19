package org.kazusa.matter.ui.detail

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import org.kazusa.matter.data.model.WeiboStatusDto
import java.util.concurrent.ConcurrentHashMap

/**
 * 微博正文详情页预存缓存与共享元素键助手：
 * 1. 在列表点击卡片瞬间将 WeiboStatusDto 存入高速缓存；
 * 2. 详情页首帧直接渲染已有数据，消除白屏/转圈 Loading 对共享元素测量的打断；
 * 3. 统一规范卡片与正文之间 sharedBounds 的唯一 Key。
 */
object PostDetailCache {
    private val cache = ConcurrentHashMap<String, WeiboStatusDto>()

    fun put(post: WeiboStatusDto) {
        val keys = listOfNotNull(
            post.idStr,
            post.mid,
            post.id?.toString()
        )
        for (key in keys) {
            if (key.isNotBlank()) {
                cache[key] = post
            }
        }
    }

    fun get(id: String): WeiboStatusDto? {
        if (id.isBlank()) return null
        return cache[id]
    }

    fun remove(id: String) {
        if (id.isNotBlank()) {
            cache.remove(id)
        }
    }
}

/**
 * 计算用于 sharedBounds Morph 容器变换的统一共享键
 */
fun WeiboStatusDto.getCardSharedKey(): String {
    val idPart = idStr?.takeIf { it.isNotBlank() }
        ?: mid?.takeIf { it.isNotBlank() }
        ?: id?.toString()?.takeIf { it.isNotBlank() }
        ?: ""
    return if (idPart.isNotBlank()) "post_card_$idPart" else ""
}

fun formatPostDetailSharedKey(postId: String): String {
    return if (postId.isNotBlank()) "post_card_$postId" else ""
}

/**
 * 「转发卡片内原微博区块」的共享键。
 *
 * 转发卡片里渲染的是被转发的原微博；如果直接复用 `post_card_<id>`，
 * 会和信息流里原微博自己的卡片撞 key，点击转发区域时动画会从那张原微博卡片起飞。
 * 这里给转发区块单独一套 key，正文页在命中该 key 时使用同名 key 收尾。
 */
fun WeiboStatusDto.getRetweetSectionSharedKey(): String {
    val idPart = idStr?.takeIf { it.isNotBlank() }
        ?: mid?.takeIf { it.isNotBlank() }
        ?: id?.toString()?.takeIf { it.isNotBlank() }
        ?: ""
    return formatRetweetSectionSharedKey(idPart)
}

fun formatRetweetSectionSharedKey(postId: String): String {
    return if (postId.isNotBlank()) "retweet_card_$postId" else ""
}
