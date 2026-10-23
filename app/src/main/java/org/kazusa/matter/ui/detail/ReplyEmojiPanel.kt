package org.kazusa.matter.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.core.util.weiboEmojiImageModel
import org.kazusa.matter.data.model.OnlineEmojiState
import org.kazusa.matter.data.model.WeiboEmojis

/** 仅用于面板展示；下载与缓存仍由 ViewModel / Repository 管理。 */
internal data class ReplyEmojiGroup(
    val id: String,
    val label: String,
    val codes: List<String>,
    val images: Map<String, String> = emptyMap(),
    val representative: String? = codes.firstOrNull(),
    val packageId: String? = null
)

internal fun replyEmojiGroups(online: OnlineEmojiState, recent: List<String>): List<ReplyEmojiGroup> {
    val installed = online.installed.associateBy { it.id }
    val hot = installed["com.sina.default"]
    val base = online.catalog.firstOrNull { it.id == "catalog.default" }
    return buildList {
        add(ReplyEmojiGroup("recent", "最近使用", recent))
        add(ReplyEmojiGroup("weibo", "微博表情",
            (hot?.emojis.orEmpty().map { it.code } + (base?.emojis?.map { it.code } ?: WeiboEmojis.weibo)).distinct(),
            representative = base?.emojis?.firstOrNull()?.code ?: "[微笑]"))
        add(ReplyEmojiGroup("unicode", "Emoji", WeiboEmojis.unicode, representative = "😄"))
        if (online.catalog.isEmpty()) add(ReplyEmojiGroup("other", "其他表情", WeiboEmojis.other, representative = "[哆啦A梦微笑]"))
        online.catalog.filterNot { it.id == "catalog.default" }.forEach { group ->
            add(ReplyEmojiGroup(group.id, group.title, group.emojis.map { it.code }, group.emojis.associate { it.code to it.image }))
        }
        val packages = (online.packages.map { it.id to it.title } + online.installed.map { it.id to it.title })
            .distinctBy { it.first }.filterNot { it.first == "com.sina.default" }
        packages.forEach { (id, title) ->
            val emojis = installed[id]?.emojis.orEmpty()
            add(ReplyEmojiGroup("online:$id", title, emojis.map { it.code }, emojis.associate { it.code to it.image }, packageId = id))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyEmojiPanel(
    recentEmojis: List<String>,
    online: OnlineEmojiState,
    selectedGroup: String,
    onGroupChange: (String) -> Unit,
    onOpenOnline: () -> Unit,
    onRefreshOnline: () -> Unit,
    onInstall: (String) -> Unit,
    onEmojiClick: (String) -> Unit
) {
    val onlineImages = LocalOnlineEmojiImages.current
    val groups = remember(online.catalog, online.packages, online.installed, recentEmojis) {
        replyEmojiGroups(online, recentEmojis)
    }
    val groupIds = groups.map { it.id }
    val pager = rememberPagerState(initialPage = groupIds.indexOf(selectedGroup).takeIf { it >= 0 } ?: 1,
        pageCount = { groups.size })
    val tabList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selected = groups.getOrNull(pager.currentPage) ?: groups.first { it.id == "weibo" }
    LaunchedEffect(Unit) { onOpenOnline() }
    // 目录增删按分组 ID 恢复位置；只有停稳的页才写回持久化选择并触发下载。
    LaunchedEffect(groupIds) {
        pager.scrollToPage(groupIds.indexOf(selectedGroup).takeIf { it >= 0 } ?: 1)
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (page, scrolling) ->
            if (!scrolling) groupIds.getOrNull(page)?.let(onGroupChange)
        }
    }
    LaunchedEffect(selectedGroup, online.packages) {
        selectedGroup.takeIf { it.startsWith("online:") }?.removePrefix("online:")?.let(onInstall)
    }
    LaunchedEffect(pager.currentPage, groupIds) {
        val index = pager.currentPage.coerceIn(groupIds.indices)
        val visible = tabList.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible == null || visible.offset < tabList.layoutInfo.viewportStartOffset ||
            visible.offset + visible.size > tabList.layoutInfo.viewportEndOffset) {
            tabList.animateScrollToItem(index)
        }
    }
    Column(Modifier.fillMaxWidth().height(280.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), state = tabList, contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rowItems(groups, key = { it.id }) { group ->
                    val representative = group.representative
                    val image = representative?.let { group.images[it] ?: weiboEmojiImageModel(it, onlineImages) }
                    Box(contentAlignment = Alignment.Center,
                        modifier = Modifier.size(36.dp).clip(CircleShape).selectable(
                            selected = selected.id == group.id, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(groupIds.indexOf(group.id)) } }).background(
                            if (selected.id == group.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            CircleShape).semantics { contentDescription = group.label }) {
                        when {
                            group.id == "recent" -> Icon(Icons.Outlined.History, null, Modifier.size(20.dp))
                            image != null -> AsyncImage(image, null, modifier = Modifier.size(19.dp))
                            representative != null -> Text(representative, fontSize = 20.sp)
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                                Text(group.label.removeSuffix(" 月表情").replace(" 年 ", "/"), fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
                item(key = "refresh-emojis") {
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !online.refreshing, onClick = onRefreshOnline),
                        contentAlignment = Alignment.Center) {
                        if (online.refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, "刷新表情", Modifier.size(20.dp))
                    }
                }
            }
        }
        if (online.error != null) Text("在线表情未能完整更新，可点刷新重试", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        HorizontalPager(state = pager, key = { groups[it].id }, modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top) { page ->
            val group = groups[page]
            val packageId = group.packageId
            val codes = group.codes
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (packageId != null && packageId in online.downloading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else if (packageId != null && (online.packageErrors[packageId] != null || codes.isEmpty())) {
                        TextButton(onClick = { onInstall(packageId) }) { Text("重试") }
                    }
                }
                if (codes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(when {
                            group.id == "recent" -> "还没有使用过表情"
                            packageId in online.downloading -> "正在下载表情…"
                            else -> "表情加载失败，点击重试"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), state = rememberLazyGridState(),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        items(codes, key = { it }) { code ->
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                tooltip = { PlainTooltip { Text(code.removeSurrounding("[", "]")) } },
                                state = rememberTooltipState()
                            ) {
                                Box(Modifier.fillMaxWidth().height(48.dp).clickable { onEmojiClick(code) }, contentAlignment = Alignment.Center) {
                                    val image = group.images[code] ?: weiboEmojiImageModel(code, onlineImages)
                                    if (image != null) AsyncImage(image, contentDescription = code, modifier = Modifier.size(26.dp))
                                    else Text(code, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
