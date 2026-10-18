package org.kazusa.matter.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.favoriteId
import com.kyant.backdrop.backdrops.LayerBackdrop
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.media.morph.LocalImageMorphState

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

val LocalFavoritesRepository = staticCompositionLocalOf<FavoritesRepository?> { null }
val LocalFavoriteActionScope = staticCompositionLocalOf<CoroutineScope?> { null }
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
fun FavoriteButton(
    post: WeiboStatusDto,
    modifier: Modifier = Modifier,
    scroll: TopBarScrollState? = null,
    backdrop: LayerBackdrop? = null
) {
    val repository = LocalFavoritesRepository.current ?: return
    val state by repository.state.collectAsStateWithLifecycle()
    val id = post.favoriteId
    val busy = id in state.pending
    val favorited = state.overrides[id] ?: post.isFavorited
    // 卡片离开视口时继续完成已发出的操作，确保服务端结果能同步到其他页面。
    val scope = LocalFavoriteActionScope.current ?: rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current
    val onClick: () -> Unit = {
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
    if (scroll != null) {
        LiquidGlassIconButton(
            onClick = onClick, enabled = id != null && !busy,
            backdrop = backdrop, glassAlpha = scroll.alpha, tintAlpha = scroll.alpha,
            modifier = modifier.topBarActionPadding(scroll)
        ) { FavoriteIcon(busy, favorited) }
    } else {
        IconButton(modifier = modifier, enabled = id != null && !busy, onClick = onClick) {
            FavoriteIcon(busy, favorited)
        }
    }
}

@Composable
private fun FavoriteIcon(busy: Boolean, favorited: Boolean) {
    if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    else Icon(
        imageVector = if (favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        contentDescription = if (favorited) "取消收藏" else "收藏微博",
        tint = if (favorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

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
    val scope = LocalFavoriteActionScope.current ?: rememberCoroutineScope()
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
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = tint
            )
        } else {
            Icon(
                imageVector = if (favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (favorited) "取消收藏" else "收藏微博",
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

