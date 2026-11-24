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
// 12. ПЛАГИНЫ
// Плагин — это JSON-файл (.json), который можно импортировать в настройках.
// Код плагины не исполняют: только декларативные действия (вставка по шаблону,
// преобразование текста, системные клавиши, HTTP-запрос с разрешения "network"),
// сокращения (автозамена по пробелу), наборы символов, темы, оформление (skin),
// любые настройки, свои раскладки и варианты при удержании. Формат — PLUGINS.md.
// ============================================================================

enum class PluginActionType { INSERT, TRANSFORM, KEY, HTTP }

@Immutable
data class PluginAction(
    val title: String,
    val icon: String,
    val type: PluginActionType,
    val text: String = "",
    val transform: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val key: String = "",
    val scope: String = "auto",           // auto (выделение → слово) | word | text | selection
    val url: String = "",
    val method: String = "GET",
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val jsonPath: String = "",
    val output: String = "replace",       // replace | insert
    val pinned: Boolean = false           // кнопка прямо на панели инструментов
)

/** Настройка мода из блока "config": видна в карточке мода, скрипт читает её через kb.config(id). */
@Immutable
data class ModOption(
    val id: String,
    val title: String,
    /** toggle | text | textarea | choice | slider | color | image — значения; preset | button | info — без значения. */
    val type: String,
    val default: Any?,
    val description: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    /** Вкладка и раздел, в которых лежит настройка; пусто — без них. */
    val tab: String = "",
    val section: String = "",
    val sectionCollapsed: Boolean = false,
    /** Показывать, только если: "id", "!id" или "id=значение". */
    val showIf: String = "",
    /** Что задаёт пресет. */
    val values: Map<String, Any?> = emptyMap(),
    val hint: String = ""
) {
    val hasValue: Boolean get() = type !in NO_VALUE

    companion object {
        val NO_VALUE = setOf("preset", "button", "info")
    }
}

/** Значения настроек модов: свой файл настроек на мод. */
object ModConfig {
    private fun prefs(context: Context, id: String) =
        context.applicationContext.getSharedPreferences("plugin_config_" + id.replace(Regex("[^A-Za-z0-9._-]"), "_"), Context.MODE_PRIVATE)

    fun get(context: Context, plugin: KeyboardPlugin, o: ModOption): Any? {
        val all = prefs(context, plugin.id).all
        return normalize(o, all[o.id]) ?: o.default
    }

    /** Увеличивается при любой смене значений, в том числе из скрипта: карточка мода показывает новые. */
    var revision by mutableIntStateOf(0)
        private set

    fun all(context: Context, plugin: KeyboardPlugin): Map<String, Any?> =
        plugin.config.filter { it.hasValue }.associate { it.id to get(context, plugin, it) }

    /** Значение под тип настройки: скрипт мог передать 1 в переключатель или строку в ползунок. */
    internal fun normalize(o: ModOption, v: Any?): Any? = when {
        v == null -> null
        o.type == "toggle" -> when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            else -> v.toString().trim().lowercase() in setOf("true", "1", "on", "yes")
        }
        o.type == "slider" -> when (v) {
            is Number -> v.toFloat()
            is Boolean -> if (v) o.max else o.min
            else -> v.toString().trim().replace(',', '.').toFloatOrNull()
        }?.takeIf { !it.isNaN() }?.coerceIn(minOf(o.min, o.max), maxOf(o.min, o.max))
        else -> v.toString().take(MAX_TEXT)
    }

    private const val MAX_TEXT = 256 * 1024

    private fun put(e: android.content.SharedPreferences.Editor, id: String, v: Any?) {
        when (v) {
            null -> e.remove(id)
            is Boolean -> e.putBoolean(id, v)
            is Number -> e.putFloat(id, v.toFloat())
            else -> e.putString(id, v.toString())
        }
    }

    /** Кнопка настроек: мод получает событие option, значение не хранится. */
    fun press(plugin: KeyboardPlugin, id: String) = ScriptHost.optionChanged(plugin.id, id, true)

    /** Пресет: несколько значений сразу. Возвращает, сколько настроек изменилось. */
    fun setAll(context: Context, plugin: KeyboardPlugin, values: Map<String, Any?>): Int {
        val changed = LinkedHashMap<String, Any?>()
        val e = prefs(context, plugin.id).edit()
        for ((k, raw) in values) {
            val o = plugin.config.firstOrNull { it.id == k && it.hasValue } ?: continue
            val v = normalize(o, raw) ?: continue
            if (v != get(context, plugin, o)) {
                put(e, k, v)
                changed[k] = v
            }
        }
        if (changed.isEmpty()) return 0
        e.apply()
        revision++
        changed.forEach { (k, v) -> ScriptHost.optionChanged(plugin.id, k, v) }
        return changed.size
    }

    fun set(context: Context, plugin: KeyboardPlugin, id: String, value: Any?) {
        val o = plugin.config.firstOrNull { it.id == id }
        val v = if (o != null) normalize(o, value) else value
        // то же значение — без события: иначе мод, который в "option" снова вызывает setConfig, зациклится
        if (o != null && value != null && v == get(context, plugin, o)) return
        prefs(context, plugin.id).edit().also { put(it, id, v) }.apply()
        revision++
        ScriptHost.optionChanged(plugin.id, id, v)
    }
}

@Immutable
data class SymbolPack(val title: String, val icon: String, val items: List<String>)

@Immutable
data class PluginTheme(
    val seed: Int? = null,
    val mode: String? = null,
    val shape: String? = null,
    val keyOpacity: Float? = null,
    val colors: Map<String, Int> = emptyMap()
)

enum class PluginScriptEngine { RHINO, QUICKJS }

