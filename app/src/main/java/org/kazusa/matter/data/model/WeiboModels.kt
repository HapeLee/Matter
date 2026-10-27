package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

@Serializable
data class TimelineResponse(
    val ok: Int = 1,
    val statuses: List<WeiboStatusDto> = emptyList(),
    @SerialName("total_number") val totalNumber: Int? = null,
    val data: TimelineDataDto? = null
)

@Serializable
data class TimelineDataDto(
    val statuses: List<WeiboStatusDto> = emptyList(),
    val cards: List<WeiboCardDto> = emptyList(),
    @SerialName("total_number") val totalNumber: Int? = null
)

@Immutable
@Serializable
data class WeiboStatusDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long? = null,
    val mid: String? = null,
    @SerialName("idstr") val idStr: String? = null,
    val text: String = "",
    @SerialName("text_raw") val textRaw: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val source: String? = null,
    val user: WeiboUserDto? = null,
    @SerialName("retweeted_status") val retweetedStatus: WeiboStatusDto? = null,
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("reposts_count") val repostsCount: String = "0",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("comments_count") val commentsCount: String = "0",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("attitudes_count") val attitudesCount: String = "0",
    @SerialName("pic_ids") val picIds: List<String> = emptyList(),
    @SerialName("pic_urls") val picUrls: List<WeiboPicUrlDto> = emptyList(),
    @SerialName("pic_infos") val picInfos: Map<String, WeiboPicInfoDto> = emptyMap(),
    @SerialName("bmiddle_pic") val bmiddlePic: String? = null,
    @SerialName("original_pic") val originalPic: String? = null,
    @SerialName("thumbnail_pic") val thumbnailPic: String? = null,
    @SerialName("page_info") val pageInfo: WeiboPageInfoDto? = null,
    /** 图文混排媒体（图片与视频共用一条有序列表），见 [mediaItems] */
    @SerialName("mix_media_info") val mixMediaInfo: WeiboMixMediaInfoDto? = null,
    @SerialName("region_name") val regionName: String? = null,
    @SerialName("status_title") val statusTitle: String? = null,
    @Serializable(with = WeiboTitleTextSerializer::class) val title: String? = null,
    @SerialName("isLongText") val isLongText: Boolean = false,
    @SerialName("favorited") val isFavorited: Boolean = false,
    @SerialName("liked") val isLiked: Boolean = false
) {
    val displayBloggerTitle: String?
        get() = statusTitle?.trim()?.ifBlank { null }
            ?: title?.trim()?.ifBlank { null }
            ?: user?.verifiedReason?.trim()?.ifBlank { null }

    /**
     * Lazy 列表使用的稳定 key。
     *
     * 不要直接用 [hashCode]（data class 全字段派生）：点赞数等字段变化会导致 key 漂移，
     * 让列表项被当作新项重建并丢失状态。
     *
     * 用 `by lazy` 而不是 getter：这个值会被 LazyColumn 的 key lambda、`distinctBy`、
     * 各种 `indexOfFirst` 反复读取，每次现算 `id.toString()` 都在列表路径上分配字符串。
     */
    val listKey: String by lazy {
        idStr ?: mid ?: id?.toString() ?: hashCode().toString()
    }

    /**
     * 原图档 URL 列表。结果会被缓存，避免每次读取都重新 map 出新的 List。
     *
     * 下游 `ImageGrid` 用 `remember(images)` 缓存插槽信息，若每次返回新实例会导致缓存失效，
     * 因此这里必须保证同一实例内引用稳定。
     */
    val allLargeImageUrls: List<String> by lazy {
        val fromPicUrls = picUrls.mapNotNull { pic ->
            (pic.originalPic ?: pic.bmiddlePic ?: pic.thumbnailPic)
                ?.withWeiboImageSize(WeiboImageSize.LARGE)
        }
        when {
            fromPicUrls.isNotEmpty() -> fromPicUrls
            picIds.isNotEmpty() -> picIds.map { weiboImageUrlFromId(it, WeiboImageSize.LARGE) }
            else -> {
                val single = (originalPic ?: bmiddlePic ?: thumbnailPic)
                    ?.withWeiboImageSize(WeiboImageSize.LARGE)
                if (!single.isNullOrBlank()) listOf(single) else emptyList()
            }
        }
    }

    /**
     * 信息流宫格使用的缩略图档 URL 列表，与 [allLargeImageUrls] 一一对应、顺序一致。
     *
     * 非微博 CDN 图片（视频封面、外链图等）无法切换档位，会原样保留原图地址。
     */
    val allThumbImageUrls: List<String> by lazy {
        allLargeImageUrls.map { it.withWeiboImageSize(WeiboImageSize.THUMBNAIL) ?: it }
    }

    /**
     * 映射每张图片的大图/原图 URL 或 picId 到对应的实况短视频 URL。
     */
    val livePhotoVideoMap: Map<String, String> by lazy {
        if (picInfos.isEmpty()) return@lazy emptyMap()
        val map = mutableMapOf<String, String>()
        for ((picId, info) in picInfos) {
            val videoUrl = info.liveVideoUrl
            if (info.isLivePhoto && !videoUrl.isNullOrBlank()) {
                map[picId] = videoUrl
                info.large?.url?.let {
                    map[it] = videoUrl
                    it.withWeiboImageSize(WeiboImageSize.LARGE)?.let { u -> map[u] = videoUrl }
                    it.withWeiboImageSize(WeiboImageSize.ORIGINAL)?.let { u -> map[u] = videoUrl }
                }
                info.original?.url?.let {
                    map[it] = videoUrl
                    it.withWeiboImageSize(WeiboImageSize.LARGE)?.let { u -> map[u] = videoUrl }
                    it.withWeiboImageSize(WeiboImageSize.ORIGINAL)?.let { u -> map[u] = videoUrl }
                }
                info.bmiddle?.url?.let { map[it] = videoUrl }
                weiboImageUrlFromId(picId, WeiboImageSize.LARGE).let { map[it] = videoUrl }
                weiboImageUrlFromId(picId, WeiboImageSize.ORIGINAL).let { map[it] = videoUrl }
            }
        }
        map
    }

    /**
     * 媒体区按服务端顺序混排的媒体单元（图片 / 视频）。
     *
     * 顺序来源优先级：
     * 1. `mix_media_info.items[]`：图文混排微博的真实顺序（视频可能夹在图片中间）；
     * 2. 仅图片（`pic_urls` / `pic_ids` / 单图字段）；
     * 3. 服务端没有下发 `mix_media_info`、但同时有配图与 `page_info` 视频时，
     *    把视频并入同一列表末尾，仍然走同一个宫格，不再单独占一行。
     *
     * 结果会被缓存，保证 [StatusMediaItem] 列表引用稳定（下游宫格用 `remember` 缓存插槽信息）。
     */
    val mediaItems: List<StatusMediaItem> by lazy {
        val hostMid = idStr ?: mid ?: id?.toString() ?: ""
        val mixed = mixMediaInfo?.items.orEmpty()
            .mapNotNull { it.toStatusMediaItem(hostMid) }
        if (mixed.isNotEmpty()) return@lazy mixed

        val pictures = allLargeImageUrls.map { StatusMediaItem.Picture(it) }
        val pageVideo = pageInfo?.toStatusVideo(hostMid)
        when {
            pageVideo == null -> pictures
            pictures.isEmpty() -> listOf(pageVideo)
            else -> pictures + pageVideo
        }
    }
}

