package org.kazusa.matter.ui.composer.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.data.repository.PublishSession
import org.kazusa.matter.data.repository.SuggestionRepository
import org.kazusa.matter.ui.components.composer.ComposerImage
import org.kazusa.matter.ui.components.composer.ComposerPickerController
import org.kazusa.matter.ui.components.composer.ComposerPickerState
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH
import org.kazusa.matter.ui.components.composer.limitComposerDraft

/** 单条微博最多配图数（与服务端一致）。 */
internal const val MAX_POST_IMAGES = 9

internal data class PostComposerState(
    val draft: String = "",
    val images: List<ComposerImage> = emptyList(),
    /** 正在上传的图片序号，-1 表示没有在传。 */
    val uploadingIndex: Int = -1,
    val isSending: Boolean = false,
    val errorMessage: String? = null
) {
    /** 正文可为空，只要有图就能发；反之纯文字也可以。 */
    val canSubmit: Boolean get() = !isSending && (draft.isNotBlank() || images.isNotEmpty())
}

/**
 * 发微博面板的业务状态（NavEntry 级，离开编辑页即销毁）。
 *
 * - 账号在开始编辑时固定，提交过程中切号会让结果失效；
 * - 图片在**提交时**才读取并上传，失败保留草稿与已上传的 pic id，重试不重复上传；
 * - 成功后整体复位并通过 [published] 通知界面返回，由导航根统一提示。
 */
internal class PostComposerViewModel(
    private val publishRepository: PublishRepository,
    private val suggestionRepository: SuggestionRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PostComposerState())
    val state: StateFlow<PostComposerState> = _state.asStateFlow()

    private val picker = ComposerPickerController(viewModelScope) { kind, query ->
        suggestionRepository.suggestions(kind, query)
    }

    /** `@` / `#` 选择器状态；只有选择器自己收集。 */
    val pickerState: StateFlow<ComposerPickerState> = picker.state

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val publishedChannel = Channel<Unit>(Channel.BUFFERED)

    /** 发布成功：界面收到后返回上一页。 */
    val published: Flow<Unit> = publishedChannel.receiveAsFlow()

    private var session: PublishSession? = null
    private var submitJob: Job? = null
    private var submitToken = 0

    init {
        viewModelScope.launch {
            publishRepository.sessions.collect { current ->
                if (session != null && session != current) {
                    // 换账号：草稿、已选图片与选择器里的建议都属于上一个账号
                    submitToken++
                    submitJob?.cancel()
                    picker.dismiss()
                    _state.value = PostComposerState()
                }
                session = current
            }
        }
    }

    fun updateDraft(text: String) {
        if (_state.value.isSending) return
        val limited = limitComposerDraft(text, MAX_COMPOSER_TEXT_LENGTH)
        if (_state.value.draft == limited) return
        _state.update { it.copy(draft = limited, errorMessage = null) }
    }

    fun openPicker(kind: ReplyPickerKind) = picker.open(kind)

    fun updatePickerQuery(query: String) = picker.updateQuery(query)

    fun dismissPicker() = picker.dismiss()

    fun addImages(handles: List<ReplyImageHandle>) {
        if (_state.value.isSending || handles.isEmpty()) return
        _state.update { current ->
            val room = MAX_POST_IMAGES - current.images.size
            if (room <= 0) current
            else current.copy(
                images = current.images + handles.take(room).map(::ComposerImage),
                errorMessage = null
            )
        }
    }

    fun removeImage(index: Int) {
        if (_state.value.isSending) return
        _state.update { current ->
            if (index !in current.images.indices) current
            else current.copy(
                images = current.images.filterIndexed { i, _ -> i != index },
                errorMessage = null
            )
        }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val account = session ?: return
        val token = ++submitToken
        _state.update { it.copy(isSending = true, errorMessage = null) }
        submitJob = viewModelScope.launch {
            try {
                val pictures = ArrayList<StatusPicture>(current.images.size)
                var images = current.images
                for ((index, image) in images.withIndex()) {
                    image.uploaded?.let {
                        pictures += it
                        continue
                    }
                    _state.update { it.copy(uploadingIndex = index) }
                    val upload = publishRepository.upload(account, image.handle)
                    currentCoroutineContext().ensureActive()
                    if (token != submitToken || publishRepository.currentSession() != account) return@launch
                    when (upload) {
                        is ApiResult.Success -> {
                            images = images.toMutableList().also { it[index] = image.copy(uploaded = upload.data) }
                            pictures += upload.data
                            _state.update { it.copy(images = images) }
                        }

                        is ApiResult.Error -> {
                            _state.update {
                                it.copy(errorMessage = upload.message.ifBlank { "图片上传失败，请重试" })
                            }
                            return@launch
                        }

                        ApiResult.Loading -> {
                            _state.update { it.copy(errorMessage = "图片上传未完成，请重试") }
                            return@launch
                        }
                    }
                }
                _state.update { it.copy(uploadingIndex = -1) }

                // 上传期间用户可能又改了正文；以提交那一刻的输入为准
                val result = publishRepository.publish(account, current.draft.trim(), pictures)
                currentCoroutineContext().ensureActive()
                if (token != submitToken || publishRepository.currentSession() != account) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        _state.value = PostComposerState()
                        publishedChannel.trySend(Unit)
                    }

                    is ApiResult.Error -> _state.update {
                        it.copy(errorMessage = result.message.ifBlank { "发布失败，请稍后重试" })
                    }

                    ApiResult.Loading -> _state.update { it.copy(errorMessage = "发布未完成，请重试") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == submitToken) {
                    _state.update { it.copy(errorMessage = failure.message ?: "发布失败，请稍后重试") }
                }
            } finally {
                if (token == submitToken) {
                    _state.update { it.copy(isSending = false, uploadingIndex = -1) }
                }
            }
        }
    }
}
