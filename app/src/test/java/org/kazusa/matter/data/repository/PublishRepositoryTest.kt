package org.kazusa.matter.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.data.model.ReplyImageContent
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader

@OptIn(ExperimentalCoroutinesApi::class)
class PublishRepositoryTest {

    private val session = PublishSession(uid = "1", cookie = "SUB=test", loggedIn = true)
    private val handle = ReplyImageHandle("content://image/1")

    private fun repository(
        sessions: MutableStateFlow<PublishSession> = MutableStateFlow(session),
        reader: ReplyImageReader? = ReplyImageReader { ReplyImageContent("img".toByteArray()) },
        upload: suspend (PublishSession, ByteArray) -> ApiResult<String> = { _, _ -> ApiResult.Success("pic-1") },
        publish: suspend (PublishSession, String, List<StatusPicture>) -> ApiResult<Unit> = { _, _, _ ->
            ApiResult.Success(Unit)
        }
    ) = PublishRepository(sessions, reader, upload, publish)

    @Test
    fun successfulPublishIncrementsThePublishedCount() = runTest {
        val repository = repository()

        assertTrue(
            repository.publish(session, "文本", listOf(StatusPicture("pic-1"))) is ApiResult.Success
        )
        advanceUntilIdle()

        assertEquals(1L, repository.publishedCount.value)
    }

    @Test
    fun failedPublishDoesNotIncrementTheCount() = runTest {
        val repository = repository(publish = { _, _, _ -> ApiResult.Error(message = "失败") })

        repository.publish(session, "文本", emptyList())
        advanceUntilIdle()

        assertEquals(0L, repository.publishedCount.value)
    }

    @Test
    fun uploadReadsTheHandleAndPairsThePidWithItsRealMimeType() = runTest {
        var readHandle: ReplyImageHandle? = null
        var uploadedBytes: ByteArray? = null
        val repository = repository(
            reader = ReplyImageReader { requested ->
                readHandle = requested
                // PNG 原样透传，pid 的 type 必须是 image/png 而不是 image/jpeg
                ReplyImageContent("bytes".toByteArray(), "image/png")
            },
            upload = { _, bytes ->
                uploadedBytes = bytes
                ApiResult.Success("pic-9")
            }
        )

        val result = repository.upload(session, handle)

        assertTrue(result is ApiResult.Success)
        assertEquals(StatusPicture("pic-9", "image/png"), (result as ApiResult.Success).data)
        assertEquals(handle, readHandle)
        assertEquals("bytes", uploadedBytes?.decodeToString())
    }

    @Test
    fun uploadRejectsWhenTheAccountHasNoCookie() = runTest {
        val repository = repository()
        val anonymous = PublishSession(uid = "1", cookie = "", loggedIn = false)

        val result = repository.upload(anonymous, handle)

        assertEquals(401, (result as ApiResult.Error).code)
    }

    @Test
    fun uploadRejectsAfterTheAccountSwitched() = runTest {
        val sessions = MutableStateFlow(session)
        val repository = repository(sessions = sessions)
        sessions.value = PublishSession(uid = "2", cookie = "SUB=other", loggedIn = true)

        val result = repository.upload(session, handle)

        assertTrue(result is ApiResult.Error)
        assertEquals("账号已切换，请重新选择图片", (result as ApiResult.Error).message)
    }

    @Test
    fun publishRejectsAfterTheAccountSwitched() = runTest {
        val sessions = MutableStateFlow(session)
        val repository = repository(sessions = sessions)
        sessions.value = PublishSession(uid = "2", cookie = "SUB=other", loggedIn = true)

        val result = repository.publish(session, "文本", emptyList())

        assertTrue(result is ApiResult.Error)
        assertEquals("账号已切换，请重新发布", (result as ApiResult.Error).message)
    }
}
