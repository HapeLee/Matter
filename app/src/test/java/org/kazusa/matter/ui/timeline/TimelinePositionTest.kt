package org.kazusa.matter.ui.timeline

import org.kazusa.matter.data.model.WeiboStatusDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePositionTest {
    private val posts = listOf("a", "b", "c").map { WeiboStatusDto(idStr = it) }
    @Test fun dividerBetweenPostsCountsAsAnItem() {
        // 当断点在第 0 项 ("a") 时为无效断点（上方无新内容，不展示分割线），故不产生偏移
        assertEquals(0, resolveTimelineRestoreIndex(posts, "a", "a", 0))
        // 当断点在第 1 项 ("b") 且定位到断点及之后项时，前方存在一条分割线，偏移 +1
        assertEquals(2, resolveTimelineRestoreIndex(posts, "b", "b", 0))
        assertEquals(3, resolveTimelineRestoreIndex(posts, "b", "c", 0))
        // 当断点在第 1 项 ("b") 但定位到断点上方未读项 ("a") 时，目标项在分割线上方，不产生偏移
        assertEquals(0, resolveTimelineRestoreIndex(posts, "b", "a", 0))
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
