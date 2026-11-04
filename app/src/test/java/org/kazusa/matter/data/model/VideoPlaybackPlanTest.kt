package org.kazusa.matter.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPlanTest {

    private fun stream(url: String, label: String = "", height: Int = 0) =
        VideoStreamInfo(label = label, url = url, height = height)

    // ---- normalizeVideoUrl ----

    @Test
    fun blankOrNullOrSchemeLessUrlIsRejected() {
        assertNull(normalizeVideoUrl(null))
        assertNull(normalizeVideoUrl(""))
        assertNull(normalizeVideoUrl("   "))
        assertNull(normalizeVideoUrl("ftp://f.video.weibocdn.com/a.mp4"))
        assertNull(normalizeVideoUrl("https://"))
        assertNull(normalizeVideoUrl("mp4_720p"))
    }

    @Test
    fun protocolRelativeUrlGetsHttpsScheme() {
        assertEquals(
            "https://f.video.weibocdn.com/o0/a.mp4",
            normalizeVideoUrl("//f.video.weibocdn.com/o0/a.mp4")
        )
    }

    @Test
    fun weiboCdnHttpUrlIsUpgradedToHttps() {
        assertEquals(
            "https://f.video.weibocdn.com/o0/a.mp4?label=mp4_720p",
            normalizeVideoUrl("http://f.video.weibocdn.com/o0/a.mp4?label=mp4_720p")
        )
        assertEquals(
            "https://f.us.sinaimg.cn/o0/a.mp4",
            normalizeVideoUrl("http://f.us.sinaimg.cn/o0/a.mp4")
        )
    }

    @Test
    fun thirdPartyHttpUrlIsKeptAsIs() {
        // 第三方站不一定支持 TLS，盲目升级会把本来能播的视频改坏
        assertEquals(
            "http://cdn.example.com/v/a.mp4",
            normalizeVideoUrl("http://cdn.example.com/v/a.mp4")
        )
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            "https://f.video.weibocdn.com/a.mp4",
            normalizeVideoUrl("  https://f.video.weibocdn.com/a.mp4  ")
        )
    }

    // ---- buildVideoPlaybackCandidates ----

    @Test
    fun defaultUrlComesFirstAndKeepsServerLabel() {
        val candidates = buildVideoPlaybackCandidates(
            videoUrl = "https://f.video.weibocdn.com/720.mp4",
            streams = listOf(
                stream("https://f.video.weibocdn.com/1080.mp4", "1080P 超清", 1080),
                stream("https://f.video.weibocdn.com/720.mp4", "720P 高清", 720)
            )
        )

        assertEquals(listOf("720P 高清", "1080P 超清"), candidates.map { it.label })
        assertEquals("https://f.video.weibocdn.com/720.mp4", candidates[0].url)
    }

    @Test
    fun defaultUrlMissingFromStreamsIsPrepended() {
        val candidates = buildVideoPlaybackCandidates(
            videoUrl = "https://f.video.weibocdn.com/default.mp4",
            streams = listOf(stream("https://f.video.weibocdn.com/1080.mp4", "1080P 超清", 1080))
        )

        assertEquals(2, candidates.size)
        assertEquals("默认", candidates[0].label)
        assertEquals("https://f.video.weibocdn.com/default.mp4", candidates[0].url)
    }

    @Test
    fun blankStreamsAreDroppedInsteadOfBecomingUnplayableTargets() {
        val candidates = buildVideoPlaybackCandidates(
            videoUrl = "",
            streams = listOf(
                stream("", "空档位", 1080),
                stream("   ", "空白档位", 720),
                stream("https://f.video.weibocdn.com/480.mp4", "480P", 480)
            )
        )

        assertEquals(1, candidates.size)
        assertEquals("https://f.video.weibocdn.com/480.mp4", candidates[0].url)
    }

    @Test
    fun duplicatedUrlsAreCollapsed() {
        val candidates = buildVideoPlaybackCandidates(
            videoUrl = "http://f.video.weibocdn.com/a.mp4",
            streams = listOf(
                stream("https://f.video.weibocdn.com/a.mp4", "720P 高清", 720),
                stream("//f.video.weibocdn.com/a.mp4", "重复档位", 720)
            )
        )

        assertEquals(1, candidates.size)
        assertEquals("https://f.video.weibocdn.com/a.mp4", candidates[0].url)
    }

    @Test
    fun noUsableUrlProducesEmptyPlan() {
        assertTrue(buildVideoPlaybackCandidates(videoUrl = "", streams = emptyList()).isEmpty())
        assertTrue(
            buildVideoPlaybackCandidates(
                videoUrl = " ",
                streams = listOf(stream("", "空档位"))
            ).isEmpty()
        )
    }

    @Test
    fun labelFallsBackWhenServerOmitsIt() {
        val candidates = buildVideoPlaybackCandidates(
            videoUrl = "https://f.video.weibocdn.com/a.mp4",
            streams = listOf(stream("https://f.video.weibocdn.com/a.mp4", "  "))
        )

        assertEquals("画质 1", candidates[0].label)
    }
}
