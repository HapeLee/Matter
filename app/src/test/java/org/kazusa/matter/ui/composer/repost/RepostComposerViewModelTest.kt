package org.kazusa.matter.ui.composer.repost

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.ReplyImageContent
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.data.repository.PublishSession
import org.kazusa.matter.data.repository.ReplySession
import org.kazusa.matter.data.repository.RepostRepository
import org.kazusa.matter.data.repository.RepostRequest
import org.kazusa.matter.data.repository.SuggestionRepository
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH

@OptIn(ExperimentalCoroutinesApi::class)
class RepostComposerViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val post = WeiboStatusDto(id = 9, text = "原微博", repostsCount = "0")
    private val other = WeiboStatusDto(id = 10, text = "另一条", repostsCount = "0")

    /** 一条「转发」：正文是自己的转发语，原微博挂在 retweeted_status 上。 */
    private val reposted = WeiboStatusDto(
        id = 20,
        text = "上一层转发语",
        user = WeiboUserDto(id = 2, screenName = "上一层用户"),
        retweetedStatus = WeiboStatusDto(id = 9, text = "原微博")
    )

    private fun viewModel(
        scheduler: TestCoroutineScheduler,
        upload: suspend () -> ApiResult<String> = { ApiResult.Error(message = "本次用例不涉及上传") },
        write: suspend (String, RepostRequest) -> ApiResult<Unit> = { _, _ -> ApiResult.Success(Unit) }
    ): RepostComposerViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return RepostComposerViewModel(
            RepostRepository(write),
            PublishRepository(
                sessions = MutableStateFlow(PublishSession("1", "SUB=test", true)),
                imageReader = ReplyImageReader { ReplyImageContent(byteArrayOf(1, 2, 3)) },
                uploadImage = { _, _ -> upload() },
                publishStatus = { _, _, _ -> ApiResult.Success(Unit) }
            ),
            SuggestionRepository(MutableStateFlow(ReplySession("1", "SUB=test", true)))
        )
    }

    @Test
    fun draftIsKeptPerPostAcrossDismissal() = runTest {
        val vm = viewModel(testScheduler)
        vm.open(post)
        vm.updateDraft("第一条的转发语")
        vm.dismiss()
        assertFalse(vm.state.value.isOpen)
        assertNull(vm.state.value.target)

        vm.open(other)
        assertEquals("", vm.state.value.draft)

        vm.open(post)
        assertEquals("第一条的转发语", vm.state.value.draft)
    }

    @Test
    fun repostingARepostPrefillsTheNestedChain() = runTest {
        val vm = viewModel(testScheduler)

        vm.open(reposted)

        assertEquals("//@上一层用户:上一层转发语", vm.state.value.draft)
        assertTrue(vm.state.value.draftPrefilled)
    }

    @Test
    fun repostingAnOriginalPrefillsNothing() = runTest {
        val vm = viewModel(testScheduler)

        vm.open(post)

        assertEquals("", vm.state.value.draft)
        assertFalse(vm.state.value.draftPrefilled)
    }

    @Test
    fun upperLevelWithoutCommentFallsBackToTheDefaultRepostText() = runTest {
        val vm = viewModel(testScheduler)

        vm.open(reposted.copy(text = "  "))

        assertEquals("//@上一层用户:$DEFAULT_REPOST_COMMENT", vm.state.value.draft)
    }

    @Test
    fun clearingThePrefilledChainIsRemembered() = runTest {
        val vm = viewModel(testScheduler)
        vm.open(reposted)
        vm.updateDraft("")

        vm.dismiss()
        vm.open(reposted)

        assertEquals("", vm.state.value.draft)
        assertFalse(vm.state.value.draftPrefilled)
    }

    @Test
    fun emptyRepostIsSubmittedAndClosesTheSheet() = runTest {
        val vm = viewModel(testScheduler)
        val message = async(start = CoroutineStart.UNDISPATCHED) { vm.messages.first() }

        vm.open(post)
        vm.submit()
        advanceUntilIdle()

        assertFalse(vm.state.value.isOpen)
        assertEquals("转发成功", message.await())
    }

    @Test
    fun sendingBlocksDuplicateSubmit() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = viewModel(testScheduler) { _, _ ->
            calls += 1
            gate.await()
            ApiResult.Success(Unit)
        }

        vm.open(post)
        vm.submit()
        assertTrue(vm.state.value.isSending)
        vm.submit()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertFalse(vm.state.value.isSending)
        assertFalse(vm.state.value.isOpen)
    }

    @Test
    fun failureKeepsTheSheetAndDraftWithTheServerMessage() = runTest {
        val vm = viewModel(testScheduler) { _, _ -> ApiResult.Error(message = "操作频繁，请稍后再试") }

        vm.open(post)
        vm.updateDraft("转发语")
        vm.submit()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isOpen)
        assertEquals("转发语", state.draft)
        assertEquals("操作频繁，请稍后再试", state.errorMessage)
        assertFalse(state.isSending)
    }

    @Test
    fun switchingTargetDoesNotLetTheOldResultCloseTheNewSheet() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(testScheduler) { _, _ ->
            gate.await()
            ApiResult.Success(Unit)
        }

        vm.open(post)
        vm.submit()
        vm.open(other)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(10L, vm.state.value.target?.id ?: 0L)
        assertTrue(vm.state.value.isOpen)
    }

    @Test
    fun draftIsTruncatedAtTheComposerLimit() = runTest {
        val vm = viewModel(testScheduler)
        vm.open(post)
        vm.updateDraft("甲".repeat(MAX_COMPOSER_TEXT_LENGTH + 10))
        assertEquals(MAX_COMPOSER_TEXT_LENGTH, vm.state.value.draft.length)
    }

    @Test
    fun resetDropsDraftsAndClosesTheSheet() = runTest {
        val vm = viewModel(testScheduler)
        vm.open(post)
        vm.updateDraft("转发语")

        vm.reset()

        assertFalse(vm.state.value.isOpen)
        assertNull(vm.state.value.target)
        vm.open(post)
        assertEquals("", vm.state.value.draft)
    }

    @Test
    fun alsoCommentVisibilityAndPictureReachTheWrite() = runTest {
        val requests = mutableListOf<RepostRequest>()
        val vm = viewModel(testScheduler, write = { _, request ->
            requests += request
            ApiResult.Success(Unit)
        }, upload = { ApiResult.Success("008pid") })

        vm.open(post)
        vm.updateDraft("转发语")
        vm.addImage(ReplyImageHandle("content://image/1"))
        vm.setAlsoComment(true)
        vm.setVisibility(StatusVisibility.PRIVATE)
        vm.submit()
        advanceUntilIdle()

        val request = requests.single()
        assertEquals("转发语", request.text)
        assertTrue(request.alsoComment)
        assertEquals(StatusVisibility.PRIVATE, request.visibility)
        assertEquals(listOf("008pid"), request.pictures.map { it.pid })
    }

    @Test
    fun failedUploadAbortsTheRepostWithTheUploadMessage() = runTest {
        var writes = 0
        val vm = viewModel(testScheduler, write = { _, _ ->
            writes += 1
            ApiResult.Success(Unit)
        }, upload = { ApiResult.Error(message = "图片过大") })

        vm.open(post)
        vm.addImage(ReplyImageHandle("content://image/1"))
        vm.submit()
        advanceUntilIdle()

        assertEquals(0, writes)
        assertTrue(vm.state.value.isOpen)
        assertEquals("图片过大", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSending)
        assertFalse(vm.state.value.uploadingImage)
    }
}
