package org.kazusa.matter.core.network

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.kazusa.matter.data.model.WeiboPageInfoDto

class WeiboVideoModelTest {

    @Test
    fun deserializesNormalVideoPageInfo() {
        val json = """
            {
                "type": "video",
                "page_title": "测试视频",
                "page_pic": "https://wx1.sinaimg.cn/large/test.jpg",
                "media_info": {
                    "stream_url": "https://video.weibo.com/test.mp4",
                    "mp4_720p_mp4": "https://video.weibo.com/test_720.mp4",
                    "mp4_hd_url": "https://video.weibo.com/test_hd.mp4",
                    "mp4_sd_url": "https://video.weibo.com/test_sd.mp4"
                }
            }
        """.trimIndent()

        val pageInfo = KtorClientFactory.jsonConfig.decodeFromString<WeiboPageInfoDto>(json)
        assertNotNull(pageInfo.mediaInfo)
        val media = pageInfo.mediaInfo!!
        assertEquals("https://video.weibo.com/test_720.mp4", media.bestVideoUrl)
        assertEquals(4, media.availableStreams.size)
    }

    @Test
    fun deserializesWhenPlaybackListIsEmptyStringOrMissing() {
        val jsonWithEmptyString = """
            {
                "type": "video",
                "page_title": "测试视频2",
                "media_info": {
                    "stream_url": "https://video.weibo.com/test.mp4",
                    "playback_list": ""
                }
            }
        """.trimIndent()

        val pageInfo = KtorClientFactory.jsonConfig.decodeFromString<WeiboPageInfoDto>(jsonWithEmptyString)
        assertNotNull(pageInfo.mediaInfo)
        val media = pageInfo.mediaInfo!!
        assertEquals("https://video.weibo.com/test.mp4", media.bestVideoUrl)
        assertEquals(1, media.availableStreams.size)
    }

    @Test
    fun blankDirectFieldsFallBackToNextPlayableField() {
        // 微博会把未转码完成的档位下发成空串而不是 null，`?:` 判空会直接把空地址交给播放器
        val json = """
            {
                "type": "video",
                "page_title": "测试视频",
                "media_info": {
                    "mp4_720p_mp4": "",
                    "mp4_hd_url": "https://video.weibo.com/test_hd.mp4",
                    "stream_url": "https://video.weibo.com/test.mp4"
                }
            }
        """.trimIndent()

        val media = KtorClientFactory.jsonConfig
            .decodeFromString<WeiboPageInfoDto>(json)
            .mediaInfo!!

        assertEquals("https://video.weibo.com/test_hd.mp4", media.bestVideoUrl)
        assertEquals(
            listOf("https://video.weibo.com/test_hd.mp4", "https://video.weibo.com/test.mp4"),
            media.availableStreams.map { it.url }
        )
    }

    @Test
    fun protocolRelativeVideoUrlIsNormalized() {
        val json = """
            {
                "type": "video",
                "media_info": {
                    "stream_url": "//f.video.weibocdn.com/o0/test.mp4"
                }
            }
        """.trimIndent()

        val media = KtorClientFactory.jsonConfig
            .decodeFromString<WeiboPageInfoDto>(json)
            .mediaInfo!!

        assertEquals("https://f.video.weibocdn.com/o0/test.mp4", media.bestVideoUrl)
    }

    @Test
    fun deserializesWhenPlaybackListHasArrayWithMixedTypes() {
        val json = """
            {
                "type": "video",
                "page_title": "测试视频3",
                "media_info": {
                    "stream_url": "https://video.weibo.com/test.mp4",
                    "playback_list": [
                        {
                            "meta": {
                                "label": "1080P 超清",
                                "quality_desc": "1080p",
                                "quality_label": "1080p"
                            },
                            "play_info": {
                                "url": "https://video.weibo.com/1080p.mp4",
                                "width": "1920",
                                "height": "1080",
                                "bitrate": "2500000"
                            }
                        },
                        {
                            "meta": {
                                "label": "720P 高清"
                            },
                            "play_info": {
                                "url": "https://video.weibo.com/720p.mp4",
                                "width": 1280,
                                "height": 720,
                                "bitrate": 1200000
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val pageInfo = KtorClientFactory.jsonConfig.decodeFromString<WeiboPageInfoDto>(json)
        assertNotNull(pageInfo.mediaInfo)
        val media = pageInfo.mediaInfo!!
        val streams = media.availableStreams
        assertEquals(3, streams.size)
        assertEquals("1080P 超清", streams[0].label)
        assertEquals(1080, streams[0].height)
        assertEquals("720P 高清", streams[1].label)
        assertEquals(720, streams[1].height)
    }
}
