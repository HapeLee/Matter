package org.kazusa.matter.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.SearchUserCard
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.liquid.LiquidGlassFloatingMenu
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.rememberScreenBackdrop

/** 我的关注 / 我的粉丝列表类型。 */
enum class UserListKind(val title: String) {
    FOLLOWING("我的关注"),
    FOLLOWERS("我的粉丝")
}

/**
 * 「我的关注 / 我的粉丝」用户列表页。
 *
 * 「我的关注」按微博客户端的分组组织：关注的人 / 最新发博 / 经常访问，
 * 容器 id 见 [FollowingListTab]；「我的粉丝」为单列表。
 *
 * 数据与滚动位置由 [UserListViewModel] 持有，从博主主页返回时不会重建列表，
 * 保证共享元素在返回起点就有稳定的目标节点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    kind: UserListKind,
    viewModel: UserListViewModel,
    onBackClick: () -> Unit,
    onUserClick: (Long?) -> Unit
) {
    val sources = viewModel.sources
    val selectedSource = sources[viewModel.selectedIndex]
    val state = viewModel.states.getValue(selectedSource.key)
    val listState = viewModel.listStates.getValue(selectedSource.key)
    val backdrop = rememberScreenBackdrop()
    val pullState = rememberPullToRefreshState()
    val isMultiTab = sources.size > 1

    // 触底自动加载下一页（各分组独立，状态在 ViewModel 中不会随返回丢失）
    LaunchedEffect(selectedSource.key) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val current = viewModel.states.getValue(selectedSource.key)
            current.loaded && !current.isInitialLoading && !current.isRefreshing && !current.isLoadingMore &&
                current.hasMore && current.error == null &&
                layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = kind.title,
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
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.isRefreshing,
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
                        // 右下角悬浮分组按钮避让
                        bottom = padding.calculateBottomPadding() + if (isMultiTab) 96.dp else 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.items.isEmpty()) {
                        item(key = "empty") {
                            EmptyMessage(
                                message = state.error
                                    ?: if (state.isInitialLoading) "正在加载${selectedSource.title}…" else selectedSource.emptyText,
                                isLoading = state.isInitialLoading,
                                buttonText = if (state.error != null && !viewModel.isGuest) "重试" else null,
                                onButtonClick = if (state.error != null && !viewModel.isGuest) {
                                    viewModel::retry
                                } else {
                                    null
                                },
                                modifier = Modifier.fillParentMaxHeight(0.65f)
                            )
                        }
                    }

                    items(
                        items = state.items,
                        key = { it.userListKey() },
                        contentType = { "user" }
                    ) { card ->
                        val userId = card.user?.id ?: card.user?.idStr?.toLongOrNull()
                        val userKey = card.userKey()
                        // 「关注的人 / 最新发博」天然都是已关注用户，不依赖服务端按钮文案
                        val forcedFollowing = when (selectedSource.followingTab) {
                            FollowingListTab.ALL,
                            FollowingListTab.LATEST_POST -> true
                            else -> null
                        }
                        val effectiveFollowing = userKey?.let { viewModel.followStates[it] }
                            ?: forcedFollowing
                            ?: card.user?.following
                        SearchUserCard(
                            card = card,
                            onClick = { onUserClick(userId) },
                            onUserClick = onUserClick,
                            followingOverride = effectiveFollowing,
                            followUpdating = userKey != null && viewModel.followUpdatingUid == userKey,
                            onFollowClick = if (userKey == null) {
                                null
                            } else {
                                { viewModel.onFollowClick(card, currentlyFollowing = effectiveFollowing == true) }
                            }
                        )
                    }

                    if (state.isLoadingMore) {
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

                    if (state.items.isNotEmpty() && state.error != null) {
                        item(key = "load_error") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = viewModel::retry) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    if (state.items.isNotEmpty() && !state.hasMore && !state.isLoadingMore && state.error == null) {
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

            // 右下角悬浮分组切换（与博主主页 / 搜索页一致）
            if (isMultiTab) {
                LiquidGlassFloatingMenu(
                    items = sources.map { it.title },
                    selectedIndex = viewModel.selectedIndex,
                    onSelect = viewModel::selectSource,
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 16.dp),
                    icon = {
                        LiquidGlassIcon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "切换关注分组"
                        )
                    }
                )
            }

            viewModel.pendingUnfollow?.let { card ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissUnfollowDialog,
                    title = { Text("取消关注") },
                    text = {
                        val name = card.user?.screenName?.takeIf { it.isNotBlank() }
                            ?: card.userKey().orEmpty()
                        Text("确定不再关注 @$name 吗？")
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmUnfollow) {
                            Text("取消关注", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissUnfollowDialog) {
                            Text("再想想")
                        }
                    }
                )
            }

            viewModel.followActionError?.let { message ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissActionError,
                    title = { Text("操作失败") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissActionError) {
                            Text("知道了")
                        }
                    }
                )
            }
        }
    }
}
