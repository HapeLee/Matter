package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable

/**
 * 一条微博媒体区里的单个媒体单元：图片或视频。
 *
 * 微博服务端通过 `mix_media_info.items[]` 下发图文混排的真实顺序（`type` 为 `pic` / `video`），
 * [WeiboStatusDto.mediaItems] 会还原该顺序，因此 UI 按列表顺序铺格子即可，
 * 不需要再把视频从图片里拆出来单独占一行。
 */
@Immutable
sealed interface StatusMediaItem {

    /** 宫格缩略图地址：图片为图片本身，视频为封面 */
    val previewUrl: String

    /** 图片：[previewUrl] 即大图档地址 */
    @Immutable
    data class Picture(override val previewUrl: String) : StatusMediaItem

    /** 视频：[previewUrl] 为封面地址，点击进入播放器 */
    @Immutable
    data class Video(
        override val previewUrl: String,
        val videoUrl: String,
        val streams: List<VideoStreamInfo> = emptyList(),
        /** 服务端下发的标题，为空时由 UI 用正文兜底 */
        val title: String? = null,
        /** 视频所属微博 ID，用于播放器共享元素 key */
        val mid: String = "",
        val durationSeconds: Int? = null
    ) : StatusMediaItem
}
