package com.expressive.keyboard

import kotlin.math.abs

/** Decides what an edge swipe does to the one-hand mode. Pure logic, no Android. */
internal object EdgeSwipeLogic {
    /**
     * [startX] and [width] are in pixels of the keyboard view. Returns the new one-hand mode ("LEFT", "RIGHT" or "OFF"),
     * or null when this swipe is not an edge swipe (or changes nothing).
     * [exitFromOpposite]: from the opposite edge the keyboard goes back to full width instead of jumping to that side.
     */
    fun resolve(
        startX: Float, width: Float, zonePx: Float, dx: Float, dy: Float, distancePx: Float,
        current: String, exitFromOpposite: Boolean
    ): String? {
        val fromLeft = startX <= zonePx
        val fromRight = startX >= width - zonePx
        if (!fromLeft && !fromRight) return null
        if (abs(dx) < distancePx || abs(dy) > abs(dx) * 0.6f) return null
        val inward = if (fromLeft) dx > 0f else dx < 0f
        if (!inward) return null
        val side = if (fromLeft) "LEFT" else "RIGHT"
        return when {
            current != "LEFT" && current != "RIGHT" -> side
            current == side -> null
            exitFromOpposite -> "OFF"
            else -> side
        }
    }
}
