package com.expressive.keyboard

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The display's vsync as a clock for the GL threads. A TextureView shows a new frame when the main thread draws its next UI frame, so a
 * frame that is ready at a random moment waits for that moment anyway, and sleeping to a frame rate makes the picture judder. A GL thread
 * waits here for the Choreographer tick (it comes on the main thread), draws, and the frame is ready for the next UI frame.
 * The Choreographer callback is posted only while a GL thread is waiting, so a paused or idle layer costs nothing.
 */
internal object GlVsync {
    private val lock = ReentrantLock()
    private val ticked = lock.newCondition()
    private var tick = 0L
    private var lastNs = 0L
    private var armed = false
    private val main = Handler(Looper.getMainLooper())
    private val callback = Choreographer.FrameCallback { ts -> onFrame(ts) }

    /** The length of one display frame in ns, learned from the ticks (16.7 ms until the first ones arrive). */
    @Volatile
    var periodNs = 16_666_667L
        private set

    /**
     * Waits for the next vsync and returns its time on the System.nanoTime clock. Returns 0 when no vsync came within [timeoutMs]
     * (the main thread is stuck or the display is off) or the thread was interrupted: the caller then falls back to a timed wait.
     */
    fun await(timeoutMs: Long): Long {
        lock.lock()
        try {
            val start = tick
            if (!armed) {
                armed = true
                main.post {
                    val c = Choreographer.getInstance()
                    c.removeFrameCallback(callback)
                    c.postFrameCallback(callback)
                }
            }
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (tick == start) {
                if (left <= 0L) {
                    armed = false
                    return 0L
                }
                left = ticked.awaitNanos(left)
            }
            return lastNs
        } catch (e: InterruptedException) {
            return 0L
        } finally {
            lock.unlock()
        }
    }

    private fun onFrame(ts: Long) {
        lock.withLock {
            val d = ts - lastNs
            // only gaps of about one display frame teach the period: a long gap is a frame the GL thread skipped, or a pause
            if (lastNs != 0L && d in 4_000_000L..60_000_000L && d < periodNs * 3 / 2) periodNs += (d - periodNs) / 8
            lastNs = ts
            tick++
            armed = false
            ticked.signalAll()
        }
    }
}
