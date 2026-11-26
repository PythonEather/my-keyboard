@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// ЖУРНАЛ ИЗМЕНЕНИЙ ПЛАГИНА
// Долго живущие плагины копят историю версий прямо в описании, и карточка в списке становилась бесконечной.
// Теперь описание остаётся коротким, а история уходит на отдельную страницу-«статью»: последняя версия
// раскрыта, старые свёрнуты. История берётся из поля манифеста "changelog" или из описания (раздел
// «Changelog» / «Что нового» либо подряд идущие заголовки версий).
// ============================================================================

@androidx.compose.runtime.Immutable
data class ChangelogEntry(val version: String, val date: String, val notes: List<String>)

internal object PluginChangelog {
    private val marker = Regex(
        """^\s*(?:#{1,4}\s*)?(?:changelog|change\s*log|changes|what'?s\s+new|release\s+notes|history|что\s+нового|история\s+изменений|список\s+изменений|изменения)\s*:?\s*$""",
        RegexOption.IGNORE_CASE
    )
    // "v1.2", "1.2.3 - 2026-11-01", "## Version 2.0 (Nov 2026)", "[1.4]"
    private val header = Regex(
        """^\s*(?:#{1,4}\s*)?[\[(]?(?:v|ver\.?|version\s*)?(\d+(?:\.\d+)+[\w.+\-]*)[\])]?\s*(?:[-–—:|(]\s*(.*?)\)?)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val bullet = Regex("""^\s*(?:[-*•·]|\d+[.)])\s+""")

    /** Splits a description into (short text, history). The history is empty when nothing looks like one. */
    fun split(description: String): Pair<String, List<ChangelogEntry>> {
        if (description.isBlank()) return "" to emptyList()
        val lines = description.replace("\r", "").lines()
        val m = lines.indexOfFirst { marker.matches(it) }
        if (m >= 0) {
            val entries = parseEntries(lines.drop(m + 1))
            val intro = lines.take(m).joinToString("\n").trim()
            if (entries.isNotEmpty()) return intro to entries
            // раздел без версий — одна запись «Изменения»
            val body = lines.drop(m + 1).map { clean(it) }.filter { it.isNotBlank() }
            return if (body.isEmpty()) intro to emptyList() else intro to listOf(ChangelogEntry("", "", body))
        }
        val firstHeader = lines.indexOfFirst { header.matches(it) && it.isNotBlank() }
        if (firstHeader >= 0 && lines.count { header.matches(it) } >= 2) {
            val entries = parseEntries(lines.drop(firstHeader))
            if (entries.size >= 2) return lines.take(firstHeader).joinToString("\n").trim() to entries
        }
        return description.trim() to emptyList()
    }

    private fun clean(line: String) = line.replace(bullet, "").trim()

    private fun parseEntries(lines: List<String>): List<ChangelogEntry> {
        val out = ArrayList<ChangelogEntry>()
        var version: String? = null
        var date = ""
        var notes = ArrayList<String>()
        fun flush() {
            val v = version
            if (v != null && (notes.isNotEmpty() || v.isNotEmpty())) out += ChangelogEntry(v, date, notes.toList())
            version = null; date = ""; notes = ArrayList()
        }
        for (raw in lines) {
            if (raw.isBlank()) continue
            val h = header.matchEntire(raw)
            if (h != null && !bullet.containsMatchIn(raw)) {
                flush()
                version = h.groupValues[1]
                date = h.groupValues[2].trim()
            } else {
                if (version == null) version = ""
                val t = clean(raw)
                if (t.isNotEmpty()) notes += t
            }
        }
        flush()
        return out.take(200)
    }

    /** Manifest "changelog": a text, a list of lines, or a list of {version, date, notes}. */
    fun fromJson(v: Any?): List<ChangelogEntry> = when (v) {
        is String -> split("Changelog\n$v").second
        is org.json.JSONArray -> buildList {
            for (i in 0 until v.length()) {
                val o = v.opt(i)
                if (o is org.json.JSONObject) {
                    val n = o.opt("notes") ?: o.opt("changes")
                    val notes = when (n) {
                        is org.json.JSONArray -> (0 until n.length()).map { n.optString(it).trim() }.filter { it.isNotEmpty() }
                        is String -> n.lines().map { clean(it) }.filter { it.isNotEmpty() }
                        else -> emptyList()
                    }
                    add(ChangelogEntry(o.optString("version").take(30), o.optString("date").take(30), notes.take(60)))
                } else if (o is String && o.isNotBlank()) {
                    add(ChangelogEntry("", "", listOf(o.trim())))
                }
            }
        }.take(200)
        else -> emptyList()
    }
}

/** Short "what's new" teaser inside the plugin card: the latest version, three lines, and a button to the full page. */
@Composable
internal fun ChangelogTeaser(p: KeyboardPlugin, entries: List<ChangelogEntry>) {
    val cs = MaterialTheme.colorScheme
    var open by remember(p.id) { mutableStateOf(false) }
    val latest = entries.first()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.secondaryContainer.copy(alpha = 0.55f))
            .clickable { open = true }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Text(
                if (latest.version.isNotEmpty()) tr("Что нового в v{0}", latest.version) else tr("Что нового"),
                style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f)
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        latest.notes.take(3).forEach {
            Text("•  $it", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            tr("Весь журнал ({0})", entries.size), style = MaterialTheme.typography.labelLarge, color = cs.primary
        )
    }
    if (open) ChangelogSheet(p, entries) { open = false }
}

/** The changelog "article": latest version open, older ones folded; a header with the plugin and the number of versions. */
@Composable
internal fun ChangelogSheet(p: KeyboardPlugin, entries: List<ChangelogEntry>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // индексы раскрытых записей; по умолчанию — только самая новая
    var openSet by remember(p.id) { mutableStateOf(setOf(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surfaceContainerLow) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(tr("Журнал изменений"), style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
                    Text(
                        listOf(tr(p.name), "v${p.version}", tr("Версий: {0}", entries.size)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                    )
                    if (entries.size > 1) {
                        val allOpen = openSet.size == entries.size
                        TextButton(
                            onClick = { openSet = if (allOpen) setOf(0) else entries.indices.toSet() },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) { Text(tr(if (allOpen) "Свернуть всё" else "Развернуть всё")) }
                    }
                }
            }
            itemsIndexed(entries, key = { i, e -> "$i:${e.version}" }) { i, e ->
                val isOpen = i in openSet
                val turn by animateFloatAsState(if (isOpen) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "clTurn")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(if (i == 0) cs.primaryContainer.copy(alpha = 0.5f) else cs.surfaceContainerHigh)
                        .clickable { openSet = if (isOpen) openSet - i else openSet + i }
                        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (e.version.isNotEmpty()) {
                            Box(Modifier.clip(CircleShape).background(if (i == 0) cs.primary else cs.secondaryContainer).padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Text("v${e.version}", style = MaterialTheme.typography.labelLarge, color = if (i == 0) cs.onPrimary else cs.onSecondaryContainer)
                            }
                        }
                        if (i == 0) Text(tr("Последняя"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        Text(e.date, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn })
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(10.dp))
                        e.notes.forEach { n ->
                            Text("•  $n", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    } else if (e.notes.isNotEmpty()) {
                        Text(e.notes.first(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
