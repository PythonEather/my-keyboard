package com.expressive.keyboard

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** Manifest block "settingsLayout": page names as in PLUGINS.md (themes, colors, size, keys, layout, languages, ...). */
@Immutable
data class SettingsLayoutSpec(val order: List<String>, val pinned: List<String>, val hidden: List<String>)

/**
 * Lets a plugin with the "settings_layout" permission arrange the Settings home screen: show some pages first
 * ("pinned"), reorder the rest ("order") and hide pages ("hidden"). Only the order and visibility change; the pages
 * themselves stay native, and search still finds every page. Plugins, Backup and About can never be hidden, so a
 * plugin cannot hide the place where it is switched off. The user can switch the whole thing off in Plugins.
 */
internal object PluginSettingsLayout {
    private val ALWAYS = setOf(SettingsPage.HOME, SettingsPage.PLUGINS, SettingsPage.BACKUP, SettingsPage.ABOUT)

    fun parse(o: JSONObject?): SettingsLayoutSpec? {
        if (o == null) return null
        fun list(key: String): List<String> = o.optJSONArray(key)
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(40) } ?: emptyList()
        val spec = SettingsLayoutSpec(list("order"), list("pinned"), list("hidden"))
        return spec.takeIf { it.order.isNotEmpty() || it.pinned.isNotEmpty() || it.hidden.isNotEmpty() }
    }

    /** The enabled plugin that arranges Settings right now (the first one), or null. */
    fun owner(allowed: Boolean): KeyboardPlugin? {
        if (!allowed) return null
        val off = PluginRepository.disabled
        return PluginRepository.plugins.firstOrNull { it.settingsLayout != null && "settings_layout" in it.permissions && it.id !in off }
    }

    private fun pages(names: List<String>): List<SettingsPage> = names.mapNotNull { raw ->
        val n = raw.trim().replace('-', '_').replace(' ', '_')
        SettingsPage.entries.firstOrNull { it.name.equals(n, ignoreCase = true) }
    }.distinct()

    private fun hidden(spec: SettingsLayoutSpec): Set<SettingsPage> = pages(spec.hidden).filter { it !in ALWAYS }.toSet()

    fun pinned(spec: SettingsLayoutSpec?): List<SettingsPage> {
        if (spec == null) return emptyList()
        val off = hidden(spec)
        return pages(spec.pinned).filter { it != SettingsPage.HOME && it !in off }
    }

    fun arrange(list: List<SettingsPage>, spec: SettingsLayoutSpec?): List<SettingsPage> {
        if (spec == null) return list
        val off = hidden(spec)
        val top = pinned(spec).toSet()
        val order = pages(spec.order)
        return list.filter { it !in off && it !in top }
            .sortedBy { p -> order.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
