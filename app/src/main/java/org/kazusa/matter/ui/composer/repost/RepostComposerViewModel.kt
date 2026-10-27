package org.kazusa.matter.ui.composer.repost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.data.repository.RepostRepository
import org.kazusa.matter.data.repository.RepostRequest
import org.kazusa.matter.data.repository.SuggestionRepository
import org.kazusa.matter.data.repository.repostKeyOf
import org.kazusa.matter.ui.components.composer.ComposerImage
import org.kazusa.matter.ui.components.composer.ComposerPickerController
import org.kazusa.matter.ui.components.composer.ComposerPickerState
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH
import org.kazusa.matter.ui.components.composer.limitComposerDraft

/**
 * 转发面板的状态。
 *
 * [target] 为 null 表示面板收起；草稿按被转发微博 id 各自保留，收起后重开仍在。
 * 转发一条**转发**时，草稿会被预先填上 `//@上一层用户:他的转发语`（见 [repostChainOf]），
 * 由 [draftPrefilled] 告诉界面把光标放到正文开头，让用户在自己的评论和这条链之间书写。
 */
internal data class RepostComposerState(
    val target: WeiboStatusDto? = null,
    val draft: String = "",
    /** 草稿是打开面板时自动填进去的嵌套转发文案，尚未被用户编辑过。 */
    val draftPrefilled: Boolean = false,
    /** 待转发图片（最多一张，与评论一致）；上传成功后才写入 `pic_id`。 */
    val image: ComposerImage? = null,
    val uploadingImage: Boolean = false,
    /** 同时评论此微博：转发语同时作为评论发给原作者。 */
    val alsoComment: Boolean = false,
    /** 分享范围。 */
    val visibility: StatusVisibility = StatusVisibility.PUBLIC,
    /** 提交在途：期间禁止重复提交。 */
    val isSending: Boolean = false,
    val errorMessage: String? = null
) {
    val isOpen: Boolean get() = target != null
}

/**
 * 转发面板的业务状态。
 *
 * 面板挂在导航根（信息流卡片与正文页共用一份），因此 ViewModel 的作用域比单个页面长：
 * - 写入经 [RepostRepository] 提交，计数乐观更新、失败回滚与换账号隔离都由仓库负责；
 * - 转发配图复用 [PublishRepository] 的图片上传（同一个图床端点、同一份账号固定语义），
 *   不为「转发」再造一条上传通道；
 * - 成功后整体复位并把提示交给 [messages]，由宿主决定提示位置；
 * - 同时只提交一条转发，换目标 / 收起不会让旧结果改写新面板。
 */
