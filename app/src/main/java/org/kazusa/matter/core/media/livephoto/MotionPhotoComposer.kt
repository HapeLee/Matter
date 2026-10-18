package org.kazusa.matter.core.media.livephoto

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/** Writes standard JPEG Motion Photos with legacy MicroVideo metadata for older readers.
 * Recognition by a manufacturer's gallery must be verified on that gallery/version.
 */
object MotionPhotoComposer {
    fun compose(
        imageStream: InputStream,
        videoStream: InputStream,
        videoLength: Long,
        outputStream: OutputStream
    ) {
        require(videoLength > 0) { "实况视频为空" }
        val image = imageStream.readBytes()
        val layout = JpegContainer.read(ByteArrayInputStream(image))
        requireNotNull(layout) { "实况封面必须为有效 JPEG 图片" }
        val xmp = buildXmpMeta(videoLength).toByteArray(Charsets.UTF_8)
        val segmentLength = 2 + JpegContainer.xmpHeader.size + xmp.size
        require(segmentLength <= 65535) { "实况元数据过大" }

        outputStream.write(image, 0, 2)
        outputStream.write(byteArrayOf(0xff.toByte(), 0xe1.toByte()))
        outputStream.write(segmentLength shr 8)
        outputStream.write(segmentLength and 0xff)
        outputStream.write(JpegContainer.xmpHeader)
        outputStream.write(xmp)
        // Replace existing standard XMP packets and discard a verified previously appended video.
        // Other JPEG metadata (EXIF, ICC, extended XMP, etc.) remains byte-for-byte intact.
        var imageEnd = image.size
        for (segment in layout.xmpSegments) {
            val previousLength = MotionPhotoParser.videoLengthFromXmp(segment.xml) ?: continue
            if (previousLength <= 0 || previousLength > image.size - layout.imageLength) continue
            val start = image.size - previousLength.toInt()
            val valid = runCatching {
                DataInputStream(ByteArrayInputStream(image, start, previousLength.toInt())).use {
                    MotionPhotoParser.isMp4(it, previousLength)
                }
            }.getOrDefault(false)
            if (valid) {
                imageEnd = start
                break
            }
        }
        var cursor = 2
        for (segment in layout.xmpSegments) {
            outputStream.write(image, cursor, segment.offset.toInt() - cursor)
            cursor = segment.offset.toInt() + segment.length
        }
        // Preserve auxiliary image data after the first EOI (e.g. an Ultra HDR gainmap).
        outputStream.write(image, cursor, imageEnd - cursor)
        val copied = videoStream.copyTo(outputStream)
        require(copied == videoLength) { "实况视频长度不匹配" }
        outputStream.flush()
    }

    private fun buildXmpMeta(videoLength: Long): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
              xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
              xmlns:Container="http://ns.google.com/photos/1.0/container/"
              xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              Camera:MotionPhoto="1"
              Camera:MotionPhotoVersion="1"
              Camera:MotionPhotoPresentationTimestampUs="-1"
              Camera:MicroVideo="1"
              Camera:MicroVideoVersion="1"
              Camera:MicroVideoOffset="$videoLength"
              Camera:MicroVideoPresentationTimestampUs="-1">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()
}