/** `mix_media_info` 里的单个媒体条目，`type == "pic"` 时 [data] 为图片，`"video"` 时为封面 + 播放信息 */
private fun WeiboMixMediaItemDto.toStatusMediaItem(hostMid: String): StatusMediaItem? =
    when (type?.lowercase()) {
        "pic" -> {
            val picId = data?.picId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() }
            val rawUrl = data?.large?.url
                ?: data?.bmiddle?.url
                ?: data?.original?.url
                ?: picId?.let { weiboImageUrlFromId(it, WeiboImageSize.LARGE) }
            rawUrl
                ?.let { it.withWeiboImageSize(WeiboImageSize.LARGE) ?: it }
                ?.takeIf { it.isNotBlank() }
                ?.let { StatusMediaItem.Picture(it) }
        }
        "video" -> data?.toStatusVideo(hostMid)
        else -> null
    }

/** 图文混排视频条目（`MblogCardInfo` 结构）→ 统一媒体单元 */
private fun WeiboMixMediaDataDto.toStatusVideo(hostMid: String): StatusMediaItem.Video? {
    val info = mediaInfo ?: return null
    val videoUrl = info.bestVideoUrl ?: return null
    return StatusMediaItem.Video(
        previewUrl = pagePic.orEmpty(),
        videoUrl = videoUrl,
        streams = info.availableStreams,
        title = pageTitle?.takeIf { it.isNotBlank() }
            ?: info.videoTitle?.takeIf { it.isNotBlank() }
            ?: info.kolTitle?.takeIf { it.isNotBlank() },
        mid = hostMid,
        durationSeconds = duration?.takeIf { it > 0 } ?: info.duration?.takeIf { it > 0 }
    )
}

/** 传统 `page_info` 视频卡片 → 统一媒体单元 */
private fun WeiboPageInfoDto.toStatusVideo(hostMid: String): StatusMediaItem.Video? {
    val info = mediaInfo ?: return null
    val videoUrl = info.bestVideoUrl ?: return null
    return StatusMediaItem.Video(
        previewUrl = pagePic.orEmpty(),
        videoUrl = videoUrl,
        streams = info.availableStreams,
        title = pageTitle?.takeIf { it.isNotBlank() }
            ?: info.videoTitle?.takeIf { it.isNotBlank() }
            ?: info.kolTitle?.takeIf { it.isNotBlank() },
        mid = hostMid,
        durationSeconds = info.duration?.takeIf { it > 0 }
    )
}

/**
 * 从微博图片 URL 尝试提取出 picId（如 `https://wx1.sinaimg.cn/large/007xxx.jpg` -> `007xxx`）
 */
fun extractWeiboPicId(url: String): String? {
    val lastSlash = url.lastIndexOf('/')
    if (lastSlash < 0 || lastSlash >= url.length - 1) return null
    val filename = url.substring(lastSlash + 1)
    val dot = filename.indexOf('.')
    return if (dot > 0) filename.substring(0, dot) else filename
}

/**
 * 微博图片单张档位详情
 */
@Immutable
@Serializable
data class WeiboPicDetailDto(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("cut_type") val cutType: Int? = null,
    val type: String? = null
)

/**
 * 微博图片详细信息（支持 Live Photo 短视频）
 */
@Immutable
@Serializable
data class WeiboPicInfoDto(
    @SerialName("pic_id") val picId: String? = null,
    val type: String? = null,
    val thumbnail: WeiboPicDetailDto? = null,
    val bmiddle: WeiboPicDetailDto? = null,
    val large: WeiboPicDetailDto? = null,
    val original: WeiboPicDetailDto? = null,
    @SerialName("video") val video: String? = null,
    @SerialName("video_url") val videoUrl: String? = null
) {
    val isLivePhoto: Boolean
        get() = type?.equals("livephoto", ignoreCase = true) == true || !liveVideoUrl.isNullOrBlank()

    val liveVideoUrl: String?
        get() = video?.takeIf { it.isNotBlank() } ?: videoUrl?.takeIf { it.isNotBlank() }
}

@Immutable
@Serializable
data class WeiboPicUrlDto(
    @SerialName("thumbnail_pic") val thumbnailPic: String? = null,
    @SerialName("bmiddle_pic") val bmiddlePic: String? = null,
    @SerialName("original_pic") val originalPic: String? = null
)

@Serializable
data class WeiboPageInfoDto(
    val type: String? = null,
    @SerialName("page_title") val pageTitle: String? = null,
    @Serializable(with = WeiboPagePicSerializer::class) @SerialName("page_pic") val pagePic: String? = null,
    @SerialName("media_info") val mediaInfo: WeiboMediaInfoDto? = null
)

/**
 * 图文混排媒体信息（`mix_media_info`）。
 *
 * 微博客户端对「视频 + 图片」混排的微博，会把两类媒体按发布顺序放进同一个列表；
 * 旧字段（`pic_ids` / `page_info`）只描述各自类型的媒体，无法还原这个顺序。
 */
@Immutable
@Serializable
data class WeiboMixMediaInfoDto(
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("selected_index") val selectedIndex: Int? = null,
    val items: List<WeiboMixMediaItemDto> = emptyList()
)

/** 单个混排条目：[type] 为 `pic` / `video`，[data] 为对应类型的媒体数据 */
@Immutable
@Serializable
data class WeiboMixMediaItemDto(
    val id: String? = null,
    val type: String? = null,
    val scheme: String? = null,
    val data: WeiboMixMediaDataDto? = null
)

/**
 * 混排条目数据：`pic` 使用 `PicInfo` 结构（与 `pic_infos` 内的值同构），
 * `video` 使用 `MblogCardInfo` 结构（封面 + `media_info`）。
 */
@Immutable
@Serializable
data class WeiboMixMediaDataDto(
    // ---- 图片 ----
    @SerialName("pic_id") val picId: String? = null,
    val thumbnail: WeiboPicDetailDto? = null,
    val bmiddle: WeiboPicDetailDto? = null,
    val large: WeiboPicDetailDto? = null,
    val original: WeiboPicDetailDto? = null,
    val type: String? = null,
    val video: String? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    // ---- 视频 ----
    @Serializable(with = WeiboPagePicSerializer::class) @SerialName("page_pic") val pagePic: String? = null,
    @SerialName("page_title") val pageTitle: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("page_pic_width") val pagePicWidth: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("page_pic_height") val pagePicHeight: Int? = null,
    @SerialName("media_info") val mediaInfo: WeiboMediaInfoDto? = null,
    @Serializable(with = FlexibleIntSerializer::class) val duration: Int? = null
)

@Serializable
data class VideoStreamInfo(
    val label: String,
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0L
)

@Serializable
data class WeiboPlaybackMetaDto(
    val label: String? = null,
    @SerialName("quality_desc") val qualityDesc: String? = null,
    @SerialName("quality_label") val qualityLabel: String? = null,
    @SerialName("quality_class") val qualityClass: String? = null,
    val type: Int? = null
)

