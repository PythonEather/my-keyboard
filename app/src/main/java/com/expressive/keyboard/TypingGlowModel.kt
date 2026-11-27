package com.expressive.keyboard

import kotlin.math.exp

/** How hot the typing glow is: from presses in the last 1.5 seconds, smoothed so it rises fast and calms down slowly. */
internal class TypingGlowModel {
    private val stamps = ArrayDeque<Long>()

    fun press(nowMs: Long) {
        stamps.addLast(nowMs)
        trim(nowMs)
    }

    fun cps(nowMs: Long): Float {
        trim(nowMs)
        return stamps.size / (WINDOW_MS / 1000f)
    }

    fun target(nowMs: Long, fullCps: Float): Float = (cps(nowMs) / fullCps.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun reset() = stamps.clear()

    private fun trim(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() > WINDOW_MS) stamps.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 1500L

        /** One frame: [dtSec] seconds passed. [calmSec] is about how long the glow takes to fade after you stop. */
        fun step(heat: Float, target: Float, dtSec: Float, calmSec: Float): Float {
            val k = if (target > heat) 1f - exp(-dtSec / 0.12f) else 1f - exp(-dtSec * 3f / calmSec.coerceAtLeast(0.2f))
            return heat + (target - heat) * k
        }
    }
}
