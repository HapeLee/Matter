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
import coil.compose.AsyncImage

data class FormattedWeiboText(
    val annotatedString: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent> = emptyMap()
)

object WeiboTextFormatter {

    /**
     * 常用官方微博表情对照表 (Sina CDN 高清静态图)
     */
    private val OFFICIAL_EMOJI_MAP: Map<String, String> = mapOf(
        "[doge]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/b7/2014new_doge02_org.png",
        "[二哈]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/22/2018new_erha_org.png",
        "[笑cry]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/34/2015new_xiaoku_org.png",
        "[偷笑]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/71/2018new_touxiao_org.png",
        "[泪]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/6e/2018new_leimu_org.png",
        "[心]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/8a/2018new_xin_org.png",
        "[赞]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/e6/2018new_zan_org.png",
        "[吃惊]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/49/2018new_chijing_org.png",
        "[思考]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/30/2018new_sikao_org.png",
        "[害羞]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/c1/2018new_haixiu_org.png",
        "[憧憬]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/c9/2018new_chongjing_org.png",
        "[鼓掌]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/6e/2018new_guzhang_org.png",
        "[拜拜]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/fd/2018new_baibai_org.png",
        "[给力]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/36/2018new_geili_org.png",
        "[悲伤]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/ee/2018new_beishang_org.png",
        "[太开心]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/1e/2018new_taikaixin_org.png",
        "[摊手]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/62/2018new_tanshou_org.png",
        "[喵喵]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/7b/2018new_miaomiao_org.png",
        "[疑问]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/b8/2018new_ningwen_org.png",
        "[握手]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/e9/2018new_woshou_org.png",
        "[作揖]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/e7/2018new_zuoyi_org.png",
        "[互粉]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/86/2018new_hufen02_org.png",
        "[鲜花]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/d4/2018new_xianhua_org.png",
        "[委屈]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/a5/2018new_weiqu_org.png",
        "[可爱]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/09/2018new_keai_org.png",
        "[酷]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/c4/2018new_ku_org.png",
        "[汗]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/28/2018new_han_org.png",
        "[衰]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/a2/2018new_shuai_org.png",
        "[闭嘴]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/62/2018new_bizui_org.png",
        "[钱]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/a2/2018new_qian_org.png",
        "[失望]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/0c/2018new_shiwang_org.png",
        "[晕]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/07/2018new_yun_org.png",
        "[阴险]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/9e/2018new_yinxian_org.png",
        "[怒]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/f6/2018new_nu_org.png",
        "[哈欠]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/45/2018new_haqian_org.png",
        "[并不简单]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/aa/2018new_bingbujiandan_org.png",
        "[泪奔]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/72/2018new_leiben_org.png",
        "[亲亲]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/2c/2018new_qinqin_org.png",
        "[生病]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/3b/2018new_shengbing_org.png",
        "[求抱抱]" to "https://img.t.sinajs.cn/t4/appstyle/expression/ext/normal/c4/2018new_baobao_org.png"
    )

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
        onTopicClick: ((String) -> Unit)? = null
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

        // 2. 预提取 HTML 中的超链接 (如 <a href="http://t.cn/xxx">网页链接</a>)
        // 编码为特殊标记符号以便后续安全还原
        val linkRegex = Regex("""<a[^>]+href="(?<href>[^"]+)"[^>]*>(?<content>.*?)</a>""", RegexOption.IGNORE_CASE)
        preprocessed = linkRegex.replace(preprocessed) { matchResult ->
            val href = matchResult.groups["href"]?.value ?: ""
            val rawContent = matchResult.groups["content"]?.value ?: ""
            val cleanTitle = rawContent.replace(Regex("<[^>]*>"), "").trim()
            val title = if (cleanTitle.isNotBlank()) cleanTitle else "网页链接"
            // HTML 包装的话题也交给 #话题# 分支，避免被当作网页链接添加图标。
            if (Regex("""#[^#\n\r]+#""").matches(title)) {
                title
            } else {
                "\uFFF9LINK|${href}|${title}\uFFFA"
            }
        }

        // 3. 清理换行符与剩余 HTML 标签及实体字符
        preprocessed = preprocessed
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

        val inlineContentMap = mutableMapOf<String, InlineTextContent>()
        var inlineIdCounter = 0

        val annotated = buildAnnotatedString {
            // 匹配模式：HTML链接标记、原生URL链接、微博表情标签、@昵称、#话题#
            val tokenRegex = Regex("""(\uFFF9LINK\|[^\uFFFA]+\uFFFA)|(https?://[a-zA-Z0-9./?&=_%#:-]+)|(\[[^\[\]\s]+\])|(@[\u4e00-\u9fa5a-zA-Z0-9_-]+)|(#[^#\n\r]+#)""")
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
                        val body = token.removePrefix("\uFFF9LINK|").removeSuffix("\uFFFA")
                        val parts = body.split("|")
                        val href = parts.getOrNull(0) ?: ""
                        val title = parts.getOrNull(1) ?: "网页链接"
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
                        val emojiUrl = dynamicEmojiMap[token] ?: OFFICIAL_EMOJI_MAP[token]
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

                    // @用户昵称
                    token.startsWith("@") -> {
                        val linkStyle = SpanStyle(color = primaryColor)
                        if (onMentionClick != null) {
                            val name = token.removePrefix("@")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "mention",
                                    styles = TextLinkStyles(style = linkStyle),
                                    linkInteractionListener = { onMentionClick(name) }
                                )
                            ) {
                                append(token)
                            }
                        } else {
                            withStyle(linkStyle) {
                                append(token)
                            }
                        }
                    }

                    // #话题#
                    token.startsWith("#") && token.endsWith("#") -> {
                        val linkStyle = SpanStyle(color = primaryColor)
                        if (onTopicClick != null) {
                            val topic = token.removePrefix("#").removeSuffix("#")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "topic",
                                    styles = TextLinkStyles(style = linkStyle),
                                    linkInteractionListener = { onTopicClick(topic) }
                                )
                            ) {
                                append(token)
                            }
                        } else {
                            withStyle(linkStyle) {
                                append(token)
                            }
                        }
                    }

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

    fun formatAnnotatedText(
        rawText: String?,
        primaryColor: Color,
        onMentionClick: ((String) -> Unit)? = null,
        onTopicClick: ((String) -> Unit)? = null
    ): AnnotatedString {
        return formatWeiboContent(rawText, primaryColor, onMentionClick, onTopicClick).annotatedString
    }
}
