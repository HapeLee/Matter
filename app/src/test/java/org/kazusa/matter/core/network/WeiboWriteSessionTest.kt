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
    fun writesAreSpacedByTheNormalIntervalBeforeAnyRateLimit() = runTest {
        val startedAt = mutableListOf<Long>()
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime }
        )

        repeat(3) {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                "ok"
            }
        }

        // 没踩过频控时用常规间隔（250ms），连点不至于排长队
        assertEquals(listOf(0L, 250L, 500L), startedAt)
    }

    @Test
    fun rateLimitBacksOffTheSpacingForFollowingWrites() = runTest {
        val startedAt = mutableListOf<Long>()
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime }
        )

        runCatching {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                session.reportRateLimited()
                throw WriteGateException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            }
        }
        advanceTimeBy(60_001)

        repeat(2) {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                "ok"
            }
        }

        // 冷却结束后按退避间隔（800ms）排队，而不是回到 250ms
        assertEquals(listOf(0L, 60_001L, 60_801L), startedAt)
    }

    @Test
    fun spacingRecoversAfterConsecutiveSuccesses() = runTest {
        val startedAt = mutableListOf<Long>()
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime },
            recoverySuccesses = 2
        )

        runCatching {
            session.execute(cookie, "点赞") {
                session.reportRateLimited()
                throw WriteGateException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            }
        }
        advanceTimeBy(60_001)

        repeat(3) {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                "ok"
            }
        }

        // 前两次仍在退避（间隔 800ms），连续两次成功后第三次恢复常规间隔（250ms）
        assertEquals(listOf(60_001L, 60_801L, 61_051L), startedAt)
    }

    @Test
    fun rateLimitedResultDoesNotCountAsRecoverySuccess() = runTest {
        val startedAt = mutableListOf<Long>()
        val session = WeiboWriteSession(
            fetchCredentials = { _, _ -> credentials() },
            clock = { testScheduler.currentTime },
            recoverySuccesses = 2
        )

        // 真实写路径被限流时是「上报 + 返回 Error」，不抛异常
        val limited: ApiResult<Any> = session.execute(cookie, "点赞") {
            session.reportRateLimited()
            ApiResult.Error(
                code = WeiboWriteErrors.CODE_RATE_LIMITED,
                message = WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        assertEquals(
            WeiboWriteErrors.CODE_RATE_LIMITED,
            (limited as ApiResult.Error).code
        )
        advanceTimeBy(60_001)

        repeat(3) {
            session.execute(cookie, "点赞") {
                startedAt += testScheduler.currentTime
                "ok"
            }
        }

        // 被限流那次不能算「连续成功」：冷却后第一次成功仍按 800ms 退避排队，
        // 若把失败计入进度，这里会提前掉回 250ms
        assertEquals(listOf(60_001L, 60_801L, 61_051L), startedAt)
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
