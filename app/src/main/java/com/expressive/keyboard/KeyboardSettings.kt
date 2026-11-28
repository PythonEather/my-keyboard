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
// 2. НАСТРОЙКИ (ОДИН СЛУШАТЕЛЬ НА ВЕСЬ ПРОЦЕСС)
// ============================================================================

enum class HapticMode(val id: String, val title: String) {
    CLICK("CLICK", "Клик"),
    TICK("TICK", "Тик"),
    HEAVY("HEAVY", "Сильный"),
    CUSTOM("CUSTOM", "Свой");

    companion object {
        fun fromId(id: String): HapticMode = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

internal val BOTTOM_OFFSET_RANGE = -16f..64f
private const val DEFAULT_BOTTOM_OFFSET_DP = -12f

@Immutable
data class KeyboardSettings(
    val themeMode: String = "SYSTEM",
    val keyHeightDp: Float = 54f,
    val headerHeightDp: Float = 48f,                // толщина верхней панели
    val cornerRadiusDp: Float = 16f,
    val keySpacingDp: Float = 5f,
    val rowSpacingDp: Float = 8f,
    val bottomOffsetDp: Float = DEFAULT_BOTTOM_OFFSET_DP,
    val shapePreset: String = ExpressiveShapePreset.COOKIE_12.id,
    /** Own shape for the letter popup bubble; empty = the same as shapePreset. */
    val popupShapePreset: String = "",
    val waveCount: Int = 12,
    val waveAmplitudePercent: Float = 6f,
    val showNumberRow: Boolean = false,
    val showKeyBorders: Boolean = false,
    val keyPreview: Boolean = true,
    val autoCapitalize: Boolean = true,
    val autoCorrect: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val hapticMode: String = HapticMode.CUSTOM.id,
    val vibrationMs: Float = 16f,
    val vibrationAmplitude: Float = 135f,
    val vibrateOnRelease: Boolean = false,
    val strongSpecialKeys: Boolean = true,
    val soundEnabled: Boolean = false,
    val longPressMs: Float = 320f,
    val enterHoldSend: Boolean = true,
    val enterHoldMs: Float = 550f,
    val flickSensitivity: Float = 0.5f,
    val spaceCursorSpeed: Float = 1f,             // скорость курсора при свайпе по пробелу влево-вправо (1 = как раньше)
    val spaceLineSpeed: Float = 1f,               // то же при свайпе вверх-вниз
    val fontStyle: String = "ROUNDED",
    val customFontName: String = "",
    val customFontVersion: Long = 0L,
    // --- цвета ---
    val colorSource: String = "DYNAMIC",          // DYNAMIC (обои) | SEED (свой цвет)
    val seedColor: Int = 0xFF6750A4.toInt(),
    val colorBackground: Int = 0,                 // 0 = авто
    val colorKey: Int = 0,
    val colorKeyText: Int = 0,
    val colorFunc: Int = 0,
    val colorFuncText: Int = 0,
    val colorAccent: Int = 0,
    val colorAccentText: Int = 0,
    val keyOpacity: Float = 1f,
    /**
     * Подложка (плашка) под клавишами. Выключено — остаются только буквы и значки, фон видно насквозь:
     * так не видно прямоугольников и при размытии под клавиатурой.
     */
    val keyBackground: Boolean = true,
    /** Прозрачность фона клавиатуры: 0 — обычный фон, 1 — фона нет, видно только клавиши. */
    val bgTransparency: Float = 0f,
    // --- фон ---
    val bgImageVersion: Long = 0L,
    val bgDim: Float = 0.35f,
    val photoFrame: PhotoFrame = PhotoFrame(),
    val bgBlurDp: Float = 0f,
    /** Blur the background picture once instead of live (static frosted glass). */
    val bgFrostStatic: Boolean = false,
    /** Key shadows lean away from the tilt of the phone. */
    val dynamicShadow: Boolean = false,
    /** Reminder about plugins that use sensitive permissions (not changeable by plugins). */
    val securityReminder: Boolean = true,
    val securityReminderMin: Int = 4,
    /** The "YOU ARE THE SECURITY" notice has been confirmed (5 taps); it is never shown again. */
    val pluginTrustAck: Boolean = false,
    /** Plugins with the settings_layout permission may arrange the Settings home screen. */
    val pluginSettingsLayout: Boolean = true,
    /** Plugins with the toolbar_layout permission may arrange the keyboard toolbar. */
    val pluginToolbarLayout: Boolean = true,
    /** Settings home: pinned pages ("Important" slots) in the user's order, as SettingsPage names. */
    val homePins: String = "",
    /** Wallpaper behind Settings: 0 — none, otherwise the time it was picked. */
    val settingsWallpaper: Long = 0L,
    /** Frosted glass over that wallpaper: 0 off, 1 light, 2 medium, 3 strong. */
    val settingsGlass: Int = 2,
    /** Live (video) wallpaper behind the keyboard keys and behind Settings; 0 = none. The value is the pick time. */
    val liveBgVersion: Long = 0L,
    val liveSettingsVersion: Long = 0L,
    // --- клавиши ---
    val keyLabelScale: Float = 1f,
    val fontWeight: Int = 700,
    val showHints: Boolean = true,
    val sidePaddingDp: Float = 6f,
    /** Own screen density for the keyboard only (dpi). 0 = the system one. */
    val customDpi: Int = 0,
    val oneHanded: String = "OFF",                // OFF | LEFT | RIGHT
    val oneHandedWidth: Float = 0.7f,
    /** Плавающая клавиатура: небольшое окно, которое можно перетащить в любое место экрана. */
    val floating: Boolean = false,
    /** Ширина плавающей клавиатуры — доля ширины экрана. */
    val floatWidth: Float = 0.8f,
    val floatKeyHeightDp: Float = 46f,
    /** Где стоит плавающая клавиатура: доли свободного места по горизонтали и снизу вверх. */
    val floatX: Float = 0.5f,
    val floatY: Float = 0.15f,
    /** Ширина клавиш (доля экрана, по центру) — для планшетов и широких экранов; 1 — во всю ширину. */
    val keyboardWidth: Float = 1f,
    /** Верхняя панель над клавиатурой: подсказки, микрофон и кнопка меню. */
    val showHeader: Boolean = true,
    /** Поля чисел и телефонов получают цифровую клавиатуру 4×4, как у системной. */
    val numberPad: Boolean = true,
    /** «Жидкое стекло» на любой теме: клавиши-линзы с объёмными бликами (Android 13+). */
    val liquidGlass: Boolean = false,
    /** Стеклянные клавиши при нажатии сливаются с соседними, как капли (Android 13+). */
    val glassMerge: Boolean = false,
    /** Бирманский: ေ и ြ набираются перед согласной, как на экране, а в поле попадают в порядке Unicode. */
    val myanmarVisual: Boolean = true,
    val showSuggestions: Boolean = true,
    val inlineCalculator: Boolean = true,
    val showMicButton: Boolean = true,
    /** Что делает правая кнопка верхней панели: VOICE (микрофон), EMOJI, CLIPBOARD, TRANSLATE, GIF, UNDO, HIDE. */
    val micAction: String = "VOICE",
    /** Окно удержания: SHAPE — волнистые фигуры, CIRCLE, BOX — скруглённые квадраты, PANEL — одна панель со всеми символами. */
    val popupStyle: String = "SHAPE",
    /** Свои символы при удержании (JSON: клавиша → символы), см. UserVariants. */
    val keyVariants: String = "",
    /** Пока есть подсказки слов, кнопки меню и микрофона прячутся — подсказкам вся ширина. */
    val suggestionsFullWidth: Boolean = false,
    val showEmojiKey: Boolean = true,
    val showGlobeKey: Boolean = true,
    val pressAnimation: Float = 1f,               // 0 = без анимации нажатия
    /** How strongly a pressed key changes color / glows: 0 = no color change at all, 1 = as the theme draws it. */
    val pressHighlight: Float = 1f,
    /** Neighbour push: keys around a pressed key slide away and bounce back (visual only). */
    val keyPush: Boolean = false,
    val keyPushStrength: Float = 4f,
    val keyPushRings: Float = 1.5f,
    val keyPushBounce: Float = 0.35f,
    val keyPushSpeed: Float = 1f,
    /** AWAY (push away), TOWARD (pull in like a magnet) or SWIRL (swirl around the pressed key). */
    val keyPushMode: String = "AWAY",
    /** How much the pushed keys get squeezed (0 = not at all). */
    val keyPushSquash: Float = 0f,
    /** Wash: a colour wave that slides across ?123, emoji, language, Shift, Enter and the toolbar buttons when tapped. */
    val buttonWash: Boolean = true,
    val buttonWashSpeed: Float = 1f,
    val buttonWashStrength: Float = 0.55f,
    /** Press shockwave: a soft ring spreads from the pressed key and nudges the keys it passes. */
    val shockwave: Boolean = false,
    val shockwaveSpeed: Float = 1f,
    val shockwaveStrength: Float = 0.5f,
    val shockwaveReach: Float = 3.5f,
    val shockwaveRipple: Float = 3f,
    val shockwaveRing: Boolean = true,
    /** Tilt parallax: the keys shift a little when the phone tilts, so the keyboard looks 3D. */
    val tiltParallax: Boolean = false,
    val tiltParallaxStrength: Float = 1f,
    val tiltParallaxInvert: Boolean = false,
    /** Typing glow: a neon strip along the keyboard that gets brighter and faster the quicker you type. */
    val typingGlow: Boolean = false,
    val typingGlowEdge: String = "TOP",
    val typingGlowColor: String = "ACCENT",
    val typingGlowMax: Float = 0.8f,
    val typingGlowFullCps: Float = 7f,
    val typingGlowCalm: Float = 2f,
    val typingGlowThickness: Float = 4f,
    /** Edge swipe: swipe in from the screen edge to shrink the keyboard to that side (one-hand mode). */
    val edgeSwipe: Boolean = false,
    val edgeSwipeZoneDp: Float = 18f,
    val edgeSwipeDistanceDp: Float = 64f,
    val edgeSwipeOpposite: String = "EXIT",
    val edgeSwipeHaptic: Boolean = true,
    /** Themes by app and by time of day (the looks themselves are saved in `look_presets`). */
    val perAppThemes: Boolean = false,
    val dayNight: Boolean = false,
    val dayNightMode: String = "TIMES",
    val dayLook: String = "",
    val nightLook: String = "",
    val dayStartMin: Int = 420,
    val nightStartMin: Int = 1140,
    val sunLatLon: String = "",
    /** Pitch of key sounds: how far the keys differ from each other and how much each press wobbles. */
    val soundKeyPitch: Float = 0f,
    val soundWobble: Float = 0.5f,
    /** Space bar cursor ticks: a small vibration for every character or line the cursor moves. */
    val spaceTicks: Boolean = true,
    val spaceTickStrength: String = "NORMAL",
    val spaceTickLines: Boolean = true,
    /** Typing stats page: counts speed, streaks and most used words on this phone only. */
    val typingStats: Boolean = false,
    /** Emoji key in the bottom row: width (1 = as before), icon size and place: DEFAULT | START | AFTER_SPACE. */
    val emojiKeyWidth: Float = 1f,
    val emojiKeyIconScale: Float = 1f,
    val emojiKeyPos: String = "DEFAULT",
    /** Soft fade when the key page changes (letters, numbers and symbols): 0 = instant, 1 = a visible dip. */
    val pageSwitchAnim: Float = 0.5f,
    val panelAnimations: Boolean = true,
    /** Когда работает энергосбережение: SYSTEM — вместе с системой, ALWAYS — всегда, NEVER — никогда. */
    val ecoWhen: String = "SYSTEM",
    /** Что выключает энергосбережение. */
    val ecoPress: Boolean = true,
    val ecoPanels: Boolean = true,
    val ecoBlur: Boolean = true,
    val ecoTrail: Boolean = true,
    val ecoPreview: Boolean = false,
    val ecoHaptics: Boolean = false,
    val ecoSound: Boolean = false,
    val ecoSuggestions: Boolean = false,
    /** Анимация нажатия: THEME — из темы, id готовой или своей (Animations.kt). */
    val animPress: String = "THEME",
    /** Скорость всех анимаций клавиатуры. */
    val animSpeed: Float = 1f,
    /** Появление подсказки над клавишей: POP | ZOOM | SLIDE | FADE | NONE */
    val animPopup: String = "POP",
    /** Переход между панелями: SCALE | SLIDE | FADE | NONE */
    val animPanel: String = "SCALE",
    /** Свои анимации нажатия (JSON-массив). */
    val customAnims: String = "",
    /** Своя настройка перехода панелей (JSON); пусто — готовый вариант из animPanel. */
    val animPanelCfg: String = "",
    /** Появление клавиатуры при открытии: NONE | FADE | SCALE | SLIDE. */
    val animOpen: String = "NONE",
    /** Своя настройка появления клавиатуры (JSON); пусто — готовый вариант из animOpen. */
    val animOpenCfg: String = "",
    // --- ввод ---
    val spaceSwipe: String = "CURSOR",            // CURSOR | LANGUAGE | OFF
    val spaceSwipeVertical: String = "OFF",       // свайп по пробелу вверх-вниз: OFF | CURSOR | LANGUAGE
    /** Удержание кнопки языка: LANGUAGES | SYSTEM | EMOJI | CLIPBOARD | VOICE | SETTINGS | ONE_HANDED | OFF */
    val globeLongPress: String = "LANGUAGES",
    /** Панель редактирования над клавиатурой: курсор, выделение, копировать/вставить. */
    val editBar: Boolean = false,
    /** Удержание кнопки панели функций: PIN — закрепить панель над подсказками | EDIT_BAR — панель редактирования. */
    val toolsLongPress: String = "PIN",
    /** Размытие приложения под клавиатурой: THEME — как в оформлении | ON | OFF. По умолчанию выключено: оно греет телефон. */
    val windowBlur: String = "OFF",
    /** Сила размытия, dp. */
    val windowBlurDp: Float = 32f,
    val swipeDownHide: Boolean = true,            // свайп вниз по строке подсказок закрывает клавиатуру
    val enterWidth: Float = 1f,                   // множитель ширины клавиши ввода
    val numberRowScale: Float = 0.78f,            // высота ряда цифр относительно ряда букв
    val backspaceSwipe: Boolean = true,           // свайп влево по ⌫ удаляет слово
    val clipboardHistory: Boolean = true,
    /** Just-copied offer (paste chip) sits in its own row above the toolbar instead of replacing it. */
    val clipOfferTop: Boolean = false,
    val clipboardSize: Int = 50,
    /** Сколько хранить записи буфера: 1H | 1D | 7D | 30D | FOREVER. */
    val clipboardKeep: String = "FOREVER",
    /** Новые скриншоты сами попадают в буфер (нужен доступ к фото). */
    val clipScreenshots: Boolean = true,
    /** A copy longer than this many characters becomes a .txt file instead of text (0 — off). */
    val longTextLimit: Int = 50_000,
    /** Where those files go: DOWNLOADS (Downloads/Expressive Keyboard) | PRIVATE (the app's own folder). */
    val longTextPlace: String = "DOWNLOADS",
    /** The keyboard deletes a long text this long after it was last used: 1D | 7D | 30D | NEVER. */
    val longTextKeep: String = "7D",
    /** Only the newest N long-text files stay; pinned ones are never deleted. */
    val longTextMax: Int = 10,
    val soundVolume: Float = 0.6f,
    val giphyKey: String = "",
    /** Свой ключ KLIPY (бесплатный, замена Tenor). */
    val klipyKey: String = "",
    /** Кто исправляет текст кнопкой ✨: AUTO | NANO | GEMINI | CLAUDE | OPENAI | GROQ | OPENROUTER | LANGUAGETOOL. */
    val aiProvider: String = "AUTO",
    /** Ключи ИИ-сервисов (секреты: не попадают в экспорт настроек). */
    val anthropicKey: String = "",
    val geminiKey: String = "",
    val openaiKey: String = "",
    val groqKey: String = "",
    val openrouterKey: String = "",
    /** Свои модели ИИ (пусто — по умолчанию). */
    val geminiModel: String = "",
    val claudeModel: String = "",
    val openaiModel: String = "",
    val groqModel: String = "",
    val openrouterModel: String = "",
    /** Свои ИИ-сервисы (хранятся зашифрованными вместе с ключами, в экспорт не попадают). */
    val aiCustom: List<CustomAi> = emptyList(),
    // --- скорость и жесты ---
    val instantInput: Boolean = true,             // буква появляется в момент касания, а не при отпускании
    val glideTyping: Boolean = true,              // печать, не отрывая пальца
    val glideTrail: Boolean = true,
    val swipeUpHint: Boolean = true,              // свайп вверх по клавише вводит символ-подсказку
    val spaceLongPress: Boolean = true,           // удержание пробела включено
    /** Удержание пробела: PICKER | AI_FIX | VOICE | EMOJI | CLIPBOARD | TOOLBAR | SETTINGS | OFF */
    val spaceLongAction: String = "PICKER",
    /** Свой звук нажатия: версия файла (0 — системный звук). */
    val customSoundVersion: Long = 0L,
    /** Звук нажатия: SYSTEM | SOFT | BUBBLE | WOOD | MECHANICAL | GLASS | CUSTOM (см. KeySoundStyle). */
    val soundStyle: String = "SYSTEM",
    val autoPair: Boolean = false,                // ( → () и курсор внутрь
    val incognito: Boolean = false,               // не учить слова и не запоминать буфер
    // --- оформление из плагина ---
    val skinPlugin: String = "",
    /** Настройки тем: JSON { "id темы": { "id настройки": значение } }. */
    val skinOptions: String = "",
    // --- языки и подсказки ---
    val languages: String = "ru,en",              // включённые языки по порядку
    val autoCorrectStrength: String = "NORMAL",   // SAFE — только уверенные исправления | NORMAL — как Gboard
    val dictAutoDownload: Boolean = true,         // скачивать словарь языка при включении
    val punctuationKeys: Boolean = true,          // «,» и «.» по бокам пробела, как в Gboard
    val spaceLeftKey: String = "",                // своя клавиша слева от пробела на буквах: "" — нет
    val spaceRightKey: String = "",               // …и справа
    val systemSpellcheck: Boolean = true,         // спрашивать системную проверку правописания (Gboard, Samsung)
    val smartPunctuation: Boolean = true,         // «слово ,» → «слово,»
    /** Пробел сам ставится после запятой, «!», «?», «;» и «:» за словом. */
    val autoSpacePunct: Boolean = true,
    /** Два дефиса подряд превращаются в длинное тире: «--» → «—». */
    val dashMerge: Boolean = true,
    /** Исправлять и там, где приложение просит клавиатуру не исправлять (кроме паролей, почты и адресов). */
    val correctEverywhere: Boolean = true,
    /** Динамические зоны клавиш, как на iPhone: у границы клавиш срабатывает буква, вероятнее подходящая к слову. */
    val dynamicZones: Boolean = true,
    /** Голосовой ввод чистится ИИ (без «э-э», повторов и оговорок), если задан ключ ИИ. */
    val aiVoiceCleanup: Boolean = true,
    /** Кнопка панели функций в строке подсказок. Без неё и без подсказок панель функций видна всегда. */
    val toolsButton: Boolean = true,
    /** Панель функций закреплена отдельной строкой над подсказками (удержание кнопки панели функций). */
    val pinnedTools: Boolean = false,
    /** Что по удержанию буквы: VARIANTS — варианты буквы и символы (как раньше), SYMBOLS — символ с того же места на «?123», как в Gboard. */
    val holdMode: String = "VARIANTS",
    /** В альбомной ориентации клавиатура делится на две половины — до букв легко дотянуться большими пальцами. */
    val landscapeSplit: Boolean = true,
    /** Размер клавиатуры в альбомной: 1 — как подобрано под экран. */
    val landscapeScale: Float = 1f,
    /** Свои раскладки языков: JSON { "код языка": [["клавиша", …], …] }. */
    val langLayouts: String = "",
    val translateTarget: String = "en",
    // --- панель инструментов ---
    val toolbarOrder: String = "",
    val toolbarHidden: String = "",
    /** Свои места клавиш: JSON «раскладка → порядок символьных клавиш». */
    val userLayouts: String = "",
    // --- эмодзи ---
    val emojiFontVersion: Long = 0L,
    /** SYSTEM, CUSTOM (свой шрифт) или встроенный набор: twemoji, noto, blob, openmoji. */
    val emojiStyle: String = "SYSTEM",
    /** После вставки эмодзи клавиатура сразу возвращается к буквам, как в Gboard. */
    val emojiReturnToKeyboard: Boolean = false,
    // --- интерфейс ---
    val appLanguage: String = "SYSTEM",           // SYSTEM | ru | en
    val designStyle: String = "EXPRESSIVE",
    /** Сколько выразительных форм: FULL, ACCENTS или NONE (см. ShapeAmount). */
    val shapeAmount: String = ShapeAmount.FULL,
    /** Не хранится: клавиатура в альбомной ориентации — ниже клавиши и шапка. */
    val landscape: Boolean = false
) {
    val toolbarOrderList: List<String> get() = toolbarOrder.split(',').filter { it.isNotBlank() }
    val toolbarHiddenSet: Set<String> get() = toolbarHidden.split(',').filter { it.isNotBlank() }.toSet()
    val userLayoutMap: Map<String, List<String>> by lazy { UserLayouts.parse(userLayouts) }

    val hasBackgroundImage: Boolean get() = bgImageVersion != 0L

    /**
     * Акцент, который выбрал сам пользователь: «Акцент» в цветах или «Свой цвет» палитры.
     * В него перекрашивается любая тема; 0 — пользователь ничего не выбирал, у темы свои цвета.
     */
    val userAccent: Int get() = when {
        colorAccent != 0 -> colorAccent
        colorSource == "SEED" -> seedColor
        else -> 0
    }

    /** В альбомной ориентации клавиатура занимает не больше ~55% высоты экрана. */
    fun forLandscape(screenHeightDp: Int): KeyboardSettings {
        val rows = if (showNumberRow) 4f + numberRowScale else 4f
        val row = min(rowSpacingDp, 5f)
        val bottom = min(bottomOffsetDp, 2f)
        val budget = screenHeightDp * 0.55f - 40f - 16f - bottom - row * (rows - 1f)
        return copy(
            keyHeightDp = ((budget / rows).coerceIn(30f, keyHeightDp) * landscapeScale)
                .coerceIn(24f, max(30f, (screenHeightDp * 0.85f - 56f - bottom - row * (rows - 1f)) / rows)),
            rowSpacingDp = row, bottomOffsetDp = bottom, landscape = true
        )
    }

    /** Настройки для клавиатуры с учётом энергосбережения; экран настроек показывает исходные значения. */
    fun forPower(systemSaver: Boolean): KeyboardSettings {
        val on = when (ecoWhen) {
            "ALWAYS" -> true
            "NEVER" -> false
            else -> systemSaver
        }
        if (!on) return this
        return copy(
            pressAnimation = if (ecoPress) 0f else pressAnimation,
            panelAnimations = panelAnimations && !ecoPanels,
            windowBlur = if (ecoBlur) "OFF" else windowBlur,
            liquidGlass = liquidGlass && !ecoBlur,
            glassMerge = glassMerge && !ecoBlur,
            glideTrail = glideTrail && !ecoTrail,
            keyPreview = keyPreview && !ecoPreview,
            hapticsEnabled = hapticsEnabled && !ecoHaptics,
            soundEnabled = soundEnabled && !ecoSound,
            showSuggestions = showSuggestions && !ecoSuggestions
        )
    }

    /** Ключи API не попадают ни в логи, ни в отчёты об ошибках. */
    override fun toString(): String {
        fun mask(k: String) = if (k.isEmpty()) "" else "•••" + k.takeLast(3)
        return "KeyboardSettings(theme=$themeMode, skin=$skinPlugin, languages=$languages, ai=$aiProvider, " +
            "anthropicKey=${mask(anthropicKey)}, geminiKey=${mask(geminiKey)}, openaiKey=${mask(openaiKey)}, groqKey=${mask(groqKey)}, openrouterKey=${mask(openrouterKey)}, " +
            "giphyKey=${mask(giphyKey)}, klipyKey=${mask(klipyKey)})"
    }

    fun accentSpec(): WavySpec {
        val p = ExpressiveShapePreset.fromId(shapePreset)
        // у готовых форм и круга волн нет — не плодим отдельные кэшированные пути для каждого значения ползунков
        if (!p.tunable) return WavySpec.of(p)
        return WavySpec(p.profile, waveCount, waveAmplitudePercent / 100f, p)
    }
}

object KeyboardPrefs {
    const val PREFS_NAME = "expressive_keyboard_v7_prefs"
    const val CUSTOM_FONT_FILENAME = "custom_keyboard_font.ttf"

    private val state = mutableStateOf(KeyboardSettings())
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureInit(context: Context) {
        if (listener != null) return
        val sp = prefs(context)
        SecretBox.migrate(sp)
        if (!sp.contains("float_key_height_dp") && sp.contains("key_height_dp")) {
            val previousHeight = runCatching { sp.getFloat("key_height_dp", 54f) }.getOrDefault(54f)
            sp.edit().putFloat("float_key_height_dp", (previousHeight * 0.86f).coerceIn(28f, 68f)).apply()
        }
        state.value = read(view(sp))
        I18n.apply(state.value.appLanguage)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            // одна правка меняет десятки ключей (тема, сброс, импорт), а слушатель зовётся на каждый:
            // раньше все настройки перечитывались столько же раз подряд. Теперь — один раз, следующим сообщением
            if (!dirty) {
                dirty = true
                mainHandler.post { refresh(p) }
            }
        }
        listener = l
        sp.registerOnSharedPreferenceChangeListener(l)
    }

    /** A look worn for the app being typed in or for the time of day: some theme values replaced while the keyboard reads its settings. */
    @Volatile private var lookOverride: Map<String, Any?>? = null

    private fun view(p: SharedPreferences): SharedPreferences = lookOverride?.let { OverlayPrefs(p, it) } ?: p

    /** Wears a look (null = your own theme). Nothing is written to the preferences. Call on the main thread. */
    fun setLookOverride(context: Context, values: Map<String, Any?>?) {
        ensureInit(context)
        if (lookOverride == values) return
        lookOverride = values
        runCatching { read(view(prefs(context))) }.onSuccess { if (it != state.value) state.value = it }
        I18n.apply(state.value.appLanguage)
    }

    @Volatile private var dirty = false
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun refresh(p: SharedPreferences) {
        if (!dirty) return
        dirty = false
        // значение не того типа (из старой копии) не должно оставлять настройки застывшими
        runCatching { read(view(p)) }.onSuccess { if (it != state.value) state.value = it }
        I18n.apply(state.value.appLanguage)
    }

    fun current(context: Context): KeyboardSettings {
        ensureInit(context)
        // правку только что записали, а сообщение ещё не дошло — читаем сразу, чтобы не отдать старое
        if (dirty && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) refresh(prefs(context))
        return state.value
    }

    @Composable
    fun observe(): State<KeyboardSettings> {
        ensureInit(LocalContext.current)
        return state
    }

    /** The settings the keyboard would have while wearing [values] (a saved look). Nothing is written. For thumbnails. */
    fun settingsWith(context: Context, values: Map<String, Any?>): KeyboardSettings =
        read(OverlayPrefs(prefs(context), values))

    fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = prefs(context).edit()
        e.block()
        e.apply()
    }

