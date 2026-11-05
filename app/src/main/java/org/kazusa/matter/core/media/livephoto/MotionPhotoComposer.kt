package org.kazusa.matter.core.media.livephoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 把静态封面 JPEG 与实况短视频合成为 Motion Photo。
 *
 * 输出结构对齐 ColorOS 上确认可播放的实况图（一加 / OPPO 相册）：
 * ```
 * SOI | EXIF APP1（ColorOS 且封面无 EXIF 时补：ImageWidth/ImageLength + UserComment）
 * 原图其余段 | XMP APP1 | …原有 ICC 等 | MPF APP2（仅 ColorOS）| DQT…SOS…EOI | MP4 到文件末尾
 * ```
 *
 * 元数据分两层：
 * - **所有设备**：`GCamera:MotionPhoto` + `Container:Directory`（Google 现行格式），
 *   视频项 `Item:Length` 从文件尾回溯（`fileLength - Length` 必须精确等于视频起点）；
 * - **仅 OPPO / 一加 / realme（[oplusMarkers]）**：`OpCamera:*` 字段、EXIF `UserComment`
 *   = `{"oplustag":838860}`、APP2 MPF。实测这几项是 ColorOS 相册判定「动态照片」所必需的，
 *   其他厂商（小米 / 华为 / vivo…）各有各的私有格式，按 Google 标准写即可。
 *
 * 视频紧跟 JPEG EOI，原图 EXIF / ICC 等元数据逐字节保留。
 */
object MotionPhotoComposer {
    /** APP2 MPF 段总字节数：marker 2 + 段长 2 + "MPF\0" 4 + TIFF 66（单张图一个 MPEntry）。 */
    private const val MPF_SEGMENT_TOTAL = 74

    /** MPF TIFF 里 MPEntry 数组的偏移：TIFF 头 8 + IFD0（2 + 3×12 + 4）。 */
    private const val MPF_ENTRIES_OFFSET = 50

    /**
     * OPPO / 一加相册判定「动态照片」的 EXIF `UserComment` 标记。
     * 取值来自一台 OPPO 上确认可播放的第三方实况图（VESDK 产物）。
     */
    private const val OPLUS_USER_COMMENT = "{\"oplustag\":838860}"

    fun compose(
        imageStream: InputStream,
        videoStream: InputStream,
        videoLength: Long,
        outputStream: OutputStream,
        /**
         * 是否写 OPPO / 一加 / realme（ColorOS / OxygenOS / realme UI）的私有实况元数据：
         * `OpCamera:*` 字段、EXIF `UserComment` 标记、APP2 MPF 段。
         *
         * 其他厂商按 Google Motion Photo 标准写（只有 `GCamera:*` + `Container:Directory`），
         * 不该带这套只对 ColorOS 有意义的标记。
         */
        oplusMarkers: Boolean = false
    ) {
        require(videoLength > 0) { "实况视频为空" }
        val image = imageStream.readBytes()
        val layout = JpegContainer.read(ByteArrayInputStream(image))
        requireNotNull(layout) { "实况封面必须为有效 JPEG 图片" }
        val xmp = buildXmpMeta(videoLength, oplusMarkers).toByteArray(Charsets.UTF_8)
        val segmentLength = 2 + JpegContainer.xmpHeader.size + xmp.size
        require(segmentLength <= 65535) { "实况元数据过大" }
        val xmpSegmentTotal = 4 + JpegContainer.xmpHeader.size + xmp.size

        val imageEnd = trimmedImageEnd(image, layout)
        // 旧的 XMP / MPF 段整段替换，其余元数据（EXIF、ICC 等）逐字节保留
        val stale = (layout.xmpSegments + layout.mpfSegments).sortedBy { it.offset }
        val removedTotal = stale.sumOf { it.length }
        // ColorOS 分支：封面没有 EXIF 时补一段最小 EXIF，写入 OPPO 的 UserComment 标记；
        // 已有 EXIF 则原样保留（不做 TIFF 手术，避免破坏方向等信息）。
        val injectedExif = if (oplusMarkers && layout.exifSegments.isEmpty()) {
            buildUserCommentExif(OPLUS_USER_COMMENT, layout.frameWidth, layout.frameHeight)
        } else {
            null
        }
        val exifSegmentTotal = injectedExif?.size ?: 0
        val mpfTotal = if (oplusMarkers) MPF_SEGMENT_TOTAL else 0
        val jpegLength = imageEnd - removedTotal + exifSegmentTotal + xmpSegmentTotal + mpfTotal

        // 插入点都是原图坐标；补写的 EXIF 只是顶在最前面，不影响原图的区间划分
        val xmpAt = layout.xmpInsert.toInt()
        val mpfAt = layout.metadataEnd.toInt()
        val mpf = if (oplusMarkers) buildMpfSegment(jpegLength) else null

        val jpegPart = ByteArrayOutputStream(jpegLength).apply {
            write(image, 0, 2)
            if (injectedExif != null) write(injectedExif)
            copyRange(this, image, 2, xmpAt, stale)
            write(byteArrayOf(0xff.toByte(), 0xe1.toByte()))
            write(segmentLength shr 8)
            write(segmentLength and 0xff)
            write(JpegContainer.xmpHeader)
            write(xmp)
            copyRange(this, image, xmpAt, mpfAt, stale)
            if (mpf != null) write(mpf)
            copyRange(this, image, mpfAt, imageEnd, stale)
        }.toByteArray()
        require(jpegPart.size == jpegLength) { "实况封面长度计算异常" }

        outputStream.write(jpegPart)
        val copied = videoStream.copyTo(outputStream)
        require(copied == videoLength) { "实况视频长度不匹配" }
        outputStream.flush()
    }

