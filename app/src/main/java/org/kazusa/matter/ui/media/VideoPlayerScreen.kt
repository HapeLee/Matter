package org.kazusa.matter.ui.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.kazusa.matter.MainActivity
import org.kazusa.matter.data.model.VideoStreamInfo
import androidx.activity.ComponentActivity
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.kazusa.matter.core.util.VideoSaver
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import org.kazusa.matter.ui.navigation.formatVideoSharedKey
import org.kazusa.matter.ui.navigation.weiboVideoSharedBounds

/**
 * 微博视频全功能播放界面：
 * 1. 多挡位清晰度平滑无缝切换（1080P/720P/480P 等）
 * 2. Android 原生系统画中画 (Picture-in-Picture) 与 Android 12+ 自动进 PiP
 * 3. 磁盘 LRU 高速缓存 (Media3 SimpleCache)，避免重复网络下载与重放秒开
 * 4. 自动音频焦点管理，避免与系统其他媒体产生冲突
 * 5. 支持类似图片 Morph 的容器共享元素转场（Shared Bounds Container Morph）
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    title: String,
    coverUrl: String = "",
    mid: String = "",
    streams: List<VideoStreamInfo> = emptyList(),
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val componentActivity = context as? ComponentActivity

    // 候选清晰度列表：有传入 streams 则使用，否则以 videoUrl 兜底
    val candidateStreams = remember(streams, videoUrl) {
        if (streams.isNotEmpty()) streams
        else if (videoUrl.isNotBlank()) listOf(VideoStreamInfo(label = "默认", url = videoUrl))
        else emptyList()
    }

    // 当前选中的清晰度流（默认优先选中传入的 videoUrl 对应档，或第一档最高画质）
    var selectedStream by remember(candidateStreams) {
        mutableStateOf(candidateStreams.firstOrNull { it.url == videoUrl } ?: candidateStreams.firstOrNull())
    }
    var showQualityMenu by remember { mutableStateOf(false) }

    // 画中画 (PiP) 支持与状态
    val isPipSupported = remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    var isInPipMode by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity?.isInPictureInPictureMode == true else false)
    }

    // 视频保存相关逻辑
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    fun performSaveVideo() {
        val downloadUrl = selectedStream?.url ?: videoUrl
        if (downloadUrl.isBlank()) {
            Toast.makeText(context, "视频下载地址无效", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSaving) {
            Toast.makeText(context, "正在保存中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        Toast.makeText(context, "开始保存视频至相册...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val result = VideoSaver.saveVideo(context, downloadUrl, title)
            isSaving = false
            result.onSuccess {
                Toast.makeText(context, "视频已保存至 Movies/Matter", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(context, "保存失败: ${err.localizedMessage ?: "下载或写入异常"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performSaveVideo()
        } else {
            Toast.makeText(context, "未授予存储权限，无法保存视频", Toast.LENGTH_SHORT).show()
        }
    }

    val requestSaveVideo: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performSaveVideo()
        }
    }

    // 监听画中画模式变化
    DisposableEffect(componentActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && componentActivity != null) {
            val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                isInPipMode = info.isInPictureInPictureMode
            }
            componentActivity.addOnPictureInPictureModeChangedListener(listener)
            onDispose {
                componentActivity.removeOnPictureInPictureModeChangedListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    // 初始化带音频焦点与平滑播放的 ExoPlayer（由 videoUrl 驱动实例生命周期）
    val exoPlayer = remember(videoUrl) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true) // 自动接管音频焦点
            .build().apply {
                val initialUrl = selectedStream?.url ?: videoUrl
                if (initialUrl.isNotBlank()) {
                    setMediaItem(MediaItem.fromUri(initialUrl))
                    prepare()
                    playWhenReady = true
                }
            }
    }

    // 播放错误自动降级回退（如 1080P 鉴权失败自动切换回默认可用流）
    DisposableEffect(exoPlayer) {
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (selectedStream?.url != videoUrl && videoUrl.isNotBlank()) {
                    selectedStream = candidateStreams.firstOrNull { it.url == videoUrl }
                        ?: VideoStreamInfo(label = "默认", url = videoUrl)
                    exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(errorListener)
        onDispose {
            exoPlayer.removeListener(errorListener)
        }
    }

    // 计算符合 Android PiP 标准（宽高比必须在 1:2.39 ~ 2.39:1 之间）的 Rational
    fun calculatePipAspectRatio(videoSize: VideoSize): Rational {
        val width = videoSize.width
        val height = videoSize.height
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    // 主动进入画中画
    fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && isPipSupported) {
            val aspect = calculatePipAspectRatio(exoPlayer.videoSize)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
                builder.setSeamlessResizeEnabled(true)
            }
            try {
                activity.enterPictureInPictureMode(builder.build())
            } catch (_: Exception) {}
        }
    }

    // 监听视频尺寸并在 Android 12+ 上注册自动进 PiP 参数
    DisposableEffect(exoPlayer, activity) {
        val sizeListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null && isPipSupported) {
                    val aspect = calculatePipAspectRatio(videoSize)
                    try {
                        activity.setPictureInPictureParams(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(aspect)
                                .setAutoEnterEnabled(true)
                                .setSeamlessResizeEnabled(true)
                                .build()
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        exoPlayer.addListener(sizeListener)
        onDispose {
            exoPlayer.removeListener(sizeListener)
        }
    }

    // 清晰度平滑切换逻辑
    LaunchedEffect(selectedStream?.url) {
        val targetUrl = selectedStream?.url ?: return@LaunchedEffect
        val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUri != null && currentUri != targetUrl) {
            val position = exoPlayer.currentPosition
            val isPlaying = exoPlayer.isPlaying
            exoPlayer.setMediaItem(MediaItem.fromUri(targetUrl), position)
            exoPlayer.prepare()
            if (isPlaying) {
                exoPlayer.play()
            }
        }
    }

    // 生命周期与按 Home 键（Android 8-11）监听兜底
    DisposableEffect(Unit) {
        val mainActivity = activity as? MainActivity
        if (mainActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported) {
            mainActivity.onUserLeaveHintListener = {
                if (exoPlayer.isPlaying) {
                    enterPictureInPicture()
                }
            }
        }
        onDispose {
            mainActivity?.onUserLeaveHintListener = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null && isPipSupported) {
                try {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                    )
                } catch (_: Exception) {}
            }
            exoPlayer.release()
        }
    }

    // 监听视频是否已渲染出首帧（首帧渲染前显示封面垫底，避免黑屏过渡）
    var isFirstFrameRendered by remember { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val frameListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
            }
        }
        exoPlayer.addListener(frameListener)
        onDispose {
            exoPlayer.removeListener(frameListener)
        }
    }

    val videoSharedKey = remember(mid, videoUrl) { formatVideoSharedKey(mid, videoUrl) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .weiboVideoSharedBounds(key = videoSharedKey)
            .background(Color.Black)
    ) {
        // 视频播放核心视图
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = !isInPipMode
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { playerView ->
                // 画中画模式下隐藏自带控制器，全屏时恢复
                playerView.useController = !isInPipMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // 稳态缩略图垫底层（在视频加载并渲染出第一帧前展示，保证从卡片展开时无缝视觉过渡）
        if (!isFirstFrameRendered && coverUrl.isNotBlank() && !isInPipMode) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 顶部悬浮控制栏（画中画模式下完全隐藏，全屏模式下展示）
        AnimatedVisibility(
            visible = !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.54f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }

                // 标题
                if (title.isNotBlank()) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 清晰度选择药丸按钮
                if (candidateStreams.size > 1) {
                    Box {
                        Surface(
                            onClick = { showQualityMenu = true },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.54f),
                            contentColor = Color.White,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedStream?.label ?: "画质",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "选择画质",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false }
                        ) {
                            candidateStreams.forEach { stream ->
                                val isSelected = stream.url == selectedStream?.url
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stream.label,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedStream = stream
                                        showQualityMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // 保存视频按钮
                IconButton(
                    onClick = { requestSaveVideo() },
                    enabled = !isSaving,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.54f))
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "保存视频",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 画中画按钮（支持设备才显示）
                if (isPipSupported) {
                    IconButton(
                        onClick = { enterPictureInPicture() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.54f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PictureInPictureAlt,
                            contentDescription = "悬浮窗播放",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
