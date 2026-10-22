package org.kazusa.matter.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import kotlinx.coroutines.launch
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.SettingCard
import org.kazusa.matter.ui.components.SettingGroup
import org.kazusa.matter.ui.components.SettingItem
import org.kazusa.matter.ui.components.SwitchSettingItem
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

    val isBlurSupported = rememberBlurEffectsEnabled()
    val backdrop = if (isBlurSupported) rememberLayerBackdrop() else null
    val scrollState = rememberScrollState()

    var showColorSheet by remember { mutableStateOf(false) }

    if (showColorSheet) {
        ThemeColorPickerBottomSheet(
            initialColor = themeState.seedColor,
            isDynamicColor = themeState.dynamicColor,
            onDismissRequest = { showColorSheet = false },
            onColorSelected = { selectedColor ->
                coroutineScope.launch {
                    themePreferences.setSeedColor(selectedColor)
                }
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
            SettingGroup(title = "深色与显示") {
                // 深浅色模式 (下拉菜单)
                val currentModeLabel = when (themeState.isDarkTheme) {
                    null -> "跟随系统"
                    false -> "浅色模式"
                    true -> "深色模式"
                }

                SettingItem(
                    title = "深浅色模式",
                    description = currentModeLabel,
                    dropdownMenu = { onDismiss ->
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
                                    onDismiss()
                                }
                            )
                        }
                    }
                )

                // 模糊效果
                SwitchSettingItem(
                    title = "模糊效果",
                    description = "统一控制背景模糊与液态玻璃效果，关闭后使用实色背景",
                    checked = themeState.blurEffectsEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch {
                            themePreferences.setBlurEffectsEnabled(enabled)
                        }
                    }
                )

                // AMOLED 纯黑背景
                SwitchSettingItem(
                    title = "AMOLED 纯黑背景",
                    description = "深色模式下使用纯黑(#000000)背景，屏幕更省电",
                    checked = themeState.isAmoled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch {
                            themePreferences.setIsAmoled(checked)
                        }
                    }
                )

                // Android 12+ Monet 取色
                SwitchSettingItem(
                        title = "系统壁纸动态取色 (Monet)",
                        description = "提取当前系统桌面壁纸的主色调并全应用适配（开启时优先于自定义色彩）",
                        checked = themeState.dynamicColor,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                themePreferences.setDynamicColor(checked)
                            }
                        }
                    )
            }

            // 2. Kolor 调色板与色彩引擎分组
            SettingGroup(title = "Kolor 色彩引擎与调色板") {
                // 调色板样式 (下拉菜单模式)
                val currentStyle = AvailablePaletteStyles.find { it.style == themeState.paletteStyle }
                val currentStyleName = currentStyle?.title ?: themeState.paletteStyle.name

                SettingItem(
                    title = "调色板样式",
                    description = if (themeState.dynamicColor)
                        "跟随系统 Monet"
                    else
                        currentStyleName,
                    dropdownMenu = { onDismiss ->
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
                                    onDismiss()
                                }
                            )
                        }
                    }
                )

                // 主题色彩 (设置点击项，配合 ThemeColorPickerBottomSheet)
                val currentPreset = PresetThemeColors.find { it.color.value == themeState.seedColor.value }
                val seedColorLabel = if (currentPreset != null) {
                    "${currentPreset.name} (${themeState.seedColor.toHexString()})"
                } else {
                    "${themeState.seedColor.toHexString()} (自定义)"
                }

                SettingItem(
                    title = "主题色彩",
                    description = if (themeState.dynamicColor)
                        "系统壁纸 Monet 动态色彩"
                    else
                        "种子色 $seedColorLabel",
                    onClick = { showColorSheet = true }
                )
            }

            // 3. 实时色彩方案组件预览
            SettingGroup(title = "色彩预览效果") {
                SettingCard(cornerRadius = 16.dp) {
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
                                    .clip(smoothRoundedCorner(8.dp))
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
                                    .clip(smoothRoundedCorner(8.dp))
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
                                    .clip(smoothRoundedCorner(8.dp))
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
                                shape = smoothRoundedCorner(10.dp)
                            ) {
                                Text("主按钮")
                            }
                            FilledTonalButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                shape = smoothRoundedCorner(10.dp)
                            ) {
                                Text("强调按钮")
                            }
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                shape = smoothRoundedCorner(10.dp)
                            ) {
                                Text("轮廓按钮")
                            }
                        }
                    }
                }
            }
        }
    }
}
