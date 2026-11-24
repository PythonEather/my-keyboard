package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlTexturesTest {
    @Test
    fun aPlainFileNameIsEnough() {
        val s = GlTextures.parse(mapOf("iTex0" to "brick.png"))
        assertEquals(1, s.size)
        assertEquals("iTex0", s[0].name)
        assertEquals("brick.png", s[0].src)
        assertEquals(GlTexSpec.Wrap.CLAMP, s[0].wrap)
        assertFalse(s[0].nearest)
        assertFalse(s[0].mipmap)
    }

    @Test
    fun optionsAreRead() {
        val s = GlTextures.parse(mapOf("iTex0" to mapOf("src" to "./a/b.png", "wrap" to "Repeat", "filter" to "NEAREST", "mipmap" to true)))[0]
        assertEquals("a/b.png", s.src)
        assertEquals(GlTexSpec.Wrap.REPEAT, s.wrap)
        assertTrue(s.nearest)
        assertTrue(s.mipmap)
        assertEquals(0x2700, s.minFilter)
        assertEquals(0x2600, s.magFilter)
        assertEquals(GlTexSpec.Wrap.MIRROR, GlTextures.parse(mapOf("t" to mapOf("src" to "x.png", "wrap" to "mirror")))[0].wrap)
    }

    @Test
    fun filterAndMipmapPickTheMatchingGlConstant() {
        fun min(nearest: Boolean, mip: Boolean) = GlTexSpec("t", "x.png", GlTexSpec.Wrap.CLAMP, nearest, mip).minFilter
        assertEquals(0x2601, min(false, false))
        assertEquals(0x2600, min(true, false))
        assertEquals(0x2703, min(false, true))
        assertEquals(0x2700, min(true, true))
    }

    @Test
    fun badEntriesAreSkippedAndOnlyFourAreKept() {
        val raw = linkedMapOf<String, Any?>(
            "1bad" to "a.png", "iTime" to "a.png", "iPrev" to "a.png", "has space" to "a.png",
            "up" to "../secret.png", "num" to 5,
            "a" to "a.png", "b" to "b.png", "c" to "c.png", "d" to "d.png", "e" to "e.png"
        )
        assertEquals(listOf("a", "b", "c", "d"), GlTextures.parse(raw).map { it.name })
        assertTrue(GlTextures.parse(null).isEmpty())
        assertTrue(GlTextures.parse("brick.png").isEmpty())
        assertTrue(GlTextures.parse(listOf("a.png")).isEmpty())
    }

    @Test
    fun sourcesAreCleaned() {
        assertEquals("a.png", GlTextures.cleanSrc("  ./a.png "))
        assertEquals("dir/a.png", GlTextures.cleanSrc("/dir\\a.png"))
        assertNull(GlTextures.cleanSrc(""))
        assertNull(GlTextures.cleanSrc("a/../b.png"))
        assertNull(GlTextures.cleanSrc("x".repeat(201)))
        assertEquals("data:image/png;base64,AAAA", GlTextures.cleanSrc("data:image/png;base64,AAAA"))
        assertNull(GlTextures.cleanSrc("data:image/png"))
    }

    @Test
    fun sameFileWithOtherParametersIsAnotherTexture() {
        val a = GlTexSpec("t", "x.png")
        assertEquals(a.key, GlTexSpec("other", "x.png").key)
        assertNotEquals(a.key, GlTexSpec("t", "x.png", GlTexSpec.Wrap.REPEAT).key)
        assertNotEquals(a.key, GlTexSpec("t", "x.png", nearest = true).key)
        assertNotEquals(a.key, GlTexSpec("t", "x.png", mipmap = true).key)
        assertNotEquals(a.key, GlTexSpec("t", "y.png").key)
    }

    @Test
    fun powerOfTwoHelpers() {
        assertTrue(GlTextures.isPot(1) && GlTextures.isPot(256) && GlTextures.isPot(2048))
        assertFalse(GlTextures.isPot(0) || GlTextures.isPot(3) || GlTextures.isPot(-4) || GlTextures.isPot(1000))
        assertEquals(1, GlTextures.nextPot(1))
        assertEquals(256, GlTextures.nextPot(129))
        assertEquals(256, GlTextures.nextPot(256))
    }

    @Test
    fun smallImagesKeepTheirSizeWhenNothingForcesPowersOfTwo() {
        assertEquals(100 to 60, GlTextures.targetSize(100, 60, 2048, false))
        assertEquals(1 to 1, GlTextures.targetSize(0, -3, 2048, false))
    }

    @Test
    fun bigImagesAreScaledDownKeepingTheirShape() {
        assertEquals(2048 to 1024, GlTextures.targetSize(4096, 2048, 2048, false))
        assertEquals(1024 to 2048, GlTextures.targetSize(4096, 8192, 2048, false))
    }

    @Test
    fun openGlEs2NeedsPowersOfTwoForRepeatAndMipmaps() {
        assertEquals(128 to 64, GlTextures.targetSize(100, 60, 2048, true))
        assertEquals(2048 to 2048, GlTextures.targetSize(2048, 2048, 2048, true))
        // never above the limit, even when rounding up would go over it
        assertEquals(1024 to 1024, GlTextures.targetSize(1500, 1500, 1500, true))
        assertEquals(1024 to 1024, GlTextures.targetSize(5000, 2500, 1500, true))
    }

    @Test
    fun aBlankSourceIsAnEmptySlotNotAMistake() {
        val s = GlTextures.parse(linkedMapOf<String, Any?>("a" to "", "b" to "  ", "c" to mapOf("src" to "")))
        assertEquals(listOf("a", "b", "c"), s.map { it.name })
        assertTrue(s.all { it.isEmpty })
        assertFalse(GlTexSpec("t", "x.png").isEmpty)
    }

    @Test
    fun userPictureSourcesPointAtOneSetting() {
        assertEquals("photo", GlTextures.userOption(GlTextures.userValue("photo", 123L)))
        assertEquals("my.pic-1", GlTextures.userOption("user:my.pic-1:99"))
        assertNull(GlTextures.userOption("brick.png"))
        assertNull(GlTextures.userOption("user:nostamp"))
        assertNull(GlTextures.userOption("user::5"))
        assertNull(GlTextures.userOption("user:../x:5"))
        assertNull(GlTextures.userOption("user:" + "a".repeat(41) + ":5"))
    }

    @Test
    fun aUserPictureSourceSurvivesParsingAndEachNewPictureIsANewTexture() {
        val a = GlTextures.parse(mapOf("iTex0" to GlTextures.userValue("photo", 1L)))[0]
        val b = GlTextures.parse(mapOf("iTex0" to GlTextures.userValue("photo", 2L)))[0]
        assertEquals("user:photo:1", a.src)
        assertFalse(a.isEmpty)
        assertNotEquals(a.key, b.key)
    }
}
