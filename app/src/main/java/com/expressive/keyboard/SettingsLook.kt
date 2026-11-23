package com.expressive.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
// Settings look: a wallpaper behind every settings page with static frosted glass,
// and "important" slots on the home screen that the user pins and drags into order.
// ============================================================================

/** True while a wallpaper is shown behind Settings: cards turn into translucent glass. */
internal val LocalSettingsGlass = compositionLocalOf { false }

/** A card colour that lets the wallpaper shine through when Settings has one. */
@Composable
internal fun glassSurface(color: Color): Color = if (LocalSettingsGlass.current) color.copy(alpha = 0.66f) else color

/** Page background: plain while there is no wallpaper, see-through with one. */
@Composable
internal fun settingsPageBg(): Color =
    if (LocalSettingsGlass.current) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer

/** Keeps the content composed but takes no space and draws nothing (used while the search results are shown). */
internal fun Modifier.collapsedIf(on: Boolean): Modifier =
    if (!on) this else this.layout { _, _ -> layout(0, 0) {} }

internal object SettingsWallpaper {
    private const val FILE = "settings_wallpaper.jpg"
    private const val MAX_EDGE = 1080

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** The picture is scaled down to about 1080 px and re-compressed, so the wallpaper stays small. */
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > MAX_EDGE) decoder.setTargetSize(
                    (info.size.width * (MAX_EDGE.toFloat() / edge)).toInt().coerceAtLeast(1),
                    (info.size.height * (MAX_EDGE.toFloat() / edge)).toInt().coerceAtLeast(1)
                )
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxSide <= 0) return false
            var sample = 1
            while (maxSide / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        }
        val target = android.util.AtomicFile(file(context))
        try {
            val stream = target.startWrite()
            try {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        } finally {
            bmp.recycle()
        }
        true
    }.getOrDefault(false)

    fun remove(context: Context) {
        file(context).delete()
    }

    /**
     * level 0 — the sharp picture; 1..3 — static frosted glass: blurred once on a small copy and then only drawn
     * stretched, so it costs nothing per frame and needs no Android 12 window blur.
     */
    fun load(context: Context, level: Int): ImageBitmap? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val image = bmp.asImageBitmap()
        if (level <= 0) image else frostImage(image, when (level) { 1 -> 8f; 2 -> 16f; else -> 28f })
    }.getOrNull()
}

/** The wallpaper for the current settings, decoded and frosted off the main thread. Null — no wallpaper. */
@Composable
internal fun rememberSettingsWallpaper(settings: KeyboardSettings): ImageBitmap? {
    val context = LocalContext.current
    val version = settings.settingsWallpaper
    val level = settings.settingsGlass
    val state = produceState<ImageBitmap?>(null, version, level) {
        value = if (version == 0L) null else withContext(Dispatchers.Default) { SettingsWallpaper.load(context, level) }
    }
    return if (version == 0L) null else state.value
}

@Composable
internal fun SettingsWallpaperLayer(image: ImageBitmap, modifier: Modifier) {
    // a light scrim in the page colour keeps text readable on bright pictures
    val scrim = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.32f)
    Canvas(modifier) {
        drawKeyboardPhoto(image, size, PhotoFrame(), 1f)
        drawRect(scrim)
    }
}

/** Wallpaper picker and frost strength: the last section of the Settings home. */
@Composable
internal fun HomeLookSection(settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { SettingsWallpaper.import(context, uri) }
            if (ok) KeyboardPrefs.edit(context) { putLong("settings_wallpaper", System.currentTimeMillis()) }
            else Toast.makeText(context, tr("Не удалось открыть картинку"), Toast.LENGTH_LONG).show()
        }
    }
    SettingsSection("This screen") {
        ActionItem(
            Icons.Rounded.Wallpaper, "Wallpaper behind Settings",
            if (settings.settingsWallpaper != 0L) "Tap to pick another picture" else "Pick a picture; the cards turn into frosted glass",
            onClick = { picker.launch("image/*") }
        )
        if (settings.settingsWallpaper != 0L) {
            ChoiceItem(
                Icons.Rounded.BlurOn, "Frosted glass", "The picture is blurred once, so scrolling stays smooth",
                listOf("0" to "Off", "1" to "Light", "2" to "Medium", "3" to "Strong"), settings.settingsGlass.toString()
            ) { v -> KeyboardPrefs.edit(context) { putInt("settings_glass", v.toIntOrNull() ?: 2) } }
            ActionItem(Icons.Rounded.DeleteOutline, "Remove wallpaper", null, onClick = {
                SettingsWallpaper.remove(context)
                KeyboardPrefs.edit(context) { putLong("settings_wallpaper", 0L) }
            })
        }
    }
}

