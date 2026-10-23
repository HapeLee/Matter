package org.kazusa.matter.core.util

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

data class FormattedWeiboText(
    val annotatedString: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent> = emptyMap()
)

object WeiboTextFormatter {


    fun formatHtmlText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return html
            .replace("<br />", "\n")
            .replace("<br/>", "\n")
            .replace("<br>", "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * 将微博正文或评论文本格式化为支持富文本排版的 FormattedWeiboText：
     * 1. 网页链接：解析 <a> 标签与原生 http/https 链接，支持点击跳转系统浏览器；
     * 2. 微博表情：解析 <img> 表情及文字形式的 [doge]、[二哈] 等，通过 InlineTextContent 呈现原生表情图标；
     * 3. 互动高亮：高亮博主昵称（@xxx）与话题（#xxx#），支持点击交互事件。
     */
    fun formatWeiboContent(
        rawText: String?,
        primaryColor: Color,
        onMentionClick: ((String) -> Unit)? = null,
        onTopicClick: ((String) -> Unit)? = null,
        emojiImages: Map<String, String> = emptyMap()
    ): FormattedWeiboText {
        if (rawText.isNullOrBlank()) {
            return FormattedWeiboText(AnnotatedString(""))
        }

        // 1. 动态提取 HTML 中的表情图片 (如 <img ... alt="[doge]" src="//h5.sinaimg.cn/..." />)
        val dynamicEmojiMap = mutableMapOf<String, String>()
        val imgRegex = Regex("""<img[^>]+alt="(?<alt>\[[^\]]+\])"[^>]+src="(?<src>[^"]+)"[^>]*>|<img[^>]+src="(?<src2>[^"]+)"[^>]+alt="(?<alt2>\[[^\]]+\])"[^>]*>""", RegexOption.IGNORE_CASE)
        var preprocessed = imgRegex.replace(rawText) { matchResult ->
            val alt = matchResult.groups["alt"]?.value ?: matchResult.groups["alt2"]?.value ?: ""
            var src = matchResult.groups["src"]?.value ?: matchResult.groups["src2"]?.value ?: ""
            if (src.startsWith("//")) {
                src = "https:$src"
            }
            if (alt.isNotBlank() && src.isNotBlank()) {
                dynamicEmojiMap[alt] = src
            }
            alt
        }

        // HTML 的博主、话题链接保留完整标题（包括昵称中的标点），并记录站内目标。
        val htmlLinks = mutableListOf<Pair<String, String>>()
        val linkRegex = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val hrefRegex = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        preprocessed = linkRegex.replace(preprocessed) { match ->
            val hrefMatch = hrefRegex.find(match.groupValues[1])
            val href = decodeEntities(hrefMatch?.groups?.get(1)?.value ?: hrefMatch?.groups?.get(2)?.value.orEmpty())
            val title = decodeEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "")).trim().ifBlank { "网页链接" }
            val index = htmlLinks.size
            htmlLinks += href to title
            "\uFFF9LINK|$index\uFFFA"
        }

