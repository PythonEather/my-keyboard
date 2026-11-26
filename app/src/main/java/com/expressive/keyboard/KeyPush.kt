package com.expressive.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Neighbour push ("jelly"): while a key is pressed, the keys around it slide away from it and spring back on release.
 * Visual only: it moves the drawing layer of each key, the touch areas stay where they are. One spring drives all
 * keys, and nothing is read or computed while the push is idle.
 */
internal object KeyPush {
    var enabled = false
    var strengthDp = 4f
    var rings = 1.5f
    var bounce = 0.35f
    var speed = 1f
    var mode = "AWAY"
    var squash = 0f

    private val amount = Animatable(0f)
    private var cx by mutableFloatStateOf(0f)
    private var cy by mutableFloatStateOf(0f)
    private var halfW = 0f
    private var halfH = 0f
    private var held = 0

    fun set(on: Boolean, strength: Float, rings: Float, bounce: Float, speed: Float, mode: String, squash: Float) {
        enabled = on
        strengthDp = strength
        this.rings = rings
        this.bounce = bounce
        this.speed = speed
        this.mode = mode
        this.squash = squash
    }

    private fun spec() = spring<Float>(dampingRatio = bounce.coerceIn(0.15f, 1f), stiffness = (900f * speed).coerceIn(200f, 4000f))

    fun press(cell: LayoutCoordinates?, scope: CoroutineScope) {
        if (!enabled) return
        val c = cell?.takeIf { it.isAttached } ?: return
        val p = c.positionInRoot()
        halfW = c.size.width / 2f
        halfH = c.size.height / 2f
        cx = p.x + halfW
        cy = p.y + halfH
        held++
        scope.launch(start = CoroutineStart.UNDISPATCHED) { amount.animateTo(1f, spec()) }
    }

    fun release(scope: CoroutineScope) {
        if (held > 0) held--
        // another finger still holds a key: the push stays until the last one lets go
        if (held > 0) return
        if (amount.value == 0f && !amount.isRunning) return
        scope.launch { amount.animateTo(0f, spec()) }
    }

    /** Called from each key's graphicsLayer block; adds the push to the layer's translation. */
    fun apply(layer: GraphicsLayerScope, cell: LayoutCoordinates?) {
        val a = amount.value
        if (abs(a) < 0.004f) return
        val c = cell?.takeIf { it.isAttached } ?: return
        val p = c.positionInRoot()
        val mx = p.x + c.size.width / 2f - cx
        val my = p.y + c.size.height / 2f - cy
        val len = hypot(mx, my)
        if (len < 1f) return
        // distance from the pressed key's edge, so wide keys (space, enter) push their neighbours as well
        val d = hypot(max(abs(mx) - halfW, 0f), max(abs(my) - halfH, 0f))
        val reach = min(halfW, halfH) * 2f * (rings + 0.35f)
        if (d >= reach) return
        val t = 1f - d / reach
        val push = layer.density * strengthDp * t * t * a
        val ux = mx / len
        val uy = my / len
        when (mode) {
            "TOWARD" -> { layer.translationX -= ux * push; layer.translationY -= uy * push }
            "SWIRL" -> { layer.translationX -= uy * push; layer.translationY += ux * push }
            else -> { layer.translationX += ux * push; layer.translationY += uy * push }
        }
        if (squash > 0f) {
            val f = (1f - squash * t * t * a).coerceIn(0.6f, 1.2f)
            layer.scaleX *= f
            layer.scaleY *= f
        }
    }
}

/** Keeps [KeyPush] in sync with the settings. Draws nothing. */
@Composable
internal fun KeyPushEffect(settings: KeyboardSettings) {
    SideEffect {
        KeyPush.set(settings.keyPush, settings.keyPushStrength, settings.keyPushRings, settings.keyPushBounce, settings.keyPushSpeed, settings.keyPushMode, settings.keyPushSquash)
    }
}
