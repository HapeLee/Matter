package org.kazusa.matter.ui.media.morph

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import org.kazusa.matter.core.datastore.ImageQualityMode
import org.kazusa.matter.core.datastore.MediaPreferenceState
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.util.ImageSaver
import org.kazusa.matter.core.util.NetworkMonitor
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.withWeiboImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import androidx.compose.animation.core.animate
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * 微博图片 MD3 Morph 形变宿主浮层（参考 legado-with-MD3 经典设计）：
 * - 在应用根窗口之上直接层叠，不改动导航栈，无闪屏与白屏；
 * - 第一层：背景深黑渐变 Veil；
 * - 第二层：从信息流卡片原始位置物理展开/收拢的飞行图片层 (Flying Image)；
 * - 第三层：展开后完整多图 HorizontalPager 画廊，支持左右切图、双指捏合缩放、双击缩放与上下拉跟手收起。
 */
@Composable
fun ImageMorphOverlay(
    morphState: ImageMorphState,
    mediaPreferences: MediaPreferences,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val mediaState by mediaPreferences.mediaStateFlow.collectAsStateWithLifecycle(
        initialValue = MediaPreferenceState()
    )

    // 用 derivedStateOf 收窄对动画进度的依赖：仅在布尔结果变化时才重组，
    // 避免形变动画期间每帧重组整个浮层。
    val hostPresent by remember(morphState) { derivedStateOf { morphState.isPresent } }
    val backEnabled by remember(morphState) { derivedStateOf { morphState.isOpen } }
    var backSettleJob by remember { mutableStateOf<Job?>(null) }

    key(hostPresent) {
        PredictiveBackHandler(enabled = hostPresent && backEnabled) { events ->
            if (!morphState.isOpen) {
                events.collect { }
                return@PredictiveBackHandler
            }
            backSettleJob?.cancel()
            val startProgress = morphState.progress.value
            var lastProgress = startProgress
            var lastTimeNanos = 0L
            var releaseVelocity = 0f
            try {
                morphState.progress.stop()
                morphState.dragging = true
                events.collect { event ->
                    val preview = computePredictiveMorphProgress(startProgress, event.progress)
                    val now = System.nanoTime()
                    if (lastTimeNanos != 0L && now > lastTimeNanos) {
                        releaseVelocity = (preview - lastProgress) /
                                ((now - lastTimeNanos) / 1_000_000_000f)
                    }
                    lastProgress = preview
                    lastTimeNanos = now
                    morphState.progress.snapTo(preview)
                }
                morphState.dragging = false
                backSettleJob = scope.launch {
                    morphState.collapse(scope, initialVelocity = releaseVelocity)
                }
            } catch (cancelled: CancellationException) {
                if (morphState.isOpen) {
                    backSettleJob = scope.launch {
                        morphState.progress.animateTo(1f, MORPH_SETTLE_SPEC)
                    }
                }
                throw cancelled
            } finally {
                morphState.dragging = false
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 首帧同步初始化屏幕尺寸，杜绝任何因异步布局回调造成的首帧端点缺失跳跃
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val screenRect = remember(screenW, screenH) { Rect(0f, 0f, screenW, screenH) }

        LaunchedEffect(screenRect) {
            morphState.reportScreenBounds(screenRect)
        }

        if (!morphState.isPresent) return@BoxWithConstraints

        val urls = morphState.urls
        if (urls.isEmpty()) return@BoxWithConstraints

        val pagerState = rememberPagerState(
            initialPage = morphState.initialIndex.coerceIn(0, urls.size - 1),
            pageCount = { urls.size }
        )

        // 同步当前 Pager 页码至 Morph 状态，以便收起时追踪对应的图片
        LaunchedEffect(pagerState.currentPage) {
            morphState.onPageChanged(pagerState.currentPage)
        }

        // ---- 清晰度策略（与 ImageViewerScreen 保持一致）----
        // 打开查看器时定格网络状态，避免浏览过程中网络切换导致清晰度反复跳变
        val unmeteredNetwork = remember { NetworkMonitor.isUnmetered(context) }
        val autoOriginal = remember(mediaState.imageQualityMode, unmeteredNetwork) {
            when (mediaState.imageQualityMode) {
                ImageQualityMode.ALWAYS_ORIGINAL -> true
                ImageQualityMode.WIFI_ORIGINAL -> unmeteredNetwork
                ImageQualityMode.THUMBNAIL_ONLY -> false
            }
        }
        var forceOriginal by remember { mutableStateOf(false) }
        var originalDisabled by remember { mutableStateOf(false) }
        var hasShownMedium by remember { mutableStateOf(!autoOriginal) }
        var originalLoadFailed by remember { mutableStateOf(false) }
        var toastMessage by remember { mutableStateOf<String?>(null) }
        var urlAwaitingStoragePermission by remember { mutableStateOf<String?>(null) }

        val showOriginal = (autoOriginal || forceOriginal) && !originalDisabled

        LaunchedEffect(showOriginal) {
            if (!showOriginal) hasShownMedium = true
        }

        LaunchedEffect(toastMessage) {
            toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                toastMessage = null
            }
        }

        // 原图加载失败：提示并退回中等档位，避免整屏空白
        LaunchedEffect(originalLoadFailed) {
            if (originalLoadFailed) {
                originalLoadFailed = false
                toastMessage = "加载原图失败"
                originalDisabled = true
            }
        }

        val startSave: (String) -> Unit = { imageUrl ->
            scope.launch {
                val result = ImageSaver.saveImage(context, imageUrl)
                toastMessage = result.fold(
                    { name -> "图片已保存至相册：$name" },
                    { error -> "图片保存失败：${error.message ?: "未知错误"}" }
                )
            }
        }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            val pending = urlAwaitingStoragePermission
            urlAwaitingStoragePermission = null
            when {
                granted && pending != null -> startSave(pending)
                !granted -> toastMessage = "未授予存储权限，无法保存图片"
            }
        }

        // Android 10+ 走 MediaStore 沙箱无需权限；Android 9 及以下需要动态申请写入权限。
        // 保存进相册始终转存原图档位，不做二次压缩。
        val requestSave: (String) -> Unit = { imageUrl ->
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                urlAwaitingStoragePermission = imageUrl
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startSave(imageUrl)
            }
        }

        // 当前页清晰度判定
        val currentOriginalUrl = urls[pagerState.currentPage.coerceIn(0, urls.lastIndex)]
        val currentIsAnimated = currentOriginalUrl.contains(".gif", ignoreCase = true)
        val currentThumbUrl = remember(currentOriginalUrl) {
            currentOriginalUrl.withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: currentOriginalUrl
        }
        val canLoadOriginal = !currentIsAnimated && currentThumbUrl != currentOriginalUrl
        val showingOriginal = showOriginal || currentIsAnimated

        var pageDragAlpha by remember { mutableFloatStateOf(1f) }
        var isPageDragging by remember { mutableStateOf(false) }

        // 第一层：背景遮罩 (Veil) 与全屏触摸隔离盾（Touch Shield）
        // 彻底拦截并消费浮层激活全周期（展开过渡、大图画廊、拖拽释放、收拢飞行）的一切触摸手势，
        // 100% 杜绝任何手势下穿到底层微博卡片（防止误触卡片进入正文、防止误滑导致列表位置偏移）！
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .drawBehind {
                    val currentVeil = (morphState.veilAlpha * pageDragAlpha).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = currentVeil))
                }
        )

        // 是否处于 100% 完全展开且未触发收拢的纯画廊模式（仅在状态切换时重组）
        val isGalleryMode by remember(morphState) {
            derivedStateOf { morphState.progress.value >= 1f && !morphState.isCollapsing }
        }

        if (!isGalleryMode) {
            // 第二层：飞行形变图片层（在展开过渡、退出收拢、系统预测返回时全权接管物理几何运动）
            // 与第三层严格互斥挂载，同屏绝不出现第二个图片实例！
            val flyingFrame = morphState.flyingFrame()
            if (flyingFrame != null && !flyingFrame.bounds.isEmpty) {
                val currentUrl = urls.getOrNull(morphState.currentIndex) ?: urls[0]
                val left = flyingFrame.bounds.left.roundToInt()
                val top = flyingFrame.bounds.top.roundToInt()
                val widthDp = with(density) { flyingFrame.bounds.width.toDp() }
                val heightDp = with(density) { flyingFrame.bounds.height.toDp() }
                val cornerRadiusDp = with(density) { flyingFrame.cornerRadiusPx.toDp() }
                // 飞行图使用信息流已解码的缩略图档，动画起始帧立即有画面；
                // 原图交由画廊层承载，避免飞行途中原图未就绪出现“空窗”而丢失动画观感。
                val stableRequest = rememberStableImageRequest(currentUrl.morphThumbnailUrl())

                Box(
                    modifier = Modifier
                        .offset { IntOffset(left, top) }
                        .size(width = widthDp, height = heightDp)
                        .clip(RoundedCornerShape(cornerRadiusDp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    AsyncImage(
                        model = stableRequest,
                        contentDescription = "形变放大图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            // 第三层：交互式多图画廊（仅在 100% 完全展开且非退出状态时挂载，提供切图、缩放与拖拽退出手势）
            var currentZoomScale by remember { mutableFloatStateOf(1f) }

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = (currentZoomScale <= 1.05f),
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val originalUrl = urls[pageIndex]
                val isAnimated = originalUrl.contains(".gif", ignoreCase = true)
                val mediumUrl = remember(originalUrl) {
                    originalUrl.withWeiboImageSize(WeiboImageSize.MEDIUM) ?: originalUrl
                }
                val gridUrl = remember(originalUrl) {
                    originalUrl.withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: originalUrl
                }
                val displayOriginal = showOriginal || isAnimated

                MorphZoomablePage(
                    url = originalUrl,
                    dataUrl = if (displayOriginal) originalUrl else mediumUrl,
                    // 原图优先复用刚看过的中等档位图，否则复用信息流宫格已解码的缩略图，
                    // 实现「缩略图 → 原图」的无缝升级，杜绝白屏与闪烁
                    placeholderUrl = if (displayOriginal && hasShownMedium && mediumUrl != originalUrl) {
                        mediumUrl
                    } else {
                        gridUrl
                    },
                    pageIndex = pageIndex,
                    isCurrentPage = (pagerState.currentPage == pageIndex),
                    onCollapse = { dragOffset, initialVelocity, veilAlpha ->
                        morphState.collapse(
                            scope = scope,
                            initialVelocity = initialVelocity,
                            dragOffset = dragOffset,
                            veilAlpha = veilAlpha
                        )
                        pageDragAlpha = 1f
                        isPageDragging = false
                    },
                    onScaleChanged = { scale ->
                        if (pagerState.currentPage == pageIndex) {
                            currentZoomScale = scale
                        }
                    },
                    onDragStateChanged = { offset, alpha ->
                        pageDragAlpha = alpha
                        isPageDragging = (offset != Offset.Zero)
                    },
                    onLoadError = {
                        if (displayOriginal && !isAnimated) originalLoadFailed = true
                    }
                )
            }
        }

        // 顶部返回按钮与页码指示器 (在 progress > 0.60 展开末期自然渐入，拖拽时按 alpha 渐隐)
        val showUi by remember(morphState) { derivedStateOf { morphState.progress.value > 0.60f } }
        if (showUi) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp)
                    .graphicsLayer {
                        val progressAlpha = ((morphState.progress.value - 0.60f) / 0.40f).coerceIn(0f, 1f)
                        alpha = progressAlpha * pageDragAlpha
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { morphState.collapse(scope) },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (urls.size > 1) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${urls.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // 底部操作栏：查看原图 / 保存（保存始终转存原图档位）
        if (showUi) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp)
                    .graphicsLayer {
                        val progressAlpha = ((morphState.progress.value - 0.60f) / 0.40f).coerceIn(0f, 1f)
                        alpha = progressAlpha * pageDragAlpha
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canLoadOriginal && !showingOriginal) {
                    MorphActionButton(
                        text = "查看原图",
                        filled = true,
                        onClick = { forceOriginal = true }
                    )
                }
                MorphActionButton(
                    text = "保存",
                    filled = false,
                    onClick = { requestSave(currentOriginalUrl) }
                )
            }
        }
    }
}

