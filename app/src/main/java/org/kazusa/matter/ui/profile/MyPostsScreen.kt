package org.kazusa.matter.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「我的微博」页。
 *
 * 复用博主时间线接口（`containerid=107603{uid}`），只展示当前登录账号发布的微博。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPostsScreen(
    uid: String,
    onBackClick: () -> Unit,
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit,
    onUserClick: (Long?) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onVideoClick: (String, String, List<VideoStreamInfo>, String, String) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit
) {
    // 列表与分页挂在 NavEntry 级 ViewModel 上：进入正文再返回时不会重新请求
    val viewModel: MyPostsViewModel = koinViewModel { parametersOf(uid) }

    val listState = rememberLazyListState()
    val backdrop = rememberScreenBackdrop()
    val pullState = rememberPullToRefreshState()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val posts = uiState.posts
    val hasMore = uiState.hasMore
    val isInitialLoading = uiState.isInitialLoading
    val isRefreshing = uiState.isRefreshing
    val isLoadingMore = uiState.isLoadingMore
    val errorMessage = uiState.errorMessage
    val isGuest = viewModel.isGuest

    LaunchedEffect(viewModel) { viewModel.loadIfNeeded() }

    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount to (layout.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collect { (total, lastVisible) ->
            // 能否加载由状态里的 canLoadMore 决定；列表增减会重新触发判断
            val section = viewModel.uiState.value
            if (section.canLoadMore && total > 0 && lastVisible >= total - 3) viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "我的微博",
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
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = isRefreshing,
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
                        bottom = padding.calculateBottomPadding() + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (posts.isEmpty()) {
                        item(key = "empty") {
                            EmptyMessage(
                                message = errorMessage
                                    ?: if (isInitialLoading) "正在加载我的微博…" else "还没有发过微博",
                                isLoading = isInitialLoading,
                                buttonText = if (errorMessage != null && !isGuest) "重试" else null,
                                onButtonClick = if (errorMessage != null && !isGuest) {
                                    { viewModel.retry() }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillParentMaxHeight(0.65f)
                            )
                        }
                    }

                    items(
                        items = posts,
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

                    if (isLoadingMore) {
                        item(key = "loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    if (posts.isNotEmpty() && errorMessage != null) {
                        item(key = "load_error") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { viewModel.retry() }) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    if (posts.isNotEmpty() && !hasMore && !isLoadingMore && errorMessage == null) {
                        item(key = "end") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "已显示全部",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
