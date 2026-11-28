package com.expressive.keyboard

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// LONG COPIED TEXT (11.2.37)
// A very long copy used to be loaded as one giant string: kept in the history, saved to disk as JSON and drawn
// as a card, and that froze or crashed the keyboard. Above the limit set in Settings the text is now written to
// a .txt file and the history only holds a short entry that points at it (its size and the first words).
// Like pictures, the entry is a string with an invisible prefix, so it can be pinned, filed in a category
// and deleted in the same way as any other card.
// ============================================================================

object LongTexts {
    private const val PREFIX = "\u0000txt:"
    private const val SEP = '\u0000'

    /** Folder inside Downloads. */
    const val FOLDER = "Expressive Keyboard"

    /** How much a tap on the card pastes; the whole text goes out as a file with the share button. */
    const val PASTE_CHARS = 5_000

    private const val PREVIEW_CHARS = 160
    private const val MAX_SAVE_CHARS = 20_000_000
    private const val PRIVATE_DIR = "longtexts"
    private const val PINNED_KEY = "pinned_clips"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)

    class Info(val location: String, val chars: Int, val bytes: Long, val preview: String)

    fun isLong(entry: String) = entry.startsWith(PREFIX)

    fun info(entry: String): Info? {
        if (!isLong(entry)) return null
        val p = entry.substring(PREFIX.length).split(SEP, limit = 4)
        if (p.size < 4) return null
        return Info(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L, p[3])
    }

    private fun isPrivate(location: String) = location.startsWith("/")

    private fun privateDir(context: Context) = File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }

    /** The text is longer than the limit from Settings (0 — the feature is off). */
    fun isTooLong(length: Int, s: KeyboardSettings) = s.longTextLimit > 0 && length > s.longTextLimit

    // ------------------------------------------------------------------ labels

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun countLabel(n: Int): String = String.format(Locale.US, "%,d", n)

    /** Text of the paste offer above the keys. */
    fun offerLabel(entry: String): String {
        val i = info(entry) ?: return ""
        return tr("Paste long text") + " · " + sizeLabel(i.bytes)
    }

    fun dateLabel(entry: String): String {
        val at = ClipStore.timeOf(entry) ?: System.currentTimeMillis()
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(at))
    }

    private fun previewOf(text: CharSequence): String {
        val head = text.subSequence(0, minOf(text.length, PREVIEW_CHARS * 2)).toString()
        return head.replace(SEP, ' ').replace(Regex("\\s+"), " ").trim().take(PREVIEW_CHARS)
    }

    // ------------------------------------------------------------------ saving

    private class CountingStream(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            o.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            o.write(b, off, len)
            count += len
        }

        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    /** Writes in small pieces: a huge text is never turned into one huge byte array. */
    private fun writeText(out: OutputStream, text: CharSequence, length: Int): Long {
        val counting = CountingStream(out)
        val w = java.io.OutputStreamWriter(counting, Charsets.UTF_8)
        var i = 0
        while (i < length) {
            val end = minOf(i + 32_768, length)
            w.append(text, i, end)
            i = end
        }
        w.flush()
        return counting.count
    }

    /**
     * Saves the text as a .txt file and returns the history entry for it, or null if that failed.
     * place: DOWNLOADS (Downloads/Expressive Keyboard, Android 10 and newer) or PRIVATE (the app's own folder).
     */
    suspend fun save(context: Context, text: CharSequence, place: String, stamp: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val length = minOf(text.length, MAX_SAVE_CHARS)
            val name = "long-text-" + SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date(stamp)) + ".txt"
            var bytes = 0L
            val location: String
            if (place == "DOWNLOADS" && Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                try {
                    bytes = resolver.openOutputStream(uri)?.use { writeText(it, text, length) } ?: error("no stream")
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                location = uri.toString()
            } else {
                val f = File(privateDir(context), name)
                bytes = f.outputStream().use { writeText(it, text, length) }
                location = f.absolutePath
            }
            PREFIX + location + SEP + length + SEP + bytes + SEP + previewOf(text)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ reading

    private fun open(context: Context, location: String): InputStream? =
        if (isPrivate(location)) File(location).takeIf { it.exists() }?.inputStream()
        else context.contentResolver.openInputStream(Uri.parse(location))

    /** The file is still there (it can be deleted in a file manager, or lost when the app is reinstalled). */
    fun exists(context: Context, entry: String): Boolean {
        val i = info(entry) ?: return false
        return runCatching {
            if (isPrivate(i.location)) File(i.location).exists()
            else context.contentResolver.openFileDescriptor(Uri.parse(i.location), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    /** First characters of the text, for a paste; null if the file is gone. Call off the main thread. */
    fun readStart(context: Context, entry: String, n: Int = PASTE_CHARS): String? {
        val i = info(entry) ?: return null
        return runCatching {
            open(context, i.location)?.use { s ->
                java.io.InputStreamReader(s, Charsets.UTF_8).use { r ->
                    val buf = CharArray(n)
                    var got = 0
                    while (got < n) {
                        val k = r.read(buf, got, n - got)
                        if (k < 0) break
                        got += k
                    }
                    String(buf, 0, got)
                }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ sharing

    /** Opens the Android share sheet with the whole file; false if the file is not there. */
    fun share(context: Context, entry: String): Boolean {
        val i = info(entry) ?: return false
        return runCatching {
            val uri = if (isPrivate(i.location)) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(i.location))
            } else Uri.parse(i.location)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ deleting

    /** Deletes the file now; call off the main thread. Only files the keyboard made are ever touched. */
    fun delete(context: Context, entry: String) {
        val i = info(entry) ?: return
        runCatching {
            if (isPrivate(i.location)) File(i.location).delete()
            else context.contentResolver.delete(Uri.parse(i.location), null, null)
        }
    }

    /** Deletes the file in the background. */
    fun deleteLater(context: Context, entry: String) {
        if (!isLong(entry)) return
        val app = context.applicationContext
        io.launch { delete(app, entry) }
    }

    /** Which entries of the history the automatic cleanup removes: too old, or beyond the newest N. Pinned stay. */
    fun overflow(list: List<String>, pinned: Collection<String>, s: KeyboardSettings): List<String> {
        val longs = list.filter { isLong(it) && it !in pinned }
        val out = LinkedHashSet<String>()
        val keep = keepMs(s)
        val now = System.currentTimeMillis()
        if (keep > 0) longs.forEach { if (now - (ClipStore.timeOf(it) ?: now) > keep) out += it }
        longs.filter { it !in out }.drop(s.longTextMax.coerceAtLeast(1)).forEach { out += it }
        return out.toList()
    }

    private fun keepMs(s: KeyboardSettings): Long = when (s.longTextKeep) {
        "1D" -> 86_400_000L
        "7D" -> 7 * 86_400_000L
        "30D" -> 30 * 86_400_000L
        else -> 0L
    }

    /** Entries whose file has been deleted from outside the keyboard. Call off the main thread. */
    fun missing(context: Context, entries: List<String>): List<String> =
        entries.filter { isLong(it) && !exists(context, it) }

    // ------------------------------------------------------------------ the settings page

    private fun pinnedOnDisk(context: Context): List<String> = runCatching {
        val prefs = context.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(PINNED_KEY, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Every long text the keyboard knows about (history and pinned), newest first. Call off the main thread. */
    fun listAll(context: Context, s: KeyboardSettings): List<String> =
        (ClipStore.load(context, s) + pinnedOnDisk(context)).filter { isLong(it) && exists(context, it) }.distinct()

    /** Deletes the files and removes their cards from the saved history and the pinned list. Call off the main thread. */
    fun forget(context: Context, s: KeyboardSettings, entries: Set<String>) {
        if (entries.isEmpty()) return
        entries.forEach {
            delete(context, it)
            ClipStore.forget(it)
        }
        ClipStore.save(context, ClipStore.load(context, s).filter { it !in entries })
        runCatching {
            val prefs = context.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
            val keep = JSONArray()
            pinnedOnDisk(context).filter { it !in entries }.forEach { keep.put(it) }
            prefs.edit().putString(PINNED_KEY, keep.toString()).apply()
        }
    }
}
