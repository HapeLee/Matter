package org.kazusa.matter.ui.settings

import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.launch
import org.kazusa.matter.core.datastore.ImageQualityMode
import org.kazusa.matter.core.datastore.MediaPreferenceState
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.ui.components.ImmersiveTopBar
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
    modifier: Modifier = Modifier
) {
    val themeState by themePreferences.themeStateFlow.collectAsStateWithLifecycle(initialValue = AppThemeState())
    val mediaState by mediaPreferences.mediaStateFlow.collectAsStateWithLifecycle(initialValue = MediaPreferenceState())
    val coroutineScope = rememberCoroutineScope()

    val isBlurSupported = remember { isRuntimeShaderSupported() }
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
            Text(
                text = "外观与个性化",
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
                ListItem(
                    modifier = Modifier.clickable { onNavigateToAppearance() },
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
                            text = "主题与调色板",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (themeState.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                "系统壁纸 Monet 动态色彩"
                            else
                                "种子色 ${themeState.seedColor.toHexString()} · ${themeState.paletteStyle.displayName()} 风格",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            // 2. 图片与流量
            Text(
                text = "图片与流量",
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
                var qualityMenuExpanded by remember { mutableStateOf(false) }

                ListItem(
                    modifier = Modifier.clickable { qualityMenuExpanded = true },
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
                            text = "大图清晰度",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    supportingContent = {
                        Text(
                            text = mediaState.imageQualityMode.description(),
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
                                    .clickable { qualityMenuExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = mediaState.imageQualityMode.label(),
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
                                expanded = qualityMenuExpanded,
                                onDismissRequest = { qualityMenuExpanded = false }
                            ) {
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
                                            qualityMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }

            // 3. 关于与系统信息
            Text(
                text = "关于应用",
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
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = "应用版本",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    trailingContent = {
                        Text(
                            text = "v1.0.0 (Compose M3)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = "架构与依赖",
                            style = MaterialTheme.typography.bodyLargeEmphasized
                        )
                    },
                    trailingContent = {
                        Text(
                            text = "Ktor + DataStore + Kolor + Media3",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
