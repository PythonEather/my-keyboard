package com.expressive.keyboard

/** Pitch of a key click: each key gets its own fixed small offset, so "a" always sounds a little different from "s". */
internal object KeyPitch {
    /** A fixed number from -1 to 1 for a key name. */
    fun offset(name: String): Float {
        if (name.isEmpty()) return 0f
        var h = -0x7ee3623b // 2166136261, FNV-1a
        for (c in name) h = (h xor c.code) * 16777619
        // mix the bits, so one-letter names do not end up with almost the same number
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        val m = h and 0xFFFF
        return m / 32767.5f - 1f
    }

    /**
     * Playback rate for SoundPool. [amount] 0..1 is how far keys differ (1 = up to 20% up or down), [wobble] 0..1 is the
     * random change on every press (0.5 = the old plus or minus 3%), [random] is a number from 0 to 1.
     */
    fun rate(name: String, amount: Float, wobble: Float, random: Float): Float =
        (1f + offset(name) * 0.2f * amount + (random - 0.5f) * 0.12f * wobble).coerceIn(0.6f, 1.6f)
}
