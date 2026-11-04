package org.kazusa.matter.ui.media

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.shape.CircleShape


import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.kazusa.matter.MainActivity
import org.kazusa.matter.core.network.WeiboRequestHeaders
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.buildVideoPlaybackCandidates
import androidx.activity.ComponentActivity
import android.widget.Toast
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
 * 1. 多挡位清晰度切换，失败时按候选队列自动降级（1080P → 720P → 480P）
 * 2. Android 原生系统画中画 (Picture-in-Picture) 与 Android 12+ 自动进 PiP
 * 3. 与图片 / 下载链路一致的 UA 与防盗链 Referer（微博 CDN 会按节点校验）
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

    // 候选播放队列：默认档排第一，其余档位作为失败后的降级候选。
    // 地址在这里统一规范化（空串 / 协议相对 / 非 http(s) 一律剔除），
    // 否则 MediaItem.fromUri("") 只会让播放器静默黑屏。
    val candidates = remember(streams, videoUrl) {
        buildVideoPlaybackCandidates(videoUrl = videoUrl, streams = streams)
    }
    var selectedIndex by remember(candidates) { mutableStateOf(0) }
    val selectedCandidate = candidates.getOrNull(selectedIndex)

    // 已尝试失败的地址：同一档位不重复重试，全部失败后直接给出可读的错误文案
    var failedUrls by remember(candidates) { mutableStateOf(emptySet<String>()) }
    var playbackError by remember(candidates) { mutableStateOf<String?>(null) }
    // 用户重试 / 切换画质时自增，用于对同一地址强制重新 prepare
    var reloadToken by remember(candidates) { mutableStateOf(0) }

    var showQualityMenu by remember { mutableStateOf(false) }

    // 画中画 (PiP) 支持与状态
    val isPipSupported = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    var isInPipMode by remember {
        mutableStateOf(activity?.isInPictureInPictureMode == true)
    }

    // 视频保存相关逻辑
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    fun performSaveVideo() {
        val downloadUrl = selectedCandidate?.url ?: videoUrl
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

    val requestSaveVideo: () -> Unit = {
        performSaveVideo()
    }

    // 监听画中画模式变化
    DisposableEffect(componentActivity) {
        if (componentActivity != null) {
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

    // 初始化 ExoPlayer：音频焦点 + 与图片/下载链路一致的 UA / Referer。
    // 之前用默认 HTTP 栈（UA 为 `ExoPlayerLib/x.y.z`、不带 Referer），落到微博 CDN
    // 真正做防盗链校验的节点上会被 403，表现就是「有些视频一直转圈/黑屏」。
    val exoPlayer = remember(context, videoUrl) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(WeiboRequestHeaders.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        // Referer 只补给微博 / 新浪系 CDN：第三方视频站通常不校验来源页，
        // 乱加请求头反而可能触发它们的校验规则（与 WeiboImageClient 同一套判断）。
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, httpDataSourceFactory)
        ) { dataSpec: DataSpec ->
            if (WeiboRequestHeaders.isWeiboHost(dataSpec.uri.host.orEmpty())) {
                dataSpec.withRequestHeaders(WeiboRequestHeaders.CDN_REQUEST_HEADERS)
            } else {
                dataSpec
            }
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true) // 自动接管音频焦点
            .build()
    }

    // 播放失败按候选队列自动降级（1080P 被 CDN 拒绝 → 720P → 480P……），
    // 全部失败才展示可读错误。以前只有「非默认档」才会降级回默认档，
    // 默认档本身失败就是永久黑屏，这是「有些视频打不开」最直接的观感来源。
    DisposableEffect(exoPlayer, candidates) {
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val failed = candidates.getOrNull(selectedIndex)?.url
                    ?.let { failedUrls + it }
                    ?: failedUrls
                failedUrls = failed
                val nextIndex = candidates.indexOfFirst { it.url !in failed }
                if (nextIndex >= 0) {
                    selectedIndex = nextIndex
                } else {
                    playbackError = error.toPlaybackMessage()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        exoPlayer.addListener(errorListener)
        onDispose {
            exoPlayer.removeListener(errorListener)
        }
    }

    // 没有任何可用地址时直接给结论，不要留一个纯黑界面
    LaunchedEffect(candidates) {
        if (candidates.isEmpty()) {
            playbackError = "该视频没有可用的播放地址（服务端未下发有效直链）"
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
        if (activity != null && isPipSupported) {
            val aspect = calculatePipAspectRatio(exoPlayer.videoSize)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setAutoEnterEnabled(true)
                .setSeamlessResizeEnabled(true)
            try {
                activity.enterPictureInPictureMode(builder.build())
            } catch (_: Exception) {}
        }
    }

    // 监听视频尺寸并在 Android 12+ 上注册自动进 PiP 参数
    DisposableEffect(exoPlayer, activity) {
        val sizeListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (activity != null && isPipSupported) {
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

    // 首屏播放 / 切换清晰度 / 播放失败降级：都走这里换源，保留当前进度
    LaunchedEffect(exoPlayer, selectedIndex, reloadToken) {
        val target = candidates.getOrNull(selectedIndex) ?: return@LaunchedEffect
        val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        // reloadToken > 0 表示用户主动重试，即使地址相同也要重新 prepare
        if (currentUrl == target.url && reloadToken == 0) return@LaunchedEffect
        val position = if (currentUrl == null) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        exoPlayer.setMediaItem(MediaItem.fromUri(target.url), position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // 按 Home 键（Android 8-11）监听兜底：只依赖监听相关的键，不跟随播放器重建
    DisposableEffect(exoPlayer, activity, isPipSupported) {
        val mainActivity = activity as? MainActivity
        if (mainActivity != null && isPipSupported) {
            mainActivity.onUserLeaveHintListener = {
                if (exoPlayer.isPlaying) {
                    enterPictureInPicture()
                }
            }
        }
        onDispose {
            mainActivity?.onUserLeaveHintListener = null
            if (activity != null && isPipSupported) {
                try {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                    )
                } catch (_: Exception) {}
            }
        }
    }

    // 资源释放跟着播放器实例走：videoUrl 变化会重建 exoPlayer，
    // 用 DisposableEffect(Unit) 会捕获旧实例，导致新播放器不被释放
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // 监听视频是否已渲染出首帧（首帧渲染前显示封面垫底，避免黑屏过渡）
    var isFirstFrameRendered by remember(exoPlayer) { mutableStateOf(false) }
    // 换源后必须重新等首帧，否则封面垫底会在新画面出来前一直留着
    LaunchedEffect(selectedIndex, reloadToken) {
        isFirstFrameRendered = false
    }
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
                // 播放器实例可能因换视频重建，AndroidView 的 factory 不会重跑
                if (playerView.player !== exoPlayer) playerView.player = exoPlayer
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

        // 播放失败：给出可读原因与重试入口，而不是一直黑屏
        if (playbackError != null && !isInPipMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = playbackError.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    onClick = {
                        playbackError = null
                        failedUrls = emptySet()
                        reloadToken++
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.54f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "重试", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
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
                if (candidates.size > 1) {
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
                                    text = selectedCandidate?.label ?: "画质",
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
                            candidates.forEachIndexed { index, candidate ->
                                val isSelected = index == selectedIndex
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = candidate.label,
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
                                        selectedIndex = index
                                        playbackError = null
                                        failedUrls = emptySet()
                                        reloadToken++
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

/**
 * 把 Media3 的播放错误翻译成用户能理解的原因。
 *
 * 之前播放失败没有任何界面反馈（只有一帧黑屏 + 一直转圈），
 * 既无法判断是网络、鉴权还是编码问题，也让「有些视频打不开」变成不可复现的猜测。
 */
private fun PlaybackException.toPlaybackMessage(): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "视频地址已失效或被 CDN 拒绝（HTTP 异常），可尝试切换画质"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "网络连接失败，请检查网络后重试"
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
        "该视频只提供明文 http 地址，系统已阻止播放"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "该视频格式暂不支持（可能是 HLS 直播流）"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "当前设备不支持该视频编码"
    else -> "视频播放失败（${errorCodeName}）"
}
