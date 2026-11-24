package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun apk(name: String, size: Long = 100) = AssetRow(name, "https://example.com/$name", size)
    private fun rel(tag: String, vararg assets: AssetRow, draft: Boolean = false, notes: String = "") =
        ReleaseRow(tag, draft, notes, "https://example.com/r/$tag", assets.toList())

    @Test
    fun versionsAreComparedNumberByNumber() {
        assertTrue(UpdateLogic.newer("11.2.15", "11.2.14"))
        assertTrue(UpdateLogic.newer("11.10.0", "11.9.9"))
        assertTrue(UpdateLogic.newer("v12", "11.99.99"))
        assertTrue(UpdateLogic.newer("11.2.14.1", "11.2.14"))
        assertFalse(UpdateLogic.newer("11.2.14", "11.2.14"))
        assertFalse(UpdateLogic.newer("11.2.13", "11.2.14"))
        assertFalse(UpdateLogic.newer("11.2.14", "11.2.14.0"))
    }

    @Test
    fun labelsAroundTheNumberAreIgnored() {
        assertEquals("11.2.15", UpdateLogic.versionOf("v11.2.15"))
        assertEquals("11.2.15", UpdateLogic.versionOf("11.2.15-beta"))
        assertEquals("11.2.15", UpdateLogic.versionOf("Expressive Keyboard 11.2.15"))
        assertEquals("0", UpdateLogic.versionOf("nightly"))
        assertTrue(UpdateLogic.newer("v11.2.15-beta", "11.2.14-debug"))
    }

    @Test
    fun debugAndUnsignedApksAreNeverOffered() {
        assertNull(UpdateLogic.apkOf(listOf(apk("app-debug.apk"), apk("app-release-unsigned.apk"), apk("notes.txt"), apk("source.zip"))))
        assertEquals("app-release.apk", UpdateLogic.apkOf(listOf(apk("app-debug.apk"), apk("app-release.apk")))?.name)
        assertEquals("ExpressiveKeyboard-11.2.15.apk", UpdateLogic.apkOf(listOf(apk("app-release.apk"), apk("ExpressiveKeyboard-11.2.15.apk")))?.name)
        assertEquals("X.APK", UpdateLogic.apkOf(listOf(apk("X.APK")))?.name)
    }

    @Test
    fun theNewestReleaseWithAnApkWinsWhateverTheOrder() {
        val list = listOf(
            rel("v11.2.13", apk("ExpressiveKeyboard-11.2.13.apk")),
            rel("v11.2.15", apk("ExpressiveKeyboard-11.2.15.apk"), notes = "  Textures in GL layers  "),
            rel("v11.2.14", apk("ExpressiveKeyboard-11.2.14.apk"))
        )
        val got = UpdateLogic.pick(list)!!
        assertEquals("11.2.15", got.version)
        assertEquals("Textures in GL layers", got.notes)
        assertEquals("https://example.com/ExpressiveKeyboard-11.2.15.apk", got.apkUrl)
        assertEquals("https://example.com/r/v11.2.15", got.page)
    }

    @Test
    fun draftsAndReleasesWithoutApkAreSkipped() {
        val list = listOf(
            rel("v11.3.0", apk("ExpressiveKeyboard-11.3.0.apk"), draft = true),
            rel("v11.2.16"),
            rel("v11.2.15", apk("app-debug.apk")),
            rel("v11.2.14", apk("ExpressiveKeyboard-11.2.14.apk"))
        )
        assertEquals("11.2.14", UpdateLogic.pick(list)!!.version)
        assertNull(UpdateLogic.pick(emptyList()))
        assertNull(UpdateLogic.pick(listOf(rel("v1.0.0"))))
    }

    @Test
    fun aPreReleaseWithAnApkIsOfferedToo() {
        // the GitHub "latest" shortcut hides pre-releases; the list does not
        val got = UpdateLogic.pick(listOf(rel("v11.2.15-beta", apk("ExpressiveKeyboard-11.2.15.apk"))))
        assertEquals("11.2.15", got!!.version)
    }
}
