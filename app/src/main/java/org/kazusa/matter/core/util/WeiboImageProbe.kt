package org.kazusa.matter.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kazusa.matter.data.model.WeiboImageSize
import org.kazusa.matter.data.model.isWeiboCdnImage
import org.kazusa.matter.data.model.withWeiboImageSize
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 微博图片最大可用档位与体积元数据探测结果。
 *
 * @param targetUrl 探测出的最高可用档位图片地址（依次按 original -> woriginal -> mw2000 -> large 探测可用性）
 * @param contentLength 图片文件体积（字节），若无法获取则为 -1
 * @param tier 最终采用的清晰度档位
 */
data class ImageProbeResult(
    val targetUrl: String,
    val contentLength: Long = -1L,
    val tier: WeiboImageSize = WeiboImageSize.LARGE
) {
    /** 文件是否超过 10MB 门限 */
    val isOversized: Boolean
        get() = contentLength > WeiboImageProbe.MAX_AUTO_DOWNLOAD_BYTES

    /** 格式化后的文件大小（如 "2.4 MB", "850 KB"），未知时为 null */
    val formattedSize: String?
        get() = when {
            contentLength <= 0 -> null
            contentLength < 1024 * 1024 -> String.format(Locale.getDefault(), "%.0f KB", contentLength / 1024f)
            else -> String.format(Locale.getDefault(), "%.1f MB", contentLength / (1024f * 1024f))
        }
}

/**
 * 微博图片探测器：
 * 1. 采用 HTTP HEAD 轻量级请求实现零实体带宽探测（只获取响应头，不下载图片数据）；
 * 2. 依次按最高清晰度梯度探测 [original -> woriginal -> mw2000 -> large]；
 * 3. 提取 Content-Length 判断图片体积，支持对超过 10MB 的超大图拦截自动加载；
 * 4. 内置内存 LRU 缓存，同一张图片只探测一次。
 */
object WeiboImageProbe {

    /** 自动加载原图的最大体积门限：10MB */
    const val MAX_AUTO_DOWNLOAD_BYTES: Long = 10L * 1024L * 1024L

    /** 探测清晰度档位梯度（画质从高到低） */
    val PROBE_TIERS: List<WeiboImageSize> = listOf(
        WeiboImageSize.ORIGINAL,  // original (纯原图/未压缩)
        WeiboImageSize.WORIGINAL, // woriginal (微相册/早期网页原图)
        WeiboImageSize.MW2000,    // mw2000 (2000px 超清限宽)
        WeiboImageSize.LARGE      // large (标准大图兜底)
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ImageProbeResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageProbeResult>?): Boolean {
            return size > 500
        }
    }

    /**
     * 同步/快速从缓存读取结果，若未命中则返回 null。
     */
    fun getCached(rawUrl: String): ImageProbeResult? = synchronized(lock) {
        cache[rawUrl]
    }

    /**
     * 手动更新或填充缓存（可用于单元测试或预热）。
     */
    fun putCache(rawUrl: String, result: ImageProbeResult) {
        synchronized(lock) {
            cache[rawUrl] = result
        }
    }

    /**
     * 清空探测缓存。
     */
    fun clearCache() {
        synchronized(lock) {
            cache.clear()
        }
    }

    /**
     * 异步探测指定图片地址的最佳档位与体积。
     */
    suspend fun probe(rawUrl: String): ImageProbeResult = withContext(Dispatchers.IO) {
        val cached = getCached(rawUrl)
        if (cached != null) return@withContext cached

        // 非微博 CDN 图片（如视频封面、外链头像等），无法替换档位，仅探测自身体积
        if (!rawUrl.isWeiboCdnImage) {
            val length = queryContentLength(rawUrl)
            val result = ImageProbeResult(
                targetUrl = rawUrl,
                contentLength = length,
                tier = WeiboImageSize.LARGE
            )
            putCache(rawUrl, result)
            return@withContext result
        }

        // 微博 CDN 图片：按梯度依次探测
        for (tier in PROBE_TIERS) {
            val candidateUrl = rawUrl.withWeiboImageSize(tier) ?: continue
            val (status, length) = headRequest(candidateUrl)
            if (status in 200..299) {
                val result = ImageProbeResult(
                    targetUrl = candidateUrl,
                    contentLength = length,
                    tier = tier
                )
                putCache(rawUrl, result)
                return@withContext result
            }
        }

        // 若所有档位均探测失败（如离线或网络异常），默认以 large 兜底
        val fallbackUrl = rawUrl.withWeiboImageSize(WeiboImageSize.LARGE) ?: rawUrl
        val fallbackResult = ImageProbeResult(
            targetUrl = fallbackUrl,
            contentLength = -1L,
            tier = WeiboImageSize.LARGE
        )
        // 网络失败时不缓存，下次允许重试
        fallbackResult
    }

    private fun queryContentLength(url: String): Long {
        return headRequest(url).second
    }

    private fun headRequest(url: String): Pair<Int, Long> {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)")
                setRequestProperty("Referer", "https://weibo.com/")
            }
            try {
                val code = connection.responseCode
                val length = connection.contentLengthLong
                code to length
            } finally {
                runCatching { connection.disconnect() }
            }
        }.getOrDefault(-1 to -1L)
    }
}
