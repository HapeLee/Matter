package org.kazusa.matter.ui.message

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY = 24 * 60 * 60 * 1000L

/**
 * 聊天气泡上的时间：一天内只给 `HH:mm`，跨天带上日期。
 *
 * 消息页的会话 / 提醒列表直接使用接口下发的时间文案（「刚刚」「10分钟前」「12-25」），
 * 只有聊天记录是秒级时间戳，需要在这里格式化。
 */
internal fun formatChatTime(timestampMillis: Long?, fallback: String?): String? {
    if (timestampMillis != null && timestampMillis > 0) {
        val pattern = if (System.currentTimeMillis() - timestampMillis < DAY) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMillis))
    }
    return fallback?.takeIf { it.isNotBlank() }
}
