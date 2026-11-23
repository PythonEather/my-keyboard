package com.expressive.keyboard

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
