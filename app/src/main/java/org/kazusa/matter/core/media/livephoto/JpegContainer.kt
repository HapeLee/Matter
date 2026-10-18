package org.kazusa.matter.core.media.livephoto

import java.io.BufferedInputStream
import java.io.InputStream

internal data class JpegXmpSegment(val offset: Long, val length: Int, val xml: ByteArray)
internal data class JpegLayout(val imageLength: Long, val xmpSegments: List<JpegXmpSegment>)

/** Reads JPEG marker boundaries, including progressive scans, without treating metadata as video. */
internal object JpegContainer {
    val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    fun read(source: InputStream): JpegLayout? {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        var position = 0L
        fun byte(): Int = input.read().also { if (it >= 0) position++ }
        fun skip(count: Int): Boolean {
            repeat(count) { if (byte() < 0) return false }
            return true
        }
        if (byte() != 0xff || byte() != 0xd8) return null
        val segments = mutableListOf<JpegXmpSegment>()
        var inScan = false
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
            if (value == 0xd9) return JpegLayout(position, segments)
            if (value == 0xd8 || value == 0) return null
            if (value == 0x01 || value in 0xd0..0xd7) continue
            val high = byte()
            val low = byte()
            if (high < 0 || low < 0) return null
            val length = (high shl 8) + low
            if (length < 2) return null
            if (value == 0xe1) {
                val payload = ByteArray(length - 2)
                for (i in payload.indices) {
                    val next = byte()
                    if (next < 0) return null
                    payload[i] = next.toByte()
                }
                if (payload.size >= xmpHeader.size &&
                    payload.copyOfRange(0, xmpHeader.size).contentEquals(xmpHeader)
                ) {
                    segments += JpegXmpSegment(
                        markerOffset, (position - markerOffset).toInt(),
                        payload.copyOfRange(xmpHeader.size, payload.size)
                    )
                }
            } else if (!skip(length - 2)) return null
            if (value == 0xda) inScan = true
        }
    }
}