    private fun read(p: SharedPreferences): KeyboardSettings {
        val font = p.getString("font_style", "ROUNDED")
        return KeyboardSettings(
            themeMode = p.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            // до 96 dp — для планшетов (на телефоне ползунок доходит до 68)
            keyHeightDp = p.getFloat("key_height_dp", 54f).coerceIn(32f, 96f),
            headerHeightDp = p.getFloat("header_height_dp", 48f).coerceIn(40f, 60f),
            cornerRadiusDp = p.getFloat("corner_radius_dp", 16f).coerceIn(6f, 30f),
            keySpacingDp = p.getFloat("key_spacing_dp", 5f).coerceIn(2f, 8f),
            rowSpacingDp = p.getFloat("row_spacing_dp", 8f).coerceIn(2f, 12f),
            bottomOffsetDp = p.getFloat("bottom_offset_dp", DEFAULT_BOTTOM_OFFSET_DP).coerceIn(BOTTOM_OFFSET_RANGE),
            shapePreset = p.getString("accent_shape_preset", ExpressiveShapePreset.COOKIE_12.id)
                ?: ExpressiveShapePreset.COOKIE_12.id,
            popupShapePreset = p.getString("popup_shape_preset", "") ?: "",
            waveCount = p.getInt("wave_count", 12).coerceIn(4, 18),
            waveAmplitudePercent = p.getFloat("wave_amplitude_percent", 6f).coerceIn(0f, 14f),
            showNumberRow = p.getBoolean("show_number_row", false),
            showKeyBorders = p.getBoolean("show_key_borders", false),
            keyPreview = p.getBoolean("key_preview", true),
            autoCapitalize = p.getBoolean("auto_capitalize", true),
            autoCorrect = p.getBoolean("auto_correct_enabled", true),
            doubleSpacePeriod = p.getBoolean("double_space_period", true),
            hapticsEnabled = p.getBoolean("haptics_enabled", true),
            hapticMode = p.getString("haptic_mode", HapticMode.CUSTOM.id) ?: HapticMode.CUSTOM.id,
            vibrationMs = p.getFloat("vibration_ms", 16f).coerceIn(1f, 80f),
            vibrationAmplitude = p.getFloat("vibration_amplitude", 135f).coerceIn(1f, 255f),
            vibrateOnRelease = p.getBoolean("vibrate_on_release", false),
            strongSpecialKeys = p.getBoolean("strong_special_keys", true),
            soundEnabled = p.getBoolean("sound_enabled", false),
            longPressMs = p.getFloat("long_press_ms", 320f).coerceIn(180f, 700f),
            enterHoldSend = p.getBoolean("enter_hold_send", true),
            enterHoldMs = p.getFloat("enter_hold_ms", 550f).coerceIn(300f, 1500f),
            flickSensitivity = p.getFloat("flick_sensitivity", 0.5f).coerceIn(0f, 1f),
            spaceCursorSpeed = p.getFloat("space_cursor_speed", 1f).coerceIn(0.5f, 3f),
            spaceLineSpeed = p.getFloat("space_line_speed", 1f).coerceIn(0.5f, 3f),
            fontStyle = if (font == null || font == "DEFAULT") "ROUNDED" else font,
            customFontName = p.getString("custom_font_name", "") ?: "",
            customFontVersion = p.getLong("custom_font_version", 0L),
            colorSource = p.getString("color_source", "DYNAMIC") ?: "DYNAMIC",
            seedColor = p.getInt("seed_color", 0xFF6750A4.toInt()),
            colorBackground = p.getInt("color_background", 0),
            colorKey = p.getInt("color_key", 0),
            colorKeyText = p.getInt("color_key_text", 0),
            colorFunc = p.getInt("color_func", 0),
            colorFuncText = p.getInt("color_func_text", 0),
            colorAccent = p.getInt("color_accent", 0),
            colorAccentText = p.getInt("color_accent_text", 0),
            keyOpacity = p.getFloat("key_opacity", 1f).coerceIn(0.2f, 1f),
            keyBackground = p.getBoolean("key_background", true),
            bgTransparency = p.getFloat("bg_transparency", 0f).coerceIn(0f, 1f),
            bgImageVersion = p.getLong("bg_image_version", 0L),
            bgDim = p.getFloat("bg_dim", 0.35f).coerceIn(0f, 0.9f),
            photoFrame = PhotoFrame(
                mode = PhotoFit.entries.firstOrNull { it.name == p.getString("photo_fit", "COVER") } ?: PhotoFit.COVER,
                zoom = p.getFloat("photo_zoom", 1f).coerceIn(0.5f, 3f),
                width = p.getFloat("photo_width", 1f).coerceIn(0.5f, 2f),
                height = p.getFloat("photo_height", 1f).coerceIn(0.5f, 2f),
                x = p.getFloat("photo_x", 0f).coerceIn(-1f, 1f),
                y = p.getFloat("photo_y", 0f).coerceIn(-1f, 1f)
            ),
            bgBlurDp = p.getFloat("bg_blur_dp", 0f).coerceIn(0f, 24f),
            bgFrostStatic = p.getBoolean("bg_frost_static", false),
            dynamicShadow = p.getBoolean("dynamic_shadow", false),
            securityReminder = p.getBoolean("security_reminder", true),
            securityReminderMin = p.getInt("security_reminder_min", 4).coerceIn(1, 1440),
            pluginTrustAck = p.getBoolean(PluginTrust.PREF_KEY, false),
            pluginSettingsLayout = p.getBoolean("plugin_settings_layout", true),
            pluginToolbarLayout = p.getBoolean("plugin_toolbar_layout", true),
            homePins = p.getString("home_pins", "") ?: "",
            settingsWallpaper = p.getLong("settings_wallpaper", 0L),
            settingsGlass = p.getInt("settings_glass", 2).coerceIn(0, 3),
            liveBgVersion = p.getLong("live_bg_version", 0L),
            liveSettingsVersion = p.getLong("live_settings_version", 0L),
            keyLabelScale = p.getFloat("key_label_scale", 1f).coerceIn(0.7f, 1.4f),
            fontWeight = p.getInt("font_weight", 700).coerceIn(300, 900),
            showHints = p.getBoolean("show_hints", true),
            sidePaddingDp = p.getFloat("side_padding_dp", 6f).coerceIn(0f, 32f),
            customDpi = p.getInt("custom_dpi", 0).let { if (it <= 0) 0 else it.coerceIn(120, 640) },
            oneHanded = p.getString("one_handed", "OFF") ?: "OFF",
            oneHandedWidth = p.getFloat("one_handed_w", 0.7f).coerceIn(0.55f, 0.9f),
            floating = p.getBoolean("floating_keyboard", false),
            floatWidth = p.getFloat("float_width", 0.8f).coerceIn(0.5f, 1f),
            floatKeyHeightDp = p.getFloat("float_key_height_dp", 46f).coerceIn(28f, 68f),
            floatX = p.getFloat("float_x", 0.5f).coerceIn(0f, 1f),
            floatY = p.getFloat("float_y", 0.15f).coerceIn(0f, 1f),
            keyboardWidth = p.getFloat("keyboard_width", 1f).coerceIn(0.5f, 1f),
            showHeader = p.getBoolean("show_header", true),
            numberPad = p.getBoolean("number_pad", true),
            liquidGlass = p.getBoolean("liquid_glass", false),
            glassMerge = p.getBoolean("glass_merge", false),
            myanmarVisual = p.getBoolean("myanmar_visual", true),
            showSuggestions = p.getBoolean("show_suggestions", true),
            inlineCalculator = p.getBoolean("inline_calculator", true),
            showMicButton = p.getBoolean("show_mic_button", true),
            micAction = p.getString("mic_action", "VOICE") ?: "VOICE",
            popupStyle = p.getString("popup_style", "SHAPE") ?: "SHAPE",
            keyVariants = (p.getString(UserVariants.PREF, "") ?: "").also { UserVariants.update(it) },
            suggestionsFullWidth = p.getBoolean("suggestions_full_width", false),
            showEmojiKey = p.getBoolean("show_emoji_key", true),
            showGlobeKey = p.getBoolean("show_globe_key", true),
            pressAnimation = p.getFloat("press_animation", 1f).coerceIn(0f, 2f),
            pressHighlight = p.getFloat("press_highlight", 1f).coerceIn(0f, 1f),
            keyPush = p.getBoolean("key_push", false),
            keyPushStrength = p.getFloat("key_push_strength", 4f).coerceIn(1f, 14f),
            keyPushRings = p.getFloat("key_push_rings", 1.5f).coerceIn(1f, 4f),
            keyPushBounce = p.getFloat("key_push_bounce", 0.35f).coerceIn(0.15f, 1f),
            keyPushSpeed = p.getFloat("key_push_speed", 1f).coerceIn(0.5f, 2f),
            keyPushMode = (p.getString("key_push_mode", "AWAY") ?: "AWAY").takeIf { it == "TOWARD" || it == "SWIRL" } ?: "AWAY",
            keyPushSquash = p.getFloat("key_push_squash", 0f).coerceIn(0f, 0.3f),
            buttonWash = p.getBoolean("button_wash", true),
            buttonWashSpeed = p.getFloat("button_wash_speed", 1f).coerceIn(0.5f, 2.5f),
            buttonWashStrength = p.getFloat("button_wash_strength", 0.55f).coerceIn(0.1f, 1f),
            shockwave = p.getBoolean("shockwave", false),
            shockwaveSpeed = p.getFloat("shockwave_speed", 1f).coerceIn(0.5f, 2.5f),
            shockwaveStrength = p.getFloat("shockwave_strength", 0.5f).coerceIn(0.1f, 1f),
            shockwaveReach = p.getFloat("shockwave_reach", 3.5f).coerceIn(1.5f, 6f),
            shockwaveRipple = p.getFloat("shockwave_ripple", 3f).coerceIn(0f, 8f),
            shockwaveRing = p.getBoolean("shockwave_ring", true),
            tiltParallax = p.getBoolean("tilt_parallax", false),
            tiltParallaxStrength = p.getFloat("tilt_parallax_strength", 1f).coerceIn(0.2f, 2.5f),
            tiltParallaxInvert = p.getBoolean("tilt_parallax_invert", false),
            typingGlow = p.getBoolean("typing_glow", false),
            typingGlowEdge = (p.getString("typing_glow_edge", "TOP") ?: "TOP").takeIf { it == "BOTTOM" } ?: "TOP",
            typingGlowColor = (p.getString("typing_glow_color", "ACCENT") ?: "ACCENT").takeIf { it == "RAINBOW" } ?: "ACCENT",
            typingGlowMax = p.getFloat("typing_glow_max", 0.8f).coerceIn(0.2f, 1f),
            typingGlowFullCps = p.getFloat("typing_glow_full_cps", 7f).coerceIn(3f, 14f),
            typingGlowCalm = p.getFloat("typing_glow_calm", 2f).coerceIn(0.5f, 6f),
            typingGlowThickness = p.getFloat("typing_glow_thickness", 4f).coerceIn(2f, 12f),
            edgeSwipe = p.getBoolean("edge_swipe", false),
            edgeSwipeZoneDp = p.getFloat("edge_swipe_zone_dp", 18f).coerceIn(10f, 40f),
            edgeSwipeDistanceDp = p.getFloat("edge_swipe_distance_dp", 64f).coerceIn(30f, 140f),
            edgeSwipeOpposite = (p.getString("edge_swipe_opposite", "EXIT") ?: "EXIT").takeIf { it == "SWITCH" } ?: "EXIT",
            edgeSwipeHaptic = p.getBoolean("edge_swipe_haptic", true),
            perAppThemes = p.getBoolean("per_app_themes", false),
            dayNight = p.getBoolean("day_night", false),
            dayNightMode = (p.getString("day_night_mode", "TIMES") ?: "TIMES").takeIf { it == "SUNSET" || it == "SYSTEM" } ?: "TIMES",
            dayLook = p.getString("day_look", "") ?: "",
            nightLook = p.getString("night_look", "") ?: "",
            dayStartMin = p.getInt("day_start_min", 420).coerceIn(0, 1439),
            nightStartMin = p.getInt("night_start_min", 1140).coerceIn(0, 1439),
            sunLatLon = p.getString("sun_lat_lon", "") ?: "",
            soundKeyPitch = p.getFloat("sound_key_pitch", 0f).coerceIn(0f, 1f),
            soundWobble = p.getFloat("sound_wobble", 0.5f).coerceIn(0f, 1f),
            spaceTicks = p.getBoolean("space_ticks", true),
            spaceTickStrength = (p.getString("space_tick_strength", "NORMAL") ?: "NORMAL").takeIf { it == "SOFT" || it == "STRONG" } ?: "NORMAL",
            spaceTickLines = p.getBoolean("space_tick_lines", true),
            typingStats = p.getBoolean("typing_stats", false),
            emojiKeyWidth = p.getFloat("emoji_key_width", 1f).coerceIn(0.7f, 2.2f),
            emojiKeyIconScale = p.getFloat("emoji_key_icon_scale", 1f).coerceIn(0.7f, 1.8f),
            emojiKeyPos = (p.getString("emoji_key_pos", "DEFAULT") ?: "DEFAULT").takeIf { it == "START" || it == "AFTER_SPACE" } ?: "DEFAULT",
            pageSwitchAnim = p.getFloat("page_switch_anim", 0.5f).coerceIn(0f, 1f),
            panelAnimations = p.getBoolean("panel_animations", true),
            ecoWhen = p.getString("eco_when", null) ?: if (p.getBoolean("eco_mode", true)) "SYSTEM" else "NEVER",
            ecoPress = p.getBoolean("eco_press", true),
            ecoPanels = p.getBoolean("eco_panels", true),
            ecoBlur = p.getBoolean("eco_blur", true),
            ecoTrail = p.getBoolean("eco_trail", true),
            ecoPreview = p.getBoolean("eco_preview", false),
            ecoHaptics = p.getBoolean("eco_haptics", false),
            ecoSound = p.getBoolean("eco_sound", false),
            ecoSuggestions = p.getBoolean("eco_suggestions", false),
            animPress = p.getString("anim_press", "THEME") ?: "THEME",
            animSpeed = p.getFloat("anim_speed", 1f).coerceIn(0.5f, 2f),
            animPopup = p.getString("anim_popup", "POP") ?: "POP",
            animPanel = p.getString("anim_panel", "SCALE") ?: "SCALE",
            customAnims = p.getString("custom_anims", "") ?: "",
            animPanelCfg = p.getString("anim_panel_cfg", "") ?: "",
            animOpen = p.getString("anim_open", "NONE") ?: "NONE",
            animOpenCfg = p.getString("anim_open_cfg", "") ?: "",
            spaceSwipe = p.getString("space_swipe", "CURSOR") ?: "CURSOR",
            spaceSwipeVertical = p.getString("space_swipe_vertical", "OFF") ?: "OFF",
            globeLongPress = p.getString("globe_long_press", "LANGUAGES") ?: "LANGUAGES",
            editBar = p.getBoolean("edit_bar", false),
            toolsLongPress = p.getString("tools_long_press", "PIN") ?: "PIN",
            windowBlur = p.getString("window_blur", "OFF") ?: "OFF",
            windowBlurDp = p.getFloat("window_blur_dp", 32f).coerceIn(4f, 100f),
            backspaceSwipe = p.getBoolean("backspace_swipe", true),
            swipeDownHide = p.getBoolean("swipe_down_hide", true),
            enterWidth = p.getFloat("enter_width", 1f).coerceIn(1f, 1.8f),
            numberRowScale = p.getFloat("number_row_scale", 0.78f).coerceIn(0.6f, 1.1f),
            clipboardHistory = p.getBoolean("clipboard_history", true),
            clipOfferTop = p.getBoolean("clip_offer_top", false),
            // 0 — без ограничения числа записей
            clipboardSize = p.getInt("clipboard_size", 50).let { if (it <= 0) Int.MAX_VALUE else it.coerceIn(5, 5000) },
            clipboardKeep = p.getString("clipboard_keep", "FOREVER") ?: "FOREVER",
            clipScreenshots = p.getBoolean("clip_screenshots", true),
            longTextLimit = p.getInt("long_text_limit", 50_000).let { if (it <= 0) 0 else it.coerceIn(5_000, 5_000_000) },
            longTextPlace = p.getString("long_text_place", "DOWNLOADS") ?: "DOWNLOADS",
            longTextKeep = p.getString("long_text_keep", "7D") ?: "7D",
            longTextMax = p.getInt("long_text_max", 10).coerceIn(3, 100),
            soundVolume = p.getFloat("sound_volume", 0.6f).coerceIn(0.05f, 1f),
            giphyKey = SecretBox.decrypt(p.getString("giphy_key", "") ?: ""),
            klipyKey = SecretBox.decrypt(p.getString("klipy_key", "") ?: ""),
            anthropicKey = SecretBox.decrypt(p.getString("anthropic_key", "") ?: ""),
            aiProvider = p.getString("ai_provider", "AUTO") ?: "AUTO",
            geminiKey = SecretBox.decrypt(p.getString("gemini_key", "") ?: ""),
            openaiKey = SecretBox.decrypt(p.getString("openai_key", "") ?: ""),
            groqKey = SecretBox.decrypt(p.getString("groq_key", "") ?: ""),
            openrouterKey = SecretBox.decrypt(p.getString("openrouter_key", "") ?: ""),
            geminiModel = (p.getString("gemini_model", "") ?: "").trim(),
            claudeModel = (p.getString("anthropic_model", "") ?: "").trim(),
            openaiModel = (p.getString("openai_model", "") ?: "").trim(),
            groqModel = (p.getString("groq_model", "") ?: "").trim(),
            openrouterModel = (p.getString("openrouter_model", "") ?: "").trim(),
            aiCustom = CustomAi.parse(SecretBox.decrypt(p.getString("ai_custom", "") ?: "").ifEmpty { "[]" }),
            instantInput = p.getBoolean("instant_input", true),
            glideTyping = p.getBoolean("glide_typing", true),
            glideTrail = p.getBoolean("glide_trail", true),
            swipeUpHint = p.getBoolean("swipe_up_hint", true),
            spaceLongPress = p.getBoolean("space_long_press", true),
            spaceLongAction = if (!p.getBoolean("space_long_press", true)) "OFF" else p.getString("space_long_action", "PICKER") ?: "PICKER",
            customSoundVersion = p.getLong("custom_sound_version", 0L),
            // до выбора стилей свой файл включался сам, как только его загрузили
            soundStyle = p.getString("sound_style", null)
                ?: if (p.getLong("custom_sound_version", 0L) != 0L) "CUSTOM" else "SYSTEM",
            autoPair = p.getBoolean("auto_pair", false),
            incognito = p.getBoolean("incognito", false),
            skinPlugin = p.getString("skin_plugin", "") ?: "",
            skinOptions = p.getString("skin_options", "") ?: "",
            languages = p.getString("languages", "ru,en")?.ifBlank { "ru,en" } ?: "ru,en",
            autoCorrectStrength = p.getString("auto_correct_strength", "NORMAL") ?: "NORMAL",
            dictAutoDownload = p.getBoolean("dict_auto_download", true),
            punctuationKeys = p.getBoolean("punctuation_keys", true),
            spaceLeftKey = p.getString("space_left_key", "") ?: "",
            spaceRightKey = p.getString("space_right_key", "") ?: "",
            systemSpellcheck = p.getBoolean("system_spellcheck", true),
            smartPunctuation = p.getBoolean("smart_punctuation", true),
            autoSpacePunct = p.getBoolean("auto_space_punct", true),
            dashMerge = p.getBoolean("dash_merge", true),
            correctEverywhere = p.getBoolean("correct_everywhere", true),
            dynamicZones = p.getBoolean("dynamic_zones", true),
            aiVoiceCleanup = p.getBoolean("ai_voice_cleanup", true),
            toolsButton = p.getBoolean("tools_button", true),
            pinnedTools = p.getBoolean("pinned_tools", false),
            holdMode = p.getString("hold_mode", "VARIANTS") ?: "VARIANTS",
            landscapeSplit = p.getBoolean("landscape_split", true),
            landscapeScale = p.getFloat("landscape_scale", 1f).coerceIn(0.7f, 1.8f),
            langLayouts = p.getString("lang_layouts", "") ?: "",
            translateTarget = p.getString("translate_target", "en") ?: "en",
            toolbarOrder = p.getString("toolbar_order", "") ?: "",
            toolbarHidden = p.getString("toolbar_hidden", "") ?: "",
            userLayouts = p.getString("user_layouts", "") ?: "",
            emojiFontVersion = p.getLong("emoji_font_version", 0L),
            emojiStyle = p.getString("emoji_style", null) ?: if (p.getLong("emoji_font_version", 0L) != 0L) "CUSTOM" else "SYSTEM",
            emojiReturnToKeyboard = p.getBoolean("emoji_return_keyboard", false),
            appLanguage = p.getString("app_language", null).let { if (it == "ru" || it == "en") it else "SYSTEM" },
            designStyle = p.getString("design_style", "EXPRESSIVE") ?: "EXPRESSIVE",
            shapeAmount = p.getString("shape_amount", ShapeAmount.FULL) ?: ShapeAmount.FULL
        )
    }