@Immutable
data class KeyboardPlugin(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val icon: String,
    val permissions: Set<String>,
    val actions: List<PluginAction>,
    val shortcuts: Map<String, String>,
    val symbols: List<SymbolPack>,
    val theme: PluginTheme?,
    val builtIn: Boolean,
    val skin: KeyboardSkin? = null,
    val settings: Map<String, Any> = emptyMap(),
    val layouts: Map<String, List<KeyRow>> = emptyMap(),
    val variants: Map<String, List<String>> = emptyMap(),
    /** JavaScript мода (см. ScriptEngine.kt). */
    val script: String? = null,
    /** Файлы из архива .zip: путь → base64. Скрипт читает их через kb.asset(). */
    val files: Map<String, String> = emptyMap(),
    /** Замена иконок: слот → эмодзи/текст или data:image/…;base64. */
    val icons: Map<String, String> = emptyMap(),
    val languages: List<KeyboardLanguage> = emptyList(),
    /** Дополнительные слова для подсказок: язык → слова. */
    val dictionary: Map<String, List<String>> = emptyMap(),
    val enabledByDefault: Boolean = true,
    /** Исходный JSON оформления: из него собираются варианты по настройкам темы. */
    val skinJson: String? = null,
    /** Свои настройки мода (блок "config"). */
    val config: List<ModOption> = emptyList(),
    val scriptEngine: PluginScriptEngine = PluginScriptEngine.RHINO,
    /** Сколько мс даётся одному вызову скрипта (манифест: "callBudgetMs", 300..2000). */
    val callBudgetMs: Int = 300,
    /**
     * Свой экран мода (манифест: "display"): "fullscreen" — на весь экран, "floating" — плавающая панель,
     * "landscape" / "landscape-right" — на весь экран в альбомной ориентации (на портретном экране картинка повёрнута на 90°).
     */
    val display: String? = null,
    /** Arranges the Settings home screen (manifest "settingsLayout", needs the "settings_layout" permission). */
    val settingsLayout: SettingsLayoutSpec? = null,
    /** Arranges the keyboard toolbar (manifest "toolbarLayout", needs the "toolbar_layout" permission). */
    val toolbarLayout: ToolbarLayoutSpec? = null
) {
    val usesNetwork: Boolean get() = "network" in permissions && (actions.any { it.type == PluginActionType.HTTP } || script != null)
    /** Есть что применить кнопкой «Применить оформление». */
    val hasLook: Boolean get() = theme != null || skin != null || settings.isNotEmpty()
    val summary: String
        get() = buildList {
            if (script != null) add(tr("мод (скрипт)"))
            if (actions.isNotEmpty()) add(tr("{0} действ.", actions.size))
            if (languages.isNotEmpty()) add(tr("языки: {0}", languages.joinToString(", ") { it.id }))
            if (icons.isNotEmpty()) add(tr("{0} иконок", icons.size))
            if (dictionary.isNotEmpty()) add(tr("словарь"))
            if (shortcuts.isNotEmpty()) add(tr("{0} сокращ.", shortcuts.size))
            if (symbols.isNotEmpty()) add(tr("{0} символов", symbols.sumOf { it.items.size }))
            if (skin != null) add(tr("оформление"))
            if (theme != null) add(tr("тема"))
            if (settings.isNotEmpty()) add(tr("{0} настр.", settings.size))
            if (layouts.isNotEmpty()) add(tr("раскладки: {0}", layouts.keys.joinToString(", ")))
            if (variants.isNotEmpty()) add(tr("{0} вариантов", variants.size))
        }.joinToString(" · ")
}

object PluginParser {
    private val ID = Regex("^[A-Za-z0-9._-]{2,64}$")
    val TRANSFORMS = setOf(
        "upper", "lower", "title", "sentence", "reverse", "mock", "wrap", "regex", "trim",
        "sort_lines", "unique_lines", "base64_encode", "base64_decode", "url_encode", "url_decode",
        "translit", "layout_swap", "strike", "spaced", "calc", "camel", "snake", "kebab", "bold", "italic",
        "superscript"
    )
    val LAYOUT_IDS = setOf("symbols", "symbols2")
    private val LANG_ID = Regex("^[a-z]{2,3}([-_][a-z0-9]{2,8})?$")
    val PERMISSIONS = setOf("network", "read_text", "clipboard", "launch", "ai", "sensors", "microphone", "app_info", "screen", "media", "settings_layout", "toolbar_layout")
    val KEYS = setOf(
        "tab", "enter", "left", "right", "up", "down", "home", "end", "select_all", "copy", "cut",
        "paste", "undo", "redo", "delete_word", "escape"
    )
    val COLOR_KEYS = setOf("background", "key", "keyText", "func", "funcText", "accent", "accentText")

    fun parseColor(s: String?): Int? =
        if (s.isNullOrBlank()) null else runCatching { AndroidColor.parseColor(s.trim()) }.getOrNull()

    fun parse(raw: String, builtIn: Boolean): KeyboardPlugin {
        val o = JSONObject(raw)
        val id = o.optString("id").trim()
        require(ID.matches(id)) { "Field id: 2–64 characters from A-Z, a-z, 0-9, . _ -" }
        val name = o.optString("name").trim()
        require(name.isNotEmpty()) { "The name field is required" }
        val perms = o.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

        val actions = ArrayList<PluginAction>()
        o.optJSONArray("actions")?.let { arr ->
            require(arr.length() <= 64) { "At most 64 actions" }
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val title = a.optString("title").trim()
                require(title.isNotEmpty()) { "Action #${i + 1}: the title field is required" }
                val type = when (a.optString("type").lowercase()) {
                    "insert" -> PluginActionType.INSERT
                    "transform" -> PluginActionType.TRANSFORM
                    "key" -> PluginActionType.KEY
                    "http" -> PluginActionType.HTTP
                    else -> throw IllegalArgumentException("Action \"$title\": type = insert | transform | key | http")
                }
                val transform = a.optString("transform").lowercase()
                if (type == PluginActionType.TRANSFORM) require(transform in TRANSFORMS) { "\"$title\": unknown transform $transform" }
                if (transform == "regex") runCatching { Regex(a.optString("pattern")) }.getOrElse {
                    throw IllegalArgumentException("\"$title\": invalid regular expression")
                }
                val key = a.optString("key").lowercase()
                if (type == PluginActionType.KEY) require(key in KEYS) { "\"$title\": unknown key $key" }
                val url = a.optString("url").trim()
                if (type == PluginActionType.HTTP) {
                    require("network" in perms) { "\"$title\": http needs \"permissions\": [\"network\"]" }
                    require(url.startsWith("https://")) { "\"$title\": url must start with https://" }
                }
                val headers = HashMap<String, String>()
                a.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
                actions += PluginAction(
                    title = title.take(40),
                    icon = iconSpec(a.optString("icon").ifBlank { defaultIcon(type) }),
                    type = type,
                    text = a.optString("text"),
                    transform = transform,
                    prefix = a.optString("prefix"),
                    suffix = a.optString("suffix"),
                    pattern = a.optString("pattern"),
                    replacement = a.optString("replacement"),
                    key = key,
                    scope = a.optString("scope", "auto").lowercase().let { if (it in setOf("auto", "word", "text", "selection")) it else "auto" },
                    url = url,
                    method = a.optString("method", "GET").uppercase().let { if (it == "POST") "POST" else "GET" },
                    body = a.optString("body"),
                    headers = headers,
                    jsonPath = a.optString("jsonPath"),
                    output = if (a.optString("output") == "insert") "insert" else "replace",
                    pinned = a.optBoolean("pinned", false)
                )
            }
        }

        val shortcuts = LinkedHashMap<String, String>()
        o.optJSONObject("shortcuts")?.let { s ->
            s.keys().forEach { k ->
                val trigger = k.trim()
                if (trigger.isNotEmpty() && trigger.none { it.isWhitespace() } && shortcuts.size < 500) shortcuts[trigger] = s.optString(k)
            }
        }

