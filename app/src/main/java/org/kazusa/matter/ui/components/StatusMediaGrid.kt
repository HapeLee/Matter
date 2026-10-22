package org.kazusa.matter.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.kazusa.matter.core.util.WeiboTextFormatter
import org.kazusa.matter.data.model.StatusMediaItem
import org.kazusa.matter.data.model.VideoStreamInfo
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.extractWeiboPicId
import org.kazusa.matter.data.model.withWeiboImageSize
import org.kazusa.matter.ui.media.morph.ImageRatioCache
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.media.morph.rememberStableImageRequest
import org.kazusa.matter.ui.navigation.LocalSharedElementTarget
import org.kazusa.matter.ui.navigation.LocalSharedTransitionSourceActive
import org.kazusa.matter.ui.navigation.formatVideoSharedKey
import org.kazusa.matter.ui.navigation.weiboPostImageSharedElement
import org.kazusa.matter.ui.navigation.weiboVideoSharedBounds

/** 单图的圆角（大图更接近微博客户端的 6dp） */
private val SingleMediaShape = RoundedCornerShape(6.dp)

/** 宫格单元的圆角 */
private val GridMediaShape = RoundedCornerShape(4.dp)

/** 视频卡片（整行）的圆角 */
private val VideoCardShape = RoundedCornerShape(8.dp)

/**
 * 信息流媒体区实际请求的地址：降到缩略图档位（约 440px），原图仅在查看器按需加载。
 *
 * 动图（.gif）保持原档位，避免降档后丢失动画质量；非微博 CDN 图片由
 * [withWeiboImageSize] 自行原样返回。
 */
private fun gridDisplayUrl(rawUrl: String): String {
    if (rawUrl.contains(".gif", ignoreCase = true)) return rawUrl
    return rawUrl.withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: rawUrl
}

/**
 * 视频时长角标文案：`125` -> `2:05`，`3670` -> `1:01:10`。
 */
internal fun formatVideoDurationLabel(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainSeconds = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainSeconds.toString().padStart(2, '0')}"
    }
}

/**
 * 视频标题兜底：服务端没下发 `page_title` 时，用正文纯文本前 [maxLength] 字。
 */
internal fun WeiboStatusDto.videoTitleFallback(maxLength: Int = 20): String =
    WeiboTextFormatter.formatHtmlText(textRaw ?: text).take(maxLength)

/**
 * 微博媒体区：图片与视频在同一套宫格里按服务端顺序混排。
 *
 * - 1 项：图片走单图自适应（不限比例 + 尺寸边界）；视频走整行封面卡
 * - 2 项：单行 2 列（宽 80%）
 * - 4 项：经典 2x2 田字格（宽 75%）
 * - 3、5~9 项：3 列九宫格，超出 9 项时第 9 格叠加 "+N" 蒙层
 *
 * 视频格子与图片格子同尺寸（正方形），封面 + 播放按钮 + 时长角标，
 * 点击进入播放器并与卡片/正文的对应格子做形变转场。
 */
