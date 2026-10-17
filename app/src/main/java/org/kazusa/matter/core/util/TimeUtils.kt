package org.kazusa.matter.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtils {

    /** 时间戳 -> 时钟串（如 15:42），供「更新于 15:42」这类展示用。 */
    fun formatClockTime(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /**
     * 时间戳 -> 友好相对时间（浏览记录等本地数据展示用）。
     * 复用 [formatWeiboTime] 的「刚刚 / X分钟前 / 今天 / 昨天 / MM-dd」规则。
     */
    fun formatRelativeTime(millis: Long): String {
        if (millis <= 0L) return ""
        return formatWeiboTime(standardDateFormat.format(Date(millis)))
    }

    // 微博接口常见时间格式：EEE MMM dd HH:mm:ss Z yyyy (例如: "Wed Oct 14 00:33:45 +0800 2026")
    private val weiboDateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)
    private val standardDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 将微博返回的 created_at 转换为易读的友好时间格式：
     * - 1分钟内: 刚刚
     * - 1小时内: X分钟前
     * - 今天内: 今天 HH:mm (或 X小时前)
     * - 昨天: 昨天 HH:mm
     * - 同一年: MM-dd HH:mm
     * - 往年: yyyy-MM-dd
     */
    fun formatWeiboTime(rawTime: String?): String {
        if (rawTime.isNullOrBlank()) return ""
        val trimmed = rawTime.trim()

        // 如果已经是相对时间（如 "刚刚"、"5分钟前"、"昨天 12:30"），直接返回
        if (trimmed.contains("刚刚") || trimmed.contains("前") || trimmed.contains("昨天") || trimmed.contains("前天")) {
            return trimmed
        }

        val date: Date = try {
            weiboDateFormat.parse(trimmed)
        } catch (_: Exception) {
            try {
                standardDateFormat.parse(trimmed)
            } catch (_: Exception) {
                null
            }
        } ?: return trimmed

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val diffMs = now.timeInMillis - target.timeInMillis
        val diffSeconds = diffMs / 1000
        val diffMinutes = diffSeconds / 60
        val diffHours = diffMinutes / 60

        val nowYear = now.get(Calendar.YEAR)
        val targetYear = target.get(Calendar.YEAR)
        val nowDayOfYear = now.get(Calendar.DAY_OF_YEAR)
        val targetDayOfYear = target.get(Calendar.DAY_OF_YEAR)

        val isSameYear = nowYear == targetYear
        val isSameDay = isSameYear && nowDayOfYear == targetDayOfYear
        val isYesterday = (isSameYear && nowDayOfYear - targetDayOfYear == 1) ||
                (nowYear - targetYear == 1 && nowDayOfYear == 1 && targetDayOfYear >= 365)

        val hourMinute = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)

        return when {
            diffSeconds in 0..59 -> "刚刚"
            diffMinutes in 1..59 -> "${diffMinutes}分钟前"
            isSameDay -> {
                if (diffHours in 1..11) {
                    "${diffHours}小时前"
                } else {
                    "今天 $hourMinute"
                }
            }
            isYesterday -> "昨天 $hourMinute"
            isSameYear -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        }
    }
}