        val symbols = ArrayList<SymbolPack>()
        o.optJSONArray("symbols")?.let { arr ->
            for (i in 0 until min(arr.length(), 20)) {
                val p = arr.getJSONObject(i)
                val items = p.optJSONArray("items")?.let { a -> (0 until min(a.length(), 2000)).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                if (items.isNotEmpty()) symbols += SymbolPack(p.optString("title", "Символы").take(30), iconSpec(p.optString("icon", items.first())), items)
            }
        }

        val theme = o.optJSONObject("theme")?.let { t ->
            val colors = HashMap<String, Int>()
            t.optJSONObject("colors")?.let { c -> c.keys().forEach { k -> if (k in COLOR_KEYS) parseColor(c.optString(k))?.let { colors[k] = it } } }
            PluginTheme(
                seed = parseColor(t.optString("seed")),
                mode = t.optString("mode").uppercase().takeIf { it in setOf("SYSTEM", "LIGHT", "DARK", "AMOLED") },
                shape = t.optString("shape").takeIf { s -> ExpressiveShapePreset.entries.any { it.id == s } },
                keyOpacity = if (t.has("keyOpacity")) t.optDouble("keyOpacity").toFloat().coerceIn(0.2f, 1f) else null,
                colors = colors
            )
        }

        val skin = o.optJSONObject("skin")?.let { runCatching { SkinParser.parse(it) }.getOrElse { e -> throw IllegalArgumentException("skin: ${e.message}") } }

        val settings = LinkedHashMap<String, Any>()
        o.optJSONObject("settings")?.let { s ->
            s.keys().forEach { k ->
                require(k in KeyboardPrefs.KEY_TYPES) { "settings: unknown key \"$k\"" }
                s.opt(k)?.takeIf { it != JSONObject.NULL }?.let { settings[k] = it }
            }
        }

        val layouts = LinkedHashMap<String, List<KeyRow>>()
        o.optJSONObject("layouts")?.let { l ->
            l.keys().forEach { lid ->
                require(lid in LAYOUT_IDS || LANG_ID.matches(lid)) { "layouts: \"$lid\" must be a language code (ru, en, de…), symbols or symbols2" }
                val rows = l.getJSONArray(lid)
                require(rows.length() in 1..8) { "layouts.$lid: from 1 to 8 rows" }
                layouts[lid] = (0 until rows.length()).map { KeyboardLayouts.parseRow(rows.getJSONArray(it), lid !in LAYOUT_IDS, id) }
                    .filter { it.keys.isNotEmpty() }
            }
        }

        val script = when (val s = o.opt("script")) {
            is String -> s
            is JSONArray -> (0 until s.length()).joinToString("\n") { s.optString(it) }
            else -> null
        }?.takeIf { it.isNotBlank() }
        require((script?.length ?: 0) <= 2_000_000) { "script: larger than 2 MB" }

        val files = HashMap<String, String>()
        o.optJSONObject("files")?.let { f -> f.keys().forEach { k -> files[k] = f.optString(k) } }

        val icons = LinkedHashMap<String, String>()
        o.optJSONObject("icons")?.let { ic ->
            ic.keys().forEach { k ->
                val v = ic.optString(k)
                // путь к картинке внутри архива превращается в data URI
                icons[k] = files[v]?.takeIf { !it.startsWith(MEDIA_MARK) }?.let { data -> "data:image/${v.substringAfterLast('.', "png")};base64,$data" } ?: v
            }
        }

        val languages = ArrayList<KeyboardLanguage>()
        o.optJSONArray("languages")?.let { arr ->
            for (i in 0 until min(arr.length(), 20)) languages += Languages.parse(arr.getJSONObject(i), id)
        }

        val dictionary = HashMap<String, List<String>>()
        o.optJSONObject("dictionary")?.let { d ->
            d.keys().forEach { k ->
                d.optJSONArray(k)?.let { a -> dictionary[k] = (0 until min(a.length(), 100_000)).map { a.optString(it) }.filter { it.isNotBlank() } }
            }
        }

        val variants = HashMap<String, List<String>>()
        o.optJSONObject("variants")?.let { v ->
            v.keys().forEach { k ->
                val a = v.optJSONArray(k) ?: return@forEach
                val items = (0 until min(a.length(), 12)).map { a.optString(it) }.filter { it.isNotEmpty() }
                if (k.isNotEmpty() && items.isNotEmpty()) variants[k.lowercase()] = items
            }
        }

        require(
            actions.isNotEmpty() || shortcuts.isNotEmpty() || symbols.isNotEmpty() || theme != null || skin != null ||
                settings.isNotEmpty() || layouts.isNotEmpty() || variants.isNotEmpty() || script != null ||
                icons.isNotEmpty() || languages.isNotEmpty() || dictionary.isNotEmpty()
        ) {
            "The plugin is empty: it needs script, actions, shortcuts, symbols, theme, skin, settings, layouts, variants, icons, languages or dictionary"
        }
        val scriptEngine = when (o.optString("scriptEngine", "rhino")) {
            "rhino" -> PluginScriptEngine.RHINO
            "quickjs" -> PluginScriptEngine.QUICKJS
            else -> throw IllegalArgumentException("scriptEngine: expected rhino or quickjs")
        }
        val callBudget = o.optInt("callBudgetMs", 300).coerceIn(300, 2000)
        val display = o.optString("display", "").trim()
        require(display.isEmpty() || display == "fullscreen" || display == "floating" || display == "landscape" || display == "landscape-right") {
            "display: expected fullscreen, landscape, landscape-right or floating"
        }
        return KeyboardPlugin(
            id = id,
            name = name.take(40),
            version = o.optString("version", "1.0"),
            author = o.optString("author"),
            description = o.optString("description"),
            icon = iconSpec(o.optString("icon", "@extension").ifBlank { "@extension" }),
            permissions = perms,
            actions = actions,
            shortcuts = shortcuts,
            symbols = symbols,
            theme = theme,
            builtIn = builtIn,
            skin = skin,
            settings = settings,
            layouts = layouts,
            variants = variants,
            script = script,
            scriptEngine = scriptEngine,
            callBudgetMs = callBudget,
            display = display.ifEmpty { null },
            files = files,
            icons = icons,
            languages = languages,
            dictionary = dictionary,
            enabledByDefault = o.optBoolean("enabledByDefault", true),
            skinJson = o.optJSONObject("skin")?.toString(),
            config = parseConfig(o.optJSONArray("config")),
            settingsLayout = PluginSettingsLayout.parse(o.optJSONObject("settingsLayout")),
            toolbarLayout = PluginToolbarLayout.parse(o.optJSONObject("toolbarLayout"), id)
        )
    }

    private const val MAX_OPTIONS = 300

