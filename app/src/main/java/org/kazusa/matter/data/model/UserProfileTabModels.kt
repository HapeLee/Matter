package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable

/**
 * 博主主页「相册」tab 里的一个相册。
 *
 * 来源：`https://weibo.com/ajax/profile/getImageWall?uid={uid}&sinceid=0&has_album=true`
 * 的 `data.album_list[]`。
 */
@Immutable
data class UserAlbum(
    /** 后续拉取相册内图片时使用的 containerid */
    val containerId: String,
    val title: String,
    val coverUrl: String? = null,
    val photoCount: Int? = null
)

/** 相册内图片的一页；[nextCursor] 非 0 时继续请求下一页。 */
data class AlbumPhotoPage(
    val photos: List<String> = emptyList(),
    val nextCursor: String = "0",
    val hasMore: Boolean = false
)

/**
 * 博主主页「视频」tab 的一页。
 *
 * 来源：`https://weibo.com/ajax/profile/getWaterFallContent?uid={uid}&cursor={cursor}`
 * 的 `data.list[]`，元素本身就是微博 mblog 结构，可直接复用 [WeiboStatusDto]。
 */
data class VideoFeedPage(
    val videos: List<WeiboStatusDto> = emptyList(),
    val nextCursor: String = "-1",
    val hasMore: Boolean = false
)

/**
 * 博主主页「文章」tab 的一页。
 *
 * 来源：`m.weibo.cn/api/container/getIndex?containerid=230413{uid}_-_WEIBO_SECOND_PROFILE_WEIBO_ARTICAL`
 * 的 `data.cards[].mblog`，同样是微博 mblog 结构。
 */
data class ArticleFeedPage(
    val articles: List<WeiboStatusDto> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val total: Int? = null
)
