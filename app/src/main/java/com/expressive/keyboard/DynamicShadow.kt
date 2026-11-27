package com.expressive.keyboard

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * Dynamic key shadows: the shadow of every skinned key leans away from the tilt of the phone, as if the light stayed
 * still while the keyboard moves. The offset is stored in half-dp steps as Compose state, so keys are redrawn (and their
 * cached shadow picture rebuilt) only when the step really changes, not on every sensor event.
 */
internal object DynShadow {
    private const val STEP_DP = 0.5f
    private const val MAX_STEPS = 10 // 5 dp

    var enabled by mutableStateOf(false)
        private set
    private var qx by mutableIntStateOf(0)
    private var qy by mutableIntStateOf(0)

    /** Extra shadow offset in dp, to be added to the normal one. */
    val dx: Float get() = qx * STEP_DP
    val dy: Float get() = qy * STEP_DP

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastX = 0
    @Volatile private var lastY = 0

    // tilt parallax: the keys themselves shift, in quarter-dp steps, so a still hand redraws nothing
    private const val P_STEP_DP = 0.25f
    private const val P_MAX_STEPS = 28 // 7 dp
    var parallaxOn by mutableStateOf(false)
        private set
    private var pqx by mutableIntStateOf(0)
    private var pqy by mutableIntStateOf(0)
    @Volatile private var plastX = 0
    @Volatile private var plastY = 0

    /** Parallax shift in dp at strength 1. */
    val parallaxDx: Float get() = pqx * P_STEP_DP
    val parallaxDy: Float get() = pqy * P_STEP_DP

    fun setParallax(on: Boolean) {
        if (parallaxOn == on) return
        parallaxOn = on
        if (!on) {
            plastX = 0
            plastY = 0
            pqx = 0
            pqy = 0
        }
    }

    fun set(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (!on) {
            lastX = 0
            lastY = 0
            qx = 0
            qy = 0
        }
    }

    /** Called from the sensor thread with the smoothed tilt (-1..1; y is 1 when the phone stands upright). */
    fun update(tiltX: Float, tiltY: Float) {
        if (parallaxOn) {
            // 0.7 is about how a phone is held while typing, so the keys sit still there
            val px = (tiltX * 6f / P_STEP_DP).roundToInt().coerceIn(-P_MAX_STEPS, P_MAX_STEPS)
            val py = ((tiltY - 0.7f) * 6f / P_STEP_DP).roundToInt().coerceIn(-P_MAX_STEPS, P_MAX_STEPS)
            if (px != plastX || py != plastY) {
                plastX = px
                plastY = py
                main.post { pqx = px; pqy = py }
            }
        }
        if (!enabled) return
        val x = (tiltX * 4f / STEP_DP).roundToInt().coerceIn(-MAX_STEPS, MAX_STEPS)
        val y = ((tiltY - 1f) * 3f / STEP_DP).roundToInt().coerceIn(-MAX_STEPS, MAX_STEPS)
        if (x == lastX && y == lastY) return
        lastX = x
        lastY = y
        main.post { qx = x; qy = y }
    }
}

/** Keeps [DynShadow] and the tilt sensor in sync with the setting. Draws nothing. */
@Composable
internal fun DynamicShadowEffect(on: Boolean) {
    LaunchedEffect(on) {
        DynShadow.set(on)
        ScriptHost.refreshDevices()
    }
}

/** Tilt parallax: shifts each key a little with the tilt of the phone, so the keys look like they float above the background. */
internal object TiltParallax {
    var strength = 1f
    var invert = false

    /** Called from each key's graphicsLayer block. Reads nothing while the parallax is off. */
    fun apply(layer: androidx.compose.ui.graphics.GraphicsLayerScope) {
        if (!DynShadow.parallaxOn) return
        val k = layer.density * strength * (if (invert) -1f else 1f)
        layer.translationX += DynShadow.parallaxDx * k
        layer.translationY += DynShadow.parallaxDy * k
    }
}

/** Keeps [DynShadow] parallax, [TiltParallax] and the tilt sensor in sync with the settings. Draws nothing. */
@Composable
internal fun TiltParallaxEffect(settings: KeyboardSettings) {
    SideEffect {
        TiltParallax.strength = settings.tiltParallaxStrength
        TiltParallax.invert = settings.tiltParallaxInvert
    }
    LaunchedEffect(settings.tiltParallax) {
        DynShadow.setParallax(settings.tiltParallax)
        ScriptHost.refreshDevices()
    }
}