    private fun parseConfig(a: JSONArray?): List<ModOption> {
        if (a == null) return emptyList()
        val out = ArrayList<ModOption>()
        val types = setOf("toggle", "text", "textarea", "choice", "slider", "color", "image", "preset", "button", "info")
        var tab = ""
        var section = ""
        var collapsed = false
        var auto = 0
        for (i in 0 until a.length()) {
            if (out.size >= MAX_OPTIONS) break
            val c = a.optJSONObject(i) ?: continue
            val rawType = c.optString("type", "toggle").lowercase()
            // вкладка и раздел действуют на всё, что идёт за ними
            if (rawType == "tab") {
                tab = c.optString("title").take(40)
                section = ""
                collapsed = false
                continue
            }
            if (rawType == "section") {
                section = c.optString("title").take(60)
                collapsed = c.optBoolean("collapsed", false)
                continue
            }
            val type = rawType.takeIf { it in types } ?: "toggle"
            val noValue = type in ModOption.NO_VALUE
            val oid = c.optString("id").takeIf { Regex("^[A-Za-z0-9_.-]{1,40}$").matches(it) }
                ?: if (noValue && type != "button") "_item${auto++}" else continue
            val choices = c.optJSONArray("choices")?.let { ch ->
                (0 until min(ch.length(), 100)).mapNotNull { j ->
                    when (val x = ch.opt(j)) {
                        is JSONObject -> x.optString("id").takeIf { it.isNotEmpty() }?.let { it to x.optString("title", it) }
                        is String -> x to x
                        else -> null
                    }
                }
            } ?: emptyList()
            val def: Any? = when (type) {
                "toggle" -> c.optBoolean("default", false)
                "slider" -> c.optDouble("default", c.optDouble("min", 0.0)).toFloat()
                "choice" -> c.optString("default", choices.firstOrNull()?.first.orEmpty())
                "preset", "button", "info" -> null
                else -> c.optString("default", "")
            }
            val values = LinkedHashMap<String, Any?>()
            c.optJSONObject("values")?.let { v -> v.keys().forEach { k -> v.opt(k)?.takeIf { it != JSONObject.NULL }?.let { x -> values[k] = x } } }
            out += ModOption(
                oid, c.optString("title", if (noValue) "" else oid).take(if (type == "info") 500 else 60), type, def,
                c.optString("description").take(500), choices,
                c.optDouble("min", 0.0).toFloat(), c.optDouble("max", 100.0).toFloat(), c.optDouble("step", 1.0).toFloat(),
                tab = c.optString("tab", tab).take(40),
                section = c.optString("section", section).take(60),
                sectionCollapsed = collapsed,
                showIf = c.optString("showIf").take(80),
                values = values,
                hint = c.optString("hint").take(200)
            )
        }
        return out
    }

    private val JS_MANIFEST = Regex("/\\*\\s*@plugin\\s*([\\s\\S]*?)\\*/")

