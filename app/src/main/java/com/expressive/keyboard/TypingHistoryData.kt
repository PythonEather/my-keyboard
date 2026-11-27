package com.expressive.keyboard

/**
 * Numbers for the typing stats page. Only counts and words are kept, never the text around them.
 * Pure logic (days are epoch day numbers), so it is tested without Android. Saving is done by [TypingHistory].
 */
internal class TypingHistoryData {
    class Day(var keys: Int = 0, var words: Int = 0, var activeMs: Long = 0L, var best: Float = 0f)

    val days = HashMap<Int, Day>()
    val words = HashMap<String, Int>()

    private var lastPress = 0L
    private val recent = ArrayDeque<Long>()

    /** One character, space or enter. */
    fun press(epochDay: Int, nowMs: Long) {
        val d = days.getOrPut(epochDay) { Day() }
        d.keys++
        val gap = if (lastPress == 0L) 0L else nowMs - lastPress
        // time between presses counts as typing only while the pauses are short
        if (gap in 1..ACTIVE_GAP_MS) d.activeMs += gap
        lastPress = nowMs
        recent.addLast(nowMs)
        while (recent.isNotEmpty() && nowMs - recent.first() > BEST_WINDOW_MS) recent.removeFirst()
        if (recent.size >= BEST_MIN_PRESSES) {
            // presses in 5 seconds: five presses make one word, so words per minute = presses / 5 * 12
            val wpm = recent.size / 5f * (60_000f / BEST_WINDOW_MS)
            if (wpm > d.best) d.best = wpm
        }
        if (days.size > MAX_DAYS) days.keys.minOrNull()?.let { days.remove(it) }
    }

    fun word(epochDay: Int, raw: String) {
        val w = clean(raw) ?: return
        days.getOrPut(epochDay) { Day() }.words++
        words[w] = (words[w] ?: 0) + 1
        if (words.size > MAX_WORDS) prune()
    }

    /** Drops the rarest quarter of the words once the list is too long. */
    private fun prune() {
        val sorted = words.entries.sortedBy { it.value }
        for (e in sorted.take(MAX_WORDS / 4)) words.remove(e.key)
    }

    fun top(n: Int): List<Pair<String, Int>> =
        words.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(n).map { it.key to it.value }

    /** Consecutive days with typing, counted back from today (or from yesterday while today is still empty). */
    fun streak(today: Int): Int {
        var day = if ((days[today]?.keys ?: 0) > 0) today else today - 1
        var n = 0
        while ((days[day]?.keys ?: 0) > 0) {
            n++
            day--
        }
        return n
    }

    fun longestStreak(): Int {
        var best = 0
        var run = 0
        var prev = Int.MIN_VALUE
        for (d in days.filter { it.value.keys > 0 }.keys.sorted()) {
            run = if (d == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    /** Average words per minute over the last [span] days, or 0 when there is too little typing to say. */
    fun averageWpm(today: Int, span: Int): Float {
        var keys = 0
        var ms = 0L
        for (i in 0 until span) {
            val d = days[today - i] ?: continue
            keys += d.keys
            ms += d.activeMs
        }
        if (ms < 10_000L) return 0f
        return keys / 5f / (ms / 60_000f)
    }

    fun bestWpm(): Float = days.values.maxOfOrNull { it.best } ?: 0f

    fun keysOn(day: Int): Int = days[day]?.keys ?: 0

    fun totalKeys(): Int = days.values.sumOf { it.keys }

    fun totalWords(): Int = days.values.sumOf { it.words }

    fun clear() {
        days.clear()
        words.clear()
        recent.clear()
        lastPress = 0L
    }

    companion object {
        const val ACTIVE_GAP_MS = 2000L
        const val BEST_WINDOW_MS = 5000L
        const val BEST_MIN_PRESSES = 12
        const val MAX_DAYS = 400
        const val MAX_WORDS = 3000

        /** Lower case letters only, 2 to 24 long; numbers, codes and mixed strings are never stored. */
        fun clean(raw: String): String? {
            val w = raw.trim().lowercase()
            if (w.length < 2 || w.length > 24) return null
            for (c in w) if (!c.isLetter() && c != '\'' && c != '’') return null
            return w
        }
    }
}
