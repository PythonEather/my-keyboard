package com.expressive.keyboard

import java.io.File
import java.util.concurrent.TimeUnit

// Plugin Studio, part 1: the pieces with no Android UI in them (patches, saved versions, the basic shell).
// The screen itself is in PluginStudio.kt.

/**
 * A small find/replace patch that is easy to paste on a phone:
 *
 *     @@ find
 *     old text (one or more lines)
 *     @@ replace
 *     new text
 *     @@ end
 *
 *     @@ append
 *     text added at the very end of the file
 *     @@ end
 *
 * Every `find` must match exactly once. All blocks are applied together or not at all.
 */
object PluginPatch {
    class Block(val find: String, val replace: String, val append: Boolean = false)
    class Result(val text: String, val blocks: Int)

    private enum class Mode { NONE, FIND, REPLACE, APPEND }

    fun parse(patch: String): List<Block> {
        val out = ArrayList<Block>()
        var mode = Mode.NONE
        val find = ArrayList<String>()
        val repl = ArrayList<String>()
        val app = ArrayList<String>()
        var n = 0
        for (raw in patch.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val t = raw.trimEnd()
            when {
                t == "@@ find" -> {
                    require(mode == Mode.NONE) { "Patch: \"@@ find\" found before the previous block was closed with \"@@ end\"" }
                    mode = Mode.FIND; find.clear(); repl.clear(); n++
                }
                t == "@@ replace" -> {
                    require(mode == Mode.FIND) { "Patch: \"@@ replace\" must come after \"@@ find\" (block $n)" }
                    mode = Mode.REPLACE
                }
                t == "@@ append" -> {
                    require(mode == Mode.NONE) { "Patch: \"@@ append\" found before the previous block was closed with \"@@ end\"" }
                    mode = Mode.APPEND; app.clear(); n++
                }
                t == "@@ end" -> {
                    when (mode) {
                        Mode.FIND -> throw IllegalArgumentException("Patch: block $n has \"@@ find\" but no \"@@ replace\"")
                        Mode.REPLACE -> {
                            require(find.any { it.isNotBlank() }) { "Patch: block $n has an empty \"@@ find\"" }
                            out += Block(find.joinToString("\n"), repl.joinToString("\n"))
                        }
                        Mode.APPEND -> out += Block("", app.joinToString("\n"), append = true)
                        Mode.NONE -> throw IllegalArgumentException("Patch: \"@@ end\" without an open block")
                    }
                    mode = Mode.NONE
                }
                mode == Mode.FIND -> find += raw.trimEnd('\r')
                mode == Mode.REPLACE -> repl += raw.trimEnd('\r')
                mode == Mode.APPEND -> app += raw.trimEnd('\r')
                // outside a block: blank lines, ``` fences and comments are ignored
            }
        }
        require(mode == Mode.NONE) { "Patch: the last block is not closed with \"@@ end\"" }
        require(out.isNotEmpty()) { "Patch: nothing to apply (expected \"@@ find\" … \"@@ replace\" … \"@@ end\")" }
        return out
    }

    fun apply(source: String, patch: String): Result {
        val blocks = parse(patch)
        var text = source.replace("\r\n", "\n")
        blocks.forEachIndexed { i, b ->
            val label = "block ${i + 1}"
            if (b.append) {
                text = text.trimEnd('\n') + "\n" + b.replace + "\n"
                return@forEachIndexed
            }
            var starts = occurrences(text, b.find)
            var findLen = b.find.length
            if (starts.isEmpty()) {
                // second chance: the same lines, ignoring spaces at the end of each line
                val loose = looseMatches(text, b.find)
                starts = loose.map { it.first }
                if (loose.isNotEmpty()) findLen = -1
                if (loose.size == 1) {
                    val (s, e) = loose[0]
                    text = splice(text, s, e, b.replace)
                    return@forEachIndexed
                }
            }
            when {
                starts.isEmpty() -> throw IllegalArgumentException("Patch $label: the text to find was not found: \"" + b.find.lineSequence().first().trim().take(60) + "\"")
                starts.size > 1 -> throw IllegalArgumentException("Patch $label: the text to find appears ${starts.size} times; add more lines so that it matches once")
                else -> text = splice(text, starts[0], starts[0] + findLen, b.replace)
            }
        }
        return Result(text, blocks.size)
    }

