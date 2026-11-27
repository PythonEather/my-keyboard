package com.expressive.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Press shockwave: a soft ring spreads from the pressed key across the keyboard. The keys the ring passes are nudged
 * outward for a moment (visual only, like the jelly push: touch areas stay where they are). One animation drives
 * everything, a new press restarts it, and nothing is read or drawn while no ring is running.
 */
internal object Shockwave {
    var enabled = false
    var speed = 1f
    var strength = 0.5f
    var reach = 3.5f
    var rippleDp = 3f
    var ring = true

    private val progress = Animatable(1f)
    private var running by mutableStateOf(false)
    private var ox by mutableFloatStateOf(0f)
    private var oy by mutableFloatStateOf(0f)
    private var maxR by mutableFloatStateOf(1f)
    private var gen = 0

    fun set(on: Boolean, speed: Float, strength: Float, reach: Float, rippleDp: Float, ring: Boolean) {
        enabled = on
        this.speed = speed
        this.strength = strength
        this.reach = reach
        this.rippleDp = rippleDp
        this.ring = ring
    }

    fun press(cell: LayoutCoordinates?, scope: CoroutineScope) {
        if (!enabled) return
        val c = cell?.takeIf { it.isAttached } ?: return
        val p = c.positionInRoot()
        ox = p.x + c.size.width / 2f
        oy = p.y + c.size.height / 2f
        // wide keys (space, enter) do not make a giant ring: the width is capped by the height
        maxR = min(c.size.width.toFloat(), c.size.height * 1.6f) * reach
        val mine = ++gen
        running = true
        val ms = (900f / speed).toInt().coerceIn(300, 2000)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(ms, easing = LinearOutSlowInEasing))
            } finally {
                // a newer press owns the ring now; only the last one switches it off
                if (mine == gen) running = false
            }
        }
    }

    /** Called from each key's graphicsLayer block: pushes the key outward while the ring passes it. */
    fun apply(layer: GraphicsLayerScope, cell: LayoutCoordinates?) {
        if (!running || rippleDp <= 0f) return
        val t = progress.value
        if (t >= 0.999f) return
        val c = cell?.takeIf { it.isAttached } ?: return
        val p = c.positionInRoot()
        val mx = p.x + c.size.width / 2f - ox
        val my = p.y + c.size.height / 2f - oy
        val d = hypot(mx, my)
        if (d < 1f) return
        val w = maxR * 0.18f + 1f
        val x = (d - maxR * t) / w
        if (x > 3f || x < -3f) return
        // a bump that follows the ring and gets smaller as it spreads
        val push = layer.density * rippleDp * strength * 2f * exp(-x * x) * (1f - t)
        layer.translationX += mx / d * push
        layer.translationY += my / d * push
    }

    /** The ring itself: a soft wide line under a thin bright one, thick at the start and thin at the end. */
    fun draw(scope: DrawScope, origin: Offset, color: Color) {
        if (!running) return
        val t = progress.value
        if (t >= 0.999f) return
        val r = maxR * t
        if (r < 2f) return
        val fade = 1f - t
        val alpha = (strength * fade * fade * 1.4f).coerceIn(0f, 1f)
        val center = Offset(ox - origin.x, oy - origin.y)
        with(scope) {
            val sw = 2.5.dp.toPx() + 5.dp.toPx() * fade
            drawCircle(color.copy(alpha = alpha * 0.25f), r, center, style = Stroke(sw * 3f))
            drawCircle(color.copy(alpha = alpha), r, center, style = Stroke(sw))
        }
    }
}

/** Draws the ring over the keyboard. Does nothing while the shockwave or its ring is switched off. */
@Composable
internal fun BoxScope.ShockwaveLayer(show: Boolean, color: Color) {
    if (!show) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Shockwave.draw(this, origin, color)
    }
}

/** Keeps [Shockwave] in sync with the settings. Draws nothing. */
@Composable
internal fun ShockwaveEffect(settings: KeyboardSettings) {
    SideEffect {
        Shockwave.set(
            settings.shockwave, settings.shockwaveSpeed, settings.shockwaveStrength,
            settings.shockwaveReach, settings.shockwaveRipple, settings.shockwaveRing
        )
    }
}
