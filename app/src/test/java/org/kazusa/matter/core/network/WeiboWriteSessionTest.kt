package org.kazusa.matter.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeiboWriteSessionTest {

    private val cookie = "SUB=abc; SUBP=def"

    private fun credentials() = WeiboWriteCredentials(
        sourceCookie = cookie,
        cookieHeader = cookie,
        token = "st-1"
    )

    @Test
    fun credentialsAreFetchedOnceAndReusedAcrossWrites() = runTest {
        var fetches = 0
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ ->
                fetches += 1
                credentials()
            },
            clock = { testScheduler.currentTime }
        )

        session.execute(cookie, "点赞") { it.token }
        session.execute(cookie, "点赞") { it.token }

        // 一次点赞只发一个写请求，不再每次都先打 /api/config
        assertEquals(1, fetches)
    }

    @Test
    fun invalidateForcesCredentialsRefetch() = runTest {
        var fetches = 0
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ ->
                fetches += 1
                credentials()
            },
            clock = { testScheduler.currentTime }
        )

        session.execute(cookie, "点赞") { it.token }
        // st 失效应答后由接口层丢弃缓存
        session.invalidate()
        session.execute(cookie, "点赞") { it.token }

        assertEquals(2, fetches)
    }

    @Test
    fun switchingSessionCookieRefetchesCredentials() = runTest {
        var fetches = 0
        val session = WeiboWriteSession(
            fetchCredentials = { source, _ ->
                fetches += 1
                credentials().copy(sourceCookie = source)
            },
            clock = { testScheduler.currentTime }
        )

        session.execute(cookie, "点赞") { it.token }
        session.execute("SUB=other", "点赞") { it.token }

        // 换账号后旧 st 与旧 Cookie 都不能再用
        assertEquals(2, fetches)
    }

    @Test
    fun writesAreSpacedByTheMinimumInterval() = runTest {
        val startedAt = mutableListOf<Long>()
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime },
            minWriteIntervalMillis = 800
        )

        repeat(3) {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                "ok"
            }
        }

        assertEquals(listOf(0L, 800L, 1_600L), startedAt)
    }

    @Test
    fun rateLimitBlocksFollowingWritesWithoutHittingTheServer() = runTest {
        var writes = 0
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime }
        )

        val first = runCatching {
            session.execute(cookie, "点赞") {
                writes += 1
                session.reportRateLimited()
                throw WriteGateException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            }
        }.exceptionOrNull()
        assertEquals(WeiboWriteErrors.CODE_RATE_LIMITED, (first as WriteGateException).code)

        // 冷却期内直接拒绝：不再提交请求，否则每点一次都在给服务端续冷却
        val second = runCatching {
            session.execute(cookie, "点赞") {
                writes += 1
                "ok"
            }
        }.exceptionOrNull()
        assertTrue(second is WriteGateException)
        assertEquals(WeiboWriteErrors.CODE_RATE_LIMITED, (second as WriteGateException).code)
        assertEquals(1, writes)

        advanceTimeBy(60_001)
        assertEquals(
            "ok",
            session.execute(cookie, "点赞") {
                writes += 1
                "ok"
            }
        )
        assertEquals(2, writes)
    }

    @Test
    fun rateLimitedCredentialsFetchAlsoStartsCooldown() = runTest {
        var fetches = 0
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ ->
                fetches += 1
                // /api/config 被限流时也是同一句文案，不能当成登录失效
                throw WriteGateException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            },
            clock = { testScheduler.currentTime }
        )

        val failure = runCatching { session.execute(cookie, "点赞") { "ok" } }.exceptionOrNull()
        assertEquals(WeiboWriteErrors.CODE_RATE_LIMITED, (failure as WriteGateException).code)
        assertTrue(session.isCoolingDown())

        // 冷却期内连凭据都不再请求
        runCatching { session.execute(cookie, "点赞") { "ok" } }
        assertEquals(1, fetches)
    }

    @Test
    fun rateLimitMessagesAreRecognised() {
        assertTrue(WeiboWriteErrors.isRateLimited("操作频繁，请稍后再试"))
        assertTrue(WeiboWriteErrors.isRateLimited("请求过于频繁"))
        assertTrue(!WeiboWriteErrors.isRateLimited("请求不合法"))
        assertTrue(!WeiboWriteErrors.isRateLimited(null))
    }
}
