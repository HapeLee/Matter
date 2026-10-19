package org.kazusa.matter.ui.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.effect.liquid.LiquidGlassFloatingMenu
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.rememberScreenBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPostsScreen(
    viewModel: SavedPostsViewModel,
    onBackClick: () -> Unit,
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit,
    onUserClick: (Long?) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onVideoClick: (String, String, List<VideoStreamInfo>, String, String) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collection = state.selected
    val list = state.lists.getValue(collection)
    // 状态由页面持有，切换分栏、进入正文后返回都保留各自的阅读位置。
    val likedListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val listState = if (collection == UserPostCollection.LIKES) likedListState else favoritesListState
    val backdrop = rememberScreenBackdrop()

    val currentCategoryTitle = when (collection) {
        UserPostCollection.LIKES -> "赞过"
        UserPostCollection.FAVORITES -> "收藏"
    }

    LaunchedEffect(collection, listState) {
        snapshotFlow {
            val current = state.lists.getValue(collection)
            val layout = listState.layoutInfo
            !current.refreshing && !current.loadingMore && current.nextPage != null && current.error == null &&
                layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { if (it) viewModel.loadMore(collection) }
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = currentCategoryTitle,
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            key(collection) {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = list.refreshing,
                    onRefresh = { viewModel.refresh(collection) },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = list.refreshing,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding())
                        )
                    }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = padding.calculateTopPadding() + 10.dp,
                            end = 12.dp,
                            // 给右下角悬浮切换按钮留出避让空间
                            bottom = padding.calculateBottomPadding() + 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (list.posts.isEmpty() && !list.refreshing && list.error == null) {
                            item(key = "empty") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillParentMaxHeight(0.6f)
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (collection == UserPostCollection.LIKES) "还没有赞过的微博" else "还没有收藏微博",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(
                            items = list.posts,
                            key = { it.listKey },
                            contentType = { "post" }
                        ) { post ->
                            WeiboPostCard(
                                post = post,
                                onPostClick = onPostClick,
                                onCommentClick = onCommentClick,
                                onUserClick = onUserClick,
                                onImageClick = onImageClick,
                                onVideoClick = onVideoClick,
                                onMentionClick = onMentionClick,
                                onTopicClick = onTopicClick
                            )
                        }
                        if (list.loadingMore) item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                        list.error?.let { error ->
                            item(key = "error") {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(error, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = viewModel::retry) { Text("重试") }
                                }
                            }
                        }
                        if (list.posts.isNotEmpty() && list.nextPage == null && !list.refreshing && list.error == null) {
                            item(key = "end") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (collection == UserPostCollection.LIKES) "已显示全部赞过的微博" else "已显示全部收藏",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 右下角通用的悬浮切换按钮
            LiquidGlassFloatingMenu(
                items = listOf("赞过", "收藏"),
                selectedIndex = UserPostCollection.entries.indexOf(collection),
                onSelect = { viewModel.select(UserPostCollection.entries[it]) },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp),
                icon = {
                    LiquidGlassIcon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "切换分类"
                    )
                }
            )
        }
    }
}
