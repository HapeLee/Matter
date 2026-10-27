package org.kazusa.matter.ui.components.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion

/**
 * 输入框里 `@用户` / `#话题` 选择器的状态机。
 *
 * 评论、发微博与转发三个输入框都要「点开就拉一批默认建议、边打字边搜、换词丢弃旧结果」，
 * 这段时序（去抖 250ms + 令牌作废旧请求）只有一份实现，各 ViewModel 只负责把它接到自己的
 * 作用域与仓库上。选择后的文本插入仍留在 UI：三个输入框的包裹格式并不相同。
 */
internal class ComposerPickerController(
    private val scope: CoroutineScope,
    private val fetch: suspend (ReplyPickerKind, String) -> ApiResult<List<ReplySuggestion>>
) {
    private val _state = MutableStateFlow(ComposerPickerState())
    val state: StateFlow<ComposerPickerState> = _state.asStateFlow()

    private var job: Job? = null
    private var token = 0

    /** 打开选择器并立即拉一次默认建议（未输入时是关注列表 / 热搜）。 */
    fun open(kind: ReplyPickerKind) {
        _state.value = ComposerPickerState(kind = kind)
        search("")
    }

    /** 输入法每次改动都会来这里；空白查询同样要等去抖，避免逐字打热搜请求。 */
    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
        search(query)
    }

    fun dismiss() {
        token += 1
        job?.cancel()
        _state.value = ComposerPickerState()
    }

    private fun search(query: String) {
        val kind = _state.value.kind ?: return
        val current = ++token
        job?.cancel()
        _state.update { it.copy(query = query, loading = true, suggestions = emptyList(), error = null) }
        job = scope.launch {
            try {
                if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
                val result = fetch(kind, query)
                if (current != token) return@launch
                when (result) {
                    is ApiResult.Success -> _state.update {
                        it.copy(loading = false, suggestions = result.data)
                    }

                    is ApiResult.Error -> _state.update {
                        it.copy(loading = false, error = result.message)
                    }

                    ApiResult.Loading -> _state.update { it.copy(loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current == token) {
                    _state.update { it.copy(loading = false, error = "加载失败，请重试") }
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
