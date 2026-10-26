package org.kazusa.matter.ui.message

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTimeFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定「现在」：2026-10-26 10:30，避免测试依赖运行日期。 */
    private val now = millis(2026, 10, 26, 10, 30)

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    private fun format(timestamp: Long?, fallback: String? = null): String? =
        formatChatTime(timestamp, fallback, nowMillis = now, zoneId = zone)

    @Test
    fun todayShowsClockOnly() {
        assertEquals("09:05", format(millis(2026, 10, 26, 9, 5)))
        assertEquals("00:01", format(millis(2026, 10, 26, 0, 1)))
    }

    @Test
    fun yesterdayKeepsDateWordEvenWithin24Hours() {
        // 昨晚 23:50 距现在不到 24 小时，但跨天了，必须带「昨天」而不是裸 HH:mm
        assertEquals("昨天 23:50", format(millis(2026, 10, 25, 23, 50)))
    }

    @Test
    fun earlierThisYearShowsMonthAndDay() {
        assertEquals("3月8日 18:20", format(millis(2026, 3, 8, 18, 20)))
    }

    @Test
    fun previousYearsShowFullDate() {
        assertEquals("2025年12月31日 08:00", format(millis(2025, 12, 31, 8, 0)))
    }

    @Test
    fun futureTimestampDegradesToClock() {
        assertEquals("11:00", format(millis(2026, 10, 26, 11, 0)))
    }

    @Test
    fun missingTimestampFallsBackToServerText() {
        assertEquals("12-25", format(null, "12-25"))
        assertEquals("刚刚", format(0L, "刚刚"))
        assertNull(format(null, "   "))
        assertNull(format(null, null))
    }
}
