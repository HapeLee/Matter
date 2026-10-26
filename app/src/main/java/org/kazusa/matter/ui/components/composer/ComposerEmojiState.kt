package org.kazusa.matter.ui.components.composer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 表情面板与输入法的互斥状态。
 *
 * 面板展开时收起键盘，点「键盘」键时先收起面板、等一帧再把焦点交还编辑器并唤起输入法
 * （同一帧里 clearFocus 后立刻 show 会失效）。正文回复与私信输入栏共用这一套行为，
 * 各自只决定面板挂在哪里。
 */
@Stable
internal class ComposerEmojiState(
    private val focusManager: FocusManager,
    private val keyboardController: SoftwareKeyboardController?,
    private val focusRequester: FocusRequester,
    private val scope: CoroutineScope
) {
    var visible by mutableStateOf(false)
        private set

    fun open() {
        focusManager.clearFocus()
        keyboardController?.hide()
        visible = true
    }

    fun dismiss() {
        visible = false
    }

    /** 收起面板并把焦点交还编辑器，下一帧唤起输入法。 */
    fun dismissAndRestoreKeyboard() {
        visible = false
        scope.launch {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun toggle() {
        if (visible) dismissAndRestoreKeyboard() else open()
    }

    /** 编辑器重新拿到焦点（用户点了输入框）时让出面板。 */
    fun onEditorFocused() {
        if (visible) visible = false
    }
}

/**
 * @param key 变化时重置面板状态（例如回复的目标评论换了）。
 */
@Composable
internal fun rememberComposerEmojiState(
    focusRequester: FocusRequester,
    key: Any? = Unit
): ComposerEmojiState {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val state = remember(key) {
        ComposerEmojiState(focusManager, keyboardController, focusRequester, scope)
    }
    BackHandler(enabled = state.visible) { state.dismiss() }
    return state
}
