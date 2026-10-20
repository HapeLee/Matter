package org.kazusa.matter.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kazusa.matter.data.model.ChatMessage
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 会话详情：与某个人的私信历史（只读）。
 *
 * 列表用 `reverseLayout` 从底部开始，索引 0 是最新一条；向上滚动到接近末尾时用 `max_id` 继续取更早的页。
 * 发送私信需要 POST 与额外的 CSRF 校验，尚未验证，因此底部明确标注当前只读，不做半成品输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerUid: String,
    peerName: String,
    onBackClick: () -> Unit
) {
    val viewModel: ChatViewModel = koinViewModel { parametersOf(peerUid, peerName) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val backdrop = rememberScreenBackdrop()

    LaunchedEffect(viewModel) { viewModel.loadIfNeeded() }

    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount to (layout.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collect { (total, lastVisible) ->
            val state = viewModel.uiState.value
            if (state.canLoadMore && total > 0 && lastVisible >= total - 3) viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                listState = listState,
                title = { scroll ->
                    TopBarTitleText(
                        text = viewModel.peerName.ifBlank { "私信" },
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.key },
                        contentType = { "message" }
                    ) { message ->
                        MessageBubble(message = message)
                    }

                    if (uiState.messages.isEmpty()) {
                        item(key = "empty") {
                            EmptyMessage(
                                message = uiState.errorMessage
                                    ?: if (uiState.isInitialLoading) "正在加载聊天记录…" else "还没有聊天记录",
                                isLoading = uiState.isInitialLoading,
                                buttonText = if (uiState.errorMessage != null) "重试" else null,
                                onButtonClick = if (uiState.errorMessage != null) {
                                    { viewModel.retry() }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillParentMaxHeight(0.6f)
                            )
                        }
                    }

                    if (uiState.isLoadingMore) {
                        item(key = "loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    if (uiState.messages.isNotEmpty() && uiState.errorMessage != null) {
                        item(key = "load_error") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = uiState.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { viewModel.retry() }) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    if (uiState.messages.isNotEmpty() && !uiState.hasMore && !uiState.isLoadingMore) {
                        item(key = "end") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "没有更早的消息了",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "私信发送暂未开放，当前仅可查看历史消息",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = if (message.fromMe) {
                RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            },
            color = if (message.fromMe) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.text.ifBlank { "（空消息）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                formatChatTime(message.timestampMillis, message.timeText)?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
