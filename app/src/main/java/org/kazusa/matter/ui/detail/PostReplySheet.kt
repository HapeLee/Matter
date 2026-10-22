package org.kazusa.matter.ui.detail

import androidx.compose.foundation.shape.CircleShape


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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.kazusa.matter.ui.effect.liquid.rememberLiquidSheetShape

/** 输入区最小高度：与微博类客户端的评论面板一致，留出足够的书写空间。 */
private val ReplySheetInputMinHeight = 104.dp

/** 表情快捷条里的候选表情：点一下追加到正文末尾。 */
private val QuickEmojis = listOf(
    "😄", "😂", "🥰", "😎", "🤔", "😭", "😡", "👍", "🎉", "🔥", "🌹", "🐶"
)

/**
 * 正文页的回复面板（类似微博客户端的评论 sheet）。
 *
 * - 从底部滑入，占满宽度，上边缘圆角；遮罩点击 / 系统返回键 / 发布成功都会收起；
 * - 键盘弹起时按 IME 高度整体上移，输入区不会被输入法盖住；
 * - 顶部是「回复@博主」占位的大输入区，下面是工具条（表情 / @ / 话题 / 图片 / 更多）与发布按钮；
 * - 图片与「更多」的接口尚未接入，先以禁用态占位，避免点了没有任何反应。
 *
 * 面板的显隐、草稿与发送状态都在 ViewModel 的 [ReplyComposerState] 里，
 * 这里只负责渲染与事件。
 */
@Composable
internal fun PostReplySheet(
    replyState: StateFlow<ReplyComposerState>,
    placeholder: String,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by replyState.collectAsStateWithLifecycle()
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
                placeholder = placeholder,
                onDraftChange = onDraftChange,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ReplySheetPanel(
    draft: String,
    isSending: Boolean,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    var showEmojiRow by remember { mutableStateOf(false) }
    val canSend = draft.isNotBlank() && !isSending

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
                if (draft.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ReplySheetInputMinHeight)
                        .focusRequester(focusRequester)
                )
            }

            AnimatedVisibility(visible = showEmojiRow) {
                QuickEmojiRow(
                    onEmojiClick = { emoji -> onDraftChange(draft + emoji) }
                )
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
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = "表情",
                    active = showEmojiRow,
                    onClick = { showEmojiRow = !showEmojiRow }
                )
                // 图片与「更多」依赖尚未接入的上传 / 扩展能力，先禁用占位
                SheetToolButton(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "插入图片（暂未支持）",
                    enabled = false,
                    onClick = {}
                )
                SheetToolButton(
                    imageVector = Icons.Outlined.AlternateEmail,
                    contentDescription = "插入 @",
                    onClick = { onDraftChange(draft + "@") }
                )
                SheetToolButton(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = "插入话题",
                    onClick = { onDraftChange(draft + "#") }
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
        }
    }
}

/** 表情快捷条：点选后追加到正文末尾。 */
@Composable
private fun QuickEmojiRow(onEmojiClick: (String) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(QuickEmojis.size) { index ->
            val emoji = QuickEmojis[index]
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onEmojiClick(emoji) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
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
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 面板里的占位文案：与微博客户端一致，写成「回复@博主」。 */
internal fun replyPlaceholderFor(screenName: String?): String =
    screenName?.takeIf { it.isNotBlank() }?.let { "回复@$it" } ?: "写评论…"
