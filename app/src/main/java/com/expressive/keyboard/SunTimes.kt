package com.expressive.keyboard

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset from a latitude and longitude, with the simple NOAA formulas (good to a couple of minutes).
 * No location permission: the person types the two numbers once. Pure maths, no Android.
 */
internal object SunTimes {
    class Day(val sunrise: Int, val sunset: Int)

    /** Local minutes after midnight, or null when the sun does not rise or set that day (polar day or night). */
    fun compute(lat: Double, lon: Double, dayOfYear: Int, utcOffsetMinutes: Int): Day? {
        val g = 2.0 * Math.PI / 365.0 * (dayOfYear - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa > 1.0 || cosHa < -1.0) return null
        val ha = Math.toDegrees(acos(cosHa))
        val rise = 720.0 - 4.0 * (lon + ha) - eq + utcOffsetMinutes
        val set = 720.0 - 4.0 * (lon - ha) - eq + utcOffsetMinutes
        return Day(norm(rise), norm(set))
    }

    private fun norm(minutes: Double): Int {
        var m = minutes.roundToInt() % 1440
        if (m < 0) m += 1440
        return m
    }

    /** "7.2, 124.2" or "7.2 124.2" to latitude and longitude; null when it is not two valid numbers. */
    fun parseLatLon(text: String): Pair<Double, Double>? {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return lat to lon
    }

    /** True when [now] (minutes after midnight) lies in the night that starts at [nightStart] and ends at [dayStart]. */
    fun inNight(now: Int, nightStart: Int, dayStart: Int): Boolean = when {
        nightStart == dayStart -> false
        nightStart > dayStart -> now >= nightStart || now < dayStart
        else -> now >= nightStart && now < dayStart
    }
}
