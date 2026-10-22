package org.kazusa.matter.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.FavoriteToggleAction
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.liquidRoundedRectShape
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassText

/** 悬浮栏胶囊的统一高度：只留图标与短文案，尽量少压正文。 */
private val BarCapsuleHeight = 44.dp

/** 每个图标槽位的宽度：固定值让右侧胶囊按内容收紧，而不是把图标拉开一整条。 */
private val BarActionSlotWidth = 36.dp

/** 悬浮栏里所有图标的统一尺寸。 */
private val BarIconSize = 20.dp

/**
 * 互动胶囊左右内边距。
 *
 * 取「槽宽 - 图标尺寸」的一半，让**胶囊边缘到首尾图标的距离**和**图标之间的间距**相等
 * （(36 - 20) / 2 + 8 = 16 = 36 - 20），否则两端会显得比中间挤。
 */
private val BarActionsHorizontalPadding = 8.dp

/**
 * 正文页底部的液态玻璃悬浮操作栏。
 *
 * 左侧是「写评论」胶囊，点击唤起 [PostReplySheet]；右侧是互动胶囊：
 * 赞、收藏、分享。评论不再单独放图标 —— 左侧「写评论」已经承担了评论入口，
 * 评论区本身也还在正文下方，重复一个气泡只会把这条悬浮栏拉长。
 *
 * 收藏与分享不再占用顶栏；左侧输入胶囊吃掉剩余宽度，右侧互动胶囊按内容收紧。
 */
@Composable
internal fun PostDetailFloatingBar(
    post: WeiboStatusDto,
    isLiked: Boolean,
    backdrop: LayerBackdrop?,
    onWriteComment: () -> Unit,
    onLikeClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 输入胶囊吃掉剩余宽度，右侧互动胶囊按内容收紧
        WriteCommentCapsule(
            backdrop = backdrop,
            onClick = onWriteComment,
            modifier = Modifier.weight(1f)
        )

        PostActionsCapsule(
            post = post,
            isLiked = isLiked,
            backdrop = backdrop,
            onLikeClick = onLikeClick,
            onShareClick = onShareClick
        )
    }
}

/** 左「写评论」胶囊。 */
@Composable
private fun WriteCommentCapsule(
    backdrop: LayerBackdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassCapsule(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier.height(BarCapsuleHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIcon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(BarIconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            // 胶囊宽度来自外层 weight，内部宽度是确定的；文案固定且很短，这里不需要
            // 再给文字 weight（在一颗 wrap-content 的胶囊里加 weight 会把它撑成整行）
            LiquidGlassText(
                text = "写评论",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

/** 右互动胶囊：赞 / 收藏 / 分享。 */
@Composable
private fun PostActionsCapsule(
    post: WeiboStatusDto,
    isLiked: Boolean,
    backdrop: LayerBackdrop?,
    onLikeClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassCapsule(
        backdrop = backdrop,
        modifier = modifier.height(BarCapsuleHeight)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BarActionsHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIcon(
                imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = if (isLiked) "取消赞" else "赞",
                tint = if (isLiked) MaterialTheme.colorScheme.primary else null,
                onClick = onLikeClick
            )
            FavoriteActionIcon(post = post)
            ActionIcon(
                imageVector = Icons.Filled.Share,
                contentDescription = "分享当前微博链接",
                onClick = onShareClick
            )
        }
    }
}

/** 单个图标点击区：固定槽宽、撑满胶囊高度，保证 44dp 触摸热区。 */
@Composable
private fun ActionIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color? = null
) {
    Box(
        modifier = Modifier
            .width(BarActionSlotWidth)
            .fillMaxHeight()
            .clip(liquidRoundedRectShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(BarIconSize)
        )
    }
}

/** 收藏项：状态与写入统一走共享的收藏仓库，这里只决定图标形态。 */
@Composable
private fun FavoriteActionIcon(
    post: WeiboStatusDto
) {
    FavoriteToggleAction(post = post) { favorited, busy, enabled, onClick ->
        val tint = if (favorited) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Box(
            modifier = Modifier
                .width(BarActionSlotWidth)
                .fillMaxHeight()
                .clip(liquidRoundedRectShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = tint
                )
            } else {
                // 收藏沿用全 app 一致的书签图标（与卡片、顶栏收藏按钮同一套语义）
                LiquidGlassIcon(
                    imageVector = if (favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (favorited) "取消收藏" else "收藏微博",
                    tint = tint,
                    modifier = Modifier.size(BarIconSize)
                )
            }
        }
    }
}
