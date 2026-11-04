package org.kazusa.matter.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FollowTimelinePageTest {
    private val special = "4249162129677215"
    private fun post(id: Long, following: Boolean?, uid: Long = 2) =
        WeiboStatusDto(id = id, user = WeiboUserDto(id = uid, following = following))

    private fun page(vararg posts: WeiboStatusDto, cursor: Long? = 50) =
        ApiResult.Success(TimelineResponse(statuses = posts.toList(), nextMaxId = cursor, hasMore = cursor != null))

    @Test fun sharedFollowChangesTakePrecedenceOverStaleResponseFlags() = runTest {
        val result = loadFollowTimelinePage(special, "1", null, { true },
            followingOf = { uid -> when (uid) { "2" -> true; "3" -> false; else -> null } }) {
            page(post(10, false, uid = 2), post(11, true, uid = 3), post(12, null, uid = 4))
        } as ApiResult.Success
        assertEquals(listOf(10L, 12L), result.data.posts.map { it.id })
    }

    @Test fun onlyExplicitlyUnfollowedOuterAuthorsAreRemoved() = runTest {
        val retweet = post(4, true).copy(retweetedStatus = post(40, false))
        val result = loadFollowTimelinePage(special, "1", null, { true }) {
            page(post(1, false), post(2, true), post(3, null), retweet,
                post(5, false, uid = 1).copy(idStr = ""), WeiboStatusDto(id = 6), post(2, true))
        } as ApiResult.Success
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), result.data.posts.map { it.id })
        assertEquals(40L, result.data.posts[2].retweetedStatus?.id)
        assertEquals(50L, result.data.nextMaxId)
    }

    @Test fun ordinaryFollowFeedsShareTheFilterButSpecialMembershipFeedsDoNot() = runTest {
        for (gid in listOf(null, "all", "100011", "110001", "110011", "110021", "100091", special)) {
            val result = loadFollowTimelinePage(gid, "1", null, { true }) {
                page(post(1, false), post(2, true), cursor = null)
            } as ApiResult.Success
            assertEquals(listOf(2L), result.data.posts.map { it.id })
        }
        for (gid in listOf("100051", "100101", "100131")) {
            val result = loadFollowTimelinePage(gid, "1", null, { true }) {
                page(post(1, false), cursor = null)
            } as ApiResult.Success
            assertEquals(1L, result.data.posts.single().id)
        }
    }

    @Test fun aFilteredPageContinuesUsingTheRawCursorRatherThanVisibleIds() = runTest {
        val requests = mutableListOf<Long?>()
        val result = loadFollowTimelinePage(special, "1", null, { true }) { cursor ->
            requests += cursor
            if (cursor == null) page(post(90, false), cursor = 70)
            else page(post(60, true), post(40, false), cursor = 30)
        } as ApiResult.Success
        assertEquals(listOf(null, 70L), requests)
        assertEquals(listOf(60L), result.data.posts.map { it.id })
        assertEquals(30L, result.data.nextMaxId)
        assertTrue(result.data.hasMore)
    }

    @Test fun serverEmptyPagesWithAnAdvancingCursorAreAlsoSkipped() = runTest {
        val requests = mutableListOf<Long?>()
        val result = loadFollowTimelinePage(special, "1", null, { true }) {
            requests += it
            if (it == null) page(cursor = 70) else page(post(50, true), cursor = null)
        } as ApiResult.Success
        assertEquals(listOf(null, 70L), requests)
        assertEquals(50L, result.data.posts.single().id)
        assertFalse(result.data.hasMore)
    }

    @Test fun anEntirelyFilteredFinalPageIsAnEmptySuccess() = runTest {
        val result = loadFollowTimelinePage(special, "1", null, { true }) {
            page(post(1, false), cursor = null)
        } as ApiResult.Success
        assertTrue(result.data.posts.isEmpty())
        assertFalse(result.data.hasMore)
    }

    @Test fun theRequestBudgetReturnsAResumableCursorInsteadOfEndOfFeed() = runTest {
        var calls = 0
        val result = loadFollowTimelinePage(special, "1", 100, { true }) {
            calls++
            page(post(100 - calls.toLong(), false), cursor = 100 - calls.toLong())
        } as ApiResult.Success
        assertEquals(5, calls)
        assertTrue(result.data.posts.isEmpty())
        assertTrue(result.data.hasMore)
        assertEquals(95L, result.data.nextMaxId)
    }

    @Test fun failureWhileSkippingDoesNotCommitACursorAndRetryStartsAtTheSamePage() = runTest {
        var fail = true
        val requests = mutableListOf<Long?>()
        suspend fun load() = loadFollowTimelinePage(special, "1", 100, { true }) { cursor ->
            requests += cursor
            when {
                cursor == 100L -> page(post(90, false), cursor = 70)
                fail -> ApiResult.Error(message = "offline")
                else -> page(post(60, true), cursor = 50)
            }
        }
        assertTrue(load() is ApiResult.Error)
        fail = false
        val result = load() as ApiResult.Success
        assertEquals(listOf(100L, 70L, 100L, 70L), requests)
        assertEquals(50L, result.data.nextMaxId)
    }

    @Test fun repeatedAndCyclicCursorsAreErrorsRatherThanInfiniteRequests() = runTest {
        val same = loadFollowTimelinePage(special, "1", 100, { true }) {
            page(post(90, false), cursor = 100)
        }
        assertTrue(same is ApiResult.Error)
        var calls = 0
        val cycle = loadFollowTimelinePage(special, "1", 100, { true }) {
            calls++
            page(post(90, false), cursor = if (calls == 1) 50 else 100)
        }
        assertTrue(cycle is ApiResult.Error)
        assertEquals(2, calls)
    }

    @Test fun accountSwitchRejectsASlowResponseAndDoesNotRequestAnotherPage() = runTest {
        val gate = CompletableDeferred<Unit>()
        var current = true
        var calls = 0
        val result = async {
            loadFollowTimelinePage(special, "1", null, { current }) {
                calls++
                gate.await()
                page(post(90, false), cursor = 70)
            }
        }
        runCurrent()
        current = false
        gate.complete(Unit)
        assertTrue(result.await() is ApiResult.Error)
        assertEquals(1, calls)
    }

    @Test fun cancellationCannotReturnALateResultEvenWhenTransportIgnoresIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val result = async {
            loadFollowTimelinePage(special, "1", null, { true }) {
                calls++
                withContext(NonCancellable) { gate.await() }
                page(post(90, false), cursor = 70)
            }
        }
        runCurrent()
        result.cancel()
        gate.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(1, calls)
    }
}