    const val EMOJI_FONT_FILENAME = "emoji_font.ttf"

    fun setEmojiFont(context: Context) = edit(context) {
        putLong("emoji_font_version", System.currentTimeMillis())
        putString("emoji_style", "CUSTOM")
    }

    fun resetEmojiFont(context: Context) {
        File(context.filesDir, EMOJI_FONT_FILENAME).delete()
        edit(context) {
            putLong("emoji_font_version", 0L)
            putString("emoji_style", "SYSTEM")
        }
    }

    /** Типы ключей настроек — плагин может менять любой из них блоком "settings". */
    val KEY_TYPES: Map<String, Char> = buildMap {
        listOf(
            "key_height_dp", "header_height_dp", "landscape_scale", "corner_radius_dp", "key_spacing_dp", "row_spacing_dp", "bottom_offset_dp",
            "wave_amplitude_percent", "vibration_ms", "vibration_amplitude", "long_press_ms", "enter_hold_ms", "key_opacity", "bg_transparency", "bg_dim",
            "photo_zoom", "photo_width", "photo_height", "photo_x", "photo_y",
            "bg_blur_dp", "window_blur_dp", "key_label_scale", "side_padding_dp", "one_handed_w", "float_width", "float_key_height_dp", "flick_sensitivity", "space_cursor_speed", "space_line_speed", "enter_width", "number_row_scale", "press_animation", "press_highlight", "key_push_strength", "key_push_rings", "key_push_bounce", "key_push_speed", "key_push_squash", "button_wash_speed", "button_wash_strength", "shockwave_speed", "shockwave_strength", "shockwave_reach", "shockwave_ripple", "tilt_parallax_strength", "typing_glow_max", "typing_glow_full_cps", "typing_glow_calm", "typing_glow_thickness", "edge_swipe_zone_dp", "edge_swipe_distance_dp", "sound_key_pitch", "sound_wobble", "emoji_key_width", "emoji_key_icon_scale", "page_switch_anim", "sound_volume", "anim_speed"
        ).forEach { put(it, 'f') }
        listOf(
            "wave_count", "seed_color", "color_background", "color_key", "color_key_text", "color_func",
            "color_func_text", "color_accent", "color_accent_text", "font_weight", "clipboard_size", "custom_dpi", "day_start_min", "night_start_min"
        ).forEach { put(it, 'i') }
        listOf(
            "dynamic_shadow", "bg_frost_static", "show_number_row", "show_key_borders", "key_preview", "key_background", "emoji_return_keyboard", "auto_capitalize", "auto_correct_enabled",
            "double_space_period", "haptics_enabled", "vibrate_on_release", "strong_special_keys", "sound_enabled",
            "show_hints", "show_header", "number_pad", "floating_keyboard", "liquid_glass", "glass_merge", "myanmar_visual", "show_suggestions", "inline_calculator", "enter_hold_send", "show_mic_button", "suggestions_full_width", "show_emoji_key", "show_globe_key", "panel_animations",
            "backspace_swipe", "swipe_down_hide", "clipboard_history", "clip_offer_top", "key_push", "button_wash", "shockwave", "shockwave_ring", "tilt_parallax", "tilt_parallax_invert", "typing_glow", "edge_swipe", "edge_swipe_haptic", "per_app_themes", "day_night", "space_ticks", "space_tick_lines", "typing_stats", "instant_input", "glide_typing", "glide_trail", "swipe_up_hint",
            "space_long_press", "auto_pair", "incognito", "dict_auto_download", "punctuation_keys", "smart_punctuation",
            "system_spellcheck", "edit_bar", "auto_space_punct", "eco_mode", "eco_press", "eco_panels", "eco_blur", "eco_trail", "eco_preview", "eco_haptics", "eco_sound", "eco_suggestions", "dash_merge", "clip_screenshots", "correct_everywhere", "dynamic_zones", "ai_voice_cleanup", "tools_button", "pinned_tools", "landscape_split"
        ).forEach { put(it, 'b') }
        listOf(
            "theme_mode", "accent_shape_preset", "popup_shape_preset", "haptic_mode", "font_style", "color_source", "one_handed", "space_swipe", "photo_fit", "key_push_mode", "emoji_key_pos", "typing_glow_edge", "typing_glow_color", "edge_swipe_opposite", "day_night_mode", "space_tick_strength",
            "eco_when", "globe_long_press", "tools_long_press", "window_blur", "hold_mode", "ai_provider", "space_long_action", "languages", "auto_correct_strength", "translate_target", "gemini_model", "anthropic_model", "openai_model", "groq_model", "openrouter_model", "toolbar_order", "toolbar_hidden", "user_layouts",
            "space_left_key", "space_right_key", "mic_action", "popup_style", "key_variants", "sound_style", "emoji_style", "clipboard_keep", "app_language", "design_style", "shape_amount", "skin_options", "anim_press", "anim_popup", "anim_panel", "anim_panel_cfg", "anim_open", "anim_open_cfg", "custom_anims", "lang_layouts"
        ).forEach { put(it, 's') }
    }

