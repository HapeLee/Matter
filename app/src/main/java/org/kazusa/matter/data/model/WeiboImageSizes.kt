package org.kazusa.matter.data.model

/**
 * 微博图片 CDN 尺寸档位。
 *
 * 微博图片地址形如 `https://wx1.sinaimg.cn/{size}/{picId}.jpg`，
 * 只替换 `{size}` 路径段即可在「缩略图 / 中等 / 原图」之间切换，
 * 不需要额外请求 API，也不会破坏 Coil 的磁盘缓存（不同档位各自独立缓存）。
 * 档位取自微博 CDN 实际支持的路径段：
 * `thumbnail` / `bmiddle` / `or480` / `orj480` / `orj960` / `orj1080` /
 * `wap360` / `wap720` / `webp360` / `mw690` / `mw2000` / `woriginal` / `large`
 */
enum class WeiboImageSize(val segment: String) {
    /** 信息流宫格缩略图档（约 440px，省流量省内存） */
    THUMBNAIL("bmiddle"),

    /** 查看器中等档（约 720px，长图 / 超大图更省内存） */
    MEDIUM("wap720"),

    /** 原图档 */
    ORIGINAL("large")
}

private const val WEIBO_IMAGE_HOST_SUFFIX = "sinaimg.cn"

/** 微博 CDN 上所有已知的尺寸路径段 */
private val WEIBO_SIZE_SEGMENTS: Set<String> = setOf(
    "thumb", "thumbnail", "small",
    "bmiddle", "middle", "or480", "or06",
    "orj360", "orj480", "orj960", "orj1080",
    "wap180", "wap360", "wap720",
    "mw690", "mw2000",
    "webp360",
    "woriginal", "large", "original"
)

/** 是否是可切换尺寸档位的微博 CDN 图片（视频封面、外链头像等返回 false） */
val String?.isWeiboCdnImage: Boolean
    get() {
        val url = this ?: return false
        val pathStart = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val slash = url.indexOf('/', pathStart)
        if (slash <= pathStart) return false
        return url.substring(pathStart, slash).endsWith(WEIBO_IMAGE_HOST_SUFFIX)
    }

/**
 * 把微博 CDN 图片地址切换到指定尺寸档位。
 *
 * - 非微博 CDN 地址、或路径段无法识别的地址原样返回，绝不破坏 URL；
 * - `crop.x.x.w.h` 这类裁剪段同样允许替换（裁剪对缩略图不是必需的）。
 */
fun String?.withWeiboImageSize(size: WeiboImageSize): String? {
    val url = this ?: return null
    if (url.isBlank()) return url
    return try {
        val hostStart = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = url.indexOf('/', hostStart)
        if (pathStart <= hostStart) return url

        val host = url.substring(hostStart, pathStart)
        if (!host.endsWith(WEIBO_IMAGE_HOST_SUFFIX)) return url

        val pathEnd = url.indexOf('/', pathStart + 1).let { if (it < 0) url.length else it }
        val segment = url.substring(pathStart + 1, pathEnd)
        if (segment == size.segment) return url
        if (segment !in WEIBO_SIZE_SEGMENTS && !segment.startsWith("crop.")) return url

        url.substring(0, pathStart + 1) + size.segment + url.substring(pathEnd)
    } catch (_: Exception) {
        url
    }
}

/** 由图片 ID 拼出指定档位的微博 CDN 地址 */
fun weiboImageUrlFromId(picId: String, size: WeiboImageSize): String =
    "https://wx1.sinaimg.cn/${size.segment}/$picId.jpg"
