package org.kazusa.matter.ui.composer.repost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.data.model.OnlineEmojiState
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.repostKeyOf
import org.kazusa.matter.ui.components.composer.ComposerImage
import org.kazusa.matter.ui.components.composer.ComposerPickerSheet
import org.kazusa.matter.ui.components.composer.ComposerPickerState
import org.kazusa.matter.ui.components.composer.ComposerSheet
import org.kazusa.matter.ui.components.composer.ComposerToolButton
import org.kazusa.matter.ui.components.composer.EmojiDraftState
import org.kazusa.matter.ui.components.composer.MAX_COMPOSER_TEXT_LENGTH
import org.kazusa.matter.ui.components.composer.WeiboEmojiPanel
import org.kazusa.matter.ui.components.composer.WeiboEmojiTextField
import org.kazusa.matter.ui.components.composer.limitEmojiDraft
import org.kazusa.matter.ui.components.composer.rememberComposerEmojiState

/** 输入区最小高度：与评论面板一致，留出足够的书写空间。 */
private val RepostInputMinHeight = 96.dp

/**
 * 转发面板（底部 sheet）。信息流卡片与正文页共用同一份实现，由导航根统一挂载。
 *
 * - 顶部是被转发微博的只读摘要，下面是转发语输入区；
 * - 转发语允许为空（直接转发），因此「转发」按钮只在提交在途时禁用；
 * - 转发一条转发时，正文里预先带上 `//@上一层用户:转发语` 的嵌套文案（见 [repostChainOf]），
 *   光标落在开头，用户自己的评论写在链前面；
 * - 支持 @ 用户 / # 话题 / 一张配图、「同时评论此微博」与分享范围；
 * - 显隐、草稿与提交状态都在 [RepostComposerViewModel] 里，这里只负责渲染与事件。
 */
@Composable
internal fun RepostSheet(
    state: StateFlow<RepostComposerState>,
    pickerState: StateFlow<ComposerPickerState>,
    onlineEmojiState: StateFlow<OnlineEmojiState>,
    onOpenOnlineEmojis: () -> Unit,
    onRefreshOnlineEmojis: () -> Unit,
    onInstallEmojiPackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAddImage: (ReplyImageHandle) -> Unit,
    onRemoveImage: () -> Unit,
    onAlsoCommentChange: (Boolean) -> Unit,
    onVisibilityChange: (StatusVisibility) -> Unit,
    onOpenPicker: (ReplyPickerKind) -> Unit,
    onPickerQueryChange: (String) -> Unit,
    onPickerDismiss: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val composer by state.collectAsStateWithLifecycle()
    val picker by pickerState.collectAsStateWithLifecycle()
    val onlineEmojis by onlineEmojiState.collectAsStateWithLifecycle()

    // 收起动画期间 target 已经变 null；保留最后一次目标，避免面板内容瞬间变空再滑走
    var lastTarget by remember { mutableStateOf<WeiboStatusDto?>(null) }
    LaunchedEffect(composer.target) {
        composer.target?.let { lastTarget = it }
    }

    // 选择器自己是一个 ModalBottomSheet，开着的时候返回键交给它
    BackHandler(enabled = composer.isOpen && picker.kind == null) { onDismiss() }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = composer.isOpen,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = composer.isOpen,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            (composer.target ?: lastTarget)?.let { target ->
                RepostSheetPanel(
                    target = target,
                    composer = composer,
                    picker = picker,
                    onlineEmojis = onlineEmojis,
                    onOpenOnlineEmojis = onOpenOnlineEmojis,
                    onRefreshOnlineEmojis = onRefreshOnlineEmojis,
                    onInstallEmojiPackage = onInstallEmojiPackage,
                    onDismiss = onDismiss,
                    onDraftChange = onDraftChange,
                    onAddImage = onAddImage,
                    onRemoveImage = onRemoveImage,
                    onAlsoCommentChange = onAlsoCommentChange,
                    onVisibilityChange = onVisibilityChange,
                    onOpenPicker = onOpenPicker,
                    onPickerQueryChange = onPickerQueryChange,
                    onPickerDismiss = onPickerDismiss,
                    onSubmit = onSubmit
                )
            }
        }
    }
}

