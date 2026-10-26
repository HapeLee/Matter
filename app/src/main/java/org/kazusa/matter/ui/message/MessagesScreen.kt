package org.kazusa.matter.ui.message

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.shape.CircleShape


import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.data.model.MessageConversation
import org.kazusa.matter.data.model.MessageNotice
import org.kazusa.matter.data.model.MessageTab
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassMorphMenu
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import org.kazusa.matter.ui.media.morph.MORPH_SETTLE_SPEC
import org.kazusa.matter.ui.theme.MatterShapeTokens
import org.koin.compose.viewmodel.koinViewModel

/**
 * 消息页：私信 / @我 / 评论 / 赞 四个分区。
 *
 * 数据全部来自微博移动端消息页的接口（见 `MessageApi`），与时间线共用同一份 m.weibo.cn Cookie。
 * 未登录时接口返回 `ok=-100`，Repository 翻译成 401，页面展示提示与重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBackClick: () -> Unit,
    onConversationClick: (MessageConversation) -> Unit,
    onNoticeClick: (MessageNotice) -> Unit
) {
    val viewModel: MessageCenterViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val backdrop = rememberScreenBackdrop()
    val tabs = MessageTab.entries
    val selectedIndex = tabs.indexOf(uiState.tab)

    // 列表滚动状态提升到页面层，才能驱动顶栏的液态玻璃与自动伸缩。
    // 私信与通知是两套内容，各自持有一个状态，按当前 tab 把对应的那个传给顶栏。
    val conversationListState = rememberLazyListState()
    val noticeListState = rememberLazyListState()
    val activeListState =
        if (uiState.tab == MessageTab.DIRECT) conversationListState else noticeListState

    // 分类菜单只服务于这一页：返回键先收起菜单，切换分区后收起
    var menuExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = menuExpanded) { menuExpanded = false }
    val menuProgress = animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        animationSpec = MORPH_SETTLE_SPEC,
        label = "MessageCategoryMenuMorph"
    )

    // NavDisplay 只组合栈顶条目：从会话页返回时本页会重新进入组合，这个 effect 随之重跑。
    // 首次进入拉首屏，再次出现则静默同步首页，让服务端已清的未读在列表上生效。
    LaunchedEffect(uiState.tab, viewModel) { viewModel.onContentVisible(uiState.tab) }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = activeListState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "消息",
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
            // 列表直接铺满整个内容区，把顶部 inset 作为 contentPadding，
            // 让内容能滚到透明顶栏下方 —— 这样顶栏的液态玻璃才有可折射的底图，
            // 同时避免顶栏背后露出 Scaffold 的 background（深色主题下就是一片黑）。
            when (uiState.tab) {
                MessageTab.DIRECT -> ConversationList(
                    listState = conversationListState,
                    section = uiState.conversations,
                    backdrop = backdrop,
                    onRefresh = { viewModel.refresh(MessageTab.DIRECT) },
                    onLoadMore = { viewModel.loadMore(MessageTab.DIRECT) },
                    onRetry = { viewModel.retry(MessageTab.DIRECT) },
                    onConversationClick = { conversation ->
                        // 立即清掉本地未读，返回列表时再用服务端数据校正
                        viewModel.markConversationRead(conversation.peerUid)
                        onConversationClick(conversation)
                    },
                    topPadding = padding.calculateTopPadding(),
                    bottomPadding = padding.calculateBottomPadding()
                )

                else -> NoticeList(
                    listState = noticeListState,
                    tab = uiState.tab,
                    section = uiState.noticeSection(uiState.tab),
                    backdrop = backdrop,
                    onRefresh = { viewModel.refresh(uiState.tab) },
                    onLoadMore = { viewModel.loadMore(uiState.tab) },
                    onRetry = { viewModel.retry(uiState.tab) },
                    onNoticeClick = onNoticeClick,
                    topPadding = padding.calculateTopPadding(),
                    bottomPadding = padding.calculateBottomPadding()
                )
            }

            // 空白点击收起菜单（在列表之上、按钮之下）
            if (menuExpanded || menuProgress.value > 0.001f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { menuExpanded = false }
                        )
                )
            }

            // 底部右侧悬浮按钮：点开是私信 / @我 / 评论 / 赞 的分类面板
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 12.dp)
                    .zIndex(2f)
            ) {
                LiquidGlassMorphMenu(
                    items = tabs.map { it.title },
                    selectedIndex = selectedIndex,
                    progress = menuProgress,
                    expanded = menuExpanded,
                    onToggle = { menuExpanded = !menuExpanded },
                    onItemClick = { index ->
                        menuExpanded = false
                        viewModel.selectTab(tabs[index])
                    },
                    backdrop = backdrop,
                    icon = {
                        LiquidGlassIcon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "切换消息分类"
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationList(
    listState: LazyListState,
    section: ConversationSection,
    backdrop: LayerBackdrop?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onConversationClick: (MessageConversation) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp
) {
    val pullState = rememberPullToRefreshState()
    InfiniteScrollEffect(listState, section.canLoadMore, onLoadMore)

    PullToRefreshBox(
        isRefreshing = section.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = section.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
            )
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            contentPadding = PaddingValues(
                start = MESSAGE_LIST_INSET,
                top = topPadding + 8.dp,
                end = MESSAGE_LIST_INSET,
                bottom = bottomPadding + FLOATING_MENU_CLEARANCE
            ),
            verticalArrangement = Arrangement.spacedBy(MESSAGE_CARD_GAP)
        ) {
            if (section.items.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage(
                        message = section.errorMessage
                            ?: if (section.isInitialLoading) "正在加载私信…" else "还没有私信往来",
                        isLoading = section.isInitialLoading,
                        buttonText = if (section.errorMessage != null) "重试" else null,
                        onButtonClick = if (section.errorMessage != null) onRetry else null,
                        modifier = Modifier.fillParentMaxHeight(0.6f)
                    )
                }
            }

            items(
                items = section.items,
                key = { it.key },
                contentType = { "conversation" }
            ) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation) }
                )
            }

            loadMoreFooter(
                isLoadingMore = section.isLoadingMore,
                hasItems = section.items.isNotEmpty(),
                hasMore = section.hasMore,
                errorMessage = section.errorMessage,
                onRetry = onRetry
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticeList(
    listState: LazyListState,
    tab: MessageTab,
    section: NoticeSection,
    backdrop: LayerBackdrop?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onNoticeClick: (MessageNotice) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp
) {
    val pullState = rememberPullToRefreshState()
    InfiniteScrollEffect(listState, section.canLoadMore, onLoadMore)

    PullToRefreshBox(
        isRefreshing = section.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = section.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
            )
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            contentPadding = PaddingValues(
                start = MESSAGE_LIST_INSET,
                top = topPadding + 8.dp,
                end = MESSAGE_LIST_INSET,
                bottom = bottomPadding + FLOATING_MENU_CLEARANCE
            ),
            verticalArrangement = Arrangement.spacedBy(MESSAGE_CARD_GAP)
        ) {
            if (section.items.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage(
                        message = section.errorMessage
                            ?: if (section.isInitialLoading) "正在加载${tab.title}…" else "还没有${tab.title}消息",
                        isLoading = section.isInitialLoading,
                        buttonText = if (section.errorMessage != null) "重试" else null,
                        onButtonClick = if (section.errorMessage != null) onRetry else null,
                        modifier = Modifier.fillParentMaxHeight(0.6f)
                    )
                }
            }

            items(
                items = section.items,
                key = { it.key },
                contentType = { "notice" }
            ) { notice ->
                NoticeCard(notice = notice, onClick = { onNoticeClick(notice) })
            }

            loadMoreFooter(
                isLoadingMore = section.isLoadingMore,
                hasItems = section.items.isNotEmpty(),
                hasMore = section.hasMore,
                errorMessage = section.errorMessage,
                onRetry = onRetry
            )
        }
    }
}

/** 两个列表共用的底部状态行：加载中 / 加载失败重试 / 已显示全部。 */
private fun LazyListScope.loadMoreFooter(
    isLoadingMore: Boolean,
    hasItems: Boolean,
    hasMore: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit
) {
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

    if (hasItems && errorMessage != null) {
        item(key = "load_error") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }

    if (hasItems && !hasMore && !isLoadingMore && errorMessage == null) {
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

/** 滚动到接近末尾时请求下一页。 */
@Composable
private fun InfiniteScrollEffect(
    listState: LazyListState,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, canLoadMore) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount to (layout.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collect { (total, lastVisible) ->
            if (canLoadMore && total > 0 && lastVisible >= total - 3) onLoadMore()
        }
    }
}

/**
 * 单条私信会话卡片。
 *
 * 用 [MatterCard] 承载整行：列表背景留在主题 surface 上，卡片自身带 16dp 圆角与
 * `surfaceContainer` 底色，行与行之间只靠间距分隔，不再画分隔线。
 */
@Composable
private fun ConversationCard(
    conversation: MessageConversation,
    onClick: () -> Unit
) {
    MatterCard(
        onClick = onClick,
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MessageAvatar(url = conversation.peerAvatarUrl, name = conversation.peerName)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    conversation.timeText?.let { time ->
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.preview.ifBlank { "点击查看会话" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadBadge(count = conversation.unreadCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    notice: MessageNotice,
    onClick: () -> Unit
) {
    MatterCard(
        onClick = onClick,
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            MessageAvatar(url = notice.peerAvatarUrl, name = notice.peerName)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notice.peerName.ifBlank { "微博用户" },
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    notice.timeText?.let { time ->
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (notice.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = notice.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                notice.sourceText?.let { source ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = source,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAvatar(url: String, name: String) {
    val context = LocalContext.current
    val model = remember(url) {
        ImageRequest.Builder(context)
            .data(url.ifBlank { DEFAULT_AVATAR })
            .crossfade(true)
            .build()
    }
    val ringColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    AsyncImage(
        model = model,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (url.isNotBlank()) {
                    Modifier.drawBehind {
                        drawCircle(
                            color = ringColor,
                            radius = size.minDimension / 2 - 0.5.dp.toPx(),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                } else {
                    Modifier
                }
            )
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.error
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError
        )
    }
}

/** 列表底部要空出悬浮按钮（56dp）+ 外边距，最后一项才不会被挡住。 */
private val FLOATING_MENU_CLEARANCE = 88.dp

/** 卡片列表的左右留白，与「浏览记录」「收藏」等卡片列表保持同一套边距。 */
private val MESSAGE_LIST_INSET = 16.dp

/** 卡片之间的垂直间距；卡片自己负责圆角与底色，不再画分隔线。 */
private val MESSAGE_CARD_GAP = 10.dp

private const val DEFAULT_AVATAR =
    "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
