package org.kazusa.matter.ui.message

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 聊天气泡里的时间文案。
 *
 * 规则按「今天 / 昨天 / 今年 / 更早」分档，而不是按「距今是否满 24 小时」：
 * 后者会把昨晚 23:50 显示成 `00:10` 这种看不出日期的 `HH:mm`。
 *
 * 消息页的会话 / 提醒列表直接使用接口下发的时间文案（「刚刚」「10分钟前」「12-25」），
 * 只有聊天记录是秒级时间戳，需要在这里格式化。
 */

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault())
private val FULL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.getDefault())

/**
 * @param timestampMillis 消息时间（毫秒）；无效时退回接口下发的 [fallback] 文案。
 * @param nowMillis 当前时间，测试可注入固定值。
 * @param zoneId 时区，测试可注入固定值。
 */
internal fun formatChatTime(
    timestampMillis: Long?,
    fallback: String?,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String? {
    if (timestampMillis == null || timestampMillis <= 0L) {
        return fallback?.takeIf { it.isNotBlank() }
    }

    val time = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val clock = time.format(CLOCK_FORMAT)

    return when {
        time.toLocalDate() == now.toLocalDate() -> clock
        time.toLocalDate() == now.toLocalDate().minusDays(1) -> "昨天 $clock"
        time.year == now.year -> time.format(MONTH_DAY_FORMAT)
        else -> time.format(FULL_DATE_FORMAT)
    }
}
