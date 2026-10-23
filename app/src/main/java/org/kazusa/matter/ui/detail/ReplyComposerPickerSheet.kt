package org.kazusa.matter.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion
import org.kazusa.matter.ui.components.EmptyMessage

/** 面板固定占可用高度的比例：内容始终撑到该高度，打开即一次滑到顶，不再按内容收缩。 */
private const val PickerSheetHeightFraction = 0.86f

/**
 * 回复面板里的 @ 用户 / 话题选择器。
 *
 * - Sheet 只保留 Hidden / Expanded 两个锚点：拖动不会停在半开位置，打开直达顶部（参考 legado 的 AppModalBottomSheet）；
 * - 搜索默认收起，由标题右侧按钮展开，展开时才请求焦点，收起时清空搜索词并收回键盘；
 * - 默认区小标题（我的关注 / 热门话题）与面板标题垂直居中成组、紧跟其正下方，进入搜索后收起；
 * - 建议行与占位共用圆角行样式，加载 / 出错 / 为空统一用 [EmptyMessage] 占位，切换自带过渡动画。
 */
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
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var searchVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(PickerSheetHeightFraction)
                .padding(horizontal = 16.dp)
        ) {
            // 标题与默认区小标题垂直居中成组：小标题紧跟标题正下方，进入搜索后收起
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (mention) "@ 用户" else "选择话题",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 48.dp)
                    )
                    AnimatedVisibility(
                        visible = state.query.isBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            if (mention) "我的关注" else "热门话题",
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) {
                            onQueryChange("")
                            keyboard?.hide()
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (searchVisible) "收起搜索" else "搜索"
                    )
                }
            }
            AnimatedVisibility(visible = searchVisible) {
                PickerSearchField(
                    query = state.query,
                    hint = if (mention) "搜索昵称" else "搜索或输入话题",
                    onQueryChange = onQueryChange,
                    focusRequester = searchFocus,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            LaunchedEffect(searchVisible) {
                if (searchVisible) searchFocus.requestFocus()
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val customTopic = state.query.trim().trim('#').takeIf { !mention && it.isNotBlank() && !it.contains('\n') }
                if (customTopic != null && state.suggestions.none { it.name.trim('#') == customTopic }) item("custom-topic") {
                    ReplySuggestionRow(ReplySuggestion(customTopic, "#$customTopic#", "使用此话题"), modifier = Modifier.animateItem()) {
                        onSelected(ReplySuggestion(customTopic, customTopic))
                    }
                }
                items(state.suggestions, key = { it.id }) { suggestion ->
                    ReplySuggestionRow(
                        suggestion.copy(name = if (mention) suggestion.name else "#${suggestion.name.trim('#')}#"),
                        modifier = Modifier.animateItem()
                    ) {
                        onSelected(suggestion)
                    }
                }
                // 加载 / 出错 / 为空共用同一块占位；加载指示与颜文字之间的过渡由 EmptyMessage 内部完成
                val statusMessage = when {
                    state.loading -> if (state.query.isBlank()) "正在加载…" else "正在搜索…"
                    state.error != null -> state.error
                    state.suggestions.isEmpty() -> when {
                        state.query.isBlank() -> if (mention) "还没有关注任何人" else "暂时没有热门话题"
                        mention -> "没有找到用户，试试完整昵称"
                        else -> "没有找到话题，可直接使用输入的话题"
                    }
                    else -> null
                }
                if (statusMessage != null) item("status") {
                    EmptyMessage(
                        message = statusMessage,
                        isLoading = state.loading,
                        buttonText = state.error?.let { "重试" },
                        onButtonClick = state.error?.let { { onQueryChange(state.query) } },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/** 紧凑的胶囊搜索框：44dp 高，带前置图标与清空按钮，焦点由外部通过 [focusRequester] 控制。 */
@Composable
private fun PickerSearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            Modifier.height(44.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** 单条建议：圆角填充块 + 行内边距，避免点击波纹顶到屏幕边缘；头像圆形裁剪。 */
@Composable
private fun ReplySuggestionRow(
    suggestion: ReplySuggestion,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            suggestion.avatarUrl?.let { url ->
                AsyncImage(
                    url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(suggestion.name, style = MaterialTheme.typography.labelLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (suggestion.description.isNotBlank()) Text(
                    suggestion.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
