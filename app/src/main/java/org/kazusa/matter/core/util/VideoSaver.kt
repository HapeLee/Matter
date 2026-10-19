package org.kazusa.matter.core.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 将微博视频保存至系统媒体库（相册/视频库）：
 * - Android 10+ (API 29+) 走 MediaStore 沙箱写入，保存至 Movies/Matter 目录，免申请权限；
 * - Android 9 及以下写入公共 Movies/Matter 目录，依赖 WRITE_EXTERNAL_STORAGE 权限；
 * - 边下边写，流式写入避免视频占用 JVM 堆内存导致 OOM；
 * - 携带微博防盗链 Referer 请求头，确保 CDN 正常响应。
 */
object VideoSaver {

    private const val ALBUM_DIR = "Matter"

    /**
     * 下载视频并保存至系统媒体库。
     *
     * @param context Android 上下文
     * @param url 视频直链地址
     * @param title 视频标题或描述（用于生成可读文件名）
     * @return 成功时返回保存的文件名
     */
    suspend fun saveVideo(
        context: Context,
        url: String,
        title: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.isNotBlank()) { "视频下载地址为空" }
            val fileName = buildVideoFileName(url, title)
            val mimeType = "video/mp4"
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(resolver, url, fileName, mimeType)
            } else {
                saveViaLegacyFile(context, url, fileName, mimeType)
            }
        }
    }

    private fun saveViaMediaStore(
        resolver: android.content.ContentResolver,
        url: String,
        fileName: String,
        mimeType: String
    ): String {
        val pendingUri: Uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM_DIR"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: error("无法在媒体库中创建视频记录")

        try {
            resolver.openOutputStream(pendingUri)?.use { output ->
                downloadTo(url, output)
            } ?: error("无法打开视频输出流")

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
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM_DIR
        )
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            error("无法创建视频目录")
        }
        val target = File(moviesDir, fileName)
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
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)")
            // 微博 CDN 防盗链支持
            setRequestProperty("Referer", "https://weibo.com/")
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "下载视频失败 HTTP $code" }
            connection.inputStream.use { input ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun buildVideoFileName(url: String, title: String): String {
        val sanitizedTitle = title.trim()
            .replace(Regex("[^\\w\\u4e00-\\u9fa5_-]"), "_")
            .take(24)

        val rawName = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "video" }

        val stem = if (sanitizedTitle.isNotBlank()) sanitizedTitle else rawName.take(32)
        return "Matter_${System.currentTimeMillis()}_$stem.mp4"
    }
}
