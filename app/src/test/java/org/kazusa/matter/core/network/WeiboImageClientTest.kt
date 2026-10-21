package org.kazusa.matter.core.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboImageClientTest {

    /** 楼中楼只给 pic_ids 时拼出来的地址就是这种形态 */
    private val nestedCommentImage = "https://wx1.sinaimg.cn/bmiddle/0064kYZFgy1h8k3m2nopqj30u0140wo5.jpg"

    private fun request(url: String, vararg headers: Pair<String, String>): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    @Test
    fun refererAndBrowserUserAgentAreAddedForWeiboCdn() {
        val decorated = WeiboImageClient.decorate(request(nestedCommentImage))

        assertEquals(WeiboRequestHeaders.CDN_REFERER, decorated.header("Referer"))
        // 图片请求必须与 API / 下载 / 探测共用同一份 UA；OkHttp 默认的 `okhttp/x.y.z`
        // 会被微博 CDN 的校验节点当成非浏览器请求直接 403
        val userAgent = decorated.header("User-Agent").orEmpty()
        assertEquals(WeiboRequestHeaders.USER_AGENT, userAgent)
        assertFalse(userAgent.startsWith("okhttp"))
    }

    @Test
    fun weiboWebHostsAreAlsoDecorated() {
        for (url in listOf("https://weibo.com/ajax/x", "https://m.weibo.cn/api/x")) {
            val decorated = WeiboImageClient.decorate(request(url))
            assertEquals(WeiboRequestHeaders.CDN_REFERER, decorated.header("Referer"))
            assertEquals(WeiboRequestHeaders.USER_AGENT, decorated.header("User-Agent"))
        }
    }

    @Test
    fun explicitlyProvidedHeadersAreNotOverridden() {
        val decorated = WeiboImageClient.decorate(
            request(nestedCommentImage, "Referer" to "https://m.weibo.cn/", "User-Agent" to "custom")
        )

        assertEquals("https://m.weibo.cn/", decorated.header("Referer"))
        assertEquals("custom", decorated.header("User-Agent"))
    }

    @Test
    fun thirdPartyHostsAreLeftUntouched() {
        val decorated = WeiboImageClient.decorate(request("https://cdn.example.com/a.jpg"))

        assertNull(decorated.header("Referer"))
        assertNull(decorated.header("User-Agent"))
    }

    @Test
    fun transientRejectionsAreRetriedButDeterministicFailuresAreNot() {
        assertTrue(WeiboImageClient.shouldRetry(403, "GET"))
        assertTrue(WeiboImageClient.shouldRetry(429, "GET"))
        assertTrue(WeiboImageClient.shouldRetry(503, "GET"))

        assertFalse(WeiboImageClient.shouldRetry(200, "GET"))
        assertFalse(WeiboImageClient.shouldRetry(404, "GET"))
        // 写请求重试会把一次操作变成两次，图片场景也不存在 POST
        assertFalse(WeiboImageClient.shouldRetry(403, "POST"))
    }
}
