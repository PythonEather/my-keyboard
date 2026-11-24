package com.expressive.keyboard

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Installing a plugin from a link: address clean-up, version comparison and the download itself. No Android classes, unit-tested. */
object PluginLink {
    private val GITHUB_BLOB = Regex("^https://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$")

    /** Trims the link, turns a github.com/…/blob/… page address into the raw file address and refuses anything that is not https. */
    fun normalize(raw: String): String {
        val s = raw.trim().replace("\\s".toRegex(), "")
        require(s.startsWith("https://")) { "Only https:// links are accepted" }
        require(s.length <= 2000) { "The link is too long" }
        val m = GITHUB_BLOB.matchEntire(s.substringBefore('#').substringBefore('?'))
        return if (m != null) "https://raw.githubusercontent.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/" + m.groupValues[3] else s
    }

    /** Compares dotted versions number by number: "1.10" is newer than "1.9". Versions without digits compare as equal. */
    fun compare(a: String, b: String): Int {
        val x = nums(a)
        val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }
            val q = y.getOrElse(i) { 0L }
            if (p != q) return if (p > q) 1 else -1
        }
        return 0
    }

    private fun nums(v: String): List<Long> = Regex("\\d+").findAll(v).map { it.value.toLongOrNull() ?: 0L }.toList()

    /** Is the downloaded version worth installing over the installed one? When neither has a number, any different text counts as new. */
    fun isNewer(candidate: String, installed: String): Boolean {
        if (nums(candidate).isEmpty() && nums(installed).isEmpty()) return candidate.trim() != installed.trim()
        return compare(candidate, installed) > 0
    }

    /** Downloads to a file. Follows up to 5 redirects, only to https addresses; refuses files over maxBytes. */
    fun download(url: String, out: File, maxBytes: Long) {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "ExpressiveKeyboard-plugin-installer")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("The server redirected without an address")
                    val next = URL(URL(current), loc).toString()
                    require(next.startsWith("https://")) { "A redirect to a non-https address was refused" }
                    current = next
                    return@repeat
                }
                if (code != 200) throw IOException("The server answered HTTP $code")
                require(c.contentLengthLong <= maxBytes) { "The file is larger than " + (maxBytes / 1024 / 1024) + " MB" }
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= maxBytes) { "The file is larger than " + (maxBytes / 1024 / 1024) + " MB" }
                            o.write(buf, 0, n)
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
