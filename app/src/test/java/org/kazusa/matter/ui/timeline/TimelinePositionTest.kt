package org.kazusa.matter.ui.timeline

import org.kazusa.matter.data.model.WeiboStatusDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePositionTest {
    private val posts = listOf("a", "b", "c").map { WeiboStatusDto(idStr = it) }
    @Test fun dividerBeforeTheFirstPostCountsAsAnItem() {
        assertEquals(1, resolveTimelineRestoreIndex(posts, "a", "a", 0))
        assertEquals(3, resolveTimelineRestoreIndex(posts, "a", "c", 0))
    }
    @Test fun idTakesPriorityOverAnObsoleteIndex() {
        assertEquals(1, resolveTimelineRestoreIndex(posts, "c", "b", 10))
        assertEquals(2, resolveTimelineRestoreIndex(posts, null, "c", 0))
    }
    @Test fun fallbackIncludesTheLastPostAfterADivider() {
        assertEquals(3, resolveTimelineRestoreIndex(posts, "b", "missing", 99))
        assertEquals(0, resolveTimelineRestoreIndex(emptyList(), null, null, 99))
    }
}
