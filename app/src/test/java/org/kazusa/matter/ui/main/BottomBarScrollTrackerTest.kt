package org.kazusa.matter.ui.main

import org.junit.Assert.*
import org.junit.Test

class BottomBarScrollTrackerTest {
    @Test fun smallVerticalInputsAccumulateWithoutFlickering() {
        val tracker = BottomBarScrollTracker(24f)
        assertNull(tracker.onScroll(-8f))
        assertNull(tracker.onScroll(-8f))
        assertEquals(false, tracker.onScroll(-8f))
    }
    @Test fun horizontalMovementAndDirectionChangesDoNotCarryOldDistance() {
        val tracker = BottomBarScrollTracker(24f)
        tracker.onScroll(-20f)
        assertNull(tracker.onScroll(0f))
        assertNull(tracker.onScroll(8f))
        assertEquals(true, tracker.onScroll(16f))
    }
    @Test fun returningToThePageStartsAnew() {
        val tracker = BottomBarScrollTracker(24f)
        tracker.onScroll(-20f)
        tracker.reset()
        assertNull(tracker.onScroll(-8f))
        assertEquals(false, tracker.onScroll(-16f))
    }
}
