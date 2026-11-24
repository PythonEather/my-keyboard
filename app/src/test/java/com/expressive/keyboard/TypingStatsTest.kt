package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingStatsTest {
    @Test
    fun speedIsCountedOverTheWindow() {
        val t = TypingStats(windowMs = 2000L)
        var s = t.press("letter", 1000L)
        for (i in 1 until 10) s = t.press("letter", 1000L + i * 200L)
        // 10 нажатий за 2 с
        assertEquals(5f, s.cps, 0.01f)
        assertEquals(0f, s.backspaceCps, 0.001f)
    }

    @Test
    fun oldPressesLeaveTheWindow() {
        val t = TypingStats(windowMs = 2000L)
        t.press("letter", 0L)
        t.press("letter", 100L)
        val s = t.press("letter", 10_000L)
        assertEquals(0.5f, s.cps, 0.01f)
    }

    @Test
    fun backspaceIsCountedSeparately() {
        val t = TypingStats()
        t.press("letter", 0L)
        t.press("backspace", 100L)
        val s = t.press("backspace", 200L)
        assertEquals(1f, s.backspaceCps, 0.01f)
        assertEquals(0.5f, s.cps, 0.01f)
    }

    @Test
    fun burstResetsAfterAPause() {
        val t = TypingStats()
        t.press("letter", 1000L)
        t.press("letter", 1200L)
        val third = t.press("letter", 1400L)
        assertEquals(3, third.burst)
        val after = t.press("letter", 3000L)
        assertEquals(1, after.burst)
        assertEquals(1600L, after.gapMs)
    }

    @Test
    fun shiftDoesNotCountAsTyping() {
        val t = TypingStats()
        val s = t.press("shift", 100L)
        assertTrue(s.cps == 0f)
    }
}
