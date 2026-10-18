package org.kazusa.matter.core.media.livephoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

internal object LivePhotoImage {
    /** JPEG bytes are preserved; other decodable formats are converted, never just renamed. */
    fun normalizeJpeg(file: File) {
        val isJpeg = file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }
        if (isJpeg) return
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the source's encoded orientation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath) ?: error("无法解码实况封面图片")
        }
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "无法转换实况封面为 JPEG" }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
