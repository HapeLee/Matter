package org.kazusa.matter.ui.components.composer

/** 微博正文 / 转发语的硬上限：与评论同一口径，超出部分直接截断。 */
internal const val MAX_COMPOSER_TEXT_LENGTH = 2000

/**
 * 按 [maxLength] 截断，且不把 UTF-16 代理对（emoji）切成半个字符。
 *
 * 表情代码（`[微笑]`）的整块截断由 [limitEmojiDraft] 负责，这里只处理字符边界。
 */
internal fun limitComposerDraft(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    val end = if (text[maxLength - 1].isHighSurrogate() && text[maxLength].isLowSurrogate()) {
        maxLength - 1
    } else {
        maxLength
    }
    return text.take(end)
}
