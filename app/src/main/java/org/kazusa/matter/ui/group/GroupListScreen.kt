package org.kazusa.matter.ui.group

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.kazusa.matter.data.model.WeiboGroupDto
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.MatterSnackbar
import org.kazusa.matter.ui.components.MatterTag
import org.kazusa.matter.ui.components.TopBarActionButton
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import org.kazusa.matter.ui.theme.MatterShapeTokens

@Composable
fun GroupListScreen(
    viewModel: GroupViewModel,
    onSelectGroup: (gid: String?, groupName: String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val isBlurSupported = rememberBlurEffectsEnabled()
    val backdrop = if (isBlurSupported) rememberLayerBackdrop() else null
    val snackbarHostState = remember { SnackbarHostState() }

    val haptic = LocalHapticFeedback.current

    // Reorderable 状态管理
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        // 计算在 displayedGroups 中的对应索引（顶部有一个说明 banner 和一个 sectionHeader，共 2 个 item）
        val headerOffset = 2
        val fromIndex = from.index - headerOffset
        val toIndex = to.index - headerOffset
        val displayed = uiState.displayedGroups

        // 仅在 displayedGroups 内部且不包含 index 0 (全部关注) 的情况下允许重排
        if (fromIndex in 1 until displayed.size && toIndex in 1 until displayed.size) {
            viewModel.onGroupDragged(fromIndex, toIndex)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    // 弹窗状态管理
    var showCreateDialog by remember { mutableStateOf(false) }
    var groupToRename by remember { mutableStateOf<WeiboGroupDto?>(null) }
    var groupToDelete by remember { mutableStateOf<WeiboGroupDto?>(null) }

    // 操作消息 Snackbar 提示
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 16.dp)
            ) { data ->
                MatterSnackbar(
                    containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (data.visuals.message.contains("失败")) Icons.Default.Info else Icons.Default.Check,
                            contentDescription = null,
                            tint = if (data.visuals.message.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "关注分组管理",
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                },
                actions = { scroll ->
                    TopBarActionButton(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建分组",
                        scroll = scroll,
                        backdrop = backdrop,
                        onClick = { showCreateDialog = true }
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading && uiState.groups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                uiState.errorMessage != null && uiState.groups.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "加载分组失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = { viewModel.loadGroups(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新加载")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            bottom = innerPadding.calculateBottomPadding() + 32.dp
                        )
                    ) {
                        // 顶部说明卡片
                        item(key = "header_hint_banner") {
                            MatterCard(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "设置在动态顶部展示的分组。启用的分组可在动态首页顶部快速切换；上下移动可调整切换时的排列顺序。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 分组段 1：在动态顶部展示的分组
                        item(key = "header_visible_section") {
                            SectionHeader(
                                title = "在动态顶部展示的分组",
                                count = uiState.displayedGroups.size,
                                isAccent = true
                            )
                        }

                        val displayedGroups = uiState.displayedGroups
                        itemsIndexed(displayedGroups, key = { _, g -> "vis_${g.identifier}" }) { _, group ->
                            val isAllFocus = group.identifier == "all"

                            ReorderableItem(
                                state = reorderableLazyListState,
                                key = "vis_${group.identifier}",
                                enabled = !isAllFocus
                            ) { isDragging ->
                                GroupManagementCard(
                                    group = group,
                                    isVisibleInTop = true,
                                    isSystemGroup = group.isSystem,
                                    isAllGroup = isAllFocus,
                                    isDragging = isDragging,
                                    dragHandleModifier = if (!isAllFocus) {
                                        Modifier.draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                viewModel.onDragFinished()
                                            }
                                        )
                                    } else null,
                                    onToggleVisibility = { isChecked ->
                                        viewModel.toggleGroupVisibility(group.identifier, isChecked)
                                    },
                                    onRename = { groupToRename = group },
                                    onDelete = { groupToDelete = group },
                                    onClick = {
                                        onSelectGroup(group.identifier, group.displayName)
                                    }
                                )
                            }
                        }

                        // 分组段 2：未在顶部展示的分组（如有）
                        val hiddenGroups = uiState.hiddenGroups
                        if (hiddenGroups.isNotEmpty()) {
                            item(key = "header_hidden_section") {
                                Spacer(modifier = Modifier.height(16.dp))
                                SectionHeader(
                                    title = "未在顶部展示的分组",
                                    count = hiddenGroups.size,
                                    isAccent = false
                                )
                            }

                            itemsIndexed(hiddenGroups, key = { _, g -> "hid_${g.identifier}" }) { _, group ->
                                GroupManagementCard(
                                    group = group,
                                    isVisibleInTop = false,
                                    isSystemGroup = group.isSystem,
                                    isAllGroup = false,
                                    onToggleVisibility = { isChecked ->
                                        viewModel.toggleGroupVisibility(group.identifier, isChecked)
                                    },
                                    onRename = { groupToRename = group },
                                    onDelete = { groupToDelete = group },
                                    onClick = {
                                        onSelectGroup(group.identifier, group.displayName)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建分组弹窗
    if (showCreateDialog) {
        CreateGroupDialog(
            isSubmitting = uiState.isOperating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createGroup(name) { success ->
                    if (success) {
                        showCreateDialog = false
                    }
                }
            }
        )
    }

    // 重命名分组弹窗
    groupToRename?.let { group ->
        RenameGroupDialog(
            initialName = group.displayName,
            isSubmitting = uiState.isOperating,
            onDismiss = { groupToRename = null },
            onConfirm = { newName ->
                viewModel.renameGroup(group.identifier, newName) { success ->
                    if (success) {
                        groupToRename = null
                    }
                }
            }
        )
    }

    // 删除分组确认弹窗
    groupToDelete?.let { group ->
        DeleteGroupConfirmDialog(
            groupName = group.displayName,
            isSubmitting = uiState.isOperating,
            onDismiss = { groupToDelete = null },
            onConfirm = {
                viewModel.deleteGroup(group.identifier, group.displayName)
                groupToDelete = null
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    isAccent: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        MatterTag(
            text = "${count}个",
            containerColor = if (isAccent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (isAccent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun GroupManagementCard(
    group: WeiboGroupDto,
    isVisibleInTop: Boolean,
    isSystemGroup: Boolean,
    isAllGroup: Boolean,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier? = null,
    onToggleVisibility: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val icon = when {
        group.identifier == "all" || group.displayName == "全部关注" -> Icons.Default.Public
        group.identifier == "special" || group.displayName.contains("特别关注") -> Icons.Default.Star
        group.identifier == "friends" || group.displayName.contains("互相关注") -> Icons.Default.Group
        group.displayName.contains("悄悄") -> Icons.Default.VisibilityOff
        group.identifier == "original" || group.displayName.contains("原创") -> Icons.Default.Create
        else -> Icons.Default.Folder
    }

    val elevation = if (isDragging) 8.dp else 0.dp
    val containerColor = when {
        isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
        isVisibleInTop -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
    }

    MatterCard(
        shape = MatterShapeTokens.listItem,
        containerColor = containerColor,
        elevation = elevation,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标容器
            Surface(
                shape = CircleShape,
                color = if (isVisibleInTop) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isVisibleInTop) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 名称与标签信息
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        color = if (isVisibleInTop) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    if (isAllGroup) {
                        MatterTag(
                            text = "默认置顶",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else if (isSystemGroup) {
                        MatterTag(
                            text = "系统",
                            containerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (group.memberCount > 0) {
                    Text(
                        text = "${group.memberCount} 人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 拖动排序手柄（仅在动态顶部展示的分组可用，全部关注置顶不可动）
            if (isVisibleInTop && !isAllGroup && dragHandleModifier != null) {
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .size(34.dp)
                        .then(dragHandleModifier)
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "按住拖动排序",
                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 自定义分组的更多菜单（重命名、删除）
            if (!isSystemGroup) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名分组") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除分组", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 展示在顶部开关（全部关注默认开启且不可关）
            Switch(
                checked = isVisibleInTop,
                onCheckedChange = { isChecked ->
                    if (!isAllGroup) {
                        onToggleVisibility(isChecked)
                    }
                },
                enabled = !isAllGroup,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
private fun CreateGroupDialog(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val isNameValid = text.trim().isNotEmpty() && text.length <= 15

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text("新建关注分组", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column {
                Text(
                    text = "新建的分组将同步至您的微博账号，并可在动态顶部快速切换浏览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 15) text = it },
                    label = { Text("分组名称") },
                    placeholder = { Text("例如：数码、设计、日常") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${text.length}/15",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = isNameValid && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RenameGroupDialog(
    initialName: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    val isNameValid = text.trim().isNotEmpty() && text.length <= 15 && text.trim() != initialName

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text("重命名分组", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 15) text = it },
                    label = { Text("分组新名称") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${text.length}/15",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = isNameValid && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DeleteGroupConfirmDialog(
    groupName: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text("删除分组「$groupName」？", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(
                text = "确定要删除该分组吗？此操作将从微博中删除该分组分类，不会取消关注该分组内的任何博主。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("取消")
            }
        }
    )
}
