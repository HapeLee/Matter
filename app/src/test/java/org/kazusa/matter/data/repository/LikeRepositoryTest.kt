package org.kazusa.matter.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto

@OptIn(ExperimentalCoroutinesApi::class)
class LikeRepositoryTest {

    private val post = WeiboStatusDto(id = 1, isLiked = false, attitudesCount = "3")
    private val comment = WeiboCommentDto(id = 7, likeCount = "2")

    @Test
    fun successfulToggleKeepsOptimisticStateAndClearsPending() = runTest {
        val repository = LikeRepository { _, isLike -> ApiResult.Success(isLike) }
        assertNull(repository.togglePost(post))

        val state = repository.postState(post)
        assertTrue(state.liked)
        assertEquals(4, state.count)
        assertFalse(state.pending)
    }

    @Test
    fun failedToggleRollsBackAndReturnsMessage() = runTest {
        val repository = LikeRepository { _, _ -> ApiResult.Error(message = "网络断开") }
        assertEquals("网络断开", repository.togglePost(post))

        val state = repository.postState(post)
        assertFalse(state.liked)
        assertEquals(3, state.count)
        assertFalse(state.pending)
    }

    @Test
    fun cancelledRequestClearsPendingInsteadOfLeavingTheButtonStuck() = runTest {
        val repository = LikeRepository { _, _ ->
            delay(1_000)
            ApiResult.Success(true)
        }
        val job = launch { repository.togglePost(post) }
        runCurrent()
        assertTrue(repository.postState(post).pending)

        // 页面整体销毁 / 换账号导致请求被取消
        job.cancelAndJoin()

        val state = repository.postState(post)
        assertFalse(state.pending)
        assertFalse(state.liked)
        assertEquals(3, state.count)
    }

    @Test
    fun commentToggleUsesItsOwnStateSpace() = runTest {
        val requested = mutableListOf<Pair<String, Boolean>>()
        val repository = LikeRepository { id, isLike ->
            requested += id to isLike
            ApiResult.Success(isLike)
        }

        assertNull(repository.toggleComment(comment))

        assertEquals(listOf("7" to true), requested)
        val state = repository.commentState(comment)
        assertTrue(state.liked)
        assertEquals(3, state.count)
        // 评论与微博状态互不影响
        assertFalse(repository.postState(post).liked)
    }

    @Test
    fun accountSwitchDiscardsLateSuccessFromThePreviousSession() = runTest {
        var calls = 0
        val repository = LikeRepository { _, isLike ->
            calls += 1
            if (calls == 1) {
                delay(1_000)
                ApiResult.Success(isLike)
            } else {
                ApiResult.Success(isLike)
            }
        }
        val job = launch { repository.togglePost(post) }
        runCurrent()
        assertTrue(repository.postState(post).liked)

        // A 账号的在途请求还没回来，此时切到 B 账号
        repository.clear()
        advanceUntilIdle()
        job.join()

        // 迟到的成功结果不能写回新会话：仍应是服务端初始值
        val state = repository.postState(post)
        assertFalse(state.liked)
        assertEquals(3, state.count)
        assertFalse(state.pending)

        // 新会话的点赞照常工作
        assertNull(repository.togglePost(post))
        assertTrue(repository.postState(post).liked)
    }

    @Test
    fun rollbackAfterAccountSwitchDoesNotRestorePreviousSessionState() = runTest {
        val likedByPreviousAccount = WeiboStatusDto(id = 2, isLiked = true, attitudesCount = "5")
        val repository = LikeRepository { _, _ ->
            delay(1_000)
            ApiResult.Success(true)
        }
        val job = launch { repository.togglePost(likedByPreviousAccount) }
        runCurrent()
        assertFalse(repository.postState(likedByPreviousAccount).liked)

        repository.clear()
        job.cancelAndJoin()

        // 取消回滚同样要校验会话代次，不能把 A 账号的点赞态写回 B 账号
        val state = repository.postState(likedByPreviousAccount)
        assertTrue(state.liked)
        assertEquals(5, state.count)
        assertFalse(state.pending)
    }

    @Test
    fun pendingRequestCannotBeToggledTwice() = runTest {
        var calls = 0
        val repository = LikeRepository { _, _ ->
            calls += 1
            delay(1_000)
            ApiResult.Success(true)
        }
        val first = launch { repository.togglePost(post) }
        runCurrent()
        assertNull(repository.togglePost(post))

        advanceUntilIdle()
        first.join()
        assertEquals(1, calls)
        assertTrue(repository.postState(post).liked)
    }
}
