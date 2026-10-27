package org.kazusa.matter.ui.composer.repost

import org.kazusa.matter.data.model.WeiboStatusDto

/** 直接转发时网页端使用的默认转发语；嵌套链里上一层没写字时也用它。 */
internal const val DEFAULT_REPOST_COMMENT = "转发微博"

/**
 * 转发一条「转发」时自动追加的嵌套文案：`//@上一层用户:他的转发语`。
 *
 * - 只补一层：更早的层级已经在那条微博的正文里，再补会和它重复；
 * - 转发原微博（没有 `retweeted_status`）不生成任何文案，就是普通转发；
 * - 把文案放进可编辑的正文而不是提交时偷偷拼接，用户才能删掉或改写它。
 */
internal fun repostChainOf(post: WeiboStatusDto): String {
    if (post.retweetedStatus == null) return ""
    val author = post.user?.screenName?.takeIf { it.isNotBlank() } ?: return ""
    return "//@$author:${repostCommentOf(post)}"
}

/** 上一层微博的转发语：去掉 HTML 标签与实体，空白时退回「转发微博」。 */
private fun repostCommentOf(post: WeiboStatusDto): String {
    val raw = post.textRaw?.takeIf { it.isNotBlank() } ?: post.text
    val cleaned = raw
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .trim()
    return cleaned.ifBlank { DEFAULT_REPOST_COMMENT }
}
