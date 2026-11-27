package com.expressive.keyboard

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Typing glow: a thin neon strip along the top or bottom of the keyboard. It gets brighter and its light runs faster the
 * quicker you type, and it calms down by itself when you stop. Frames run only while the strip is warm.
 */
internal object TypingGlow {
    private val model = TypingGlowModel()

    /** Changes on every key press and wakes the frame loop up. */
    var kick by mutableIntStateOf(0)
        private set

    fun press() {
        model.press(SystemClock.uptimeMillis())
        kick++
    }

    fun target(fullCps: Float): Float = model.target(SystemClock.uptimeMillis(), fullCps)
}

@Composable
internal fun BoxScope.TypingGlowLayer(settings: KeyboardSettings, accent: Color) {
    if (settings.typingGlow) TypingGlowBody(settings, accent)
}

@Composable
private fun BoxScope.TypingGlowBody(settings: KeyboardSettings, accent: Color) {
    var heat by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    val full = settings.typingGlowFullCps
    val calm = settings.typingGlowCalm
    LaunchedEffect(TypingGlow.kick, full, calm) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val target = TypingGlow.target(full)
            val h = TypingGlowModel.step(heat, target, dt, calm)
            heat = if (h < 0.004f && target == 0f) 0f else h
            // the light runs faster the hotter the strip is
            phase = (phase + dt * (0.25f + 2.2f * heat)) % 1f
            if (heat == 0f) break
        }
    }
    val top = settings.typingGlowEdge != "BOTTOM"
    val rainbow = settings.typingGlowColor == "RAINBOW"
    val thick = settings.typingGlowThickness.dp
    Canvas(
        Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(thick * 6)
    ) {
        val h = heat
        if (h < 0.003f) return@Canvas
        val a = (h * settings.typingGlowMax).coerceIn(0f, 1f)
        val w = size.width
        val th = thick.toPx()
        val glowH = size.height
        val stripY = if (top) 0f else glowH - th
        val hue = phase * 360f
        val base = if (rainbow) Color.hsv(hue, 0.75f, 1f) else accent
        // soft light spreading from the strip into the keyboard
        val y0 = if (top) 0f else glowH
        val y1 = if (top) glowH else 0f
        drawRect(
            Brush.linearGradient(listOf(base.copy(alpha = a * 0.45f), Color.Transparent), start = Offset(0f, y0), end = Offset(0f, y1)),
            size = Size(w, glowH)
        )
        // the strip: a steady line plus a bright piece of light that runs along it
        if (rainbow) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(
                        Color.hsv(hue % 360f, 0.75f, 1f, a * 0.6f),
                        Color.hsv((hue + 120f) % 360f, 0.75f, 1f, a * 0.6f),
                        Color.hsv((hue + 240f) % 360f, 0.75f, 1f, a * 0.6f)
                    ),
                    startX = 0f, endX = w
                ),
                topLeft = Offset(0f, stripY), size = Size(w, th)
            )
        } else {
            drawRect(base.copy(alpha = a * 0.5f), topLeft = Offset(0f, stripY), size = Size(w, th))
        }
        val seg = w * (0.18f + 0.22f * h)
        val x = phase * (w + seg) - seg
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = a * 0.9f), Color.Transparent), startX = x, endX = x + seg),
            topLeft = Offset(0f, stripY), size = Size(w, th)
        )
    }
}
