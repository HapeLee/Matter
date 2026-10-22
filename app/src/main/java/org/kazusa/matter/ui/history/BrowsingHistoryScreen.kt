package org.kazusa.matter.ui.history

import androidx.compose.foundation.shape.CircleShape


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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.core.util.TimeUtils
import org.kazusa.matter.data.local.BrowsingCategory
import org.kazusa.matter.data.local.BrowsingHistoryEntity
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.MatterTag
import org.kazusa.matter.ui.components.SearchInputField
import org.kazusa.matter.ui.components.TopBarNavigationButton
import org.kazusa.matter.ui.theme.MatterShapeTokens
import org.kazusa.matter.ui.effect.liquid.LiquidGlassFloatingMenu
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon

/** 悬浮筛选的可选项；[mask] 直接作为位掩码下发给 DAO */
private enum class HistoryFilter(val label: String, val mask: Int) {
    ALL("全部", BrowsingCategory.ALL),
    POST("微博", BrowsingCategory.POST),
    USER("用户", BrowsingCategory.USER)
}

/**
 * 浏览记录页。
 *
 * 数据源是本地 Room 表（`browsing_history`），不依赖任何微博接口，离线可用。
 *
 * 交互形态对齐 app 内既有页面：
 * - 搜索：与搜索结果页同构 —— 顶栏标题槽常驻液态玻璃搜索栏（前置图标槽是返回键），
 *   输入即时过滤列表；因为不存在「标题 ↔ 搜索栏」的切换，也就没有任何形变动画；
 * - 清空：入口放在列表尾部（顶栏 actions 槽留空，几何恒定），点击走二次确认；
 * - 筛选：右下角悬浮按钮（[LiquidGlassFloatingMenu]，与搜索结果页的悬浮入口同款），
 *   点击后向上形变为选项面板（全部 / 微博 / 用户），单选带对勾。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowsingHistoryScreen(
    viewModel: BrowsingHistoryViewModel,
    onBackClick: () -> Unit,
    onOpen: (BrowsingHistoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 输入框用本地状态即时回显（关键词要经 Room 回查，直接用 uiState 会有一帧延迟）
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    var showClearDialog by remember { mutableStateOf(false) }

    val backdrop = rememberScreenBackdrop()

    // 滚动到接近底部时自动加载下一页
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            layoutInfo.totalItemsCount > 0 && lastVisible >= layoutInfo.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空浏览记录？") },
            text = { Text("将删除当前账号在本机的全部浏览记录，且不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearAll()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                animationLabel = "HistoryTopBarScrim",
                // 返回键走经典导航槽：它不参与标题槽的几何计算，
                // 滚动内收动画只影响搜索栏自身，不会多出一棵图标子树跟着每帧重建。
                navigationIcon = { scroll ->
                    TopBarNavigationButton(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = onBackClick
                    )
                },
                // 标题槽常驻搜索栏（与搜索结果页同构）：没有「标题 ↔ 搜索栏」的切换，
                // 也就没有任何形变动画。
                title = { scroll ->
                    SearchInputField(
                        query = query,
                        onQueryChange = {
                            query = it
                            viewModel.onKeywordChange(it)
                        },
                        // 列表是实时过滤的，IME 搜索只需收起键盘
                        onSearch = {
                            focusManager.clearFocus()
                            softwareKeyboardController?.hide()
                        },
                        onClear = {
                            query = ""
                            viewModel.onKeywordChange("")
                        },
                        backdrop = backdrop,
                        placeholder = "搜索浏览记录",
                        scrollAlpha = scroll.alpha,
                        // 左右边缘的滚动联动边距统一由 SearchInputField 内部负责，这里只负责撑满标题槽位
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                uiState.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize().padding(top = topPadding)) {
                        EmptyHistoryState(
                            keyword = uiState.keyword,
                            filteredByCategory = uiState.categoryMask != BrowsingCategory.ALL
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = topPadding + 12.dp,
                            end = 16.dp,
                            // 给右下角悬浮筛选按钮留出避让空间
                            bottom = 104.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items = uiState.items, key = { it.id }) { item ->
                            HistoryItemRow(
                                item = item,
                                onOpen = { onOpen(item) },
                                onDelete = { viewModel.remove(item) }
                            )
                        }
                        item(key = "footer") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (uiState.hasMore) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        text = "已经到底了 · 共 ${uiState.total} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // 清空入口放列表尾部：顶栏只保留搜索栏，几何恒定
                                    TextButton(onClick = { showClearDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "清空浏览记录",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右下角悬浮筛选：点击后原地向上形变为选项面板
            LiquidGlassFloatingMenu(
                items = HistoryFilter.entries.map { it.label },
                selectedIndex = HistoryFilter.entries
                    .indexOfFirst { it.mask == uiState.categoryMask }
                    .coerceAtLeast(0),
                onSelect = { index -> viewModel.onCategoryChange(HistoryFilter.entries[index].mask) },
                backdrop = backdrop,
                // 与搜索结果页的悬浮入口保持同一套定位：避开导航栏 + 16dp 边距
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp),
                icon = {
                    LiquidGlassIcon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = "筛选浏览记录"
                    )
                }
            )
        }
    }
}

@Composable
private fun HistoryItemRow(
    item: BrowsingHistoryEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val isUser = item.category and BrowsingCategory.USER != 0

    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUser) Icons.Outlined.Person else Icons.AutoMirrored.Outlined.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.username?.ifBlank { null } ?: if (isUser) "微博用户" else "微博正文",
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    CategoryPill(text = if (isUser) "用户" else "微博")
                }

                item.totalText?.ifBlank { null }?.let { summary ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = TimeUtils.formatRelativeTime(item.createAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除这条记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CategoryPill(text: String) {
    MatterTag(
        text = text,
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun EmptyHistoryState(keyword: String, filteredByCategory: Boolean) {
    val filtered = keyword.isNotBlank() || filteredByCategory

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (filtered) "没有找到相关记录" else "还没有浏览记录",
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                keyword.isNotBlank() -> "换个关键词再试试"
                filteredByCategory -> "换个分类看看"
                else -> "看过的微博与博主会在这里按时间倒序列出"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
