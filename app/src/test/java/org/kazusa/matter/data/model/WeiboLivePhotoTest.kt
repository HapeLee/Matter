package org.kazusa.matter.data.model

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.KtorClientFactory

/**
 * 实况照片（Live Photo）视频地址的还原。
 *
 * 服务端下发的 `pic_infos[].video` 常见 `http://g.us.sinaimg.cn/…`：
 * targetSdk 28+ 的明文策略会直接拒绝，表现为「保存实况」抛
 * `Cleartext HTTP traffic to g.us.sinaimg.cn not permitted`、长按预览黑屏。
 * 因此映射表里的视频地址必须是可直接请求的 https。
 */
class WeiboLivePhotoTest {

    private fun decode(json: String): WeiboStatusDto =
        KtorClientFactory.jsonConfig.decodeFromString<WeiboStatusDto>(json)

    @Test
    fun livePhotoVideoUrlIsUpgradedToHttps() {
        val post = decode(
            """
            {
                "id": 20,
                "idstr": "5000000000000020",
                "text": "实况图",
                "pic_ids": ["lp1"],
                "pic_infos": {
                    "lp1": {
                        "pic_id": "lp1",
                        "type": "livephoto",
                        "large": {"url": "https://wx2.sinaimg.cn/large/lp1.jpg"},
                        "video": "http://g.us.sinaimg.cn/o0/lp1.mp4"
                    }
                }
            }
            """.trimIndent()
        )

        val map = post.livePhotoVideoMap
        assertTrue(post.picInfos.getValue("lp1").isLivePhoto)
        // picId 与大图地址两种键都能查到，且值统一是可请求的 https
        assertEquals("https://g.us.sinaimg.cn/o0/lp1.mp4", map["lp1"])
        assertEquals("https://g.us.sinaimg.cn/o0/lp1.mp4", map["https://wx2.sinaimg.cn/large/lp1.jpg"])
        assertEquals(
            "https://g.us.sinaimg.cn/o0/lp1.mp4",
            map[weiboImageUrlFromId("lp1", WeiboImageSize.LARGE)]
        )
    }

    @Test
    fun nonHttpLiveVideoUrlIsDropped() {
        val post = decode(
            """
            {
                "id": 21,
                "idstr": "5000000000000021",
                "text": "异常实况地址",
                "pic_ids": ["lp2"],
                "pic_infos": {
                    "lp2": {
                        "pic_id": "lp2",
                        "type": "livephoto",
                        "large": {"url": "https://wx2.sinaimg.cn/large/lp2.jpg"},
                        "video": "weibo://livephoto/lp2"
                    }
                }
            }
            """.trimIndent()
        )

        // 既不能下载也不能播放的地址不登记，界面按普通图片处理（不至于整张图都点不开）
        assertNull(post.livePhotoVideoMap["lp2"])
        assertTrue(post.livePhotoVideoMap.isEmpty())
    }
}
