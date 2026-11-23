package com.expressive.keyboard

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** Manifest block "toolbarLayout": button ids as in PLUGINS.md (emoji, gif, clipboard, ..., "button:<id>" for the plugin's own). */
@Immutable
data class ToolbarLayoutSpec(val order: List<String>, val hidden: List<String>)

/**
 * Lets a plugin with the "toolbar_layout" permission arrange the keyboard toolbar: the buttons it names come first,
 * in its order, the rest keep the user's order after them, and it can hide buttons. The Plugins and Settings buttons
 * can never be hidden, so a plugin cannot take away the way to switch it off. The user can turn the whole thing off
 * in Plugins (plugin security) or with one tap on the Toolbar settings page.
 */
internal object PluginToolbarLayout {
    private val ALWAYS = setOf("plugins", "settings")

    /** [pluginId] expands the short form "button:<id>" to the full id of the plugin's own script button. */
    fun parse(o: JSONObject?, pluginId: String): ToolbarLayoutSpec? {
        if (o == null) return null
        fun list(key: String): List<String> = o.optJSONArray(key)
            ?.let { a ->
                (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }.take(60)
                    .map { if (it.startsWith("button:")) "btn:$pluginId:" + it.removePrefix("button:") else it }
            } ?: emptyList()
        val spec = ToolbarLayoutSpec(list("order"), list("hidden"))
        return spec.takeIf { it.order.isNotEmpty() || it.hidden.isNotEmpty() }
    }

    /** The enabled plugin that arranges the toolbar right now (the first one), or null. */
    fun owner(allowed: Boolean): KeyboardPlugin? {
        if (!allowed) return null
        val off = PluginRepository.disabled
        return PluginRepository.plugins.firstOrNull { it.toolbarLayout != null && "toolbar_layout" in it.permissions && it.id !in off }
    }

    /** Visible buttons in their final order (or all of them with [includeHidden]). [ids] is the default order. */
    fun effective(
        ids: List<String>, userOrder: List<String>, userHidden: Set<String>, spec: ToolbarLayoutSpec?, includeHidden: Boolean = false
    ): List<String> {
        if (spec == null) return Toolbar.arrange(ids, userOrder, userHidden, includeHidden)
        val first = spec.order.filter { it in ids }.distinct()
        val rest = Toolbar.arrange(ids, userOrder, emptySet(), includeHidden = true).filter { it !in first }
        val all = first + rest
        if (includeHidden) return all
        val off = spec.hidden.filter { it !in ALWAYS }.toSet() + userHidden
        return all.filter { it !in off }
    }
}
