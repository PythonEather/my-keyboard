package com.expressive.keyboard

import kotlin.math.sqrt

/**
 * Встряхивание телефона по данным акселерометра (событие "shake" для модов).
 * Рывок — ускорение заметно выше силы тяжести; несколько рывков подряд за короткое время — это встряхивание.
 * Чистая логика без Android: проверяется юнит-тестом.
 */
class ShakeDetector(
    /** Порог рывка в единицах g (1 g — покой). */
    private val threshold: Float = 2.3f,
    /** Сколько рывков нужно. */
    private val jolts: Int = 3,
    /** За какое время они должны случиться, мс. */
    private val windowMs: Long = 900L,
    /** Пауза после сработавшего события, мс. */
    private val cooldownMs: Long = 1000L,
    /** Минимум между двумя рывками, мс: одна тряска — один рывок, а не десять. */
    private val gapMs: Long = 80L
) {
    private val times = LongArray(jolts)
    private var count = 0
    private var lastJolt = Long.MIN_VALUE / 2
    private var lastShake = Long.MIN_VALUE / 2
    private var peak = 0f

    /** Значения акселерометра в м/с². Возвращает силу встряхивания (в g) в момент срабатывания, иначе null. */
    fun feed(x: Float, y: Float, z: Float, nowMs: Long): Float? {
        val g = sqrt(x * x + y * y + z * z) / 9.80665f
        if (g < threshold) return null
        if (nowMs - lastShake < cooldownMs) return null
        if (nowMs - lastJolt < gapMs) {
            if (g > peak) peak = g
            return null
        }
        lastJolt = nowMs
        // забываем рывки, которые вышли из окна
        var keep = 0
        for (i in 0 until count) if (nowMs - times[i] <= windowMs) times[keep++] = times[i]
        count = keep
        if (count == jolts) {
            for (i in 1 until jolts) times[i - 1] = times[i]
            count--
        }
        times[count++] = nowMs
        if (g > peak) peak = g
        if (count < jolts) return null
        val strength = peak
        reset()
        lastShake = nowMs
        return strength
    }

    fun reset() {
        count = 0
        peak = 0f
    }
}
