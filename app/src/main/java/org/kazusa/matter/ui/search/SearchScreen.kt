package org.kazusa.matter.ui.search

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.util.Locale
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.core.util.TimeUtils
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchEntry
import org.kazusa.matter.data.model.HotSearchItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterTag
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule

/**
 * 「微博热搜」视图（主框架「热门」Tab 下的展示模式之一，由左侧悬浮按钮在热搜 / 热门之间切换）。
 *
 * - 顶栏标题是当前榜单名（服务端下发，如「热搜」「文娱」「同城」）；
 * - 榜单切换交给悬浮底栏右侧的分类按钮，页面内不再放切换条；
 * - 内容为热搜榜卡片列表，点条目 / 入口卡进入独立的搜索结果页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotSearchScreen(
    hotSearchViewModel: HotSearchBoardViewModel,
    onSearchSubmit: (String) -> Unit,
    backdrop: LayerBackdrop? = null,
    bottomBarPadding: Dp = 0.dp,
    /** 是否为主框架当前展示的内容；交叉淡化期间离场的那份不应再上报滚动状态 */
    isActive: Boolean = true,
    /** 上报当前榜单列表状态，主框架用它复刻动态页的「到顶立即显示底栏」判断 */
    onActiveListStateChanged: (LazyListState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pullRefreshState = rememberPullToRefreshState()

    // 顶栏胶囊需要「当前列表」的滚动状态才能淡入标题底；过渡期间存在两份列表，
    // 因此这里只镜像当前那一份（切换后由新列表接上）
    val topBarListState = remember { mutableStateOf(LazyListState()) }

    val hotSearchUi by hotSearchViewModel.uiState.collectAsStateWithLifecycle()

    // 顶栏标题直接显示当前榜单名（如「热搜」「文娱」「同城」），切榜 / 刷新后跟着变
    val topBarTitle = hotSearchUi.selectedChannel?.name
        ?.takeIf { it.isNotBlank() }
        ?: hotSearchUi.pageTitle?.takeIf { it.isNotBlank() }
        ?: "微博热搜"

    // 初始加载热搜（下拉刷新走 PullToRefreshBox → hotSearchViewModel.refresh()）
    LaunchedEffect(Unit) {
        hotSearchViewModel.loadIfNeeded()
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = topBarListState.value,
                animationLabel = "HotSearchTopBarScrim",
                title = { scroll ->
                    TopBarTitleText(
                        text = topBarTitle,
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
            isRefreshing = hotSearchUi.isRefreshing,
            state = pullRefreshState,
            onRefresh = { hotSearchViewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // 换榜：新旧两份内容交叉淡化，各自持有 LazyListState，过渡期间互不干扰。
            // contentKey 只认 containerid —— 同榜刷新（下拉刷新 / 自动刷新）不重放过渡，
            // 列表状态也按 containerid remember：数据原地更新、滚动位置保留，
            // 不会出现「旧列表停在中段 + 新列表在顶部」两份不同位置叠着淡出淡入。
            AnimatedContent(
                targetState = hotSearchUi.content,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(140))
                },
                contentKey = { it.containerId },
                label = "HotSearchContent"
            ) { content ->
                val board = content.board
                val boardListState = remember(content.containerId) { LazyListState() }
                // 只有「当前」那份内容接管顶栏的滚动采样；离场的那份在淡出期间不再写入
                val isCurrent = content === hotSearchUi.content
                SideEffect {
                    if (isActive && isCurrent) {
                        if (topBarListState.value !== boardListState) {
                            topBarListState.value = boardListState
                        }
                        onActiveListStateChanged(boardListState)
                    }
                }

                LazyColumn(
                    state = boardListState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 只让当前那份参与顶栏模糊采样，避免过渡期间两份列表同时写入同一张 backdrop
                        .then(
                            if (isCurrent && backdrop != null) {
                                Modifier.layerBackdrop(backdrop)
                            } else {
                                Modifier
                            }
                        )
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = topPadding + 8.dp,
                        end = 12.dp,
                        bottom = bottomBarPadding + 20.dp
                    )
                ) {
                    hotSearchItems(
                        board = board,
                        updatedAt = hotSearchUi.lastLoadedAt,
                        isLoading = hotSearchUi.isLoading,
                        onItemClick = { hotItem -> onSearchSubmit(hotItem.title) },
                        onEntryClick = { entry ->
                            // 入口卡（好友热搜 / 榜规则说明）目前没有对应落地页，先按关键词搜索兜底
                            onSearchSubmit(entry.title)
                        }
                    )
                }
            }
        }
    }
}

