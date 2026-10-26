package org.kazusa.matter.core.network

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天接口的时间字段解析。
 *
 * 私信相关的同一批接口里 `created_at` 至少有四种形态，必须都认，否则会退化成一串原始文案：
 * - 秒级 / 毫秒级数字时间戳（也可能是字符串形式的数字）；
 * - `java.util.Date.toString()` 形态，如 `Mon Oct 26 20:14:45 +0800 2026` —— webim 私信接口就是这种；
 * - `yyyy-MM-dd HH:mm:ss`；
 * - ISO-8601（`2026-10-26T20:14:45+08:00` / 带 `Z`）。
 *
 * 解析不出来返回 null，由调用方决定退回原始文案还是干脆不显示。
 */
internal fun parseChatTimestampMillis(raw: String?): Long? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { value ->
        return when {
            value >= 1_000_000_000_000L -> value
            value >= 1_000_000_000L -> value * 1000
            else -> null
        }
    }
    return parseDateToString(text)
        ?: parseLocalDateTime(text)
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

/** `Date.toString()`：`EEE MMM d HH:mm:ss Z yyyy`，月份 / 星期是英文缩写，固定用 [Locale.US]。 */
private fun parseDateToString(text: String): Long? = runCatching {
    OffsetDateTime.parse(text, DATE_TO_STRING).toInstant().toEpochMilli()
}.getOrNull()

/** 不带时区的本地时间按系统时区解释（接口给的都是北京时间）。 */
private fun parseLocalDateTime(text: String): Long? = runCatching {
    LocalDateTime.parse(text, LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

private val DATE_TO_STRING = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss Z yyyy", Locale.US)
private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