@Composable
fun StatusMediaGrid(
    items: List<StatusMediaItem>,
    modifier: Modifier = Modifier,
    /**
     * 当前宫格所属卡片的唯一标识（微博卡片 key / 正文页 key）。
     * 转载了相同图片的两条微博必须使用不同标识，否则形变进出会张冠李戴。
     */
    ownerKey: String = "",
    /** 卡片到正文的共享容器；独立于图片查看器的 ownerKey。 */
    sharedContainerKey: String = "",
    sharedContainerOwnerKey: String? = null,
    sharedImagePart: String = "image",
    livePhotoMap: Map<String, String> = emptyMap(),
    /** 只有单个视频时的整行高度（信息流 / 正文各自保持原有尺寸） */
    singleVideoHeight: Dp = 190.dp,
    /** 视频标题缺失时的兜底文案（通常是正文前若干字） */
    videoTitleFallback: String = "",
    onImageClick: (urls: List<String>, index: Int) -> Unit = { _, _ -> },
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    if (items.isEmpty()) return

    // 图片查看器 / 共享元素只认图片，视频格子不参与图片形变
    val pictures = remember(items) {
        items.mapNotNull { (it as? StatusMediaItem.Picture)?.previewUrl }
    }
    // 宫格下标 -> 图片列表下标（视频为 -1），保证形变与查看器的索引一致
    val pictureIndexOf = remember(items) {
        IntArray(items.size).also { mapping ->
            var next = 0
            items.forEachIndexed { index, item ->
                mapping[index] = if (item is StatusMediaItem.Picture) next++ else -1
            }
        }
    }

    @Composable
    fun imageSharedModifier(index: Int): Modifier = Modifier.weiboPostImageSharedElement(
        containerKey = sharedContainerKey,
        index = index,
        shape = if (items.size == 1) SingleMediaShape else GridMediaShape,
        ownerKey = sharedContainerOwnerKey,
        part = sharedImagePart
    )

    val morphState = LocalImageMorphState.current
    val scope = rememberCoroutineScope()

    // 同一条微博可能同时出现在相邻分页（「动态」与「热门」）里，两份宫格共用同一个卡片 key：
    // 非当前可见页的那份不登记形变槽位，否则收起动画会飞向屏幕外那张卡片的坐标。
    val morphOwnerKey = if (LocalSharedTransitionSourceActive.current) ownerKey else ""

    val slotBoundsMap = remember(items) { mutableMapOf<Int, Rect>() }
    val slotRadiusMap = remember(items) { mutableMapOf<Int, Float>() }

    val handleImageClick: (gridIndex: Int, Rect, Float) -> Unit = { gridIndex, bounds, cornerRadiusPx ->
        val pictureIndex = pictureIndexOf[gridIndex]
        if (pictureIndex >= 0 && morphState != null && !bounds.isEmpty) {
            slotBoundsMap[pictureIndex] = bounds
            slotRadiusMap[pictureIndex] = cornerRadiusPx
            val url = pictures[pictureIndex]
            val ratio = ImageRatioCache.get(url) ?: if (bounds.height > 0) bounds.width / bounds.height else 1f
            morphState.open(
                ownerKey = morphOwnerKey,
                urls = pictures,
                index = pictureIndex,
                bounds = bounds,
                cornerRadiusPx = cornerRadiusPx,
                imageRatio = ratio,
                allBounds = slotBoundsMap,
                allRadii = slotRadiusMap,
                livePhotoMap = livePhotoMap,
                scope = scope
            )
        } else if (pictureIndex >= 0) {
            onImageClick(pictures, pictureIndex)
        }
    }

    @Composable
    fun mediaCell(index: Int, cellModifier: Modifier, extraOverlayText: String? = null) {
        when (val item = items[index]) {
            is StatusMediaItem.Picture -> {
                val pictureIndex = pictureIndexOf[index]
                GridImageItem(
                    imageUrl = item.previewUrl,
                    images = pictures,
                    index = pictureIndex,
                    ownerKey = morphOwnerKey,
                    modifier = cellModifier.then(imageSharedModifier(pictureIndex)),
                    livePhotoMap = livePhotoMap,
                    extraOverlayText = extraOverlayText,
                    onPositionReported = { b, r ->
                        slotBoundsMap[pictureIndex] = b
                        slotRadiusMap[pictureIndex] = r
                    },
                    onClick = { bounds, radius -> handleImageClick(index, bounds, radius) }
                )
            }
            is StatusMediaItem.Video -> VideoGridCell(
                video = item,
                modifier = cellModifier,
                fallbackTitle = videoTitleFallback,
                onVideoClick = onVideoClick
            )
        }
    }

    when (items.size) {
        1 -> {
            when (val only = items[0]) {
                is StatusMediaItem.Picture -> SingleImageView(
                    imageUrl = only.previewUrl,
                    images = pictures,
                    index = 0,
                    ownerKey = morphOwnerKey,
                    modifier = modifier.then(imageSharedModifier(0)),
                    livePhotoMap = livePhotoMap,
                    onPositionReported = { b, r ->
                        slotBoundsMap[0] = b
                        slotRadiusMap[0] = r
                    },
                    onClick = { bounds, radius -> handleImageClick(0, bounds, radius) }
                )
                is StatusMediaItem.Video -> VideoMediaCard(
                    video = only,
                    modifier = modifier,
                    height = singleVideoHeight,
                    fallbackTitle = videoTitleFallback,
                    onVideoClick = onVideoClick
                )
            }
        }
        2 -> {
            // 2 项：并排两列
            Row(
                modifier = modifier.fillMaxWidth(0.8f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(2) { index ->
                    mediaCell(index, Modifier.weight(1f))
                }
            }
        }
        4 -> {
            // 4 项：2x2 田字格
            Column(
                modifier = modifier.fillMaxWidth(0.75f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(2) { rowIndex ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { colIndex ->
                            mediaCell(rowIndex * 2 + colIndex, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        else -> {
            // 3、5~9 项及以上九宫格
            val visibleCount = items.size.coerceAtMost(9)
            val rows = (0 until visibleCount).chunked(3)
            val extraCount = items.size - 9

            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rows.forEach { rowIndices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowIndices.forEach { index ->
                            val isLastOfNine = extraCount > 0 && index == 8
                            mediaCell(
                                index = index,
                                cellModifier = Modifier.weight(1f),
                                extraOverlayText = if (isLastOfNine) "+$extraCount" else null
                            )
                        }
                        // 补齐空白格保持对齐
                        repeat(3 - rowIndices.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 整行视频卡片（单个视频时使用，保持信息流 / 正文原有观感）。
 */
@Composable
private fun VideoMediaCard(
    video: StatusMediaItem.Video,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    fallbackTitle: String = "",
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    val sharedElementTarget = LocalSharedElementTarget.current
    val sharedKey = remember(video.mid, video.videoUrl) { formatVideoSharedKey(video.mid, video.videoUrl) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .weiboVideoSharedBounds(key = sharedKey, shape = VideoCardShape)
            .clip(VideoCardShape)
            .background(Color.Black)
            .clickable {
                sharedElementTarget?.selectVideo(sharedKey)
                onVideoClick(video.videoUrl, video.title ?: fallbackTitle, video.streams, video.previewUrl, video.mid)
            },
        contentAlignment = Alignment.Center
    ) {
        if (video.previewUrl.isNotBlank()) {
            AsyncImage(
                model = video.previewUrl,
                contentDescription = "视频封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        PlayBadge(size = 52.dp, iconSize = 32.dp)
        formatVideoDurationLabel(video.durationSeconds)?.let { label ->
            MatterMediaBadge(
                text = label,
                containerColor = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
            )
        }
    }
}

/**
 * 宫格里的视频格子：与图片格子等大，封面 + 播放按钮 + 时长角标。
 */
@Composable
private fun VideoGridCell(
    video: StatusMediaItem.Video,
    modifier: Modifier = Modifier,
    fallbackTitle: String = "",
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    val sharedElementTarget = LocalSharedElementTarget.current
    val sharedKey = remember(video.mid, video.videoUrl) { formatVideoSharedKey(video.mid, video.videoUrl) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .weiboVideoSharedBounds(key = sharedKey, shape = GridMediaShape)
            .clip(GridMediaShape)
            .background(Color.Black)
            .clickable {
                sharedElementTarget?.selectVideo(sharedKey)
                onVideoClick(video.videoUrl, video.title ?: fallbackTitle, video.streams, video.previewUrl, video.mid)
            },
        contentAlignment = Alignment.Center
    ) {
        if (video.previewUrl.isNotBlank()) {
            AsyncImage(
                model = video.previewUrl,
                contentDescription = "视频封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        PlayBadge(size = 34.dp, iconSize = 22.dp)
        formatVideoDurationLabel(video.durationSeconds)?.let { label ->
            MatterMediaBadge(
                text = label,
                containerColor = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun PlayBadge(size: Dp, iconSize: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放视频",
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 单图自适应布局：
 * 不限纵横比，但约束最大宽度与高度，支持长图局部预览与角标
 */
@Composable
private fun SingleImageView(
    imageUrl: String,
    images: List<String>,
    index: Int,
    ownerKey: String,
    modifier: Modifier = Modifier,
    livePhotoMap: Map<String, String> = emptyMap(),
    onPositionReported: (Rect, Float) -> Unit = { _, _ -> },
    onClick: (Rect, Float) -> Unit = { _, _ -> }
) {
    val morphState = LocalImageMorphState.current
    var imageRatio by remember(imageUrl) {
        mutableStateOf(ImageRatioCache.get(imageUrl))
    }

    var itemBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { 6.dp.toPx() }

    val isLivePhoto = remember(imageUrl, livePhotoMap) {
        livePhotoMap.containsKey(imageUrl) || extractWeiboPicId(imageUrl)?.let { livePhotoMap.containsKey(it) } == true
    }

    val isGif = remember(imageUrl) {
        imageUrl.contains(".gif", ignoreCase = true)
    }

    // 单图尺寸边界约束
    val maxWidth = 250.dp
    val maxHeight = 270.dp
    val minWidth = 110.dp
    val minHeight = 90.dp

    val ratio = imageRatio
    val isLongImage = ratio != null && ratio < 0.5f

    // 动态计算宽高与对齐裁切模式
    val sizeCalc = remember(ratio) {
        when {
            ratio == null -> {
                // 尚未获取原图尺寸时的稳态占位尺寸
                SizeCalculation(
                    width = 220.dp,
                    height = 165.dp,
                    scale = ContentScale.Crop,
                    alignment = Alignment.Center
                )
            }
            ratio < 0.5f -> {
                // 长图（比例极高，如长截图、条漫等）：限制高度，固定舒适宽度，顶部对齐
                SizeCalculation(
                    width = 160.dp,
                    height = maxHeight,
                    scale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
            ratio in 0.5f..<1.0f -> {
                // 纵向图：高度撑到上限，宽度根据比例自适应
                val w = (maxHeight * ratio).coerceIn(minWidth, maxWidth)
                SizeCalculation(
                    width = w,
                    height = maxHeight,
                    scale = ContentScale.Crop,
                    alignment = Alignment.Center
                )
            }
            else -> {
                // 横向图或方图：宽度撑满上限，高度根据比例自适应
                val calculatedH = maxWidth / ratio
                val h = calculatedH.coerceIn(minHeight, maxHeight)
                SizeCalculation(
                    width = maxWidth,
                    height = h,
                    scale = if (calculatedH < minHeight) ContentScale.Crop else ContentScale.Fit,
                    alignment = Alignment.Center
                )
            }
        }
    }

    val displayUrl = remember(imageUrl) { gridDisplayUrl(imageUrl) }
    val imageRequest = rememberStableImageRequest(displayUrl)

    Box(
        modifier = modifier
            .width(sizeCalc.width)
            .height(sizeCalc.height)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                itemBounds = bounds
                onPositionReported(bounds, cornerRadiusPx)
                morphState?.registerSlot(ownerKey, images, index, bounds, cornerRadiusPx, imageRatio)
            }
            .clip(SingleMediaShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer {
                // 在绘制阶段读取 morph 进度，避免形变动画期间逐帧重组缩略图
                alpha = if (morphState?.isSlotHidden(ownerKey, images, index) == true) 0f else 1f
            }
            .clickable { onClick(itemBounds, cornerRadiusPx) }
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = "微博图片",
            contentScale = sizeCalc.scale,
            alignment = sizeCalc.alignment,
            onSuccess = { state ->
                val w = state.result.image.width
                val h = state.result.image.height
                if (w > 0 && h > 0) {
                    val computedRatio = w.toFloat() / h.toFloat()
                    imageRatio = computedRatio
                    ImageRatioCache.put(imageUrl, computedRatio)
                    morphState?.registerSlot(ownerKey, images, index, itemBounds, cornerRadiusPx, computedRatio)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 动图 / 长图 / 实况角标提示
        val badgeText = when {
            isLivePhoto -> "实况"
            isGif -> "动图"
            isLongImage -> "长图"
            else -> null
        }

        if (badgeText != null) {
            MatterMediaBadge(
                text = badgeText,
                containerColor = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 6.dp)
            )
        }
    }
}

private data class SizeCalculation(
    val width: Dp,
    val height: Dp,
    val scale: ContentScale,
    val alignment: Alignment
)

@Composable
private fun GridImageItem(
    imageUrl: String,
    images: List<String>,
    index: Int,
    ownerKey: String,
    modifier: Modifier = Modifier,
    livePhotoMap: Map<String, String> = emptyMap(),
    extraOverlayText: String? = null,
    onPositionReported: (Rect, Float) -> Unit = { _, _ -> },
    onClick: (Rect, Float) -> Unit = { _, _ -> }
) {
    val morphState = LocalImageMorphState.current
    var itemBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { 4.dp.toPx() }

    val isLivePhoto = remember(imageUrl, livePhotoMap) {
        livePhotoMap.containsKey(imageUrl) || extractWeiboPicId(imageUrl)?.let { livePhotoMap.containsKey(it) } == true
    }

    val isGif = remember(imageUrl) {
        imageUrl.contains(".gif", ignoreCase = true)
    }

    val displayUrl = remember(imageUrl) { gridDisplayUrl(imageUrl) }
    val imageRequest = rememberStableImageRequest(displayUrl)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                itemBounds = bounds
                onPositionReported(bounds, cornerRadiusPx)
                val cachedRatio = ImageRatioCache.get(imageUrl)
                morphState?.registerSlot(ownerKey, images, index, bounds, cornerRadiusPx, cachedRatio)
            }
            .clip(GridMediaShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer {
                // 在绘制阶段读取 morph 进度，避免形变动画期间逐帧重组缩略图
                alpha = if (morphState?.isSlotHidden(ownerKey, images, index) == true) 0f else 1f
            }
            .clickable { onClick(itemBounds, cornerRadiusPx) },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = "微博图片",
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                val w = state.result.image.width
                val h = state.result.image.height
                if (w > 0 && h > 0) {
                    val computedRatio = w.toFloat() / h.toFloat()
                    ImageRatioCache.put(imageUrl, computedRatio)
                    morphState?.registerSlot(ownerKey, images, index, itemBounds, cornerRadiusPx, computedRatio)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 超出 9 项时的蒙层
        if (extraOverlayText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = extraOverlayText,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLargeEmphasized
                )
            }
        } else if (isLivePhoto || isGif) {
            MatterMediaBadge(
                text = if (isLivePhoto) "实况" else "动图",
                containerColor = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
            )
        }
    }
}