/** 榜单卡统一圆角：整个榜段 / 入口卡组各拼成一张大卡，条目之间用分隔线衔接。 */
private val HotSearchCardRadius = 18.dp
private val HotSearchCardShape = RoundedCornerShape(18.dp)

/**
 * 微博实时热搜榜。
 *
 * 榜单结构**完全按服务端下发的卡片渲染**：
 * 每个榜段一个段标题（实时热点 / 实时上升热点 / 文娱热搜），段内条目分四种：
 * 置顶（无真实名次、角标图由服务端下发）、排名（名次取服务端 `realpos`）、
 * 商业推广位（不占名次）、「查看更多…」跳转条目；末尾另有「好友热搜」这类入口卡。
 *
 * 段标题渲染在**本榜段卡片的最上方**（[HotSearchSectionHeader]），
 * 与段内条目同属一张圆角卡，下面用一条分隔线与首条隔开。
 *
 * 当前榜单名由顶栏标题承担（[SearchScreen] 的 [TopBarTitleText]），列表内不再重复。
 *
 * 注意：这里刻意不为悬浮底栏预留底部间距（不避让悬浮栏），
 * 列表可以一直延伸到屏幕底部，被半透明悬浮栏覆盖。
 */
private fun LazyListScope.hotSearchItems(
    board: HotSearchBoard,
    /** 上次成功加载的时间戳（[HotSearchBoardViewModel.lastLoadedAt]），0 表示还没有 */
    updatedAt: Long,
    isLoading: Boolean,
    onItemClick: (HotSearchItem) -> Unit,
    onEntryClick: (HotSearchEntry) -> Unit
) {
    if (isLoading && board.isEmpty) {
        item(key = "hot_search_loading", contentType = "loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }

    // 服务端没给任何条目（同城榜在所在城市没有热点时就是这样）：整页一个空占位，
    // 段标题 / 通栏图 / 城市切换入口卡都不渲染
    if (board.isEmpty) {
        item(key = "hot_search_empty", contentType = "empty") {
            HotSearchEmptyHint()
        }
        return
    }

    // 更新时间挂在最上面那个有标题的榜段标题行右侧；一个段标题都没有时才退化成独立一行
    val firstTitledSectionIndex = board.sections.indexOfFirst { !it.title.isNullOrBlank() }
    val canInlineUpdatedAt = updatedAt > 0L && firstTitledSectionIndex >= 0
    if (updatedAt > 0L && !canInlineUpdatedAt) {
        item(key = "hot_updated_at", contentType = "updated_at") {
            HotSearchUpdatedAt(updatedAt = updatedAt)
        }
    }

    board.sections.forEachIndexed { sectionIndex, section ->
        val sectionTitle = section.title?.takeIf { it.isNotBlank() }
        // 只有最上面那个段标题带更新时间，避免每个榜段都重复
        val sectionUpdatedAt = if (canInlineUpdatedAt && sectionIndex == firstTitledSectionIndex) updatedAt else 0L
        if (sectionIndex > 0) {
            item(key = "hot_section_spacer_$sectionIndex", contentType = "spacer") {
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // 段内一条都没有（极端服务端数据）：段标题单独成卡，避免标题孤零零露在卡片外
        if (section.items.isEmpty()) {
            if (sectionTitle != null) {
                item(key = "hot_section_header_$sectionIndex", contentType = "section_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, HotSearchCardShape)
                            .clip(HotSearchCardShape)
                    ) {
                        HotSearchSectionHeader(
                            title = sectionTitle,
                            icon = section.icon,
                            updatedAt = sectionUpdatedAt
                        )
                    }
                }
            }
            return@forEachIndexed
        }

        itemsIndexed(
            items = section.items,
            // 名次可能与热搜词重复（同一个词出现在多个榜段），key 用段+下标保证唯一
            key = { index, _ -> "hot_${sectionIndex}_$index" },
            contentType = { _, _ -> "hot" }
        ) { index, item ->
            val isFirst = index == 0
            val isLast = index == section.items.lastIndex
            val shape = when {
                isFirst && isLast -> HotSearchCardShape
                isFirst -> RoundedCornerShape(topStart = HotSearchCardRadius, topEnd = HotSearchCardRadius, bottomEnd = 0.dp, bottomStart = 0.dp)
                isLast -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = HotSearchCardRadius, bottomStart = HotSearchCardRadius)
                else -> RectangleShape
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .clip(shape)
            ) {
                // 段标题放进卡片最上方：整个榜段就是一整张圆角卡
                if (isFirst && sectionTitle != null) {
                    HotSearchSectionHeader(
                        title = sectionTitle,
                        icon = section.icon,
                        updatedAt = sectionUpdatedAt
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )
                }
                HotSearchRow(item = item, onClick = { onItemClick(item) })
                if (!isLast) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }

    if (board.entries.isNotEmpty()) {
        item(key = "hot_entries_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(14.dp))
        }

        itemsIndexed(
            items = board.entries,
            key = { index, _ -> "hot_entry_$index" },
            contentType = { _, _ -> "entry" }
        ) { index, entry ->
            val isFirst = index == 0
            val isLast = index == board.entries.lastIndex
            val shape = when {
                isFirst && isLast -> HotSearchCardShape
                isFirst -> RoundedCornerShape(topStart = HotSearchCardRadius, topEnd = HotSearchCardRadius, bottomEnd = 0.dp, bottomStart = 0.dp)
                isLast -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = HotSearchCardRadius, bottomStart = HotSearchCardRadius)
                else -> RectangleShape
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .clip(shape)
            ) {
                HotSearchEntryRow(entry = entry, onClick = { onEntryClick(entry) })
                if (!isLast) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 44.dp, end = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

/**
 * 榜单更新时间：列表最上方一行的弱化小字（如「更新于 15:42」）。
 *
 * 只负责展示 [HotSearchBoardViewModel.lastLoadedAt]，不触发任何自动刷新 ——
 * 刷新仍然只能靠下拉刷新 / 切榜。
 */
@Composable
private fun HotSearchUpdatedAt(updatedAt: Long) {
    val clock = remember(updatedAt) { TimeUtils.formatClockTime(updatedAt) }
    if (clock.isBlank()) return

    Text(
        text = "更新于 $clock",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 6.dp)
    )
}

/**
 * 空榜单占位：服务端没有任何条目时显示（例：同城榜在所在城市没有热点）。
 *
 * 只放一个弱化的图标 + 一句提示，不放图、不放入口卡。
 */
@Composable
private fun HotSearchEmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "暂无内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 榜段标题（服务端下发，如「实时热点，每分钟更新一次」「实时上升热点」「文娱热搜」）。
 *
 * 渲染在榜段圆角卡的最上方，因此自带卡片内边距；`updatedAt > 0` 时把「更新于 HH:mm」
 * 放在同一行右侧（由调用方只给最上面那个榜段传，避免每段都重复）。
 */
@Composable
private fun HotSearchSectionHeader(title: String, icon: String?, updatedAt: Long = 0L) {
    val clock = remember(updatedAt) {
        if (updatedAt > 0L) TimeUtils.formatClockTime(updatedAt) else ""
    }
    val cleanTitle = title.trim()
    val parts = cleanTitle.split("，", ",", " ")
    val mainTitle = parts.firstOrNull()?.trim() ?: cleanTitle
    val subTitle = if (parts.size > 1) {
        cleanTitle.removePrefix(mainTitle).trimStart('，', ',', ' ').trim()
    } else null

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)
    ) {
        Text(
            text = mainTitle,
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!subTitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (clock.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "更新于 $clock",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

/** 入口卡（服务端 `card_type=101`：好友热搜 / 热搜榜规则说明）。 */
@Composable
private fun HotSearchEntryRow(
    entry: HotSearchEntry,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        if (!entry.icon.isNullOrBlank()) {
            AsyncImage(
                model = entry.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
            entry.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 热搜榜一行。
 *
 * 名次用**服务端 `realpos`**（置顶与推广位没有名次，改为渲染服务端角标图或留空），
 * 因此置顶项不会把后面的名次顶掉；`heat` / `icon` / `badge` 全部原样用服务端下发的值。
 */
@Composable
private fun HotSearchRow(
    item: HotSearchItem,
    onClick: () -> Unit
) {
    if (item.isMore) {
        HotSearchMoreRow(text = item.title, onClick = onClick)
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rank = item.rank
        if (rank != null) {
            val (rankColor, rankStyle) = when (rank) {
                1 -> Color(0xFFFF3838) to MaterialTheme.typography.titleMediumEmphasized
                2 -> Color(0xFFFF7A00) to MaterialTheme.typography.titleMediumEmphasized
                3 -> Color(0xFFFFAC1C) to MaterialTheme.typography.titleMediumEmphasized
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) to MaterialTheme.typography.bodyMediumEmphasized
            }
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = rank.toString(),
                    color = rankColor,
                    style = rankStyle
                )
            }
        } else if (item.isPinned) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!item.badge.isNullOrBlank()) {
                    AsyncImage(
                        model = item.badge,
                        contentDescription = "置顶",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(15.dp)
                    )
                } else {
                    MatterTag(
                        text = "置顶",
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        } else if (item.isPromoted) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                MatterTag(
                    text = "推广",
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
            }
        } else {
            // 留出等宽占位，保证标题垂直对齐
            Box(
                modifier = Modifier.width(38.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!item.badge.isNullOrBlank()) {
                    AsyncImage(
                        model = item.badge,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(15.dp)
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = if (rank in 1..3 || item.isPinned) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 右侧元数据：分类标签、热/新/爆角标、热度值
        val (categoryTag, formattedHeat) = remember(item.heat) { formatHeat(item.heat) }

        if (!categoryTag.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            MatterTag(
                text = categoryTag,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        if (!item.icon.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            AsyncImage(
                model = item.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(15.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }

        if (formattedHeat.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formattedHeat,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

/** 「查看更多…」条目（服务端下发的文案 + 右箭头），例：查看更多实时上升热点。 */
@Composable
private fun HotSearchMoreRow(
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmallEmphasized,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 格式化热搜热度值。
 *
 * 服务端下发形式多样：
 * - 纯数字（如 "319814"）：转为 "32.0万"
 * - 带分类前缀（如 "综艺 112490"）：拆出分类 tag "综艺" 与 "11.2万"
 * - 文案（如 "热度飙升"）：原样保留
 */
private fun formatHeat(heat: String): Pair<String?, String> {
    val trimmed = heat.trim()
    if (trimmed.isEmpty()) return null to ""

    val spaceIndex = trimmed.indexOf(' ')
    val (tag, countStr) = if (spaceIndex > 0) {
        trimmed.substring(0, spaceIndex) to trimmed.substring(spaceIndex + 1).trim()
    } else {
        null to trimmed
    }

    val countInt = countStr.toIntOrNull()
    val formattedCount = if (countInt != null) {
        when {
            countInt >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", countInt / 10_000.0)
            else -> countInt.toString()
        }
    } else {
        countStr
    }

    return tag to formattedCount
}
