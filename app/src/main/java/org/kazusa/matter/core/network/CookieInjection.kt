package org.kazusa.matter.core.network

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

/**
 * Cookie 注入唯一出口（docs/credential-auth-review.md T5 / A1）。
 *
 * 此前 40+ 个业务方法各自手写 `header("Cookie", …)`，新增接口漏写就被服务端
 * 按游客处理，错误还会被误判成接口不可用。现在：
 * - 业务方法只调 [cookieHeader] 把凭据挂到请求属性上，头由 [CookieAuth] 统一写入；
 * - 未显式指定的微博内容域请求按 [CookieFallbackSource] 兜底注入（当前会话，
 *   否则缓存的游客凭据；不主动发起请求，避免插件内递归）；
 * - 任何情况下都不允许出现双 Cookie 头——服务端按第一个取值，行为随引擎漂移。
 */
private val ExplicitCookieKey = AttributeKey<String>("ExplicitSessionCookie")

/** 微博内容域：这些 host 上的请求未显式指定 Cookie 时走兜底注入。 */
private val WEIBO_CONTENT_HOSTS = setOf("m.weibo.cn", "weibo.cn", "weibo.com", "api.weibo.com")

/**
 * 可后置绑定的兜底凭据来源。HttpClient 构建时 WeiboApiService（真正的来源）
 * 尚未创建，所以先传空壳、由服务在初始化时 bind。
 */
class CookieFallbackSource {
    @Volatile
    private var source: (suspend () -> String)? = null

    fun bind(source: suspend () -> String) {
        this.source = source
    }

    internal suspend fun resolve(): String = source?.invoke().orEmpty()
}

class CookieAuthConfig {
    /** 兜底凭据来源，默认无（不注入）。 */
    var fallbackSource: CookieFallbackSource? = null
}

/** 业务代码用这个扩展声明凭据，不再直接写 Cookie 头。空白值等价于不指定。 */
fun HttpRequestBuilder.cookieHeader(value: String) {
    if (value.isNotBlank()) attributes.put(ExplicitCookieKey, value)
}

val CookieAuth: ClientPlugin<CookieAuthConfig> = createClientPlugin("CookieAuth", ::CookieAuthConfig) {
    val fallback = pluginConfig.fallbackSource
    onRequest { request, _ ->
        if (request.headers.contains(HttpHeaders.Cookie)) return@onRequest
        val explicit = request.attributes.getOrNull(ExplicitCookieKey)
        if (explicit != null) {
            request.headers.set(HttpHeaders.Cookie, explicit)
            return@onRequest
        }
        if (request.url.host !in WEIBO_CONTENT_HOSTS) return@onRequest
        val cookie = fallback?.resolve().orEmpty()
        if (cookie.isNotBlank()) {
            request.headers.set(HttpHeaders.Cookie, cookie)
        }
    }
}
