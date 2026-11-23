package com.expressive.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** What one enabled plugin may do with sensitive permissions, and which of them it is using right now. */
internal data class SensitiveUse(val id: String, val name: String, val perms: Set<String>, val live: Set<String>)

internal object PluginSafety {
    /** Permissions that give a plugin access to what the user types, hears, does or plays. */
    val SENSITIVE = setOf("read_text", "clipboard", "microphone", "sensors", "media", "app_info")

    /** Time of the last reminder (0 — no plugin with sensitive access has been seen yet). Not saved: per process. */
    var lastReminderAt = 0L

    fun label(perm: String): String = when (perm) {
        "read_text" -> tr("typed text")
        "clipboard" -> tr("clipboard")
        "microphone" -> tr("microphone level")
        "sensors" -> tr("motion sensors")
        "media" -> tr("now playing")
        "app_info" -> tr("current app")
        else -> perm
    }

    /** The cases that keep the red dot on even when the reminder is switched off. */
    fun dangerous(u: SensitiveUse): Boolean =
        "microphone" in u.live || ("network" in u.perms && u.live.any { it == "read_text" || it == "clipboard" || it == "media" })
}

private val WarnRed = Color(0xFFE53935)

/**
 * Drawn by the keyboard itself, above everything a plugin can draw, so a plugin cannot cover it.
 * - a small red dot while a plugin is actually using a sensitive permission;
 * - a banner with a red warning icon every N minutes (setting, can be switched off) while a plugin has sensitive access.
 * The dot does not catch touches. Nothing runs while the keyboard is not on screen.
 */
@Composable
internal fun BoxScope.SecurityIndicatorLayer(settings: KeyboardSettings) {
    val context = LocalContext.current
    var uses by remember { mutableStateOf(emptyList<SensitiveUse>()) }
    var banner by remember { mutableStateOf(false) }
    val interval = settings.securityReminderMin * 60_000L
    LaunchedEffect(settings.securityReminder, interval) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val cur = ScriptHost.sensitiveUse()
            if (cur != uses) uses = cur
            if (cur.isEmpty()) PluginSafety.lastReminderAt = 0L
            else if (PluginSafety.lastReminderAt == 0L) PluginSafety.lastReminderAt = now
            if (settings.securityReminder && cur.isNotEmpty() && now - PluginSafety.lastReminderAt >= interval) {
                PluginSafety.lastReminderAt = now
                banner = true
                delay(7000)
                banner = false
            }
            delay(2000)
        }
    }
    val live = uses.filter { it.live.isNotEmpty() }
    if (live.isNotEmpty() && (settings.securityReminder || live.any { PluginSafety.dangerous(it) })) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(8.dp).clip(CircleShape).background(WarnRed))
    }
    if (banner && uses.isNotEmpty()) {
        val shown = uses.take(3)
        val text = tr("Plugins with sensitive access") + "\n" + shown.joinToString("\n") { u ->
            u.name + ": " + u.perms.filter { it in PluginSafety.SENSITIVE }.joinToString(", ") { PluginSafety.label(it) }
        } + if (uses.size > shown.size) "\n+" + (uses.size - shown.size) else ""
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 10.dp, end = 10.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF2202124))
                .clickable {
                    banner = false
                    runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Rounded.Warning, null, tint = WarnRed, modifier = Modifier.size(20.dp))
            Text(text, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 5)
        }
    }
}

/** Settings → Plugins: the reminder switch, the list of plugins with sensitive access and the media access help. */
@Composable
internal fun PluginSecuritySection(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val plugins = PluginRepository.plugins
    val off = PluginRepository.disabled
    val risky = plugins.filter { !it.builtIn && it.id !in off && it.permissions.any { p -> p in PluginSafety.SENSITIVE } }
    SettingsSection(tr("Plugin security")) {
        ToggleItem(
            Icons.Rounded.Security, tr("Sensitive access reminder"),
            tr("A warning with a red icon appears on the keyboard from time to time while a plugin can read typed text, clipboard, microphone, motion or media. A red dot shows while one is actually using it"),
            settings.securityReminder
        ) { v -> KeyboardPrefs.edit(context) { putBoolean("security_reminder", v) } }
        if (settings.securityReminder) {
            ChoiceItem(
                Icons.Rounded.Timer, tr("Reminder interval"), null,
                listOf("4" to tr("4 minutes"), "15" to tr("15 minutes"), "60" to tr("1 hour"), "1440" to tr("Once a day")),
                settings.securityReminderMin.toString()
            ) { v -> KeyboardPrefs.edit(context) { putInt("security_reminder_min", v.toIntOrNull() ?: 4) } }
        }
        if (plugins.any { it.settingsLayout != null }) {
            ToggleItem(
                Icons.Rounded.Extension, tr("Plugins may arrange Settings"),
                tr("Plugins with the settings_layout permission can reorder, pin or hide pages on the Settings home screen. Plugins, Backup and About always stay visible"),
                settings.pluginSettingsLayout
            ) { v -> KeyboardPrefs.edit(context) { putBoolean("plugin_settings_layout", v) } }
        }
        if (plugins.any { it.toolbarLayout != null }) {
            ToggleItem(
                Icons.Rounded.Extension, tr("Plugins may arrange the toolbar"),
                tr("Plugins with the toolbar_layout permission can reorder and hide buttons on the keyboard toolbar. Plugins and Settings always stay visible"),
                settings.pluginToolbarLayout
            ) { v -> KeyboardPrefs.edit(context) { putBoolean("plugin_toolbar_layout", v) } }
        }
        risky.forEach { p ->
            GroupItem {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(p.name), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        Text(
                            p.permissions.filter { it in PluginSafety.SENSITIVE }.joinToString(" · ") { PluginSafety.label(it) } +
                                if ("network" in p.permissions) " · " + tr("internet") else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.error
                        )
                    }
                    TextButton(onClick = { PluginRepository.setEnabled(context, p.id, false) }) { Text(tr("Выключить")) }
                }
            }
        }
    }
    if (plugins.any { "media" in it.permissions && it.id !in off }) MediaAccessSection()
}

@Composable
private fun MediaAccessSection() {
    val context = LocalContext.current
    var access by remember { mutableStateOf(MediaBridge.hasAccess(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = MediaBridge.hasAccess(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SettingsSection(tr("Media access")) {
        ActionItem(
            Icons.Rounded.MusicNote,
            if (access) tr("Now-playing info: allowed") else tr("Allow now-playing info"),
            tr("Title, artist, cover and position need \"Notification access\" for this app. The keyboard only reads media sessions, never notification text. Without it plugins only see whether music plays"),
            onClick = { openSystem(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )
        ActionItem(
            Icons.Rounded.Info, tr("Restricted settings (Android 13+)"),
            tr("If the Notification access switch is greyed out: open App info, tap the three dots and choose \"Allow restricted settings\""),
            onClick = {
                openSystem(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }
        )
    }
}

private fun openSystem(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
