package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLinkTest {
    @Test
    fun githubPageLinksBecomeRawLinks() {
        assertEquals(
            "https://raw.githubusercontent.com/me/repo/main/plugins/a.js",
            PluginLink.normalize("  https://github.com/me/repo/blob/main/plugins/a.js?plain=1#L3 ")
        )
        assertEquals("https://raw.githubusercontent.com/me/repo/main/a.js", PluginLink.normalize("https://raw.githubusercontent.com/me/repo/main/a.js"))
    }

    @Test
    fun onlyHttpsIsAccepted() {
        for (bad in listOf("http://example.com/a.js", "file:///sdcard/a.js", "ftp://x/a", "a.js", "")) {
            val r = runCatching { PluginLink.normalize(bad) }
            assertTrue("$bad should be refused", r.isFailure)
        }
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(PluginLink.isNewer("1.10", "1.9"))
        assertTrue(PluginLink.isNewer("2.0", "1.99.9"))
        assertFalse(PluginLink.isNewer("1.0", "1.0"))
        assertFalse(PluginLink.isNewer("1.0.0", "1.0"))
        assertFalse(PluginLink.isNewer("0.9", "1.0"))
        assertTrue(PluginLink.isNewer("beta-2", "beta"))
        assertTrue(PluginLink.isNewer("rc", "alpha"))
        assertFalse(PluginLink.isNewer("rc", "rc"))
    }
}
