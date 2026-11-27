package com.expressive.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Typing stats that stay on this phone: counts per day, the best speed and the most used words (letters only, never
 * numbers or codes). Nothing is recorded in password fields, in incognito or while the switch is off. The numbers are
 * kept in memory and written to their own small file every 40 presses and when the keyboard closes.
 * ([TypingStats] is something else: a 2 second window that feeds the "typing" event of plugins, it keeps no history.)
 */
internal object TypingHistory {
    private const val PREFS = "typing_history"

    val data = TypingHistoryData()
    private var loaded = false
    private var unsaved = 0

    /** Changes after every save, so the stats page redraws. */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(now: Long = System.currentTimeMillis()): Int = ((now + TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()

    @Synchronized
    fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val p = prefs(c)
            p.getString("days", null)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i)
                    data.days[r.getInt(0)] = TypingHistoryData.Day(r.getInt(1), r.getInt(2), r.getLong(3), r.getDouble(4).toFloat())
                }
            }
            p.getString("words", null)?.let { s ->
                val o = JSONObject(s)
                for (k in o.keys()) data.words[k] = o.getInt(k)
            }
        }
    }

    fun press(c: Context) {
        ensure(c)
        data.press(today(), SystemClock.uptimeMillis())
        changed(c)
    }

    fun word(c: Context, word: String) {
        ensure(c)
        data.word(today(), word)
        changed(c)
    }

    private fun changed(c: Context) {
        unsaved++
        if (unsaved >= 40) flush(c)
    }

    @Synchronized
    fun flush(c: Context) {
        if (unsaved == 0 || !loaded) return
        unsaved = 0
        runCatching {
            val a = JSONArray()
            for ((d, x) in data.days) {
                a.put(JSONArray().put(d).put(x.keys).put(x.words).put(x.activeMs).put(x.best.toDouble()))
            }
            val o = JSONObject()
            for ((w, n) in data.words) o.put(w, n)
            prefs(c).edit().putString("days", a.toString()).putString("words", o.toString()).apply()
        }
        version++
    }

    /** Deletes everything, on the phone and in memory. */
    @Synchronized
    fun clear(c: Context) {
        ensure(c)
        data.clear()
        unsaved = 0
        prefs(c).edit().clear().apply()
        version++
    }
}
