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
 *
 * 同时承担反向通道：[bindRotationSink] 接收响应里的 `Set-Cookie`。微博的
 * `SUB` / `SUBP` 是滑动凭据，浏览器会跟着服务端的轮换更新，Matter 此前只发不收，
 * 本地凭据永远停在登录那一刻——这是「用一段时间就掉登录」的结构性原因。
 */
class CookieFallbackSource {
    @Volatile
    private var source: (suspend () -> String)? = null

    /** 轮换落盘回调；未绑定时 Set-Cookie 直接丢弃（旧行为）。 */
    @Volatile
    private var rotationSink: (suspend (String, List<String>) -> Unit)? = null

    fun bind(source: suspend () -> String) {
        this.source = source
    }

    fun bindRotationSink(sink: suspend (host: String, setCookies: List<String>) -> Unit) {
        this.rotationSink = sink
    }

    internal suspend fun resolve(): String = source?.invoke().orEmpty()

    internal suspend fun rotate(host: String, setCookies: List<String>) {
        rotationSink?.invoke(host, setCookies)
    }
}

/** `Set-Cookie` 里会参与登录态的长期项；其余短期项不值得每响应写一次 DataStore。 */
private fun isSessionCredentialCookie(header: String): Boolean {
    val name = header.substringBefore('=').trim()
    return name.equals("SUB", ignoreCase = true) || name.equals("SUBP", ignoreCase = true)
}

/**
 * 解析一条 `Set-Cookie` 的键值；返回 `null` 表示这条是**删除指令**或无效。
 *
 * 实测（2026-11-23）：带着 `domain=.weibo.com` 的 SUB 去请求 `m.weibo.cn`，
 * 服务端会回
 *
 * ```
 * SUB=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/; domain=.weibo.cn
 * ```
 *
 * 这是「把 .weibo.cn 域上的 SUB 删掉」的指令，不是新的凭据值。把它当成新值合并，
 * Cookie 头会变成 `SUB=deleted`，此后每个请求都被按未登录处理——等于**自毁凭据**。
 * 浏览器遇到这种 Cookie 是删除而非赋值，这里必须一致。
 *
 * 判据取三条中最先命中的一条：字面量 `deleted`、值为空、`Max-Age<=0`。
 * `expires` 在过去也算删除，但 HTTP 日期格式有 `01-Jan-1970` 这种 RFC1123 不认的变体，
 * 直接用 `Max-Age` 判断更可靠（微博这两个头总是成对出现）。
 */
internal fun parseSetCookie(header: String): Pair<String, String>? {
    val pair = header.substringBefore(';').trim()
    val eq = pair.indexOf('=')
    if (eq <= 0) return null
    val name = pair.substring(0, eq).trim()
    val value = pair.substring(eq + 1).trim().removeSurrounding("\"")
    if (name.isEmpty()) return null
    if (value.isEmpty() || value.equals("deleted", ignoreCase = true)) return null
    val maxAge = Regex("(?i)max-age=(-?\\d+)").find(header)
        ?.groupValues?.get(1)?.toLongOrNull()
    if (maxAge != null && maxAge <= 0) return null
    return name to value
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
    // 服务端轮换的长期凭据回写：浏览器语义（cookie jar），由 CookieFallbackSource 的
    // sink 落到 DataStore。只挑 SUB/SUBP，其余 Set-Cookie 内容不参与登录态、
    // 也没有必要每响应触发一次磁盘写。
    onResponse { response ->
        val source = fallback ?: return@onResponse
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        if (setCookies.isEmpty()) return@onResponse
        if (setCookies.none(::isSessionCredentialCookie)) return@onResponse
        source.rotate(response.call.request.url.host, setCookies)
    }
}
