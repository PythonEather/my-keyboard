package com.expressive.keyboard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Heat of the phone (Android 10+). The GL layers lower their frame rate and resolution BEFORE the system throttles the CPU/GPU:
 * a throttled phone stutters much worse than one that dropped quality early.
 */
object GlThermal {
    @Volatile var status = 0
        private set
    private var lastPoll = 0L

    /** Cheap: asks the system at most once every 4 seconds. Called from the GL thread. */
    fun poll(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoll < 4000L) return
        lastPoll = now
        if (Build.VERSION.SDK_INT >= 29) {
            status = try {
                (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
            } catch (t: Throwable) {
                0
            }
        }
    }

    /** Frame-rate ceiling for the current heat level (THERMAL_STATUS_MODERATE = 2, SEVERE = 3, CRITICAL = 4 and up). */
    fun fpsCap(): Int = capFor(status)

    /** Resolution factor for the current heat level. */
    fun scaleFactor(): Float = factorFor(status)

    fun capFor(s: Int): Int = when {
        s >= 4 -> 12
        s >= 3 -> 20
        s >= 2 -> 30
        else -> 60
    }

    fun factorFor(s: Int): Float = when {
        s >= 4 -> 0.5f
        s >= 3 -> 0.7f
        s >= 2 -> 0.85f
        else -> 1f
    }
}

/**
 * Picks a resolution factor from how long frames really take. Pure logic (no Android classes), unit-tested.
 * The factor only takes values from a fixed ladder (1, 0.85, 0.72, 0.61 ... down to the minimum), because every new factor makes the
 * layer reallocate its buffers, which is a visible hitch of its own. Over 90% of the frame budget for ten frames in a row: one step
 * down. Under half the budget for 150 frames: one step up. A step up that is followed by a step down within ten seconds was too
 * eager, so the next step up waits twice as long (up to 1200 frames), and the wait resets after 3000 stable frames.
 * The minimum is a multiplier on the layer's scale; a minimum of 1 locks the resolution.
 */
class GlGovernor {
    var factor = 1f
        private set
    private var level = 0
    private var avg = 0.0
    private var slow = 0
    private var fast = 0
    private var upNeeded = UP_FRAMES
    private var sinceUp = NEVER

    fun frame(ms: Double, budgetMs: Double, minScale: Float) {
        val floor = minScale.coerceIn(0.1f, 1f)
        val last = lastLevel(floor)
        if (level > last) level = last
        if (sinceUp < NEVER) sinceUp++
        if (sinceUp >= STABLE_FRAMES) upNeeded = UP_FRAMES
        avg = if (avg == 0.0) ms else avg * 0.9 + ms * 0.1
        when {
            avg > budgetMs * 0.9 -> {
                slow++
                fast = 0
            }
            avg < budgetMs * 0.5 -> {
                fast++
                slow = 0
            }
            else -> {
                slow = 0
                fast = 0
            }
        }
        if (slow >= 10) {
            slow = 0
            if (level < last) {
                level++
                avg = 0.0
                if (sinceUp < FLIP_FRAMES) upNeeded = min(upNeeded * 2, MAX_UP_FRAMES)
            }
        } else if (fast >= upNeeded && level > 0) {
            fast = 0
            level--
            sinceUp = 0
            avg = 0.0
        }
        factor = factorAt(level, floor)
    }

    fun reset() {
        factor = 1f
        level = 0
        avg = 0.0
        slow = 0
        fast = 0
        upNeeded = UP_FRAMES
        sinceUp = NEVER
    }

    private fun factorAt(level: Int, floor: Float): Float {
        var f = 1f
        repeat(level) { f *= STEP }
        return max(floor, f)
    }

    /** The lowest ladder step for this minimum (0 when the minimum is 1: the resolution never drops). */
    private fun lastLevel(floor: Float): Int {
        var l = 0
        var f = 1f
        while (f > floor && l < MAX_LEVEL) {
            f *= STEP
            l++
        }
        return l
    }

    private companion object {
        const val STEP = 0.85f
        const val MAX_LEVEL = 16
        const val UP_FRAMES = 150
        const val MAX_UP_FRAMES = 1200
        const val FLIP_FRAMES = 600
        const val STABLE_FRAMES = 3000
        const val NEVER = 1_000_000
    }
}

/**
 * Compiled shader programs on disk (OpenGL ES 3.0 program binaries). A heavy shader then links in a few milliseconds on the next
 * launch instead of freezing the first frames. The key includes the driver, so an updated GPU driver never loads a stale binary.
 * File format: 4 bytes binaryFormat, then the binary.
 */
object GlProgramCache {
    private const val MAX_FILES = 48
    val hits = AtomicInteger()
    val stores = AtomicInteger()

    fun key(driver: String, vert: String, frag: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(driver.toByteArray())
        md.update(0)
        md.update(vert.toByteArray())
        md.update(0)
        md.update(frag.toByteArray())
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun file(dir: File, key: String) = File(File(dir, "glbin"), "$key.bin")

    fun load(dir: File, key: String): Pair<Int, ByteArray>? = try {
        val f = file(dir, key)
        if (!f.isFile || f.length() < 8) {
            null
        } else {
            java.io.DataInputStream(f.inputStream().buffered()).use { s ->
                val fmt = s.readInt()
                val bytes = s.readBytes()
                f.setLastModified(System.currentTimeMillis())
                fmt to bytes
            }
        }
    } catch (e: Exception) {
        null
    }

    fun store(dir: File, key: String, format: Int, bytes: ByteArray) {
        try {
            val d = File(dir, "glbin")
            d.mkdirs()
            val tmp = File(d, "$key.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(format)
                s.write(bytes)
            }
            val dst = file(dir, key)
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return
            }
            stores.incrementAndGet()
            val all = d.listFiles { x -> x.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
            if (all.size > MAX_FILES) all.take(all.size - MAX_FILES).forEach { it.delete() }
        } catch (e: Exception) {
            // the cache is only an optimisation
        }
    }
}
