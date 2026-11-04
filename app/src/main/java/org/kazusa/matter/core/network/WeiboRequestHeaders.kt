package org.kazusa.matter.core.network

/**
 * 访问微博接口与 CDN 的统一请求头。
 *
 * 同一个账号在 API、图片渲染、图片下载、清晰度探测、视频下载这几条链路上应当使用同一份
 * `User-Agent` / `Referer`：微博会把「同一会话出现多种客户端指纹」当成异常信号，
 * 轻则图片被 CDN 判 403，重则写操作被判「操作频繁」。
 *
 * 之前这些值散落在各处各写一遍（`Android 13` 短 UA、`Android 14` 短 UA、完整 Chrome UA
 * 三种并存），任何一处漏改都会重新引入「有些图加载失败」这类难复现的问题，因此统一在这里维护。
 */
object WeiboRequestHeaders {

    /**
     * 与微博移动网页端一致的浏览器 UA。
     * Android 版本段固定写死，不随真实设备变化，避免成为额外的区分特征。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 微博图片 CDN 防盗链校验的来源页；缺它直接 403。 */
    const val CDN_REFERER = "https://weibo.com/"

    /**
     * 需要补防盗链请求头的域名后缀：微博 / 新浪系 CDN 与网页域名。
     *
     * 视频 CDN（`f.video.weibocdn.com`、`f.us.sinaimg.cn`）与图片 CDN 同属一套校验，
     * 播放器的请求也必须带上同一份指纹，否则会落到做校验的节点上被 403。
     */
    private val refererHostSuffixes = listOf(
        "sinaimg.cn",
        "sinajs.cn",
        "sina.com.cn",
        "weibocdn.com",
        "weibo.com",
        "weibo.cn",
        "miaopai.com"
    )

    /** 播放器 / 下载器用的防盗链请求头（不含 User-Agent，UA 由各客户端单独设置）。 */
    val CDN_REQUEST_HEADERS: Map<String, String> = mapOf("Referer" to CDN_REFERER)

    /** 该主机是否属于微博 / 新浪系域名，需要补 [USER_AGENT] 与 [CDN_REFERER]。 */
    fun isWeiboHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return false
        return refererHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
    }
}
