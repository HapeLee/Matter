package org.kazusa.matter.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 主题色彩与拾色器 BottomSheet
 * 包含常用预设色彩快捷选取与自定义 HEX/RGB 拾色器
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeColorPickerBottomSheet(
    initialColor: Color,
    isDynamicColor: Boolean,
    onDismissRequest: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var red by remember(initialColor) { mutableFloatStateOf(initialColor.red * 255f) }
    var green by remember(initialColor) { mutableFloatStateOf(initialColor.green * 255f) }
    var blue by remember(initialColor) { mutableFloatStateOf(initialColor.blue * 255f) }

    fun buildHex(r: Float, g: Float, b: Float): String {
        val ri = r.roundToInt().coerceIn(0, 255)
        val gi = g.roundToInt().coerceIn(0, 255)
        val bi = b.roundToInt().coerceIn(0, 255)
        return String.format("%02X%02X%02X", ri, gi, bi)
    }

    var hexInput by remember(initialColor) { mutableStateOf(buildHex(red, green, blue)) }
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        text = "主题色彩",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "选择常用预设色彩或通过拾色器自定义种子色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 1. 预设常用色彩
            Text(
                text = "预设常用色彩",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PresetThemeColors.forEach { preset ->
                    val isSelected = !isDynamicColor && currentColor.value == preset.color.value

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            red = preset.color.red * 255f
                            green = preset.color.green * 255f
                            blue = preset.color.blue * 255f
                            hexInput = buildHex(red, green, blue)
                            isHexError = false
                            onColorSelected(preset.color)
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
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
                                    modifier = Modifier.size(20.dp)
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
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 2. 自定义拾色器
            Text(
                text = "自定义拾色器",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            // 实时预览块
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(currentColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
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

            // HEX 输入框
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
                shape = RoundedCornerShape(10.dp)
            )

            // RGB 滑块
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 红
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

                // 绿
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

                // 蓝
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
            }

            // 灵感色系
            Column {
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

            // 应用按钮
            Button(
                onClick = {
                    onColorSelected(currentColor)
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "应用此色彩",
                    style = MaterialTheme.typography.labelLargeEmphasized
                )
            }
        }
    }
}