@Serializable
data class WeiboPlaybackPlayInfoDto(
    val url: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val width: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val height: Int? = null,
    @Serializable(with = FlexibleLongSerializer::class) val bitrate: Long? = null,
    val fps: Float? = null,
    val size: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null
)

@Serializable
data class WeiboPlaybackItemDto(
    val meta: WeiboPlaybackMetaDto? = null,
    @SerialName("play_info") val playInfo: WeiboPlaybackPlayInfoDto? = null
)

/**
 * 微博后端在 playback_list 字段上既可能返回数组，也可能在无数据时返回空字符串 "" 或对象，
 * 此序列化器保证容错且不阻断外层卡片与微博列表的反序列化。
 */
object FlexiblePlaybackListSerializer : KSerializer<List<WeiboPlaybackItemDto>> {
    override val descriptor: SerialDescriptor =
        ListSerializer(WeiboPlaybackItemDto.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<WeiboPlaybackItemDto>) {
        ListSerializer(WeiboPlaybackItemDto.serializer()).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<WeiboPlaybackItemDto> {
        if (decoder !is JsonDecoder) {
            return emptyList()
        }
        val element = decoder.decodeJsonElement()
        if (element !is JsonArray) {
            return emptyList()
        }
        val list = mutableListOf<WeiboPlaybackItemDto>()
        for (item in element) {
            try {
                val parsed = decoder.json.decodeFromJsonElement(WeiboPlaybackItemDto.serializer(), item)
                list.add(parsed)
            } catch (_: Exception) {
                // 单条格式不符跳过，不影响其余清晰度
            }
        }
        return list
    }
}

@Serializable
data class WeiboMediaInfoDto(
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("stream_url_hd") val streamUrlHd: String? = null,
    @SerialName("mp4_1080p") val mp41080pAlt: String? = null,
    @SerialName("mp4_1080p_mp4") val mp41080p: String? = null,
    @SerialName("mp4_720p_mp4") val mp4720p: String? = null,
    @SerialName("mp4_hd_url") val mp4Hd: String? = null,
    @SerialName("mp4_sd_url") val mp4Sd: String? = null,
    @SerialName("mp4_ld_mp4") val mp4Ld: String? = null,
    @SerialName("h265_mp4_hd") val h265Mp4Hd: String? = null,
    @SerialName("h265_mp4_ld") val h265Mp4Ld: String? = null,
    /** 视频时长（秒），视频 tab 的封面角标使用 */
    @Serializable(with = FlexibleIntSerializer::class) val duration: Int? = null,
    /** 视频标题：新版字段 / 旧版字段 */
    @SerialName("video_title") val videoTitle: String? = null,
    @SerialName("kol_title") val kolTitle: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @Serializable(with = FlexiblePlaybackListSerializer::class)
    @SerialName("playback_list") val playbackList: List<WeiboPlaybackItemDto> = emptyList()
) {
    /**
     * 获取所有可用的清晰度档位列表，按分辨率（高 -> 低）及码率降序排列。
     */
    val availableStreams: List<VideoStreamInfo>
        get() {
            val list = mutableListOf<VideoStreamInfo>()
            val seenUrls = mutableSetOf<String>()

            // 1. 优先解析现代微博视频的 playback_list
            playbackList.forEach { item ->
                val playInfo = item.playInfo
                val rawUrl = playInfo?.url
                if (!rawUrl.isNullOrBlank() && seenUrls.add(rawUrl)) {
                    val label = item.meta?.label
                        ?: item.meta?.qualityDesc
                        ?: if ((playInfo.height ?: 0) > 0) "${playInfo.height}P" else "高清"
                    list.add(
                        VideoStreamInfo(
                            label = label,
                            url = rawUrl,
                            width = playInfo.width ?: 0,
                            height = playInfo.height ?: 0,
                            bitrate = playInfo.bitrate ?: 0L
                        )
                    )
                }
            }

            // 2. 补充直接字段并去重
            fun addIfAbsent(label: String, url: String?, width: Int = 0, height: Int = 0) {
                if (!url.isNullOrBlank() && seenUrls.add(url)) {
                    list.add(VideoStreamInfo(label = label, url = url, width = width, height = height))
                }
            }

            addIfAbsent("1080P 超清", mp41080p ?: mp41080pAlt, 1920, 1080)
            addIfAbsent("H.265 超清", h265Mp4Hd, 1920, 1080)
            addIfAbsent("720P 高清", mp4720p, 1280, 720)
            addIfAbsent("高清", streamUrlHd ?: mp4Hd)
            addIfAbsent("标清", mp4Sd)
            addIfAbsent("流畅", mp4Ld ?: h265Mp4Ld)
            addIfAbsent("默认", streamUrl)

            return list.sortedWith(
                compareByDescending<VideoStreamInfo> { it.height }
                    .thenByDescending { it.bitrate }
            )
        }

    val bestVideoUrl: String?
        get() = mp4720p ?: mp4Hd ?: mp4Sd ?: streamUrl ?: availableStreams.firstOrNull()?.url
}

// 微博卡片与搜索/热搜数据模型
@Serializable
data class WeiboCardResponse(
    val ok: Int = 1,
    val data: WeiboCardContainerDto? = null
)

@Serializable
data class WeiboCardContainerDto(
    val cards: List<WeiboCardDto> = emptyList(),
    @SerialName("userInfo") val userInfo: WeiboUserDto? = null,
    @SerialName("cardlistInfo") val cardlistInfo: WeiboCardlistInfoDto? = null,
    /**
     * 通用 container 的分栏配置（`data.tabs`）。
     *
     * ⚠️ **搜索页不走这里**：真机 `searchall` 响应里没有 `data.tabs`，
     * 搜索分栏在 `data.cardlistInfo.cardlist_head_cards[].channel_list`
     * —— 见 [WeiboCardlistInfoDto.cardlistHeadCards]。
     * 本字段仅作为兜底来源保留。
     */
    val tabs: List<WeiboSearchTabDto> = emptyList(),
    /** `data.tab_default`：服务端指定的默认选中分栏下标 */
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("tab_default")
    val tabDefault: Int? = null
)

/** 服务端下发的单个搜索分栏（对应 `data.tabs[]`）。 */
@Immutable
@Serializable
data class WeiboSearchTabDto(
    /** 分栏标题，如「综合」「视频」「图片」「用户」 */
    val title: String? = null,
    /** 该分栏的 containerid，可直接作为 `containerid` 参数发起请求 */
    val containerid: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val type: Int? = null,
    val scheme: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("showDot") val showDot: Boolean = false
)

/**
 * 一页搜索结果 + 服务端下发的分栏配置。
 *
 * 微博搜索（`container/getIndex` + `page_type=searchall`）返回的是 `cards[]`，
 * 每张卡带 `card_type`，卡内还可能带 `card_group[]`。
 * 这里保留结构化的 [items]（微博卡 / 用户卡 / 话题卡），
 * 而不是把卡片拍平成微博列表 —— 否则用户卡、话题卡会被整体丢弃。
 */
data class SearchPageResult(
    val items: List<SearchResultItem> = emptyList(),
    val tabs: List<WeiboSearchTabDto> = emptyList(),
    val defaultTabIndex: Int = 0
) {
    /** 只取微博本体；供只需要微博列表的调用方（uid / 昵称解析等）使用。 */
    val statuses: List<WeiboStatusDto> by lazy {
        items.asSequence()
            .filter { it.kind == SearchResultKind.POST }
            .mapNotNull { it.status }
            .toList()
    }
}

/** 搜索结果列表项的种类。 */
enum class SearchResultKind { POST, USER, TOPIC, CHIP_GROUP }

/**
 * 搜索结果列表项。
 *
 * 四种来源（按内容形状分派，见 `WeiboApiService#buildSearchItems`）：
 * - [SearchResultKind.POST]：`card.mblog` / `card_group[].mblog` → 微博卡
 * - [SearchResultKind.USER]：条目带完整 `user` → 用户卡
 * - [SearchResultKind.TOPIC]：条目带 `title_sub` / `title` / 封面图 → 话题（图文）卡
 * - [SearchResultKind.CHIP_GROUP]：卡片组里全是纯文本词条 → 「相关搜索」「热门话题」这类词条组
 */
@Immutable
data class SearchResultItem(
    val kind: SearchResultKind,
    /** [SearchResultKind.POST] 时非空 */
    val status: WeiboStatusDto? = null,
    /** [SearchResultKind.USER] / [SearchResultKind.TOPIC] 时非空 */
    val card: WeiboSearchCardInfo? = null,
    /** [SearchResultKind.CHIP_GROUP] 时非空 */
    val chipGroup: WeiboSearchChipGroup? = null,
    /** Lazy 列表使用的稳定 key（由生成方保证同一次加载内唯一） */
    val stableKey: String = ""
)

/**
 * 词条组：卡片组下发的**纯文本条目**集合。
 *
 * 微博把「相关搜索」「热门话题」「搜索发现」这类内容也做成卡片组下发：
 * 顶层卡带 `title`（组标题），`card_group[]` 每条只有 `title`（词条）与 `scheme`（点击后的深链），
 * 没有微博 / 用户 / 图片。全部条目都是纯文本时才归为词条组，否则按普通卡片组处理。
 */
@Immutable
data class WeiboSearchChipGroup(
    /** 组标题，如「相关搜索」；服务端没给则只渲染词条 */
    val title: String? = null,
    val chips: List<WeiboSearchChip> = emptyList()
)

/** 词条组里的单个词条。 */
@Immutable
data class WeiboSearchChip(
    /** 词条文本 */
    val text: String,
    /** 二级文案（热度 / 说明），可为空 */
    val extra: String? = null,
    /** 点击后的深链（通常 `sinaweibo://searchall?containerid=...&q=...`） */
    val scheme: String? = null
) {
    /**
     * 点击后应当搜索的关键词。
     *
     * 优先从 `scheme` 的 `q` 参数取（服务端可能对词条做过改写/高亮），取不到就用词条本身。
     */
    val keyword: String
        get() = scheme
            ?.let { runCatching { android.net.Uri.parse(it).getQueryParameter("q") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: text
}

/**
 * 用户卡 / 话题卡的展示数据。
 *
 * 由 [WeiboCardDto]（顶层卡）与 [WeiboCardGroupItemDto]（`card_group` 条目）归一而来。
 *
 * 字段定义（对应服务端卡片 JSON key）：
 * - 顶层卡基础字段：`card_type` / `card_type_name` / `title` / `itemid` / `scheme`
 * - 用户卡：`user` / `desc1` / `buttons` / `right_pic` / `right_word`
 * - 带副标题卡：`title_sub` / `desc1` / `desc2` / `desc3` / `pic_url` / `pic_scheme`
 * - 卡片组条目：`title` / `desc1` / `desc2` / `pic` / `pic_big` /
 *   `object_type` / `online_users` / `video_duration` / `user`
 *
 * 注意：`desc1` / `desc2` 是**服务端预格式化好的展示文案**（认证说明、粉丝数等），
 * 直接渲染即可，不要自行拼接。
 */
@Immutable
data class WeiboSearchCardInfo(
    val title: String? = null,
    val titleSub: String? = null,
    val desc1: String? = null,
    val desc2: String? = null,
    val desc3: String? = null,
    /** 卡片主图（顶层 `pic_url` / 条目 `pic`） */
    val picUrl: String? = null,
    /** 条目大图 `pic_big` */
    val picBigUrl: String? = null,
    val scheme: String? = null,
    val itemid: String? = null,
    val objectType: String? = null,
    val onlineUsers: Int? = null,
    val user: WeiboUserDto? = null,
    val buttons: List<WeiboCardButtonDto> = emptyList()
) {
    /** 卡片主图：优先大图。 */
    val bestPicUrl: String? get() = picBigUrl?.takeIf { it.isNotBlank() } ?: picUrl?.takeIf { it.isNotBlank() }
}

@Serializable
data class WeiboCardlistInfoDto(
    val containerid: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val total: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val page: Int? = null,
    /**
     * `cardlistInfo.cardlist_head_cards`：**搜索页分栏列表的真正来源**（真机实测）。
     *
     * 结构：[WeiboCardlistHeadCardDto.channelList] 才是分栏数组，每项含
     * `name`（分栏名）/ `containerid`（切栏用的容器 id）/ `scheme`。
     * 例：`searchall` 首页返回 `channel_list = [综合(1)、实时(61)、图片(63)、用户(3)、视频(64)、关注(62)、热门(60)、…]`。
     *
     * 响应里**没有** `data.tabs`，也没有 `sub_header`；`sub_header` 仅作为兼容来源保留在
     * [WeiboCardlistInfoDto.subHeader]。
     */
    @SerialName("cardlist_head_cards") val cardlistHeadCards: List<WeiboCardlistHeadCardDto> = emptyList(),
    /**
     * `cardlistInfo.sub_header`：**兼容来源**。
     *
     * 真机 `searchall` 响应里不存在该字段，实际分栏走 [cardlistHeadCards]；
     * 这里保留解析能力以防服务端回退。
     */
    @SerialName("sub_header") val subHeader: List<WeiboCardDto> = emptyList()
)

/**
 * `cardlistInfo.cardlist_head_cards[]`：搜索页头部卡，分栏数组挂在 [channelList] 上。
 *
 * 对应模型含 `cardlist_head_cards` / `head_type` / `head_type_name` / `show_menu` /
 * `menu_scheme` / `channel_list` 常量串。
 */
@Immutable
@Serializable
data class WeiboCardlistHeadCardDto(
    /** 头部卡种类，真机搜索页恒为 `0` */
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("head_type")
    val headType: Int? = null,
    /** 头部卡种类名，如 `channel_list`（分栏）/ `immersive`（沉浸式头图） */
    @SerialName("head_type_name") val headTypeName: String? = null,
    /**
     * 沉浸式头部卡的页面标题（`head_type=1`，如热搜页的「微博热搜」）。
     * 挂在头部卡上而不是卡片列表里，用来给页面大标题取名字（客户端不写死文案）。
     */
    @SerialName("title_top") val titleTop: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("show_menu")
    val showMenu: Int? = null,
    /** 「筛选」菜单的 scheme（`sinaweibo://cardlist?containerid=…&extparam=…`） */
    @SerialName("menu_scheme") val menuScheme: String? = null,
    /** 分栏数组 */
    @SerialName("channel_list") val channelList: List<WeiboSearchChannelDto> = emptyList()
)

/**
 * 搜索页的一个分栏（`cardlist_head_cards[0].channel_list[]`）。
 *
 * 真机样例（关键词「美食」）：
 * ```json
 * { "id": "1", "name": "综合", "containerid": "100103type=1&q=美食&t=1",
 *   "scheme": "sinaweibo://selectchannel?containerid=100103type=1&q=美食&t=1",
 *   "must_show": 1, "default_add": 1 }
 * ```
 * 分栏名与 containerid 全部由服务端下发，客户端不硬编码。
 */
@Immutable
@Serializable
data class WeiboSearchChannelDto(
    /** 分栏 id（真机为字符串数字，如 `"1"` / `"61"` / `"63"`；缺省为空串） */
    @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    /** 分栏名（综合 / 实时 / 图片 / 用户 / 视频 …） */
    val name: String? = null,
    /** 该分栏的 containerid，直接作为切栏请求的 `containerid` 参数 */
    val containerid: String? = null,
    /** 切栏 scheme，形如 `sinaweibo://selectchannel?containerid=…` */
    val scheme: String? = null,
    /** 服务端标记的分栏类型（真机「图片」栏为 `"cardlist"`，其余缺省为空串） */
    @Serializable(with = FlexibleStringSerializer::class) val type: String = "",
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("must_show")
    val mustShow: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("default_add")
    val defaultAdd: Int? = null
)

@Serializable
data class WeiboCardDto(
    /** 真机响应里可能是数字也可能是字符串（`"card_type": "4"`），用弹性解析兜底。 */
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("card_type")
    val cardType: Int? = null,
    /** `card_type_name`：卡片种类名 */
    @SerialName("card_type_name") val cardTypeName: String? = null,
    val title: String? = null,
    val desc: String? = null,
    /** `title_sub`：副标题 */
    @SerialName("title_sub") val titleSub: String? = null,
    val desc1: String? = null,
    val desc2: String? = null,
    /** 真机响应里可能是字符串也可能是**埋点对象**（`{"act_code":…}`），弹性解析兜底。 */
    @Serializable(with = FlexibleStringSerializer::class) val itemid: String = "",
    val scheme: String? = null,
    /** `containerId`：该卡片对应的 containerid（搜索分栏卡用它） */
    val containerId: String? = null,
    /** 顶层卡片主图 */
    @SerialName("pic_url") val picUrl: String? = null,
    /** 顶层卡片的图标（如「用户」栏顶部的「筛选」入口卡） */
    val pic: String? = null,
    /** `desc_extr`：服务端补充说明文案（如「精确查找你感兴趣的人」） */
    @SerialName("desc_extr") val descExtr: String? = null,
    /** 顶层卡片的完整用户对象 */
    val user: WeiboUserDto? = null,
    val buttons: List<WeiboCardButtonDto> = emptyList(),
    val mblog: WeiboStatusDto? = null,
    @SerialName("card_group") val cardGroup: List<WeiboCardGroupItemDto> = emptyList()
)

/**
 * 卡片按钮：`{ name, type, url, img, desc, pageId, actionlog, hidden }`。
 *
 * ⚠️ [actionlog] 在真机响应里**既可能是字符串也可能是对象**
 * （`"actionlog":"…"` 或 `{"act_code":92,"ext":"…"}`），因此用 [FlexibleStringSerializer] 兜底 ——
 * 之前声明为普通 `String?` 会让「用户卡所在卡片组」整棵树解析抛异常，搜索页直接报错。
 */
@Immutable
@Serializable
data class WeiboCardButtonDto(
    val name: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val type: Int? = null,
    val url: String? = null,
    val scheme: String? = null,
    /**
     * 图片字段同样可能下发对象（如 `{"url": …}`），统一兜成字符串。
     */
    @Serializable(with = FlexibleStringSerializer::class) val img: String = "",
    val desc: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val actionlog: String = ""
)

/**
 * `card_group` 里的单个条目。
 *
 * `desc1` / `desc2` / `desc3` / `title_sub` 都是**服务端预格式化文案**，直接渲染即可。
 */
@Immutable
@Serializable
data class WeiboCardGroupItemDto(
    /** 真机响应里可能是数字也可能是字符串（`"card_type": "4"`）。 */
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("card_type")
    val cardType: Int? = null,
    val title: String? = null,
    @SerialName("title_sub") val titleSub: String? = null,
    val desc: String? = null,
    val desc1: String? = null,
    val desc2: String? = null,
    val desc3: String? = null,
    @SerialName("desc_extr") val descExtr: String? = null,
    @SerialName("desc1_scheme") val desc1Scheme: String? = null,
    val icon: String? = null,
    val scheme: String? = null,
    /**
     * 左侧小图标（`card_type=101` 的分段标题卡 / 入口卡用，如「文娱热搜」的娱乐图标）。
     */
    @SerialName("left_tag_img") val leftTagImg: String? = null,
    /** 副标题（入口卡用，如「好友热搜」的「好友圈的新鲜热点」）。 */
    @SerialName("sub_title") val subTitle: String? = null,
    /** 是否展示右箭头（`card_type=6` 的「查看更多」条目恒为 1）。 */
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("display_arrow")
    val displayArrow: Int? = null,
    /** 真机响应里可能是字符串也可能是**埋点对象**（`{"act_code":…}`），弹性解析兜底。 */
    @Serializable(with = FlexibleStringSerializer::class) val itemid: String = "",
    val pic: String? = null,
    @SerialName("pic_big") val picBig: String? = null,
    @SerialName("object_type") val objectType: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("online_users") val onlineUsers: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("video_duration") val videoDuration: Int? = null,
    /**
     * 条目内嵌用户。
     *
     * 注意：部分卡片这里只有 `{ uid, username }`，
     * 取不到昵称/粉丝数时会被解析成空对象，渲染侧需判空。
     */
    val user: WeiboUserDto? = null,
    val buttons: List<WeiboCardButtonDto> = emptyList(),
    val mblog: WeiboStatusDto? = null
)

/**
 * 把顶层卡片归一成展示信息。
 *
 * `desc1` / `desc2` 在顶层卡上常常缺省，回落到服务端另一套文案字段 `desc` / `desc_extr`
 * （如「用户」栏顶部的「筛选」入口卡：`desc` = 标题、`desc_extr` = 说明）。
 */
fun WeiboCardDto.toSearchCardInfo(): WeiboSearchCardInfo = WeiboSearchCardInfo(
    title = title,
    titleSub = titleSub,
    desc1 = desc1 ?: desc,
    desc2 = desc2 ?: descExtr,
    picUrl = picUrl ?: pic,
    scheme = scheme,
    itemid = itemid,
    user = user,
    buttons = buttons
)

/** 把卡片组条目归一成展示信息。 */
fun WeiboCardGroupItemDto.toSearchCardInfo(): WeiboSearchCardInfo = WeiboSearchCardInfo(
    title = title,
    titleSub = titleSub,
    desc1 = desc1,
    desc2 = desc2,
    desc3 = desc3,
    picUrl = pic,
    picBigUrl = picBig,
    scheme = scheme,
    itemid = itemid,
    objectType = objectType,
    onlineUsers = onlineUsers,
    user = user,
    buttons = buttons
)

/**
 * 把卡片组条目归一成词条（相关搜索 / 热门话题 / 搜索发现）；不是纯文本词条时返回 null。
 *
 * 「纯文本词条」的判定：有词条文本 `title` + 有跳转 `scheme`，且**没有**微博 / 用户 / 图片 ——
 * 这几样只要有一样，就说明它是内容卡而不是词条。
 */
fun WeiboCardGroupItemDto.toSearchChipOrNull(): WeiboSearchChip? {
    val text = title?.takeIf { it.isNotBlank() } ?: return null
    val link = scheme?.takeIf { it.isNotBlank() } ?: return null
    if (mblog != null || user != null) return null
    if (!pic.isNullOrBlank() || !picBig.isNullOrBlank()) return null
    return WeiboSearchChip(
        text = text,
        extra = desc1?.takeIf { it.isNotBlank() },
        scheme = link
    )
}

/**
 * 把一个完整的 `card_group` 解释成词条组；只要有一条不是纯文本词条就返回 null。
 *
 * 要求组内至少两条：微博下发的词条组（相关搜索 / 热门话题）都是成组的，
 * 单条文本更像普通话题卡，交给 [classifySearchCard] 处理，避免把已有渲染退化掉。
 */
fun List<WeiboCardGroupItemDto>.toSearchChipGroupOrNull(title: String? = null): WeiboSearchChipGroup? {
    if (size < 2) return null
    val chips = mapNotNull { it.toSearchChipOrNull() }
    if (chips.size != size) return null
    return WeiboSearchChipGroup(title = title?.takeIf { it.isNotBlank() }, chips = chips)
}

/**
 * 判定卡片的渲染种类；返回 null 表示没有可渲染内容（丢弃）。
 *
 * 优先级按数据完整度：带用户对象 → 用户卡；否则只要有标题 / 副标题 / 图 / 描述 → 话题卡。
 */
fun WeiboSearchCardInfo.classifySearchCard(): SearchResultKind? = when {
    user != null -> SearchResultKind.USER
    title != null || titleSub != null || desc1 != null || picUrl != null || picBigUrl != null ->
        SearchResultKind.TOPIC

    else -> null
}

// ===== 发现页「微博实时热搜」结构 =====
//
// 真机响应（`106003type=25&t=3&disable_hot=1&filter_type=realtimehot`）是**若干张卡片**，
// 每张卡是一个「榜段」，段内条目形态并不统一（都是服务端下发的，客户端不硬编码）：
//
// ```
// cards[0] title="实时热点，每分钟更新一次"  52 条 = 1 置顶 + 50 榜 + 1「查看更多」
//    [0] cate:5000 无 realpos 无热度值 pic=…img_search_stick@3x.png   ← 置顶
//    [4] cate:5001 无 realpos is_ad_pos:1 promotion={…} pic=…orange.png ← 商业推广位（不占排名）
//    [1] cate:5001 realpos:1 pic=wb_search_img_search_1.png           ← 第 1 名
//    [51] card_type=6 desc="查看更多实时上升热点"                       ← 查看更多
// cards[1] title="实时上升热点"  20 条（cate:5002, desc_extr="热度飙升"）+ 查看更多
// cards[2] group[0]=card_type 101 title=" 文娱热搜 " ← 段标题写在组内标题卡上；之后 10 条 + 查看更多
// cards[3] 只有一个 card_type 101 条目：title=" 好友热搜" sub_title="好友圈的新鲜热点" ← 入口卡
// cards[4] 只有一个 card_type 101 条目：title=" 热搜榜规则说明"                       ← 入口卡
// ```
//
// 因此「第一条是置顶」成立：置顶项 `cate:5000`、没有 `realpos`、没有热度值，
// 而排名从 `realpos:1` 开始 —— 置顶**不计入名次**；「实时上升热点」是另一张卡片（另一个榜段）。

/**
 * 首屏默认榜单（「我的」榜）的 containerid。
 *
 * 真实榜单列表以服务端下发的 `channel_list` 为准，这里只是「还没拉到榜单列表时」的初始请求参数。
 */
const val DEFAULT_HOT_SEARCH_CONTAINER_ID =
    "106003_-_type:25_-_filter_type:mineband"

/** 兜底热搜榜（「热搜」榜）的 containerid。若未登录或「我的」请求失败时，可回退到此榜单。 */
const val REALTIME_HOT_SEARCH_CONTAINER_ID =
    "106003type=25&t=3&disable_hot=1&filter_type=realtimehot"

/** 榜段的段标题卡 / 入口卡（服务端 `card_type=101`）。 */
private const val HOT_SEARCH_TITLE_CARD_TYPE = 101

/** 「查看更多」条目（服务端 `card_type=6`）。 */
private const val HOT_SEARCH_MORE_CARD_TYPE = 6

private val HOT_SEARCH_REALPOS = Regex("realpos:(\\d+)")

/**
 * 热搜榜的一个条目。
 *
 * - [rank]：服务端 `itemid` 里的 `realpos:N`，即**真实名次**（置顶与推广位没有）；
 * - [isPinned]：置顶项（`cate:5000` / 角标图带 `stick`），不占名次；
 * - [isPromoted]：商业推广位（`is_ad_pos:1`），也不占名次；
 * - [isMore]：「查看更多…」跳转条目（`card_type=6`），文案由服务端下发；
 * - [badge]：`pic`，服务端下发的角标图（置顶角标 / 推广小圆点）；
 * - [icon]：`icon`，服务端下发的标记图（热 / 新 / 爆 / 上升）。
 */
@Immutable
data class HotSearchItem(
    val title: String,
    val heat: String = "",
    val icon: String? = null,
    val badge: String? = null,
    val rank: Int? = null,
    val isPinned: Boolean = false,
    val isPromoted: Boolean = false,
    val isMore: Boolean = false,
    val scheme: String? = null
)

/** 热搜榜的一个「段」（服务端下发的一张卡片），例：实时热点 / 实时上升热点 / 文娱热搜。 */
@Immutable
data class HotSearchSection(
    val title: String? = null,
    val icon: String? = null,
    val items: List<HotSearchItem> = emptyList()
)

/** 热搜榜里的入口卡（例：好友热搜 / 热搜榜规则说明）。 */
@Immutable
data class HotSearchEntry(
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    val scheme: String? = null
)

/**
 * 热搜页的一个**榜单**（服务端 `cardlistInfo.cardlist_head_cards[].channel_list[]` 下发）。
 *
 * 真机样例（同一份 list 在任意榜单的响应里都会回来）：

 * 我的（`filter_type=mineband`）/ 热搜（`realtimehot`）/ 文娱（`fun`）/ 社会（`social`）/ 同城（`region`），
 * 账号不同榜单集合也不同，因此只能整体按服务端下发渲染，不能写死。
 */
@Immutable
data class HotSearchChannel(
    val id: Int? = null,
    val name: String = "",
    /** 切榜用的 containerid，直接作为 `getIndex` 的 `containerid` 参数 */
    val containerid: String = "",
    val scheme: String? = null,
    /** 服务端标记的「默认加入」 */
    val isDefault: Boolean = false
)

/** 发现页热搜榜：页面标题 + 可切换的榜单列表 + 若干榜段 + 若干入口卡。 */
@Immutable
data class HotSearchBoard(
    /** 服务端 `head_type=1` 的 `title_top`，如「微博热搜」 */
    val title: String? = null,
    val channels: List<HotSearchChannel> = emptyList(),
    val sections: List<HotSearchSection> = emptyList(),
    val entries: List<HotSearchEntry> = emptyList()
) {
    /**
     * 榜单没有任何条目（例：同城榜在所在城市没有热点时，服务端只回一张通栏图和一张城市切换卡）。
     *
     * 此时整页走空占位，不再渲染段标题 / 通栏图 / 入口卡 —— 那些都不是榜单内容。
     */
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
}

/** 把一个热搜卡片组条目归一成榜单条目；不是榜单条目（如段标题卡）时返回 null。 */
fun WeiboCardGroupItemDto.toHotSearchItem(): HotSearchItem? {
    // 通栏图（`card_type=22`，没有文案，如同城榜所在城市没热点时的空状态图）不渲染：
    // 榜里没有条目时整页走空占位，比铺一张通栏图干净
    val text = desc?.takeIf { it.isNotBlank() } ?: return null
    val id = itemid

    return HotSearchItem(
        title = text,
        heat = descExtr.orEmpty(),
        icon = icon,
        badge = pic,
        rank = HOT_SEARCH_REALPOS.find(id)?.groupValues?.get(1)?.toIntOrNull(),
        isPinned = id.contains("cate:5000") || pic?.contains("stick") == true,
        isPromoted = id.contains("is_ad_pos:1"),
        isMore = cardType == HOT_SEARCH_MORE_CARD_TYPE,
        scheme = scheme
    )
}

/** 把一张热搜卡片归一成榜段；没有可展示条目时返回 null。 */
fun WeiboCardDto.toHotSearchSection(): HotSearchSection? {
    // 段标题：优先顶层 title（实时热点 / 实时上升热点），否则取组内的 card_type=101 标题卡（文娱热搜）。
    // 服务端标题常带前后空格（" 文娱热搜 "），统一 trim 掉。
    val titleCard = cardGroup.firstOrNull { it.cardType == HOT_SEARCH_TITLE_CARD_TYPE }
    val sectionTitle = (title ?: titleCard?.title)?.trim()?.takeIf { it.isNotBlank() }
    val items = cardGroup.mapNotNull { it.toHotSearchItem() }
    if (items.isEmpty()) return null

    return HotSearchSection(title = sectionTitle, icon = titleCard?.leftTagImg, items = items)
}

/**
 * 把热搜页响应（`getIndex` 的 `data`）归一成 [HotSearchBoard]。
 *
 * - 榜单列表：服务端 `cardlistInfo.cardlist_head_cards[].channel_list[]`（换榜就是换 containerid 重新请求）；
 * - 页面标题：服务端 `head_type=1` 头部卡的 `title_top`；
 * - 榜段 / 入口卡：只有一个 `card_type=101` 条目的卡片是**入口卡**（好友热搜 / 榜规则说明），
 *   其余卡片按榜段处理。
 */
fun WeiboCardContainerDto.toHotSearchBoard(): HotSearchBoard {
    val sections = mutableListOf<HotSearchSection>()
    val entries = mutableListOf<HotSearchEntry>()

    cards.forEach { card ->
        val onlyItem = card.cardGroup.singleOrNull()
        if (onlyItem?.cardType == HOT_SEARCH_TITLE_CARD_TYPE) {
            val label = onlyItem.title?.trim()
            if (!label.isNullOrBlank()) {
                entries += HotSearchEntry(
                    title = label,
                    // 副标题：优先 `sub_title`，没有就用 `desc`（同城榜的城市切换卡是「当前」+「点击切换」）
                    subtitle = onlyItem.subTitle ?: onlyItem.desc,
                    icon = onlyItem.leftTagImg,
                    scheme = onlyItem.scheme
                )
            }
        } else {
            card.toHotSearchSection()?.let { sections += it }
        }
    }

    return HotSearchBoard(
        title = cardlistInfo?.toHotSearchPageTitle(),
        channels = cardlistInfo?.toHotSearchChannels().orEmpty(),
        sections = sections,
        entries = entries
    )
}

/** 热搜页大标题（服务端 `head_type=1` 头部卡的 `title_top`，如「微博热搜」）。 */
fun WeiboCardlistInfoDto.toHotSearchPageTitle(): String? =
    cardlistHeadCards.firstNotNullOfOrNull { it.titleTop?.trim()?.takeIf { s -> s.isNotBlank() } }

/**
 * 热搜页可切换的**榜单**列表（服务端下发）。
 *
 * 每条必须有 `name`（榜名）和 `containerid`（切榜请求参数），缺任一就丢弃。
 */
fun WeiboCardlistInfoDto.toHotSearchChannels(): List<HotSearchChannel> =
    cardlistHeadCards.flatMap { headCard ->
        headCard.channelList.mapNotNull { channel ->
            val id = channel.containerid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = channel.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HotSearchChannel(
                id = channel.id.toIntOrNull(),
                name = label,
                containerid = id,
                scheme = channel.scheme,
                isDefault = channel.defaultAdd == 1
            )
        }
    }

// 微博评论模型
/**
 * 评论区的「评论管理」配置，位于评论响应的 `data.status.comment_manage_info`。
 *
 * `approval_comment_type = 1` 表示博主开启了「精选评论」：此时评论接口只下发博主精选过的评论，
 * 而不是全部评论（实测：一条 total_number=1041 的微博只返回 1 条精选评论）。
 */
@Serializable
data class CommentManageInfoDto(
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("approval_comment_type") val approvalCommentType: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("comment_permission_type") val commentPermissionType: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("comment_sort_type") val commentSortType: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("comment_anon_type") val commentAnonType: Int? = null
) {
    /** 博主是否开启了精选评论。 */
    val isApprovalEnabled: Boolean
        get() = approvalCommentType == 1
}

@Serializable
data class CommentStatusDto(
    @SerialName("comment_manage_info") val commentManageInfo: CommentManageInfoDto? = null
)

@Serializable
data class CommentsDataDto(
    val data: List<WeiboCommentDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("total_number") val totalNumber: Int? = null,
    @Serializable(with = FlexibleLongSerializer::class) @SerialName("max_id") val maxId: Long? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("max_id_type") val maxIdType: Int? = null,
    val status: CommentStatusDto? = null
) {
    /** 该微博开启了「博主精选评论」，列表里只有精选出来的评论。 */
    val approvalCommentsOnly: Boolean
        get() = status?.commentManageInfo?.isApprovalEnabled == true
}

@Immutable
@Serializable
data class WeiboCommentDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long? = null,
    val text: String = "",
    val user: WeiboUserDto? = null,
    @SerialName("created_at") val createdAt: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("like_count") val likeCount: String = "0",
    @Serializable(with = CommentListSerializer::class) val comments: List<WeiboCommentDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("total_number") val totalNumber: Int? = null,
    @Serializable(with = FlexibleLongSerializer::class) @SerialName("max_id") val maxId: Long? = null,
    @Serializable(with = ReplyCommentSerializer::class) @SerialName("reply_comment") val replyComment: WeiboCommentDto? = null,
    @Serializable(with = CommentOptionalTextSerializer::class) @SerialName("reply_original_text") val replyOriginalText: String? = null,
    @Serializable(with = CommentOptionalTextSerializer::class) val source: String? = null,
    @Serializable(with = CommentOptionalTextSerializer::class) @SerialName("region_name") val regionName: String? = null,
    /** 部分端返回 `like`，部分端返回 `liked`，两者都兼容。 */
    @Serializable(with = FlexibleBooleanSerializer::class) @SerialName("like") val like: Boolean? = null,
    @Serializable(with = FlexibleBooleanSerializer::class) @SerialName("liked") val liked: Boolean? = null,
    @Serializable(with = CommentPicIdsSerializer::class) @SerialName("pic_ids") val picIds: List<String> = emptyList(),
    @Serializable(with = CommentPicInfosSerializer::class) @SerialName("pic_infos") val picInfos: Map<String, WeiboPicInfoDto> = emptyMap(),
    @Serializable(with = CommentPicUrlsSerializer::class) @SerialName("pic_urls") val picUrls: List<WeiboPicUrlDto> = emptyList(),
    /** hotflow 下发的单张评论图（`pic`，最常见的一图评论）。 */
    @Serializable(with = CommentPicSerializer::class) val pic: WeiboCommentPicDto? = null,
    /** hotflow 下发的多图（`pics`，可能是数组也可能是单个对象，还可能夹带无效项）。 */
    @Serializable(with = CommentPicsSerializer::class) val pics: List<WeiboCommentPicDto> = emptyList()
) {
    /** Lazy 列表使用的稳定 key（避免依赖 data class hashCode 造成 key 漂移）。 */
    val listKey: String by lazy {
        id?.toString() ?: hashCode().toString()
    }

    /** 当前登录用户是否已点赞该评论。 */
    val isLiked: Boolean
        get() = liked == true || like == true

    /**
     * 评论配图（评论同样支持发图）。
     *
     * 微博不同端给的字段并不一致：
     * - `pic_ids` + `pic_infos`：按 picId 顺序拼大图；
     * - `pic` / `pics`：hotflow 下发的评论图（一图评论几乎只给 `pic`）；
     * - `pic_urls`：旧版接口字段。
     *
     * 依次尝试，取到第一组可用地址即可。
     */
    val imageUrls: List<String> by lazy {
        val fromIds = picIds.mapNotNull { picId ->
            picInfos[picId]?.bestDisplayUrl() ?: weiboImageUrlFromId(picId, WeiboImageSize.LARGE)
        }
        val fromPicFields = (listOfNotNull(pic) + pics)
            .distinctBy { it.pid ?: it.large?.url ?: it.url ?: it }
            .mapNotNull { it.bestDisplayUrl() }
        when {
            fromIds.isNotEmpty() -> fromIds
            fromPicFields.isNotEmpty() -> fromPicFields
            picUrls.isNotEmpty() -> picUrls.mapNotNull { pic ->
                (pic.originalPic ?: pic.bmiddlePic ?: pic.thumbnailPic)
                    ?.withWeiboImageSize(WeiboImageSize.LARGE)
            }
            picInfos.isNotEmpty() -> picInfos.values.mapNotNull { it.bestDisplayUrl() }
            else -> emptyList()
        }
    }
}

/** 评论/正文图片统一取一档足够清晰的展示地址，真正加载时会再降到缩略图。 */
private fun WeiboPicInfoDto.bestDisplayUrl(): String? {
    val url = large?.url ?: bmiddle?.url ?: original?.url ?: thumbnail?.url
    return url?.withWeiboImageSize(WeiboImageSize.LARGE)
}

/**
 * 评论配图（`pic` / `pics` 里的元素）。
 *
 * 与 [WeiboPicInfoDto] 的区别是它自带一个 `url`（orj360 档）而没有 pic_id，
 * 且 `pics` 里偶尔会混进 pid 为空、url 形如 `.../orj360/&690` 的无效项。
 */
@Serializable
data class WeiboCommentPicDto(
    val pid: String? = null,
    val url: String? = null,
    val large: WeiboPicDetailDto? = null,
    val bmiddle: WeiboPicDetailDto? = null,
    val original: WeiboPicDetailDto? = null
)

private fun WeiboCommentPicDto.bestDisplayUrl(): String? {
    val url = large?.url ?: url ?: bmiddle?.url ?: original?.url
    // 无效项的 url 里带着未替换的参数（如 `&690`），真实图片地址不会出现裸 `&`
    if (url.isNullOrBlank() || url.contains("&")) return null
    return url.withWeiboImageSize(WeiboImageSize.LARGE)
}

// 关注 / 粉丝列表一页（移动端 container 接口）
@Immutable
data class WeiboUserListPage(
    val items: List<WeiboSearchCardInfo> = emptyList(),
    val total: Int? = null,
    val nextPage: Int? = null
)

/**
 * 「我的关注」页的固定分组容器（与微博客户端一致）。
 *
 * 容器 id 来自反编译产物 `MyFriendActivity.java`：
 * - `231093_-_selffollowed`：关注的人
 * - `231093_-_lastmblog`：最新发博
 * - `231093_-_recently`：经常访问
 */
enum class FollowingListTab(val containerId: String) {
    ALL("231093_-_selffollowed"),
    LATEST_POST("231093_-_lastmblog"),
    RECENT_VISIT("231093_-_recently")
}

// 博主主页时间线整合结果
data class UserTimelineResult(
    val user: WeiboUserDto? = null,
    val statuses: List<WeiboStatusDto> = emptyList(),
    val total: Int? = null,
    val hasMore: Boolean = true
)

// 微博转发列表（网页端与 m 站共用这一份数据结构，信封在解析层拆）
@Serializable
data class RepostsDataDto(
    val data: List<WeiboStatusDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("total_number") val totalNumber: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val max: Int? = null
)

// 微博点赞列表响应
@Serializable
data class AttitudesResponse(
    val ok: Int = 1,
    /** 无内容时服务端返回 ok=0 + msg="这里还没有内容"，不能当成加载失败 */
    val msg: String? = null,
    val data: AttitudesDataDto? = null
)

@Serializable
data class AttitudesDataDto(
    val data: List<WeiboAttitudeDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("total_number") val totalNumber: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val max: Int? = null
)

@Immutable
@Serializable
data class WeiboAttitudeDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long? = null,
    @SerialName("created_at") val createdAt: String = "",
    val source: String? = null,
    val attitude: String? = null,
    val user: WeiboUserDto? = null
) {
    val listKey: String
        get() = id?.toString() ?: user?.id?.toString() ?: hashCode().toString()
}

