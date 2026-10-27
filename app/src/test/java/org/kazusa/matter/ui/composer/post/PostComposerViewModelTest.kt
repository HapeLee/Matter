package org.kazusa.matter.ui.composer.post

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.data.model.ReplyImageContent
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.data.repository.PublishSession
import org.kazusa.matter.data.repository.ReplySession
import org.kazusa.matter.data.repository.SuggestionRepository
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH

@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerViewModelTest {

    private val session = PublishSession(uid = "1", cookie = "SUB=test", loggedIn = true)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        scheduler: TestCoroutineScheduler,
        sessions: MutableStateFlow<PublishSession> = MutableStateFlow(session),
        upload: suspend (PublishSession, ByteArray) -> ApiResult<String> = { _, _ -> ApiResult.Success("pic") },
        publish: suspend (PublishSession, String, List<StatusPicture>) -> ApiResult<Unit> = { _, _, _ ->
            ApiResult.Success(Unit)
        }
    ): PostComposerViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return PostComposerViewModel(
            PublishRepository(
                sessions = sessions,
                imageReader = ReplyImageReader { handle -> ReplyImageContent(handle.value.toByteArray()) },
                uploadImage = upload,
                publishStatus = publish
            ),
            SuggestionRepository(MutableStateFlow(ReplySession("1", "SUB=test", true)))
        )
    }

    private fun image(index: Int) = ReplyImageHandle("content://image/$index")

    @Test
    fun draftIsTruncatedAtTheComposerLimit() = runTest {
        val vm = viewModel(testScheduler)
        advanceUntilIdle()

        vm.updateDraft("甲".repeat(MAX_COMPOSER_TEXT_LENGTH + 5))

        assertEquals(MAX_COMPOSER_TEXT_LENGTH, vm.state.value.draft.length)
    }

    @Test
    fun picturesAreCappedAtNine() = runTest {
        val vm = viewModel(testScheduler)
        advanceUntilIdle()

        vm.addImages((1..12).map(::image))

        assertEquals(MAX_POST_IMAGES, vm.state.value.images.size)
    }

    @Test
    fun emptyDraftWithoutPicturesCannotBeSubmitted() = runTest {
        val vm = viewModel(testScheduler)
        advanceUntilIdle()

        vm.submit()
        advanceUntilIdle()

        assertFalse(vm.state.value.canSubmit)
        assertFalse(vm.state.value.isSending)
    }

    @Test
    fun submitUploadsEveryPictureInOrderThenPublishes() = runTest {
        val uploaded = mutableListOf<String>()
        var publishedWith: List<StatusPicture>? = null
        val vm = viewModel(
            scheduler = testScheduler,
            upload = { _, bytes ->
                val value = bytes.decodeToString()
                uploaded += value
                ApiResult.Success("$value-id")
            },
            publish = { _, text, ids ->
                assertEquals("配图微博", text)
                publishedWith = ids
                ApiResult.Success(Unit)
            }
        )
        advanceUntilIdle()

        vm.updateDraft("配图微博")
        vm.addImages(listOf(image(1), image(2)))
        vm.submit()
        advanceUntilIdle()

        assertEquals(listOf("content://image/1", "content://image/2"), uploaded)
        // 读取器没声明 MIME 时按 JPEG 处理；顺序必须与选择顺序一致
        assertEquals(
            listOf(
                StatusPicture("content://image/1-id", "image/jpeg"),
                StatusPicture("content://image/2-id", "image/jpeg")
            ),
            publishedWith
        )
        assertTrue(vm.state.value.draft.isEmpty())
        assertTrue(vm.state.value.images.isEmpty())
    }

    @Test
    fun successfulPublishSignalsTheEditorToClose() = runTest {
        val vm = viewModel(testScheduler)
        advanceUntilIdle()
        val closed = async(start = CoroutineStart.UNDISPATCHED) { vm.published.first() }

        vm.updateDraft("文本")
        vm.submit()
        advanceUntilIdle()

        closed.await()
        assertFalse(vm.state.value.isSending)
    }

    @Test
    fun uploadFailureKeepsTheDraftAndSkipsAlreadyUploadedPicturesOnRetry() = runTest {
        var uploadCalls = 0
        var publishCalls = 0
        var failSecondPictureOnce = true
        val vm = viewModel(
            scheduler = testScheduler,
            upload = { _, bytes ->
                uploadCalls += 1
                val value = bytes.decodeToString()
                if (value.endsWith("/2") && failSecondPictureOnce) {
                    failSecondPictureOnce = false
                    ApiResult.Error(message = "图片上传失败")
                } else {
                    ApiResult.Success("uploaded-${value.substringAfterLast('/')}")
                }
            },
            publish = { _, _, _ ->
                publishCalls += 1
                ApiResult.Success(Unit)
            }
        )
        advanceUntilIdle()

        vm.updateDraft("文本")
        vm.addImages(listOf(image(1), image(2)))
        vm.submit()
        advanceUntilIdle()

        assertEquals("图片上传失败", vm.state.value.errorMessage)
        assertEquals("文本", vm.state.value.draft)
        assertEquals(0, publishCalls)
        // 第一张已经在第一次提交时上传成功，重试只补第二张
        assertEquals("uploaded-1", vm.state.value.images.first().uploaded?.pid)

        vm.submit()
        advanceUntilIdle()

        assertEquals(3, uploadCalls)
        assertEquals(1, publishCalls)
        assertTrue(vm.state.value.errorMessage == null)
    }

    @Test
    fun sendingBlocksDuplicateSubmit() = runTest {
        var publishCalls = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = viewModel(
            scheduler = testScheduler,
            publish = { _, _, _ ->
                publishCalls += 1
                gate.await()
                ApiResult.Success(Unit)
            }
        )
        advanceUntilIdle()

        vm.updateDraft("文本")
        vm.submit()
        assertTrue(vm.state.value.isSending)
        vm.submit()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, publishCalls)
    }

    @Test
    fun switchingAccountClearsTheDraftAndPictures() = runTest {
        val sessions = MutableStateFlow(session)
        val vm = viewModel(scheduler = testScheduler, sessions = sessions)
        advanceUntilIdle()
        vm.updateDraft("别人的草稿")
        vm.addImages(listOf(image(1)))

        sessions.value = PublishSession(uid = "2", cookie = "SUB=other", loggedIn = true)
        advanceUntilIdle()

        assertTrue(vm.state.value.draft.isEmpty())
        assertTrue(vm.state.value.images.isEmpty())
    }
}
