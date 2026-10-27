package org.kazusa.matter.ui.composer.post

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.ui.components.composer.ComposerImage
import org.kazusa.matter.ui.components.composer.ComposerPickerSheet
import org.kazusa.matter.ui.components.composer.ComposerToolButton
import org.kazusa.matter.ui.components.composer.OnlineEmojiViewModel
import org.kazusa.matter.ui.components.composer.EmojiDraftState
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH
import org.kazusa.matter.ui.components.composer.WeiboEmojiPanel
import org.kazusa.matter.ui.components.composer.WeiboEmojiTextField
import org.kazusa.matter.ui.components.composer.limitEmojiDraft
import org.kazusa.matter.ui.components.composer.rememberComposerEmojiState
import org.koin.compose.viewmodel.koinViewModel

private val InputMinHeight = 160.dp
private val ImageCellCorner = 12.dp

/**
 * 发微博页（全屏）。
 *
 * 页面只渲染状态并发出事件：草稿、图片、提交与错误都在 [PostComposerViewModel]；
 * 成功后由调用方决定返回位置，时间线通过 [org.kazusa.matter.data.repository.PublishRepository]
 * 的 `published` 事件自行刷新。
 */
@Composable
internal fun PostComposerScreen(
    onDismiss: () -> Unit,
    onPublished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostComposerViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker by viewModel.pickerState.collectAsStateWithLifecycle()
    val onlineEmojiViewModel: OnlineEmojiViewModel = koinViewModel()
    val onlineEmojis by onlineEmojiViewModel.state.collectAsStateWithLifecycle()
    val emojiImages = LocalOnlineEmojiImages.current

    val focusRequester = remember { FocusRequester() }
    val emojiPanel = rememberComposerEmojiState(focusRequester)
    val editorInteractions = remember { MutableInteractionSource() }
    val editorFocused by editorInteractions.collectIsFocusedAsState()
    LaunchedEffect(editorFocused) { if (editorFocused) emojiPanel.onEditorFocused() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val draftEditor = remember { EmojiDraftState(state.draft) }
    val editor = draftEditor.field
    val currentOnDraftChange by rememberUpdatedState(viewModel::updateDraft)
    LaunchedEffect(editor) {
        snapshotFlow { editor.text.toString() }.collect { currentOnDraftChange(draftEditor.publish()) }
    }
    LaunchedEffect(state.draft) { draftEditor.receive(state.draft) }

    // 与评论面板同一套插入语义：@ 覆盖正在输入的半截 @，话题成对包裹后接光标
    fun insert(text: String, prefix: Char? = null) {
        editor.edit {
            var start = selection.min
            if (prefix != null && start > 0) {
                val preceding = toString().take(start)
                val index = preceding.lastIndexOf(prefix)
                val openTopic = prefix != '#' || preceding.count { it == '#' } % 2 == 1
                if (index >= 0 && openTopic && preceding.substring(index + 1).none { it.isWhitespace() }) start = index
            }
            replace(start, selection.max, text)
            val limited = limitEmojiDraft(toString(), emojiImages, MAX_COMPOSER_TEXT_LENGTH)
            if (limited.length < length) delete(limited.length, length)
            selection = TextRange((start + text.length).coerceAtMost(length))
        }
        viewModel.updateDraft(draftEditor.publish())
    }

    fun openPicker(kind: ReplyPickerKind) {
        emojiPanel.dismiss()
        focusManager.clearFocus()
        keyboardController?.hide()
        viewModel.openPicker(kind)
    }

    // 选择器关闭后把焦点还给正文，键盘随之回来（与评论面板一致）
    var restoreEditorFocus by remember { mutableStateOf(false) }
    LaunchedEffect(restoreEditorFocus, picker.kind) {
        if (restoreEditorFocus && picker.kind == null) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
            restoreEditorFocus = false
        }
    }

    var recentEmojis by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedEmojiGroup by rememberSaveable { mutableStateOf("weibo") }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    val hasContent = state.draft.isNotBlank() || state.images.isNotEmpty()

    LaunchedEffect(viewModel) {
        viewModel.published.collect { onPublished() }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_POST_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris.map { ReplyImageHandle(it.toString()) })
    }

    // 进页面直接进入输入态（微博客户端的惯例）；面板收起时返回键交还导航
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BackHandler(enabled = hasContent && !state.isSending) { showDiscardDialog = true }
    // 发布在途时不允许退出：ViewModel 随 NavEntry 销毁，退出会中断上传 / 提交，
    // 用户也无从知道这条微博到底发出去了没有
    BackHandler(enabled = state.isSending) { }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ComposerTopBar(
                draftLength = state.draft.length,
                canSubmit = state.canSubmit,
                enabled = !state.isSending,
                onDismiss = {
                    if (hasContent) showDiscardDialog = true else onDismiss()
                },
                onSubmit = {
                    emojiPanel.dismiss()
                    viewModel.submit()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = InputMinHeight)
                ) {
                    if (editor.text.isEmpty()) {
                        Text(
                            text = "分享新鲜事…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    WeiboEmojiTextField(
                        state = editor,
                        interactionSource = editorInteractions,
                        maxLines = 12,
                        maxLength = MAX_COMPOSER_TEXT_LENGTH,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }

                if (state.images.isNotEmpty()) {
                    ComposerImageGrid(
                        images = state.images,
                        uploadingIndex = state.uploadingIndex,
                        enabled = !state.isSending,
                        onRemove = viewModel::removeImage,
                        onAdd = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }

                state.errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.6.dp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComposerToolButton(
                    imageVector = if (emojiPanel.visible) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = "表情",
                    active = emojiPanel.visible,
                    onClick = { emojiPanel.toggle() }
                )
                ComposerToolButton(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = "添加图片",
                    onClick = {
                        emojiPanel.dismiss()
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                ComposerToolButton(
                    imageVector = Icons.Outlined.AlternateEmail,
                    contentDescription = "插入 @",
                    enabled = !state.isSending,
                    onClick = { openPicker(ReplyPickerKind.MENTION) }
                )
                ComposerToolButton(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = "插入话题",
                    enabled = !state.isSending,
                    onClick = { openPicker(ReplyPickerKind.TOPIC) }
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.isSending) {
                    Text(
                        text = if (state.uploadingIndex >= 0) {
                            "上传图片 ${state.uploadingIndex + 1}/${state.images.size}"
                        } else {
                            "发布中…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            AnimatedVisibility(visible = emojiPanel.visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
                WeiboEmojiPanel(
                    recentEmojis = recentEmojis,
                    online = onlineEmojis,
                    selectedGroup = selectedEmojiGroup,
                    onGroupChange = { selectedEmojiGroup = it },
                    onOpenOnline = onlineEmojiViewModel::openCatalog,
                    onRefreshOnline = onlineEmojiViewModel::refreshCatalog,
                    onInstall = onlineEmojiViewModel::install,
                    onEmojiClick = { code ->
                        insertEmoji(editor, draftEditor, emojiImages, code, currentOnDraftChange)
                        recentEmojis = (listOf(code) + recentEmojis).distinct().take(28)
                    }
                )
            }
        }
    }

    if (picker.kind != null) ComposerPickerSheet(
        state = picker,
        onQueryChange = viewModel::updatePickerQuery,
        onDismiss = {
            viewModel.dismissPicker()
            restoreEditorFocus = true
        },
        onSelected = { suggestion ->
            val mention = picker.kind == ReplyPickerKind.MENTION
            insert(
                if (mention) "@${suggestion.name} " else "#${suggestion.name.trim('#')}#",
                if (mention) '@' else '#'
            )
            viewModel.dismissPicker()
            restoreEditorFocus = true
        }
    )

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃这条微博？") },
            text = { Text("退出后本次输入的内容不会保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDismiss()
                    }
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }
}

@Composable
private fun ComposerTopBar(
    draftLength: Int,
    canSubmit: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss, enabled = enabled) {
            Text(text = "取消", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$draftLength/$MAX_COMPOSER_TEXT_LENGTH",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.heightIn(min = 36.dp)
        ) {
            Text(text = "发布", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 3 列宫格：最后补一个「+」格子，图片不足 9 张时可继续添加。 */
@Composable
private fun ComposerImageGrid(
    images: List<ComposerImage>,
    uploadingIndex: Int,
    enabled: Boolean,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit
) {
    val cells = (images.indices.toList() + if (images.size < MAX_POST_IMAGES) listOf(-1) else emptyList())
        .chunked(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cells.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { index ->
                    if (index == -1) {
                        AddImageCell(
                            enabled = enabled,
                            onClick = onAdd,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ImageCell(
                            image = images[index],
                            uploading = uploadingIndex == index,
                            enabled = enabled,
                            onRemove = { onRemove(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ImageCell(
    image: ComposerImage,
    uploading: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ImageCellCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = image.handle.value,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (image.uploaded != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (uploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
        IconButton(
            onClick = onRemove,
            enabled = enabled && !uploading,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除图片",
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
private fun AddImageCell(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ImageCellCorner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AddPhotoAlternate,
            contentDescription = if (enabled) "添加图片" else "发布中",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** 在光标处插入表情代码，并按表情感知的长度上限截断。 */
private fun insertEmoji(
    editor: TextFieldState,
    draftEditor: EmojiDraftState,
    emojiImages: Map<String, String>,
    code: String,
    onDraftChange: (String) -> Unit
) {
    editor.edit {
        val start = selection.min
        replace(start, selection.max, code)
        val limited = limitEmojiDraft(toString(), emojiImages, MAX_COMPOSER_TEXT_LENGTH)
        if (limited.length < length) delete(limited.length, length)
        selection = TextRange((start + code.length).coerceAtMost(length))
    }
    onDraftChange(draftEditor.publish())
}
