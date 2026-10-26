package org.kazusa.matter.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    val errorMessage: String? = null,
    /** 私信发送进行中：输入条禁用并显示进度。 */
    val sending: Boolean = false
) {
    val canLoadMore: Boolean
        get() = hasMore && errorMessage == null && !isInitialLoading && !isLoadingMore
}

internal class ChatViewModel(
    val peerUid: String,
    val peerName: String,
    private val repository: MessageRepository,
    private val cometClient: org.kazusa.matter.core.network.ChatCometClient? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(isInitialLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var cometStarted = false

    /**
     * 输入框草稿单独一条流：逐字输入只重组输入条，不带动整页消息列表
     * （与正文页 ReplyComposerState 同一处理方式）。
     */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** 一次性事件（发送失败提示等），Snackbar 消费后即消失。 */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    private var nextMaxId: Long? = null
    private var started = false
    private var requestToken = 0
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    fun loadIfNeeded() {
        if (started) return
        started = true
        load(maxId = null, firstPage = true)
    }

    /**
     * 启动 CometD 长轮询：web.im.weibo.com 的推送当唤醒信号，收到即拉最新一页。
     * 定时轮询仍保留——长轮询断连 / 重连退避期间由它兜底。
     */
    fun startCometIfNeeded() {
        if (cometStarted) return
        cometStarted = true
        cometClient?.start(viewModelScope, onWake = ::poll)
    }

    override fun onCleared() {
        cometClient?.stop()
        super.onCleared()
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

    fun updateInput(text: String) {
        _input.value = text
    }

    /**
     * 发送一条私信；被空文本或「上一条还在发送」拦下时返回 null。
     *
     * [draft] 由界面传入：输入框现在持有 Compose `TextFieldState`，界面在点击发送时取一次快照传进来，
     * 不依赖 `_input` 是否已经同步（同步是逐键异步进行的）。返回值是刚插入的乐观消息，
     * 界面用它做「输入胶囊 → 目标气泡」的飞行动画：只有真正插入了气泡才有动画。
     */
    fun send(draft: String = _input.value): ChatMessage? {
        val text = draft.trim()
        if (text.isEmpty() || _uiState.value.sending) return null
        val optimistic = ChatMessage(
            id = "local_${System.currentTimeMillis()}",
            text = text,
            fromMe = true,
            timestampMillis = System.currentTimeMillis(),
            timeText = null,
            pending = true
        )
        _uiState.update { it.copy(messages = listOf(optimistic) + it.messages, sending = true) }
        _input.value = ""
        viewModelScope.launch {
            when (val result = repository.send(peerUid, text)) {
                is ApiResult.Success -> {
                    confirmOptimistic(optimistic, result.data.id, result.data.timestampMillis)
                    // 立即拉一次最新页：带进服务端确认的消息与对方可能的新回复
                    poll()
                }

                is ApiResult.Error -> {
                    rollbackOptimistic(optimistic)
                    _input.value = text
                    _events.trySend(result.message.ifBlank { "发送失败，请稍后重试" })
                }

                ApiResult.Loading -> Unit
            }
        }
        return optimistic
    }

    fun sendImage(bytes: ByteArray) {
        if (_uiState.value.sending) return
        // 图片消息的 fid 由服务端生成，本地先插入占位气泡，成功后由轮询拉回真实消息渲染。
        // 占位气泡不带文字：确认后正文会被图片替换，先写一行字再消失比空着更跳
        val optimistic = ChatMessage(
            id = "local_img_${System.currentTimeMillis()}",
            text = "",
            fromMe = true,
            timestampMillis = System.currentTimeMillis(),
            timeText = null,
            pending = true
        )
        _uiState.update { it.copy(messages = listOf(optimistic) + it.messages, sending = true) }
        viewModelScope.launch {
            when (val result = repository.sendImage(peerUid, bytes)) {
                is ApiResult.Success -> {
                    confirmOptimistic(optimistic, result.data.id, result.data.timestampMillis)
                    poll()
                }

                is ApiResult.Error -> {
                    rollbackOptimistic(optimistic)
                    _events.trySend(result.message.ifBlank { "图片发送失败，请稍后重试" })
                }

                ApiResult.Loading -> Unit
            }
        }
    }

    /**
     * 拉取最新一页并与本地合并（按 id 去重）：页面可见期间由界面定时触发，用于收取对方新消息。
     * 轮询失败静默——下一次轮询会重试，不该用错误条打断阅读。
     */
    fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            when (val result = repository.history(peerUid, maxId = null)) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        val merged = (result.data.messages + state.messages)
                            .distinctBy { it.key }
                            .sortedByDescending { it.timestampMillis ?: Long.MIN_VALUE }
                        state.copy(messages = merged)
                    }
                }

                is ApiResult.Error -> Unit
                ApiResult.Loading -> Unit
            }
        }
    }

    private fun confirmOptimistic(optimistic: ChatMessage, serverId: String?, serverTime: Long?) {
        _uiState.update { state ->
            state.copy(
                sending = false,
                messages = state.messages.map { message ->
                    if (message.id != optimistic.id) message else message.copy(
                        id = serverId?.takeIf { it.isNotBlank() } ?: message.id,
                        timestampMillis = serverTime ?: message.timestampMillis,
                        pending = false
                    )
                }
            )
        }
    }

    private fun rollbackOptimistic(optimistic: ChatMessage) {
        _uiState.update { state ->
            state.copy(
                sending = false,
                messages = state.messages.filterNot { it.id == optimistic.id }
            )
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