internal class RepostComposerViewModel(
    private val repostRepository: RepostRepository,
    private val publishRepository: PublishRepository,
    private val suggestionRepository: SuggestionRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RepostComposerState())
    val state: StateFlow<RepostComposerState> = _state.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val picker = ComposerPickerController(viewModelScope) { kind, query ->
        suggestionRepository.suggestions(kind, query)
    }

    /** `@` / `#` 选择器状态；只有选择器自己收集。 */
    val pickerState: StateFlow<ComposerPickerState> = picker.state

    private var submitJob: Job? = null
    private var submitToken = 0

    /** 用户改动过的草稿（被转发微博 id → 正文）；自动填入的嵌套文案不进来。 */
    private val drafts = mutableMapOf<String, String>()

    /** 打开面板；同一目标再次点击不重置正在编辑的草稿。 */
    fun open(post: WeiboStatusDto) {
        val key = repostKeyOf(post)
        if (key.isEmpty()) return
        val current = _state.value
        if (current.target?.let { repostKeyOf(it) } == key) return
        rememberDraft(current)
        // 旧提交的结果不再改写面板；请求本身仍会完成（仓库自己处理回滚与事件）
        submitToken += 1
        picker.dismiss()
        val saved = drafts[key]
        // 链也可能超长（上一层写了一篇长文）：与逐字输入同一口径截断
        val prefill = saved ?: limitComposerDraft(repostChainOf(post), MAX_COMPOSER_TEXT_LENGTH)
        _state.value = RepostComposerState(
            target = post,
            draft = prefill,
            draftPrefilled = saved == null && prefill.isNotEmpty()
        )
    }

    fun dismiss() {
        val current = _state.value
        rememberDraft(current)
        picker.dismiss()
        _state.value = RepostComposerState()
    }

    fun updateDraft(text: String) {
        val limited = limitComposerDraft(text, MAX_COMPOSER_TEXT_LENGTH)
        if (_state.value.draft == limited) return
        val key = _state.value.target?.let { repostKeyOf(it) }
        if (key != null) drafts[key] = limited
        _state.update { it.copy(draft = limited, draftPrefilled = false, errorMessage = null) }
    }

    fun addImage(handle: ReplyImageHandle) {
        if (_state.value.isSending) return
        _state.update { it.copy(image = ComposerImage(handle), errorMessage = null) }
    }

    fun removeImage() {
        if (_state.value.isSending || _state.value.uploadingImage) return
        _state.update { it.copy(image = null, errorMessage = null) }
    }

    fun setAlsoComment(enabled: Boolean) {
        _state.update { it.copy(alsoComment = enabled) }
    }

    fun setVisibility(visibility: StatusVisibility) {
        _state.update { it.copy(visibility = visibility) }
    }

    fun openPicker(kind: ReplyPickerKind) = picker.open(kind)

    fun updatePickerQuery(query: String) = picker.updateQuery(query)

    fun dismissPicker() = picker.dismiss()

    /** 转发语允许为空（直接转发），因此胶囊始终可点，只在提交在途时禁用。 */
    fun submit() {
        val current = _state.value
        val target = current.target ?: return
        if (current.isSending) return
        val token = ++submitToken
        val key = repostKeyOf(target)
        _state.update { it.copy(isSending = true, errorMessage = null) }
        submitJob = viewModelScope.launch {
            try {
                val picture = when (val outcome = ensurePictureUploaded(current, token, key)) {
                    is PictureOutcome.Ready -> outcome.picture
                    PictureOutcome.Aborted -> return@launch
                }
                val message = repostRepository.repost(
                    target,
                    RepostRequest(
                        text = current.draft.trim(),
                        pictures = listOfNotNull(picture),
                        alsoComment = current.alsoComment,
                        visibility = current.visibility
                    )
                )
                if (token != submitToken) return@launch
                if (message == null) {
                    drafts.remove(key)
                    // 目标已经切走时只保留新面板，不把旧结果关掉
                    if (_state.value.target?.let { repostKeyOf(it) } == key) {
                        _state.value = RepostComposerState()
                    }
                    messageChannel.trySend("转发成功")
                } else {
                    // 面板已被收起时错误无处显示，改用一次性提示，避免静默失败
                    if (_state.value.target?.let { repostKeyOf(it) } == key) {
                        _state.update { it.copy(errorMessage = message) }
                    } else {
                        messageChannel.trySend(message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == submitToken) {
                    val text = failure.message ?: "转发失败，请稍后重试"
                    if (_state.value.target?.let { repostKeyOf(it) } == key) {
                        _state.update { it.copy(errorMessage = text) }
                    } else {
                        messageChannel.trySend(text)
                    }
                }
            } finally {
                if (token == submitToken) {
                    _state.update { it.copy(isSending = false, uploadingImage = false) }
                }
            }
        }
    }

    private sealed interface PictureOutcome {
        /** [picture] 为 null 表示这次转发不带图。 */
        data class Ready(val picture: StatusPicture?) : PictureOutcome

        /** 图片上传失败：错误已经交给面板或一次性提示。 */
        data object Aborted : PictureOutcome
    }

    /**
     * 取出本次转发要用的图片 pid；已经传过的直接复用，重试不会重复上传。
     */
    private suspend fun ensurePictureUploaded(
        current: RepostComposerState,
        token: Int,
        key: String
    ): PictureOutcome {
        val image = current.image ?: return PictureOutcome.Ready(null)
        image.uploaded?.let { return PictureOutcome.Ready(it) }
        val session = publishRepository.currentSession()
        _state.update { it.copy(uploadingImage = true) }
        return when (val upload = publishRepository.upload(session, image.handle)) {
            is ApiResult.Success -> {
                if (_state.value.target?.let { repostKeyOf(it) } == key) {
                    _state.update {
                        it.copy(uploadingImage = false, image = it.image?.copy(uploaded = upload.data))
                    }
                }
                PictureOutcome.Ready(upload.data)
            }

            is ApiResult.Error -> {
                reportUploadFailure(token, key, upload.message.ifBlank { "图片上传失败，请重试" })
                PictureOutcome.Aborted
            }

            ApiResult.Loading -> {
                reportUploadFailure(token, key, "图片上传未完成，请重试")
                PictureOutcome.Aborted
            }
        }
    }

    private fun reportUploadFailure(token: Int, key: String, message: String) {
        if (token != submitToken) return
        if (_state.value.target?.let { repostKeyOf(it) } == key) {
            _state.update { it.copy(uploadingImage = false, errorMessage = message) }
        } else {
            messageChannel.trySend(message)
        }
    }

    /** 切换账号：草稿与在途提交都属于旧账号，一并丢弃。 */
    fun reset() {
        submitToken += 1
        submitJob?.cancel()
        drafts.clear()
        picker.dismiss()
        _state.value = RepostComposerState()
    }

    /**
     * 收起/切换目标前记下正文。
     *
     * 只记用户改过的草稿：自动填入的嵌套转发文案不算草稿，否则下次打开会停在这条链上，
     * 而不是重新按当时的微博内容生成（也与「用户把正文删空」区分开）。
     */
    private fun rememberDraft(current: RepostComposerState) {
        if (current.draftPrefilled) return
        current.target?.let { drafts[repostKeyOf(it)] = current.draft }
    }
}
