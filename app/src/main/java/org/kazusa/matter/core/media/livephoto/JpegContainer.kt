package org.kazusa.matter.core.media.livephoto

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * 一段可被合成器整段替换的 JPEG 元数据段。
 *
 * [payload] 对 XMP 段是去掉 `http://ns.adobe.com/xap/1.0/\0` 头之后的内容，
 * 对 MPF 段是完整的 `MPF\0…` 内容。
 */
internal data class JpegMetadataSegment(val offset: Long, val length: Int, val payload: ByteArray)

internal data class JpegLayout(
    /** 第一段 EOI 之后的位置（不含），其后可能还有增益图等附属数据 */
    val imageLength: Long,
    val xmpSegments: List<JpegMetadataSegment>,
    val mpfSegments: List<JpegMetadataSegment>,
    /** 以 `Exif\0\0` 开头的 APP1 段 */
    val exifSegments: List<JpegMetadataSegment>,
    /** 开头连续 APPn / COM 段的结束位置（MPF 段插在这里） */
    val metadataEnd: Long,
    /** 最后一个 APP1 段的结束位置（XMP 段插在这里，与 ColorOS 原生实况图顺序一致） */
    val xmpInsert: Long,
    /** SOFn 里的图像宽高，用于补写 EXIF 的 ImageWidth / ImageLength */
    val frameWidth: Int,
    val frameHeight: Int
)

/** Reads JPEG marker boundaries, including progressive scans, without treating metadata as video. */
internal object JpegContainer {
    val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val mpfHeader = "MPF\u0000".toByteArray(Charsets.US_ASCII)
    private val exifHeader = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

    fun read(source: InputStream): JpegLayout? {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        var position = 0L
        fun byte(): Int = input.read().also { if (it >= 0) position++ }
        fun skip(count: Int): Boolean {
            repeat(count) { if (byte() < 0) return false }
            return true
        }
        if (byte() != 0xff || byte() != 0xd8) return null
        val xmpSegments = mutableListOf<JpegMetadataSegment>()
        val mpfSegments = mutableListOf<JpegMetadataSegment>()
        val exifSegments = mutableListOf<JpegMetadataSegment>()
        var inScan = false
        var metadataEnd = -1L
        var xmpInsert = 2L
        var frameWidth = 0
        var frameHeight = 0
        while (true) {
            var value = byte()
            if (inScan) {
                while (value >= 0 && value != 0xff) value = byte()
            }
            if (value != 0xff) return null
            val markerOffset = position - 1
            do { value = byte() } while (value == 0xff)
            if (value < 0) return null
            if (inScan && (value == 0 || value in 0xd0..0xd7)) continue
            inScan = false
            // 开头连续的应用段（EXIF / XMP / ICC 等）到此结束，之后就是图像本体
            if (metadataEnd < 0 && !(value in 0xe0..0xef || value == 0xfe)) metadataEnd = markerOffset
            if (value == 0xd9) {
                return JpegLayout(
                    imageLength = position,
                    xmpSegments = xmpSegments,
                    mpfSegments = mpfSegments,
                    exifSegments = exifSegments,
                    metadataEnd = if (metadataEnd < 0) markerOffset else metadataEnd,
                    xmpInsert = xmpInsert,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight
                )
            }
            if (value == 0xd8 || value == 0) return null
            if (value == 0x01 || value in 0xd0..0xd7) continue
            val high = byte()
            val low = byte()
            if (high < 0 || low < 0) return null
            val length = (high shl 8) + low
            if (length < 2) return null
            // SOF0~SOF15（排除 DHT=C4 / JPG=C8 / DAC=CC）
            if (value in 0xc0..0xcf && value != 0xc4 && value != 0xc8 && value != 0xcc && length >= 7) {
                val precision = byte()
                val heightHigh = byte(); val heightLow = byte()
                val widthHigh = byte(); val widthLow = byte()
                if (precision < 0 || heightHigh < 0 || heightLow < 0 || widthHigh < 0 || widthLow < 0) return null
                frameHeight = (heightHigh shl 8) + heightLow
                frameWidth = (widthHigh shl 8) + widthLow
                if (!skip(length - 7)) return null
            } else if (value == 0xe1 || value == 0xe2) {
                val payload = ByteArray(length - 2)
                for (i in payload.indices) {
                    val next = byte()
                    if (next < 0) return null
                    payload[i] = next.toByte()
                }
                when {
                    value == 0xe1 && payload.startsWith(xmpHeader) -> xmpSegments += JpegMetadataSegment(
                        markerOffset,
                        (position - markerOffset).toInt(),
                        payload.copyOfRange(xmpHeader.size, payload.size)
                    )
                    value == 0xe2 && payload.startsWith(mpfHeader) -> mpfSegments += JpegMetadataSegment(
                        markerOffset,
                        (position - markerOffset).toInt(),
                        payload
                    )
                    value == 0xe1 && payload.startsWith(exifHeader) -> exifSegments += JpegMetadataSegment(
                        markerOffset,
                        (position - markerOffset).toInt(),
                        payload
                    )
                }
                // 新 XMP 段插在最后一个 APP1 之后（ColorOS 原生实况图就是这个顺序）
                if (value == 0xe1) xmpInsert = position
            } else if (!skip(length - 2)) {
                return null
            }
            if (value == 0xda) inScan = true
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
