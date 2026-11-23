package com.expressive.keyboard

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Static frosted glass: the picture is blurred once on a small copy (about 160 px) and then only drawn stretched,
 * so it costs nothing per frame and works on every Android version, unlike the live window/RenderEffect blur.
 */
internal fun frostImage(src: ImageBitmap, radiusDp: Float): ImageBitmap {
    val original = src.asAndroidBitmap()
    val soft = if (original.config == Bitmap.Config.HARDWARE) original.copy(Bitmap.Config.ARGB_8888, false) ?: return src else original
    val scale = (160f / max(soft.width, soft.height)).coerceAtMost(1f)
    val w = max(1, (soft.width * scale).roundToInt())
    val h = max(1, (soft.height * scale).roundToInt())
    val small = Bitmap.createScaledBitmap(soft, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val r = (radiusDp / 24f * 0.07f * max(w, h)).roundToInt().coerceIn(1, 14)
    val tmp = IntArray(px.size)
    repeat(2) {
        blurPass(px, tmp, w, h, r, true)
        blurPass(tmp, px, w, h, r, false)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out.asImageBitmap()
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val count = if (horizontal) h else w
    val div = 2 * r + 1
    for (line in 0 until count) {
        var a = 0
        var rr = 0
        var g = 0
        var b = 0
        for (i in -r..r) {
            val p = src[at(line, i.coerceIn(0, len - 1), w, horizontal)]
            a += p ushr 24
            rr += (p shr 16) and 255
            g += (p shr 8) and 255
            b += p and 255
        }
        for (i in 0 until len) {
            dst[at(line, i, w, horizontal)] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
            val add = src[at(line, (i + r + 1).coerceAtMost(len - 1), w, horizontal)]
            val rem = src[at(line, (i - r).coerceAtLeast(0), w, horizontal)]
            a += (add ushr 24) - (rem ushr 24)
            rr += ((add shr 16) and 255) - ((rem shr 16) and 255)
            g += ((add shr 8) and 255) - ((rem shr 8) and 255)
            b += (add and 255) - (rem and 255)
        }
    }
}

private fun at(line: Int, i: Int, w: Int, horizontal: Boolean) = if (horizontal) line * w + i else i * w + line
