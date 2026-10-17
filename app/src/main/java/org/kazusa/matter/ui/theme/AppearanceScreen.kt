package org.kazusa.matter.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.launch
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import kotlin.math.roundToInt

/**
 * 外观与个性化独立设置页面 (包含深浅色、AMOLED 纯黑、Monet、Kolor 调色板样式、自定义种子色拾取器等)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    themePreferences: ThemePreferences,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val themeState by themePreferences.themeStateFlow.collectAsStateWithLifecycle(initialValue = AppThemeState())

    val isBlurSupported = remember { isRuntimeShaderSupported() }
    val backdrop = if (isBlurSupported) rememberLayerBackdrop() else null
    val scrollState = rememberScrollState()

    var showCustomColorDialog by remember { mutableStateOf(false) }

    if (showCustomColorDialog) {
        CustomColorPickerDialog(
            initialColor = themeState.seedColor,
            onDismissRequest = { showCustomColorDialog = false },
            onColorConfirmed = { selectedColor ->
                coroutineScope.launch {
                    themePreferences.setSeedColor(selectedColor)
                }
                showCustomColorDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                scrollState = scrollState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "外观与个性化",
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(scrollState)
                .padding(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = 16.dp,
                    bottom = 32.dp
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 深色与显示分组
            Text(
                text = "深色与显示",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 深浅色模式 (下拉菜单)
                var darkMenuExpanded by remember { mutableStateOf(false) }
                val currentModeLabel = when (themeState.isDarkTheme) {
                    null -> "跟随系统"
                    false -> "浅色模式"
                    true -> "深色模式"
                }

                ListItem(
                    modifier = Modifier.clickable { darkMenuExpanded = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DarkMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = "深浅色模式",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = currentModeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { darkMenuExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = currentModeLabel,
                                    style = MaterialTheme.typography.labelLargeEmphasized,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = darkMenuExpanded,
                                onDismissRequest = { darkMenuExpanded = false }
                            ) {
                                val modes = listOf("跟随系统", "浅色模式", "深色模式")
                                modes.forEachIndexed { index, label ->
                                    val isSelected = when (themeState.isDarkTheme) {
                                        null -> index == 0
                                        false -> index == 1
                                        true -> index == 2
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                style = if (isSelected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge
                                            )
                                        },
                                        trailingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            coroutineScope.launch {
                                                themePreferences.setDarkThemeMode(index)
                                            }
                                            darkMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // AMOLED 纯黑背景
                ListItem(
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            themePreferences.setIsAmoled(!themeState.isAmoled)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = "AMOLED 纯黑背景",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "深色模式下使用纯黑(#000000)背景，屏幕更省电",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = themeState.isAmoled,
                            onCheckedChange = { checked ->
                                coroutineScope.launch {
                                    themePreferences.setIsAmoled(checked)
                                }
                            }
                        )
                    }
                )

                // Android 12+ Monet 取色
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                themePreferences.setDynamicColor(!themeState.dynamicColor)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                text = "系统壁纸动态取色 (Monet)",
                                style = MaterialTheme.typography.bodyLargeEmphasized
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "提取当前系统桌面壁纸的主色调并全应用适配（开启时优先于自定义色彩）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = themeState.dynamicColor,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        themePreferences.setDynamicColor(checked)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 2. Kolor 调色板与色彩引擎分组
            Text(
                text = "Kolor 色彩引擎与调色板",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 调色板样式 (下拉菜单模式)
                var styleMenuExpanded by remember { mutableStateOf(false) }
                val currentStyle = AvailablePaletteStyles.find { it.style == themeState.paletteStyle }
                val currentStyleName = currentStyle?.title ?: themeState.paletteStyle.name
                val currentStyleDesc = currentStyle?.subtitle.orEmpty()

                ListItem(
                    modifier = Modifier.clickable { styleMenuExpanded = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatPaint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = "调色板样式",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (themeState.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                "当前正跟随系统 Monet · 选择样式将切换为 Kolor 自定义"
                            else
                                "$currentStyleName · $currentStyleDesc",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { styleMenuExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = currentStyleName,
                                    style = MaterialTheme.typography.labelLargeEmphasized,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = styleMenuExpanded,
                                onDismissRequest = { styleMenuExpanded = false }
                            ) {
                                AvailablePaletteStyles.forEach { option ->
                                    val isSelected = !themeState.dynamicColor && themeState.paletteStyle == option.style
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = option.title,
                                                    style = if (isSelected) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = option.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        trailingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            coroutineScope.launch {
                                                themePreferences.setPaletteStyle(option.style)
                                            }
                                            styleMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // 自定义种子色主项 (带颜色圆徽章与拾色器弹窗按钮)
                val currentPreset = PresetThemeColors.find { it.color.value == themeState.seedColor.value }
                val seedColorLabel = if (currentPreset != null) {
                    "${currentPreset.name} (${themeState.seedColor.toHexString()})"
                } else {
                    "${themeState.seedColor.toHexString()} (自定义)"
                }

                ListItem(
                    modifier = Modifier.clickable { showCustomColorDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(themeState.seedColor)
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                        )
                    },
                    headlineContent = {
                        Text(
                            text = "自定义种子色",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "当前: $seedColorLabel · 支持 HEX 色值与 RGB 精调",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { showCustomColorDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = "拾色器", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )

                // 快捷预设色彩网格
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp, top = 4.dp)
                ) {
                    Text(
                        text = "常用色彩快捷选取",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val isPresetSelected = !themeState.dynamicColor &&
                            PresetThemeColors.any { it.color.value == themeState.seedColor.value }
                    val isCustomSeedSelected = !themeState.dynamicColor && !isPresetSelected

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PresetThemeColors.forEach { preset ->
                            val isSelected = !themeState.dynamicColor &&
                                    themeState.seedColor.value == preset.color.value

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch {
                                        themePreferences.setSeedColor(preset.color)
                                    }
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(preset.color)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 自定义色快捷圆球
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { showCustomColorDialog = true }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCustomSeedSelected) themeState.seedColor else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .border(
                                        width = if (isCustomSeedSelected) 3.dp else 1.dp,
                                        color = if (isCustomSeedSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCustomSeedSelected) {
                                    val isLight = (0.299f * themeState.seedColor.red + 0.587f * themeState.seedColor.green + 0.114f * themeState.seedColor.blue) > 0.5f
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isLight) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ColorLens,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (isCustomSeedSelected) "自定义" else "拾色",
                                style = if (isCustomSeedSelected) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelSmall,
                                color = if (isCustomSeedSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. 实时色彩方案组件预览
            Text(
                text = "色彩预览效果",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "组件与容器色彩效果",
                        style = MaterialTheme.typography.bodyMediumEmphasized
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Primary",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Secondary",
                                color = MaterialTheme.colorScheme.onSecondary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.tertiary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tertiary",
                                color = MaterialTheme.colorScheme.onTertiary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("主按钮")
                        }
                        FilledTonalButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("强调按钮")
                        }
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("轮廓按钮")
                        }
                    }
                }
            }
        }
    }
}
