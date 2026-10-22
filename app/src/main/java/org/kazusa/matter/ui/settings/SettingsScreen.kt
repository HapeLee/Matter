package org.kazusa.matter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import kotlinx.coroutines.launch
import org.kazusa.matter.BuildConfig
import org.kazusa.matter.core.datastore.ImageQualityMode
import org.kazusa.matter.core.datastore.MediaPreferenceState
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.SettingGroup
import org.kazusa.matter.ui.components.SettingItem
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.theme.AppThemeState
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.ui.theme.displayName
import org.kazusa.matter.ui.theme.toHexString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themePreferences: ThemePreferences,
    mediaPreferences: MediaPreferences,
    onNavigateToAppearance: () -> Unit,
    onBackClick: () -> Unit,
    onNavigateToOpenSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeState by themePreferences.themeStateFlow.collectAsStateWithLifecycle(initialValue = AppThemeState())
    val mediaState by mediaPreferences.mediaStateFlow.collectAsStateWithLifecycle(initialValue = MediaPreferenceState())
    val coroutineScope = rememberCoroutineScope()

    val isBlurSupported = rememberBlurEffectsEnabled()
    val backdrop = if (isBlurSupported) rememberLayerBackdrop() else null
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                scrollState = scrollState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "设置与外观",
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 外观个性化
            SettingGroup(title = "外观与个性化") {
                SettingItem(
                    title = "主题与调色板",
                    description = if (themeState.dynamicColor)
                        "系统壁纸 Monet 动态色彩"
                    else
                        "种子色 ${themeState.seedColor.toHexString()} · ${themeState.paletteStyle.displayName()} 风格",
                    onClick = onNavigateToAppearance
                )
            }

            // 2. 图片与流量
            SettingGroup(title = "图片与流量") {
                SettingItem(
                    title = "大图清晰度",
                    description = mediaState.imageQualityMode.label(),
                    dropdownMenu = { onDismiss ->
                        ImageQualityMode.entries.forEach { mode ->
                            val isSelected = mediaState.imageQualityMode == mode
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = mode.label(),
                                            style = if (isSelected) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = mode.description(),
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
                                        mediaPreferences.setImageQualityMode(mode)
                                    }
                                    onDismiss()
                                }
                            )
                        }
                    }
                )
            }

            // 3. 关于与系统信息
            SettingGroup(title = "关于应用") {
                SettingItem(
                    title = "开源组件",
                    description = "组件版本、项目链接与开源许可证",
                    onClick = onNavigateToOpenSource
                )
                SettingItem(
                    title = "应用版本",
                    description = "v${BuildConfig.VERSION_NAME} (构建 ${BuildConfig.VERSION_CODE})"
                )
                SettingItem(
                    title = "架构与依赖",
                    description = "Ktor + DataStore + Kolor + Media3"
                )
            }
        }
    }
}

private fun ImageQualityMode.label(): String = when (this) {
    ImageQualityMode.THUMBNAIL_ONLY -> "仅缩略图"
    ImageQualityMode.WIFI_ORIGINAL -> "Wi-Fi 原图"
    ImageQualityMode.ALWAYS_ORIGINAL -> "始终原图"
}

private fun ImageQualityMode.description(): String = when (this) {
    ImageQualityMode.THUMBNAIL_ONLY -> "始终先加载中等档，需要时手动点按查看原图"
    ImageQualityMode.WIFI_ORIGINAL -> "非计费网络自动加载原图，移动网络需手动点按"
    ImageQualityMode.ALWAYS_ORIGINAL -> "始终自动加载原图，最清晰但更耗流量"
}
