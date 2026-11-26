package com.expressive.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ============================================================================
// АНИМАЦИИ
// Нажатие клавиши описывается одной «формулой»: на сколько клавиша сжимается по ширине и высоте,
// сдвигается, поворачивается и тускнеет, и насколько упруго возвращается. Готовые анимации — такие же
// формулы, свои пользователь собирает ползунками. Отдельно — скорость всех анимаций, всплывающая
// подсказка над клавишей и переходы между панелями.
// ============================================================================

data class PressAnim(
    val id: String,
    val title: String,
    val scaleX: Float = 0.94f,
    val scaleY: Float = 0.94f,
    /** Сдвиг по вертикали, dp: плюс — вниз (вдавливание), минус — вверх (подпрыгивание). */
    val lift: Float = 0f,
    val rotate: Float = 0f,
    val alpha: Float = 1f,
    /** Упругость возврата: 0.2 — долго качается, 1 — без колебаний. */
    val damping: Float = 0.5f,
    /** Скорость возврата (жёсткость пружины). */
    val stiffness: Float = 600f,
    val custom: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("sx", scaleX.toDouble()).put("sy", scaleY.toDouble())
        .put("lift", lift.toDouble()).put("rot", rotate.toDouble()).put("alpha", alpha.toDouble())
        .put("damping", damping.toDouble()).put("stiffness", stiffness.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = PressAnim(
            id = o.optString("id"), title = o.optString("title", "Custom"),
            scaleX = o.optDouble("sx", 0.94).toFloat().coerceIn(0.5f, 1.5f),
            scaleY = o.optDouble("sy", 0.94).toFloat().coerceIn(0.5f, 1.5f),
            lift = o.optDouble("lift", 0.0).toFloat().coerceIn(-16f, 16f),
            rotate = o.optDouble("rot", 0.0).toFloat().coerceIn(-45f, 45f),
            alpha = o.optDouble("alpha", 1.0).toFloat().coerceIn(0.1f, 1f),
            damping = o.optDouble("damping", 0.5).toFloat().coerceIn(0.15f, 1f),
            stiffness = o.optDouble("stiffness", 600.0).toFloat().coerceIn(100f, 3000f),
            custom = true
        )
    }
}

object Anims {
    val presets = listOf(
        // возврат короткий: каждый кадр анимации — перерисовка всей клавиатуры, долгие покачивания грели телефон
        PressAnim("scale", "Сжатие", 0.94f, 0.94f, damping = 0.8f, stiffness = 1400f),
        PressAnim("jelly", "Желе", 1.07f, 0.93f, damping = 0.45f, stiffness = 1100f),
        PressAnim("bounce", "Пружина", 1.07f, 1.07f, damping = 0.55f, stiffness = 1200f),
        PressAnim("sink", "Вдавливание", 0.98f, 0.98f, lift = 2.5f, damping = 0.85f, stiffness = 1400f),
        PressAnim("pop", "Прыжок", 1.1f, 1.1f, lift = -5f, damping = 0.5f, stiffness = 1000f),
        PressAnim("squish", "Расплющивание", 1.12f, 0.82f, lift = 2f, damping = 0.5f, stiffness = 1000f),
        PressAnim("tilt", "Наклон", 0.95f, 0.95f, rotate = -6f, damping = 0.55f, stiffness = 1000f),
        PressAnim("fade", "Затухание", 0.97f, 0.97f, alpha = 0.5f, damping = 1f, stiffness = 1200f),
        PressAnim("none", "Без анимации", 1f, 1f, damping = 1f, stiffness = 1500f)
    )

    /** Анимация из темы — та же формула, что у готовых. */
    fun fromEffect(e: PressEffect?, pressScale: Float?): PressAnim = when (e) {
        PressEffect.JELLY -> presets[1]
        PressEffect.BOUNCE -> presets[2].let { if (pressScale != null) it.copy(scaleX = pressScale, scaleY = pressScale) else it }
        PressEffect.SINK -> presets[3]
        PressEffect.NONE -> presets.last()
        else -> presets[0].let { if (pressScale != null) it.copy(scaleX = pressScale, scaleY = pressScale) else it }
    }

