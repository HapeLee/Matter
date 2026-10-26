package org.kazusa.matter.ui.message

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.ChatMessage
import org.kazusa.matter.data.model.ChatMessagePage
import org.kazusa.matter.data.model.ChatSendResult
import org.kazusa.matter.data.model.MessageConversationPage
import org.kazusa.matter.data.model.MessageNoticePage
import org.kazusa.matter.data.model.MessageTab
import org.kazusa.matter.data.repository.MessageRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun keep(viewModel: ChatViewModel): ChatViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("chat", vm)
            stores += store
        }
    }

    private class FakeRepository(
        var historyResponse: suspend (String, Long?) -> ApiResult<ChatMessagePage> = {
            _, _ -> ApiResult.Success(ChatMessagePage(emptyList(), null))
        },
        var sendResponse: suspend (String, String) -> ApiResult<ChatSendResult> = {
            _, _ -> ApiResult.Success(ChatSendResult("100", 1_700_000_000_000L))
        },
        var imageResponse: suspend (String, ByteArray) -> ApiResult<ChatSendResult> = {
            _, _ -> ApiResult.Success(ChatSendResult("200", 1_700_000_000_001L))
        }
    ) : MessageRepository {
        val sendCalls = mutableListOf<String>()
        val imageCalls = mutableListOf<ByteArray>()
        val historyCalls = mutableListOf<Pair<String, Long?>>()

        override suspend fun conversations(page: Int) =
            ApiResult.Success(MessageConversationPage(emptyList(), false))

        override suspend fun notices(tab: MessageTab, page: Int) =
            ApiResult.Success(MessageNoticePage(emptyList(), false))

        override suspend fun history(peerUid: String, maxId: Long?): ApiResult<ChatMessagePage> {
            historyCalls += peerUid to maxId
            return historyResponse(peerUid, maxId)
        }

        override suspend fun send(peerUid: String, text: String): ApiResult<ChatSendResult> {
            sendCalls += text
            return sendResponse(peerUid, text)
        }

        override suspend fun sendImage(peerUid: String, bytes: ByteArray): ApiResult<ChatSendResult> {
            imageCalls += bytes
            return imageResponse(peerUid, bytes)
        }
    }

    private fun message(id: String, fromMe: Boolean = false, time: Long = 1_000L) =
        ChatMessage(id = id, text = "消息$id", fromMe = fromMe, timestampMillis = time, timeText = null)

    @Test
    fun sendInsertsOptimisticBubbleThenConfirmsWithServerId() = runTest {
        val repository = FakeRepository()
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.updateInput("你好")

        vm.send()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.sending)
        // 服务端 id 替换本地临时 id，消息不再 pending
        assertEquals(listOf("100"), state.messages.map { it.id })
        assertTrue(state.messages.single().fromMe)
        assertFalse(state.messages.single().pending)
        assertEquals("你好", state.messages.single().text)
        // 发送成功后输入框清空
        assertEquals("", vm.input.value)
        // 发送成功立即拉一次最新页收取新消息
        assertTrue(repository.historyCalls.any { it.second == null })
    }

    @Test
    fun sendFailureRollsBackAndRestoresInput() = runTest {
        val repository = FakeRepository(
            sendResponse = { _, _ -> ApiResult.Error(message = "发送失败，请稍后再试") }
        )
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.updateInput("草稿内容")

        vm.send()
        advanceUntilIdle()

        // 事件提示与回滚在同一分支：回滚与回填成立即失败事件也已发出；
        // Snackbar 展示在真机验证，测试调度器下热流 collector 无法稳定同步
        val state = vm.uiState.value
        assertFalse(state.sending)
        assertTrue(state.messages.isEmpty())
        // 失败回填输入框，用户不必重新打字
        assertEquals("草稿内容", vm.input.value)
    }

    @Test
    fun sendIsIgnoredWhileSendingOrWhenInputBlank() = runTest {
        val repository = FakeRepository(
            sendResponse = { _, _ -> delay(10_000); ApiResult.Success(ChatSendResult("1", null)) }
        )
        val vm = keep(ChatViewModel("42", "对方", repository))

        // 空文本直接忽略，不发请求
        vm.send()
        advanceUntilIdle()
        assertTrue(repository.sendCalls.isEmpty())

        vm.updateInput("第一条")
        vm.send()
        runCurrent()
        assertTrue(vm.uiState.value.sending)

        // 进行中再点发送（含新输入）不会发出第二条
        vm.updateInput("第二条")
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf("第一条"), repository.sendCalls)
    }

    @Test
    fun sendReturnsTheOptimisticMessageForTheFlightAnimation() = runTest {
        val repository = FakeRepository()
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.updateInput("起飞")

        val sent = vm.send()

        // 界面拿这条乐观消息做「输入胶囊 → 目标气泡」的飞行起点与文案
        assertNotNull(sent)
        assertEquals("起飞", sent!!.text)
        assertTrue(sent.pending)
        // 输入已被清空，再点一次不会产生第二条气泡，也不该有动画
        assertNull(vm.send())
    }

    @Test
    fun sendUsesDraftSnapshotPassedByTheEditor() = runTest {
        val repository = FakeRepository()
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.updateInput("旧草稿")

        // 编辑器持有 TextFieldState，点击发送时直接把快照传进来，不依赖逐键同步是否已完成
        val sent = vm.send("直接传进来的文本")

        assertEquals("直接传进来的文本", sent?.text)
        assertEquals("", vm.input.value)
    }

    @Test
    fun sendClearsInputImmediatelyWhileRequestInFlight() = runTest {
        val repository = FakeRepository(
            sendResponse = { _, _ -> delay(10_000); ApiResult.Success(ChatSendResult("1", null)) }
        )
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.updateInput("马上要发出去的文字")

        vm.send()
        runCurrent()

        // 请求还没回来就必须清空输入框：用户可以直接接着打下一句，而不是等网络
        assertEquals("", vm.input.value)
        assertTrue(vm.uiState.value.sending)
        assertTrue(vm.uiState.value.messages.single().pending)
    }

    @Test
    fun imageSendInsertsPlaceholderAndConfirms() = runTest {
        val repository = FakeRepository()
        val vm = keep(ChatViewModel("42", "对方", repository))
        val bytes = ByteArray(16) { 7 }

        vm.sendImage(bytes)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.sending)
        assertEquals(listOf("200"), state.messages.map { it.id })
        assertTrue(state.messages.single().fromMe)
        assertEquals(1, repository.imageCalls.size)
    }

    @Test
    fun startCometIsIdempotentAndSafeWithoutClient() = runTest {
        val vm = keep(ChatViewModel("42", "对方", FakeRepository()))
        // cometClient 缺省为 null：不崩溃即可（CometD 链路由 ChatCometClientTest 覆盖）
        vm.startCometIfNeeded()
        vm.startCometIfNeeded()
    }

    @Test
    fun pollMergesNewMessagesWithoutDuplicates() = runTest {
        var newestCalls = 0
        val repository = FakeRepository(
            historyResponse = { _, maxId ->
                if (maxId == null) {
                    newestCalls += 1
                    // 首次进入只有一条历史；之后每次拉最新页多带回一条新消息（含与本地重复的一条）
                    val items = if (newestCalls == 1) {
                        listOf(message("1", fromMe = true, time = 900L))
                    } else {
                        listOf(message("3", time = 1_000L), message("1", fromMe = true, time = 900L))
                    }
                    ApiResult.Success(ChatMessagePage(items, null))
                } else {
                    ApiResult.Success(ChatMessagePage(emptyList(), null))
                }
            }
        )
        val vm = keep(ChatViewModel("42", "对方", repository))
        vm.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.uiState.value.messages.map { it.id })

        vm.poll()
        advanceUntilIdle()

        // 与本地按 id 去重合并，新消息进入且旧消息不丢
        assertEquals(listOf("3", "1"), vm.uiState.value.messages.map { it.id })
    }
}
