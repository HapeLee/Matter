package org.kazusa.matter.ui.components

import android.util.LruCache
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.extractWeiboPicId
import org.kazusa.matter.data.model.withWeiboImageSize
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.media.morph.rememberStableImageRequest

import org.kazusa.matter.ui.media.morph.ImageRatioCache

/**
 * 信息流宫格实际请求的地址：降到缩略图档位（约 440px），原图仅在查看器按需加载。
 *
 * 动图（.gif）保持原档位，避免降档后丢失动画质量；非微博 CDN 图片由
 * [withWeiboImageSize] 自行原样返回。
 */
private fun gridDisplayUrl(rawUrl: String): String {
    if (rawUrl.contains(".gif", ignoreCase = true)) return rawUrl
    return rawUrl.withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: rawUrl
}

/**
 * 微博多图自适应网格：
 * - 1 张图：不限制原图比例，但限制最大/最小尺寸边界展示；长图顶部对齐并提示角标；微圆角 6dp
 * - 2 张图：单行 2 列并排，微圆角 4dp
 * - 3 张图：单行 3 列并排
 * - 4 张图：经典 2x2 田字格 (宽 75%)
 * - 5~9 张图：3 列九宫格自适应
 * - 超出 9 张图：展示前 9 张，第 9 张叠加 "+N" 蒙层提示
 */
@Composable
fun ImageGrid(
    images: List<String>,
    modifier: Modifier = Modifier,
    livePhotoMap: Map<String, String> = emptyMap(),
    onImageClick: (Int) -> Unit = {}
) {
    if (images.isEmpty()) return

    val totalCount = images.size
    val morphState = LocalImageMorphState.current
    val scope = rememberCoroutineScope()

    val slotBoundsMap = remember(images) { mutableMapOf<Int, Rect>() }
    val slotRadiusMap = remember(images) { mutableMapOf<Int, Float>() }

    val handleImageClick: (Int, Rect, Float) -> Unit = { index, bounds, cornerRadiusPx ->
        if (morphState != null && !bounds.isEmpty) {
            slotBoundsMap[index] = bounds
            slotRadiusMap[index] = cornerRadiusPx
            val ratio = ImageRatioCache.get(images[index]) ?: if (bounds.height > 0) bounds.width / bounds.height else 1f
            morphState.open(
                urls = images,
                index = index,
                bounds = bounds,
                cornerRadiusPx = cornerRadiusPx,
                imageRatio = ratio,
                allBounds = slotBoundsMap,
                allRadii = slotRadiusMap,
                livePhotoMap = livePhotoMap,
                scope = scope
            )
        } else {
            onImageClick(index)
        }
    }

    when (totalCount) {
        1 -> {
            SingleImageView(
                imageUrl = images[0],
                images = images,
                index = 0,
                modifier = modifier,
                livePhotoMap = livePhotoMap,
                onPositionReported = { b, r ->
                    slotBoundsMap[0] = b
                    slotRadiusMap[0] = r
                },
                onClick = { bounds, radius -> handleImageClick(0, bounds, radius) }
            )
        }
        2 -> {
            // 2 张图：并排两列
            Row(
                modifier = modifier.fillMaxWidth(0.8f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GridImageItem(
                    imageUrl = images[0],
                    images = images,
                    index = 0,
                    modifier = Modifier.weight(1f),
                    livePhotoMap = livePhotoMap,
                    onPositionReported = { b, r -> slotBoundsMap[0] = b; slotRadiusMap[0] = r },
                    onClick = { bounds, radius -> handleImageClick(0, bounds, radius) }
                )
                GridImageItem(
                    imageUrl = images[1],
                    images = images,
                    index = 1,
                    modifier = Modifier.weight(1f),
                    livePhotoMap = livePhotoMap,
                    onPositionReported = { b, r -> slotBoundsMap[1] = b; slotRadiusMap[1] = r },
                    onClick = { bounds, radius -> handleImageClick(1, bounds, radius) }
                )
            }
        }
        4 -> {
            // 4 张图：2x2 田字格
            Column(
                modifier = modifier.fillMaxWidth(0.75f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GridImageItem(
                        imageUrl = images[0],
                        images = images,
                        index = 0,
                        modifier = Modifier.weight(1f),
                        livePhotoMap = livePhotoMap,
                        onPositionReported = { b, r -> slotBoundsMap[0] = b; slotRadiusMap[0] = r },
                        onClick = { bounds, radius -> handleImageClick(0, bounds, radius) }
                    )
                    GridImageItem(
                        imageUrl = images[1],
                        images = images,
                        index = 1,
                        modifier = Modifier.weight(1f),
                        livePhotoMap = livePhotoMap,
                        onPositionReported = { b, r -> slotBoundsMap[1] = b; slotRadiusMap[1] = r },
                        onClick = { bounds, radius -> handleImageClick(1, bounds, radius) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GridImageItem(
                        imageUrl = images[2],
                        images = images,
                        index = 2,
                        modifier = Modifier.weight(1f),
                        livePhotoMap = livePhotoMap,
                        onPositionReported = { b, r -> slotBoundsMap[2] = b; slotRadiusMap[2] = r },
                        onClick = { bounds, radius -> handleImageClick(2, bounds, radius) }
                    )
                    GridImageItem(
                        imageUrl = images[3],
                        images = images,
                        index = 3,
                        modifier = Modifier.weight(1f),
                        livePhotoMap = livePhotoMap,
                        onPositionReported = { b, r -> slotBoundsMap[3] = b; slotRadiusMap[3] = r },
                        onClick = { bounds, radius -> handleImageClick(3, bounds, radius) }
                    )
                }
            }
        }
        else -> {
            // 3、5~9 张图及以上九宫格
            val displayImages = images.take(9)
            val rows = displayImages.chunked(3)
            val hasMoreThan9 = totalCount > 9
            val extraCount = totalCount - 9

            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rows.forEachIndexed { rowIndex, rowImages ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowImages.forEachIndexed { colIndex, url ->
                            val index = rowIndex * 3 + colIndex
                            val isLastOfNine = hasMoreThan9 && index == 8

                            GridImageItem(
                                imageUrl = url,
                                images = images,
                                index = index,
                                modifier = Modifier.weight(1f),
                                livePhotoMap = livePhotoMap,
                                extraOverlayText = if (isLastOfNine) "+$extraCount" else null,
                                onPositionReported = { b, r -> slotBoundsMap[index] = b; slotRadiusMap[index] = r },
                                onClick = { bounds, radius -> handleImageClick(index, bounds, radius) }
                            )
                        }
                        // 补齐空白格保持对齐
                        repeat(3 - rowImages.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
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
                morphState?.registerSlot(images, index, bounds, cornerRadiusPx, imageRatio)
            }
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer {
                // 在绘制阶段读取 morph 进度，避免形变动画期间逐帧重组缩略图
                alpha = if (morphState?.isSlotHidden(images, index) == true) 0f else 1f
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
                    morphState?.registerSlot(images, index, itemBounds, cornerRadiusPx, computedRatio)
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
                morphState?.registerSlot(images, index, bounds, cornerRadiusPx, cachedRatio)
            }
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer {
                // 在绘制阶段读取 morph 进度，避免形变动画期间逐帧重组缩略图
                alpha = if (morphState?.isSlotHidden(images, index) == true) 0f else 1f
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
                    morphState?.registerSlot(images, index, itemBounds, cornerRadiusPx, computedRatio)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 超出 9 张图时的蒙层
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isLivePhoto) "实况" else "动图",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

