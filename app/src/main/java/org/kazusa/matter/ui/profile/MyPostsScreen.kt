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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.effect.rememberScreenBackdrop

/**
 * 「我的微博」页。
 *
 * 复用博主时间线接口（`containerid=107603{uid}`），只展示当前登录账号发布的微博。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPostsScreen(
    uid: String,
    apiService: WeiboApiService,
    onBackClick: () -> Unit,
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit,
    onUserClick: (Long?) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onVideoClick: (String, String, List<VideoStreamInfo>, String, String) -> Unit,
    onMentionClick: (String) -> Unit,
    onTopicClick: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val backdrop = rememberScreenBackdrop()
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    val isGuest = uid.isBlank() || uid == "guest"

    var posts by remember(uid) { mutableStateOf<List<WeiboStatusDto>>(emptyList()) }
    var currentPage by remember(uid) { mutableIntStateOf(0) }
    var hasMore by remember(uid) { mutableStateOf(true) }
    var isInitialLoading by remember(uid) { mutableStateOf(!isGuest) }
    var isRefreshing by remember(uid) { mutableStateOf(false) }
    var isLoadingMore by remember(uid) { mutableStateOf(false) }
    var errorMessage by remember(uid) { mutableStateOf<String?>(null) }

    suspend fun load(targetPage: Int, refresh: Boolean) {
        if (isGuest) {
            isInitialLoading = false
            errorMessage = "登录后才能查看我的微博"
            return
        }
        when {
            refresh -> isRefreshing = true
            targetPage <= 1 -> isInitialLoading = true
            else -> isLoadingMore = true
        }
        errorMessage = null

        when (val result = apiService.getUserTimeline(uid, targetPage)) {
            is ApiResult.Success -> {
                val pagePosts = result.data.statuses
                val firstPage = refresh || targetPage <= 1
                val oldSize = posts.size
                val merged = if (firstPage) {
                    pagePosts
                } else {
                    (posts + pagePosts).distinctBy { it.listKey }
                }
                posts = merged
                currentPage = targetPage
                val total = result.data.total
                hasMore = when {
                    pagePosts.isEmpty() -> false
                    total != null -> merged.size < total
                    firstPage -> true
                    else -> merged.size > oldSize
                }
            }
            is ApiResult.Error -> {
                errorMessage = result.message ?: "加载失败"
                if (targetPage > 1) hasMore = false
            }
            ApiResult.Loading -> Unit
        }

        isInitialLoading = false
        isRefreshing = false
        isLoadingMore = false
    }

    LaunchedEffect(uid) {
        load(1, refresh = false)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            !isInitialLoading && !isRefreshing && !isLoadingMore && hasMore && errorMessage == null &&
                layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) scope.launch { load(currentPage + 1, refresh = false) }
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
                onRefresh = { scope.launch { load(1, refresh = true) } },
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
                                    { scope.launch { load(1, refresh = true) } }
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
                                TextButton(onClick = { scope.launch { load(currentPage + 1, refresh = false) } }) {
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
