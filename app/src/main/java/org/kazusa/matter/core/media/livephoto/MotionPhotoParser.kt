package org.kazusa.matter.core.media.livephoto

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.DataInput
import java.io.RandomAccessFile
import javax.xml.parsers.DocumentBuilderFactory

data class MotionPhotoInfo(val fileLength: Long, val videoOffset: Long, val videoLength: Long)

/** JPEG Motion Photo reader. Container metadata is namespace-aware; fallback starts after JPEG EOI. */
object MotionPhotoParser {
    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

    fun parse(file: File): MotionPhotoInfo? = runCatching {
        val layout = file.inputStream().use { JpegContainer.read(it) } ?: return null
        RandomAccessFile(file, "r").use { input ->
            val fileLength = input.length()
            if (layout.imageLength >= fileLength) return null
            for (segment in layout.xmpSegments) {
                val metadata = readMetadata(segment.xml) ?: continue
                if (metadata.disabled) return null
                val length = metadata.videoLength ?: continue
                if (length > 0 && length <= fileLength - layout.imageLength) {
                    val start = fileLength - length
                    if (isMp4(input, start, fileLength)) return MotionPhotoInfo(fileLength, start, length)
                }
            }
            // No metadata: only accept a structurally valid MP4 immediately following the JPEG.
            if (isMp4(input, layout.imageLength, fileLength)) {
                MotionPhotoInfo(fileLength, layout.imageLength, fileLength - layout.imageLength)
            } else null
        }
    }.getOrNull()

    fun extractVideo(sourceFile: File, destFile: File): Boolean {
        // Opening the output must never truncate the source itself (including aliases).
        if (runCatching { sourceFile.canonicalFile == destFile.canonicalFile }.getOrDefault(true)) return false
        val info = parse(sourceFile) ?: return false
        return try {
            RandomAccessFile(sourceFile, "r").use { input ->
                input.seek(info.videoOffset)
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = info.videoLength
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        check(read > 0) { "实况视频不完整" }
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            true
        } catch (_: Exception) {
            destFile.delete()
            false
        }
    }

    private data class Metadata(val disabled: Boolean, val videoLength: Long?)

    internal fun videoLengthFromXmp(xml: ByteArray): Long? =
        readMetadata(xml)?.takeUnless { it.disabled }?.videoLength

    internal fun isVideoFile(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { isMp4(it, 0, it.length()) }
    }.getOrDefault(false)

    private fun readMetadata(xml: ByteArray): Metadata? = runCatching {
        val text = xml.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's parser doesn't implement every JAXP feature. Reject DTDs above as well.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }.parse(ByteArrayInputStream(xml))
        val all = document.getElementsByTagName("*")
        var legacyLength: Long? = null
        var videoLength: Long? = null
        var motionFlag: Long? = null
        var legacyFlag: Long? = null
        fun cameraProperty(element: Element, name: String): String? =
            element.getAttributeNS(CAMERA_NS, name).takeIf { it.isNotBlank() }
                ?: element.takeIf { it.namespaceURI == CAMERA_NS && it.localName == name }?.textContent?.trim()
        for (i in 0 until all.length) {
            val element = all.item(i) as? Element ?: continue
            cameraProperty(element, "MotionPhoto")?.toLongOrNull()?.let { motionFlag = it }
            cameraProperty(element, "MicroVideo")?.toLongOrNull()?.let { legacyFlag = it }
            cameraProperty(element, "MicroVideoOffset")?.toLongOrNull()?.let { legacyLength = it }
            if (element.namespaceURI == CONTAINER_NS && element.localName == "Item" &&
                element.getAttributeNS(ITEM_NS, "Semantic") == "MotionPhoto" &&
                element.getAttributeNS(ITEM_NS, "Mime") in setOf("video/mp4", "video/quicktime")
            ) videoLength = element.getAttributeNS(ITEM_NS, "Length").toLongOrNull()
        }
        // Modern metadata takes precedence over the obsolete MicroVideo flag.
        val disabled = motionFlag?.let { it <= 0 } ?: legacyFlag?.let { it <= 0 } ?: false
        Metadata(disabled, videoLength ?: legacyLength)
    }.getOrNull()

    /** Validate box sizes, ftyp, and the presence of both movie metadata and media data. */
    private fun isMp4(input: RandomAccessFile, start: Long, end: Long): Boolean {
        if (start < 0 || end - start < 24) return false
        input.seek(start)
        return isMp4(input, end - start)
    }

    internal fun isMp4(input: DataInput, length: Long): Boolean {
        if (length < 24) return false
        var position = 0L
        var movie = false
        var media = false
        while (position < length) {
            if (length - position < 8) return false
            var size = input.readInt().toLong() and 0xffffffffL
            val type = input.readInt()
            var header = 8L
            if (size == 1L) {
                if (length - position < 16) return false
                size = input.readLong()
                header = 16L
            } else if (size == 0L) size = length - position
            if (size < header || size > length - position) return false
            if (position == 0L && (type != 0x66747970 || size < header + 8)) return false // ftyp
            if (type == 0x6d6f6f76 && size > header) movie = true // moov
            if (type == 0x6d646174 && size > header) media = true // mdat
            var toSkip = size - header
            while (toSkip > 0) {
                val skipped = input.skipBytes(minOf(toSkip, Int.MAX_VALUE.toLong()).toInt())
                if (skipped <= 0) return false
                toSkip -= skipped
            }
            position += size
        }
        return movie && media
    }
}
