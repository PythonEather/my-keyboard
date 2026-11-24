package com.expressive.keyboard

/**
 * Скорость набора для события "typing": считает нажатия по скользящему окну.
 * Чистая логика без Android: проверяется юнит-тестом. Текст букв сюда не попадает — только вид клавиши и время.
 */
class TypingStats(private val windowMs: Long = 2000L) {
    private val letters = ArrayDeque<Long>()
    private val backspaces = ArrayDeque<Long>()
    private var lastAt = 0L
    private var burst = 0

    class Snapshot(
        /** Вид клавиши: letter, space, backspace, enter, shift, other. */
        val kind: String,
        /** Нажатий символов в секунду за последние ~2 с. */
        val cps: Float,
        /** Стираний в секунду за последние ~2 с: быстрые подряд — человек ошибается или нервничает. */
        val backspaceCps: Float,
        /** Сколько нажатий подряд без паузы дольше 700 мс. */
        val burst: Int,
        /** Пауза перед этим нажатием, мс. */
        val gapMs: Long
    )

    fun press(kind: String, nowMs: Long): Snapshot {
        val gap = if (lastAt == 0L) 0L else nowMs - lastAt
        lastAt = nowMs
        burst = if (gap in 1..700L || burst == 0 && gap == 0L) burst + 1 else 1
        when (kind) {
            "backspace" -> backspaces.addLast(nowMs)
            "letter", "space", "enter" -> letters.addLast(nowMs)
        }
        trim(letters, nowMs)
        trim(backspaces, nowMs)
        val secs = windowMs / 1000f
        return Snapshot(kind, letters.size / secs, backspaces.size / secs, burst, gap)
    }

    private fun trim(q: ArrayDeque<Long>, now: Long) {
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
    }

    fun reset() {
        letters.clear()
        backspaces.clear()
        lastAt = 0L
        burst = 0
    }
}
