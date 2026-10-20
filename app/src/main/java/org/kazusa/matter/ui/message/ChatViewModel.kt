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
import org.kazusa.matter.data.model.ChatMessage
import org.kazusa.matter.data.repository.MessageRepository

/**
 * 与某个人的私信记录状态。
 *
 * [ChatUiState.messages] 始终按「最新在前」排列（索引 0 是最新一条），界面用 `reverseLayout`
 * 列表从底部开始展示，向上滚动时用 `max_id` 继续取更早的页。
 */
internal data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
) {
    val canLoadMore: Boolean
        get() = hasMore && errorMessage == null && !isInitialLoading && !isLoadingMore
}

internal class ChatViewModel(
    val peerUid: String,
    val peerName: String,
    private val repository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(isInitialLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var nextMaxId: Long? = null
    private var started = false
    private var requestToken = 0
    private var loadJob: Job? = null

    fun loadIfNeeded() {
        if (started) return
        started = true
        load(maxId = null, firstPage = true)
    }

    fun loadMore() {
        if (!_uiState.value.canLoadMore) return
        val maxId = nextMaxId ?: return
        load(maxId = maxId, firstPage = false)
    }

    fun retry() {
        if (_uiState.value.messages.isEmpty()) {
            load(maxId = null, firstPage = true)
        } else {
            nextMaxId?.let { load(maxId = it, firstPage = false) }
        }
    }

    private fun load(maxId: Long?, firstPage: Boolean) {
        loadJob?.cancel()
        requestToken += 1
        val token = requestToken
        _uiState.update {
            it.copy(
                isInitialLoading = firstPage,
                isLoadingMore = !firstPage,
                errorMessage = null
            )
        }

        loadJob = viewModelScope.launch {
            try {
                when (val result = repository.history(peerUid, maxId)) {
                    is ApiResult.Success -> {
                        if (token != requestToken) return@launch
                        val page = result.data
                        _uiState.update { current ->
                            val merged = if (firstPage) {
                                page.messages
                            } else {
                                (current.messages + page.messages).distinctBy { it.key }
                            }
                            current.copy(messages = merged, hasMore = page.nextMaxId != null)
                        }
                        nextMaxId = page.nextMaxId
                    }

                    is ApiResult.Error -> {
                        if (token != requestToken) return@launch
                        _uiState.update {
                            it.copy(errorMessage = result.message.ifBlank { "聊天记录加载失败" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == requestToken) {
                    _uiState.update {
                        it.copy(isInitialLoading = false, isLoadingMore = false)
                    }
                }
            }
        }
    }
}
