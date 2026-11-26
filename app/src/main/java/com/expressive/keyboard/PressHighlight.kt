package com.expressive.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Press highlight strength (setting "press_highlight"): scales how much a pressed key changes color and glows.
 * 1 = as the theme draws it, 0 = the key keeps its resting color (the press animation still plays).
 * Kept as Compose state so keys redraw when the slider moves.
 */
internal object PressFx {
    var highlight by mutableFloatStateOf(1f)
        private set

    fun set(v: Float) {
        val c = v.coerceIn(0f, 1f)
        if (c != highlight) highlight = c
    }
}

/** Keeps [PressFx] in sync with the setting. Draws nothing. */
@Composable
internal fun PressHighlightEffect(strength: Float) {
    SideEffect { PressFx.set(strength) }
}
