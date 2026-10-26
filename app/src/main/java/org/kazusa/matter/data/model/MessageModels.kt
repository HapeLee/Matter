package org.kazusa.matter.data.model

/** 消息页的四个分区。 */
enum class MessageTab(val title: String) {
    DIRECT("私信"),
    MENTION("@我"),
    COMMENT("评论"),
    LIKE("赞")
}

/**
 * 私信会话（消息页「私信」分区）。
 *
 * 数据来自微博移动端消息页 `https://m.weibo.cn/message/msglist`：
 * 每项含 `user`（头像 / 昵称）、`text`（最后一条消息，HTML）、`created_at`（相对时间文案）、
 * `unread`（未读数）与 `scheme`（会话跳转路径，其中带 uid）。
 */
data class MessageConversation(
    val peerUid: String,
    val peerName: String,
    val peerAvatarUrl: String,
    /** 最后一条消息的纯文本摘要。 */
    val preview: String,
    /** 接口直接下发的时间文案（如「刚刚」「10分钟前」「12-25」）。 */
    val timeText: String?,
    val unreadCount: Int
) {
    val key: String get() = peerUid
}

data class MessageConversationPage(
    val conversations: List<MessageConversation>,
    val hasMore: Boolean
)

/**
 * 一条互动提醒（评论 / @我 / 赞）。
 *
 * [text] 是提醒正文（评论内容、@ 我的微博正文、点赞的微博摘要），[sourceText] 是被评论 / 被赞的
 * 原微博摘要，接口没给时为 null。点击时优先按 [statusId] 打开正文。
 */
data class MessageNotice(
    val id: String,
    val peerUid: String,
    val peerName: String,
    val peerAvatarUrl: String,
    val text: String,
    val sourceText: String?,
    val timeText: String?,
    /** 从 `scheme` 里解析出的微博 id；拿不到时为 null，点击退化为打开对方主页。 */
    val statusId: String?
) {
    val key: String get() = id
}

data class MessageNoticePage(
    val notices: List<MessageNotice>,
    val hasMore: Boolean
)

/**
 * 私信聊天记录中的一条（`https://m.weibo.cn/api/chat/list` 的 `msgs`）。
 *
 * 图片私信不会把地址拼在正文里，而是下发 `media_type=1` + `attachment` 对象：
 * [imageUrl] 取自 `attachment.original_image.url`（地址里的 `source` 由服务端给出，不能自己拼）。
 * 没有原图地址时退回缩略图地址，只有 fid 时按 mss 通道拼。
 * 正文里的表情是 `<img alt="[代码]">`，解析成代码后由 [emojiImages] 提供官方图片地址兜底。
 * [pending] 标记本地乐观插入、尚未被服务端确认的消息，气泡降透明度提示。
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    /** 秒级时间戳已归一到毫秒；接口给的是文案时为 null。 */
    val timestampMillis: Long?,
    val timeText: String?,
    /** 图片私信原图地址；非空时气泡渲染图片。 */
    val imageUrl: String? = null,
    /** 正文表情代码 → 官方图片地址（`face.t.sinajs.cn`），本地资源缺失时兜底。 */
    val emojiImages: Map<String, String> = emptyMap(),
    val pending: Boolean = false
) {
    val key: String get() = id
}

data class ChatMessagePage(
    val messages: List<ChatMessage>,
    /** 更早一页的 `max_id`；null 表示已经到最早一条。 */
    val nextMaxId: Long?
)

/** 发送私信成功后服务端确认的消息身份（id 可能缺省，仅用于替换本地乐观插入的临时 id）。 */
data class ChatSendResult(
    val id: String?,
    val timestampMillis: Long?
)

/** webim（api.weibo.com/chat）体系的 appkey 与图片展示通道，契约从其 SPA bundle 核实。 */
internal const val CHAT_WEBIM_SOURCE = "209678993"

/** mss 图片读取通道与私有化 `source`（实测私信图片地址里的取值，见 [chatMssImageUrl]）。 */
internal const val CHAT_MSS_GET = "https://attachment.api.weibo.com/2/mss/msget"
internal const val CHAT_MSS_SOURCE = "2390714039"

/** 只有 fid、没有现成地址时（本地乐观消息等）按 mss 通道拼一张原图地址。 */
internal fun chatMssImageUrl(fid: String): String =
    "$CHAT_MSS_GET?fid=$fid&imageType=origin&source=$CHAT_MSS_SOURCE"
