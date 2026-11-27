package com.expressive.keyboard

/**
 * Which saved look the keyboard wears right now. An app rule wins over the day/night rule.
 * The look name [BASE] means "my normal theme": it stops the day/night rule too, so a browser can stay plain at night.
 * Returns "" for "no override" and a look name otherwise. Pure logic, no Android.
 */
internal object AutoLookRules {
    const val BASE = "@base"

    fun pick(
        pkg: String,
        perApp: Boolean,
        appLooks: Map<String, String>,
        dayNight: Boolean,
        night: Boolean,
        dayLook: String,
        nightLook: String,
        known: Set<String>
    ): String {
        if (perApp) {
            val n = appLooks[pkg]
            if (n == BASE) return ""
            if (n != null && n in known) return n
        }
        if (dayNight) {
            val n = if (night) nightLook else dayLook
            if (n.isNotEmpty() && n != BASE && n in known) return n
        }
        return ""
    }
}
