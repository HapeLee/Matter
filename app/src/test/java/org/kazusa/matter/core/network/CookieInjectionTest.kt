package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 头唯一由 CookieAuth 插件写入（T5）：
 * 显式声明 > 兜底来源（仅微博内容域）> 不注入；任何情况不产生双 Cookie 头。
 */
class CookieInjectionTest {

    private class Seen(val url: String, val cookies: List<String>)

    private fun pluginClient(
        fallback: CookieFallbackSource? = null,
        sink: MutableList<Seen>
    ): HttpClient {
        val handler: MockRequestHandler = { request ->
            sink.add(Seen(request.url.toString(), request.headers.getAll(HttpHeaders.Cookie).orEmpty()))
            respond("ok")
        }
        return HttpClient(MockEngine(handler)) {
            install(CookieAuth) {
                fallbackSource = fallback
            }
        }
    }

    @Test
    fun `显式声明的凭据被注入一次`() = runBlocking {
        val seen = mutableListOf<Seen>()
        val c = pluginClient(sink = seen)
        try {
            c.get("https://m.weibo.cn/api/config") { cookieHeader("SUB=abc; SUBP=def") }
            assertEquals(listOf("SUB=abc; SUBP=def"), seen.single().cookies)
        } finally {
            c.close()
        }
    }

    @Test
    fun `空白显式值等价于不指定`() = runBlocking {
        val seen = mutableListOf<Seen>()
        val c = pluginClient(sink = seen)
        try {
            c.get("https://m.weibo.cn/api/config") { cookieHeader(" ") }
            assertEquals(emptyList<String>(), seen.single().cookies)
        } finally {
            c.close()
        }
    }

    @Test
    fun `微博内容域无显式凭据时走兜底来源`() = runBlocking {
        val fallback = CookieFallbackSource().apply { bind { "SUB=guest" } }
        val seen = mutableListOf<Seen>()
        val c = pluginClient(fallback, seen)
        try {
            c.get("https://weibo.com/ajax/feed/hottimeline")
            assertEquals(listOf("SUB=guest"), seen.single().cookies)
        } finally {
            c.close()
        }
    }

    @Test
    fun `非微博域不注入兜底凭据`() = runBlocking {
        val fallback = CookieFallbackSource().apply { bind { "SUB=guest" } }
        val seen = mutableListOf<Seen>()
        val c = pluginClient(fallback, seen)
        try {
            c.get("https://login.sina.com.cn/sso/v2/sms/send")
            c.get("https://passport.weibo.com/visitor/genvisitor")
            assertEquals(emptyList<String>(), seen[0].cookies)
            assertEquals(emptyList<String>(), seen[1].cookies)
        } finally {
            c.close()
        }
    }

    @Test
    fun `响应里的 SUB 轮换交给落盘回调`() = runBlocking {
        val received = mutableListOf<Pair<String, List<String>>>()
        val fallback = CookieFallbackSource().apply {
            bind { "SUB=old" }
            bindRotationSink { host, setCookies -> received.add(host to setCookies) }
        }
        val c = HttpClient(MockEngine {
            respond(
                "ok",
                headers = headersOf(
                    HttpHeaders.SetCookie,
                    listOf("SUB=new; Path=/; Domain=.weibo.cn", "SUBP=newp; Path=/")
                )
            )
        }) {
            install(CookieAuth) { fallbackSource = fallback }
        }
        try {
            c.get("https://m.weibo.cn/api/config")
            assertEquals(1, received.size)
            assertEquals("m.weibo.cn", received.single().first)
            assertEquals(2, received.single().second.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun `不含 SUB 的 Set-Cookie 不触发落盘`() = runBlocking {
        val received = mutableListOf<Pair<String, List<String>>>()
        val fallback = CookieFallbackSource().apply {
            bind { "SUB=old" }
            bindRotationSink { host, setCookies -> received.add(host to setCookies) }
        }
        val c = HttpClient(MockEngine {
            respond(
                "ok",
                headers = headersOf(
                    HttpHeaders.SetCookie,
                    listOf("_T_WM=1; Path=/", "XSRF-TOKEN=abc; Path=/")
                )
            )
        }) {
            install(CookieAuth) { fallbackSource = fallback }
        }
        try {
            c.get("https://m.weibo.cn/api/config")
            assertTrue(received.isEmpty())
        } finally {
            c.close()
        }
    }

    @Test
    fun `Set-Cookie 的删除指令不被解析为凭据值`() {
        // 实测：带 .weibo.com 的 SUB 请求 m.weibo.cn，服务端回 SUB=deleted; Max-Age=0
        assertNull(parseSetCookie("SUB=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/"))
        assertNull(parseSetCookie("SUB=DELETED; Max-Age=0"))
        assertNull(parseSetCookie("SUB=; path=/"))
        // 即使没写 deleted，Max-Age<=0 同样表示删除
        assertNull(parseSetCookie("SUB=abc; Max-Age=0"))
        assertNull(parseSetCookie("SUB=abc; Max-Age=-1"))

        // 正常值原样返回；带引号的值去掉引号
        assertEquals("SUB" to "abc", parseSetCookie("SUB=abc; path=/; domain=.weibo.cn"))
        assertEquals("XSRF-TOKEN" to "506fe1", parseSetCookie("XSRF-TOKEN=506fe1; Max-Age=1200"))
        assertEquals("SUB" to "quoted", parseSetCookie("SUB=\"quoted\"; path=/"))
    }

    @Test
    fun `已有 Cookie 头时不重复注入`() = runBlocking {
        val seen = mutableListOf<Seen>()
        val c = pluginClient(sink = seen)
        try {
            // 模拟引擎/其他组件已写入的头：插件不得再追加（双头行为随引擎漂移）
            c.get("https://m.weibo.cn/api/config") {
                header(HttpHeaders.Cookie, "SUB=preset")
                cookieHeader("SUB=explicit")
            }
            assertEquals(listOf("SUB=preset"), seen.single().cookies)
        } finally {
            c.close()
        }
    }
}
