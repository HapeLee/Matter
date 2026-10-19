package org.kazusa.matter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.kazusa.matter.ui.theme.MatterShapeTokens

/*
 * 项目统一的「卡片 / 标签」基础组件。
 *
 * 之前各个界面各自手写 `clip + background + padding + Text` 或 `Card/Surface + clip`，
 * 导致同样语义的元素出现 3/4/6/8/10/12dp 等多种圆角与内边距。这里收敛成五个原语：
 *
 * - MatterTag：纯文字小标签（分类、状态、计数、角标）
 * - MatterChip：可放多元素的胶囊容器（词条、按钮、信息块）
 * - MatterMediaBadge：叠在图片/视频上的黑色半透明角标
 * - MatterCard：内容卡片容器（列表项、分区卡、可点击卡片）
 * - MatterSnackbar：统一的 Snackbar 外观
 *
 * 圆角统一使用 MatterShapeTokens 与 MaterialTheme.shapes；
 * 内边距统一走 4dp 网格（最小 2dp），颜色仍由调用方按语义传入。
 */

/** 小标签 4×2，胶囊 12×8，媒体角标 4×2。 */
private val DefaultTagPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
private val DefaultChipPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * 纯文字小标签。
 *
 * @param containerColor 背景色，默认主题色的 12% 透明度
 * @param contentColor 文字色
 * @param shape 圆角，默认 [MatterShapeTokens.tag]
 * @param border 描边；配上透明背景即可做 outlined 标签
 * @param onClick 非空时标签整体可点击
 */
@Composable
fun MatterTag(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    shape: Shape = MatterShapeTokens.tag,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    contentPadding: PaddingValues = DefaultTagPadding,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null
) {
    Text(
        text = text,
        style = style,
        color = contentColor,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding)
    )
}

/**
 * 胶囊/标签容器，内容可以是文字 + 图标等任意组合。
 *
 * @param contentColor 内容默认色（通过 [LocalContentColor] 下发）
 * @param border 描边；配上透明背景即可做 outlined 胶囊
 * @param horizontalArrangement 内容横向排布，撑满宽度时可设为 Center
 * @param onClick 非空时整体可点击
 */
@Composable
fun MatterChip(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MatterShapeTokens.pill,
    contentPadding: PaddingValues = DefaultChipPadding,
    onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(containerColor)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content
        )
    }
}

/**
 * 图片/视频上的半透明角标（动图、实况、时长、张数等）。
 */
@Composable
fun MatterMediaBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.6f),
    contentColor: Color = Color.White,
    shape: Shape = MatterShapeTokens.mediaBadge,
    contentPadding: PaddingValues = DefaultTagPadding
) {
    MatterTag(
        text = text,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = shape,
        style = MaterialTheme.typography.labelSmall,
        contentPadding = contentPadding
    )
}

/**
 * 统一的内容卡片容器。
 *
 * @param shape 默认跟随主题 `medium`（20dp）
 * @param contentColor 内容默认色；不传则按 containerColor 自动推导
 * @param elevation 静止态阴影/色调高度
 * @param pressedElevation 点击态高度（仅 [onClick] 非空时生效）
 * @param border 描边；需要描边卡片时使用
 */
@Composable
fun MatterCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color? = null,
    elevation: Dp = 0.dp,
    pressedElevation: Dp = 2.dp,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor ?: contentColorFor(containerColor)
    )
    val cardElevation = CardDefaults.cardElevation(
        defaultElevation = elevation,
        pressedElevation = pressedElevation
    )

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = cardElevation,
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = cardElevation,
            border = border,
            content = content
        )
    }
}

/**
 * 统一的 Snackbar 外观；内容自行决定是否包一层 Row 放图标。
 */
@Composable
fun MatterSnackbar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    content: @Composable () -> Unit
) {
    Snackbar(
        shape = MaterialTheme.shapes.medium,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = modifier,
        content = content
    )
}
