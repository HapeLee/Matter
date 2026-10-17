package org.kazusa.matter.core.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把微博图片保存到系统相册。
 *
 * - Android 10+ 走 MediaStore 沙箱写入，无需任何存储权限；
 * - Android 9 及以下写入公共 `Pictures/Matter` 目录，需要 `WRITE_EXTERNAL_STORAGE` 运行时权限；
 * - 直接流式转存原始字节，不做二次压缩，保证保存下来的是真正的原图。
 */
object ImageSaver {

    private const val ALBUM_DIR = "Matter"

    private val KNOWN_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, resolver, url, fileName, mimeType)
            } else {
                saveViaLegacyFile(context, url, fileName, mimeType)
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

    @Suppress("DEPRECATION")
    private fun saveViaLegacyFile(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String
    ): String {
        check(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { "外部存储不可用" }
        val albumDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIR
        )
        if (!albumDir.exists() && !albumDir.mkdirs()) {
            error("无法创建相册目录")
        }
        val target = File(albumDir, fileName)
        try {
            FileOutputStream(target).use { output -> downloadTo(url, output) }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
        return fileName
    }

    private fun downloadTo(url: String, output: OutputStream) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)")
            // 微博图片 CDN 对空 Referer 的请求偶尔会返回 403
            setRequestProperty("Referer", "https://weibo.com/")
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "下载失败 HTTP $code" }
            connection.inputStream.use { input -> input.copyTo(output) }
        } finally {
            runCatching { connection.disconnect() }
        }
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
