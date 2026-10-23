package org.kazusa.matter.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyComposerPickerSheet(
    state: ReplyPickerState,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelected: (ReplySuggestion) -> Unit
) {
    val kind = state.kind ?: return
    val mention = kind == ReplyPickerKind.MENTION
    val focusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (mention) "@ 用户" else "选择话题", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(if (mention) "搜索昵称" else "搜索或输入话题") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (state.query.isBlank()) item {
                    Text(if (mention) "我的关注" else "热门话题", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                val customTopic = state.query.trim().trim('#').takeIf { !mention && it.isNotBlank() && !it.contains('\n') }
                if (customTopic != null && state.suggestions.none { it.name.trim('#') == customTopic }) item("custom-topic") {
                    ReplySuggestionRow(ReplySuggestion(customTopic, "#$customTopic#", "使用此话题")) {
                        onSelected(ReplySuggestion(customTopic, customTopic))
                    }
                }
                items(state.suggestions, key = { it.id }) { suggestion ->
                    ReplySuggestionRow(suggestion.copy(name = if (mention) suggestion.name else "#${suggestion.name.trim('#')}#")) {
                        onSelected(suggestion)
                    }
                }
                if (state.loading) item("loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                state.error?.let { error -> item("error") {
                    TextButton(onClick = { onQueryChange(state.query) }) { Text("$error · 点击重试", color = MaterialTheme.colorScheme.error) }
                } }
                if (!state.loading && state.error == null && state.suggestions.isEmpty()) item("empty") {
                    Text(if (mention) "没有找到用户，试试完整昵称" else "没有找到话题，可直接使用输入的话题",
                        modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    LaunchedEffect(kind) { focusRequester.requestFocus() }
}

@Composable
private fun ReplySuggestionRow(suggestion: ReplySuggestion, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        suggestion.avatarUrl?.let { url -> AsyncImage(url, contentDescription = null, modifier = Modifier.size(40.dp)) }
        Column(Modifier.weight(1f)) {
            Text(suggestion.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (suggestion.description.isNotBlank()) Text(suggestion.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
