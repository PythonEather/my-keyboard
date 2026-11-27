package com.expressive.keyboard

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Edge swipe for one-hand mode: a swipe that starts at the very edge of the keyboard and goes inward shrinks the keyboard
 * to that side. A swipe from the opposite edge brings the full width back (or moves it to that side, as set).
 * It only watches the touches (first pass, nothing is blocked): the keys keep working as before.
 */
internal fun Modifier.edgeSwipeOneHand(settings: KeyboardSettings, context: Context, active: Boolean): Modifier {
    if (!settings.edgeSwipe || !active) return this
    return this.pointerInput(
        settings.oneHanded, settings.edgeSwipeZoneDp, settings.edgeSwipeDistanceDp, settings.edgeSwipeOpposite, settings.edgeSwipeHaptic
    ) {
        val zone = settings.edgeSwipeZoneDp.dp.toPx()
        val distance = settings.edgeSwipeDistanceDp.dp.toPx()
        val exit = settings.edgeSwipeOpposite != "SWITCH"
        val current = settings.oneHanded
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val width = size.width.toFloat()
            // a touch that does not start at an edge is none of our business
            if (down.position.x > zone && down.position.x < width - zone) return@awaitEachGesture
            val id = down.id
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                // a second finger means typing, not an edge swipe
                if (ev.changes.size > 1) break
                val ch = ev.changes.firstOrNull { it.id == id } ?: break
                if (!ch.pressed) break
                val dx = ch.position.x - down.position.x
                val dy = ch.position.y - down.position.y
                val mode = EdgeSwipeLogic.resolve(down.position.x, width, zone, dx, dy, distance, current, exit)
                if (mode != null) {
                    ch.consume()
                    KeyboardPrefs.edit(context) { putString("one_handed", mode) }
                    if (settings.edgeSwipeHaptic) HapticsEngine.tick(context, null, settings)
                    break
                }
            }
        }
    }
}
