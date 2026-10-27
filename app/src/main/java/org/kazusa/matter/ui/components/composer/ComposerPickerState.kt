package org.kazusa.matter.ui.components.composer

import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion

/**
 * `@` 用户 / 话题选择器的状态。
 *
 * 评论面板、发微博与转发三个入口共用同一份结构：谁需要插入文本，谁就持有这份状态并响应选择事件。
 */
internal data class ComposerPickerState(
    val kind: ReplyPickerKind? = null,
    val query: String = "",
    val loading: Boolean = false,
    val suggestions: List<ReplySuggestion> = emptyList(),
    val error: String? = null
)
