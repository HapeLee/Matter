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
    private var conversationSyncJob: Job? = null
    private var conversationStarted = false

    private val noticePages = mutableMapOf<MessageTab, Int>()
    private val noticeTokens = mutableMapOf<MessageTab, Int>()
    private val noticeJobs = mutableMapOf<MessageTab, Job>()
    private val noticeStarted = mutableSetOf<MessageTab>()

    fun selectTab(tab: MessageTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    /**
     * 页面内容出现：首次进入拉首屏，之后再次出现（切回私信分区、从会话页返回）只做静默同步。
     *
     * 会话的未读状态由服务端持有：拉取聊天记录时服务端把该会话标为已读，本工程没有任何
     * 「标记已读」写接口。因此去会话里读完再回到本页时必须重新取一次首页，否则列表会一直
     * 停在第一次进入时的未读数上。
     */
    fun onContentVisible(tab: MessageTab) {
        if (tab == MessageTab.DIRECT) {
            if (!conversationStarted) {
                conversationStarted = true
                loadConversations(page = 1, refresh = false)
            } else {
                syncConversations()
            }
        } else {
            if (!noticeStarted.add(tab)) return
            loadNotices(tab = tab, page = 1, refresh = false)
        }
    }

    /**
     * 点开会话时的即时反馈：先把本地未读清零，不等一次网络往返。
     *
     * 这里只改本地展示，服务端值仍是真相 —— 返回列表时的静默同步会用服务端数据覆盖它，
     * 所以聊天记录没真正拉起来（服务端没标已读）时，红点会在返回后重新出现。
     */
    fun markConversationRead(peerUid: String) {
        _uiState.update { state ->
            val items = state.conversations.items
            if (items.none { it.peerUid == peerUid && it.unreadCount > 0 }) return@update state
            state.copy(
                conversations = state.conversations.copy(
                    items = items.map { item ->
                        if (item.peerUid == peerUid) item.copy(unreadCount = 0) else item
                    }
                )
            )
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

    /**
     * 静默同步私信首页：不显示 loading、不动分页游标，失败也不覆盖已有列表。
     *
     * 服务端按时间倒序返回首页，这里把首页结果放在最前、其余已加载的会话按原顺序接在后面，
     * 已读状态会被更新，同时不丢掉用户翻过的页。
     */
    private fun syncConversations() {
        // 已有请求在飞时不叠加：首屏 / 下拉刷新 / 翻页返回的数据本身就带最新未读
        if (conversationJob?.isActive == true || conversationSyncJob?.isActive == true) return
        val token = conversationToken
        conversationSyncJob = viewModelScope.launch {
            when (val result = repository.conversations(page = 1)) {
                is ApiResult.Success -> {
                    // 期间发生过刷新 / 翻页，这份快照已经过期
                    if (token != conversationToken) return@launch
                    val fresh = result.data.conversations
                    _uiState.update { current ->
                        val freshKeys = fresh.mapTo(mutableSetOf()) { it.key }
                        val rest = current.conversations.items.filterNot { it.key in freshKeys }
                        current.copy(
                            conversations = current.conversations.copy(items = fresh + rest)
                        )
                    }
                }

                // 静默失败：保留旧列表，用户仍可下拉刷新重试
                is ApiResult.Error -> Unit
                ApiResult.Loading -> Unit
            }
        }
    }

    private fun loadConversations(page: Int, refresh: Boolean) {
        conversationJob?.cancel()
        conversationSyncJob?.cancel()
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
