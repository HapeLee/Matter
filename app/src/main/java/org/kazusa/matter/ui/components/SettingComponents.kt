package org.kazusa.matter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设置项外部圆角裁剪容器（完全参考 legado-with-MD3 的 SplicedColumnGroup）
 * 外层 16dp 圆角裁剪，内部条目以 2dp 间距排列并配合各自的 4dp 圆角实现精致的拼接卡片效果
 */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    cornerRadius: Dp = 16.dp,
    itemSpacing: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
                content = content
            )
        }
    }
}

/**
 * 单个设置项的卡片容器（参考 legado-with-MD3 的 SettingCard）
 */
@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 通用设置条目（完全参考 legado-with-MD3 的 SettingItem）
 * - 去掉图标，纯文字沉浸式风格与参考工程完全一致
 * - 外部圆角裁剪与拼接间距支持
 * - 文字样式与色值规范：title 使用 titleMedium，description 使用 bodySmall + onSurface 70% 透明度，option 使用 labelMedium + primary
 * - 支持内置 DropdownMenu 交互，对于 dropdown 设置直接在描述显示当前设置
 * - 对于点击类型无尾部槽，直接在描述中说明
 * - 采用 Material 3 ListItem 最新重载 (trailing lambda headlineContent)
 */
@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    option: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    dropdownMenu: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    cornerRadius: Dp = 4.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    semanticRole: Role? = null,
    semanticToggleState: Boolean? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    SettingCard(
        modifier = modifier,
        cornerRadius = cornerRadius,
        containerColor = containerColor
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        semanticRole?.let { role = it }
                        semanticToggleState?.let {
                            toggleableState = if (it) ToggleableState.On else ToggleableState.Off
                        }
                        if (!enabled) disabled()
                    }
                    .clickable(
                        enabled = enabled,
                        role = semanticRole,
                        onClick = {
                            if (dropdownMenu != null) {
                                showMenu = true
                            } else {
                                onClick?.invoke()
                            }
                        }
                    ),
                leadingContent = leadingContent,
                supportingContent = if (description != null || option != null) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            option?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                } else null,
                trailingContent = trailingContent,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            dropdownMenu?.let { menu ->
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    menu { showMenu = false }
                }
            }
        }
    }
}

/**
 * 开关设置条目（完全参考 legado-with-MD3 的 SwitchSettingItem）
 */
@Composable
fun SwitchSettingItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    SettingItem(
        modifier = modifier,
        title = title,
        description = description,
        leadingContent = leadingContent,
        enabled = enabled,
        containerColor = containerColor,
        semanticRole = Role.Switch,
        semanticToggleState = checked,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    )
}