    /** 把 [from, to) 原样写出，跳过其中的旧 XMP / MPF 段。 */
    private fun copyRange(
        output: OutputStream,
        image: ByteArray,
        from: Int,
        to: Int,
        stale: List<JpegMetadataSegment>
    ) {
        var cursor = from
        for (segment in stale) {
            val offset = segment.offset.toInt()
            if (offset < from || offset >= to) continue
            if (offset > cursor) output.write(image, cursor, offset - cursor)
            cursor = maxOf(cursor, offset + segment.length)
        }
        if (cursor < to) output.write(image, cursor, to - cursor)
    }

    /**
     * 上一次合成的产物：XMP 里声明的视频正好在文件尾且是合法 MP4，就把它连同
     * 附属数据一起丢弃，只保留原始 JPEG。
     */
    private fun trimmedImageEnd(image: ByteArray, layout: JpegLayout): Int {
        for (segment in layout.xmpSegments) {
            val previousLength = MotionPhotoParser.videoLengthFromXmp(segment.payload) ?: continue
            if (previousLength <= 0 || previousLength > image.size - layout.imageLength) continue
            val start = image.size - previousLength.toInt()
            val valid = runCatching {
                DataInputStream(ByteArrayInputStream(image, start, previousLength.toInt())).use {
                    MotionPhotoParser.isMp4(it, previousLength)
                }
            }.getOrDefault(false)
            if (valid) return layout.imageLength.toInt()
        }
        return image.size
    }

    /**
     * 最小 EXIF APP1：`ImageWidth` / `ImageLength` + `UserComment`（OPPO 动态照片标记）。
     *
     * TIFF 布局：头 8 → IFD0（在 8，3 条）→ Exif IFD（在 50，1 条）→ 注释数据（在 68）。
     */
    private fun buildUserCommentExif(comment: String, width: Int, height: Int): ByteArray {
        val text = comment.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        val tiff = ByteArrayOutputStream(68 + text.size).also { buffer ->
            DataOutputStream(buffer).apply {
                write("MM".toByteArray(Charsets.US_ASCII)) // 大端
                writeShort(0x002a)
                writeInt(8) // IFD0 偏移
                writeShort(3) // IFD0 条目数
                writeShort(0x0100); writeShort(4); writeInt(1); writeInt(width) // ImageWidth
                writeShort(0x0101); writeShort(4); writeInt(1); writeInt(height) // ImageLength
                writeShort(0x8769); writeShort(4); writeInt(1); writeInt(50) // Exif IFD 指针
                writeInt(0) // 无下一个 IFD
                writeShort(1) // Exif IFD 条目数
                writeShort(0x9286); writeShort(2); writeInt(text.size); writeInt(68) // UserComment
                writeInt(0)
                write(text)
            }
        }.toByteArray()

        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
        return ByteArrayOutputStream(payload.size + 4).also { buffer ->
            DataOutputStream(buffer).apply {
                writeByte(0xff); writeByte(0xe1)
                writeShort(payload.size + 2)
                write(payload)
            }
        }.toByteArray()
    }

