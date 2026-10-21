package org.kazusa.matter.core.network

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.StatusMediaItem
import org.kazusa.matter.data.model.WeiboStatusDto

/**
 * 媒体顺序解析：
 * - `mix_media_info` 存在时严格按服务端顺序还原图片与视频；
 * - 缺失时也不能让视频单独占一行，而是并入同一个媒体列表。
 */
class WeiboMixMediaTest {

    private fun decode(json: String): WeiboStatusDto =
        KtorClientFactory.jsonConfig.decodeFromString<WeiboStatusDto>(json)

    @Test
    fun keepsMixMediaOrderFromServer() {
        val post = decode(
            """
            {
                "id": 1,
                "idstr": "5000000000000001",
                "text": "图文混排",
                "mix_media_info": {
                    "selected_index": 0,
                    "items": [
                        {
                            "id": "pic_a",
                            "type": "pic",
                            "data": {
                                "pic_id": "pic_a",
                                "large": {"url": "https://wx1.sinaimg.cn/large/pic_a.jpg", "width": 1080, "height": 720}
                            }
                        },
                        {
                            "id": "video_a",
                            "type": "video",
                            "data": {
                                "page_pic": "https://wx1.sinaimg.cn/large/cover_a.jpg",
                                "page_title": "视频标题",
                                "media_info": {
                                    "mp4_720p_mp4": "https://video.weibo.com/a_720.mp4",
                                    "duration": 125
                                }
                            }
                        },
                        {
                            "id": "pic_b",
                            "type": "pic",
                            "data": {
                                "pic_id": "pic_b",
                                "large": {"url": "https://wx1.sinaimg.cn/large/pic_b.jpg", "width": 1080, "height": 720}
                            }
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val items = post.mediaItems
        assertEquals(3, items.size)
        assertEquals("https://wx1.sinaimg.cn/large/pic_a.jpg", (items[0] as StatusMediaItem.Picture).previewUrl)

        val video = items[1] as StatusMediaItem.Video
        assertEquals("https://wx1.sinaimg.cn/large/cover_a.jpg", video.previewUrl)
        assertEquals("https://video.weibo.com/a_720.mp4", video.videoUrl)
        assertEquals("视频标题", video.title)
        assertEquals("5000000000000001", video.mid)
        assertEquals(125, video.durationSeconds)

        assertEquals("https://wx1.sinaimg.cn/large/pic_b.jpg", (items[2] as StatusMediaItem.Picture).previewUrl)
    }

    @Test
    fun mergesPageVideoIntoPictureListWhenMixMediaMissing() {
        val post = decode(
            """
            {
                "id": 2,
                "idstr": "5000000000000002",
                "text": "没有 mix_media_info 的混排",
                "pic_ids": ["pic_c"],
                "pic_infos": {
                    "pic_c": {
                        "pic_id": "pic_c",
                        "large": {"url": "https://wx1.sinaimg.cn/large/pic_c.jpg"}
                    }
                },
                "page_info": {
                    "type": "video",
                    "page_title": "兜底视频",
                    "page_pic": "https://wx1.sinaimg.cn/large/cover_c.jpg",
                    "media_info": {"mp4_720p_mp4": "https://video.weibo.com/c_720.mp4"}
                }
            }
            """.trimIndent()
        )

        val items = post.mediaItems
        assertEquals(2, items.size)
        assertTrue(items[0] is StatusMediaItem.Picture)
        assertEquals("https://video.weibo.com/c_720.mp4", (items[1] as StatusMediaItem.Video).videoUrl)
    }

    @Test
    fun keepsSingleVideoAsFullWidthItem() {
        val post = decode(
            """
            {
                "id": 3,
                "idstr": "5000000000000003",
                "text": "纯视频",
                "page_info": {
                    "type": "video",
                    "page_pic": "https://wx1.sinaimg.cn/large/cover_d.jpg",
                    "media_info": {"stream_url": "https://video.weibo.com/d.mp4"}
                }
            }
            """.trimIndent()
        )

        val items = post.mediaItems
        assertEquals(1, items.size)
        assertEquals("https://video.weibo.com/d.mp4", (items[0] as StatusMediaItem.Video).videoUrl)
    }

    @Test
    fun mixMediaWithOnlyPicturesDoesNotDuplicatePicIds() {
        val post = decode(
            """
            {
                "id": 4,
                "idstr": "5000000000000004",
                "text": "纯图片",
                "pic_ids": ["pic_e", "pic_f"],
                "mix_media_info": {
                    "items": [
                        {"id": "pic_e", "type": "pic", "data": {"large": {"url": "https://wx1.sinaimg.cn/large/pic_e.jpg"}}}
                    ]
                }
            }
            """.trimIndent()
        )

        val items = post.mediaItems
        assertEquals(1, items.size)
        assertEquals("https://wx1.sinaimg.cn/large/pic_e.jpg", (items[0] as StatusMediaItem.Picture).previewUrl)
    }
}
