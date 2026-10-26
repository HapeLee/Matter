package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType

/**
 * 微博接口的域。
 *
 * 业务方法只写**路径**，host 与默认 `Referer` 由这里给出，避免 30 多个调用点各自
 * 拼 `"https://weibo.com" + path`、各自记默认 Referer。
 *
 * 两个内容域的能力边界（长期存在，不是过渡状态）：
 * - [WEB]：网页端 `weibo.com/ajax/…`。写操作只认这里的 `XSRF-TOKEN`；
 *   时间线 / 详情 / 评论 / 收藏 / 分组 / 主页等读接口也以它为准。
 * - [MOBILE]：移动站 `m.weibo.cn/api/…`。只在网页端没有等价能力时使用
 *   （热搜的段与同城榜、搜索、点赞列表、关注与粉丝容器），未登录请求会被风控回 432，
 *   需要游客凭据兜底。
 */
internal enum class WeiboHost(
    val baseUrl: String,
    /** 该域读接口的默认 Referer。 */
    val defaultReferer: String?
) {
    WEB("https://weibo.com", "https://weibo.com/"),
    MOBILE("https://m.weibo.cn", "https://m.weibo.cn/"),
    OPEN_API("https://api.weibo.com", null),
    PASSPORT("https://passport.weibo.com", null)
}

/**
 * 一次请求携带的凭据形态。
 *
 * 显式声明而不是「有形参就塞 Cookie」，是为了让「这个接口需不需要登录 / 是不是写操作」
 * 在调用点一眼可见：过去漏塞就退化成游客、错误又被当成接口不可用。
 */
internal sealed interface WeiboAuth {
    /** 只带 Cookie（读接口）。空串等价于 [Guest]。 */
    data class Session(val cookie: String) : WeiboAuth {
        val isUsable: Boolean get() = cookie.isNotBlank()
    }

    /** 网页域写操作：Cookie + `X-XSRF-TOKEN` + `Origin`，三者必须同源。 */
    data class Write(val credentials: WeiboWriteCredentials) : WeiboAuth

    /** 不显式指定，交给 `CookieAuth` 插件按域名兜底（游客凭据）。 */
    data object Guest : WeiboAuth
}

/**
 * 一个微博接口：域 + 路径 + 凭据 + Referer。
 *
 * 只描述「打哪个地址、用什么凭据」，不持有参数；参数、业务头仍由调用点的 lambda 补。
 */
internal class WeiboEndpoint(
    val host: WeiboHost,
    val path: String,
    val auth: WeiboAuth,
    /** null 表示用 [WeiboHost.defaultReferer]。 */
    private val explicitReferer: String? = null
) {
    val url: String get() = host.baseUrl + path

    /** 实际发出的 Referer；两个域都有默认值，`OPEN_API` / `PASSPORT` 可能为 null。 */
    val referer: String? get() = explicitReferer ?: host.defaultReferer
}

internal fun webEndpoint(
    path: String,
    auth: WeiboAuth,
    referer: String? = null
): WeiboEndpoint = WeiboEndpoint(WeiboHost.WEB, path, auth, referer)

internal fun mobileEndpoint(
    path: String,
    auth: WeiboAuth,
    referer: String? = null
): WeiboEndpoint = WeiboEndpoint(WeiboHost.MOBILE, path, auth, referer)

internal fun passportEndpoint(
    path: String,
    auth: WeiboAuth = WeiboAuth.Guest,
    referer: String? = null
): WeiboEndpoint = WeiboEndpoint(WeiboHost.PASSPORT, path, auth, referer)

internal fun openApiEndpoint(
    path: String,
    auth: WeiboAuth,
    referer: String? = null
): WeiboEndpoint = WeiboEndpoint(WeiboHost.OPEN_API, path, auth, referer)

/** `GET`：凭据与 Referer 由 [WeiboEndpoint] 决定，其余参数在 [block] 里补。 */
internal suspend fun HttpClient.weiboGet(
    endpoint: WeiboEndpoint,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = get(endpoint.url) {
    applyWeiboEndpoint(endpoint)
    block()
}

/** 表单 `POST`（`application/x-www-form-urlencoded`）：网页端的写操作只接受这种格式。 */
internal suspend fun HttpClient.weiboPostForm(
    endpoint: WeiboEndpoint,
    form: Parameters,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = submitForm(endpoint.url, form) {
    applyWeiboEndpoint(endpoint)
    block()
}

/** JSON `POST`：只有部分网页端写接口（删微博、收藏）要求 JSON 体。 */
internal suspend fun HttpClient.weiboPostJson(
    endpoint: WeiboEndpoint,
    json: String,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse = post(endpoint.url) {
    contentType(ContentType.Application.Json)
    setBody(json)
    applyWeiboEndpoint(endpoint)
    block()
}

/**
 * 统一写入凭据与 Referer。
 *
 * `X-Requested-With` 由 `defaultRequest` 全局设置，调用点不再重复声明。
 */
private fun HttpRequestBuilder.applyWeiboEndpoint(endpoint: WeiboEndpoint) {
    when (val auth = endpoint.auth) {
        is WeiboAuth.Session -> if (auth.isUsable) cookieHeader(auth.cookie)
        is WeiboAuth.Write -> {
            cookieHeader(auth.credentials.cookieHeader)
            header("X-XSRF-TOKEN", auth.credentials.token)
            header("Origin", WeiboHost.WEB.baseUrl)
        }
        WeiboAuth.Guest -> Unit
    }
    endpoint.referer?.let { header("Referer", it) }
}
