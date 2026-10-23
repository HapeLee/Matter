package org.kazusa.matter.core.util

internal enum class WeiboEntityKind { MENTION, TOPIC }

internal data class WeiboTextEntity(val start: Int, val end: Int, val kind: WeiboEntityKind, val target: String)

/** 输入高亮与正文渲染共用；URL、邮箱和表情代码不拆成 @ 或话题。 */
internal object WeiboTextEntities {
    const val MENTION_PATTERN = "(?<![a-zA-Z0-9_.%+@-])@[\\p{L}\\p{M}\\p{N}\\p{So}_·\\-]+"
    const val TOPIC_PATTERN = "#[^#\\n\\r\\uFFF9\\uFFFA]+#"
    const val URL_PATTERN = """https?://[^\s<>"'，。！？；：“”‘’（）【】、\uFFF9\uFFFA]+"""
    val topic = Regex(TOPIC_PATTERN)
    val tokens = Regex("""$URL_PATTERN|\[[^\[\]\s]+\]|$TOPIC_PATTERN|$MENTION_PATTERN""")

    fun find(text: String): List<WeiboTextEntity> = tokens.findAll(text).mapNotNull { match ->
        val value = match.value
        when {
            value.startsWith('@') -> WeiboTextEntity(match.range.first, match.range.last + 1, WeiboEntityKind.MENTION, value.drop(1))
            value.startsWith('#') -> WeiboTextEntity(match.range.first, match.range.last + 1, WeiboEntityKind.TOPIC, topicName(value))
            else -> null
        }
    }.filter { it.target.isNotBlank() }.toList()

    fun topicName(label: String): String = label.removeSurrounding("#").removeSuffix("[超话]").trim()
}
