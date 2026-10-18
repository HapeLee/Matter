package org.kazusa.matter.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import org.kazusa.matter.ui.components.LocalSnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.WeiboPostCard
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.theme.TextSecondary
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.group.FollowGroupBottomSheet
import org.kazusa.matter.ui.group.GroupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    groupViewModel: GroupViewModel,
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit = {},
    onCommentClick: (WeiboStatusDto) -> Unit = onPostClick,
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String) -> Unit = { _, _ -> },
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    onNavigateToGroups: () -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    backdrop: LayerBackdrop? = null,
    bottomBarPadding: Dp = 0.dp
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val morphState = LocalImageMorphState.current

    // 取一次列表快照：断点下标、分栏 key 与下面的 subList 必须来自同一份列表，
    // 否则下标可能落在上一份列表上，subList 会直接抛 IndexOutOfBoundsException（切分组时最容易触发）。
    val posts = uiState.posts

    // 阅读断点在列表中的位置：O(n) 扫描，缓存以避免每次重组重复计算
    val lastReadIndex = remember(posts, uiState.lastReadPostId) {
        uiState.lastReadPostId?.let { id ->
            posts.indexOfFirst { (it.idStr ?: it.id?.toString()) == id }
        } ?: -1
    }.coerceIn(-1, posts.size)
    var showGroupSheet by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 即时保存当前时间线阅读位置（包含精确的微博 ID、行索引与像素偏移）
    val saveCurrentPosition: (Boolean) -> Unit = remember(listState, uiState.currentGid, uiState.posts, uiState.lastReadPostId) {
        { synchronous ->
            if (uiState.posts.isNotEmpty() && uiState.pendingRestoreIndex == null &&
                listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                val firstIndex = listState.firstVisibleItemIndex
                val firstOffset = listState.firstVisibleItemScrollOffset

                val firstKey = firstVisible?.key
                val postId: String? = when {
                    firstKey != null && firstKey != "last_read_divider" -> firstKey.toString()
                    else -> {
                        val lastReadIdx = uiState.lastReadPostId?.let { id ->
                            uiState.posts.indexOfFirst { (it.idStr ?: it.id?.toString()) == id }
                        } ?: -1
                        val postIdx = if (lastReadIdx > 0 && firstIndex >= lastReadIdx) firstIndex - 1 else firstIndex
                        uiState.posts.getOrNull(postIdx)?.let { it.idStr ?: it.id?.toString() }
                    }
                }

                viewModel.onScrollPositionChanged(
                    scrollIndex = firstIndex,
                    scrollOffset = firstOffset,
                    postId = postId,
                    synchronous = synchronous
                )
            }
        }
    }

    // 点击卡片进入正文前立即同步持久化当前滚动位置，确保从正文返回时精准定位
    val handlePostClick: (WeiboStatusDto) -> Unit = remember(onPostClick, saveCurrentPosition) {
        { post ->
            saveCurrentPosition(true)
            onPostClick(post)
        }
    }

    // 点击评论按钮进入正文评论区
    val handleCommentClick: (WeiboStatusDto) -> Unit = remember(onCommentClick, saveCurrentPosition) {
        { post ->
            saveCurrentPosition(true)
            onCommentClick(post)
        }
    }

    // 初始加载已在 TimelineViewModel.init 读取记住的分组后自动启动，避免竞态冲突

    // 监听滑动无缝预加载（当滑到距离末尾还有 5 项时提前静默发起下一页加载）
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItemsCount > 0 && lastVisibleItemIndex >= totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore, uiState.pendingRestoreIndex) {
        if (shouldLoadMore && uiState.pendingRestoreIndex == null && uiState.hasMore && !uiState.isLoadingMore && !uiState.isRefreshing) {
            viewModel.loadTimeline(isRefresh = false)
        }
    }

    // 首次载入或切换分组时恢复持久化保存的上次浏览记忆位置（单次消费，决不重复执行）
    val restoreIndex = uiState.pendingRestoreIndex
    if (restoreIndex != null && posts.isNotEmpty()) {
        val hasDivider = uiState.lastReadPostId?.let { id ->
            val idx = posts.indexOfFirst { (it.idStr ?: it.id?.toString()) == id }
            idx > 0
        } ?: false
        val targetIndex = restoreIndex.coerceIn(0, posts.lastIndex + if (hasDivider) 1 else 0)
        SideEffect {
            // Apply the viewport in the next measure, before the list draws its first frame.
            listState.requestScrollToItem(targetIndex, uiState.pendingRestoreOffset)
        }
        LaunchedEffect(uiState.currentGid, restoreIndex, uiState.pendingRestoreOffset) {
            withFrameNanos { }
            viewModel.onPendingRestoreConsumed()
        }
    }

    // 刷新获取新微博时，平滑回顶动画去到列表最顶部（最新一条微博），往下浏览时滑到分割线即可知道新微博已看完
    var lastHandledScrollToTopTrigger by rememberSaveable { mutableLongStateOf(uiState.scrollToTopTrigger) }
    LaunchedEffect(uiState.scrollToTopTrigger) {
        val trigger = uiState.scrollToTopTrigger
        if (trigger > 0L && trigger != lastHandledScrollToTopTrigger) {
            lastHandledScrollToTopTrigger = trigger
            if (listState.firstVisibleItemIndex > 8) {
                listState.scrollToItem(6)
            }
            try {
                listState.animateScrollToItem(0, 0)
            } finally {
                viewModel.consumeScrollToTop()
            }
        }
    }

    // 监听刷新提示并通过 Snackbar 弹出展示更新数量
    LaunchedEffect(uiState.refreshTip) {
        val tip = uiState.refreshTip
        if (!tip.isNullOrBlank()) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = tip,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissRefreshTip()
        }
    }

    // 滑动停止时即时记录当前位置，无需等待漫长的防抖延迟
    val latestSaveCurrentPosition by rememberUpdatedState(saveCurrentPosition)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                if (!isScrolling) {
                    latestSaveCurrentPosition(false)
                }
            }
    }

    // 页面退到后台或退出时立即以 commit() 同步持久化写入 SharedPreferences（确保在进程被系统回收前物理落盘）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                saveCurrentPosition(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveCurrentPosition(true)
        }
    }

    // 关注分组弹层 Sheet
    if (showGroupSheet) {
        FollowGroupBottomSheet(
            currentGid = uiState.currentGid,
            viewModel = groupViewModel,
            onSelectGroup = { gid, groupName ->
                viewModel.setGroup(gid, groupName)
            },
            onDismissRequest = { showGroupSheet = false },
            onNavigateToManagement = onNavigateToGroups
        )
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(bottom = bottomBarPadding + 8.dp)
            ) { data ->
                val isNewContent = !data.visuals.message.contains("暂")
                Snackbar(
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isNewContent) Icons.Default.Check else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isNewContent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMediumEmphasized
                        )
                    }
                }
            }
        },
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = uiState.currentGroupName,
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = { showGroupSheet = true },
                        trailingIcon = {
                            LiquidGlassIcon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "切换分组",
                                tint = LocalContentColor.current.copy(alpha = 0.85f)
                            )
                        }
                    )
                },
                // 搜索入口（原在「发现」页顶栏，现收到动态页 actions 槽）：点击进入搜索展示页
                actions = { scroll ->
                    TopBarActionButton(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = onSearchClick
                    )
                },
                // 各功能入口已归入「我的」；点击顶栏快速回到顶部
                barModifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        coroutineScope.launch {
                            if (listState.firstVisibleItemIndex > 12) {
                                listState.scrollToItem(10)
                            }
                            listState.animateScrollToItem(0, 0)
                        }
                    }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.loadTimeline(isRefresh = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.posts.isEmpty() && (uiState.isLoading || uiState.isRefreshing) -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                uiState.posts.isEmpty() && uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "加载微博时间线失败",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SelectionContainer {
                            Text(
                                text = uiState.errorMessage ?: "加载失败",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val clipboardManager = LocalClipboardManager.current

                            OutlinedButton(
                                onClick = {
                                    uiState.errorMessage?.let { msg ->
                                        clipboardManager.setText(AnnotatedString(msg))
                                        coroutineScope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar(
                                                message = "错误信息已复制到剪贴板",
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("复制报错")
                            }

                            Button(
                                onClick = { viewModel.loadTimeline(isRefresh = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("重新加载")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.logout(onLogout) }) {
                            Text("重新登录账号", color = TextSecondary)
                        }
                    }
                }
                uiState.posts.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无微博动态",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = (morphState?.isPresent != true && uiState.pendingRestoreIndex == null),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = topPadding + 10.dp,
                            end = 12.dp,
                            bottom = 12.dp + bottomBarPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (lastReadIndex > 0) {
                            items(
                                items = posts.subList(0, lastReadIndex),
                                key = { it.listKey },
                                contentType = { "post" }
                            ) { post ->
                                WeiboPostCard(
                                    post = post,
                                    onUserClick = onUserClick,
                                    onPostClick = handlePostClick,
                                    onCommentClick = handleCommentClick,
                                    onImageClick = onImageClick,
                                    onVideoClick = onVideoClick,
                                    onMentionClick = onMentionClick,
                                    onTopicClick = onTopicClick
                                )
                            }

                            item(key = "last_read_divider", contentType = "divider") {
                                LastReadDividerPrompt(
                                    onClickRefresh = {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(0, 0)
                                        }
                                        viewModel.loadTimeline(isRefresh = true)
                                    },
                                    isRefreshing = uiState.isRefreshing
                                )
                            }

                            items(
                                items = posts.subList(lastReadIndex, posts.size),
                                key = { it.listKey },
                                contentType = { "post" }
                            ) { post ->
                                WeiboPostCard(
                                    post = post,
                                    onUserClick = onUserClick,
                                    onPostClick = handlePostClick,
                                    onCommentClick = handleCommentClick,
                                    onImageClick = onImageClick,
                                    onVideoClick = onVideoClick,
                                    onMentionClick = onMentionClick,
                                    onTopicClick = onTopicClick
                                )
                            }
                        } else {
                            items(
                                items = posts,
                                key = { it.listKey },
                                contentType = { "post" }
                            ) { post ->
                                WeiboPostCard(
                                    post = post,
                                    onUserClick = onUserClick,
                                    onPostClick = handlePostClick,
                                    onCommentClick = handleCommentClick,
                                    onImageClick = onImageClick,
                                    onVideoClick = onVideoClick,
                                    onMentionClick = onMentionClick,
                                    onTopicClick = onTopicClick
                                )
                            }
                        }

                        if (uiState.isLoadingMore) {
                            item(contentType = "loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.5.dp
                                    )
                                }
                            }
                        } else if (!uiState.hasMore) {
                            item(contentType = "footer") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "已经到底啦",
                                        style = MaterialTheme.typography.labelMedium,
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
}
}
}

/**
 * 上次阅读分割提示组件：
 * - 居中胶囊药丸按钮 + 两侧柔和分割线
 * - 点击触发手动刷新最新微博
 * - 刷新中显示微型转圈进度指示
 */
@Composable
private fun LastReadDividerPrompt(
    onClickRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 1.dp,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clip(CircleShape)
                .clickable(enabled = !isRefreshing, onClick = onClickRefresh)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "正在更新最新微博...",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "上次阅读到这里，点击刷新",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}
