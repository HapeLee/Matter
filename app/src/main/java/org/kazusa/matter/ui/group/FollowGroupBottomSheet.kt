package org.kazusa.matter.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.theme.MatterShapeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowGroupBottomSheet(
    currentGid: String?,
    viewModel: GroupViewModel,
    onSelectGroup: (gid: String?, groupName: String) -> Unit,
    onDismissRequest: () -> Unit,
    onNavigateToManagement: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            // 顶栏：标题 + 管理分组跳转
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "关注分组",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (onNavigateToManagement != null) {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onNavigateToManagement()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "管理",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.isLoading && uiState.displayedGroups.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> {
                    val activeGid = if (currentGid.isNullOrBlank()) "all" else currentGid
                    // 仅展示在分组管理中配置为展示的分组（决定动态顶部的分组选择）
                    val otherGroups = remember(uiState.displayedGroups) {
                        uiState.displayedGroups.filter {
                            it.identifier != "all" && it.name != "全部关注" && it.title != "全部关注"
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 预设系统内置分组项（全局置顶且唯一）
                        item(key = "all_focus") {
                            GroupSheetItem(
                                title = "全部关注",
                                icon = Icons.Default.Public,
                                isSelected = activeGid == "all",
                                onClick = {
                                    onSelectGroup("all", "全部关注")
                                    onDismissRequest()
                                }
                            )
                        }

                        // 其它启用的分组（按用户自定义排序）
                        items(otherGroups, key = { it.identifier }) { group ->
                            val isSelected = activeGid == group.identifier
                            val icon = when {
                                group.identifier == "special" || group.displayName.contains("特别关注") -> Icons.Default.Star
                                group.identifier == "friends" || group.displayName.contains("互相关注") -> Icons.Default.Group
                                group.displayName.contains("悄悄") -> Icons.Default.VisibilityOff
                                group.identifier == "original" || group.displayName.contains("原创") -> Icons.Default.Create
                                else -> Icons.Default.Folder
                            }

                            GroupSheetItem(
                                title = group.displayName,
                                icon = icon,
                                isSelected = isSelected,
                                memberCount = if (group.memberCount > 0) group.memberCount else null,
                                onClick = {
                                    onSelectGroup(group.identifier, group.displayName)
                                    onDismissRequest()
                                }
                            )
                        }

                        // 底部「管理关注分组」快捷入口
                        if (onNavigateToManagement != null) {
                            item(key = "manage_groups_btn") {
                                Spacer(modifier = Modifier.height(8.dp))
                                MatterCard(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                    shape = MatterShapeTokens.listItem,
                                    onClick = {
                                        onDismissRequest()
                                        onNavigateToManagement()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "管理关注分组",
                                            style = MaterialTheme.typography.bodyMediumEmphasized,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSheetItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    memberCount: Int? = null,
    onClick: () -> Unit
) {
    MatterCard(
        containerColor = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MatterShapeTokens.listItem,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (isSelected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (memberCount != null && memberCount > 0) {
                    Text(
                        text = "$memberCount 人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
