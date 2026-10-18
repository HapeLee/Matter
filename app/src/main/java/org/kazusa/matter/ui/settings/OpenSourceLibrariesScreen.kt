package org.kazusa.matter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import org.kazusa.matter.R
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.SettingCard
import org.kazusa.matter.ui.components.SettingGroup
import org.kazusa.matter.ui.components.SettingItem
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.rememberScreenBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLibrariesScreen(onBackClick: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val listState = rememberLazyListState()
    val backdrop = rememberScreenBackdrop()

    var selectedLibrary by remember { mutableStateOf<Library?>(null) }

    if (selectedLibrary != null) {
        LibraryDetailBottomSheet(
            library = selectedLibrary!!,
            onDismissRequest = { selectedLibrary = null }
        )
    }

    val allLibs = remember(libraries) {
        libraries?.libraries?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    val libraryChunks = remember(allLibs) {
        allLibs.chunked(15)
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "开源组件",
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (libraries == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = innerPadding.calculateTopPadding() + 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 全部开源组件卡片流（保留卡片效果，无项目信息、搜索与首字母分类）
                    libraryChunks.forEachIndexed { index, chunk ->
                        item(key = "chunk_$index") {
                            SettingGroup {
                                chunk.forEach { lib ->
                                    val licenseLabel = lib.licenses.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                                    SettingItem(
                                        title = lib.name,
                                        description = "${lib.uniqueId} · v${lib.artifactVersion ?: "latest"}",
                                        option = licenseLabel?.let { "$it · 点击查看详情" } ?: "点击查看详情",
                                        onClick = { selectedLibrary = lib }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryDetailBottomSheet(
    library: Library,
    onDismissRequest: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = library.uniqueId + (library.artifactVersion?.let { " · v$it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!library.description.isNullOrBlank()) {
                Text(
                    text = library.description!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val devNames = library.developers.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .ifEmpty { listOfNotNull(library.organization?.name?.takeIf { it.isNotBlank() }) }
            if (devNames.isNotEmpty()) {
                SettingCard(cornerRadius = 12.dp) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "开发者 / 组织",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = devNames.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (library.licenses.isNotEmpty()) {
                SettingCard(cornerRadius = 12.dp) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "开源许可证",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        library.licenses.forEach { lic ->
                            Text(
                                text = lic.name + (lic.url?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val websiteUrl = library.website?.takeIf { it.isNotBlank() }
                    ?: library.scm?.url?.takeIf { it.isNotBlank() }

                if (websiteUrl != null) {
                    Button(
                        onClick = {
                            uriHandler.openUri(websiteUrl)
                            onDismissRequest()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("项目主页")
                    }
                }

                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = if (websiteUrl != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