@Composable
private fun RepostSheetPanel(
    target: WeiboStatusDto,
    composer: RepostComposerState,
    picker: ComposerPickerState,
    onlineEmojis: OnlineEmojiState,
    onOpenOnlineEmojis: () -> Unit,
    onRefreshOnlineEmojis: () -> Unit,
    onInstallEmojiPackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAddImage: (ReplyImageHandle) -> Unit,
    onRemoveImage: () -> Unit,
    onAlsoCommentChange: (Boolean) -> Unit,
    onVisibilityChange: (StatusVisibility) -> Unit,
    onOpenPicker: (ReplyPickerKind) -> Unit,
    onPickerQueryChange: (String) -> Unit,
    onPickerDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val targetKey = remember(target.idStr, target.mid, target.id) { repostKeyOf(target) }
    val draft = composer.draft
    val isSending = composer.isSending
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val emojiPanel = rememberComposerEmojiState(focusRequester, key = targetKey)
    val editorInteractions = remember { MutableInteractionSource() }
    val editorFocused by editorInteractions.collectIsFocusedAsState()
    LaunchedEffect(editorFocused) { if (editorFocused) emojiPanel.onEditorFocused() }

    val draftEditor = remember(targetKey) { EmojiDraftState(draft) }
    val editor = draftEditor.field
    val emojiImages = LocalOnlineEmojiImages.current

    var recentEmojis by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedEmojiGroup by rememberSaveable { mutableStateOf("weibo") }
    var scopeMenuOpen by remember { mutableStateOf(false) }

    // 输入法 / 粘贴 / 表情插入都通过这里回传草稿，ViewModel 负责长度与去抖
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    LaunchedEffect(editor) {
        snapshotFlow { editor.text.toString() }.collect { currentOnDraftChange(draftEditor.publish()) }
    }
    LaunchedEffect(draft) { draftEditor.receive(draft) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 自动填入的嵌套转发文案：光标放到开头，让用户写在自己的话前面
    LaunchedEffect(targetKey) {
        if (composer.draftPrefilled) {
            withFrameNanos { }
            editor.edit { selection = TextRange(0) }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onAddImage(ReplyImageHandle(uri.toString()))
    }

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
        onDraftChange(draftEditor.publish())
    }

    fun openPicker(kind: ReplyPickerKind) {
        emojiPanel.dismiss()
        focusManager.clearFocus()
        keyboardController?.hide()
        onOpenPicker(kind)
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

    ComposerSheet(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "转发微博",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, enabled = !isSending) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            RepostTargetPreview(target = target)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                if (editor.text.isEmpty()) {
                    Text(
                        text = "说点什么…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WeiboEmojiTextField(
                    state = editor,
                    interactionSource = editorInteractions,
                    maxLines = 6,
                    maxLength = MAX_COMPOSER_TEXT_LENGTH,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RepostInputMinHeight)
                        .focusRequester(focusRequester)
                )
            }

            composer.image?.let { selected ->
                RepostImagePreview(
                    image = selected,
                    uploading = composer.uploadingImage,
                    enabled = !isSending,
                    onRemove = onRemoveImage
                )
            }

            composer.errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isSending) {
                            onAlsoCommentChange(!composer.alsoComment)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = composer.alsoComment,
                        onCheckedChange = { onAlsoCommentChange(it) },
                        enabled = !isSending
                    )
                    Text(
                        text = "同时评论此微博",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Box {
                    TextButton(
                        onClick = { scopeMenuOpen = true },
                        enabled = !isSending,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = composer.visibility.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "分享范围",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false }
                    ) {
                        StatusVisibility.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onVisibilityChange(option)
                                    scopeMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.6.dp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComposerToolButton(
                    imageVector = if (emojiPanel.visible) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = "表情",
                    active = emojiPanel.visible,
                    onClick = { emojiPanel.toggle() }
                )
                ComposerToolButton(
                    imageVector = Icons.Outlined.AlternateEmail,
                    contentDescription = "插入 @",
                    enabled = !isSending,
                    onClick = { openPicker(ReplyPickerKind.MENTION) }
                )
                ComposerToolButton(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = "插入话题",
                    enabled = !isSending,
                    onClick = { openPicker(ReplyPickerKind.TOPIC) }
                )
                // 转发配图：一张，与评论一致；再次选择即替换
                ComposerToolButton(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = if (composer.image == null) "添加图片" else "更换图片",
                    enabled = !isSending && !composer.uploadingImage,
                    onClick = {
                        emojiPanel.dismiss()
                        focusManager.clearFocus()
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        emojiPanel.dismiss()
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onSubmit()
                    },
                    enabled = !isSending,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = "转发", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            AnimatedVisibility(visible = emojiPanel.visible) {
                WeiboEmojiPanel(
                    recentEmojis = recentEmojis,
                    online = onlineEmojis,
                    selectedGroup = selectedEmojiGroup,
                    onGroupChange = { selectedEmojiGroup = it },
                    onOpenOnline = onOpenOnlineEmojis,
                    onRefreshOnline = onRefreshOnlineEmojis,
                    onInstall = onInstallEmojiPackage,
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
        onQueryChange = onPickerQueryChange,
        onDismiss = {
            onPickerDismiss()
            restoreEditorFocus = true
        },
        onSelected = { suggestion ->
            val mention = picker.kind == ReplyPickerKind.MENTION
            insert(
                if (mention) "@${suggestion.name} " else "#${suggestion.name.trim('#')}#",
                if (mention) '@' else '#'
            )
            onPickerDismiss()
            restoreEditorFocus = true
        }
    )
}

/** 转发配图预览：本地缩略图 + 移除按钮，上传中盖一层进度。 */
@Composable
private fun RepostImagePreview(
    image: ComposerImage,
    uploading: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 20.dp, bottom = 8.dp)
            .size(88.dp)
    ) {
        AsyncImage(
            model = image.handle.value,
            contentDescription = "待转发图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled && !uploading,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
        ) {
            Icon(Icons.Outlined.Close, "移除图片", modifier = Modifier.size(18.dp))
        }
        if (uploading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
            )
        }
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

/** 被转发微博的只读摘要：头像 + 昵称 + 正文 + 一张缩略图。 */
@Composable
private fun RepostTargetPreview(target: WeiboStatusDto) {
    // 转发一条转发时展示原微博内容，避免把「转发语 + 原博」再套一层
    val preview = target.retweetedStatus ?: target
    val author = preview.user?.screenName
    val avatar = preview.user?.avatarLarge ?: preview.user?.profileImageUrl
    val text = remember(preview.textRaw, preview.text) {
        (preview.textRaw?.takeIf { it.isNotBlank() } ?: preview.text)
            .replace(Regex("<[^>]*>"), "")
            .trim()
    }
    val thumb = remember(preview.picIds, preview.allThumbImageUrls) {
        preview.allThumbImageUrls.firstOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!avatar.isNullOrBlank()) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = author,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = author?.let { "@$it" } ?: "原微博",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (text.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        thumb?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}