    /** Применяет блок "settings" плагина; неизвестные ключи и неверные типы пропускаются. */
    fun applyPluginSettings(context: Context, values: Map<String, Any>): Int {
        var n = 0
        edit(context) {
            for ((k, v) in values) {
                val ok = when (KEY_TYPES[k]) {
                    'f' -> (v as? Number)?.let { putFloat(k, it.toFloat()) } != null
                    'i' -> when (v) {
                        is Number -> { putInt(k, v.toInt()); true }
                        is String -> PluginParser.parseColor(v)?.let { putInt(k, it) } != null
                        else -> false
                    }
                    'b' -> (v as? Boolean)?.let { putBoolean(k, it) } != null
                    's' -> (v as? String)?.takeIf { !(k == "font_style" && it == "CUSTOM") }?.let { putString(k, it) } != null
                    else -> false
                }
                if (ok) n++
            }
        }
        return n
    }

    const val BG_IMAGE_FILENAME = "keyboard_background.jpg"

    fun setBackgroundImage(context: Context) = edit(context) { putLong("bg_image_version", System.currentTimeMillis()) }

    fun clearBackgroundImage(context: Context) {
        File(context.filesDir, BG_IMAGE_FILENAME).delete()
        edit(context) { putLong("bg_image_version", 0L) }
    }

