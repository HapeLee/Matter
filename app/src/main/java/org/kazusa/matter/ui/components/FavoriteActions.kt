package org.kazusa.matter.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.favoriteId
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.media.morph.rememberImageMorphPresent

/**
 * 收藏动作的「内容插槽」版本。
 *
 * 顶栏玻璃按钮、正文页底部悬浮胶囊等形态各自决定图标与布局，但状态读取、pending、
 * 结果提示以及「形变动画进行中不响应」的判断共用同一份实现，避免每处各写一条写入路径。
 */
@Composable
fun FavoriteToggleAction(
    post: WeiboStatusDto,
    content: @Composable (
        favorited: Boolean,
        busy: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) -> Unit
) {
    val repository = LocalFavoritesRepository.current
    // 收藏态由导航根收集一次后下发（State），这里只按 id 取自己那一份
    val state = LocalFavoriteState.current
    val id = remember(post.idStr, post.mid, post.id) { post.favoriteId }
    val busy by remember(state, id) { derivedStateOf { id != null && id in state.value.pending } }
    val favorited by remember(state, id, post.isFavorited) {
        derivedStateOf { state.value.overrides[id] ?: post.isFavorited }
    }
    // 卡片 / 胶囊离开视口后仍要收到写入结果，所以优先用会话级 scope
    val scope = LocalUserActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val morphState = LocalImageMorphState.current
    val morphPresent by rememberImageMorphPresent(morphState)
    val enabled = repository != null && id != null && !busy && !morphPresent

    val onClick: () -> Unit = {
        val target = repository
        if (enabled && target != null) {
            scope.launch {
                val message = when (val result = target.toggle(post)) {
                    is ApiResult.Success -> if (result.data) "已收藏" else "已取消收藏"
                    is ApiResult.Error -> result.message
                    ApiResult.Loading -> return@launch
                }
                if (snackbarHostState != null) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    content(favorited, busy, enabled, onClick)
}

/**
 * 信息流卡片操作栏右侧的收藏按钮（纯图标，无计数）。
 *
 * 图标 16dp 与操作栏的 [WeiboActionButton] 对齐；外框固定 38dp 保留触摸热区，
 * 同时也是卡片操作栏行高的下限（正文页没有这个按钮，行高由文字按钮撑开）。
 */
@Composable
fun FavoriteActionButton(
    post: WeiboStatusDto,
    modifier: Modifier = Modifier
) {
    val repository = LocalFavoritesRepository.current
    // 注意：不要在这里 collectAsStateWithLifecycle()。列表项各自订阅一份 Flow 会付出
    // N 份收集成本（每个可见卡片一个协程 + 生命周期观察者），且任意一次收藏写入都会
    // 让它们全部重组。导航根已经收集过一次，这里按 id 精确读取。
    val state = LocalFavoriteState.current
    val id = remember(post.idStr, post.mid, post.id) { post.favoriteId }
    val busy by remember(state, id) { derivedStateOf { id != null && id in state.value.pending } }
    val favorited by remember(state, id, post.isFavorited) {
        derivedStateOf { state.value.overrides[id] ?: post.isFavorited }
    }
    val scope = LocalUserActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val morphState = LocalImageMorphState.current
    val morphPresent by rememberImageMorphPresent(morphState)

    val onClick: () -> Unit = {
        if (!morphPresent && repository != null && id != null && !busy) {
            scope.launch {
                val message = when (val result = repository.toggle(post)) {
                    is ApiResult.Success -> if (result.data) "已收藏" else "已取消收藏"
                    is ApiResult.Error -> result.message
                    ApiResult.Loading -> return@launch
                }
                if (snackbarHostState != null) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val tint = if (favorited) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }

    Box(
        modifier = modifier
            .size(38.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = id != null && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 不在 pending 期间转圈：收藏已经是乐观更新，图标就应该是结果态；
        // 转圈会让人以为「没点上」。重复点击由 clickable 的 enabled 拦住。
        Icon(
            imageVector = if (favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (favorited) "取消收藏" else "收藏微博",
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}
