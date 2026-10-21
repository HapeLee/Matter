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
}
