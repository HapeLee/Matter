package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