    @Volatile private var parsedRaw = ""
    @Volatile private var parsed: List<PressAnim> = emptyList()

    fun custom(settings: KeyboardSettings): List<PressAnim> {
        val raw = settings.customAnims
        if (raw != parsedRaw) {
            parsed = runCatching {
                val a = JSONArray(raw.ifBlank { "[]" })
                (0 until a.length()).map { PressAnim.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            parsedRaw = raw
        }
        return parsed
    }

    fun all(settings: KeyboardSettings): List<PressAnim> = cached(settings)

    /** Готовый список и карта id → анимация: forKey вызывается для каждой клавиши, а список собирался заново. */
    @Volatile private var allRaw = ""
    @Volatile private var allCached: List<PressAnim> = presets
    @Volatile private var byId: Map<String, PressAnim> = presets.associateBy { it.id }

    private fun cached(settings: KeyboardSettings): List<PressAnim> {
        val raw = settings.customAnims
        if (raw != allRaw) {
            val list = presets + custom(settings)
            val map = HashMap<String, PressAnim>(list.size * 2)
            // при совпадении id побеждает первый, как и раньше в firstOrNull
            list.forEach { map.putIfAbsent(it.id, it) }
            byId = map
            allCached = list
            allRaw = raw
        }
        return allCached
    }

    /** Анимация нажатия для клавиши: выбранная в настройках или из темы. */
    fun forKey(settings: KeyboardSettings, style: KeyStyle?): PressAnim {
        cached(settings)
        val id = settings.animPress
        if (id != "THEME") byId[id]?.let { return it }
        return fromEffect(style?.press, style?.pressScale)
    }

    fun saveCustom(context: android.content.Context, settings: KeyboardSettings, anim: PressAnim?, removeId: String? = null) {
        val list = custom(settings).filter { it.id != (anim?.id ?: removeId) }.toMutableList()
        if (anim != null) list += anim.copy(custom = true)
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        KeyboardPrefs.edit(context) {
            putString("custom_anims", a.toString())
            if (anim != null) putString("anim_press", anim.id)
            else if (settings.animPress == removeId) putString("anim_press", "THEME")
        }
    }

    private const val FILE_FORMAT = "expressive-keyboard-animations"
    private val POPUP_STYLES = setOf("POP", "ZOOM", "SLIDE", "FADE", "NONE")

    /**
     * An animation pack as a shareable JSON file (version 2): your custom press animations plus the chosen press and
     * popup animation, the speed, and the panel and keyboard-open transitions. Version 1 files (only "animations") still import.
     */
    fun exportJson(settings: KeyboardSettings): String {
        val a = JSONArray()
        custom(settings).forEach { a.put(it.toJson()) }
        val panel = if (settings.panelAnimations) Transitions.panel(settings) else TransitionAnim.preset("NONE")
        return JSONObject()
            .put("format", FILE_FORMAT).put("version", 2)
            .put("animations", a)
            .put("press", settings.animPress)
            .put("popup", settings.animPopup)
            .put("speed", settings.animSpeed.toDouble())
            .put("panel", panel.toJson())
            .put("open", Transitions.open(settings).toJson())
            .toString(2)
    }

    /**
     * Imports a pack: a file made by exportJson, a bare array of animations, or a single animation object.
     * Presets keep their ids: an imported animation with a preset id gets a new one. Same custom id replaces the old animation.
     * A version 2 pack also applies its press, popup, speed, panel and open settings.
     * Returns how many animations and settings were added or updated.
     */
    fun importJson(context: Context, settings: KeyboardSettings, raw: String): Result<Int> = runCatching {
        val text = raw.removePrefix("\uFEFF").trim()
        val top: JSONObject? = if (text.startsWith("[")) null else JSONObject(text)
        val isPack = top != null && listOf("panel", "open", "popup", "press", "speed").any { top.has(it) }
        val arr: JSONArray = when {
            top == null -> JSONArray(text)
            top.has("animations") -> top.getJSONArray("animations")
            top.has("sx") || top.has("sy") || top.has("title") -> JSONArray().put(top)
            isPack -> JSONArray()
            else -> throw IllegalArgumentException("No animations found in this file")
        }
        require(arr.length() <= 100) { "Expected between 1 and 100 animations" }
        require(arr.length() > 0 || isPack) { "Expected between 1 and 100 animations" }
        val presetIds = presets.map { it.id }.toSet()
        val list = custom(settings).toMutableList()
        val stamp = System.currentTimeMillis()
        var added = 0
        // старый id из файла → id, под которым анимация сохранилась (у готовых он меняется)
        val idMap = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val a = PressAnim.fromJson(o)
            val id = if (a.id.isBlank() || a.id in presetIds) "custom.$stamp.$i" else a.id
            if (a.id.isNotBlank()) idMap[a.id] = id
            list.removeAll { it.id == id }
            list += a.copy(id = id, title = a.title.take(24).ifBlank { "My animation" }, custom = true)
            added++
        }
        require(added > 0 || isPack) { "No animations found in this file" }
        val out = JSONArray()
        list.forEach { out.put(it.toJson()) }
        val known = presetIds + list.map { it.id } + "THEME"
        val press = top?.optString("press", "")?.takeIf { it.isNotBlank() }?.let { idMap[it] ?: it }?.takeIf { it in known }
        val popup = top?.optString("popup", "")?.uppercase()?.takeIf { it in POPUP_STYLES }
        val speed = top?.takeIf { it.has("speed") }?.optDouble("speed", 1.0)?.toFloat()?.coerceIn(0.5f, 2f)
        val panel = top?.optJSONObject("panel")?.let { TransitionAnim.fromJson(it) }
        val open = top?.optJSONObject("open")?.let { TransitionAnim.fromJson(it) }
        var applied = 0
        KeyboardPrefs.edit(context) {
            putString("custom_anims", out.toString())
            if (press != null) { putString("anim_press", press); applied++ }
            if (popup != null) { putString("anim_popup", popup); applied++ }
            if (speed != null) { putFloat("anim_speed", speed); applied++ }
            if (panel != null) {
                putString("anim_panel", panel.style)
                putString("anim_panel_cfg", panel.toJson().toString())
                putBoolean("panel_animations", panel.style != "NONE")
                applied++
            }
            if (open != null) {
                putString("anim_open", open.style)
                putString("anim_open_cfg", open.toJson().toString())
                applied++
            }
        }
        added + applied
    }

    /** a — прогресс нажатия (может «перелетать» за 1 и уходить ниже 0 у упругих анимаций). */
    fun apply(scope: androidx.compose.ui.graphics.GraphicsLayerScope, anim: PressAnim, a: Float, strength: Float) {
        val k = a * strength
        scope.scaleX = 1f + (anim.scaleX - 1f) * k
        scope.scaleY = 1f + (anim.scaleY - 1f) * k
        if (anim.lift != 0f) scope.translationY = anim.lift * scope.density * k
        if (anim.rotate != 0f) scope.rotationZ = anim.rotate * k
        if (anim.alpha < 1f) scope.alpha = (1f - (1f - anim.alpha) * k.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

/**
 * Переход панели или появление клавиатуры: одна «формула» и для готовых вариантов, и для своих.
 * style: SCALE | SLIDE | FADE | NONE. Длительности — в миллисекундах при скорости ×1.
 */
data class TransitionAnim(
    val style: String = "SCALE",
    /** Сколько длится проявление. */
    val enterMs: Int = 160,
    /** Сколько длится исчезновение уходящей панели. */
    val exitMs: Int = 100,
    /** С какого размера начинается увеличение (SCALE). */
    val scaleFrom: Float = 0.94f,
    /** Откуда выезжает новое: доля высоты, плюс — снизу, минус — сверху (SLIDE). */
    val slideIn: Float = 0.2f,
    /** Куда уезжает старое: доля высоты (SLIDE). */
    val slideOut: Float = -0.125f,
    /** Упругость: 0.2 — долго качается, 1 — без колебаний. */
    val damping: Float = 0.75f,
    /** Скорость пружины. */
    val stiffness: Float = 500f
) {
    fun toJson(): JSONObject = JSONObject()
        .put("style", style).put("enterMs", enterMs).put("exitMs", exitMs)
        .put("scaleFrom", scaleFrom.toDouble()).put("slideIn", slideIn.toDouble()).put("slideOut", slideOut.toDouble())
        .put("damping", damping.toDouble()).put("stiffness", stiffness.toDouble())

    companion object {
        val STYLES = setOf("SCALE", "SLIDE", "FADE", "NONE")

        /** Готовый вариант. Для панелей значения те же, что были до настройки переходов. */
        fun preset(style: String, open: Boolean = false): TransitionAnim = when (style) {
            "SLIDE" -> if (open) TransitionAnim("SLIDE", slideIn = 0.3f, damping = 0.85f, stiffness = 500f)
                else TransitionAnim("SLIDE", damping = 0.85f, stiffness = 600f)
            "FADE" -> if (open) TransitionAnim("FADE", enterMs = 180) else TransitionAnim("FADE", exitMs = 110)
            "NONE" -> TransitionAnim("NONE")
            else -> if (open) TransitionAnim("SCALE", scaleFrom = 0.9f, damping = 0.7f, stiffness = 400f) else TransitionAnim("SCALE")
        }

        fun fromJson(o: JSONObject): TransitionAnim {
            val st = o.optString("style", "SCALE").uppercase().takeIf { it in STYLES } ?: "SCALE"
            val b = preset(st)
            return TransitionAnim(
                style = st,
                enterMs = o.optInt("enterMs", b.enterMs).coerceIn(0, 1500),
                exitMs = o.optInt("exitMs", b.exitMs).coerceIn(0, 1500),
                scaleFrom = o.optDouble("scaleFrom", b.scaleFrom.toDouble()).toFloat().coerceIn(0.5f, 1.5f),
                slideIn = o.optDouble("slideIn", b.slideIn.toDouble()).toFloat().coerceIn(-1f, 1f),
                slideOut = o.optDouble("slideOut", b.slideOut.toDouble()).toFloat().coerceIn(-1f, 1f),
                damping = o.optDouble("damping", b.damping.toDouble()).toFloat().coerceIn(0.15f, 1f),
                stiffness = o.optDouble("stiffness", b.stiffness.toDouble()).toFloat().coerceIn(100f, 3000f)
            )
        }
    }
}

object Transitions {
    private fun parse(raw: String, style: String, open: Boolean): TransitionAnim {
        val fallback = TransitionAnim.preset(style.takeIf { it in TransitionAnim.STYLES } ?: "SCALE", open)
        if (raw.isBlank()) return fallback
        return runCatching { TransitionAnim.fromJson(JSONObject(raw)) }.getOrDefault(fallback)
    }

    /** Переход между клавиатурой, эмодзи и панелями. */
    fun panel(s: KeyboardSettings): TransitionAnim = parse(s.animPanelCfg, s.animPanel, false)

    /** Появление клавиатуры при открытии окна. По умолчанию выключено. */
    fun open(s: KeyboardSettings): TransitionAnim = parse(s.animOpenCfg, s.animOpen, true)

    fun enter(a: TransitionAnim, k: Float): EnterTransition {
        val fade = fadeIn(tween((a.enterMs / k).toInt()))
        return when (a.style) {
            "SLIDE" -> slideInVertically(spring(dampingRatio = a.damping, stiffness = a.stiffness * k * k)) { (it * a.slideIn).toInt() } + fade
            "FADE" -> fade
            "NONE" -> EnterTransition.None
            else -> fade + scaleIn(spring(dampingRatio = a.damping, stiffness = a.stiffness * k * k), initialScale = a.scaleFrom)
        }
    }

    fun exit(a: TransitionAnim, k: Float): ExitTransition {
        val fade = fadeOut(tween((a.exitMs / k).toInt()))
        return when (a.style) {
            "SLIDE" -> slideOutVertically(spring(dampingRatio = 1f, stiffness = 900f * k * k)) { (it * a.slideOut).toInt() } + fade
            "NONE" -> ExitTransition.None
            else -> fade
        }
    }

    fun spec(a: TransitionAnim, k: Float): ContentTransform = enter(a, k) togetherWith exit(a, k)
}

// ----------------------------------------------------------------------- экран настроек

/** Клавиша-образец: нажмите — проиграется анимация. */
@Composable
internal fun AnimSample(anim: PressAnim, speed: Float, strength: Float, selected: Boolean, label: String, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val a = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(48.dp)
                .graphicsLayer { Anims.apply(this, anim, a.value, strength) }
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) cs.primary else cs.secondaryContainer)
                .pointerInput(anim, speed) {
                    awaitEachGesture {
                        awaitFirstDown()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            a.snapTo(0.6f)
                            a.animateTo(1f, spring(dampingRatio = 1f, stiffness = 3500f * speed * speed))
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        scope.launch { a.animateTo(0f, spring(dampingRatio = anim.damping, stiffness = anim.stiffness * speed * speed)) }
                        onClick?.invoke()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = if (selected) cs.onPrimary else cs.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            tr(label), style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
internal fun AnimationsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PressAnim?>(null) }
    // какой переход правят: панели или открытие клавиатуры
    var editingTransition by remember { mutableStateOf<String?>(null) }
    val speed = settings.animSpeed
    fun reportImport(r: Result<Int>) {
        r.onSuccess { n -> Toast.makeText(context, tr("Импортировано: {0}", n), Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, tr("Import failed: {0}", it.message ?: ""), Toast.LENGTH_LONG).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ioScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot open file")
                    require(bytes.size <= 1024 * 1024) { "File is larger than 1 MB" }
                    Anims.importJson(context, settings, String(bytes, Charsets.UTF_8)).getOrThrow()
                }
            }
            reportImport(r)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) ioScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Anims.exportJson(settings)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("cannot open file")
                }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Animations exported") else tr("Export failed"), Toast.LENGTH_SHORT).show()
        }
    }
    InfoCard(Icons.Rounded.Animation, "Нажмите на любую клавишу-образец, чтобы увидеть анимацию. «Как в теме» — у каждой темы своя анимация нажатия.")
    SettingsSection("Нажатие клавиши") {
        GroupItem {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val themeAnim = Anims.fromEffect(PluginRepository.activeSkin(settings)?.styleFor(KeyType.CHARACTER, "a")?.press, null)
                AnimSample(themeAnim, speed, settings.pressAnimation.coerceAtLeast(0.3f), settings.animPress == "THEME", "Как в теме") {
                    KeyboardPrefs.edit(context) { putString("anim_press", "THEME") }
                }
                Anims.all(settings).forEach { anim ->
                    AnimSample(anim, speed, settings.pressAnimation.coerceAtLeast(0.3f), settings.animPress == anim.id, anim.title) {
                        KeyboardPrefs.edit(context) { putString("anim_press", anim.id) }
                    }
                }
            }
        }
        SliderItem(
            icon = Icons.Rounded.Animation, title = "Сила анимации нажатия",
            value = settings.pressAnimation, range = 0f..2f,
            format = { if (it < 0.05f) tr("Выкл") else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("press_animation", if (v < 0.05f) 0f else v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Animation, title = "Подсветка нажатой клавиши",
            value = settings.pressHighlight, range = 0f..1f,
            format = { if (it < 0.05f) tr("Выкл") else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("press_highlight", if (v < 0.05f) 0f else v) } }
        )
        ToggleItem(
            Icons.Rounded.Animation, "Цветная волна на кнопках",
            "Нажали ?123, эмодзи, язык, Shift, Enter или кнопку на панели — по ней пробегает цветная волна",
            settings.buttonWash
        ) { KeyboardPrefs.edit(context) { putBoolean("button_wash", it) } }
        if (settings.buttonWash) {
            SliderItem(
                icon = Icons.Rounded.Speed, title = "Скорость волны",
                value = settings.buttonWashSpeed, range = 0.5f..2.5f,
                format = { String.format("×%.1f", it) },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("button_wash_speed", v) } }
            )
            SliderItem(
                icon = Icons.Rounded.Animation, title = "Яркость волны",
                value = settings.buttonWashStrength, range = 0.1f..1f,
                format = { "${(it * 100).roundToInt()}%" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("button_wash_strength", v) } }
            )
        }
        ToggleItem(
            Icons.Rounded.Animation, "Соседние клавиши отодвигаются",
            "Нажатая клавиша отодвигает соседей и они пружинят назад, как желе. Только рисунок: зоны нажатия не двигаются",
            settings.keyPush
        ) { KeyboardPrefs.edit(context) { putBoolean("key_push", it) } }
        if (settings.keyPush) {
            SliderItem(
                icon = Icons.Rounded.Animation, title = "Сила отталкивания",
                value = settings.keyPushStrength, range = 1f..14f,
                format = { "${it.roundToInt()} dp" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_push_strength", v) } }
            )
            SliderItem(
                icon = Icons.Rounded.Animation, title = "Сколько рядов клавиш двигается",
                value = settings.keyPushRings, range = 1f..4f,
                format = { String.format("%.1f", it) },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_push_rings", v) } }
            )
            SliderItem(
                icon = Icons.Rounded.Animation, title = "Желейность отскока",
                value = 1f - settings.keyPushBounce, range = 0f..0.85f,
                format = { "${(it / 0.85f * 100).roundToInt()}%" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_push_bounce", 1f - v) } }
            )
            ChoiceItem(
                Icons.Rounded.Animation, "Как двигаются соседи", null,
                listOf("AWAY" to "Отталкиваются", "TOWARD" to "Притягиваются", "SWIRL" to "Закручиваются"), settings.keyPushMode
            ) { v -> KeyboardPrefs.edit(context) { putString("key_push_mode", v) } }
            SliderItem(
                icon = Icons.Rounded.Animation, title = "Сплющивание соседей",
                value = settings.keyPushSquash, range = 0f..0.3f,
                format = { if (it < 0.01f) tr("Выкл") else "${(it / 0.3f * 100).roundToInt()}%" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_push_squash", v) } }
            )
            SliderItem(
                icon = Icons.Rounded.Speed, title = "Скорость отталкивания",
                value = settings.keyPushSpeed, range = 0.5f..2f,
                format = { String.format("×%.1f", it) },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_push_speed", v) } }
            )
        }
        SliderItem(
            icon = Icons.Rounded.Speed, title = "Скорость анимаций",
            value = settings.animSpeed, range = 0.5f..2f,
            format = { String.format("×%.1f", it) },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("anim_speed", v) } }
        )
    }
    SettingsSection("Свои анимации") {
        Anims.custom(settings).forEach { anim ->
            ActionItem(Icons.Rounded.AutoAwesome, anim.title, animSummary(anim), onClick = { editing = anim }, trailing = {
                IconButton(onClick = { Anims.saveCustom(context, settings, null, anim.id) }) {
                    Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = MaterialTheme.colorScheme.error)
                }
            })
        }
        ActionItem(Icons.Rounded.Add, "Создать анимацию", "Сжатие, сдвиг, поворот, прозрачность и упругость — ползунками", onClick = {
            editing = PressAnim("custom.${System.currentTimeMillis()}", tr("Моя анимация"), custom = true)
        })
        ActionItem(Icons.Rounded.FileOpen, "Импорт набора анимаций", "Из .json-файла: нажатия, подсказка, переходы панелей и открытие клавиатуры. Подойдёт и набор от друга", onClick = {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        })
        ActionItem(Icons.Rounded.ContentPaste, "Импорт из буфера обмена", "Сначала скопируйте JSON набора, потом нажмите", onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(context, tr("Буфер обмена пуст"), Toast.LENGTH_SHORT).show()
            else reportImport(Anims.importJson(context, settings, text))
        })
        ActionItem(Icons.Rounded.Upload, "Экспорт набора анимаций", "Сохранить свои анимации, переходы и выбранные настройки в .json-файл, чтобы поделиться", onClick = {
            exportLauncher.launch("expressive-keyboard-animations.json")
        })
    }
    SettingsSection("Подсказка над клавишей") {
        ChoiceItem(
            Icons.Rounded.ChatBubbleOutline, "Как появляется", null,
            listOf("POP" to "Пружина", "ZOOM" to "Из точки", "SLIDE" to "Выезжает", "FADE" to "Проявление", "NONE" to "Сразу"),
            settings.animPopup
        ) { v -> KeyboardPrefs.edit(context) { putString("anim_popup", v) } }
    }
    SettingsSection("Панели") {
        ChoiceItem(
            Icons.Rounded.Layers, "Переход между клавиатурой, эмодзи и панелями", null,
            listOf("SCALE" to "Увеличение", "SLIDE" to "Сдвиг", "FADE" to "Проявление", "NONE" to "Без анимации"),
            if (settings.panelAnimations) settings.animPanel else "NONE"
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("anim_panel", v)
                // готовый вариант сбрасывает свою настройку
                putString("anim_panel_cfg", "")
                putBoolean("panel_animations", v != "NONE")
            }
        }
        ActionItem(Icons.Rounded.Tune, "Настроить переход панелей", "Время, размер, сдвиг и упругость — ползунками, с показом", onClick = {
            editingTransition = "panel"
        })
        SliderItem(
            icon = Icons.Rounded.Layers, title = "Плавная смена букв и цифр",
            value = settings.pageSwitchAnim, range = 0f..1f,
            format = { if (it < 0.05f) tr("Выкл") else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("page_switch_anim", if (v < 0.05f) 0f else v) } }
        )
    }
    SettingsSection("Открытие клавиатуры") {
        ChoiceItem(
            Icons.Rounded.KeyboardArrowUp, "Как появляется клавиатура", null,
            listOf("NONE" to "Без анимации", "FADE" to "Проявление", "SCALE" to "Увеличение", "SLIDE" to "Снизу"),
            settings.animOpen
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("anim_open", v)
                putString("anim_open_cfg", "")
            }
        }
        ActionItem(Icons.Rounded.Tune, "Настроить открытие", "Анимация играет при каждом появлении клавиатуры. Закрытие система показывает сама", onClick = {
            editingTransition = "open"
        })
    }
    editingTransition?.let { which ->
        val isOpen = which == "open"
        val current = if (isOpen) Transitions.open(settings) else Transitions.panel(settings)
        TransitionEditorDialog(
            title = if (isOpen) "Открытие клавиатуры" else "Переход панелей",
            initial = if (current.style == "NONE") TransitionAnim.preset("SCALE", isOpen) else current,
            withExit = !isOpen,
            speed = settings.animSpeed,
            onDismiss = { editingTransition = null }
        ) { a ->
            KeyboardPrefs.edit(context) {
                if (isOpen) {
                    putString("anim_open", a.style)
                    putString("anim_open_cfg", a.toJson().toString())
                } else {
                    putString("anim_panel", a.style)
                    putString("anim_panel_cfg", a.toJson().toString())
                    putBoolean("panel_animations", true)
                }
            }
            editingTransition = null
        }
    }
    editing?.let { a -> AnimEditorDialog(a, settings, onDismiss = { editing = null }) { Anims.saveCustom(context, settings, it); editing = null } }
}

