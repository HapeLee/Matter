package org.kazusa.matter.data.local

/**
 * 浏览历史的条目分类（位掩码）。
 * 浏览历史分类：`browsing_history` 表的 `category` 是整数位掩码，
 * DAO 里用 `category & ? > 0` 做筛选，采用位标志便于组合过滤与后续扩展。
 */
object BrowsingCategory {

    const val NONE = 0

    /** 微博正文 */
    const val POST = 1 shl 0

    /** 博主主页 */
    const val USER = 1 shl 1

    /** 话题页 */
    const val TOPIC = 1 shl 2

    /** 搜索关键词 */
    const val SEARCH = 1 shl 3

    /** 全部类别 */
    const val ALL = POST or USER or TOPIC or SEARCH
}
