package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlQualityTest {
    @Test
    fun slowFramesStepResolutionDownAndFastFramesStepItBackUp() {
        val g = GlGovernor()
        repeat(40) { g.frame(30.0, 33.0, 0.4f) }
        assertTrue("stepped down: ${g.factor}", g.factor < 1f)
        val low = g.factor
        repeat(400) { g.frame(30.0, 33.0, 0.4f) }
        assertTrue("never below the minimum: ${g.factor}", g.factor >= 0.4f && g.factor <= low)
        repeat(2000) { g.frame(4.0, 33.0, 0.4f) }
        assertEquals(1f, g.factor, 0.0001f)
    }

    @Test
    fun comfortableFramesChangeNothing() {
        val g = GlGovernor()
        repeat(500) { g.frame(20.0, 33.0, 0.4f) }
        assertEquals(1f, g.factor, 0.0001f)
    }

    @Test
    fun minimumOfOneLocksTheResolution() {
        val g = GlGovernor()
        repeat(1000) { g.frame(60.0, 33.0, 1f) }
        assertEquals(1f, g.factor, 0f)
    }

    @Test
    fun theFactorOnlyTakesStepsFromTheLadder() {
        val g = GlGovernor()
        val seen = HashSet<Float>()
        repeat(20_000) { i ->
            g.frame(if ((i / 700) % 2 == 0) 40.0 else 5.0, 33.0, 0.3f)
            seen.add(g.factor)
        }
        assertTrue("distinct factors: $seen", seen.size <= 9)
        assertTrue(seen.all { it >= 0.3f && it <= 1f })
    }

    @Test
    fun aStepUpThatIsUndoneAtOnceMakesTheNextOneWaitLonger() {
        val g = GlGovernor()
        var ups = 0
        var prev = g.factor
        repeat(5000) {
            // full resolution is too slow, one step lower is comfortably fast: it would flip back and forth
            g.frame(if (g.factor >= 0.99f) 30.0 else 15.0, 33.0, 0.4f)
            if (g.factor > prev + 0.0001f) ups++
            prev = g.factor
        }
        assertTrue("steps up: $ups", ups <= 10)
    }

    @Test
    fun heatLowersFpsAndResolutionInSteps() {
        assertEquals(60, GlThermal.capFor(0))
        assertEquals(30, GlThermal.capFor(2))
        assertEquals(20, GlThermal.capFor(3))
        assertEquals(12, GlThermal.capFor(5))
        assertTrue(GlThermal.factorFor(3) < GlThermal.factorFor(2))
        assertEquals(1f, GlThermal.factorFor(1), 0f)
    }

    @Test
    fun programCacheRoundTripsAndPrunes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "glcache-test-" + System.nanoTime())
        val key = GlProgramCache.key("drv", "vert", "frag")
        assertEquals(40, key.length)
        assertTrue(key != GlProgramCache.key("drv2", "vert", "frag"))
        assertNull(GlProgramCache.load(dir, key))
        GlProgramCache.store(dir, key, 0x1234, ByteArray(100) { it.toByte() })
        val hit = GlProgramCache.load(dir, key)
        assertNotNull(hit)
        assertEquals(0x1234, hit!!.first)
        assertEquals(100, hit.second.size)
        assertEquals(7.toByte(), hit.second[7])
        repeat(60) { GlProgramCache.store(dir, GlProgramCache.key("d", "v$it", "f"), 1, ByteArray(16)) }
        assertTrue((File(dir, "glbin").listFiles()?.size ?: 0) <= 48)
        dir.deleteRecursively()
    }
}
