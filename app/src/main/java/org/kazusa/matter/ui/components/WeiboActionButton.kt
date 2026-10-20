package org.kazusa.matter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 微博互动栏按钮（转发 / 评论 / 赞），正文页与信息流卡片共用。
 *
 * 只负责单个按钮的呈现与点击，不碰业务：
 * - 点赞的仓库写入、失败回滚、morph 期间的屏蔽留在调用方；
 * - 正文页「整行切子页签」与「图标点赞」靠 [onIconClick] 表达。
 *
 * 度量以正文页为准（图标 16dp、上下 8dp、水平 12dp、图标与数字间距 4dp、`labelSmall`），
 * 卡片操作栏因此比原来矮一点、字号小一点，两处观感保持一致。
 *
 * @param count 原始计数（服务端字符串），空串 / 0 时用 [label] 占位
 * @param tint 文字与默认图标颜色；null 表示未选中态的弱化色
 * @param iconTint 单独覆盖图标颜色（点赞态只染图标）；null 时跟随 [tint]
 * @param emphasized 选中态（正文页当前子页签）：primary 着色 + 加粗
 * @param busy 请求在途：用同尺寸转圈替换图标，文字位置不跳动
 * @param onIconClick 图标单独点击区；null 时整行只有一个点击区
 */
@Composable
fun WeiboActionButton(
    icon: ImageVector,
    count: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    iconTint: Color? = null,
    emphasized: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    onIconClick: (() -> Unit)? = null
) {
    val contentColor = when {
        emphasized -> MaterialTheme.colorScheme.primary
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }
    val resolvedIconColor = iconTint ?: contentColor
    val iconSlot: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.5.dp,
                color = resolvedIconColor
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = resolvedIconColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (onIconClick != null) {
            Box(
                modifier = Modifier.clickable(enabled = enabled, onClick = onIconClick),
                contentAlignment = Alignment.Center
            ) {
                iconSlot()
            }
        } else {
            iconSlot()
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = formatCount(count, label),
            style = if (emphasized) {
                MaterialTheme.typography.labelSmallEmphasized
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = contentColor
        )
    }
}

/** 互动数展示：空 / 0 用文案占位，上万折算成「x.x万」。 */
internal fun formatCount(countStr: String, defaultLabel: String): String {
    if (countStr.isBlank() || countStr == "0") return defaultLabel
    val countInt = countStr.toIntOrNull() ?: return countStr
    return when {
        countInt >= 10_000 -> String.format("%.1f万", countInt / 10_000.0)
        else -> countInt.toString()
    }
}
