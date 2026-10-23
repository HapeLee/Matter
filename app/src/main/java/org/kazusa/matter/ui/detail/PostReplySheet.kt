package org.kazusa.matter.ui.detail

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.data.model.OnlineEmojiState
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.ui.effect.liquid.rememberLiquidSheetShape

/** 输入区最小高度：与微博类客户端的评论面板一致，留出足够的书写空间。 */
private val ReplySheetInputMinHeight = 104.dp

/**
 * 正文页的回复面板（类似微博客户端的评论 sheet）。
 *
 * - 从底部滑入，占满宽度，上边缘圆角；遮罩点击 / 系统返回键 / 发布成功都会收起；
 * - 键盘弹起时按 IME 高度整体上移，输入区不会被输入法盖住；
 * - 顶部是「回复@博主」占位的大输入区，下面是工具条（表情 / @ / 话题 / 图片 / 更多）与发布按钮；
 * - 图片通过系统选择器选择；@ 与话题在独立 sheet 中选择，表情面板与输入法互斥。
 *
 * 面板的显隐、草稿与发送状态都在 ViewModel 的 [ReplyComposerState] 里，
 * 这里只负责渲染与事件。
 */
@Composable
internal fun PostReplySheet(
    replyState: StateFlow<ReplyComposerState>,
    onlineEmojiState: StateFlow<OnlineEmojiState>,
    onOpenOnlineEmojis: () -> Unit,
    onRefreshOnlineEmojis: () -> Unit,
    onInstallEmojiPackage: (String) -> Unit,
    pickerState: StateFlow<ReplyPickerState>,
    onBeginImageSelection: () -> Int,
    onImageSelected: (ReplyImageHandle, Int) -> Unit,
    onRemoveImage: () -> Unit,
    onOpenPicker: (ReplyPickerKind) -> Unit,
    onPickerQueryChange: (String) -> Unit,
    onPickerDismiss: () -> Unit,
    placeholder: String,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by replyState.collectAsStateWithLifecycle()
    val onlineEmojis by onlineEmojiState.collectAsStateWithLifecycle()
    val picker by pickerState.collectAsStateWithLifecycle()
    var recentEmojis by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedEmojiGroup by rememberSaveable { mutableStateOf("weibo") }
    var imageOwner by rememberSaveable { mutableStateOf(-1) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImageSelected(ReplyImageHandle(uri.toString()), imageOwner)
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(enabled = state.isOpen) { onDismiss() }

    // 收起后一并收回键盘焦点；发布成功也是走这条路（ViewModel 直接关面板）
    LaunchedEffect(state.isOpen) {
        if (!state.isOpen) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // 遮罩常驻整屏但不拦截触摸（没有 pointer input 时命中测试会穿透到下层）
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isOpen,
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
            visible = state.isOpen,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReplySheetPanel(
                draft = state.draft,
                isSending = state.isSending,
                placeholder = state.target?.let { replyPlaceholderFor(it.screenName) } ?: placeholder,
                errorMessage = state.errorMessage,
                targetId = state.target?.commentId,
                attachment = state.attachment,
                isUploading = state.isUploading,
                picker = picker,
                onlineEmojis = onlineEmojis,
                onOpenOnlineEmojis = onOpenOnlineEmojis,
                onRefreshOnlineEmojis = onRefreshOnlineEmojis,
                onInstallEmojiPackage = onInstallEmojiPackage,
                selectedEmojiGroup = selectedEmojiGroup,
                onEmojiGroupChange = { selectedEmojiGroup = it },
                recentEmojis = recentEmojis,
                onEmojiUsed = { code -> recentEmojis = (listOf(code) + recentEmojis).distinct().take(28) },
                onPickImage = {
                    imageOwner = onBeginImageSelection()
                    keyboardController?.hide()
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveImage = onRemoveImage,
                onOpenPicker = onOpenPicker,
                onPickerQueryChange = onPickerQueryChange,
                onPickerDismiss = onPickerDismiss,
                onDraftChange = onDraftChange,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ReplySheetPanel(
    draft: String,
    errorMessage: String?,
    targetId: Long?,
    attachment: ReplyAttachment?,
    isUploading: Boolean,
    picker: ReplyPickerState,
    onlineEmojis: OnlineEmojiState,
    onOpenOnlineEmojis: () -> Unit,
    onRefreshOnlineEmojis: () -> Unit,
    onInstallEmojiPackage: (String) -> Unit,
    selectedEmojiGroup: String,
    onEmojiGroupChange: (String) -> Unit,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onOpenPicker: (ReplyPickerKind) -> Unit,
    onPickerQueryChange: (String) -> Unit,
    onPickerDismiss: () -> Unit,
    isSending: Boolean,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    var showEmojiRow by remember(targetId) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val editorInteractions = remember { MutableInteractionSource() }
    val editorFocused by editorInteractions.collectIsFocusedAsState()
    LaunchedEffect(editorFocused) { if (editorFocused) showEmojiRow = false }
    BackHandler(enabled = showEmojiRow) { showEmojiRow = false }
    val draftEditor = remember(targetId) { ReplyDraftEditorState(draft) }
    val editor = draftEditor.field
    val emojiImages = LocalOnlineEmojiImages.current
    val canSend = (editor.text.isNotBlank() || attachment != null) && !isSending
    val currentOnDraftChange by androidx.compose.runtime.rememberUpdatedState(onDraftChange)
    LaunchedEffect(editor) {
        snapshotFlow { editor.text.toString() }.collect { currentOnDraftChange(draftEditor.publish()) }
    }
    LaunchedEffect(draft) { draftEditor.receive(draft) }
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
            val limited = limitReplyEmojiDraft(toString(), emojiImages)
            if (limited.length < length) delete(limited.length, length)
            selection = TextRange((start + text.length).coerceAtMost(length))
        }
        onDraftChange(draftEditor.publish())
    }

    fun openPicker(kind: ReplyPickerKind) {
        showEmojiRow = false
        focusManager.clearFocus()
        keyboardController?.hide()
        onOpenPicker(kind)
    }
    var restoreEditorFocus by remember { mutableStateOf(false) }
    fun restoreKeyboard() { restoreEditorFocus = true }
    LaunchedEffect(restoreEditorFocus, picker.kind) {
        if (restoreEditorFocus && picker.kind == null) {
            androidx.compose.runtime.withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
            restoreEditorFocus = false
        }
    }

    // 面板进入组合（滑入动画开始时）就接管焦点，键盘随之上来
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // 键盘与导航栏取并集：键盘弹起时输入区整体上移，收起时只留导航栏高度
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        // 与液态玻璃悬浮组件同款连续曲率（底部两角在屏幕 / 键盘外，取 0）
        shape = rememberLiquidSheetShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                if (editor.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReplyEmojiEditor(
                    state = editor,
                    interactionSource = editorInteractions,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ReplySheetInputMinHeight).focusRequester(focusRequester)
                )
            }

            attachment?.let { selected ->
                Box(Modifier.padding(start = 20.dp, bottom = 8.dp).size(88.dp)) {
                    AsyncImage(selected.image.value, contentDescription = "待发送图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                    IconButton(onClick = onRemoveImage, enabled = !isSending,
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
                        Icon(Icons.Outlined.Close, "移除图片", modifier = Modifier.size(18.dp))
                    }
                    if (isUploading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
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
                SheetToolButton(
                    imageVector = if (showEmojiRow) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = "表情",
                    active = showEmojiRow,
                    onClick = {
                        if (showEmojiRow) {
                            showEmojiRow = false
                            restoreKeyboard()
                        } else {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            showEmojiRow = true
                        }
                    }
                )
                // 评论支持一张附图，使用平台照片选择器。
                SheetToolButton(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = if (attachment == null) "选择图片" else "更换图片",
                    enabled = !isSending,
                    onClick = {
                        showEmojiRow = false
                        focusManager.clearFocus()
                        onPickImage()
                    }
                )
                SheetToolButton(
                    imageVector = Icons.Outlined.AlternateEmail,
                    contentDescription = "插入 @",
                    onClick = { openPicker(ReplyPickerKind.MENTION) }
                )
                SheetToolButton(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = "插入话题",
                    onClick = { openPicker(ReplyPickerKind.TOPIC) }
                )
                SheetToolButton(
                    imageVector = Icons.Outlined.AddCircleOutline,
                    contentDescription = "更多（暂未支持）",
                    enabled = false,
                    onClick = {}
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDraftChange(draftEditor.publish())
                        onSend()
                    },
                    enabled = canSend,
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
                        Text(
                            text = "发布",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            AnimatedVisibility(visible = showEmojiRow) {
                ReplyEmojiPanel(recentEmojis = recentEmojis, online = onlineEmojis,
                    selectedGroup = selectedEmojiGroup, onGroupChange = onEmojiGroupChange,
                    onOpenOnline = onOpenOnlineEmojis, onRefreshOnline = onRefreshOnlineEmojis,
                    onInstall = onInstallEmojiPackage, onEmojiClick = { insert(it); onEmojiUsed(it) })
            }
        }
    }
    if (picker.kind != null) ReplyComposerPickerSheet(
        state = picker,
        onQueryChange = onPickerQueryChange,
        onDismiss = { onPickerDismiss(); restoreKeyboard() },
        onSelected = { suggestion ->
            val token = if (picker.kind == ReplyPickerKind.MENTION) "@${suggestion.name} "
                else "#${suggestion.name.trim('#')}#"
            insert(token, if (picker.kind == ReplyPickerKind.MENTION) '@' else '#')
            onPickerDismiss()
            restoreKeyboard()
        }
    )
}

/** 工具条图标：禁用态只是变淡，不提供点击反馈。 */
@Composable
private fun SheetToolButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 面板里的占位文案：与微博客户端一致，写成「回复@博主」。 */
internal fun replyPlaceholderFor(screenName: String?): String =
    screenName?.takeIf { it.isNotBlank() }?.let { "回复@$it" } ?: "写评论…"
