package org.kazusa.matter.core.util

import org.kazusa.matter.data.model.WeiboEmojis
import androidx.compose.runtime.staticCompositionLocalOf

val LocalOnlineEmojiImages = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** Android 图片加载器的 asset URL 只在渲染边界生成。 */
internal fun weiboEmojiImageModel(code: String, onlineImages: Map<String, String> = emptyMap()): String? =
    onlineImages[code] ?: WeiboEmojis.imageAssets[code]?.let { "file:///android_asset/$it" }
        ?: WeiboEmojis.images[code]
