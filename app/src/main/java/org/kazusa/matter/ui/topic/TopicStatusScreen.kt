package org.kazusa.matter.ui.topic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.ui.search.SearchResultScreen

/**
 * 话题微博页。
 *
 * 点击正文话题 `#话题名#` 本质上是触发该话题标签下的内容搜索。
 * 原先使用固定模板 `100303type=532` 时由于缺少有效的内部 topicId，在服务端无法命中微博卡片流；
 * 现全面复用并打通 [SearchResultScreen] 的完整搜索结果架构：
 * - 携带格式化的话题关键词 `#话题#` 检索微博服务端；
 * - 顶栏支持直接修改搜索词；
 * - 右下角悬浮形变菜单提供服务端动态分栏（热门/综合、实时、图片、视频、文章等）；
 * - 原生支持下拉刷新与流式分页，完美展示包含多媒体卡片在内的完整话题微博流。
 */
@Composable
fun TopicStatusScreen(
    keyword: String,
    apiService: WeiboApiService,
    searchHistoryRepository: SearchHistoryRepository,
    onBackClick: () -> Unit,
    topicId: String = "",
    title: String = keyword,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String) -> Unit = { _, _ -> },
    onTopicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val formattedQuery = remember(keyword) {
        val trimmed = keyword.trim()
        if (trimmed.startsWith("#") && trimmed.endsWith("#")) trimmed else "#$trimmed#"
    }

    SearchResultScreen(
        query = formattedQuery,
        title = title.ifBlank { formattedQuery },
        apiService = apiService,
        searchHistoryRepository = searchHistoryRepository,
        onBackClick = onBackClick,
        onUserClick = onUserClick,
        onPostClick = onPostClick,
        onImageClick = onImageClick,
        onVideoClick = onVideoClick,
        onTopicClick = onTopicClick,
        onChipClick = onTopicClick,
        modifier = modifier
    )
}
