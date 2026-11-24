package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    private val g = 9.80665f

    @Test
    fun restingPhoneNeverShakes() {
        val d = ShakeDetector()
        for (i in 0 until 500) assertNull(d.feed(0.1f, 0.2f, g, i * 10L))
    }

    @Test
    fun slowTiltNeverShakes() {
        val d = ShakeDetector()
        for (i in 0 until 500) assertNull(d.feed(g * 0.9f, 0f, g * 0.4f, i * 10L))
    }

    @Test
    fun threeHardJoltsFireOnce() {
        val d = ShakeDetector()
        var fired = 0
        var strength = 0f
        // три рывка по 3 g с паузой 150 мс
        for (k in 0 until 3) {
            val t = k * 150L
            d.feed(0f, 0f, g * 3f, t)?.let { fired++; strength = it }
            d.feed(0f, 0f, g, t + 40L)
        }
        assertEquals(1, fired)
        assertTrue(strength >= 2.9f)
    }

    @Test
    fun twoJoltsAreNotEnough() {
        val d = ShakeDetector()
        assertNull(d.feed(0f, 0f, g * 3f, 0L))
        assertNull(d.feed(0f, 0f, g * 3f, 200L))
    }

    @Test
    fun oneBurstCountsAsOneJolt() {
        val d = ShakeDetector()
        // десять отсчётов подряд с шагом 10 мс — это один рывок
        for (i in 0 until 10) assertNull(d.feed(0f, 0f, g * 3f, i * 10L))
    }

    @Test
    fun slowJoltsOutsideTheWindowDoNotAdd() {
        val d = ShakeDetector()
        assertNull(d.feed(0f, 0f, g * 3f, 0L))
        assertNull(d.feed(0f, 0f, g * 3f, 500L))
        // третий уже за окном первого (900 мс)
        assertNull(d.feed(0f, 0f, g * 3f, 1100L))
    }

    @Test
    fun cooldownBlocksImmediateRepeat() {
        val d = ShakeDetector()
        var fired = 0
        for (k in 0 until 6) d.feed(0f, 0f, g * 3f, k * 150L)?.let { fired++ }
        assertEquals(1, fired)
        // после паузы встряхивание снова работает
        var again = 0
        for (k in 0 until 3) d.feed(0f, 0f, g * 3f, 3000L + k * 150L)?.let { again++ }
        assertNotNull(again)
        assertEquals(1, again)
    }
}
