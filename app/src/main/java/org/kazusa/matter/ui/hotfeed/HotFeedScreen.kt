package org.kazusa.matter.ui.hotfeed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.effect.fadeInOnContentChange

/**
 * 「热门」视图（主框架「热门」Tab 下的展示模式之一）。
 *
 * 顶栏标题是当前分类名（默认「热门」）；分类切换交给悬浮底栏右侧的分类按钮。
 *
 * 列表状态按分类重建：换分类后自然从顶部开始，也不需要在主框架里保存每页位置。
 * 切换时的过渡（旧列表 → loading → 新列表）交给 [fadeInOnContentChange]：
 * 它只在分类真正变化时把新列表淡入一次，翻页 / 下拉刷新都不会重置 alpha。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotFeedScreen(
    viewModel: HotFeedViewModel,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onCommentClick: (WeiboStatusDto) -> Unit = onPostClick,
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit =
        { _, _, _, _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    backdrop: LayerBackdrop? = null,
    bottomBarPadding: Dp = 0.dp,
    /** 是否为主框架当前展示的内容；交叉淡化期间离场的那份不应再上报滚动状态 */
    isActive: Boolean = true,
    /** 上报当前列表状态，主框架用它复刻动态页的「到顶立即显示底栏」判断 */
    onActiveListStateChanged: (LazyListState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()

    val categoryId = uiState.selectedCategory?.containerId
    // 每个分类一份列表状态：换分类时输入变化 → 重建，从顶部开始；
    // 用 rememberSaveable 是为了「点进微博正文 / 用户主页再返回」这一程：
    // 主框架会随导航离屏被 Nav3 回收组合，普通 remember 会连状态一起丢掉、返回即回顶，
    // 而 NavEntry 的 saveable 状态由 rememberSaveableStateHolderNavEntryDecorator 保存，
    // 返回时按同一分类 id 把首行下标与像素偏移原样恢复（与主页动态瀑布流的做法一致）。
    val listState = rememberSaveable(categoryId, saver = LazyListState.Saver) { LazyListState() }

    SideEffect {
        if (isActive) onActiveListStateChanged(listState)
    }

    // 距末尾 5 项时静默预加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 5
        }
    }
    LaunchedEffect(shouldLoadMore, categoryId) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                animationLabel = "HotFeedTopBarScrim",
                title = { scroll ->
                    TopBarTitleText(
                        text = uiState.selectedCategory?.title ?: "热门",
                        scroll = scroll,
                        backdrop = backdrop
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            state = pullRefreshState,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                uiState.posts.isEmpty() && (uiState.isLoading || uiState.isRefreshing) -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                uiState.posts.isEmpty() && uiState.errorMessage != null -> {
                    HotFeedError(
                        message = uiState.errorMessage ?: "加载失败",
                        onRetry = viewModel::retry
                    )
                }

                uiState.posts.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        // 只在「换分类」时让整列表淡入一次：同分类刷新 / 翻页不会重置 alpha
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface)
                            .fadeInOnContentChange(key = categoryId),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = topPadding + 8.dp,
                            end = 12.dp,
                            bottom = bottomBarPadding + 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = uiState.posts,
                            key = { it.listKey },
                            contentType = { "post" }
                        ) { post ->
                            WeiboPostCard(
                                post = post,
                                onUserClick = onUserClick,
                                onPostClick = onPostClick,
                                onCommentClick = onCommentClick,
                                onImageClick = onImageClick,
                                onVideoClick = onVideoClick,
                                onMentionClick = onMentionClick,
                                onTopicClick = onTopicClick
                            )
                        }

                        if (uiState.isLoadingMore) {
                            item(key = "hot_feed_loading_more", contentType = "loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // 分页失败：保留「还有下一页」的事实，只在这里给一次原地重试
                        val pageError = uiState.pageErrorMessage
                        if (pageError != null && !uiState.isLoadingMore) {
                            item(key = "hot_feed_page_error", contentType = "error") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = pageError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    TextButton(onClick = viewModel::retryLoadMore) {
                                        Text("重试")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 首屏失败占位：一条可读的错误 + 重试。 */
@Composable
private fun HotFeedError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = "热门流加载失败",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text("重新加载")
            }
        }
    }
}