    private fun splice(text: String, start: Int, end: Int, replacement: String): String {
        var e = end
        // deleting whole lines: remove the line break too, so no empty line is left behind
        if (replacement.isEmpty() && e < text.length && text[e] == '\n' && (start == 0 || text[start - 1] == '\n')) e++
        return text.substring(0, start) + replacement + text.substring(e)
    }

    private fun occurrences(text: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val r = ArrayList<Int>()
        var i = text.indexOf(needle)
        while (i >= 0) {
            r += i
            i = text.indexOf(needle, i + 1)
        }
        return r
    }

    /** Matches of [find] as whole lines, comparing lines without their trailing spaces: (start, end) offsets in [text]. */
    private fun looseMatches(text: String, find: String): List<Pair<Int, Int>> {
        val want = find.split('\n').map { it.trimEnd() }
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        for (i in lines.indices) offsets[i + 1] = offsets[i] + lines[i].length + 1
        val res = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + want.size <= lines.size) {
            var ok = true
            for (k in want.indices) if (lines[i + k].trimEnd() != want[k]) { ok = false; break }
            if (ok) res += offsets[i] to (offsets[i + want.size] - 1).coerceAtLeast(offsets[i])
            i++
        }
        return res
    }
}

/** Saved versions of a plugin's source, so that a bad edit can be undone with one tap. */
class PluginSnapshots(private val root: File, private val keep: Int = 20) {
    class Snap(val file: File, val at: Long, val size: Long)

    private fun dir(id: String) = File(root, id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "plugin" })

    /** Saves [text] unless it is the same as the newest version. Returns true if a file was written. */
    fun save(id: String, text: String): Boolean {
        if (text.isBlank()) return false
        val d = dir(id).apply { mkdirs() }
        val newest = list(id).firstOrNull()
        if (newest != null && runCatching { newest.file.readText() }.getOrNull() == text) return false
        var at = System.currentTimeMillis()
        while (File(d, "%013d.txt".format(at)).exists()) at++
        File(d, "%013d.txt".format(at)).writeText(text)
        list(id).drop(keep).forEach { it.file.delete() }
        return true
    }

    /** Newest first. */
    fun list(id: String): List<Snap> =
        dir(id).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".txt").toLongOrNull()?.let { Snap(f, it, f.length()) } }
            .sortedByDescending { it.at }

    fun read(s: Snap): String = s.file.readText()

    fun clear(id: String) { dir(id).deleteRecursively() }
}

/** The basic shell: /system/bin/sh with a time limit. It is only ever started by what the person types in the Studio console. */
class PluginShell(private val home: File) {
    var cwd: File = home
        private set

    class Out(val text: String, val exitCode: Int?, val timedOut: Boolean)

    fun prompt(): String {
        val h = home.canonicalPath
        val c = cwd.canonicalPath
        return (if (c == h) "~" else if (c.startsWith("$h/")) "~/" + c.removePrefix("$h/") else c) + " $"
    }

    /** `cd` is handled here because every command is a new process: the directory is kept by this class. */
    fun cd(arg: String): String? {
        val a = arg.trim().trim('"', '\'')
        val target = when {
            a.isEmpty() || a == "~" -> home
            a.startsWith("~/") -> File(home, a.removePrefix("~/"))
            a.startsWith("/") -> File(a)
            else -> File(cwd, a)
        }
        val canon = runCatching { target.canonicalFile }.getOrNull()
        if (canon == null || !canon.isDirectory) return "cd: no such directory: $a"
        cwd = canon
        return null
    }

    fun run(command: String, timeoutMs: Long = 10_000L, maxChars: Int = 20_000): Out {
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val pb = ProcessBuilder(sh, "-c", command).directory(cwd).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        val p = try { pb.start() } catch (e: Exception) { return Out("cannot start the shell: ${e.message}", null, false) }
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { if (sb.length < maxChars) sb.append(buf, 0, minOf(n, maxChars - sb.length)) }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val done = try { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) {
            p.destroyForcibly()
            runCatching { p.waitFor(1, TimeUnit.SECONDS) }
        }
        reader.join(500)
        val text = synchronized(sb) { sb.toString() }
        val capped = if (text.length >= maxChars) "$text\n… output cut at $maxChars characters" else text
        return Out(capped.trimEnd('\n'), if (done) p.exitValue() else null, !done)
    }
}
