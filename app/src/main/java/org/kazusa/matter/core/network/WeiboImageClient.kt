package org.kazusa.matter.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 图片加载专用的微博 CDN 请求头策略。
 *
 * `*.sinaimg.cn` 带防盗链：同一个地址，不带 `Referer` 直接回 **403**，
 * 带上 `https://weibo.com/` 回 200（与域名分片、尺寸档位无关）。
 * 热门微博的配图基本常驻 CDN 边缘缓存，问题不容易暴露；
 * 楼中楼这类少有人访问的评论配图更容易落到真正校验 Referer 的节点上，
 * 表现就是「正文 / 主评论的图正常，嵌套回复里的图片 403」。
 *
 * 下载保存（[org.kazusa.matter.core.util.ImageSaver] / [org.kazusa.matter.core.util.VideoSaver]）
 * 与清晰度探测（[org.kazusa.matter.core.util.WeiboImageProbe]）早就带着同样的 Referer，
 * 这里把 Coil 的图片请求补齐到同一套请求头上。
 */
object WeiboImageClient {

    /** 与微博网页端一致的来源页 */
    private const val REFERER = "https://weibo.com/"

    /** 需要补 Referer 的域名后缀：微博图片 CDN 与微博网页域名 */
    private val refererHostSuffixes = listOf("sinaimg.cn", "weibo.com", "weibo.cn")

    /**
     * 图片加载共用客户端（懒加载单例）：
     * 只补一个请求头，连接池与默认 OkHttp 配置保持一致，不额外占用资源。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(RefererInterceptor)
            .build()
    }

    private fun isWeiboHost(host: String): Boolean =
        refererHostSuffixes.any { host == it || host.endsWith(".$it") }

    /**
     * 仅对微博域名补 `Referer`：
     * 第三方图床（视频封面、外链图等）通常不校验来源页，乱加请求头反而可能触发它们的校验规则。
     */
    private object RefererInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!isWeiboHost(request.url.host) || request.header("Referer") != null) {
                return chain.proceed(request)
            }
            return chain.proceed(
                request.newBuilder().header("Referer", REFERER).build()
            )
        }
    }
}
