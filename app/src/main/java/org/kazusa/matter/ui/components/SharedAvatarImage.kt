package org.kazusa.matter.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade

const val DEFAULT_AVATAR_URL =
    "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

/**
 * 共享元素转场中的博主头像请求（对应官方「异步图片」用例）。
 *
 * 官方文档推荐的写法是：把 Coil 的 `memoryCacheKey()` 与 `placeholderMemoryCacheKey()`
 * 都设置成与共享元素 key 同源的稳定字符串，这样匹配的两个共享元素拥有同一个缓存键：
 * 目标页首帧直接拿来源页已解码的头像当占位，直到新图加载完成都不会出现白屏或闪烁。
 *
 * 注意：这里刻意不再用「图片 URL」当缓存键——信息流卡片用 avatarLarge、主页用 avatarHd，
 * URL 不一致会导致缓存命不中，正是之前头像在转场里闪一下的原因。
 *
 * 默认头像不参与共享缓存，避免把兜底图写进 uid 维度的缓存键里。
 */
@Composable
fun rememberSharedAvatarRequest(url: String?, sharedKey: String): ImageRequest {
    val context = LocalContext.current
    val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: DEFAULT_AVATAR_URL
    val useSharedCacheKey = sharedKey.isNotBlank() && resolvedUrl != DEFAULT_AVATAR_URL
    return remember(resolvedUrl, sharedKey, useSharedCacheKey) {
        ImageRequest.Builder(context)
            .data(resolvedUrl)
            .apply {
                if (useSharedCacheKey) {
                    memoryCacheKey(sharedKey)
                    placeholderMemoryCacheKey(sharedKey)
                }
            }
            .crossfade(true)
            .build()
    }
}
