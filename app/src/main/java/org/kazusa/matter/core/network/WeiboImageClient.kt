package org.kazusa.matter.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 图片加载专用的微博 CDN 请求头与重试策略。
 *
 * `*.sinaimg.cn` 带防盗链，判定依据不止一个请求头：
 * - 缺 `Referer` 直接回 **403**，带上 `https://weibo.com/` 才回 200；
 * - 只补 `Referer` 仍不够：CDN 的少数节点还会看 `User-Agent`，OkHttp 默认的
 *   `okhttp/x.y.z` 会被当作非浏览器请求拒掉，同一个地址换正常 UA 立刻 200。
 *
 * 这解释了「有概率」而不是「必然」：热门微博的配图常驻边缘缓存，不走到校验节点；
 * 楼中楼这类少有人访问的评论配图更容易落到真正做校验的节点上，
 * 表现就是「正文 / 主评论的图正常，嵌套回复里的图片偶尔 403」。
 *
 * 因此这里做两件事：
 * 1. 补齐 [WeiboRequestHeaders] 里的浏览器 `User-Agent` 与 `Referer`，与 API、下载、
 *    探测链路保持同一份指纹。已显式带上的请求头不覆盖，避免破坏调用方的意图；
 * 2. 403 / 429 / 5xx 时同一地址重试一次：这类拒绝多为节点级偶发，
 *    直接把错误抛给界面只会让用户看到一张永久失败的图（Coil 不会自动重试）。
 */
object WeiboImageClient {

    /** 1 次正常请求 + 1 次重试 */
    private const val MAX_ATTEMPTS = 2

    /** 需要补请求头的域名后缀：微博图片 CDN、表情 CDN 与微博网页域名 */
    private val refererHostSuffixes = listOf("sinaimg.cn", "sinajs.cn", "weibo.com", "weibo.cn")

    /**
     * 需要带会话 Cookie 的图片域名：私信 / 附件的 mss 通道（`attachment.api.weibo.com`）。
     * 实测不带 Cookie 一律 403，且与 Referer / UA 无关，所以只能补凭据。
     */
    private val sessionCookieHostSuffixes = listOf("attachment.api.weibo.com")

    /**
     * 图片加载共用客户端（懒加载单例）：
     * 只补请求头与一次重试，连接池与默认 OkHttp 配置保持一致，不额外占用资源。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(WeiboImageInterceptor)
            .build()
    }

    /**
     * Coil 专用客户端：在共用客户端之上补一层 Cookie 注入（连接池、拦截器全部复用）。
     *
     * Cookie 只发给 mss 附件通道：公开 CDN 不需要，带上反而多泄露一份凭据；
     * [cookieProvider] 由调用方提供会话快照（见 `SessionCookieSnapshot`），为空时按匿名请求。
     */
    fun clientWithSessionCookie(cookieProvider: () -> String): OkHttpClient =
        client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (!requiresSessionCookie(request.url.host) || request.header("Cookie") != null) {
                    chain.proceed(request)
                } else {
                    val cookie = cookieProvider()
                    if (cookie.isBlank()) {
                        chain.proceed(request)
                    } else {
                        chain.proceed(request.newBuilder().header("Cookie", cookie).build())
                    }
                }
            }
            .build()

    private fun isWeiboHost(host: String): Boolean =
        refererHostSuffixes.any { host == it || host.endsWith(".$it") }

    private fun requiresSessionCookie(host: String): Boolean =
        sessionCookieHostSuffixes.any { host == it || host.endsWith(".$it") }

    /**
     * 仅对微博域名补请求头：
     * 第三方图床（视频封面、外链图等）通常不校验来源页，乱加请求头反而可能触发它们的校验规则。
     */
    internal fun decorate(request: Request): Request {
        if (!isWeiboHost(request.url.host)) return request
        val builder = request.newBuilder()
        if (request.header("Referer") == null) {
            builder.header("Referer", WeiboRequestHeaders.CDN_REFERER)
        }
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", WeiboRequestHeaders.USER_AGENT)
        }
        return builder.build()
    }

    /** 可重试的响应：偶发风控 / 限流 / 上游抖动。404 等确定性失败不重试。 */
    internal fun shouldRetry(code: Int, method: String): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        return code == 403 || code == 429 || code in 500..599
    }

    private object WeiboImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = decorate(chain.request())
            var response = chain.proceed(request)
            var attempt = 1
            while (attempt < MAX_ATTEMPTS && shouldRetry(response.code, request.method)) {
                // 必须先关掉上一次响应体，否则连接无法归还给连接池
                response.close()
                attempt++
                response = chain.proceed(request)
            }
            return response
        }
    }
}