    fun resetColors(context: Context) = edit(context) {
        listOf(
            "color_background", "color_key", "color_key_text", "color_func",
            "color_func_text", "color_accent", "color_accent_text", "key_opacity", "key_background", "bg_transparency"
        ).forEach { remove(it) }
    }

    /** Резервная копия: все настройки с типами, без словаря и файлов. */
    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("format", "expressive-keyboard-settings")
        root.put("version", 1)
        val values = JSONObject()
        for ((k, v) in prefs(context).all) {
            // ключи API в файл настроек не попадают: его могут переслать или выложить
            if (k in SecretBox.SECRET_PREFS) continue
            val item = JSONObject()
            when (v) {
                is Boolean -> item.put("t", "b").put("v", v)
                is Int -> item.put("t", "i").put("v", v)
                is Long -> item.put("t", "l").put("v", v)
                is Float -> item.put("t", "f").put("v", v.toDouble())
                is String -> item.put("t", "s").put("v", v)
                else -> continue
            }
            values.put(k, item)
        }
        root.put("values", values)
        return root.toString(2)
    }

    /** Возвращает число применённых значений; файлы шрифта/фона из копии не переносятся. */
    fun importJson(context: Context, raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "expressive-keyboard-settings") { "Это не файл настроек клавиатуры" }
        val values = root.getJSONObject("values")
        var n = 0
        edit(context) {
            val keys = values.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "bg_image_version" || k == "custom_font_version" || k == "custom_font_name" || k == "emoji_font_version") continue
                if (k in SecretBox.SECRET_PREFS) continue
                val item = values.optJSONObject(k) ?: continue
                // тип берём из приложения, а не из файла: копия от другой версии могла хранить число иначе
                val type = KEY_TYPES[k]?.toString() ?: item.optString("t")
                when (type) {
                    "b" -> putBoolean(k, item.optBoolean("v"))
                    "i" -> putInt(k, item.optDouble("v").toInt())
                    "l" -> putLong(k, item.optLong("v"))
                    "f" -> putFloat(k, item.optDouble("v").toFloat())
                    "s" -> putString(k, item.optString("v"))
                    else -> continue
                }
                n++
            }
            if (values.optJSONObject("font_style")?.optString("v") == "CUSTOM") putString("font_style", "ROUNDED")
            // файлы шрифта эмодзи и фона из копии не переносятся — свой стиль эмодзи без файла не включаем
            if (values.optJSONObject("emoji_style")?.optString("v") == "CUSTOM") putString("emoji_style", "SYSTEM")
        }
        return n
    }

    fun applyPreset(context: Context, preset: ExpressiveShapePreset) = edit(context) {
        putString("accent_shape_preset", preset.id)
        putInt("wave_count", preset.defaultWaves)
        putFloat("wave_amplitude_percent", preset.defaultAmplitude)
    }

    fun saveCustomFont(context: Context, name: String) = edit(context) {
        putString("font_style", "CUSTOM")
        putString("custom_font_name", name)
        putLong("custom_font_version", System.currentTimeMillis())
    }

    fun resetCustomFont(context: Context) {
        File(context.filesDir, CUSTOM_FONT_FILENAME).delete()
        edit(context) {
            putString("font_style", "ROUNDED")
            putString("custom_font_name", "")
            putLong("custom_font_version", System.currentTimeMillis())
        }
    }

    fun resetAll(context: Context) {
        File(context.filesDir, CUSTOM_FONT_FILENAME).delete()
        File(context.filesDir, EMOJI_FONT_FILENAME).delete()
        File(context.filesDir, BG_IMAGE_FILENAME).delete()
        edit(context) { clear() }
    }
}