private fun animSummary(a: PressAnim): String = buildList {
    if (a.scaleX != 1f || a.scaleY != 1f) add(tr("размер {0}×{1}", "%.2f".format(a.scaleX), "%.2f".format(a.scaleY)))
    if (a.lift != 0f) add(tr("сдвиг {0} dp", a.lift.roundToInt()))
    if (a.rotate != 0f) add(tr("поворот {0}°", a.rotate.roundToInt()))
    if (a.alpha < 1f) add(tr("прозрачность {0}%", ((1f - a.alpha) * 100).roundToInt()))
}.joinToString(" · ").ifEmpty { tr("без изменений") }

@Composable
private fun AnimEditorDialog(initial: PressAnim, settings: KeyboardSettings, onDismiss: () -> Unit, onSave: (PressAnim) -> Unit) {
    var a by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Своя анимация")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimSample(a, settings.animSpeed, 1f, true, "Нажмите", null)
                }
                OutlinedTextField(
                    value = a.title, onValueChange = { a = a.copy(title = it.take(24)) }, singleLine = true,
                    label = { Text(tr("Название")) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                AnimSlider("Ширина", a.scaleX, 0.6f..1.4f, { "%.2f".format(it) }) { a = a.copy(scaleX = it) }
                AnimSlider("Высота", a.scaleY, 0.6f..1.4f, { "%.2f".format(it) }) { a = a.copy(scaleY = it) }
                AnimSlider("Сдвиг вниз / вверх", a.lift, -12f..12f, { "${it.roundToInt()} dp" }) { a = a.copy(lift = it) }
                AnimSlider("Поворот", a.rotate, -30f..30f, { "${it.roundToInt()}°" }) { a = a.copy(rotate = it) }
                AnimSlider("Видимость", a.alpha, 0.2f..1f, { "${(it * 100).roundToInt()}%" }) { a = a.copy(alpha = it) }
                AnimSlider("Упругость (меньше — дольше качается)", a.damping, 0.15f..1f, { "%.2f".format(it) }) { a = a.copy(damping = it) }
                AnimSlider("Скорость возврата", a.stiffness, 150f..2000f, { "${it.roundToInt()}" }) { a = a.copy(stiffness = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(a.copy(title = a.title.ifBlank { tr("Моя анимация") })) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

/** Редактор перехода: стиль, время, размер, сдвиг и упругость, а над ними — показ с нынешними значениями. */
@Composable
private fun TransitionEditorDialog(
    title: String,
    initial: TransitionAnim,
    withExit: Boolean,
    speed: Float,
    onDismiss: () -> Unit,
    onSave: (TransitionAnim) -> Unit
) {
    var a by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TransitionPreview(a, speed, withExit)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SCALE" to "Увеличение", "SLIDE" to "Сдвиг", "FADE" to "Проявление").forEach { (id, label) ->
                        FilterChip(
                            selected = a.style == id,
                            onClick = { a = a.copy(style = id) },
                            label = { Text(tr(label)) }
                        )
                    }
                }
                AnimSlider("Проявление, мс", a.enterMs.toFloat(), 0f..800f, { "${it.roundToInt()} ms" }) { a = a.copy(enterMs = it.roundToInt()) }
                if (withExit) AnimSlider("Исчезновение, мс", a.exitMs.toFloat(), 0f..800f, { "${it.roundToInt()} ms" }) { a = a.copy(exitMs = it.roundToInt()) }
                if (a.style == "SCALE") AnimSlider("Начальный размер", a.scaleFrom, 0.6f..1f, { "%.2f".format(it) }) { a = a.copy(scaleFrom = it) }
                if (a.style == "SLIDE") {
                    AnimSlider("Откуда выезжает (доля высоты)", a.slideIn, -0.5f..0.5f, { "${(it * 100).roundToInt()}%" }) { a = a.copy(slideIn = it) }
                    if (withExit) AnimSlider("Куда уезжает старое", a.slideOut, -0.5f..0.5f, { "${(it * 100).roundToInt()}%" }) { a = a.copy(slideOut = it) }
                }
                if (a.style != "FADE") {
                    AnimSlider("Упругость (меньше — дольше качается)", a.damping, 0.15f..1f, { "%.2f".format(it) }) { a = a.copy(damping = it) }
                    AnimSlider("Скорость пружины", a.stiffness, 150f..2000f, { "${it.roundToInt()}" }) { a = a.copy(stiffness = it) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(a) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

/** Образец перехода: плашка появляется и уходит по текущим значениям; повтор — по кнопке. */
@Composable
private fun TransitionPreview(a: TransitionAnim, speed: Float, withExit: Boolean) {
    val cs = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(shown, enter = Transitions.enter(a, speed), exit = Transitions.exit(a, speed)) {
                Box(
                    Modifier.width(200.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(cs.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("q w e r t y", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
        TextButton(onClick = {
            scope.launch {
                shown = false
                delay((if (withExit) a.exitMs / speed else 120f).toLong() + 250L)
                shown = true
            }
        }) { Text(tr("Показать")) }
    }
}

@Composable
private fun AnimSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
