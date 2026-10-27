package org.kazusa.matter.data.model

/** 由平台选择器提供的图片句柄；业务层不依赖 Android Uri。 */
data class ReplyImageHandle(val value: String)

/**
 * 读出的图片内容与它真实的 MIME。
 *
 * [mimeType] 要一路带到发布接口的 `pic_id`（`{"type":...,"pid":...}`）：
 * 读取器会把 HEIF / WebP 转成 JPEG，但 JPEG / PNG / GIF 是原样透传的，
 * 统一当 JPEG 声明会和图床里的实际格式对不上。
 */
data class ReplyImageContent(val bytes: ByteArray, val mimeType: String = "image/jpeg")

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
