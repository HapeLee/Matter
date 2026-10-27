package org.kazusa.matter.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kazusa.matter.data.model.ReplyImageContent
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader

/** 在 IO 线程读取系统选择器授权的内容；HEIF / WebP 等转换成上传可接受的 JPEG。 */
class AndroidReplyImageReader(context: Context) : ReplyImageReader {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(handle: ReplyImageHandle): ReplyImageContent = withContext(Dispatchers.IO) {
        val uri = Uri.parse(handle.value)
        require(uri.scheme == "content") { "请选择系统相册中的图片" }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "图片不能超过 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取图片，请重新选择")
        require(bytes.isNotEmpty()) { "图片内容为空，请重新选择" }
        val mime = resolver.getType(uri)
        if (mime in setOf("image/jpeg", "image/png", "image/gif")) {
            ReplyImageContent(bytes, mime!!)
        } else {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 4096) decoder.setTargetSize(
                    (info.size.width.toLong() * 4096 / longest).toInt().coerceAtLeast(1),
                    (info.size.height.toLong() * 4096 / longest).toInt().coerceAtLeast(1))
            }
            try {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "图片转换失败" }
                ReplyImageContent(output.toByteArray(), "image/jpeg")
            } finally { bitmap.recycle() }
        }
    }

    private companion object { const val MAX_BYTES = 20 * 1024 * 1024 }
}
