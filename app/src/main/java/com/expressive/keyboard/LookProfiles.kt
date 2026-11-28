package com.expressive.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A typed copy of some preference keys. Every entry is {k: key, t: type, v: value}; the type is b, i, l, f or s, and the
 * type "x" means "this key was not set" (the default applies). Reading checks the key and the type against a whitelist,
 * so a file from a friend can only change the keys it is meant to change, with values of the right kind.
 */
internal object PrefSnapshot {
    fun capture(p: SharedPreferences, keys: Collection<String>): JSONArray {
        val all = p.all
        val a = JSONArray()
        for (k in keys) {
            val o = JSONObject().put("k", k)
            when (val v = all[k]) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                else -> o.put("t", "x")
            }
            a.put(o)
        }
        return a
    }

    /** key to value (null = not set). Unknown keys, wrong types and odd numbers are left out. */
    fun decode(a: JSONArray?, allowed: Map<String, Char>, valid: (String, Any) -> Boolean = { _, _ -> true }): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("k")
            val want = allowed[k] ?: continue
            val t = o.optString("t")
            if (t == "x") {
                out[k] = null
                continue
            }
            if (t.length != 1 || t[0] != want) continue
            val raw = o.opt("v")
            val v: Any = when (want) {
                'b' -> raw as? Boolean
                'i' -> (raw as? Number)?.toInt()
                'l' -> (raw as? Number)?.toLong()
                'f' -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() }
                's' -> (raw as? String)?.takeIf { it.length <= 4000 }
                else -> null
            } ?: continue
            if (valid(k, v)) out[k] = v
        }
        return out
    }

    /** Writes the values; a null value removes the key. */
    fun write(e: SharedPreferences.Editor, values: Map<String, Any?>) {
        for ((k, v) in values) when (v) {
            null -> e.remove(k)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
    }
}

/**
 * Preferences with a few values replaced (or hidden): the keyboard reads its settings through this while a look is
 * worn for an app or for the time of day. Nothing is written to the real preferences, so the Settings screen and
 * your own theme stay as they are. A null value means "not set".
 */
internal class OverlayPrefs(private val base: SharedPreferences, private val over: Map<String, Any?>) : SharedPreferences by base {
    override fun contains(key: String): Boolean = if (over.containsKey(key)) over[key] != null else base.contains(key)

