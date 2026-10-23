package org.kazusa.matter.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test

class WeiboTextFormatterTest {
    @Test
    fun htmlMentionAndTopicUseInternalDestinationsWithoutWebIcons() {
        val users = mutableListOf<String>()
        val topics = mutableListOf<String>()
        val formatted = WeiboTextFormatter.formatWeiboContent(
            "<a href='/u/123456'>@博主·名字</a> <a href=\"https://huati.weibo.com/k/test\">#杭州[超话]#</a>",
            Color.Blue, users::add, topics::add
        )
        assertEquals("@博主·名字 #杭州[超话]#", formatted.annotatedString.text)
        assertTrue(formatted.inlineContent.isEmpty())
        val links = formatted.annotatedString.getLinkAnnotations(0, formatted.annotatedString.length)
        assertEquals(2, links.size)
        links.forEach { (it.item as LinkAnnotation.Clickable).let { link -> link.linkInteractionListener?.onClick(link) } }
        assertEquals(listOf("123456"), users)
        assertEquals(listOf("杭州"), topics)
    }

    @Test
    fun plainMentionsSupportUnicodeAndTopicsKeepTheirOwnAtSigns() {
        val users = mutableListOf<String>()
        val topics = mutableListOf<String>()
        val formatted = WeiboTextFormatter.formatWeiboContent(
            "谢谢@博主·é_1 #话题@名字# 联系 test@example.com",
            Color.Blue, users::add, topics::add
        )
        val links = formatted.annotatedString.getLinkAnnotations(0, formatted.annotatedString.length)
        assertEquals(2, links.size)
        links.forEach { (it.item as LinkAnnotation.Clickable).let { link -> link.linkInteractionListener?.onClick(link) } }
        assertEquals(listOf("博主·é_1"), users)
        assertEquals(listOf("话题@名字"), topics)
    }

    @Test
    fun encodedMentionAndSingleQuotedNameLinkStayClickable() {
        val users = mutableListOf<String>()
        val formatted = WeiboTextFormatter.formatWeiboContent(
            "<a href='/n/author'>&#64;昵称_测试</a> &amp; #话题#", Color.Blue, users::add
        )
        assertEquals("@昵称_测试 & #话题#", formatted.annotatedString.text)
        val link = formatted.annotatedString.getLinkAnnotations(0, formatted.annotatedString.length).single().item as LinkAnnotation.Clickable
        link.linkInteractionListener?.onClick(link)
        assertEquals(listOf("昵称_测试"), users)
    }

    @Test
    fun ordinaryWebLinksPreservePipesInTitlesAndResolveRelativeUrls() {
        val formatted = WeiboTextFormatter.formatWeiboContent("<a href='/detail/123'>A|B</a>", Color.Blue)
        val link = formatted.annotatedString.getLinkAnnotations(0, formatted.annotatedString.length).single().item as LinkAnnotation.Url
        assertEquals("https://m.weibo.cn/detail/123", link.url)
        assertTrue(formatted.annotatedString.text.endsWith(" A|B"))
    }

    @Test
    fun installedOnlineEmojiUsesTheSharedImageMapForRendering() {
        val result = WeiboTextFormatter.formatWeiboContent("新的[在线测试表情]", Color.Blue,
            emojiImages = mapOf("[在线测试表情]" to "file:/cached/face.png"))
        assertEquals(1, result.inlineContent.size)
        assertTrue(result.annotatedString.text.startsWith("新的"))
        // 内联图片保留原代码作为复制和无障碍替代文本。
        assertEquals("新的[在线测试表情]", result.annotatedString.text)
    }

    @Test
    fun punctuationAfterUrlDoesNotHideTheFollowingMentionOrTopic() {
        val entities = WeiboTextEntities.find("看 https://example.com。@博主 #话题#")
        assertEquals(listOf("博主", "话题"), entities.map { it.target })
    }
}
