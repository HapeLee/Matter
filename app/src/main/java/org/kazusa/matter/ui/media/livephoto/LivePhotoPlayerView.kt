package org.kazusa.matter.ui.media.livephoto

import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import org.kazusa.matter.R

/**
 * 实况照片 (Live Photo) 短视频无缝循环播放器：
 * - 默认静音循环播放短视频 (1.5~3s)
 * - 隐藏进度条与控制层，无缝融入大图浏览手势
 * - 每个播放器实例等待首帧就绪后淡入，静态封面始终垫底
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePhotoPlayerView(
    videoUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // 实况短视频默认静音
            setMediaItem(MediaItem.fromUri(videoUrl))
        }
    }
    // Owned by this player instance: a newly mounted player must wait for its own first frame.
    var firstFrameReady by remember(exoPlayer) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (isPlaying && firstFrameReady) 1f else 0f,
        animationSpec = tween(220),
        label = "livePhotoAlpha"
    )

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameReady = true
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.prepare()
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying, exoPlayer, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                awaitCancellation()
            } finally {
                exoPlayer.pause()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.live_photo_player, null) as PlayerView).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view -> if (view.player !== exoPlayer) view.player = exoPlayer },
        modifier = modifier.graphicsLayer { alpha = videoAlpha }
    )
}
