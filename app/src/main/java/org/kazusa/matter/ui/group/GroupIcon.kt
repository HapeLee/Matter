package org.kazusa.matter.ui.group

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import org.kazusa.matter.data.model.WeiboGroupDto
import org.kazusa.matter.data.model.WeiboGroupKind

/**
 * 分组图标。以 gid 前 5 位解析出的 [WeiboGroupKind] 为准，分组管理页与切换面板共用一份，
 * 不再各自写 `when` 去比对 `friends` / `original` 这类网页端早已不用的字符串 id。
 */
internal fun WeiboGroupDto.groupIcon(): ImageVector = when (kind) {
    WeiboGroupKind.ALL -> Icons.Default.Public
    WeiboGroupKind.SPECIAL -> Icons.Default.Star
    WeiboGroupKind.MUTUAL -> Icons.Default.Group
    WeiboGroupKind.QUIET -> Icons.Default.VisibilityOff
    WeiboGroupKind.LATEST -> Icons.Default.Refresh
    WeiboGroupKind.ORIGINAL -> Icons.Default.Create
    WeiboGroupKind.VIDEO -> Icons.Default.PlayCircle
    WeiboGroupKind.GROUP_POST,
    WeiboGroupKind.V_PLUS,
    WeiboGroupKind.CUSTOM -> Icons.Default.Folder
}