    override fun getString(key: String, defValue: String?): String? =
        if (over.containsKey(key)) (over[key] as? String ?: defValue) else base.getString(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        if (over.containsKey(key)) (over[key] as? Boolean ?: defValue) else base.getBoolean(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float =
        if (over.containsKey(key)) ((over[key] as? Number)?.toFloat() ?: defValue) else base.getFloat(key, defValue)

    override fun getInt(key: String, defValue: Int): Int =
        if (over.containsKey(key)) ((over[key] as? Number)?.toInt() ?: defValue) else base.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long =
        if (over.containsKey(key)) ((over[key] as? Number)?.toLong() ?: defValue) else base.getLong(key, defValue)

    override fun getAll(): MutableMap<String, *> {
        val m = HashMap<String, Any?>(base.all)
        for ((k, v) in over) if (v == null) m.remove(k) else m[k] = v
        return m
    }
}

/** Saved looks: a name and a copy of the theme keys (colours, shapes, font, glass, skin). */
internal object LookStore {
    private const val KEY = "look_presets"
    const val MAX = 30

    val KEYS: Map<String, Char> = buildMap {
        "theme_mode design_style color_source accent_shape_preset popup_shape_preset shape_amount font_style window_blur skin_plugin skin_options popup_style photo_fit"
            .split(' ').forEach { put(it, 's') }
        "seed_color color_background color_key color_key_text color_func color_func_text color_accent color_accent_text font_weight wave_count"
            .split(' ').forEach { put(it, 'i') }
        "key_opacity bg_transparency bg_dim bg_blur_dp window_blur_dp corner_radius_dp wave_amplitude_percent photo_zoom photo_width photo_height photo_x photo_y"
            .split(' ').forEach { put(it, 'f') }
        "liquid_glass glass_merge show_key_borders key_background bg_frost_static".split(' ').forEach { put(it, 'b') }
    }

    private fun prefs(c: Context) = KeyboardPrefs.prefs(c)

    private fun all(c: Context): JSONObject = runCatching { JSONObject(prefs(c).getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())

    fun clean(name: String): String = name.filter { !it.isISOControl() }.trim().take(30)

    fun names(c: Context): List<String> = all(c).keys().asSequence().toList().sorted()

    /** Saves the theme you wear now under [name] (an old look with the same name is replaced). False if the name is empty or there are too many. */
    fun save(c: Context, name: String): Boolean {
        val n = clean(name)
        if (n.isEmpty() || n == AutoLookRules.BASE) return false
        val o = all(c)
        if (!o.has(n) && o.length() >= MAX) return false
        o.put(n, PrefSnapshot.capture(prefs(c), KEYS.keys))
        prefs(c).edit().putString(KEY, o.toString()).apply()
        return true
    }

    fun delete(c: Context, name: String) {
        val o = all(c)
        o.remove(name)
        prefs(c).edit().putString(KEY, o.toString()).apply()
    }

    fun values(c: Context, name: String): Map<String, Any?>? {
        val a = all(c).optJSONArray(name) ?: return null
        return PrefSnapshot.decode(a, KEYS)
    }

    /** Makes the look your normal theme (writes it into the settings). */
    fun applyNow(c: Context, name: String): Boolean {
        val v = values(c, name) ?: return false
        KeyboardPrefs.edit(c) { PrefSnapshot.write(this, v) }
        return true
    }
}

/** Which look an app gets: package name to look name (or [AutoLookRules.BASE] for "my normal theme"). */
internal object AppLooks {
    private const val KEY = "app_themes"

    fun map(c: Context): Map<String, String> {
        val o = runCatching { JSONObject(KeyboardPrefs.prefs(c).getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    /** A null look removes the rule. */
    fun set(c: Context, pkg: String, look: String?) {
        val m = LinkedHashMap(map(c))
        if (look == null) m.remove(pkg) else m[pkg] = look
        KeyboardPrefs.prefs(c).edit().putString(KEY, JSONObject(m as Map<*, *>).toString()).apply()
    }
}

/** Picks the look for the app you type in and for the time of day, and wears it. Nothing is saved. */
internal object AutoLook {
    fun update(context: Context, s: KeyboardSettings, pkg: String) {
        if (!s.perAppThemes && !s.dayNight) {
            KeyboardPrefs.setLookOverride(context, null)
            return
        }
        val name = AutoLookRules.pick(
            pkg, s.perAppThemes, AppLooks.map(context), s.dayNight, isNight(context, s),
            s.dayLook, s.nightLook, LookStore.names(context).toSet()
        )
        KeyboardPrefs.setLookOverride(context, if (name.isEmpty()) null else LookStore.values(context, name))
    }

    fun isNight(context: Context, s: KeyboardSettings): Boolean {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return when (s.dayNightMode) {
            "SYSTEM" -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            "SUNSET" -> {
                val day = sunToday(s, cal)
                if (day == null) SunTimes.inNight(now, s.nightStartMin, s.dayStartMin) else SunTimes.inNight(now, day.sunset, day.sunrise)
            }
            else -> SunTimes.inNight(now, s.nightStartMin, s.dayStartMin)
        }
    }

    /** Sunrise and sunset for the typed latitude and longitude; null when they are missing or the sun does not set. */
    fun sunToday(s: KeyboardSettings, cal: Calendar = Calendar.getInstance()): SunTimes.Day? {
        val ll = SunTimes.parseLatLon(s.sunLatLon) ?: return null
        val offset = cal.timeZone.getOffset(cal.timeInMillis) / 60_000
        return SunTimes.compute(ll.first, ll.second, cal.get(Calendar.DAY_OF_YEAR), offset)
    }
}

/** Layout profiles: size, position, DPI and gaps saved under a name; one profile exports to a small file for a friend. */
internal object LayoutProfiles {
    private const val KEY = "layout_profiles"
    const val MAX = 20
    private const val FORMAT = "expressive-keyboard-layout-profile"
    private const val MAX_FILE = 64 * 1024

    val KEYS: Map<String, Char> = buildMap {
        "key_height_dp header_height_dp landscape_scale key_spacing_dp row_spacing_dp bottom_offset_dp side_padding_dp keyboard_width one_handed_w float_width float_key_height_dp float_x float_y key_label_scale number_row_scale enter_width emoji_key_width emoji_key_icon_scale"
            .split(' ').forEach { put(it, 'f') }
        put("custom_dpi", 'i')
        "floating_keyboard show_number_row landscape_split".split(' ').forEach { put(it, 'b') }
        "one_handed emoji_key_pos".split(' ').forEach { put(it, 's') }
    }

    private val STRINGS = mapOf(
        "one_handed" to setOf("OFF", "LEFT", "RIGHT"),
        "emoji_key_pos" to setOf("DEFAULT", "START", "AFTER_SPACE")
    )

    private fun valid(key: String, v: Any): Boolean {
        val allowed = STRINGS[key] ?: return true
        return v is String && v in allowed
    }

    private fun prefs(c: Context) = KeyboardPrefs.prefs(c)

    private fun all(c: Context): JSONObject = runCatching { JSONObject(prefs(c).getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())

    fun names(c: Context): List<String> = all(c).keys().asSequence().toList().sorted()

    fun save(c: Context, name: String): Boolean {
        val n = LookStore.clean(name)
        if (n.isEmpty()) return false
        val o = all(c)
        if (!o.has(n) && o.length() >= MAX) return false
        o.put(n, PrefSnapshot.capture(prefs(c), KEYS.keys))
        prefs(c).edit().putString(KEY, o.toString()).apply()
        return true
    }

    fun delete(c: Context, name: String) {
        val o = all(c)
        o.remove(name)
        prefs(c).edit().putString(KEY, o.toString()).apply()
    }

    fun apply(c: Context, name: String): Boolean {
        val a = all(c).optJSONArray(name) ?: return false
        val v = PrefSnapshot.decode(a, KEYS) { k, v -> valid(k, v) }
        if (v.isEmpty()) return false
        KeyboardPrefs.edit(c) { PrefSnapshot.write(this, v) }
        return true
    }

    /** The text of the file for one profile. */
    fun export(c: Context, name: String): String? {
        val a = all(c).optJSONArray(name) ?: return null
        return JSONObject().put("format", FORMAT).put("version", 1).put("name", name).put("values", a).toString(2)
    }

    /** Reads a profile file and saves it. Returns the name it was saved under, or an error. */
    fun import(c: Context, text: String): Result<String> = runCatching {
        require(text.length <= MAX_FILE) { "The file is too big" }
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "This is not a layout profile file" }
        require(o.optInt("version") == 1) { "This profile needs a newer app version" }
        val values = PrefSnapshot.decode(o.optJSONArray("values"), KEYS) { k, v -> valid(k, v) }
        require(values.isNotEmpty()) { "The profile is empty" }
        var name = LookStore.clean(o.optString("name")).ifEmpty { "Imported" }
        val store = all(c)
        if (store.has(name)) {
            var i = 2
            while (store.has("$name ($i)")) i++
            name = "$name ($i)".take(36)
        }
        require(store.length() < MAX) { "Too many profiles. Delete one first" }
        // only the checked values are saved, never the raw file
        val clean = JSONArray()
        for ((k, v) in values) {
            val e = JSONObject().put("k", k)
            when (v) {
                null -> e.put("t", "x")
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                else -> continue
            }
            clean.put(e)
        }
        store.put(name, clean)
        prefs(c).edit().putString(KEY, store.toString()).apply()
        name
    }
}
