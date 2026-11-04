package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable

/**
 * 播放器的一档候选地址：展示文案 + 真正交给 Media3 的直链。
 */
@Immutable
data class VideoPlaybackCandidate(
    val label: String,
    val url: String
)

/**
 * 确定支持 TLS、且微博历史上确实给过 `http://` 前缀的域名。
 * 只对这类域名做 http -> https 升级，第三方视频站保持原样，
 * 避免把只在明文下提供的视频改坏。
 */
private val httpsUpgradableHostSuffixes = listOf(
    "weibocdn.com",
    "sinaimg.cn",
    "sinajs.cn",
    "sina.com.cn",
    "weibo.com",
    "weibo.cn",
    "miaopai.com"
)

/**
 * 视频直链规范化。
 *
 * 微博下发的 `stream_url` / `mp4_*` / `playback_list[].play_info.url` 存在几种会让 Media3
 * 直接失败、而界面上只表现为「黑屏无反应」的形态：
 *
 * - **空串**：字段存在但该档位还没转码完成（`mp4_720p_mp4: ""`），
 *   `MediaItem.fromUri("")` 会解析成一个没有 host 的地址；
 * - **协议相对地址** `//f.video.weibocdn.com/...`：`Uri.parse` 后没有 scheme，
 *   被 Media3 当成无法处理的目标；
 * - **历史 `http://` 地址**：明文请求在 targetSdk 28+ 的网络策略与部分 CDN 节点上会被拒绝。
 *
 * 因此这里只放行 `http(s)` 绝对地址，`//` 补 `https:`，微博系 CDN 的 http 升级为 https。
 * 无法规范化的地址返回 null，由调用方跳过而不是排进必然失败的播放队列。
 */
fun normalizeVideoUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    val scheme = withScheme.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null

    val host = withScheme.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (host.isBlank()) return null

    return if (scheme == "http" && host.isHttpsUpgradableHost()) {
        "https://" + withScheme.substringAfter("://")
    } else {
        withScheme
    }
}

private fun String.isHttpsUpgradableHost(): Boolean {
    val normalized = substringBefore(':').lowercase()
    return httpsUpgradableHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
}

/**
 * 生成播放器按顺序尝试的候选队列。
 *
 * 1. 服务端推荐的默认档（[videoUrl]，即列表卡片点击时带过来的地址）排第一，
 *    保持「点开就播用户看到的那一档」；
 * 2. 其余档位保持 `availableStreams` 的顺序（已按画质从高到低排序），
 *    作为播放失败后的自动降级队列；
 * 3. 空地址 / 非 http(s) / 重复地址直接剔除。
 *
 * 返回空列表代表这条微博没有任何可播放地址，界面应直接给出错误提示而不是黑屏。
 */
fun buildVideoPlaybackCandidates(
    videoUrl: String?,
    streams: List<VideoStreamInfo>
): List<VideoPlaybackCandidate> {
    val candidates = mutableListOf<VideoPlaybackCandidate>()
    val seenUrls = mutableSetOf<String>()

    fun add(label: String?, rawUrl: String?) {
        val url = normalizeVideoUrl(rawUrl) ?: return
        if (!seenUrls.add(url)) return
        val text = label?.trim()?.ifBlank { null } ?: "画质 ${candidates.size + 1}"
        candidates += VideoPlaybackCandidate(label = text, url = url)
    }

    val defaultUrl = normalizeVideoUrl(videoUrl)
    if (defaultUrl != null) {
        // 默认档沿用服务端给这一档的文案（如「720P 高清」），没有才叫「默认」
        val defaultLabel = streams.firstOrNull { normalizeVideoUrl(it.url) == defaultUrl }?.label
        add(defaultLabel ?: "默认", defaultUrl)
    }
    streams.forEach { add(it.label, it.url) }
    return candidates
}
