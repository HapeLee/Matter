package org.kazusa.matter.data.model

/** 由平台选择器提供的图片句柄；业务层不依赖 Android Uri。 */
data class ReplyImageHandle(val value: String)

data class ReplyImageContent(val bytes: ByteArray)

fun interface ReplyImageReader {
    suspend fun read(handle: ReplyImageHandle): ReplyImageContent
}

enum class ReplyPickerKind { MENTION, TOPIC }

data class ReplySuggestion(
    val id: String,
    val name: String,
    val description: String = "",
    val avatarUrl: String? = null
)
