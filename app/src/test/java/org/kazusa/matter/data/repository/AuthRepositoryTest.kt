package org.kazusa.matter.data.repository

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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.CookieFallbackSource
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.session.SessionExpiredBus
import org.kazusa.matter.core.session.SessionExpiryReason
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 凭据「保持得久」的三个前提都在这一层：
 * 1. 本地读不出凭据时不探活（否则空 Cookie 必然被判未登录）；
 * 2. 单次 401 只是线索，复核一次才判失效；
 * 3. 判定失效只清登录标记，凭据保留可恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AuthRepositoryTest {

    private class FakeWebCookieStore : WebSessionCookieStore {
        var cleared = false
        override suspend fun clearAll() {
            cleared = true
        }
    }

    private suspend fun freshPrefs(): AuthPreferences =
        AuthPreferences(RuntimeEnvironment.getApplication() as Context).also { it.clearSession() }

    /**
     * @param loginAnswers 第 n 次 `/api/config` 返回的 `data.login`，超出长度后沿用最后一个。
     * @return 仓库实例 + 已发生的探活请求次数。
     */
    private fun repository(
        prefs: AuthPreferences,
        loginAnswers: List<Boolean>,
        webCookieStore: FakeWebCookieStore = FakeWebCookieStore()
    ): Triple<AuthRepository, () -> Int, FakeWebCookieStore> {
        var calls = 0
        val client = HttpClient(MockEngine {
            val login = loginAnswers.getOrElse(calls) { loginAnswers.last() }
            calls++
            respond(
                content = """{"ok":1,"data":{"login":$login,"uid":"123"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        val api = WeiboApiService(client, prefs, SessionExpiredBus(), CookieFallbackSource())
        val repository = AuthRepository(
            authPreferences = prefs,
            apiService = api,
            webCookieStore = webCookieStore,
            probeConfirmDelayMs = 0L
        )
        return Triple(repository, { calls }, webCookieStore)
    }

    /** 模拟解密失败：uid 与登录标记都在，但三个凭据字段读出为空。 */
    @Test
    fun 本地凭据读空时不探活也不判失效() = runTest {
        val prefs = freshPrefs()
        prefs.saveSession(uid = "123", sub = "", subp = "", rawCookie = "")
        val (repo, calls, _) = repository(prefs, listOf(false))

        assertEquals(SessionProbe.SKIP, repo.probeSession())
        // 关键是零请求：空 Cookie 打 /api/config 必然 login=false，
        // 判成 EXPIRED 就等于「本地解密失败 → 用户被登出」。
        assertEquals(0, calls())
    }

    @Test
    fun 单次未登录会复核一次才放行() = runTest {
        val prefs = freshPrefs()
        prefs.saveSession(uid = "123", sub = "s", subp = "p", rawCookie = "SUB=s")
        val (repo, calls, _) = repository(prefs, listOf(false, true))

        assertEquals(SessionProbe.VALID, repo.probeSession())
        assertEquals(2, calls())
    }

    @Test
    fun 连续两次未登录才判失效() = runTest {
        val prefs = freshPrefs()
        prefs.saveSession(uid = "123", sub = "s", subp = "p", rawCookie = "SUB=s")
        val (repo, calls, _) = repository(prefs, listOf(false, false))

        assertEquals(SessionProbe.EXPIRED, repo.probeSession())
        assertEquals(2, calls())
    }

    /** 判定失效不得删凭据：这是「误判可以自愈」的唯一前提。 */
    @Test
    fun 判定失效保留凭据且不清WebView() = runTest {
        val prefs = freshPrefs()
        prefs.saveSession(uid = "123", sub = "s", subp = "p", rawCookie = "SUB=s; SUBP=p")
        val (repo, _, webCookieStore) = repository(prefs, listOf(false))

        repo.suspendSession(SessionExpiryReason.PROBE_FAILED)

        val session = prefs.sessionFlow.first()
        assertFalse(session.isLoggedIn)
        assertEquals("123", session.uid)
        assertEquals("s", session.sub)
        assertEquals("SUB=s; SUBP=p", session.rawCookie)
        // 凭据还在，仅跳登录页；WebView 里的会话留着，可作为无感恢复的来源
        assertFalse(webCookieStore.cleared)
    }

    /** 用户主动登出仍必须彻底清除（含 WebView），与判定失效是两条路径。 */
    @Test
    fun 主动登出清空凭据与WebView() = runTest {
        val prefs = freshPrefs()
        prefs.saveSession(uid = "123", sub = "s", subp = "p", rawCookie = "SUB=s")
        val (repo, _, webCookieStore) = repository(prefs, listOf(false))

        repo.logout()

        assertEquals(UserSession(), prefs.sessionFlow.first())
        assertEquals(true, webCookieStore.cleared)
    }
}