/**
 * 单页大图触摸与手势处理层：
 * - 1.0x 状态放行横向滑动给父级 Pager 切图
 * - 1.0x 状态支持下滑与上划任意方向跟手拖拽退出，手指释放时弹性飞回原卡片槽位
 * - 双击 1.0x / 2.5x 缩放切换
 * - 双指捏合自由缩放平移
 * - 单击触发收拢形变
 */
@Composable
private fun MorphZoomablePage(
    url: String,
    dataUrl: String,
    placeholderUrl: String,
    pageIndex: Int,
    isCurrentPage: Boolean,
    onCollapse: (dragOffset: Offset, initialVelocity: Float, veilAlpha: Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onDragStateChanged: (Offset, Float) -> Unit,
    onLoadError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(scale) {
        onScaleChanged(scale)
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            dragOffsetX = 0f
            dragOffsetY = 0f
            isDragging = false
            onDragStateChanged(Offset.Zero, 1f)
        }
    }

    // 拖动位移向量长度：任意方向的拖动都参与微缩与背景渐隐计算
    val dragDistance = Offset(dragOffsetX, dragOffsetY).getDistance()
    val dragScale = (1f - (dragDistance / 1800f)).coerceIn(0.65f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onCollapse(Offset.Zero, 0f, 1f)
                    },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touchSlop = viewConfiguration.touchSlop
                    var panAcc = Offset.Zero
                    var pastSlop = false
                    var isDismissDrag = false
                    var isHorizontalSwipe = false
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            // 双指自由缩放
                            val zoomChange = event.calculateZoom()
                            scale = (scale * zoomChange).coerceIn(0.85f, 4.5f)
                            val panChange = event.calculatePan()
                            offsetX += panChange.x
                            offsetY += panChange.y
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val change = event.changes.first()
                            if (change.pressed) {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val panChange = change.positionChange()
                                panAcc += panChange

                                if (scale > 1.05f) {
                                    // 放大状态平移图片
                                    change.consume()
                                    offsetX += panChange.x
                                    offsetY += panChange.y
                                } else {
                                    // 1.0x 状态：先判定主导方向，再决定「退出跟手」还是「放行切图」
                                    if (!pastSlop) {
                                        if (panAcc.getDistance() > touchSlop) {
                                            pastSlop = true
                                            // 仅「接近纯横向」的手势放行给 HorizontalPager 切图；
                                            // 其余任意角度（含斜向、上行）都进入退出跟手，
                                            // 保证不论从哪个角度滑动都能触发退出动画。
                                            val clearlyHorizontal =
                                                kotlin.math.abs(panAcc.x) > kotlin.math.abs(panAcc.y) * 1.6f
                                            if (clearlyHorizontal) {
                                                isHorizontalSwipe = true
                                            } else {
                                                isDismissDrag = true
                                                isDragging = true
                                            }
                                        }
                                    }

                                    if (isDismissDrag) {
                                        change.consume()
                                        // 任意角度完全跟手：水平与垂直分量按 1:1 位移
                                        dragOffsetX += panChange.x
                                        dragOffsetY += panChange.y
                                        val curDistance = Offset(dragOffsetX, dragOffsetY).getDistance()
                                        val curAlpha = (1f - (curDistance / 600f)).coerceIn(0.2f, 1f)
                                        onDragStateChanged(Offset(dragOffsetX, dragOffsetY), curAlpha)
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isDismissDrag) {
                        val velocity = velocityTracker.calculateVelocity()
                        // 以位移 / 速度的向量长度作为判据：任意方向的一甩都能触发退出
                        val releaseDistance = Offset(dragOffsetX, dragOffsetY).getDistance()
                        val releaseSpeed = Offset(velocity.x, velocity.y).getDistance()
                        val shouldDismiss = releaseDistance > 200f || releaseSpeed > 800f

                        if (shouldDismiss) {
                            val finalAlpha = (1f - (releaseDistance / 600f)).coerceIn(0.2f, 1f)
                            onCollapse(Offset(dragOffsetX, dragOffsetY), releaseSpeed, finalAlpha)
                        } else {
                            // 取消拖动：平滑弹性回正
                            scope.launch {
                                val startX = dragOffsetX
                                val startY = dragOffsetY
                                animate(
                                    initialValue = 1f,
                                    targetValue = 0f,
                                    animationSpec = MORPH_SETTLE_SPEC
                                ) { fraction, _ ->
                                    dragOffsetX = startX * fraction
                                    dragOffsetY = startY * fraction
                                    val d = Offset(dragOffsetX, dragOffsetY).getDistance()
                                    val a = (1f - (d / 600f)).coerceIn(0.2f, 1f)
                                    onDragStateChanged(Offset(dragOffsetX, dragOffsetY), a)
                                }
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                isDragging = false
                                onDragStateChanged(Offset.Zero, 1f)
                            }
                        }
                    }

                    if (scale < 1f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 当前档位的数据地址由外层按「大图清晰度」策略决定，
        // placeholderUrl 复用已解码的低档位位图，实现无缝升级。
        val stableRequest = rememberStableImageRequest(dataUrl, placeholderUrl = placeholderUrl)
        AsyncImage(
            model = stableRequest,
            contentDescription = "查看大图",
            contentScale = ContentScale.Fit,
            onState = { state ->
                // 已有缩略图占位时不显示加载指示器，实现“缩略图→原图”的无缝升级
                isLoading = state is AsyncImagePainter.State.Loading && state.painter == null
                if (state is AsyncImagePainter.State.Success) {
                    val w = state.result.drawable.intrinsicWidth
                    val h = state.result.drawable.intrinsicHeight
                    if (w > 0 && h > 0) {
                        val ratio = w.toFloat() / h.toFloat()
                        ImageRatioCache.put(url, ratio)
                    }
                }
                if (state is AsyncImagePainter.State.Error) onLoadError()
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isDragging) {
                        translationX = dragOffsetX
                        translationY = dragOffsetY
                        scaleX = dragScale
                        scaleY = dragScale
                    } else {
                        translationX = offsetX
                        translationY = offsetY
                        scaleX = scale
                        scaleY = scale
                    }
                }
        )

        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * 大图查看器底部胶囊操作按钮：与 ImageViewerScreen 的样式保持一致。
 */
@Composable
private fun MorphActionButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (filled) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = if (filled) Color.Black else Color.White,
            style = MaterialTheme.typography.bodyMediumEmphasized
        )
    }
}