        preprocessed = decodeEntities(preprocessed
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")).trim()

        fun AnnotatedString.Builder.appendEntity(label: String, target: String, tag: String, onClick: ((String) -> Unit)?) {
            val style = SpanStyle(color = primaryColor)
            if (onClick == null || target.isBlank()) withStyle(style) { append(label) }
            else withLink(LinkAnnotation.Clickable(tag, TextLinkStyles(style = style), linkInteractionListener = { onClick(target) })) {
                append(label)
            }
        }

        val inlineContentMap = mutableMapOf<String, InlineTextContent>()
        var inlineIdCounter = 0

        val annotated = buildAnnotatedString {
            // 匹配模式：HTML链接标记、原生URL链接、微博表情标签、@昵称、#话题#
            val tokenRegex = Regex("""(\uFFF9LINK\|[0-9]+\uFFFA)|(${WeiboTextEntities.URL_PATTERN})|(\[[^\[\]\s]+\])|${WeiboTextEntities.TOPIC_PATTERN}|${WeiboTextEntities.MENTION_PATTERN}""")
            var lastIndex = 0

            tokenRegex.findAll(preprocessed).forEach { match ->
                val range = match.range
                if (range.first > lastIndex) {
                    append(preprocessed.substring(lastIndex, range.first))
                }

                val token = match.value
                when {
                    // HTML 链接标记
                    token.startsWith("\uFFF9LINK|") && token.endsWith("\uFFFA") -> {
                        val index = token.removePrefix("\uFFF9LINK|").removeSuffix("\uFFFA").toIntOrNull()
                        val (rawHref, title) = htmlLinks.getOrNull(index ?: -1) ?: ("" to "")
                        val userId = userIdFromLink(rawHref)
                        when {
                            title.startsWith("@") -> {
                                appendEntity(title, userId ?: title.drop(1), "mention", onMentionClick)
                                lastIndex = range.last + 1
                                return@forEach
                            }
                            WeiboTextEntities.topic.matches(title) -> {
                                appendEntity(title, WeiboTextEntities.topicName(title), "topic", onTopicClick)
                                lastIndex = range.last + 1
                                return@forEach
                            }
                            userId != null -> {
                                appendEntity(title, userId, "mention", onMentionClick)
                                lastIndex = range.last + 1
                                return@forEach
                            }
                        }
                        val href = when {
                            rawHref.startsWith("//") -> "https:$rawHref"
                            rawHref.startsWith("/") -> "https://m.weibo.cn$rawHref"
                            else -> rawHref
                        }
                        if (!href.startsWith("https://") && !href.startsWith("http://")) {
                            append(title)
                            lastIndex = range.last + 1
                            return@forEach
                        }
                        val linkStyle = SpanStyle(color = primaryColor)
                        val linkIconId = "link_icon_${inlineIdCounter++}"
                        inlineContentMap[linkIconId] = InlineTextContent(
                            Placeholder(
                                width = 15.sp,
                                height = 15.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = "网页链接",
                                tint = primaryColor,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        withLink(
                            LinkAnnotation.Url(
                                url = href,
                                styles = TextLinkStyles(style = linkStyle)
                            )
                        ) {
                            appendInlineContent(linkIconId, "[link]")
                            append(" $title")
                        }
                    }

                    // 原生 URL 链接
                    token.startsWith("http://") || token.startsWith("https://") -> {
                        val linkStyle = SpanStyle(color = primaryColor)
                        val linkIconId = "link_icon_${inlineIdCounter++}"
                        inlineContentMap[linkIconId] = InlineTextContent(
                            Placeholder(
                                width = 15.sp,
                                height = 15.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = "网页链接",
                                tint = primaryColor,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        withLink(
                            LinkAnnotation.Url(
                                url = token,
                                styles = TextLinkStyles(style = linkStyle)
                            )
                        ) {
                            appendInlineContent(linkIconId, "[link]")
                            append(" $token")
                        }
                    }

                    // 微博表情图标
                    token.startsWith("[") && token.endsWith("]") -> {
                        val emojiUrl = weiboEmojiImageModel(token, emojiImages) ?: dynamicEmojiMap[token]
                        if (emojiUrl != null) {
                            val inlineId = "emoji_${inlineIdCounter++}"
                            appendInlineContent(inlineId, token)
                            inlineContentMap[inlineId] = InlineTextContent(
                                Placeholder(
                                    width = 18.sp,
                                    height = 18.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                )
                            ) {
                                AsyncImage(
                                    model = emojiUrl,
                                    contentDescription = token,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            append(token)
                        }
                    }

                    token.startsWith("@") -> appendEntity(token, token.drop(1), "mention", onMentionClick)
                    token.startsWith("#") && token.endsWith("#") ->
                        appendEntity(token, WeiboTextEntities.topicName(token), "topic", onTopicClick)

                    else -> {
                        append(token)
                    }
                }
                lastIndex = range.last + 1
            }

            if (lastIndex < preprocessed.length) {
                append(preprocessed.substring(lastIndex))
            }
        }

        return FormattedWeiboText(
            annotatedString = annotated,
            inlineContent = inlineContentMap
        )
    }

    private fun userIdFromLink(href: String): String? =
        Regex("""^(?:https?://(?:m\.)?weibo\.(?:com|cn))?/(?:u|profile)/(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)
            ?: if (href.startsWith("sinaweibo://userinfo?")) Regex("""[?&]uid=(\d+)(?:&|$)""").find(href)?.groupValues?.get(1) else null

    private fun decodeEntities(value: String): String = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);""").replace(value) { match ->
        val entity = match.groupValues[1]
        when (entity) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val number = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                when {
                    number == null || number !in 0..0x10ffff || number in 0xd800..0xdfff -> match.value
                    number <= 0xffff -> number.toChar().toString()
                    else -> ((number - 0x10000) / 0x400 + 0xd800).toChar().toString() +
                        ((number - 0x10000) % 0x400 + 0xdc00).toChar()
                }
            }
        }
    }

    fun formatAnnotatedText(
        rawText: String?,
        primaryColor: Color,
        onMentionClick: ((String) -> Unit)? = null,
        onTopicClick: ((String) -> Unit)? = null
    ): AnnotatedString {
        return formatWeiboContent(rawText, primaryColor, onMentionClick, onTopicClick).annotatedString
    }
}
