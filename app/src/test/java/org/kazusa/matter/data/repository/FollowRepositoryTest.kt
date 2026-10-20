package org.kazusa.matter.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult

class FollowRepositoryTest {

    private fun repository(
        requestFollow: suspend (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) },
        requestUnfollow: suspend (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    ) = FollowRepository(requestFollow, requestUnfollow)

    @Test
    fun seedFillsOnlyMissingValues() {
        val repository = repository()

        repository.seed("1", following = true)
        // 用户点过的结果不能被服务端数据覆盖
        repository.seed("1", following = false)

        assertEquals(true, repository.followingOf("1"))
        assertNull(repository.followingOf("2"))
    }

    @Test
    fun duplicateRequestForTheSameUserIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repository(requestFollow = { gate.await(); ApiResult.Success(Unit) })

        val first = launch { repository.setFollowing("1", follow = true) }
        runCurrent()
        assertTrue(repository.states.value.getValue("1").updating)

        assertEquals(FollowResult.Ignored, repository.setFollowing("1", follow = true))

        gate.complete(Unit)
        first.join()
        assertEquals(false, repository.states.value.getValue("1").updating)
    }

    @Test
    fun failureRollsBackToThePreviousValue() = runTest {
        val repository = repository(requestFollow = { ApiResult.Error(message = "关注失败") })
        repository.seed("1", following = false)

        val result = repository.setFollowing("1", follow = true)

        assertTrue(result is FollowResult.Failure)
        assertEquals("关注失败", (result as FollowResult.Failure).message)
        assertEquals(false, repository.followingOf("1"))
        assertFalse(repository.states.value.getValue("1").updating)
    }

    @Test
    fun clearDiscardsLateResultFromThePreviousAccount() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repository(requestFollow = { gate.await(); ApiResult.Success(Unit) })

        val job = launch { repository.setFollowing("1", follow = true) }
        runCurrent()
        assertEquals(true, repository.followingOf("1"))

        // 换账号：清空并推进代次，旧账号在途请求的结果不能再写回
        repository.clear()
        assertTrue(repository.states.value.isEmpty())

        gate.complete(Unit)
        job.join()
        assertTrue(repository.states.value.isEmpty())
    }
}
