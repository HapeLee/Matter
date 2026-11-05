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

    private fun compose(
        image: ByteArray = jpeg(),
        clip: ByteArray = video(),
        oplusMarkers: Boolean = true
    ): ByteArray = ByteArrayOutputStream().also {
        MotionPhotoComposer.compose(
            imageStream = image.inputStream(),
            videoStream = clip.inputStream(),
            videoLength = clip.size.toLong(),
            outputStream = it,
            oplusMarkers = oplusMarkers
        )
    }.toByteArray()

    private fun appSegment(marker: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeByte(0xff); writeByte(marker); writeShort(payload.size + 2); write(payload)
        }
    }.toByteArray()

    private fun withXmp(image: ByteArray, xml: String): ByteArray =
        image.take(2).toByteArray() + appSegment(0xe1, JpegContainer.xmpHeader + xml.toByteArray()) + image.drop(2).toByteArray()

    private fun file(data: ByteArray) = tempFolder.newFile().apply { writeBytes(data) }

    /** 去掉合成器插入的 XMP / MPF（以及补写的 EXIF）段，剩下的应该与原图逐字节一致。 */
    private fun stripMetadata(
        output: ByteArray,
        layout: JpegLayout,
        end: Int = layout.imageLength.toInt(),
        dropExif: Boolean = false
    ): ByteArray {
        val drop = (layout.xmpSegments + layout.mpfSegments + if (dropExif) layout.exifSegments else emptyList())
            .sortedBy { it.offset }
        val restored = ByteArrayOutputStream()
        var cursor = 0
        for (segment in drop) {
            restored.write(output, cursor, segment.offset.toInt() - cursor)
            cursor = segment.offset.toInt() + segment.length
        }
        restored.write(output, cursor, end - cursor)
        return restored.toByteArray()
    }

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xff) shl 24) or ((data[at + 1].toLong() and 0xff) shl 16) or
            ((data[at + 2].toLong() and 0xff) shl 8) or (data[at + 3].toLong() and 0xff)

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
            .newDocumentBuilder().parse(ByteArrayInputStream(layout.xmpSegments.single().payload))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
        assertEquals("1", description.getAttributeNS("http://ns.google.com/photos/1.0/camera/", "MotionPhoto"))
        val items = document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item")
        assertEquals(2, items.length)
        val primary = items.item(0) as org.w3c.dom.Element
        assertEquals("Primary", primary.getAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Semantic"))
        // 主图项不写 Length（与 ColorOS 上可播放的实况图一致），视频项 Length 必须精确
        assertFalse(primary.hasAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Length"))
        assertEquals(video().size.toString(), (items.item(1) as org.w3c.dom.Element).getAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Length"))
    }

    @Test fun xmpCarriesGoogleAndOplusDetectionFields() {
        // 前缀在规范里无意义，但读取方按字面前缀检索；OPPO / 一加相册还只看 OpCamera:* 私有字段
        // （ColorOS 上确认可播放的实况图就这样写），缺了它文件只会被当成静态 JPEG。
        val xml = String(
            JpegContainer.read(compose().inputStream())!!.xmpSegments.single().payload,
            Charsets.UTF_8
        )
        // ColorOS 可用的实况图直接以 x:xmpmeta 开头，不带 xpacket 包裹
        assertTrue(xml.startsWith("<x:xmpmeta"))
        assertTrue(xml.contains("xmlns:GCamera="))
        assertTrue(xml.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xml.contains("GCamera:MotionPhotoPresentationTimestampUs=\"0\""))
        assertTrue(xml.contains("xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\""))
        assertTrue(xml.contains("OpCamera:OLivePhotoVersion=\"2\""))
        assertTrue(xml.contains("OpCamera:VideoLength=\"${video().size}\""))
        assertTrue(xml.contains("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\""))
        assertFalse(xml.contains("xmlns:Camera="))
        assertFalse(xml.contains("PresentationTimestampUs=\"-1\""))
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

    @Test fun existingExifSegmentStaysBeforeXmp() {
        // ColorOS 上可播放的实况图：EXIF 是第一个 APP1，XMP 紧跟在它后面（再后面才是 APP0/ICC）
        val original = jpeg()
        val exif = appSegment(0xe1, "Exif\u0000\u0000test".toByteArray())
        val output = compose(original.take(2).toByteArray() + exif + original.drop(2).toByteArray())
        val layout = JpegContainer.read(output.inputStream())!!
        // 偏移 2..3 = FF E1，4..5 = 段长，6 起是段负载
        assertEquals("Exif", String(output.copyOfRange(6, 10), Charsets.US_ASCII))
        assertEquals((2 + exif.size).toLong(), layout.xmpSegments.single().offset)
    }

    @Test fun exifAndJpegPixelsArePreserved() {
        val original = jpeg()
        val exif = appSegment(0xe1, "Exif\u0000\u0000test".toByteArray())
        val image = original.take(2).toByteArray() + exif + original.drop(2).toByteArray()
        val output = compose(image)
        val layout = JpegContainer.read(output.inputStream())!!
        assertArrayEquals(image, stripMetadata(output, layout))
    }

    @Test fun auxiliaryImageAfterPrimaryEoiIsPreserved() {
        val image = jpeg() + jpeg()
        val output = compose(image)
        val layout = JpegContainer.read(output.inputStream())!!
        assertArrayEquals(image, stripMetadata(output, layout, output.size - video().size, dropExif = true))
    }

    @Test fun nonOplusDevicesGetPlainGoogleMotionPhoto() {
        // 非 OPPO / 一加 / realme：只写 Google 标准字段，不补 EXIF、不写 OpCamera / MPF
        val output = compose(oplusMarkers = false)
        val layout = JpegContainer.read(output.inputStream())!!
        val xml = String(layout.xmpSegments.single().payload, Charsets.UTF_8)
        assertTrue(xml.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xml.contains("Container:Directory"))
        assertFalse(xml.contains("OpCamera"))
        // 仍是合法 XMP（能被命名空间感知的解析器读出来）
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(layout.xmpSegments.single().payload))
        assertEquals(
            2,
            document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item").length
        )
        assertTrue(layout.exifSegments.isEmpty())
        assertTrue(layout.mpfSegments.isEmpty())
        // 视频仍然紧跟 EOI，元数据定位方式不变
        val jpegLength = layout.imageLength.toInt()
        assertEquals(jpegLength + video().size, output.size)
    }

    @Test fun videoStartsRightAfterJpegEoi() {
        // 真机上可播放的第三方实况图：视频直接跟在 EOI 之后，中间没有增益图或填充
        val clip = video()
        val output = compose(clip = clip)
        val layout = JpegContainer.read(output.inputStream())!!
        val jpegLength = layout.imageLength.toInt()
        assertEquals(jpegLength + clip.size, output.size)
        assertArrayEquals(clip, output.copyOfRange(jpegLength, output.size))
        assertEquals(jpegLength.toLong(), MotionPhotoParser.parse(file(output))!!.videoOffset)
    }

    @Test fun injectsOplusUserCommentWhenCoverHasNoExif() {
        // ColorOS 靠 EXIF UserComment 里的 oplustag 标记进入 O-Live Photo 分支
        val output = compose()
        val layout = JpegContainer.read(output.inputStream())!!
        val exif = layout.exifSegments.single().payload
        assertEquals("Exif", String(exif.copyOfRange(0, 4), Charsets.US_ASCII))
        val tiff = 6 // "Exif\0\0" 之后
        assertEquals("MM", String(exif.copyOfRange(tiff, tiff + 2), Charsets.US_ASCII))
        assertEquals(42, u16(exif, tiff + 2))
        val ifd = tiff + 8
        assertEquals(3, u16(exif, ifd))
        assertEquals(0x0100, u16(exif, ifd + 2)) // ImageWidth
        assertEquals(0x0101, u16(exif, ifd + 14)) // ImageLength
        val pointer = ifd + 26 // ExifIFDPointer
        assertEquals(0x8769, u16(exif, pointer))
        val exifIfd = tiff + u32(exif, pointer + 8).toInt()
        assertEquals(1, u16(exif, exifIfd))
        val comment = exifIfd + 2
        assertEquals(0x9286, u16(exif, comment)) // UserComment
        val count = u32(exif, comment + 4).toInt()
        val textAt = tiff + u32(exif, comment + 8).toInt()
        assertEquals("{\"oplustag\":838860}", String(exif.copyOfRange(textAt, textAt + count - 2), Charsets.US_ASCII))
        // 补写的 EXIF 必须是第一个 APP1
        assertEquals("Exif", String(output.copyOfRange(6, 10), Charsets.US_ASCII))
    }

    @Test fun mpfDeclaresSinglePrimaryImage() {
        // 与真机上可播放的第三方实况图一致：MPF 只声明主图一张，视频定位靠 XMP 的 EOF 回退
        val output = compose()
        val layout = JpegContainer.read(output.inputStream())!!
        val jpegLength = layout.imageLength.toInt()
        val mpf = layout.mpfSegments.single().payload
        assertEquals(72, mpf.size + 2) // 段长字段（含自身 2 字节）
        val base = 4 // "MPF\0" 之后的 TIFF 起点
        assertEquals("MM", String(mpf.copyOfRange(base, base + 2), Charsets.US_ASCII))
        assertEquals(42, u16(mpf, base + 2)) // 0x002A：合法 TIFF 头
        val ifd = base + 8 // IFD0 相对 TIFF 起点的偏移固定为 8
        val entries = u16(mpf, ifd)
        assertEquals(3, entries)
        var images = 0
        var entriesOffset = 0
        for (i in 0 until entries) {
            val entry = ifd + 2 + i * 12
            when (u16(mpf, entry)) {
                0xb001 -> images = u32(mpf, entry + 8).toInt()
                0xb002 -> entriesOffset = u32(mpf, entry + 8).toInt()
            }
        }
        assertEquals(1, images)
        val first = base + entriesOffset
        assertEquals(jpegLength.toLong(), u32(mpf, first + 4)) // 主图长度 = 含 MPF 的 JPEG 部分
        assertEquals(0L, u32(mpf, first + 8)) // 主图偏移为 0
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
