package org.kazusa.matter.core.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kazusa.matter.core.network.WeiboRequestHeaders
import org.kazusa.matter.core.media.livephoto.MotionPhotoComposer
import org.kazusa.matter.core.media.livephoto.LivePhotoImage
import org.kazusa.matter.core.media.livephoto.MotionPhotoParser
import org.kazusa.matter.data.model.normalizeVideoUrl
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 把微博图片保存到系统相册。
 *
 * - 走 MediaStore 沙箱写入，无需任何存储权限（minSdk 31 已不再需要 WRITE_EXTERNAL_STORAGE）；
 * - 直接流式转存原始字节，不做二次压缩，保证保存下来的是真正的原图。
 */
object ImageSaver {

    private const val ALBUM_DIR = "Matter"

    private val KNOWN_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    /** Oplus 系的 `MANUFACTURER` / `BRAND` 取值（OPPO、一加、realme 全系都跑 ColorOS 内核）。 */
    private val OPLUS_BRANDS = listOf("oppo", "oneplus", "realme", "oplus")

    /**
     * 下载 [url] 并保存到相册。
     *
     * @return 成功时返回保存后的文件名
     */
    suspend fun saveImage(context: Context, url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.isNotBlank()) { "图片地址为空" }
            val fileName = buildFileName(url)
            val mimeType = buildMimeType(fileName)
            val resolver = context.contentResolver

            saveViaMediaStore(context, resolver, url, fileName, mimeType)
        }
    }

    /**
     * 当前设备是否要写 ColorOS / OxygenOS / realme UI 的私有实况元数据。
     *
     * OPPO、一加、realme 同属 Oplus，相册只认 `OpCamera:*` + EXIF `UserComment` + MPF 这一套；
     * 其他厂商按 Google Motion Photo 标准写即可，多写的私有标记反而可能干扰他们的解析。
     */
    private val useOplusLivePhoto: Boolean by lazy {
        supportsOplusLivePhoto(Build.MANUFACTURER, Build.BRAND)
    }

    internal fun supportsOplusLivePhoto(manufacturer: String?, brand: String?): Boolean {
        val identity = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase(Locale.ROOT)
        return OPLUS_BRANDS.any { it in identity }
    }

    /**
     * 将实况照片（静态大图 [imageUrl] + 短视频 [videoUrl]）合成为原生 Motion Photo 单文件并保存到系统相册。
     */
    suspend fun saveLivePhoto(context: Context, imageUrl: String, videoUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(imageUrl.isNotBlank()) { "图片地址为空" }
            require(videoUrl.isNotBlank()) { "视频地址为空" }
            val fileName = livePhotoFileName(imageUrl)
            val mimeType = "image/jpeg"
            val resolver = context.contentResolver

            val cacheDir = context.cacheDir
            val tempImage = File.createTempFile("live_image_", ".jpg", cacheDir)

            try {
                val tempVideo = File.createTempFile("live_video_", ".mp4", cacheDir)
                try {
                    FileOutputStream(tempImage).use { downloadTo(imageUrl, it) }
                    LivePhotoImage.normalizeJpeg(tempImage)
                    FileOutputStream(tempVideo).use { downloadTo(videoUrl, it) }
                    check(MotionPhotoParser.isVideoFile(tempVideo)) { "实况视频不是完整的 MP4 文件" }

                    val videoLength = tempVideo.length()

                    run {
                        val pendingUri: Uri = resolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR"
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                        ) ?: error("无法在相册中创建文件")

                        try {
                            resolver.openOutputStream(pendingUri)?.use { output ->
                                FileInputStream(tempImage).use { imgIn ->
                                    FileInputStream(tempVideo).use { vidIn ->
                                        MotionPhotoComposer.compose(
                                            imageStream = imgIn,
                                            videoStream = vidIn,
                                            videoLength = videoLength,
                                            outputStream = output,
                                            oplusMarkers = useOplusLivePhoto
                                        )
                                    }
                                }
                            } ?: error("无法打开输出流")

                            resolver.update(
                                pendingUri,
                                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                                null,
                                null
                            )
                        } catch (t: Throwable) {
                            runCatching { resolver.delete(pendingUri, null, null) }
                            throw t
                        }
                    }
                    fileName
                } finally {
                    tempVideo.delete()
                }
            } finally {
                tempImage.delete()
            }
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        resolver: android.content.ContentResolver,
        url: String,
        fileName: String,
        mimeType: String
    ): String {
        val pendingUri: Uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: error("无法在相册中创建文件")

        try {
            resolver.openOutputStream(pendingUri)?.use { output ->
                downloadTo(url, output)
            } ?: error("无法打开输出流")

            resolver.update(
                pendingUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(pendingUri, null, null) }
            throw t
        }
        return fileName
    }

    private fun downloadTo(rawUrl: String, output: OutputStream) {
        // 明文 http 会被 targetSdk 28+ 的网络安全策略直接拒绝（实况短视频常下发
        // http://g.us.sinaimg.cn/…），这里统一把微博 CDN 的 http 升级成 https，
        // 否则保存会在拿到响应之前就抛 "Cleartext HTTP traffic … not permitted"。
        val url = normalizeVideoUrl(rawUrl) ?: rawUrl
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", WeiboRequestHeaders.USER_AGENT)
            // 微博图片 CDN 对空 Referer 的请求偶尔会返回 403
            setRequestProperty("Referer", WeiboRequestHeaders.CDN_REFERER)
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "下载失败 HTTP $code" }
            connection.inputStream.use { input -> input.copyTo(output) }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 实况照片的文件名。
     *
     * 同时满足两套启发式：Motion Photo 1.0 规范的 `*MP.jpg` 命名，以及 Android 相册 /
     * 厂商图库快速识别实况照片时常用的 `MVIMG_` 前缀（否则常被当成普通截图式 JPEG）。
     */
    internal fun livePhotoFileName(url: String): String {
        val stem = buildFileName(url).substringBeforeLast('.').removePrefix("Matter_")
        return "MVIMG_${stem}_MP.jpg"
    }

    private fun buildFileName(url: String): String {
        val rawName = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "weibo_image" }

        val rawExt = rawName.substringAfterLast('.', "").lowercase()
        val hasExtension = rawExt in KNOWN_EXTENSIONS
        val extension = if (hasExtension) rawExt else "jpg"
        val stem = (if (hasExtension) rawName.substringBeforeLast('.') else rawName).take(48)

        return "Matter_${System.currentTimeMillis()}_$stem.$extension"
    }

    private fun buildMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "jpg").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                "png" -> "image/png"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
    }
}
