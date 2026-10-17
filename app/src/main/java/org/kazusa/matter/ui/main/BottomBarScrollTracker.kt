package org.kazusa.matter.ui.main

/** Accumulate actual vertical input, resetting on reversal rather than reacting to list layout. */
internal class BottomBarScrollTracker(private val threshold: Float) {
    private var distance = 0f

    fun reset() { distance = 0f }

    fun onScroll(deltaY: Float): Boolean? {
        if (deltaY == 0f) return null
        if (distance * deltaY < 0f) distance = 0f
        distance += deltaY
        if (kotlin.math.abs(distance) < threshold) return null
        val visible = distance > 0f
        distance = 0f
        return visible
    }
}
