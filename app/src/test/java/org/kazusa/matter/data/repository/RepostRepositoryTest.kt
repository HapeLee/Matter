package org.kazusa.matter.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.WeiboStatusDto

@OptIn(ExperimentalCoroutinesApi::class)
class RepostRepositoryTest {

    private val post = WeiboStatusDto(id = 9, repostsCount = "3")

    @Test
    fun successKeepsTheOptimisticCountAndEmitsTheEvent() = runTest {
        val repository = RepostRepository { _, _ -> ApiResult.Success(Unit) }
        // 立刻开始收集（UNDISPATCHED）：SharedFlow 没有 replay，订阅晚于发送就收不到
        val event = async(start = CoroutineStart.UNDISPATCHED) { repository.reposted.first() }

        assertNull(repository.repost(post, "转发语"))

        val state = repository.state(post)
        assertEquals(4, state.count)
        assertFalse(state.pending)
        assertEquals("9", event.await())
    }

    @Test
    fun failedRepostRollsBackAndReturnsTheMessage() = runTest {
        val repository = RepostRepository { _, _ -> ApiResult.Error(message = "网络断开") }

        assertEquals("网络断开", repository.repost(post, "转发语"))

        val state = repository.state(post)
        assertEquals(3, state.count)
        assertFalse(state.pending)
    }

    @Test
    fun cancelledRepostDoesNotLeaveThePendingFlagStuck() = runTest {
        val repository = RepostRepository { _, _ ->
            delay(1_000)
            ApiResult.Success(Unit)
        }
        val job = launch { repository.repost(post, "转发语") }
        runCurrent()
        assertTrue(repository.state(post).pending)

        job.cancelAndJoin()

        val state = repository.state(post)
        assertFalse(state.pending)
        assertEquals(3, state.count)
    }

    @Test
    fun pendingRequestIsNotSubmittedTwice() = runTest {
        var calls = 0
        val repository = RepostRepository { _, _ ->
            calls += 1
            delay(1_000)
            ApiResult.Success(Unit)
        }
        val first = launch { repository.repost(post, "转发语") }
        runCurrent()

        assertNull(repository.repost(post, "转发语"))

        advanceUntilIdle()
        first.join()
        assertEquals(1, calls)
        assertEquals(4, repository.state(post).count)
    }

    @Test
    fun accountSwitchDiscardsTheLateSuccessOfThePreviousSession() = runTest {
        var calls = 0
        val repository = RepostRepository { _, _ ->
            calls += 1
            if (calls == 1) {
                delay(1_000)
                ApiResult.Success(Unit)
            } else {
                ApiResult.Success(Unit)
            }
        }
        val job = launch { repository.repost(post, "转发语") }
        runCurrent()
        assertEquals(4, repository.state(post).count)

        repository.clear()
        advanceUntilIdle()
        job.join()

        // 迟到的成功不能写回新会话：仍应是服务端初始值
        val state = repository.state(post)
        assertEquals(3, state.count)
        assertFalse(state.pending)

        assertNull(repository.repost(post, ""))
        assertEquals(4, repository.state(post).count)
    }

    @Test
    fun abbreviatedCountIsParsedBeforeIncrement() = runTest {
        val abbreviated = WeiboStatusDto(id = 11, repostsCount = "1.2万")
        val repository = RepostRepository { _, _ -> ApiResult.Success(Unit) }
        // 「1.2万」无法精确参与 +1，解析成 12000 后按同一口径展示
        assertEquals(12_000, repository.state(abbreviated).count)
        assertNull(repository.repost(abbreviated, ""))
        assertEquals(12_001, repository.state(abbreviated).count)
    }

    @Test
    fun postWithoutAnyIdIsRejectedWithoutARequest() = runTest {
        val repository = RepostRepository { _, _ -> error("must not reach the network") }

        assertEquals("这条微博暂不支持转发", repository.repost(WeiboStatusDto(), "转发语"))
    }

    @Test
    fun draftTextIsPassedThroughIncludingEmpty() = runTest {
        val requests = mutableListOf<RepostRequest>()
        val repository = RepostRepository { _, request ->
            requests += request
            ApiResult.Success(Unit)
        }

        repository.repost(post, "转发语")
        repository.repost(post, "")

        assertEquals(listOf("转发语", ""), requests.map { it.text })
    }

    @Test
    fun picturesAndFlagsReachTheWrite() = runTest {
        val requests = mutableListOf<RepostRequest>()
        val repository = RepostRepository { _, request ->
            requests += request
            ApiResult.Success(Unit)
        }

        repository.repost(
            post,
            RepostRequest(
                text = "转发语",
                pictures = listOf(StatusPicture("008abc")),
                alsoComment = true,
                visibility = StatusVisibility.FRIENDS
            )
        )

        val request = requests.single()
        assertEquals(listOf("008abc"), request.pictures.map { it.pid })
        assertTrue(request.alsoComment)
        assertEquals(StatusVisibility.FRIENDS, request.visibility)
    }
}
