package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable

/**
 * 默认分类「热门」的 containerid。
 *
 * 正常情况下来自服务端 `allGroups`；这里只用于「分类列表还没回来时」挑默认项，
 * 以及服务端没下发热门频道时的兜底判断，不参与任何分类名硬编码。
 */
const val DEFAULT_HOT_FEED_CONTAINER_ID = "102803"

/**
 * 热门流的一个分类（频道）。
 *
 * 来源：`https://weibo.com/ajax/feed/allGroups` 里 `group_type=1` 的分组
 * （「我的频道」：热门 / 同城 / 实时 / 榜单 / 明星 …；「频道推荐」：财经 / 读书 / 摄影 …）。
 *
 * - [id]：`gid`，请求热门流时作为 `group_id`；
 * - [containerId]：请求热门流时作为 `containerid`（部分频道与 gid 相同，如热门 `102803`）。
 *
 * 分类集合完全由服务端下发，客户端不写死任何分类名。
 */
@Immutable
data class HotFeedCategory(
    val id: String,
    val containerId: String,
    val title: String
)

/**
 * 热门流的一页数据。
 *
 * 端点为 `https://weibo.com/ajax/feed/hottimeline`，分页靠 [nextSinceId] + [nextMaxId]：
 * 下一页把这两个值原样回传即可（实测相邻两页零重叠）。
 */
@Immutable
data class HotFeedPage(
    val posts: List<WeiboStatusDto> = emptyList(),
    /** 下一页的 `since_id`；服务端返回的是「字符串里套一层 JSON」，已在解析层拆好 */
    val nextSinceId: String? = null,
    /** 下一页的 `max_id`（通常是 `1`） */
    val nextMaxId: String? = null,
    val hasMore: Boolean = false
)
