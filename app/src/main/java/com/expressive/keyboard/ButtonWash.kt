package com.expressive.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Wash": a soft colour wave that slides across a button when it is tapped (like the share button of Xiaomi UFC).
 * One band with a fainter band behind it sweeps from the left edge to the right edge, strongest in the middle.
 * It is drawn on top of the button background and under the label, clipped to the button shape. Nothing runs
 * while no button is being tapped: the animation exists only between a tap and the end of the sweep.
 */
internal object ButtonWash {
    var enabled = true
    var speed = 1f
    var strength = 0.55f

    /** Keys that get the sweep: ?123 / ABC, the symbols page, emoji, language, Shift and Enter. Letters stay calm. */
    val keyTypes = setOf(
        KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE, KeyType.STICKERS, KeyType.GLOBE, KeyType.SHIFT, KeyType.ENTER
    )

    fun set(on: Boolean, speed: Float, strength: Float) {
        enabled = on
        this.speed = speed.coerceIn(0.5f, 2.5f)
        this.strength = strength.coerceIn(0.1f, 1f)
    }

    fun play(wash: Animatable<Float, AnimationVector1D>, scope: CoroutineScope) {
        if (!enabled) return
        val ms = (560f / speed).toInt().coerceIn(220, 1200)
        scope.launch {
            wash.snapTo(0f)
            wash.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
            wash.snapTo(0f)
        }
    }
}

/** The sweep itself; [progress] runs 0..1. Draws nothing at 0 and at 1. */
internal fun DrawScope.drawWash(progress: Float, color: Color, corner: Float) {
    if (progress <= 0f || progress >= 1f) return
    val w = size.width
    val h = size.height
    if (w <= 1f || h <= 1f) return
    val band = w * 0.7f + h
    // the band enters past the left edge and leaves past the right edge
    val head = -band + (w + band * 2f) * progress
    // swells in the middle of the sweep and fades at both ends, like a wave crest passing
    val crest = sin(PI.toFloat() * progress).coerceIn(0f, 1f)
    val main = (color.alpha * ButtonWash.strength * crest).coerceIn(0f, 1f)
    val trail = (main * 0.45f).coerceIn(0f, 1f)
    val path = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner, corner))) }
    clipPath(path) {
        val tailStart = head - band * 0.6f
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, color.copy(alpha = trail), Color.Transparent),
                start = Offset(tailStart, 0f), end = Offset(tailStart + band * 0.9f, h * 0.7f)
            )
        )
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, color.copy(alpha = main), Color.Transparent),
                start = Offset(head, 0f), end = Offset(head + band, h * 0.7f)
            )
        )
    }
}

/** Clickable that plays the sweep on every tap. For toolbar and header buttons; the shape comes from a clip before it. */
@Composable
internal fun Modifier.washClickable(color: Color, onClick: () -> Unit): Modifier {
    val wash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return this
        .clickable {
            ButtonWash.play(wash, scope)
            onClick()
        }
        .drawBehind { drawWash(wash.value, color, size.height / 2f) }
}

/** Keeps [ButtonWash] in sync with the settings. Draws nothing. */
@Composable
internal fun ButtonWashEffect(settings: KeyboardSettings) {
    SideEffect { ButtonWash.set(settings.buttonWash, settings.buttonWashSpeed, settings.buttonWashStrength) }
}
