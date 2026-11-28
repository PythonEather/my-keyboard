package com.expressive.keyboard

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
// КАТЕГОРИИ БУФЕРА ОБМЕНА
// Свои папки для записей: «Пароли», «Работа» и т. д. Встроенные фильтры (все, картинки, ссылки) считаются сами,
// а свои категории хранятся здесь. Запись, отнесённая к категории, закрепляется, чтобы её не стёрло
// «Очистить» и срок хранения. «Скрывать текст» только прячет текст точками на плитке, это не шифрование.
// ============================================================================

@Immutable
data class ClipCategory(val id: String, val name: String, val secret: Boolean)

internal object ClipCategories {
    const val MAX = 12
    val categories = mutableStateListOf<ClipCategory>()
    /** Запись буфера → id категории. */
    val assigned = mutableStateMapOf<String, String>()

    private var loaded = false
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    private fun file(context: Context) = File(context.filesDir, "clip_categories.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val cats = root.optJSONArray("cats") ?: JSONArray()
            for (i in 0 until cats.length()) {
                val o = cats.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) categories += ClipCategory(id, name, o.optBoolean("secret", false))
            }
            val map = root.optJSONObject("map")
            if (map != null) {
                val ids = categories.map { it.id }.toSet()
                for (k in map.keys()) {
                    val v = map.optString(k)
                    if (v in ids) assigned[k] = v
                }
            }
        }
    }

    private fun save(context: Context) {
        val app = context.applicationContext
        val cats = JSONArray()
        categories.forEach { cats.put(JSONObject().put("id", it.id).put("name", it.name).put("secret", it.secret)) }
        val map = JSONObject()
        assigned.entries.toList().forEach { (k, v) -> map.put(k, v) }
        val json = JSONObject().put("cats", cats).put("map", map).toString()
        saver.launch { runCatching { file(app).writeText(json) } }
    }

    /** Новая категория; null, если имя пустое, уже занято или достигнут предел. */
    fun create(context: Context, name: String, secret: Boolean): ClipCategory? {
        load(context)
        val n = name.trim().take(24)
        if (n.isEmpty() || categories.size >= MAX || categories.any { it.name.equals(n, ignoreCase = true) }) return null
        val c = ClipCategory("c${System.currentTimeMillis()}", n, secret)
        categories += c
        save(context)
        return c
    }

    fun setSecret(context: Context, id: String, secret: Boolean) {
        val i = categories.indexOfFirst { it.id == id }
        if (i < 0) return
        categories[i] = categories[i].copy(secret = secret)
        save(context)
    }

    fun delete(context: Context, id: String) {
        categories.removeAll { it.id == id }
        assigned.entries.toList().filter { it.value == id }.forEach { assigned.remove(it.key) }
        save(context)
    }

    /** id == null — убрать из категории. */
    fun assign(context: Context, entry: String, id: String?) {
        if (id == null) assigned.remove(entry) else assigned[entry] = id
        save(context)
    }

    fun byId(id: String?): ClipCategory? = if (id == null) null else categories.firstOrNull { it.id == id }

    private val link = Regex("""^\s*(?:https?://|www\.)\S+\s*$""", RegexOption.IGNORE_CASE)

    fun isLink(entry: String): Boolean = !ClipImages.isImage(entry) && !LongTexts.isLong(entry) && entry.length < 2000 && link.matches(entry)

    fun mask(text: String): String = "•".repeat(text.trim().length.coerceIn(4, 12))

    /** Names offered with one tap in the keyboard panel. */
    val presets = listOf("Пароли" to true, "Работа" to false, "Личное" to false, "Коды" to true, "Адреса" to false, "Заметки" to false)
}
