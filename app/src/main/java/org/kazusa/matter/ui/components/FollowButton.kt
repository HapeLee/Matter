package org.kazusa.matter.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 博主主页的关注按钮。
 *
 * 三种状态与微博一致：
 * - 未关注：主色胶囊「关注」
 * - 已关注：浅色胶囊「已关注」
 * - 互相关注：浅色胶囊「互相关注」
 *
 * 请求进行中时按钮不可点击，并在文字前展示小号进度圈。
 */
@Composable
fun FollowButton(
    isFollowing: Boolean,
    isMutual: Boolean,
    isUpdating: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        isFollowing && isMutual -> "互相关注"
        isFollowing -> "已关注"
        else -> "关注"
    }

    MatterChip(
        modifier = modifier,
        containerColor = if (isFollowing) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (isFollowing) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        onClick = if (isUpdating) null else onClick
    ) {
        if (isUpdating) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = LocalContentColor.current
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMediumEmphasized,
            maxLines = 1
        )
    }
}