// ----------------------------------------------------------------------- important slots

internal object HomePins {
    private val NOT_PINNABLE = setOf(SettingsPage.HOME, SettingsPage.THEME_EDITOR)

    fun parse(raw: String): List<SettingsPage> = raw.split(',')
        .mapNotNull { n -> SettingsPage.entries.firstOrNull { it.name == n.trim() } }
        .filter { it !in NOT_PINNABLE }
        .distinct()

    fun candidates(): List<SettingsPage> = SettingsPage.entries.filter { it !in NOT_PINNABLE }

    fun save(context: Context, pages: List<SettingsPage>) =
        KeyboardPrefs.edit(context) { putString("home_pins", pages.joinToString(",") { it.name }) }
}

/**
 * Edit mode of the "Important" slots: pinned pages in a list that is re-ordered by dragging the handle,
 * a star un-pins a page, and every other page below can be pinned with its star.
 */
@Composable
internal fun PinEditor(settings: KeyboardSettings, onDone: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val saved = remember(settings.homePins) { HomePins.parse(settings.homePins) }
    var order by remember(saved) { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf<SettingsPage?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(0f) }

    SettingsSection("Important") {
        if (order.isEmpty()) {
            GroupItem {
                Text(
                    tr("Nothing pinned yet. Tap a star below to put a page here."),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                )
            }
        }
        for (p in order) key(p) {
            val lifted = dragging == p
            Box(Modifier.graphicsLayer {
                translationY = if (lifted) dragDy else 0f
                shadowElevation = if (lifted) 12f else 0f
            }) {
                GroupItem {
                    Row(
                        Modifier.fillMaxWidth().onSizeChanged { rowH = it.height.toFloat() }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.weight(1f)) { ItemTitle(p.icon, tr(p.title), null) }
                        IconButton(onClick = {
                            val n = order - p
                            order = n
                            HomePins.save(context, n)
                        }) { Icon(Icons.Rounded.Star, tr("Unpin"), tint = cs.primary) }
                        Box(
                            Modifier.size(48.dp).pointerInput(p) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = p
                                        dragDy = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragDy += amount.y
                                        val cur = order.indexOf(p)
                                        if (dragDy > rowH / 2f && cur in 0 until order.lastIndex) {
                                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur + 1) }
                                            dragDy -= rowH
                                        } else if (dragDy < -rowH / 2f && cur > 0) {
                                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur - 1) }
                                            dragDy += rowH
                                        }
                                    },
                                    onDragEnd = {
                                        HomePins.save(context, order)
                                        dragging = null
                                        dragDy = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragDy = 0f
                                    }
                                )
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.DragIndicator, tr("Drag to reorder"), tint = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    val rest = HomePins.candidates().filter { it !in order }
    if (rest.isNotEmpty()) SettingsSection("Other pages") {
        for (p in rest) {
            ActionItem(
                p.icon, tr(p.title), null,
                onClick = {
                    val n = order + p
                    order = n
                    HomePins.save(context, n)
                },
                trailing = { Icon(Icons.Rounded.StarBorder, tr("Pin"), tint = cs.onSurfaceVariant) }
            )
        }
    }
    SettingsSection(null) { ActionItem(Icons.Rounded.Check, "Done", null, onClick = onDone) }
}
