package org.kazusa.matter.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.favoriteId
import org.kazusa.matter.ui.media.morph.LocalImageMorphState

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
    val state = repository?.state?.collectAsStateWithLifecycle()?.value
    val id = post.favoriteId
    val busy = state != null && id in state.pending
    val favorited = state?.overrides?.get(id) ?: post.isFavorited
    // 卡片 / 胶囊离开视口后仍要收到写入结果，所以优先用会话级 scope
    val scope = LocalUserActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val morphState = LocalImageMorphState.current
    val enabled = repository != null && id != null && !busy && morphState?.isPresent != true

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
    val state = repository?.state?.collectAsStateWithLifecycle()?.value
    val id = post.favoriteId
    val busy = state != null && id in state.pending
    val favorited = state?.overrides?.get(id) ?: post.isFavorited
    val scope = LocalUserActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val morphState = LocalImageMorphState.current

    val onClick: () -> Unit = {
        if (morphState?.isPresent != true && repository != null && id != null && !busy) {
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
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.5.dp,
                color = tint
            )
        } else {
            Icon(
                imageVector = if (favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (favorited) "取消收藏" else "收藏微博",
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