    /**
     * APP2 MPF 段：只声明主图一张（`NumberOfImages=1`）。
     *
     * 与真机上可播放的第三方实况图一致：视频直接跟在 EOI 之后，
     * 定位完全靠 XMP 里的 `Item:Length`（EOF 回退），MPF 不参与视频定位。
     */
    private fun buildMpfSegment(primaryLength: Int): ByteArray {
        val tiff = ByteArrayOutputStream(MPF_SEGMENT_TOTAL).also { buffer ->
            DataOutputStream(buffer).apply {
                write("MM".toByteArray(Charsets.US_ASCII)) // 大端
                writeShort(0x002a)
                writeInt(8) // IFD0 相对 TIFF 起点的偏移
                writeShort(3) // IFD0 条目数
                writeShort(0xb000); writeShort(7); writeInt(4); write("0100".toByteArray(Charsets.US_ASCII))
                writeShort(0xb001); writeShort(4); writeInt(1); writeInt(1)
                writeShort(0xb002); writeShort(7); writeInt(16); writeInt(MPF_ENTRIES_OFFSET)
                writeInt(0) // 无下一个 IFD
                // MPEntry[0]：主图（含 MPF 段在内的 JPEG 部分）
                writeInt(0x00030000); writeInt(primaryLength); writeInt(0); writeShort(0); writeShort(0)
            }
        }.toByteArray()

        val payload = ByteArrayOutputStream(tiff.size + 4).apply {
            write("MPF\u0000".toByteArray(Charsets.US_ASCII))
            write(tiff)
        }.toByteArray()

        return ByteArrayOutputStream(MPF_SEGMENT_TOTAL).apply {
            DataOutputStream(this).apply {
                writeByte(0xff); writeByte(0xe2)
                writeShort(payload.size + 2)
                write(payload)
            }
        }.also { require(it.size() == MPF_SEGMENT_TOTAL) { "MPF 段长度异常" } }.toByteArray()
    }

    /**
     * 实况照片的 XMP 元数据。
     *
     * 前缀必须写成 `GCamera` / `OpCamera`：现行规范里前缀本无意义（命名空间 URI 相同即可），
     * 但读取方普遍按字面前缀检索。XMP 直接以 `<x:xmpmeta>` 开头，不带 `<?xpacket?>` 包裹，
     * 主图项不写 `Item:Length` —— 都与 ColorOS 上可播放的实况图保持同样写法。
     *
     * [oplusMarkers] 为 false 时只写 Google 标准字段，不出现任何 `OpCamera:*`。
     */
    private fun buildXmpMeta(videoLength: Long, oplusMarkers: Boolean): String {
        val attributes = buildList {
            add("xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            if (oplusMarkers) add("xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"")
            add("xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            add("xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
            add("GCamera:MotionPhoto=\"1\"")
            add("GCamera:MotionPhotoVersion=\"1\"")
            add("GCamera:MotionPhotoPresentationTimestampUs=\"0\"")
            if (oplusMarkers) {
                add("OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"")
                add("OpCamera:MotionPhotoOwner=\"oplus\"")
                add("OpCamera:OLivePhotoVersion=\"2\"")
                add("OpCamera:VideoLength=\"$videoLength\"")
            }
        }
        val indent = " ".repeat(14)
        return buildString {
            appendLine("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">")
            appendLine("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            appendLine("    <rdf:Description rdf:about=\"\"")
            appendLine(attributes.joinToString("\n") { "$indent$it" } + ">")
            appendLine("      <Container:Directory>")
            appendLine("        <rdf:Seq>")
            appendLine("          <rdf:li rdf:parseType=\"Resource\">")
            appendLine("            <Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\"/>")
            appendLine("          </rdf:li>")
            appendLine("          <rdf:li rdf:parseType=\"Resource\">")
            appendLine("            <Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\"/>")
            appendLine("          </rdf:li>")
            appendLine("        </rdf:Seq>")
            appendLine("      </Container:Directory>")
            appendLine("    </rdf:Description>")
            appendLine("  </rdf:RDF>")
            append("</x:xmpmeta>")
        }
    }
}
