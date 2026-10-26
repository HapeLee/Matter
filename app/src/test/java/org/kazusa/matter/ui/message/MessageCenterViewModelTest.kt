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
import org.kazusa.matter.data.model.ChatSendResult
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

    private fun conversation(uid: String, unread: Int = 0) = MessageConversation(
        peerUid = uid,
        peerName = "用户$uid",
        peerAvatarUrl = "",
        preview = "",
        timeText = null,
        unreadCount = unread
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
        override suspend fun send(peerUid: String, text: String): ApiResult<ChatSendResult> =
            ApiResult.Error(message = "本用例不使用私信发送")
        override suspend fun sendImage(peerUid: String, bytes: ByteArray): ApiResult<ChatSendResult> =
            ApiResult.Error(message = "本用例不使用私信发送")
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
        viewModel.onContentVisible(MessageTab.DIRECT)
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
        viewModel.onContentVisible(MessageTab.COMMENT)
        viewModel.onContentVisible(MessageTab.LIKE)
        advanceUntilIdle()

        assertEquals("评论加载失败", viewModel.uiState.value.comments.errorMessage)
        assertTrue(viewModel.uiState.value.comments.items.isEmpty())
        // 另一个分区不受影响
        assertEquals(listOf("9"), viewModel.uiState.value.likes.items.map { it.id })
        assertNull(viewModel.uiState.value.likes.errorMessage)
    }

    @Test
    fun firstEntryLoadsFirstPageAndReturnOnlySyncs() = runTest {
        var conversationCalls = 0
        var noticeCalls = 0
        val repository = FakeRepository(
            loadConversations = {
                conversationCalls += 1
                ApiResult.Success(MessageConversationPage(emptyList(), hasMore = false))
            },
            loadNotices = { _, _ ->
                noticeCalls += 1
                ApiResult.Success(MessageNoticePage(emptyList(), hasMore = false))
            }
        )

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()
        // 首次进入只拉一次首屏，不重复打首页
        assertEquals(1, conversationCalls)

        // 再次出现（从会话页返回 / 切回私信分区）改为静默同步，不再整页重载
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()
        assertEquals(2, conversationCalls)
        assertTrue(!viewModel.uiState.value.conversations.isInitialLoading)

        viewModel.onContentVisible(MessageTab.MENTION)
        viewModel.onContentVisible(MessageTab.MENTION)
        advanceUntilIdle()
        assertEquals(1, noticeCalls)
    }

    @Test
    fun reenteringConversationsPicksUpServerUnread() = runTest {
        var unread = 2
        val pages = mutableListOf<Int>()
        val repository = FakeRepository(loadConversations = { page ->
            pages += page
            ApiResult.Success(
                MessageConversationPage(listOf(conversation("1", unread)), hasMore = false)
            )
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.conversations.items.single().unreadCount)

        // 在会话页读完，服务端把该会话的未读清零；返回列表应当同步到 0
        unread = 0
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.conversations.items.single().unreadCount)
        assertEquals(listOf(1, 1), pages)
    }

    @Test
    fun markConversationReadClearsLocalBadgeImmediately() = runTest {
        val repository = FakeRepository(loadConversations = {
            ApiResult.Success(
                MessageConversationPage(
                    listOf(conversation("1", 3), conversation("2", 5)),
                    hasMore = false
                )
            )
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        // 不推进调度器：点开会话必须在跳转前同步生效，否则红点会跟着页面一起被带走
        viewModel.markConversationRead("1")

        val items = viewModel.uiState.value.conversations.items
        assertEquals(0, items.first { it.peerUid == "1" }.unreadCount)
        assertEquals(5, items.first { it.peerUid == "2" }.unreadCount)
    }

    @Test
    fun syncKeepsAlreadyLoadedPagesAndTheirOrder() = runTest {
        var unread = 1
        val repository = FakeRepository(loadConversations = { page ->
            when (page) {
                1 -> ApiResult.Success(
                    MessageConversationPage(listOf(conversation("1", unread)), hasMore = true)
                )
                else -> ApiResult.Success(
                    MessageConversationPage(listOf(conversation("2")), hasMore = false)
                )
            }
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()
        viewModel.loadMore(MessageTab.DIRECT)
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), viewModel.uiState.value.conversations.items.map { it.peerUid })

        unread = 0
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        val items = viewModel.uiState.value.conversations.items
        assertEquals(listOf("1", "2"), items.map { it.peerUid })
        assertEquals(0, items.first { it.peerUid == "1" }.unreadCount)
    }

    @Test
    fun syncFailureKeepsListAndDoesNotSurfaceError() = runTest {
        var failSync = false
        val repository = FakeRepository(loadConversations = {
            if (failSync) {
                ApiResult.Error(message = "网络断开")
            } else {
                ApiResult.Success(
                    MessageConversationPage(listOf(conversation("1", 2)), hasMore = false)
                )
            }
        })

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        failSync = true
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        val section = viewModel.uiState.value.conversations
        assertEquals(listOf("1"), section.items.map { it.peerUid })
        // 静默同步失败不该在列表上弹错误条，也不该卡在 loading
        assertNull(section.errorMessage)
        assertTrue(!section.isInitialLoading && !section.isRefreshing && !section.isLoadingMore)
    }

    @Test
    fun emptyPageEndsPagination() = runTest {
        val repository = FakeRepository(
            loadConversations = {
                ApiResult.Success(MessageConversationPage(listOf(conversation("1")), hasMore = false))
            }
        )

        val viewModel = MessageCenterViewModel(repository)
        viewModel.onContentVisible(MessageTab.DIRECT)
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.conversations.hasMore)
        assertTrue(!viewModel.uiState.value.conversations.canLoadMore)
    }
}
