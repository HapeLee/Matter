package org.kazusa.matter.data.model

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kazusa.matter.core.network.KtorClientFactory

/**
 * 多图微博（>9 张）的媒体列表还原。
 *
 * 服务端的图片字段并不总是完整：`pic_urls` 在多图时只带前 9 条，完整列表落在
 * `pic_ids` / `pic_infos` 上；`mix_media_info.items` 也可能只下发其中一部分。
 * 这三组来源都必须补全，否则第 10 张及以后的图在宫格与查看器里都看不到。
 */
class WeiboStatusMediaTest {

    private fun decode(json: String): WeiboStatusDto =
        KtorClientFactory.jsonConfig.decodeFromString<WeiboStatusDto>(json)

    @Test
    fun keepsPicturesBeyondTheFirstNine() {
        val picUrls = (1..9).joinToString(",") {
            """{"thumbnail_pic": "https://wx2.sinaimg.cn/thumbnail/p$it.jpg"}"""
        }
        val picIds = (1..12).joinToString(",") { "\"p$it\"" }
        val post = decode(
            """
            {
                "id": 10,
                "idstr": "5000000000000010",
                "text": "十二图",
                "pic_urls": [$picUrls],
                "pic_ids": [$picIds]
            }
            """.trimIndent()
        )

        assertEquals(12, post.allLargeImageUrls.size)
        assertEquals(12, post.allThumbImageUrls.size)
        // 顺序按服务端下发：pic_urls 的前 9 张在前，pic_ids 补上的 3 张在后
        assertEquals("https://wx2.sinaimg.cn/large/p1.jpg", post.allLargeImageUrls.first())
        assertEquals("https://wx1.sinaimg.cn/large/p12.jpg", post.allLargeImageUrls.last())

        val items = post.mediaItems
        assertEquals(12, items.size)
        assertEquals(post.allLargeImageUrls, items.map { (it as StatusMediaItem.Picture).previewUrl })
    }

    @Test
    fun includesPicturesOnlyPresentInPicInfos() {
        val post = decode(
            """
            {
                "id": 11,
                "idstr": "5000000000000011",
                "text": "pic_infos 更全",
                "pic_ids": ["a1"],
                "pic_infos": {
                    "a1": {"pic_id": "a1", "large": {"url": "https://wx3.sinaimg.cn/large/a1.jpg"}},
                    "a2": {"pic_id": "a2", "large": {"url": "https://wx4.sinaimg.cn/large/a2.jpg"}}
                }
            }
            """.trimIndent()
        )

        assertEquals(2, post.allLargeImageUrls.size)
        assertEquals("https://wx1.sinaimg.cn/large/a1.jpg", post.allLargeImageUrls[0])
        assertEquals("https://wx4.sinaimg.cn/large/a2.jpg", post.allLargeImageUrls[1])
    }

    @Test
    fun topsUpMixMediaTruncatedToNine() {
        val mixItems = (1..9).joinToString(",") {
            """{"id": "m$it", "type": "pic", "data": {"pic_id": "m$it",
               "large": {"url": "https://wx1.sinaimg.cn/large/m$it.jpg"}}}"""
        }
        val picIds = (1..12).joinToString(",") { "\"m$it\"" }
        val post = decode(
            """
            {
                "id": 12,
                "idstr": "5000000000000012",
                "text": "混排只下发 9 条",
                "pic_ids": [$picIds],
                "mix_media_info": {"items": [$mixItems]}
            }
            """.trimIndent()
        )

        val items = post.mediaItems
        assertEquals(12, items.size)
        assertEquals("https://wx1.sinaimg.cn/large/m1.jpg", (items[0] as StatusMediaItem.Picture).previewUrl)
        assertEquals("https://wx1.sinaimg.cn/large/m9.jpg", (items[8] as StatusMediaItem.Picture).previewUrl)
        // mix 里没有的第 10~12 张按 pic_ids 顺序补在末尾
        assertEquals("https://wx1.sinaimg.cn/large/m10.jpg", (items[9] as StatusMediaItem.Picture).previewUrl)
        assertEquals("https://wx1.sinaimg.cn/large/m12.jpg", (items[11] as StatusMediaItem.Picture).previewUrl)
    }
}
