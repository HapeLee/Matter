package org.kazusa.matter.ui.theme

import androidx.compose.foundation.shape.CircleShape
import org.kazusa.matter.ui.theme.smoothRoundedCorner
import org.kazusa.matter.ui.theme.smoothUnevenRoundedCorner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import org.kazusa.matter.ui.components.MatterCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemePaletteBottomSheet(
    themePreferences: ThemePreferences,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val themeState by themePreferences.themeStateFlow.collectAsStateWithLifecycle(initialValue = AppThemeState())
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        shape = smoothUnevenRoundedCorner(28.dp, 28.dp, 0.dp, 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "外观与个性化调色板",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "基于 Material 3 & Kolor 动态色彩引擎",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 1. 深色模式选择
            Text(
                text = "深浅色模式",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            val modes = listOf("跟随系统", "浅色", "深色")
            val currentModeIndex = when (themeState.isDarkTheme) {
                null -> 0
                false -> 1
                true -> 2
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = currentModeIndex == index,
                        onClick = {
                            coroutineScope.launch {
                                themePreferences.setDarkThemeMode(index)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. AMOLED 纯黑开关 (当处于深色模式或系统为深色时生效)
            MatterCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AMOLED 纯黑背景",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                        Text(
                            text = "深色模式下使用纯黑(#000000)背景，屏幕更省电",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = themeState.isAmoled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                themePreferences.setIsAmoled(checked)
                            }
                        }
                    )
                }
            }

            // 3. Android 12+ 原生 Monet 动态取色开关
            MatterCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "系统壁纸动态取色 (Monet)",
                                style = MaterialTheme.typography.bodyLargeEmphasized
                            )
                            Text(
                                text = "提取当前系统桌面壁纸的主色调并全应用适配",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = themeState.dynamicColor,
                            onCheckedChange = { checked ->
                                coroutineScope.launch {
                                    themePreferences.setDynamicColor(checked)
                                }
                            }
                        )
                    }
                }

            // 4. Kolor 调色板样式选项 (柔和 / 中性 / 鲜艳 等)
            Text(
                text = "调色板样式 (Kolor 风格)",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (themeState.dynamicColor)
                    "切换调色板样式将自动切换至自定义主题"
                else
                    "控制色彩衍生的对比度、饱和度与风格",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AvailablePaletteStyles.forEach { option ->
                    val isSelected = !themeState.dynamicColor && themeState.paletteStyle == option.style
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            coroutineScope.launch {
                                themePreferences.setPaletteStyle(option.style)
                            }
                        },
                        label = {
                            Text(
                                text = option.title,
                                style = if (isSelected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null
                    )
                }
            }

            val currentStyleDesc = AvailablePaletteStyles.find { it.style == themeState.paletteStyle }?.subtitle.orEmpty()
            if (currentStyleDesc.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "当前样式: ${themeState.paletteStyle.displayName()} · $currentStyleDesc",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. Kolor 自定义种子调色板 (预设色彩 + 自定义种子色)
            Text(
                text = if (themeState.dynamicColor)
                    "预设调色板与种子色 (选择将切换为自定义主题)"
                else
                    "自定义主题色 (Kolor 种子色)",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "可选择预设经典色系，或通过拾取器输入 HEX 色值 / 滑块自定色彩",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            val isPresetSelected = !themeState.dynamicColor &&
                    PresetThemeColors.any { it.color.value == themeState.seedColor.value }
            val isCustomSeedSelected = !themeState.dynamicColor && !isPresetSelected

            // 颜色圆形选择器 (包含 9 个预设色 + 1 个自定义种子色按钮)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 预设颜色
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
                                .size(44.dp)
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
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 自定义种子色圆形项
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        showCustomColorDialog = true
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
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
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.ColorLens,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isCustomSeedSelected) "自定义" else "自定义色",
                        style = if (isCustomSeedSelected) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelSmall,
                        color = if (isCustomSeedSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 自定义种子色彩拾取器入口卡片
            MatterCard(
                shape = MatterShapeTokens.listItem,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { showCustomColorDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(themeState.seedColor)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自定义种子色拾取器",
                            style = MaterialTheme.typography.bodyMediumEmphasized
                        )
                        Text(
                            text = "当前: ${themeState.seedColor.toHexString()} · 点击输入 HEX 色值或拖动滑块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.FormatPaint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 自定义种子色拾取弹窗 (支持实时预览、HEX 输入、RGB 滑块精细调节以及灵感色快捷点选)
 */
@Composable
fun CustomColorPickerDialog(
    initialColor: Color,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Color) -> Unit
) {
    var red by remember { mutableFloatStateOf(initialColor.red * 255f) }
    var green by remember { mutableFloatStateOf(initialColor.green * 255f) }
    var blue by remember { mutableFloatStateOf(initialColor.blue * 255f) }

    fun buildHex(r: Float, g: Float, b: Float): String {
        val ri = r.roundToInt().coerceIn(0, 255)
        val gi = g.roundToInt().coerceIn(0, 255)
        val bi = b.roundToInt().coerceIn(0, 255)
        return String.format("%02X%02X%02X", ri, gi, bi)
    }

    var hexInput by remember { mutableStateOf(buildHex(red, green, blue)) }
    var isHexError by remember { mutableStateOf(false) }

    val currentColor = remember(red, green, blue) {
        Color(
            red.roundToInt().coerceIn(0, 255),
            green.roundToInt().coerceIn(0, 255),
            blue.roundToInt().coerceIn(0, 255)
        )
    }

    val isLightColor = (0.299f * currentColor.red + 0.587f * currentColor.green + 0.114f * currentColor.blue) > 0.5f
    val contrastTextColor = if (isLightColor) Color.Black else Color.White

    // 常用灵感色推荐
    val quickRecommendations = remember {
        listOf(
            Color(0xFF00BFA5), // 薄荷绿
            Color(0xFF9C27B0), // 薰衣草
            Color(0xFFFF5722), // 珊瑚橙
            Color(0xFF3F51B5), // 靛蓝
            Color(0xFFFFC107), // 琥珀黄
            Color(0xFFF06292), // 玫瑰粉
            Color(0xFF00BCD4), // 天空青
            Color(0xFF8BC34A), // 青柠
            Color(0xFF673AB7), // 深紫
            Color(0xFF795548)  // 暖褐
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "自定义种子色",
                    style = MaterialTheme.typography.titleMediumEmphasized
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. 色彩实时预览块
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(smoothRoundedCorner(12.dp))
                        .background(currentColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, smoothRoundedCorner(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "#${buildHex(red, green, blue)}",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = contrastTextColor
                        )
                        Text(
                            text = "RGB(${red.roundToInt()}, ${green.roundToInt()}, ${blue.roundToInt()})",
                            style = MaterialTheme.typography.labelSmall,
                            color = contrastTextColor.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. HEX 色值直接输入框
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val clean = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                        hexInput = clean
                        val parsed = parseColorOrNull(clean)
                        if (parsed != null && clean.length == 6) {
                            red = parsed.red * 255f
                            green = parsed.green * 255f
                            blue = parsed.blue * 255f
                            isHexError = false
                        } else {
                            isHexError = clean.length == 6
                        }
                    },
                    label = { Text("HEX 色值") },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = isHexError,
                    supportingText = if (isHexError) {
                        { Text("请输入合法的 6 位 16 进制颜色，如 FF8200") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = smoothRoundedCorner(10.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. RGB 滑块调节
                // 红色
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "红 (Red)",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = Color(0xFFE53935)
                    )
                    Text(
                        text = "${red.roundToInt()}",
                        style = MaterialTheme.typography.labelMediumEmphasized
                    )
                }
                Slider(
                    value = red,
                    onValueChange = {
                        red = it
                        hexInput = buildHex(red, green, blue)
                        isHexError = false
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFE53935),
                        activeTrackColor = Color(0xFFE53935)
                    )
                )

                // 绿色
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "绿 (Green)",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = Color(0xFF43A047)
                    )
                    Text(
                        text = "${green.roundToInt()}",
                        style = MaterialTheme.typography.labelMediumEmphasized
                    )
                }
                Slider(
                    value = green,
                    onValueChange = {
                        green = it
                        hexInput = buildHex(red, green, blue)
                        isHexError = false
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF43A047),
                        activeTrackColor = Color(0xFF43A047)
                    )
                )

                // 蓝色
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "蓝 (Blue)",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = Color(0xFF1976D2)
                    )
                    Text(
                        text = "${blue.roundToInt()}",
                        style = MaterialTheme.typography.labelMediumEmphasized
                    )
                }
                Slider(
                    value = blue,
                    onValueChange = {
                        blue = it
                        hexInput = buildHex(red, green, blue)
                        isHexError = false
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF1976D2),
                        activeTrackColor = Color(0xFF1976D2)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. 推荐色彩快捷点选
                Text(
                    text = "灵感色系",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickRecommendations.forEach { qColor ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(qColor)
                                .clickable {
                                    red = qColor.red * 255f
                                    green = qColor.green * 255f
                                    blue = qColor.blue * 255f
                                    hexInput = buildHex(red, green, blue)
                                    isHexError = false
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onColorConfirmed(currentColor)
                }
            ) {
                Text("确定应用", style = MaterialTheme.typography.labelLargeEmphasized)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

