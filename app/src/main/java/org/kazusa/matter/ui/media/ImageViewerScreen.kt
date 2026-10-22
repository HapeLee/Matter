package org.kazusa.matter.ui.media

import androidx.compose.foundation.shape.CircleShape


import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.kazusa.matter.ui.media.morph.ImageRatioCache
import kotlin.math.max
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kazusa.matter.core.datastore.ImageQualityMode
import org.kazusa.matter.core.datastore.MediaPreferenceState
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.util.ImageProbeResult
import org.kazusa.matter.core.util.ImageSaver
import org.kazusa.matter.core.util.NetworkMonitor
import org.kazusa.matter.core.util.WeiboImageProbe
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.withWeiboImageSize
import org.kazusa.matter.ui.media.morph.rememberStableImageRequest
import org.kazusa.matter.ui.media.morph.morphThumbnailUrl
import org.kazusa.matter.ui.navigation.weiboSharedElement
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.imageLoader
import coil3.memory.MemoryCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch

/**
 * 微博沉浸式大图查看器：
 * 1. 共享元素放大动画：支持从信息流配图位置自然放大入场与缩拢回退
 * 2. 多图横向滑动切换：使用 HorizontalPager 支持自然左滑/右滑切图
 * 3. 智能手势分发：未放大时放行横向滑动手势，双指捏合缩放，双击 1.0x / 2.5x 切换
 * 4. 下拉跟手退出：在 1.0x 状态下单指向下拖拽，支持阻尼位移、微缩与背景渐隐，松手智能回弹或退出
 * 5. 轻触背景/图片退出：单击任意位置舒适退出查看大图
 *
 * 清晰度自适应（省流量）：
 * - 信息流宫格只缓存缩略图档位，查看器打开时先用它做占位，杜绝白屏与闪烁；
 * - 是否自动加载原图由「大图清晰度」设置 + 当前网络是否计费共同决定；
 * - 未自动加载时提供「查看原图」按钮，加载失败会提示并回退到中等档位；
 * - 「保存原图」直接转存原图档位字节，不做二次压缩。
 */
