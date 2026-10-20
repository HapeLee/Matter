package org.kazusa.matter.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.MessageConversation
import org.kazusa.matter.data.model.MessageNotice
import org.kazusa.matter.data.model.MessageTab
import org.kazusa.matter.data.repository.MessageRepository

/** 「私信」分区状态。 */
internal data class ConversationSection(
    val items: List<MessageConversation> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
) {
    val canLoadMore: Boolean
        get() = hasMore && errorMessage == null &&
            !isInitialLoading && !isRefreshing && !isLoadingMore
}

/** 「@我 / 评论 / 赞」分区状态。 */
internal data class NoticeSection(
    val items: List<MessageNotice> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
) {
    val canLoadMore: Boolean
        get() = hasMore && errorMessage == null &&
            !isInitialLoading && !isRefreshing && !isLoadingMore
}

internal data class MessageCenterUiState(
    val tab: MessageTab = MessageTab.DIRECT,
    val conversations: ConversationSection = ConversationSection(),
    val mentions: NoticeSection = NoticeSection(),
    val comments: NoticeSection = NoticeSection(),
    val likes: NoticeSection = NoticeSection()
) {
    fun noticeSection(tab: MessageTab): NoticeSection = when (tab) {
        MessageTab.MENTION -> mentions
        MessageTab.COMMENT -> comments
        MessageTab.LIKE -> likes
        MessageTab.DIRECT -> NoticeSection()
    }
}

/**
 * 消息中心：私信 / @我 / 评论 / 赞 四个分区共用一份页面状态。
 *
 * 四个分区各自独立分页：互不取消对方，但同一分区内刷新会取代仍在路上的分页。
 * 只有请求成功才推进页码与 hasMore；失败只记录 errorMessage，页码保留供原地重试。
 */
internal class MessageCenterViewModel(
    private val repository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessageCenterUiState())
    val uiState: StateFlow<MessageCenterUiState> = _uiState.asStateFlow()

    private var conversationPage = 1
    private var conversationToken = 0
    private var conversationJob: Job? = null
    private var conversationStarted = false

    private val noticePages = mutableMapOf<MessageTab, Int>()
    private val noticeTokens = mutableMapOf<MessageTab, Int>()
    private val noticeJobs = mutableMapOf<MessageTab, Job>()
    private val noticeStarted = mutableSetOf<MessageTab>()

    fun selectTab(tab: MessageTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun loadIfNeeded(tab: MessageTab) {
        if (tab == MessageTab.DIRECT) {
            if (conversationStarted) return
            conversationStarted = true
            loadConversations(page = 1, refresh = false)
        } else {
            if (!noticeStarted.add(tab)) return
            loadNotices(tab = tab, page = 1, refresh = false)
        }
    }

    fun refresh(tab: MessageTab) {
        if (tab == MessageTab.DIRECT) {
            loadConversations(page = 1, refresh = true)
        } else {
            loadNotices(tab = tab, page = 1, refresh = true)
        }
    }

    fun loadMore(tab: MessageTab) {
        if (tab == MessageTab.DIRECT) {
            val section = _uiState.value.conversations
            if (!section.canLoadMore) return
            loadConversations(page = conversationPage, refresh = false)
        } else {
            if (!_uiState.value.noticeSection(tab).canLoadMore) return
            loadNotices(tab = tab, page = noticePages[tab] ?: 1, refresh = false)
        }
    }

    fun retry(tab: MessageTab) {
        if (tab == MessageTab.DIRECT) {
            val empty = _uiState.value.conversations.items.isEmpty()
            loadConversations(page = if (empty) 1 else conversationPage, refresh = false)
        } else {
            val empty = _uiState.value.noticeSection(tab).items.isEmpty()
            loadNotices(tab = tab, page = if (empty) 1 else noticePages[tab] ?: 1, refresh = false)
        }
    }

    private fun loadConversations(page: Int, refresh: Boolean) {
        conversationJob?.cancel()
        conversationToken += 1
        val token = conversationToken
        val firstPage = refresh || page <= 1
        _uiState.update {
            it.copy(
                conversations = it.conversations.copy(
                    isInitialLoading = !refresh && firstPage,
                    isRefreshing = refresh,
                    isLoadingMore = !refresh && !firstPage,
                    errorMessage = null
                )
            )
        }

        conversationJob = viewModelScope.launch {
            try {
                when (val result = repository.conversations(page)) {
                    is ApiResult.Success -> {
                        if (token != conversationToken) return@launch
                        val data = result.data
                        _uiState.update { current ->
                            val merged = if (firstPage) {
                                data.conversations
                            } else {
                                (current.conversations.items + data.conversations)
                                    .distinctBy { it.key }
                            }
                            current.copy(
                                conversations = current.conversations.copy(
                                    items = merged,
                                    hasMore = data.hasMore && data.conversations.isNotEmpty()
                                )
                            )
                        }
                        conversationPage = if (_uiState.value.conversations.hasMore) page + 1 else page
                    }

                    is ApiResult.Error -> {
                        if (token != conversationToken) return@launch
                        _uiState.update {
                            it.copy(
                                conversations = it.conversations.copy(
                                    errorMessage = result.message.ifBlank { "私信加载失败" }
                                )
                            )
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == conversationToken) {
                    _uiState.update {
                        it.copy(
                            conversations = it.conversations.copy(
                                isInitialLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false
                            )
                        )
                    }
                }
            }
        }
    }

    private fun loadNotices(tab: MessageTab, page: Int, refresh: Boolean) {
        noticeJobs[tab]?.cancel()
        val token = (noticeTokens[tab] ?: 0) + 1
        noticeTokens[tab] = token
        val firstPage = refresh || page <= 1
        updateNotice(tab) {
            it.copy(
                isInitialLoading = !refresh && firstPage,
                isRefreshing = refresh,
                isLoadingMore = !refresh && !firstPage,
                errorMessage = null
            )
        }

        noticeJobs[tab] = viewModelScope.launch {
            try {
                when (val result = repository.notices(tab, page)) {
                    is ApiResult.Success -> {
                        if (token != noticeTokens[tab]) return@launch
                        val data = result.data
                        updateNotice(tab) { current ->
                            val merged = if (firstPage) {
                                data.notices
                            } else {
                                (current.items + data.notices).distinctBy { it.key }
                            }
                            current.copy(items = merged, hasMore = data.hasMore && data.notices.isNotEmpty())
                        }
                        val hasMore = _uiState.value.noticeSection(tab).hasMore
                        noticePages[tab] = if (hasMore) page + 1 else page
                    }

                    is ApiResult.Error -> {
                        if (token != noticeTokens[tab]) return@launch
                        updateNotice(tab) {
                            it.copy(errorMessage = result.message.ifBlank { "提醒加载失败" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == noticeTokens[tab]) {
                    updateNotice(tab) {
                        it.copy(isInitialLoading = false, isRefreshing = false, isLoadingMore = false)
                    }
                }
            }
        }
    }

    private fun updateNotice(tab: MessageTab, transform: (NoticeSection) -> NoticeSection) {
        _uiState.update { state ->
            when (tab) {
                MessageTab.MENTION -> state.copy(mentions = transform(state.mentions))
                MessageTab.COMMENT -> state.copy(comments = transform(state.comments))
                MessageTab.LIKE -> state.copy(likes = transform(state.likes))
                MessageTab.DIRECT -> state
            }
        }
    }
}
