package org.kazusa.matter.core.network

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.session.SessionExpiredBus
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 轮换落盘的端到端约束。
 *
 * 起因是实测（2026-11-23）：带着 `domain=.weibo.com` 的 SUB 请求 `m.weibo.cn`，
 * 服务端会回 `SUB=deleted; Max-Age=0`。若把这条当成新值落盘，Cookie 头会变成
 * `SUB=deleted`，此后所有请求都被判未登录——一次请求就能废掉整份凭据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionCookieRotationTest {

    private suspend fun loggedInPrefs(): AuthPreferences {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)
        prefs.clearSession()
        prefs.saveSession(
            uid = "123",
            sub = REAL_SUB,
            subp = REAL_SUBP,
            rawCookie = "SUB=$REAL_SUB; SUBP=$REAL_SUBP"
        )
        return prefs
    }

    /** 构造一个「响应带指定 Set-Cookie」的服务；插件 onResponse 会驱动轮换落盘。 */
    private fun serviceWith(prefs: AuthPreferences, setCookies: List<String>): WeiboApiService {
        val source = CookieFallbackSource()
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"ok":1,"data":{"login":true,"uid":"123"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.SetCookie, setCookies)
                )
            }
        ) {
            install(CookieAuth) { fallbackSource = source }
        }
        return WeiboApiService(client, prefs, SessionExpiredBus(), source)
    }

    @Test
    fun 删除指令不覆盖本地凭据() = runTest {
        val prefs = loggedInPrefs()
        val service = serviceWith(
            prefs,
            listOf(
                "SUB=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/; domain=.weibo.cn",
                "SUBP=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/; domain=.weibo.cn"
            )
        )

        service.verifyConfig("SUB=$REAL_SUB; SUBP=$REAL_SUBP")

        val session = prefs.sessionFlow.first()
        assertEquals(REAL_SUB, session.sub)
        assertEquals(REAL_SUBP, session.subp)
        // 凭据头必须还是原来那份，不能被 "deleted" 污染
        assertEquals("SUB=$REAL_SUB; SUBP=$REAL_SUBP", session.cookieHeader)
    }

    @Test
    fun 正常轮换写回并立刻生效() = runTest {
        val prefs = loggedInPrefs()
        val service = serviceWith(
            prefs,
            listOf("SUB=$NEW_SUB; path=/; domain=.weibo.cn; Max-Age=31536000")
        )

        service.verifyConfig("SUB=$REAL_SUB; SUBP=$REAL_SUBP")

        val session = prefs.sessionFlow.first()
        assertEquals(NEW_SUB, session.sub)
        // cookieHeader 以 sub 覆盖 rawCookie 同名项，轮换后下个请求就用新值
        assertEquals("SUB=$NEW_SUB; SUBP=$REAL_SUBP", session.cookieHeader)
    }

    @Test
    fun 过短的占位值不覆盖() = runTest {
        val prefs = loggedInPrefs()
        // 非删除指令，但明显不是真实凭据（实测真实长度都在 30 以上）
        val service = serviceWith(prefs, listOf("SUB=0; path=/; Max-Age=3600"))

        service.verifyConfig("SUB=$REAL_SUB; SUBP=$REAL_SUBP")

        assertEquals(REAL_SUB, prefs.sessionFlow.first().sub)
    }

    private companion object {
        /** 真实游客 SUB 的形态（98 字符），这里换成固定样本，只用于比较长度与内容。 */
        const val REAL_SUB =
            "_2AkMcWKEAf8NxqwJRmvwUyW3kbI90wgHEieKqBFDbJRMxHRl-yT9kqn0HtRB6N9iP75ZOmI-Ea25QgjfkKB6hUUtj4c1Y"
        const val REAL_SUBP = "0033WrSXqPxfM72-Ws9jqgMF55529P9D9WWZTp3bFzy6ZgxnSLRK6mV5"
        const val NEW_SUB =
            "_2AkMcNEWSUBPLACEHOLDERxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }
}
