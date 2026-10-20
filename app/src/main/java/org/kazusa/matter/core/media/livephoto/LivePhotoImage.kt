package org.kazusa.matter.core.media.livephoto

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

internal object LivePhotoImage {
    /** JPEG bytes are preserved; other decodable formats are converted, never just renamed. */
    fun normalizeJpeg(file: File) {
        val isJpeg = file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }
        if (isJpeg) return
        // ImageDecoder 同时会应用源图的 EXIF 方向（minSdk 31 ≥ 28，无需 BitmapFactory 兜底）
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
