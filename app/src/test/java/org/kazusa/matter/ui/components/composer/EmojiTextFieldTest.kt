package org.kazusa.matter.ui.components.composer

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.ui.detail.MAX_REPLY_LENGTH
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EmojiTextFieldTest {
    private val images = mapOf("[在线]" to "https://face.t.sinajs.cn/online.png")

    @Test fun visualOutputMixesLocalOnlineAndUnicodeButRetainsUnknownCodes() {
        val raw = "你好[微笑][在线]😄\n@博主 #话题# [未知表情]"
        val source = TextFieldState(raw)
        val display = TextFieldState(source.text.toString())
        display.edit { with(EmojiOutputTransformation(images, Color.Blue)) { transformOutput() } }
        assertEquals("你好\u3000\u3000😄\n@博主 #话题# [未知表情]", display.text.toString())
        assertEquals(raw, source.text.toString())
        assertEquals(listOf("[微笑]", "[在线]"), weiboEmojiTokens(raw, images).map { it.code })
    }

    private fun edit(raw: String, start: Int, end: Int, replacement: String = ""): TextFieldState {
        val field = TextFieldState(raw, TextRange(end))
        field.edit {
            replace(start, end, replacement)
            selection = TextRange(start + replacement.length)
            with(EmojiInputTransformation(images, MAX_REPLY_LENGTH)) { transformInput() }
        }
        return field
    }

    @Test fun backspaceAndForwardDeleteRemoveWholeEmojiAndKeepSurroundingText() {
        val backspace = edit("甲[微笑]乙", 4, 5)
        assertEquals("甲乙", backspace.text.toString())
        assertEquals(TextRange(1), backspace.selection)
        val forward = edit("甲[在线]乙", 1, 2)
        assertEquals("甲乙", forward.text.toString())
        assertEquals(TextRange(1), forward.selection)
    }

    @Test fun replacingPartialEmojiPreservesReplacementAndAdjacentEmoji() {
        val field = edit("甲[微笑][在线]乙", 2, 4, "新")
        assertEquals("甲新[在线]乙", field.text.toString())
        assertEquals(TextRange(2), field.selection)
        assertEquals("甲替换乙", edit("甲[微笑][在线]乙", 3, 7, "替换").text.toString())
    }

    @Test fun plainTypingAndUnknownBracketsKeepNormalEditingBehavior() {
        assertEquals("甲[未知表情乙", edit("甲[未知表情]乙", 6, 7).text.toString())
        assertEquals("拼音输入😄", edit("拼音😄", 2, 2, "输入").text.toString())
    }

    @Test fun lengthLimitDoesNotLeaveHalfAnEmojiOrSurrogatePair() {
        val prefix = "甲".repeat(MAX_REPLY_LENGTH - 2)
        assertEquals(prefix, limitEmojiDraft(prefix + "[在线]", images, MAX_REPLY_LENGTH))
        assertEquals(
            "甲".repeat(MAX_REPLY_LENGTH - 1),
            limitEmojiDraft("甲".repeat(MAX_REPLY_LENGTH - 1) + "😄", images, MAX_REPLY_LENGTH)
        )
        val field = edit(prefix, prefix.length, prefix.length, "[在线]")
        assertEquals(prefix, field.text.toString())
    }

    @Test fun clearRemovesTypedTextEvenWhenExternalDraftWasEmpty() {
        val editor = EmojiDraftState("")
        editor.field.edit { append("你好[微笑]") }
        editor.publish()
        editor.clear()
        assertEquals("", editor.field.text.toString())

        // 清空后外部再回填同一段文本（发送失败）仍然能恢复
        editor.receive("你好")
        assertEquals("你好", editor.field.text.toString())
    }

    @Test fun delayedDraftEchoDoesNotOverwriteNewerTypingOrMoveSelection() {
        val editor = EmojiDraftState("")
        editor.field.edit { append("甲") }
        assertEquals("甲", editor.publish())
        editor.field.edit { append("乙[微笑]"); selection = TextRange(2) }
        editor.publish()
        editor.receive("甲")
        assertEquals("甲乙[微笑]", editor.field.text.toString())
        assertEquals(TextRange(2), editor.field.selection)
        editor.receive("甲乙[微笑]")
        assertEquals(TextRange(2), editor.field.selection)
        editor.receive("")
        assertEquals("", editor.field.text.toString())
        assertEquals(TextRange.Zero, editor.field.selection)
    }
}
