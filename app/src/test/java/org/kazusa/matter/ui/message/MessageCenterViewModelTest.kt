package org.kazusa.matter.ui.message

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.ChatMessagePage
import org.kazusa.matter.data.model.MessageConversation
import org.kazusa.matter.data.model.MessageConversationPage
import org.kazusa.matter.data.model.MessageNotice
import org.kazusa.matter.data.model.MessageNoticePage
import org.kazusa.matter.data.model.MessageTab
import org.kazusa.matter.data.repository.MessageRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MessageCenterViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun conversation(uid: String) = MessageConversation(
        peerUid = uid,
        peerName = "用户$uid",
        peerAvatarUrl = "",
        preview = "",
        timeText = null,
        unreadCount = 0
    )

    private fun notice(id: String) = MessageNotice(
        id = id,
        peerUid = "1",
        peerName = "用户",
        peerAvatarUrl = "",
        text = "内容",
        sourceText = null,
        timeText = null,
        statusId = null
    )

    private class FakeRepository(
        private val loadConversations: suspend (page: Int) -> ApiResult<MessageConversationPage> = {
            ApiResult.Success(MessageConversationPage(emptyList(), hasMore = false))
        },
        private val loadNotices: suspend (tab: MessageTab, page: Int) -> ApiResult<MessageNoticePage> = { _, _ ->
            ApiResult.Success(MessageNoticePage(emptyList(), hasMore = false))
        }
    ) : MessageRepository {
        override suspend fun conversations(page: Int) = loadConversations(page)
        override suspend fun notices(tab: MessageTab, page: Int) = loadNotices(tab, page)
        override suspend fun history(peerUid: String, maxId: Long?): ApiResult<ChatMessagePage> =
            ApiResult.Error(message = "本用例不使用聊天记录")
    }

    @Test
    fun conversationPaginationFailureKeepsPageAndRetryReusesIt() = runTest {
        val pages = mutableListOf<Int>()
        var failNext = true
        val repository = FakeRepository(loadConversations = { page ->
            pages += page
            when {
                page == 1 -> ApiResult.Success(
                    MessageConversationPage(listOf(conversation("1")), hasMore = true)
                )
                failNext -> {
                    failNext = false
                    ApiResult.Error(message = "网络断开")
                }
                else -> ApiResult.Success(
                    MessageConversationPage(listOf(conversation("2")), hasMore = false)
                )
            }
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.loadIfNeeded(MessageTab.DIRECT)
        advanceUntilIdle()

        viewModel.loadMore(MessageTab.DIRECT)
        advanceUntilIdle()
        assertEquals("网络断开", viewModel.uiState.value.conversations.errorMessage)
        assertTrue(viewModel.uiState.value.conversations.hasMore)
        assertEquals(listOf("1"), viewModel.uiState.value.conversations.items.map { it.peerUid })

        viewModel.retry(MessageTab.DIRECT)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(listOf("1", "2"), viewModel.uiState.value.conversations.items.map { it.peerUid })
        assertNull(viewModel.uiState.value.conversations.errorMessage)
    }

    @Test
    fun noticeSectionsFailIndependently() = runTest {
        val repository = FakeRepository(loadNotices = { tab, _ ->
            when (tab) {
                MessageTab.COMMENT -> ApiResult.Error(message = "评论加载失败")
                else -> ApiResult.Success(MessageNoticePage(listOf(notice("9")), hasMore = false))
            }
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.loadIfNeeded(MessageTab.COMMENT)
        viewModel.loadIfNeeded(MessageTab.LIKE)
        advanceUntilIdle()

        assertEquals("评论加载失败", viewModel.uiState.value.comments.errorMessage)
        assertTrue(viewModel.uiState.value.comments.items.isEmpty())
        // 另一个分区不受影响
        assertEquals(listOf("9"), viewModel.uiState.value.likes.items.map { it.id })
        assertNull(viewModel.uiState.value.likes.errorMessage)
    }

    @Test
    fun eachSectionLoadsOnlyOncePerEntry() = runTest {
        var calls = 0
        val repository = FakeRepository(
            loadConversations = {
                calls += 1
                ApiResult.Success(MessageConversationPage(emptyList(), hasMore = false))
            },
            loadNotices = { _, _ ->
                calls += 1
                ApiResult.Success(MessageNoticePage(emptyList(), hasMore = false))
            }
        )

        val viewModel = MessageCenterViewModel(repository)
        viewModel.loadIfNeeded(MessageTab.DIRECT)
        viewModel.loadIfNeeded(MessageTab.DIRECT)
        viewModel.loadIfNeeded(MessageTab.MENTION)
        viewModel.loadIfNeeded(MessageTab.MENTION)
        advanceUntilIdle()

        assertEquals(2, calls)
    }

    @Test
    fun emptyPageEndsPagination() = runTest {
        val repository = FakeRepository(
            loadConversations = {
                ApiResult.Success(MessageConversationPage(listOf(conversation("1")), hasMore = false))
            }
        )

        val viewModel = MessageCenterViewModel(repository)
        viewModel.loadIfNeeded(MessageTab.DIRECT)
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.conversations.hasMore)
        assertTrue(!viewModel.uiState.value.conversations.canLoadMore)
    }
}
