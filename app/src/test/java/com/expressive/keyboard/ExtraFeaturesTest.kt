package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExtraFeaturesTest {
    @Test
    fun sunTimesForCotabatoAreAboutRight() {
        // 27 November, UTC+8: sunrise near 5:40, sunset near 17:25
        val d = SunTimes.compute(7.2, 124.2, 331, 480)!!
        assertTrue(d.sunrise in 315..350)
        assertTrue(d.sunset in 1020..1055)
        assertNull(SunTimes.compute(80.0, 10.0, 355, 60))
    }

    @Test
    fun latLonParsing() {
        assertEquals(7.2 to 124.2, SunTimes.parseLatLon("7.2, 124.2"))
        assertEquals(7.2 to 124.2, SunTimes.parseLatLon("7.2 124.2"))
        assertNull(SunTimes.parseLatLon("abc"))
        assertNull(SunTimes.parseLatLon("95, 0"))
    }

    @Test
    fun nightWindowWrapsPastMidnight() {
        assertTrue(SunTimes.inNight(1400, 1080, 360))
        assertTrue(SunTimes.inNight(100, 1080, 360))
        assertFalse(SunTimes.inNight(720, 1080, 360))
        assertTrue(SunTimes.inNight(60, 0, 120))
    }

    @Test
    fun appRuleBeatsDayNightAndBaseStopsBoth() {
        val known = setOf("Neon", "Plain")
        assertEquals("Neon", AutoLookRules.pick("tg", true, mapOf("tg" to "Neon"), true, false, "Plain", "Plain", known))
        assertEquals("", AutoLookRules.pick("br", true, mapOf("br" to AutoLookRules.BASE), true, true, "Plain", "Neon", known))
        assertEquals("Neon", AutoLookRules.pick("x", true, mapOf(), true, true, "Plain", "Neon", known))
        assertEquals("", AutoLookRules.pick("x", true, mapOf("x" to "Gone"), false, false, "", "", known))
        assertEquals("", AutoLookRules.pick("tg", false, mapOf("tg" to "Neon"), false, true, "", "Neon", known))
    }

    @Test
    fun edgeSwipeRules() {
        val w = 1080f
        assertEquals("LEFT", EdgeSwipeLogic.resolve(10f, w, 50f, 250f, 20f, 180f, "OFF", true))
        assertEquals("RIGHT", EdgeSwipeLogic.resolve(1070f, w, 50f, -250f, 10f, 180f, "OFF", true))
        assertNull(EdgeSwipeLogic.resolve(500f, w, 50f, 250f, 0f, 180f, "OFF", true))
        assertNull(EdgeSwipeLogic.resolve(10f, w, 50f, 100f, 0f, 180f, "OFF", true))
        assertNull(EdgeSwipeLogic.resolve(10f, w, 50f, 250f, 200f, 180f, "OFF", true))
        assertNull(EdgeSwipeLogic.resolve(10f, w, 50f, -250f, 0f, 180f, "OFF", true))
        assertNull(EdgeSwipeLogic.resolve(10f, w, 50f, 250f, 0f, 180f, "LEFT", true))
        assertEquals("OFF", EdgeSwipeLogic.resolve(1070f, w, 50f, -250f, 0f, 180f, "LEFT", true))
        assertEquals("RIGHT", EdgeSwipeLogic.resolve(1070f, w, 50f, -250f, 0f, 180f, "LEFT", false))
    }

    @Test
    fun keyPitchIsStableAndSpread() {
        assertEquals(KeyPitch.offset("a"), KeyPitch.offset("a"), 0f)
        assertEquals(0f, KeyPitch.offset(""), 0f)
        val all = ('a'..'z').map { KeyPitch.offset(it.toString()) }
        assertTrue(all.min() < -0.5f && all.max() > 0.5f)
        // the old plus or minus 3% when the pitch is off and the wobble is at its default
        assertEquals(1.03f, KeyPitch.rate("a", 0f, 0.5f, 1f), 0.0001f)
        assertEquals(0.97f, KeyPitch.rate("a", 0f, 0.5f, 0f), 0.0001f)
    }

    @Test
    fun typingGlowRisesFastAndCalmsSlowly() {
        val g = TypingGlowModel()
        for (i in 0 until 12) g.press(1000L + i * 100L)
        assertEquals(8f, g.cps(2200L), 0.01f)
        assertEquals(1f, g.target(2200L, 7f), 0f)
        assertEquals(0f, g.cps(9000L), 0f)
        var h = 0f
        repeat(30) { h = TypingGlowModel.step(h, 1f, 0.016f, 2f) }
        assertTrue(h > 0.9f)
        repeat(60) { h = TypingGlowModel.step(h, 0f, 0.016f, 2f) }
        assertTrue(h in 0.2f..0.8f)
        repeat(600) { h = TypingGlowModel.step(h, 0f, 0.016f, 2f) }
        assertTrue(h < 0.02f)
    }

    @Test
    fun typingHistoryCountsSpeedWordsAndStreaks() {
        val t = TypingHistoryData()
        var now = 10_000L
        repeat(80) { t.press(100, now); now += 200L }
        assertEquals(80, t.keysOn(100))
        assertTrue(abs(t.averageWpm(100, 7) - 60f) < 3f)
        assertTrue(abs(t.bestWpm() - 60f) < 3f)
        t.press(100, now + 10_000L)
        // a long pause is not typing time
        assertEquals(15_800L, t.days[100]!!.activeMs)
        t.word(100, "Hello"); t.word(100, "hello"); t.word(100, "x"); t.word(100, "abc123"); t.word(100, "don't")
        assertEquals(listOf("hello" to 2, "don't" to 1), t.top(5))
        t.press(99, 1L); t.press(98, 1L); t.press(96, 1L)
        assertEquals(3, t.streak(100))
        assertEquals(3, t.streak(101))
        assertEquals(0, t.streak(103))
        assertEquals(3, t.longestStreak())
        t.clear()
        assertEquals(0, t.totalKeys())
        assertTrue(t.words.isEmpty())
    }

    @Test
    fun wordListStaysSmall() {
        val big = TypingHistoryData()
        for (i in 0 until 3500) big.word(1, "wx" + i.toString().map { 'a' + it.digitToInt() }.joinToString("") + "q")
        assertTrue(big.words.size <= TypingHistoryData.MAX_WORDS)
        assertNotNull(big.top(1).firstOrNull())
    }

    @Test
    fun newSoundsAreRegistered() {
        assertTrue(KeySoundStyle.TYPEWRITER in KeySoundStyle.synthesized)
        assertTrue(KeySoundStyle.CLICKY in KeySoundStyle.synthesized)
    }
}
