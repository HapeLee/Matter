package org.kazusa.matter.ui.detail

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.core.util.WeiboTextEntities
import org.kazusa.matter.core.util.weiboEmojiImageModel
import kotlin.math.roundToInt

/** 同步外部草稿时识别自身回显，避免较慢的回显覆盖较新的输入或清除输入法组合态。 */
internal class ReplyDraftEditorState(initialText: String) {
    val field = TextFieldState(initialText, TextRange(initialText.length))
    private var received = initialText
    private val pending = mutableListOf<String>()
    fun publish(): String = field.text.toString().also {
        if (it != received && pending.lastOrNull() != it) pending += it
    }
    fun receive(text: String) {
        if (text == received) return
        received = text
        val echo = pending.indexOf(text)
        if (echo >= 0) {
            pending.subList(0, echo + 1).clear()
            return
        }
        pending.clear()
        if (field.text.toString() != text) field.edit {
            val previous = selection
            replace(0, length, text)
            selection = TextRange(previous.start.coerceAtMost(length), previous.end.coerceAtMost(length))
        }
    }
}

internal data class ReplyEmojiToken(val start: Int, val end: Int, val code: String, val image: String)
private val emojiCode = Regex("\\[[^\\[\\]\\s]{1,64}\\]")
internal fun replyEmojiTokens(text: String, images: Map<String, String>): List<ReplyEmojiToken> =
    emojiCode.findAll(text).mapNotNull { match ->
        weiboEmojiImageModel(match.value, images)?.let {
            ReplyEmojiToken(match.range.first, match.range.last + 1, match.value, it)
        }
    }.toList()

internal fun limitReplyEmojiDraft(text: String, images: Map<String, String>): String {
    val limited = limitReplyDraft(text)
    val cut = replyEmojiTokens(text, images).firstOrNull { it.start < limited.length && it.end > limited.length }
    return if (cut != null) text.take(cut.start) else limited
}

/** 输出变换只压缩视觉文本，复制、草稿及发送仍使用原始表情代码。 */
internal class ReplyEmojiOutputTransformation(
    private val images: Map<String, String>, private val entityColor: Color
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val tokens = replyEmojiTokens(toString(), images)
        tokens.asReversed().forEach { replace(it.start, it.end, "\u3000") }
        var removed = 0
        tokens.forEach { token ->
            val offset = token.start - removed
            addStyle(SpanStyle(color = Color.Transparent, fontSize = 20.sp), offset, offset + 1)
            removed += token.end - token.start - 1
        }
        WeiboTextEntities.find(toString()).forEach { addStyle(SpanStyle(color = entityColor), it.start, it.end) }
    }
}

/** IME 删除一个原始码元时扩展至整枚表情；保留替换输入并让框架维护选区。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class ReplyEmojiInputTransformation(private val images: Map<String, String>) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val tokens = replyEmojiTokens(originalText.toString(), images)
        val edits = (0 until changes.changeCount).map { changes.getRange(it) to changes.getOriginalRange(it) }
        edits.asReversed().forEach { (range, original) ->
            if (!original.collapsed) {
                val touched = tokens.filter { it.start < original.max && it.end > original.min }
                if (touched.isNotEmpty()) {
                    val before = (original.min - touched.first().start).coerceAtLeast(0)
                    val after = (touched.last().end - original.max).coerceAtLeast(0)
                    if (after > 0) delete(range.max, (range.max + after).coerceAtMost(length))
                    if (before > 0) delete((range.min - before).coerceAtLeast(0), range.min)
                }
            }
        }
        val limited = limitReplyEmojiDraft(toString(), images)
        if (limited.length < length) delete(limited.length, length)
    }
}

/** Compose 原生编辑器加表情绘制层；占位符、选区和自动滚动共用同一份文字排版。 */
@Composable
internal fun ReplyEmojiEditor(
    state: TextFieldState,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier
) {
    val images = LocalOnlineEmojiImages.current
    val color = MaterialTheme.colorScheme.primary
    val output = remember(images, color) { ReplyEmojiOutputTransformation(images, color) }
    val input = remember(images) { ReplyEmojiInputTransformation(images) }
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var layout by remember(state) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val text = state.text.toString()
    val tokens = remember(text, images) { replyEmojiTokens(text, images) }
    val visualText = remember(text, tokens) {
        StringBuilder(text).apply { tokens.asReversed().forEach { replace(it.start, it.end, "\u3000") } }.toString()
    }
    BasicTextField(
        state = state, modifier = modifier,
        interactionSource = interactionSource,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
        inputTransformation = input, outputTransformation = output,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(color), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        scrollState = scroll, onTextLayout = { layout = it() },
        decorator = { inner ->
            Box(Modifier.clipToBounds(), propagateMinConstraints = true) {
                inner()
                Box(Modifier.matchParentSize().onSizeChanged { viewportHeight = it.height }.clearAndSetSemantics {}) {
                    val result = layout
                    if (result != null && result.layoutInput.text.text == visualText) {
                        var removed = 0
                        tokens.forEach { token ->
                            val offset = token.start - removed
                            removed += token.end - token.start - 1
                            val rect = result.getBoundingBox(offset)
                            val size = rect.width
                            val top = rect.top + (rect.height - size) / 2 - scroll.value
                            if (size > 0 && top + size >= 0 && top <= viewportHeight) {
                                SubcomposeAsyncImage(token.image, contentDescription = null,
                                    modifier = Modifier.absoluteOffset { IntOffset(rect.left.roundToInt(), top.roundToInt()) }
                                        .size(with(density) { size.toDp() }).clearAndSetSemantics {},
                                    loading = { Text(token.code, fontSize = 8.sp, maxLines = 1) },
                                    error = { Text(token.code, fontSize = 8.sp, maxLines = 1) })
                            }
                        }
                    }
                }
            }
        }
    )
}
