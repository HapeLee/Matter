package org.kazusa.matter.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.withWeiboImageSize

class WeiboImageProbeTest {

    @Before
    @After
    fun cleanUp() {
        WeiboImageProbe.clearCache()
    }

    @Test
    fun testProbeTiersPriorityOrder() {
        val tiers = WeiboImageProbe.PROBE_TIERS
        assertEquals(4, tiers.size)
        assertEquals(WeiboImageSize.ORIGINAL, tiers[0])
        assertEquals("original", tiers[0].segment)
        assertEquals(WeiboImageSize.WORIGINAL, tiers[1])
        assertEquals("woriginal", tiers[1].segment)
        assertEquals(WeiboImageSize.MW2000, tiers[2])
        assertEquals("mw2000", tiers[2].segment)
        assertEquals(WeiboImageSize.LARGE, tiers[3])
        assertEquals("large", tiers[3].segment)
    }

    @Test
    fun testUrlTierTransformations() {
        val originalSample = "https://wx1.sinaimg.cn/large/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg"

        assertEquals(
            "https://wx1.sinaimg.cn/original/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.ORIGINAL)
        )
        assertEquals(
            "https://wx1.sinaimg.cn/woriginal/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.WORIGINAL)
        )
        assertEquals(
            "https://wx1.sinaimg.cn/mw2000/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.MW2000)
        )
        assertEquals(
            "https://wx1.sinaimg.cn/large/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.LARGE)
        )
        assertEquals(
            "https://wx1.sinaimg.cn/wap720/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.MEDIUM)
        )
        assertEquals(
            "https://wx1.sinaimg.cn/bmiddle/001OmRRfgy1i9rjmavikrj60v91votdv02.jpg",
            originalSample.withWeiboImageSize(WeiboImageSize.THUMBNAIL)
        )
    }

    @Test
    fun testOversizedThresholdDetection() {
        val exact10Mb = 10L * 1024L * 1024L
        val justOver10Mb = exact10Mb + 1L
        val below10Mb = exact10Mb - 1L

        val normalResult = ImageProbeResult(
            targetUrl = "https://wx1.sinaimg.cn/original/test.jpg",
            contentLength = below10Mb
        )
        assertFalse("10MB 或以下应允许自动加载", normalResult.isOversized)

        val exactResult = ImageProbeResult(
            targetUrl = "https://wx1.sinaimg.cn/original/test.jpg",
            contentLength = exact10Mb
        )
        assertFalse("刚好 10MB 应允许自动加载", exactResult.isOversized)

        val oversizedResult = ImageProbeResult(
            targetUrl = "https://wx1.sinaimg.cn/original/test.jpg",
            contentLength = justOver10Mb
        )
        assertTrue("超过 10MB 应被标记为 oversized，禁止自动加载", oversizedResult.isOversized)
    }

    @Test
    fun testFormattedSizeOutput() {
        val unknown = ImageProbeResult("url", -1L)
        assertNull(unknown.formattedSize)

        val zero = ImageProbeResult("url", 0L)
        assertNull(zero.formattedSize)

        val kbImage = ImageProbeResult("url", 512L * 1024L)
        assertEquals("512 KB", kbImage.formattedSize)

        val mbImage = ImageProbeResult("url", (14.5 * 1024 * 1024).toLong())
        assertEquals("14.5 MB", mbImage.formattedSize)
    }

    @Test
    fun testCachePutAndGet() {
        val url = "https://wx1.sinaimg.cn/large/sample.jpg"
        assertNull(WeiboImageProbe.getCached(url))

        val result = ImageProbeResult(
            targetUrl = "https://wx1.sinaimg.cn/original/sample.jpg",
            contentLength = 12L * 1024L * 1024L,
            tier = WeiboImageSize.ORIGINAL
        )
        WeiboImageProbe.putCache(url, result)

        val cached = WeiboImageProbe.getCached(url)
        assertNotNull(cached)
        assertEquals("https://wx1.sinaimg.cn/original/sample.jpg", cached?.targetUrl)
        assertEquals(WeiboImageSize.ORIGINAL, cached?.tier)
        assertTrue(cached?.isOversized == true)

        WeiboImageProbe.clearCache()
        assertNull(WeiboImageProbe.getCached(url))
    }
}
