package org.kazusa.matter.core.media.livephoto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class MotionPhotoTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()

    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeInt(8 + payload.size)
            writeBytes(type)
            write(payload)
        }
    }.toByteArray()

    // Structural ISO BMFF fixture; decoding/codec compatibility requires real-device validation.
    private fun video(payloadSize: Int = 16): ByteArray =
        box("ftyp", "mp42\u0000\u0000\u0000\u0000isommp42".toByteArray()) +
            box("moov", box("mvhd", ByteArray(16))) + box("mdat", ByteArray(payloadSize) { 7 })

    private fun compose(image: ByteArray = jpeg(), clip: ByteArray = video()): ByteArray =
        ByteArrayOutputStream().also {
            MotionPhotoComposer.compose(image.inputStream(), clip.inputStream(), clip.size.toLong(), it)
        }.toByteArray()

    private fun appSegment(marker: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeByte(0xff); writeByte(marker); writeShort(payload.size + 2); write(payload)
        }
    }.toByteArray()

    private fun withXmp(image: ByteArray, xml: String): ByteArray =
        image.take(2).toByteArray() + appSegment(0xe1, JpegContainer.xmpHeader + xml.toByteArray()) + image.drop(2).toByteArray()

    private fun file(data: ByteArray) = tempFolder.newFile().apply { writeBytes(data) }

    @Test fun composeAndExtractPreservesVideoBytes() {
        val clip = video()
        val source = file(compose(clip = clip))
        val info = MotionPhotoParser.parse(source)
        assertNotNull(info)
        assertEquals(clip.size.toLong(), info!!.videoLength)
        val extracted = tempFolder.newFile()
        assertTrue(MotionPhotoParser.extractVideo(source, extracted))
        assertArrayEquals(clip, extracted.readBytes())
        assertNotNull(ImageIO.read(source))
    }

    @Test fun xmpIsValidNamespaceAwareXmlAndUsesStandardDirectory() {
        val output = compose()
        val layout = JpegContainer.read(output.inputStream())!!
        assertEquals(1, layout.xmpSegments.size)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(layout.xmpSegments.single().xml))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
        assertEquals("1", description.getAttributeNS("http://ns.google.com/photos/1.0/camera/", "MotionPhoto"))
        val items = document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item")
        assertEquals(2, items.length)
        assertEquals("0", (items.item(0) as org.w3c.dom.Element).getAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Length"))
        assertEquals(video().size.toString(), (items.item(1) as org.w3c.dom.Element).getAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Length"))
    }

    @Test fun replacingExistingMotionPhotoLeavesOnlyNewVideoAndOneXmpPacket() {
        val source = compose()
        val newClip = video(32)
        val output = compose(source, newClip)
        assertEquals(1, JpegContainer.read(output.inputStream())!!.xmpSegments.size)
        val result = file(output)
        val extracted = tempFolder.newFile()
        assertTrue(MotionPhotoParser.extractVideo(result, extracted))
        assertArrayEquals(newClip, extracted.readBytes())
    }

    @Test fun exifAndJpegPixelsArePreserved() {
        val original = jpeg()
        val exif = appSegment(0xe1, "Exif\u0000\u0000test".toByteArray())
        val image = original.take(2).toByteArray() + exif + original.drop(2).toByteArray()
        val output = compose(image)
        val layout = JpegContainer.read(output.inputStream())!!
        val xmp = layout.xmpSegments.single()
        val restored = output.take(xmp.offset.toInt()).toByteArray() +
            output.copyOfRange((xmp.offset + xmp.length).toInt(), layout.imageLength.toInt())
        assertArrayEquals(image, restored)
    }

    @Test fun auxiliaryImageAfterPrimaryEoiIsPreserved() {
        val image = jpeg() + jpeg()
        val output = compose(image)
        val layout = JpegContainer.read(output.inputStream())!!
        val packet = layout.xmpSegments.single()
        val restored = output.take(packet.offset.toInt()).toByteArray() +
            output.copyOfRange((packet.offset + packet.length).toInt(), output.size - video().size)
        assertArrayEquals(image, restored)
    }

    @Test fun nonJpegIsRejectedBeforeWritingOutput() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            MotionPhotoComposer.compose(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47).inputStream(), video().inputStream(), video().size.toLong(), output)
        }
        assertEquals(0, output.size())
    }

    @Test fun mismatchedVideoLengthFails() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionPhotoComposer.compose(jpeg().inputStream(), video().inputStream(), 123456, ByteArrayOutputStream())
        }
    }

    @Test fun fallbackFindsVideoBeforeFirstKilobyteAndFirstTwentyPercent() {
        val image = jpeg()
        val clip = video(20000)
        assertTrue(image.size < 1024 && image.size < (image.size + clip.size) / 5)
        val info = MotionPhotoParser.parse(file(image + clip))!!
        assertEquals(image.size.toLong(), info.videoOffset)
        assertEquals(clip.size.toLong(), info.videoLength)
    }

    @Test fun jpegCommentContainingCompleteMp4IsNotMotionPhoto() {
        val image = jpeg()
        val comment = appSegment(0xfe, ByteArray(2000) + video() + ByteArray(2000))
        val normal = file(image.take(2).toByteArray() + comment + image.drop(2).toByteArray())
        assertNotNull(ImageIO.read(normal))
        assertNull(MotionPhotoParser.parse(normal))
    }

    @Test fun normalImageAndStandaloneMp4AreNotMotionPhotos() {
        assertNull(MotionPhotoParser.parse(file(jpeg())))
        assertNull(MotionPhotoParser.parse(file(video())))
    }

    @Test fun truncatedVideoAndFtypOnlyAreRejected() {
        assertNull(MotionPhotoParser.parse(file(jpeg() + video().dropLast(3).toByteArray())))
        assertNull(MotionPhotoParser.parse(file(jpeg() + box("ftyp", ByteArray(16)))))
    }

    @Test fun parsesContainerOnlyMetadataWithDifferentPrefixesAndPadding() {
        val clip = video()
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:c="http://ns.google.com/photos/1.0/container/" xmlns:i="http://ns.google.com/photos/1.0/container/item/">
            <c:Item i:Semantic="Primary" i:Length="0"/><c:Item i:Semantic="MotionPhoto" i:Mime="video/mp4" i:Length="${clip.size}"/>
            <unrelated xmlns:other="urn:test" other:Length="123"/>
            </x:xmpmeta>"""
        val image = withXmp(jpeg(), xml)
        val info = MotionPhotoParser.parse(file(image + ByteArray(32) + clip))!!
        assertEquals((image.size + 32).toLong(), info.videoOffset)
        assertEquals(clip.size.toLong(), info.videoLength)
    }

    @Test fun legacyOffsetSupportsArbitraryPrefix() {
        val clip = video()
        val image = withXmp(jpeg(), """<x xmlns:c="http://ns.google.com/photos/1.0/camera/" c:MicroVideoOffset="${clip.size}"/>""")
        assertEquals(clip.size.toLong(), MotionPhotoParser.parse(file(image + ByteArray(8) + clip))!!.videoLength)
    }

    @Test fun explicitDisabledMotionPhotoIsRespected() {
        val image = withXmp(jpeg(), """<x xmlns:c="http://ns.google.com/photos/1.0/camera/" c:MotionPhoto="0"/>""")
        assertNull(MotionPhotoParser.parse(file(image + video())))
    }

    @Test fun modernFlagOverridesObsoleteDisabledFlag() {
        val image = withXmp(jpeg(), """<x xmlns:c="http://ns.google.com/photos/1.0/camera/" c:MotionPhoto="1" c:MicroVideo="0"/>""")
        assertNotNull(MotionPhotoParser.parse(file(image + video())))
    }

    @Test fun legacyOffsetCanBeAnXmlElement() {
        val clip = video()
        val image = withXmp(jpeg(), """<x xmlns:c="http://ns.google.com/photos/1.0/camera/"><c:MicroVideoOffset>${clip.size}</c:MicroVideoOffset></x>""")
        assertEquals(clip.size.toLong(), MotionPhotoParser.parse(file(image + ByteArray(8) + clip))!!.videoLength)
    }

    @Test fun metadataCannotPointInsideJpeg() {
        val clip = video()
        val image = withXmp(jpeg(), """<x xmlns:c="http://ns.google.com/photos/1.0/camera/" c:MicroVideoOffset="99999999"/>""")
        // Invalid metadata cannot make unrelated trailing bytes into a video.
        assertNull(MotionPhotoParser.parse(file(image + ByteArray(16) + clip)))
    }

    @Test fun extractingToSamePathDoesNotTruncateSource() {
        val data = compose()
        val source = file(data)
        assertFalse(MotionPhotoParser.extractVideo(source, source))
        assertArrayEquals(data, source.readBytes())
    }
}