@Composable
fun ImageViewerScreen(
    initialIndex: Int,
    urls: List<String>,
    mediaPreferences: MediaPreferences,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (urls.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaState by mediaPreferences.mediaStateFlow.collectAsStateWithLifecycle(
        initialValue = MediaPreferenceState()
    )

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, urls.size - 1),
        pageCount = { urls.size }
    )

    var currentScale by remember { mutableFloatStateOf(1f) }
    var currentZoomController by remember { mutableStateOf<ZoomController?>(null) }
    val dismissOffsetY = remember { Animatable(0f) }

    PredictiveBackHandler(enabled = true) { events ->
        events.collect { }
        onBackClick()
    }

    // ---- 清晰度策略 ----
    // 打开查看器时定格网络状态，避免浏览过程中网络切换导致清晰度反复跳变
    val unmeteredNetwork = remember { NetworkMonitor.isUnmetered(context) }
    val autoOriginal = remember(mediaState.imageQualityMode, unmeteredNetwork) {
        when (mediaState.imageQualityMode) {
            ImageQualityMode.ALWAYS_ORIGINAL -> true
            ImageQualityMode.WIFI_ORIGINAL -> unmeteredNetwork
            ImageQualityMode.THUMBNAIL_ONLY -> false
        }
    }
    var forceOriginalUrls by remember { mutableStateOf(setOf<String>()) }
    var probeResults by remember { mutableStateOf(mapOf<String, ImageProbeResult>()) }
    var originalDisabled by remember { mutableStateOf(false) }
    var originalLoadFailed by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // 探测当前页与前后相邻页的清晰度档位及文件体积
    LaunchedEffect(pagerState.currentPage, urls) {
        val currentUrl = urls.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (currentUrl !in probeResults) {
            val result = WeiboImageProbe.probe(currentUrl)
            probeResults = probeResults + (currentUrl to result)
        }
        val prevUrl = urls.getOrNull(pagerState.currentPage - 1)
        if (prevUrl != null && prevUrl !in probeResults) {
            launch {
                val result = WeiboImageProbe.probe(prevUrl)
                probeResults = probeResults + (prevUrl to result)
            }
        }
        val nextUrl = urls.getOrNull(pagerState.currentPage + 1)
        if (nextUrl != null && nextUrl !in probeResults) {
            launch {
                val result = WeiboImageProbe.probe(nextUrl)
                probeResults = probeResults + (nextUrl to result)
            }
        }
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
        coroutineScope.launch {
            val result = ImageSaver.saveImage(context, imageUrl)
            toastMessage = result.fold(
                { name -> "图片已保存至相册：$name" },
                { error -> "图片保存失败：${error.message ?: "未知错误"}" }
            )
        }
    }

    // 保存进相册走 MediaStore 沙箱，无需任何存储权限（minSdk 31 已不再需要 WRITE_EXTERNAL_STORAGE）。
    val requestSave: (String) -> Unit = { imageUrl ->
        startSave(imageUrl)
    }

    // 根据下拉拖拽进度计算背景透明度与微缩比例
    val bgAlpha = (1f - (dismissOffsetY.value / 700f)).coerceIn(0.2f, 1f)
    val dismissScale = (1f - (dismissOffsetY.value / 2400f)).coerceIn(0.75f, 1f)

    // 当前页地址与状态
    val currentOriginalUrl = urls[pagerState.currentPage.coerceIn(0, urls.lastIndex)]
    val currentProbe = probeResults[currentOriginalUrl] ?: WeiboImageProbe.getCached(currentOriginalUrl)
    val currentTargetUrl = currentProbe?.targetUrl ?: (currentOriginalUrl.withWeiboImageSize(WeiboImageSize.ORIGINAL) ?: currentOriginalUrl)
    val currentIsOversized = currentProbe?.isOversized ?: false
    val currentForced = currentOriginalUrl in forceOriginalUrls
    val currentIsAnimated = currentOriginalUrl.contains(".gif", ignoreCase = true)
    val currentShouldAuto = if (currentIsOversized) {
        false
    } else if (currentIsAnimated) {
        true
    } else {
        autoOriginal
    }
    val currentShowingOriginal = (currentShouldAuto || currentForced) && !originalDisabled
    val currentFormattedSize = currentProbe?.formattedSize

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
    ) {
        // 多图横向画廊（仅在未放大状态下允许横向切图，避免双指缩放拖动冲突）
        HorizontalPager(
            state = pagerState,
            key = { pageIndex -> urls.getOrElse(pageIndex) { pageIndex.toString() } },
            userScrollEnabled = (currentScale <= 1.05f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    translationY = dismissOffsetY.value,
                    scaleX = dismissScale,
                    scaleY = dismissScale
                )
        ) { pageIndex ->
            val originalUrl = urls[pageIndex]
            val isAnimated = originalUrl.contains(".gif", ignoreCase = true)
            val mediumUrl = remember(originalUrl) {
                originalUrl.withWeiboImageSize(WeiboImageSize.MEDIUM) ?: originalUrl
            }
            val gridUrl = remember(originalUrl) {
                originalUrl.morphThumbnailUrl()
            }

            val probe = probeResults[originalUrl] ?: WeiboImageProbe.getCached(originalUrl)
            val targetOriginalUrl = probe?.targetUrl ?: (originalUrl.withWeiboImageSize(WeiboImageSize.ORIGINAL) ?: originalUrl)
            val isOversized = probe?.isOversized ?: false
            val isForced = originalUrl in forceOriginalUrls

            val shouldAuto = if (isOversized) {
                false
            } else if (isAnimated) {
                true
            } else {
                autoOriginal
            }
            val displayOriginal = (shouldAuto || isForced) && !originalDisabled

            val isMediumCached = remember(mediumUrl) {
                context.imageLoader.memoryCache?.get(MemoryCache.Key(mediumUrl)) != null
            }

            ZoomableImagePage(
                url = originalUrl,
                dataUrl = if (displayOriginal) targetOriginalUrl else mediumUrl,
                // 原图优先复用刚看过的中等档位图（若在内存缓存中），否则复用信息流宫格已解码的缩略图
                placeholderUrl = if (displayOriginal && isMediumCached && mediumUrl != targetOriginalUrl) {
                    mediumUrl
                } else {
                    gridUrl
                },
                onBackClick = onBackClick,
                onDismissDrag = { dy ->
                    coroutineScope.launch {
                        dismissOffsetY.snapTo((dismissOffsetY.value + dy).coerceAtLeast(0f))
                    }
                },
                onDismissRelease = { shouldDismiss ->
                    coroutineScope.launch {
                        if (shouldDismiss || dismissOffsetY.value > 220f) {
                            onBackClick()
                        } else {
                            dismissOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                },
                onScaleChanged = { scale ->
                    if (pagerState.currentPage == pageIndex) {
                        currentScale = scale
                    }
                },
                onZoomControllerChanged = { controller ->
                    if (pagerState.currentPage == pageIndex) {
                        currentZoomController = controller
                    }
                },
                onLoadError = {
                    if (displayOriginal && !isAnimated) originalLoadFailed = true
                }
            )
        }

        // 顶部操作栏与页码指示器 (随下拉手势同步渐隐)
        val uiAlpha = (1f - (dismissOffsetY.value / 300f)).coerceIn(0f, 1f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp)
                .graphicsLayer(alpha = uiAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
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

        // 底部操作栏：查看原图 / 保存
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .graphicsLayer(alpha = uiAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!currentShowingOriginal) {
                val buttonText = when {
                    currentIsOversized && currentFormattedSize != null -> "查看超大原图 ($currentFormattedSize)"
                    currentFormattedSize != null -> if (currentIsAnimated) "查看动图 ($currentFormattedSize)" else "查看原图 ($currentFormattedSize)"
                    currentIsAnimated -> "查看动图"
                    else -> "查看原图"
                }
                ViewerActionButton(
                    text = buttonText,
                    filled = true,
                    onClick = { forceOriginalUrls = forceOriginalUrls + currentOriginalUrl }
                )
            }
            // 保存进相册始终使用探测到的最高画质原图档位，不做二次压缩
            ViewerActionButton(
                text = "保存",
                filled = false,
                onClick = { requestSave(currentTargetUrl) }
            )
        }
    }
}

@Composable
private fun ViewerActionButton(
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

/**
 * 支持手势分发的单页高分辨率图片呈现层：
 * - 单击：退出大图
 * - 双击：1.0x / 2.5x 智能缩放切换
 * - 双指捏合：0.9x ~ 4.5x 任意平滑缩放
 * - 放大平移：单指平移图片细节
 * - 下拉跟手：在 1.0x 状态向下拖拽触发退出
 * - 横向滑动：在 1.0x 状态完全放行给 HorizontalPager 进行切图
 */
@Composable
private fun ZoomableImagePage(
    url: String,
    dataUrl: String,
    placeholderUrl: String,
    onBackClick: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissRelease: (Boolean) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onZoomControllerChanged: (ZoomController?) -> Unit,
    onLoadError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageRatio by remember(url) { mutableFloatStateOf(ImageRatioCache.get(url) ?: 0f) }
    var isLoading by remember { mutableStateOf(true) }

    val zoomController = remember(scope) {
        DefaultZoomController(
            scope = scope,
            scaleAnim = scaleAnim,
            offsetXAnim = offsetXAnim,
            offsetYAnim = offsetYAnim
        )
    }

    androidx.compose.runtime.DisposableEffect(zoomController) {
        onZoomControllerChanged(zoomController)
        onDispose {
            onZoomControllerChanged(null)
        }
    }

    LaunchedEffect(scaleAnim.value) {
        onScaleChanged(scaleAnim.value)
    }

    val getCurW = { if (containerSize.width > 0) containerSize.width.toFloat() else 1080f }
    val getCurH = { if (containerSize.height > 0) containerSize.height.toFloat() else 2400f }
    val getCurRatio = { if (imageRatio > 0f) imageRatio else (ImageRatioCache.get(url) ?: (getCurW() / getCurH())) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onBackClick() },
                    onDoubleTap = { tapPosition ->
                        if (scaleAnim.value > 1.05f) {
                            ZoomableImageCalculator.animateZoom(
                                scope = scope,
                                scaleAnim = scaleAnim,
                                offsetXAnim = offsetXAnim,
                                offsetYAnim = offsetYAnim,
                                targetScale = 1f,
                                targetOffset = Offset.Zero
                            )
                        } else {
                            val cW = getCurW()
                            val cH = getCurH()
                            val r = getCurRatio()
                            val targetScale = ZoomableImageCalculator.calculateTargetDoubleTapScale(
                                containerWidth = cW,
                                containerHeight = cH,
                                imageRatio = r
                            )
                            val targetOffset = ZoomableImageCalculator.calculateDoubleTapTargetOffset(
                                tapPosition = tapPosition,
                                containerWidth = cW,
                                containerHeight = cH,
                                imageRatio = r,
                                targetScale = targetScale
                            )
                            ZoomableImageCalculator.animateZoom(
                                scope = scope,
                                scaleAnim = scaleAnim,
                                offsetXAnim = offsetXAnim,
                                offsetYAnim = offsetYAnim,
                                targetScale = targetScale,
                                targetOffset = targetOffset
                            )
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pastSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var panAcc = Offset.Zero
                    var isDismissDrag = false
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    val maxScale = max(
                        4.5f,
                        ZoomableImageCalculator.calculateTargetDoubleTapScale(
                            containerWidth = getCurW(),
                            containerHeight = getCurH(),
                            imageRatio = getCurRatio()
                        ) * 2f
                    )

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            // 双指捏合缩放手势
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scaleAnim.value * zoomChange).coerceIn(0.85f, maxScale * 1.25f)
                            val (maxOffsetX, maxOffsetY) = ZoomableImageCalculator.calculateMaxPanOffset(
                                containerWidth = getCurW(),
                                containerHeight = getCurH(),
                                imageRatio = getCurRatio(),
                                currentScale = newScale
                            )
                            val targetX = (offsetXAnim.value + panChange.x).coerceIn(-maxOffsetX - 150f, maxOffsetX + 150f)
                            val targetY = (offsetYAnim.value + panChange.y).coerceIn(-maxOffsetY - 150f, maxOffsetY + 150f)
                            scope.launch {
                                scaleAnim.snapTo(newScale)
                                offsetXAnim.snapTo(targetX)
                                offsetYAnim.snapTo(targetY)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val change = event.changes.first()
                            if (change.pressed) {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val panChange = change.positionChange()
                                panAcc += panChange

                                if (scaleAnim.value > 1.05f) {
                                    // 放大状态下自由平移图片细节
                                    change.consume()
                                    val (maxOffsetX, maxOffsetY) = ZoomableImageCalculator.calculateMaxPanOffset(
                                        containerWidth = getCurW(),
                                        containerHeight = getCurH(),
                                        imageRatio = getCurRatio(),
                                        currentScale = scaleAnim.value
                                    )
                                    val targetX = (offsetXAnim.value + panChange.x).coerceIn(-maxOffsetX - 120f, maxOffsetX + 120f)
                                    val targetY = (offsetYAnim.value + panChange.y).coerceIn(-maxOffsetY - 120f, maxOffsetY + 120f)
                                    scope.launch {
                                        offsetXAnim.snapTo(targetX)
                                        offsetYAnim.snapTo(targetY)
                                    }
                                } else {
                                    // 1.0x 状态：根据手势方向智能分流
                                    if (!pastSlop) {
                                        if (panAcc.getDistance() > touchSlop) {
                                            pastSlop = true
                                            // 垂直分量明显大于水平分量且为下拉
                                            if (kotlin.math.abs(panAcc.y) > kotlin.math.abs(panAcc.x) * 1.3f && panAcc.y > 0) {
                                                isDismissDrag = true
                                            }
                                        }
                                    }

                                    if (isDismissDrag) {
                                        change.consume()
                                        onDismissDrag(panChange.y)
                                    }
                                    // 若不是垂直下拉（如左右横滑），绝对不调用 consume()，完全放行给 HorizontalPager 切图！
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // 手指抬起处理
                    if (isDismissDrag) {
                        onDismissRelease(panAcc.y > 180f)
                    }

                    if (scaleAnim.value < 1f) {
                        ZoomableImageCalculator.animateZoom(
                            scope = scope,
                            scaleAnim = scaleAnim,
                            offsetXAnim = offsetXAnim,
                            offsetYAnim = offsetYAnim,
                            targetScale = 1f,
                            targetOffset = Offset.Zero
                        )
                    } else if (scaleAnim.value > maxScale) {
                        scope.launch {
                            scaleAnim.animateTo(maxScale, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    } else if (scaleAnim.value > 1.05f) {
                        // 放大状态下的手指抬起：惯性 fling 平移与越界平滑回弹
                        val velocity = velocityTracker.calculateVelocity()
                        val (maxOffsetX, maxOffsetY) = ZoomableImageCalculator.calculateMaxPanOffset(
                            containerWidth = getCurW(),
                            containerHeight = getCurH(),
                            imageRatio = getCurRatio(),
                            currentScale = scaleAnim.value
                        )
                        ZoomableImageCalculator.flingWithInertia(
                            scope = scope,
                            animatable = offsetXAnim,
                            initialVelocity = velocity.x,
                            maxBound = maxOffsetX
                        )
                        ZoomableImageCalculator.flingWithInertia(
                            scope = scope,
                            animatable = offsetYAnim,
                            initialVelocity = velocity.y,
                            maxBound = maxOffsetY
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        val isDataCached = remember(dataUrl) {
            context.imageLoader.memoryCache?.get(MemoryCache.Key(dataUrl)) != null
        }
        val highResAlpha = remember(dataUrl) {
            Animatable(if (isDataCached) 1f else 0f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weiboSharedElement("image-$url")
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                },
            contentAlignment = Alignment.Center
        ) {
            // 第一层：稳态缩略图垫底层（Base Thumbnail Layer）
            if (highResAlpha.value < 1f && placeholderUrl != dataUrl) {
                val baseRequest = rememberStableImageRequest(dataUrl = placeholderUrl)
                AsyncImage(
                    model = baseRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 第二层：高清大图/原图顶层（High-Res Overlay Layer）
            val imageRequest = rememberStableImageRequest(dataUrl, placeholderUrl)
            AsyncImage(
                model = imageRequest,
                contentDescription = "查看大图",
                contentScale = ContentScale.Fit,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading && highResAlpha.value == 0f && placeholderUrl == dataUrl
                    if (state is AsyncImagePainter.State.Success) {
                        val w = state.result.image.width
                        val h = state.result.image.height
                        if (w > 0 && h > 0) {
                            val ratio = w.toFloat() / h.toFloat()
                            imageRatio = ratio
                            ImageRatioCache.put(url, ratio)
                        }
                        if (highResAlpha.value < 1f) {
                            scope.launch {
                                highResAlpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                    }
                    if (state is AsyncImagePainter.State.Error) onLoadError()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = highResAlpha.value
                    }
            )
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
