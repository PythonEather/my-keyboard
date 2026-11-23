package com.expressive.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Finished previews, kept between visits to the page. Count-based: at most 48 pictures. */
internal object PreviewBake {
    private val cache = android.util.LruCache<Any, ImageBitmap>(48)

    fun get(key: Any): ImageBitmap? = cache.get(key)

    fun put(key: Any, image: ImageBitmap) {
        cache.put(key, image)
    }
}

/**
 * Draws [content] live the first time, then turns it into one picture and keeps only that.
 * A scrolling gallery of keyboards then costs one textured rectangle per card instead of ~30 keys with
 * blurred shadows each. [content] must call `onDrawn` once its real picture is on screen; if capturing the
 * picture fails for any reason the live drawing simply stays, as before.
 */
@Composable
internal fun BakedPreview(key: Any, modifier: Modifier, content: @Composable (onDrawn: () -> Unit) -> Unit) {
    var baked by remember(key) { mutableStateOf(PreviewBake.get(key)) }
    val image = baked
    if (image != null) {
        Canvas(modifier) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium
            )
        }
        return
    }
    val layer = rememberGraphicsLayer()
    var drawn by remember(key) { mutableStateOf(false) }
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    ) { content { drawn = true } }
    LaunchedEffect(key, drawn) {
        if (!drawn) return@LaunchedEffect
        // let the real picture reach the screen first, then capture it
        withFrameNanos { }
        withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull()
        if (shot != null && shot.width > 1 && shot.height > 1) {
            PreviewBake.put(key, shot)
            baked = shot
        }
    }
}