    /**
     * Мод одним файлом .js: манифест — JSON в комментарии в начале файла.
     * ```
     * /* @plugin { "id": "my.mod", "name": "Мой мод", "permissions": ["read_text"] } */
     * kb.on("word", e => ...)
     * ```
     */
    fun jsToJson(source: String): String {
        val m = JS_MANIFEST.find(source) ?: throw IllegalArgumentException("A .js mod must start with a comment like /* @plugin { \"id\": …, \"name\": … } */")
        val o = JSONObject(m.groupValues[1].trim())
        o.put("script", source)
        return o.toString()
    }

    /** Архив .zip: plugin.json (или manifest.json) + main.js (или поле "main") + любые файлы (картинки, данные). */
    fun zipToJson(bytes: ByteArray): String {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').trimStart('/')
                require(!name.contains("..")) { "Invalid path in the archive: $name" }
                val data = zin.readBytes()
                total += data.size
                require(total <= 8L * 1024 * 1024) { "The archive is larger than 8 MB after unpacking" }
                entries[name] = data
            }
        }
        // если всё лежит в одной папке — убираем её из путей
        val prefix = entries.keys.map { it.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { p -> p.isNotEmpty() && entries.keys.all { it.startsWith("$p/") } }
        val files = if (prefix != null) entries.mapKeys { it.key.removePrefix("$prefix/") } else entries
        val manifest = (files["plugin.json"] ?: files["manifest.json"])?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("The archive has no plugin.json")
        val o = JSONObject(manifest.removePrefix("﻿"))
        val main = o.optString("main", "main.js")
        files[main]?.let { if (!o.has("script")) o.put("script", it.toString(Charsets.UTF_8)) }
        val f = JSONObject()
        for ((k, v) in files) if (k != "plugin.json" && k != "manifest.json" && k != main) f.put(k, android.util.Base64.encodeToString(v, android.util.Base64.NO_WRAP))
        if (f.length() > 0) o.put("files", f)
        return o.toString()
    }

    /** Метка в сохранённых «files» плагина: файл большой (музыка), он лежит настоящим файлом в папке плагина, а не внутри JSON. */
    const val MEDIA_MARK = "@media:"
    private const val BIG_FILE = 256 * 1024
    private const val MAX_MEDIA = 256L * 1024 * 1024
    private val AUDIO_EXT = setOf("mp3", "ogg", "oga", "opus", "wav", "m4a", "aac", "flac", "mid", "midi")

    /** Папка больших файлов плагина (музыка из архива). Приставка «p_» не даёт id вроде «..» выйти из папки. */
    fun mediaDir(context: Context, pluginId: String): java.io.File =
        java.io.File(java.io.File(context.applicationContext.filesDir, "plugin_media"), "p_" + pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** Файл по значению с меткой MEDIA_MARK; null, если его уже нет. */
    fun mediaFile(context: Context, pluginId: String, mark: String): java.io.File? {
        val name = mark.removePrefix(MEDIA_MARK)
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        return java.io.File(mediaDir(context, pluginId), name).takeIf { it.isFile }
    }

    private fun readCapped(input: java.io.InputStream, limit: Long, message: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= limit) { message }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * То же, что zipToJson, но для большого архива на диске (до 256 МБ): архив целиком в память не читается.
     * Мелкие файлы идут в JSON base64, как раньше; большие и любое аудио копируются потоком в [mediaOut] и помечаются MEDIA_MARK.
     */
    fun zipFileToJson(zip: java.io.File, mediaOut: java.io.File): String {
        java.util.zip.ZipFile(zip).use { zf ->
            val all = ArrayList<java.util.zip.ZipEntry>()
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!e.isDirectory) all += e
            }
            fun clean(e: java.util.zip.ZipEntry): String {
                val name = e.name.replace('\\', '/').trimStart('/')
                require(!name.contains("..")) { "Invalid path in the archive: $name" }
                return name
            }
            val names = all.map { clean(it) }
            // если всё лежит в одной папке — убираем её из путей
            val prefix = names.map { it.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { p -> p.isNotEmpty() && names.all { it.startsWith("$p/") } }
            val byName = LinkedHashMap<String, java.util.zip.ZipEntry>()
            for (e in all) {
                val n = clean(e)
                byName[if (prefix != null) n.removePrefix("$prefix/") else n] = e
            }
            val manifestEntry = byName["plugin.json"] ?: byName["manifest.json"]
                ?: throw IllegalArgumentException("The archive has no plugin.json")
            val manifest = readCapped(zf.getInputStream(manifestEntry), 1L * 1024 * 1024, "plugin.json is larger than 1 MB").toString(Charsets.UTF_8)
            val o = JSONObject(manifest.removePrefix("\uFEFF"))
            val main = o.optString("main", "main.js")
            byName[main]?.let { e ->
                if (!o.has("script")) o.put("script", readCapped(zf.getInputStream(e), 8L * 1024 * 1024, "The script is larger than 8 MB").toString(Charsets.UTF_8))
            }
            val f = JSONObject()
            var small = 0L
            var media = 0L
            for ((name, e) in byName) {
                if (name == "plugin.json" || name == "manifest.json" || name == main) continue
                val big = e.size >= BIG_FILE || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
                if (!big) {
                    val data = readCapped(zf.getInputStream(e), 8L * 1024 * 1024 - small, "The small files of the archive are larger than 8 MB together")
                    small += data.size
                    f.put(name, android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                } else {
                    mediaOut.mkdirs()
                    val base = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "file" }
                    val disk = Integer.toHexString(name.hashCode()) + "_" + base
                    zf.getInputStream(e).use { input ->
                        java.io.File(mediaOut, disk).outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                media += n
                                require(media <= MAX_MEDIA) { "The big files of the archive are larger than 256 MB together" }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    f.put(name, MEDIA_MARK + disk)
                }
            }
            if (f.length() > 0) o.put("files", f)
            return o.toString()
        }
    }

    /** Эмодзи/текст обрезается до 8 символов; "@значок" и data:image — целиком. */
    fun iconSpec(s: String): String = if (s.startsWith("@") || s.startsWith("data:image")) s.take(400_000) else s.take(8)

    private fun defaultIcon(t: PluginActionType) = when (t) {
        PluginActionType.INSERT -> "@edit"
        PluginActionType.TRANSFORM -> "@magic"
        PluginActionType.KEY -> "@keyboard"
        PluginActionType.HTTP -> "@language"
    }
}

/** Подстановки в шаблонах: {date}, {date:yyyy-MM-dd}, {time}, {datetime}, {text}, {selection}, {clipboard}, {random:a|b}, {uuid}, {newline}. */
object PluginTemplates {
    private val VAR = Regex("\\{([a-z_]+)(?::([^}]*))?\\}")

    fun expand(template: String, text: String = "", clipboard: String = "", urlEncode: Boolean = false): String =
        VAR.replace(template) { m ->
            val arg = m.groupValues[2]
            val value = when (m.groupValues[1]) {
                "date" -> fmt(arg.ifEmpty { "dd.MM.yyyy" })
                "time" -> fmt(arg.ifEmpty { "HH:mm" })
                "datetime" -> fmt(arg.ifEmpty { "dd.MM.yyyy HH:mm" })
                "text", "selection" -> text
                "clipboard" -> clipboard
                "random" -> arg.split('|').filter { it.isNotEmpty() }.randomOrNull().orEmpty()
                "uuid" -> java.util.UUID.randomUUID().toString()
                "newline" -> "\n"
                else -> return@replace m.value
            }
            if (urlEncode) URLEncoder.encode(value, "UTF-8") else value
        }

    private fun fmt(pattern: String): String =
        runCatching { java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date()) }.getOrDefault(pattern)
}

object PluginTransforms {
    private const val EN_LAYOUT = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~"
    private const val RU_LAYOUT = "йцукенгшщзхъфывапролджэячсмитьбюёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ"
    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    fun apply(a: PluginAction, input: String): String = when (a.transform) {
        "upper" -> input.uppercase()
        "lower" -> input.lowercase()
        "title" -> input.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        "sentence" -> input.lowercase().replaceFirstChar { it.uppercase() }
        "reverse" -> input.reversed()
        "mock" -> {
            var up = false
            input.map { c -> if (c.isLetter()) { up = !up; if (up) c.lowercaseChar() else c.uppercaseChar() } else c }.joinToString("")
        }
        "wrap" -> a.prefix + input + a.suffix
        "regex" -> runCatching { input.replace(Regex(a.pattern), a.replacement) }.getOrDefault(input)
        "trim" -> input.trim().replace(Regex("[ \\t]+"), " ")
        "sort_lines" -> input.lines().sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n")
        "unique_lines" -> input.lines().distinct().joinToString("\n")
        "base64_encode" -> android.util.Base64.encodeToString(input.toByteArray(), android.util.Base64.NO_WRAP)
        "base64_decode" -> runCatching { String(android.util.Base64.decode(input.trim(), android.util.Base64.DEFAULT)) }.getOrDefault(input)
        "url_encode" -> URLEncoder.encode(input, "UTF-8")
        "url_decode" -> runCatching { java.net.URLDecoder.decode(input, "UTF-8") }.getOrDefault(input)
        "translit" -> buildString {
            for (c in input) {
                val t = translit[c.lowercaseChar()]
                when {
                    t == null -> append(c)
                    c.isUpperCase() -> append(t.replaceFirstChar { it.uppercase() })
                    else -> append(t)
                }
            }
        }
        "layout_swap" -> {
            val cyr = input.count { it in RU_LAYOUT }
            val lat = input.count { it in EN_LAYOUT }
            val (from, to) = if (lat >= cyr) EN_LAYOUT to RU_LAYOUT else RU_LAYOUT to EN_LAYOUT
            input.map { c -> from.indexOf(c).let { i -> if (i >= 0 && i < to.length) to[i] else c } }.joinToString("")
        }
        "strike" -> input.map { if (it.isWhitespace()) "$it" else "$it̶" }.joinToString("")
        "spaced" -> input.toList().joinToString(" ")
        "calc" -> {
            val expr = input.trim().removeSuffix("=").trim()
            Calc.eval(expr)?.let { "$expr = ${Calc.format(it)}" } ?: input
        }
        "camel" -> words(input).mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() } }.joinToString("")
        "snake" -> words(input).joinToString("_") { it.lowercase() }
        "kebab" -> words(input).joinToString("-") { it.lowercase() }
        "bold" -> mathAlpha(input, 0x1D5D4, 0x1D5EE, 0x1D7EC)
        "italic" -> mathAlpha(input, 0x1D608, 0x1D622, null)
        "superscript" -> input.map { SUPER[it] ?: it }.joinToString("")
        else -> input
    }

    private fun words(s: String): List<String> =
        s.replace(Regex("([a-zа-яё])([A-ZА-ЯЁ])"), "$1 $2").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Латиница и цифры → «математические» буквы Unicode (жирный/курсив без форматирования). */
    private fun mathAlpha(s: String, upper: Int, lower: Int, digits: Int?): String = buildString {
        for (c in s) when (c) {
            in 'A'..'Z' -> appendCodePoint(upper + (c - 'A'))
            in 'a'..'z' -> appendCodePoint(lower + (c - 'a'))
            in '0'..'9' -> if (digits != null) appendCodePoint(digits + (c - '0')) else append(c)
            else -> append(c)
        }
    }

    private val SUPER = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸',
        '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )
}

/** Калькулятор для преобразования "calc": + − × ÷ ^ %, скобки, запятая как десятичный разделитель. */
object Calc {
    const val EDITOR_LOOKBACK = 161
    private val editorExpression = Regex("""(?<![\p{L}\p{N}.,])([0-9.,+−×÷*/^%()\-][0-9.,+−×÷*/^%() \t\-]*)=$""")

    fun resultAfterEquals(before: CharSequence): String? {
        if (before.length !in 1..EDITOR_LOOKBACK || before.last() != '=') return null
        val match = editorExpression.find(before) ?: return null
        if (match.range.first == 0 && before.length >= EDITOR_LOOKBACK) return null
        val expression = match.groupValues[1].trim()
        if (expression.none { it in "+-−×÷*/^%" }) return null
        return eval(expression.replace("\t", ""))?.takeIf(Double::isFinite)?.let(::format)
    }

    fun eval(src: String): Double? {
        val s = src.replace(',', '.').replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "")
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        lateinit var expr: () -> Double
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            '(' -> { i++; val v = expr(); require(peek() == ')'); i++; v }
            else -> number()
        }
        fun power(): Double {
            var v = unary()
            while (peek() == '%') { i++; v /= 100.0 }
            if (peek() == '^') { i++; v = Math.pow(v, power()) }
            return v
        }
        fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                v = if (op == '*') v * power() else v / power()
            }
            return v
        }
        expr = {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                v = if (op == '+') v + term() else v - term()
            }
            v
        }
        return runCatching { expr().takeIf { i == s.length && !it.isNaN() } }.getOrNull()
    }

    fun format(v: Double): String =
        if (v.isInfinite()) "∞"
        else if (v == Math.rint(v) && abs(v) < 1e15) v.toLong().toString()
        else java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
}

object PluginHttp {
    private fun extract(json: String, path: String): String {
        if (path.isBlank()) return json.trim()
        var node: Any? = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
        for (seg in path.split('.')) {
            node = when (val n = node) {
                is JSONObject -> n.opt(seg)
                is JSONArray -> seg.toIntOrNull()?.let { n.opt(it) }
                else -> null
            } ?: throw IllegalStateException("The response has no \"$path\"")
        }
        return node.toString()
    }

    suspend fun run(a: PluginAction, text: String, clipboard: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = PluginTemplates.expand(a.url, text, clipboard, urlEncode = true)
            require(url.startsWith("https://")) { "Only https requests are allowed" }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = a.method
                conn.setRequestProperty("User-Agent", "ExpressiveKeyboard-Plugin")
                a.headers.forEach { (k, v) -> conn.setRequestProperty(k, PluginTemplates.expand(v, text, clipboard)) }
                if (a.method == "POST") {
                    conn.doOutput = true
                    if (a.headers.keys.none { it.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(PluginTemplates.expand(a.body, text, clipboard).toByteArray()) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("The server answered $code")
                val raw = conn.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(64 * 1024)
                    val n = r.read(buf)
                    if (n <= 0) "" else String(buf, 0, n)
                }
                extract(raw, a.jsonPath)
            } finally {
                conn.disconnect()
            }
        }
    }
}

object PluginRepository {
    private const val PREFS = "expressive_keyboard_plugins"
    private const val DIR = "plugins"
    private const val MAX_FILE = 8 * 1024 * 1024
    /** Архив .zip с музыкой: до 256 МБ, читается с диска потоком. Одиночный .json/.js остаётся до 8 МБ. */
    private const val MAX_ZIP = 256L * 1024 * 1024
    /** Встроенный мод, который подменяет «"» на «ёлочки». */
    private const val SMART_QUOTES_ID = "builtin.smart-quotes"
    /** Отметка о разовом выключении мода «Ёлочки» (см. reload). */
    private const val SMART_QUOTES_OFF = "smart_quotes_off"

    var plugins by mutableStateOf<List<KeyboardPlugin>>(emptyList())
        private set
    var disabled by mutableStateOf<Set<String>>(emptySet())
        private set
    var errors by mutableStateOf<List<String>>(emptyList())
        private set

    /** Активные плагины и их сокращения пересчитываются только при изменениях. */
    var active by mutableStateOf<List<KeyboardPlugin>>(emptyList())
        private set
    @Volatile var shortcuts: Map<String, String> = emptyMap()
        private set
    /** Есть ли сокращения не только из букв (":date", "<3") — тогда по пробелу читаем текст поля. */
    @Volatile var hasSymbolShortcuts = false
        private set
    /** Раскладки и варианты удержания из включённых плагинов (первый плагин побеждает). */
    var layouts by mutableStateOf<Map<String, List<KeyRow>>>(emptyMap())
        private set
    @Volatile var variants: Map<String, List<String>> = emptyMap()
        private set

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        reload(context)
    }

    fun reload(context: Context) {
        val app = context.applicationContext
        val errs = ArrayList<String>()
        val list = ArrayList<KeyboardPlugin>()
        runCatching { app.assets.list(DIR).orEmpty() }.getOrDefault(emptyArray()).sorted().forEach { f ->
            if (!f.endsWith(".json") && !f.endsWith(".js")) return@forEach
            runCatching {
                val raw = app.assets.open("$DIR/$f").bufferedReader().use { it.readText() }
                PluginParser.parse(if (f.endsWith(".js")) PluginParser.jsToJson(raw) else raw, true)
            }.onSuccess { list += it }.onFailure { errs += "$f: ${it.message}" }
        }
        val builtInIds = list.map { it.id }.toSet()
        File(app.filesDir, DIR).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            runCatching { PluginParser.parse(f.readText(), false) }
                .onSuccess { if (it.id !in builtInIds && list.none { p -> p.id == it.id }) list += it }
                .onFailure { errs += "${f.name}: ${it.message}" }
        }
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var dis = sp.getStringSet("disabled", emptySet())?.toSet() ?: emptySet()
        val edit = sp.edit()
        var changed = false
        // новые встроенные плагины с "enabledByDefault": false появляются выключенными
        val known = sp.getStringSet("known_builtins", emptySet())?.toSet() ?: emptySet()
        val fresh = list.filter { it.builtIn && it.id !in known }
        if (fresh.isNotEmpty()) {
            dis = dis + fresh.filter { !it.enabledByDefault }.map { it.id }
            edit.putStringSet("known_builtins", known + fresh.map { it.id })
            changed = true
        }
        // Разовая миграция: мод «Ёлочки «»» оказался включён у тех, кто его не выбирал, и нажатие «"»
        // вводило « вместо самой кавычки. Выключаем его один раз; кто включит вручную — тот и получит.
        if (!sp.getBoolean(SMART_QUOTES_OFF, false)) {
            dis = dis + SMART_QUOTES_ID
            edit.putBoolean(SMART_QUOTES_OFF, true)
            changed = true
        }
        if (changed) edit.putStringSet("disabled", dis).apply()
        disabled = dis
        plugins = list
        errors = errs
        recompute(app)
    }

    private fun recompute(context: Context) {
        val act = plugins.filter { it.id !in disabled }
        active = act
        Languages.extra = act.flatMap { it.languages }
        val words = HashMap<String, MutableList<String>>()
        for (p in act) for ((k, v) in p.dictionary) words.getOrPut(k) { ArrayList() } += v
        WordPredictorEngine.pluginWords = words
        val icons = LinkedHashMap<String, String>()
        for (p in act) for ((k, v) in p.icons) icons.putIfAbsent(k, v)
        IconStore.setManifestIcons(icons)
        IconStore.dropScripted(act.map { it.id }.toSet())
        ScriptHost.reload(context, act)
        val map = HashMap<String, String>()
        for (p in act) for ((k, v) in p.shortcuts) map.putIfAbsent(k.lowercase(), v)
        hasSymbolShortcuts = map.keys.any { k -> k.any { !it.isLetter() } }
        shortcuts = map
        val lay = HashMap<String, List<KeyRow>>()
        val vars = HashMap<String, List<String>>()
        for (p in act) {
            for ((k, v) in p.layouts) lay.putIfAbsent(k, v)
            for ((k, v) in p.variants) vars.putIfAbsent(k, v)
        }
        variants = vars
        layouts = lay
    }

    /** Оформление, выбранное в настройках, если его плагин включён (без настроек темы и акцента). */
    fun activeSkin(settings: KeyboardSettings): KeyboardSkin? = ScriptHost.skinOverride
        ?: if (settings.skinPlugin.isEmpty()) null else active.firstOrNull { it.id == settings.skinPlugin }?.skin

    /** Плагин выбранного оформления. */
    fun skinPlugin(settings: KeyboardSettings): KeyboardPlugin? =
        if (settings.skinPlugin.isEmpty()) null else active.firstOrNull { it.id == settings.skinPlugin && it.skin != null }

    /** Оформление для клавиатуры: светлое или тёмное, с настройками темы и в акцентном цвете пользователя. */
    fun resolvedSkin(settings: KeyboardSettings, dark: Boolean): KeyboardSkin? {
        val base = baseSkin(settings, dark)
        return if (settings.liquidGlass && android.os.Build.VERSION.SDK_INT >= 33) ThemeOptions.liquefy(base, settings) else base
    }

    private fun baseSkin(settings: KeyboardSettings, dark: Boolean): KeyboardSkin? {
        ScriptHost.skinOverride?.let { o ->
            val m = o.forMode(dark)
            return if (settings.userAccent != 0) ThemeOptions.recolor(m, androidx.compose.ui.graphics.Color(settings.userAccent), settings.colorAccentText) else m
        }
        val p = skinPlugin(settings) ?: return null
        return ThemeOptions.resolve(p, settings, dark)
    }

    /** Тема + настройки + оформление одной кнопкой. */
    fun applyLook(context: Context, p: KeyboardPlugin) {
        p.theme?.let { applyTheme(context, it) }
        if (p.settings.isNotEmpty()) KeyboardPrefs.applyPluginSettings(context, p.settings)
        if (p.skin != null) KeyboardPrefs.edit(context) { putString("skin_plugin", p.id) }
    }

    fun removeSkin(context: Context) = KeyboardPrefs.edit(context) { putString("skin_plugin", "") }

    fun isEnabled(id: String) = id !in disabled

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        disabled = if (enabled) disabled - id else disabled + id
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("disabled", disabled).apply()
        recompute(context.applicationContext)
    }

    fun importFrom(context: Context, uri: Uri): Result<KeyboardPlugin> = runCatching {
        val app = context.applicationContext
        // сначала файл на диск потоком: большой архив с музыкой не должен целиком лечь в память
        val tmp = File.createTempFile("plugin_import", ".tmp", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_ZIP) { "The file is larger than 256 MB" }
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalStateException("Could not open the file")
            installFile(app, tmp)
        } finally {
            tmp.delete()
        }
    }

    /** A plugin file on disk (a .zip, a .json or a .js mod) -> installed plugin. */
    private fun installFile(app: Context, tmp: File): KeyboardPlugin {
        // .zip начинается с «PK»
        val isZip = tmp.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        return if (isZip) importZip(app, tmp)
        else {
            require(tmp.length() <= MAX_FILE) { "The file is larger than 8 MB (a plugin with music goes into a .zip)" }
            importText(app, tmp.readText()).getOrThrow()
        }
    }

    // ------------------------------------------------------------ install and update from a link

    /** What an install from a link did: a new plugin, a newer version over the installed one, or nothing because the link has no newer version. */
    class LinkResult(val plugin: KeyboardPlugin?, val id: String, val version: String, val kind: Kind, val previous: String?) {
        enum class Kind { INSTALLED, UPDATED, UP_TO_DATE }
    }

    private fun linkPrefs(app: Context) = app.getSharedPreferences("plugin_sources", Context.MODE_PRIVATE)

    /** Plugin id -> the link it was installed from. */
    fun linkSources(context: Context): Map<String, String> =
        linkPrefs(context.applicationContext).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Id and version of a downloaded plugin file, read without installing it. */
    private fun peek(tmp: File): Pair<String, String> {
        val isZip = tmp.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        val manifest = if (isZip) {
            java.util.zip.ZipFile(tmp).use { z ->
                val e = z.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.endsWith("plugin.json") || it.name.endsWith("manifest.json")) }
                    .minByOrNull { it.name.length } ?: throw IllegalArgumentException("The .zip has no plugin.json")
                z.getInputStream(e).bufferedReader().use { it.readText() }
            }
        } else {
            require(tmp.length() <= MAX_FILE) { "The file is larger than 8 MB (a plugin with music goes into a .zip)" }
            val clean = tmp.readText().removePrefix("\uFEFF").trim()
            if (clean.startsWith("{")) clean else PluginParser.jsToJson(clean)
        }
        val o = JSONObject(manifest)
        return o.optString("id").trim() to o.optString("version", "").trim()
    }

    /**
     * Downloads a plugin from an https link (a GitHub page address is turned into the raw file address) and installs it.
     * If a plugin with the same id is already installed, it is replaced only when the link has a newer version (or force is true).
     * The link is remembered, so checkLinkUpdates can look for new versions later.
     */
    fun installFromUrl(context: Context, rawUrl: String, force: Boolean = false): Result<LinkResult> = runCatching {
        val app = context.applicationContext
        val url = PluginLink.normalize(rawUrl)
        val tmp = File.createTempFile("plugin_link", ".tmp", app.cacheDir)
        try {
            PluginLink.download(url, tmp, MAX_ZIP)
            val (id, version) = peek(tmp)
            require(id.isNotBlank()) { "The file has no plugin id" }
            val have = plugins.firstOrNull { it.id == id && !it.builtIn }
            if (have != null && !force && !PluginLink.isNewer(version, have.version)) {
                linkPrefs(app).edit().putString(id, url).apply()
                return@runCatching LinkResult(have, id, version, LinkResult.Kind.UP_TO_DATE, have.version)
            }
            val plugin = installFile(app, tmp)
            linkPrefs(app).edit().putString(plugin.id, url).apply()
            LinkResult(plugin, plugin.id, plugin.version, if (have != null) LinkResult.Kind.UPDATED else LinkResult.Kind.INSTALLED, have?.version)
        } finally {
            tmp.delete()
        }
    }

    /** Looks at the link of every plugin that was installed from one and installs the newer versions. */
    fun checkLinkUpdates(context: Context): List<Pair<String, Result<LinkResult>>> =
        linkSources(context).filter { (id, _) -> plugins.any { it.id == id && !it.builtIn } }.map { (id, url) -> id to installFromUrl(context, url) }

    /** Архив: большие файлы (музыка) потоком в папку плагина, остальное — в JSON плагина, как раньше. */
    private fun importZip(app: Context, zip: File): KeyboardPlugin {
        val staging = File(app.filesDir, "plugin_media_new")
        staging.deleteRecursively()
        try {
            val json = PluginParser.zipFileToJson(zip, staging)
            val parsed = PluginParser.parse(json, false)
            require(plugins.none { it.builtIn && it.id == parsed.id }) { "id \"${parsed.id}\" is taken by a built-in plugin" }
            // музыка встаёт на место до запуска плагина: скрипт может играть её сразу при загрузке
            val finalDir = PluginParser.mediaDir(app, parsed.id)
            finalDir.deleteRecursively()
            if (staging.listFiles().isNullOrEmpty()) staging.deleteRecursively()
            else {
                finalDir.parentFile?.mkdirs()
                require(staging.renameTo(finalDir)) { "Could not save the media files of the plugin" }
            }
            return importText(app, json).getOrElse { e ->
                finalDir.deleteRecursively()
                throw e
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** JSON плагина или исходник мода .js с манифестом в комментарии /* @plugin {…} */. */
    fun importText(context: Context, raw: String): Result<KeyboardPlugin> = runCatching {
        val app = context.applicationContext
        val clean = raw.removePrefix("﻿").trim()
        val json = if (clean.startsWith("{")) clean else PluginParser.jsToJson(clean)
        val plugin = PluginParser.parse(json, false)
        require(plugins.none { it.builtIn && it.id == plugin.id }) { "id \"${plugin.id}\" is taken by a built-in plugin" }
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        File(dir, "${plugin.id}.json").writeText(json)
        if (plugin.id in disabled) setEnabled(app, plugin.id, true)
        reload(app)
        plugin
    }

    fun delete(context: Context, id: String) {
        val app = context.applicationContext
        File(File(app.filesDir, DIR), "$id.json").delete()
        PluginParser.mediaDir(app, id).deleteRecursively()
        UserPictures.dir(app, id).deleteRecursively()   // pictures chosen in the plugin's `image` settings
        linkPrefs(app).edit().remove(id).apply()
        reload(app)
    }

    private val SCRIPT_HEAD = Regex("^\\s*/\\*\\s*@plugin")

    /**
     * The plugin exactly as it is stored: the installed JSON for imported plugins (scripts and zip files included),
     * the bundled file for built-in ones. Returns the file name and its text, or null if nothing is found.
     * Importing this text again restores the plugin, even after the original file is gone.
     */
    fun exportSource(context: Context, p: KeyboardPlugin): Pair<String, String>? {
        val app = context.applicationContext
        if (!p.builtIn) {
            val stored = File(File(app.filesDir, DIR), "${p.id}.json")
            if (stored.exists()) return runCatching { "${p.id}.json" to stored.readText() }.getOrNull()
        }
        val names = runCatching { app.assets.list(DIR).orEmpty() }.getOrDefault(emptyArray()).sorted()
        for (f in names) {
            if (!f.endsWith(".json") && !f.endsWith(".js")) continue
            val raw = runCatching { app.assets.open("$DIR/$f").bufferedReader().use { it.readText() } }.getOrNull() ?: continue
            val id = runCatching { PluginParser.parse(if (f.endsWith(".js")) PluginParser.jsToJson(raw) else raw, true).id }.getOrNull()
            if (id == p.id) return f to raw
        }
        return null
    }

    /** Script source ready to edit and import again as a .js mod: the manifest comment is added if the mod was installed from JSON. */
    fun exportScript(context: Context, p: KeyboardPlugin): String? {
        val script = p.script ?: return null
        if (SCRIPT_HEAD.containsMatchIn(script)) return script
        val stored = exportSource(context, p)?.second?.removePrefix("\uFEFF")?.trim()
        if (stored == null || !stored.startsWith("{")) return script
        val manifest = runCatching {
            JSONObject(stored).apply { remove("script"); remove("files"); remove("main") }.toString(2)
        }.getOrNull() ?: return script
        return "/* @plugin " + manifest + " */\n" + script
    }

    fun applyTheme(context: Context, t: PluginTheme) {
        val keys = mapOf(
            "background" to "color_background", "key" to "color_key", "keyText" to "color_key_text",
            "func" to "color_func", "funcText" to "color_func_text", "accent" to "color_accent", "accentText" to "color_accent_text"
        )
        KeyboardPrefs.edit(context) {
            t.seed?.let { putString("color_source", "SEED"); putInt("seed_color", it) }
            t.mode?.let { putString("theme_mode", it) }
            t.keyOpacity?.let { putFloat("key_opacity", it) }
            t.shape?.let { id ->
                val p = ExpressiveShapePreset.fromId(id)
                putString("accent_shape_preset", p.id)
                putInt("wave_count", p.defaultWaves)
                putFloat("wave_amplitude_percent", p.defaultAmplitude)
            }
            for ((k, pref) in keys) putInt(pref, t.colors[k] ?: 0)
        }
    }

    const val EXAMPLE = """{
  "id": "my.first.plugin",
  "name": "My plugin",
  "version": "1.0",
  "author": "Me",
  "description": "Example: actions, shortcuts, symbols, theme, skin, settings, variants and layout",
  "icon": "@rocket",
  "actions": [
    { "title": "Signature", "icon": "@note", "type": "insert", "text": "Best regards,{newline}John", "pinned": true },
    { "title": "Calculate", "icon": "@calc", "type": "transform", "transform": "calc" },
    { "title": "Today", "icon": "@date", "type": "insert", "text": "{date:d MMMM yyyy}" },
    { "title": "Quote it", "icon": "“”", "type": "transform", "transform": "wrap", "prefix": "“", "suffix": "”" },
    { "title": "CAPS", "icon": "@case", "type": "transform", "transform": "upper" },
    { "title": "Select all", "icon": "▣", "type": "key", "key": "select_all" }
  ],
  "shortcuts": {
    "ty": "thank you",
    "omg": "oh my god",
    ":date": "{date}"
  },
  "symbols": [
    { "title": "Arrows", "icon": "→", "items": ["←", "↑", "→", "↓", "↔", "⇄", "⇒", "↩"] }
  ],
  "theme": {
    "seed": "#FF7A3D",
    "mode": "DARK",
    "shape": "FLOWER_6",
    "colors": { "accent": "#FF7A3D", "accentText": "#2B1100" }
  },
  "settings": { "key_height_dp": 56, "show_number_row": true },
  "variants": { "e": ["é", "è", "ê", "ë"] },
  "skin": {
    "keyboard": {
      "background": { "colors": ["#2B1100", "#5A1E00"], "angle": 120 },
      "orbs": [ { "color": "#FF7A3D", "x": 0.2, "y": 0.4, "r": 0.4 } ]
    },
    "keys": {
      "*": { "material": "glass", "text": "#FFFFFF", "press": "jelly" },
      "enter": { "background": { "colors": ["#FF7A3D", "#FF3D7A"], "angle": 135 } },
      "space": { "shape": "pill", "label": "space" },
      "key:a": { "material": "solid", "background": "#FF7A3D", "text": "#2B1100", "glow": "#FF7A3D" }
    },
    "chips": { "material": "glass", "text": "#FFFFFF" }
  }
}"""
}
