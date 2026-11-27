package com.expressive.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

private fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() <= limit) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

// ---------------------------------------------------------------------------------------------- extra effects

@Composable
internal fun ExtrasPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    fun putB(k: String, v: Boolean) = KeyboardPrefs.edit(context) { putBoolean(k, v) }
    fun putF(k: String, v: Float) = KeyboardPrefs.edit(context) { putFloat(k, v) }
    fun putS(k: String, v: String) = KeyboardPrefs.edit(context) { putString(k, v) }
    val x = { v: Float -> String.format("×%.1f", v) }
    val pct = { v: Float -> "${(v * 100).roundToInt()}%" }

    InfoCard(Icons.Rounded.AutoAwesome, "Every effect has its own switch and sliders. They are off by default, and nothing runs while a switch is off.")

    SettingsSection("Press shockwave") {
        ToggleItem(
            Icons.Rounded.Animation, "Press shockwave",
            "A soft ring spreads from the pressed key and nudges the keys it passes. Visual only: touch areas do not move",
            settings.shockwave
        ) { putB("shockwave", it) }
        if (settings.shockwave) {
            SliderItem(Icons.Rounded.Speed, "Wave speed", settings.shockwaveSpeed, 0.5f..2.5f, x, { putF("shockwave_speed", it) })
            SliderItem(Icons.Rounded.Animation, "Wave strength", settings.shockwaveStrength, 0.1f..1f, pct, { putF("shockwave_strength", it) })
            SliderItem(
                Icons.Rounded.Animation, "How far the wave goes", settings.shockwaveReach, 1.5f..6f,
                { String.format("%.1f", it) }, { putF("shockwave_reach", it) }
            )
            SliderItem(
                Icons.Rounded.Animation, "Key ripple", settings.shockwaveRipple, 0f..8f,
                { if (it < 0.2f) tr("Выкл") else "${it.roundToInt()} dp" }, { putF("shockwave_ripple", it) }
            )
            ToggleItem(Icons.Rounded.Animation, "Show the ring", "Off keeps only the key ripple", settings.shockwaveRing) { putB("shockwave_ring", it) }
        }
    }

    SettingsSection("Tilt parallax") {
        ToggleItem(
            Icons.Rounded.Animation, "Tilt parallax",
            "The keys shift a little when you tilt the phone, so the keyboard looks 3D. It uses the tilt sensor only while the keyboard is open",
            settings.tiltParallax
        ) { putB("tilt_parallax", it) }
        if (settings.tiltParallax) {
            SliderItem(Icons.Rounded.Animation, "Parallax strength", settings.tiltParallaxStrength, 0.2f..2.5f, x, { putF("tilt_parallax_strength", it) })
            ToggleItem(Icons.Rounded.SwapHoriz, "Invert direction", "Keys move the other way", settings.tiltParallaxInvert) { putB("tilt_parallax_invert", it) }
        }
    }

    SettingsSection("Typing glow") {
        ToggleItem(
            Icons.Rounded.AutoAwesome, "Typing speed glow",
            "A neon strip gets brighter and its light runs faster the quicker you type, and it calms down when you stop",
            settings.typingGlow
        ) { putB("typing_glow", it) }
        if (settings.typingGlow) {
            ChoiceItem(
                Icons.Rounded.Straighten, "Strip place", null,
                listOf("TOP" to "Top edge", "BOTTOM" to "Bottom edge"), settings.typingGlowEdge
            ) { putS("typing_glow_edge", it) }
            ChoiceItem(
                Icons.Rounded.ColorLens, "Strip colour", null,
                listOf("ACCENT" to "Accent colour", "RAINBOW" to "Rainbow"), settings.typingGlowColor
            ) { putS("typing_glow_color", it) }
            SliderItem(Icons.Rounded.Animation, "Brightest glow", settings.typingGlowMax, 0.2f..1f, pct, { putF("typing_glow_max", it) })
            SliderItem(
                Icons.Rounded.Speed, "Typing speed for full glow", settings.typingGlowFullCps, 3f..14f,
                { "${it.roundToInt()} " + tr("keys/s") }, { putF("typing_glow_full_cps", it) }
            )
            SliderItem(
                Icons.Rounded.Speed, "Calm down time", settings.typingGlowCalm, 0.5f..6f,
                { String.format("%.1f s", it) }, { putF("typing_glow_calm", it) }
            )
            SliderItem(
                Icons.Rounded.Straighten, "Strip thickness", settings.typingGlowThickness, 2f..12f,
                { "${it.roundToInt()} dp" }, { putF("typing_glow_thickness", it) }
            )
        }
    }

    SettingsSection("Edge swipe for one hand") {
        ToggleItem(
            Icons.Rounded.TouchApp, "Edge swipe",
            "Swipe in from the left or right edge of the keyboard to shrink it to that side. The button and the setting still work",
            settings.edgeSwipe
        ) { putB("edge_swipe", it) }
        if (settings.edgeSwipe) {
            InfoCard(Icons.Rounded.Gesture, "With swipe typing on, start the swipe on the very edge, outside the first key. A thinner edge zone avoids mistakes.")
            SliderItem(
                Icons.Rounded.Straighten, "Edge zone width", settings.edgeSwipeZoneDp, 10f..40f,
                { "${it.roundToInt()} dp" }, { putF("edge_swipe_zone_dp", it) }
            )
            SliderItem(
                Icons.Rounded.Straighten, "Swipe length", settings.edgeSwipeDistanceDp, 30f..140f,
                { "${it.roundToInt()} dp" }, { putF("edge_swipe_distance_dp", it) }
            )
            ChoiceItem(
                Icons.Rounded.SwapHoriz, "Swipe from the other edge", null,
                listOf("EXIT" to "Back to full width", "SWITCH" to "Move to that side"), settings.edgeSwipeOpposite
            ) { putS("edge_swipe_opposite", it) }
            ToggleItem(Icons.Rounded.Vibration, "Tick when it switches", null, settings.edgeSwipeHaptic) { putB("edge_swipe_haptic", it) }
        }
    }

    SettingsSection("Space bar cursor ticks") {
        ToggleItem(
            Icons.Rounded.Vibration, "Cursor ticks",
            "A small vibration for every character or line the cursor moves when you swipe on the space bar. Needs vibration on",
            settings.spaceTicks
        ) { putB("space_ticks", it) }
        if (settings.spaceTicks) {
            ChoiceItem(
                Icons.Rounded.Vibration, "Tick strength", null,
                listOf("SOFT" to "Soft", "NORMAL" to "Normal", "STRONG" to "Strong"), settings.spaceTickStrength
            ) {
                putS("space_tick_strength", it)
                HapticsEngine.cursorTick(context, null, settings.copy(spaceTicks = true, spaceTickStrength = it), false)
            }
            ToggleItem(Icons.Rounded.Vibration, "Tick on lines too", "Up and down moves feel one step stronger", settings.spaceTickLines) { putB("space_tick_lines", it) }
        }
    }

    InfoCard(Icons.Rounded.MusicNote, "Typewriter and Clicky sounds, pitch per key and the random change are in Vibration and sound.")
}

// ---------------------------------------------------------------------------------------------- dialogs

@Composable
private fun NameDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true, placeholder = { Text(tr("Name")) })
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

/** Picks a look. [allowBase] adds "My normal theme", [allowNone] adds a row that removes the choice. */
@Composable
private fun LookPickerDialog(
    title: String, looks: List<String>, allowBase: Boolean, allowNone: Boolean, noneLabel: String,
    onDismiss: () -> Unit, onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (looks.isEmpty() && !allowBase) Text(tr("Save a look first (Saved looks, above)"))
                if (allowNone) PickRow(tr(noneLabel)) { onPick(null) }
                if (allowBase) PickRow(tr("My normal theme")) { onPick(AutoLookRules.BASE) }
                for (n in looks) PickRow(n) { onPick(n) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun PickRow(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .filter { it.first != context.packageName }
                    .sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Choose an app")) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(tr("Search")) })
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for ((pkg, label) in apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }) {
                        PickRow(label) { onPick(pkg) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private fun lookName(name: String?): String = when {
    name.isNullOrEmpty() -> tr("Not set")
    name == AutoLookRules.BASE -> tr("My normal theme")
    else -> name
}

// ---------------------------------------------------------------------------------------------- themes by app and time

@Composable
internal fun AutoThemesPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val looks = remember(tick) { LookStore.names(context) }
    val rules = remember(tick) { AppLooks.map(context) }
    var naming by remember { mutableStateOf(false) }
    var addingApp by remember { mutableStateOf(false) }
    var ruleApp by remember { mutableStateOf<String?>(null) }
    var pickDay by remember { mutableStateOf(false) }
    var pickNight by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    fun putB(k: String, v: Boolean) = KeyboardPrefs.edit(context) { putBoolean(k, v) }

    InfoCard(
        Icons.Rounded.Style,
        "A look is a saved copy of your theme: colours, shapes, font, glass and skin. The keyboard wears it for an app or for the time of day. Your own theme is never changed."
    )

    SettingsSection("Saved looks") {
        ActionItem(
            Icons.Rounded.Save, "Save the current theme as a look", "Set your theme in Themes and Colors first, then save it here",
            onClick = { naming = true }
        )
        for (n in looks) {
            ActionItem(
                Icons.Rounded.Palette, n, "Tap to make it your normal theme",
                onClick = {
                    LookStore.applyNow(context, n)
                    Toast.makeText(context, tr("Applied: {0}", n), Toast.LENGTH_SHORT).show()
                },
                trailing = {
                    IconButton(onClick = { confirmDelete = n }) {
                        Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    }

    SettingsSection("Theme for each app") {
        ToggleItem(
            Icons.Rounded.Apps, "Themes by app",
            "The keyboard switches to the look you picked for the app you type in, for example dark neon in Telegram and plain in your browser",
            settings.perAppThemes
        ) { putB("per_app_themes", it) }
        if (settings.perAppThemes) {
            for ((pkg, look) in rules) {
                ActionItem(
                    Icons.Rounded.Apps, appLabel(context, pkg), lookName(look),
                    onClick = { ruleApp = pkg },
                    trailing = {
                        IconButton(onClick = { AppLooks.set(context, pkg, null); tick++ }) {
                            Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
            ActionItem(Icons.Rounded.Add, "Add an app", null, onClick = { addingApp = true })
        }
    }

    SettingsSection("Day and night") {
        ToggleItem(
            Icons.Rounded.DarkMode, "Day and night themes",
            "The keyboard wears one look by day and another at night. An app rule above wins over this",
            settings.dayNight
        ) { putB("day_night", it) }
        if (settings.dayNight) {
            ChoiceItem(
                Icons.Rounded.Schedule, "When night starts", null,
                listOf("TIMES" to "At times I choose", "SUNSET" to "At sunset", "SYSTEM" to "With system dark mode"), settings.dayNightMode
            ) { KeyboardPrefs.edit(context) { putString("day_night_mode", it) } }
            ActionItem(Icons.Rounded.LightMode, "Day look", lookName(settings.dayLook), onClick = { pickDay = true })
            ActionItem(Icons.Rounded.DarkMode, "Night look", lookName(settings.nightLook), onClick = { pickNight = true })
            if (settings.dayNightMode != "SYSTEM") {
                SliderItem(
                    Icons.Rounded.LightMode, if (settings.dayNightMode == "SUNSET") "Day starts (if sunset is unknown)" else "Day starts",
                    settings.dayStartMin.toFloat(), 0f..1425f, { hhmm(it.roundToInt()) },
                    { v -> KeyboardPrefs.edit(context) { putInt("day_start_min", (v / 15f).roundToInt() * 15) } }, steps = 94
                )
                SliderItem(
                    Icons.Rounded.DarkMode, if (settings.dayNightMode == "SUNSET") "Night starts (if sunset is unknown)" else "Night starts",
                    settings.nightStartMin.toFloat(), 0f..1425f, { hhmm(it.roundToInt()) },
                    { v -> KeyboardPrefs.edit(context) { putInt("night_start_min", (v / 15f).roundToInt() * 15) } }, steps = 94
                )
            }
            if (settings.dayNightMode == "SUNSET") {
                var ll by remember { mutableStateOf(settings.sunLatLon) }
                val parsed = SunTimes.parseLatLon(ll)
                GroupItem {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Your place: latitude and longitude, for example 7.2, 124.2"), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = ll,
                            onValueChange = {
                                ll = it.take(30)
                                KeyboardPrefs.edit(context) { putString("sun_lat_lon", ll) }
                            },
                            singleLine = true, isError = ll.isNotBlank() && parsed == null,
                            placeholder = { Text("7.2, 124.2") }, modifier = Modifier.fillMaxWidth()
                        )
                        val day = if (parsed != null) AutoLook.sunToday(settings.copy(sunLatLon = ll)) else null
                        Text(
                            if (day != null) tr("Today: sunrise {0}, sunset {1}", hhmm(day.sunrise), hhmm(day.sunset))
                            else tr("The numbers stay on this phone. Without them the times above are used."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (naming) NameDialog("Name this look", { naming = false }) { name ->
        naming = false
        if (LookStore.save(context, name)) tick++ else Toast.makeText(context, tr("Could not save: empty name or too many looks"), Toast.LENGTH_LONG).show()
    }
    if (addingApp) AppPickerDialog({ addingApp = false }) { pkg ->
        addingApp = false
        ruleApp = pkg
    }
    ruleApp?.let { pkg ->
        LookPickerDialog(
            appLabel(context, pkg), looks, allowBase = true, allowNone = pkg in rules, noneLabel = "Remove the rule",
            onDismiss = { ruleApp = null }
        ) { picked ->
            AppLooks.set(context, pkg, picked)
            ruleApp = null
            tick++
        }
    }
    if (pickDay) LookPickerDialog("Day look", looks, allowBase = true, allowNone = false, noneLabel = "", onDismiss = { pickDay = false }) {
        KeyboardPrefs.edit(context) { putString("day_look", it ?: "") }
        pickDay = false
    }
    if (pickNight) LookPickerDialog("Night look", looks, allowBase = true, allowNone = false, noneLabel = "", onDismiss = { pickNight = false }) {
        KeyboardPrefs.edit(context) { putString("night_look", it ?: "") }
        pickNight = false
    }
    confirmDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Удалить «{0}»?", n)) },
            text = { Text(tr("Apps and day or night themes that use this look go back to your normal theme.")) },
            confirmButton = {
                Button(onClick = { LookStore.delete(context, n); confirmDelete = null; tick++ }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена")) } }
        )
    }
}

// ---------------------------------------------------------------------------------------------- layout profiles

@Composable
internal fun LayoutProfilesPage() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val names = remember(tick) { LayoutProfiles.names(context) }
    var naming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val name = exporting
        exporting = null
        if (uri != null && name != null) {
            val ok = runCatching {
                val text = LayoutProfiles.export(context, name) ?: error("missing")
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
            }.isSuccess
            Toast.makeText(context, if (ok) tr("Profile exported") else tr("Export failed"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 70_000) } ?: error("Could not open the file")
                LayoutProfiles.import(context, String(bytes)).getOrThrow()
            }
            result.onSuccess { Toast.makeText(context, tr("Imported: {0}", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, tr("Import failed: {0}", it.message ?: ""), Toast.LENGTH_LONG).show() }
            tick++
        }
    }

    InfoCard(
        Icons.Rounded.Straighten,
        "A profile saves your key size, gaps, margins, one-hand and floating setup, DPI and emoji key. Switching changes the sliders in Size and position. Colours and themes are not part of it."
    )
    SettingsSection("Your profiles") {
        ActionItem(Icons.Rounded.Save, "Save the current layout as a profile", null, onClick = { naming = true })
        for (n in names) {
            ActionItem(
                Icons.Rounded.Straighten, n, "Tap to switch to it",
                onClick = {
                    val ok = LayoutProfiles.apply(context, n)
                    Toast.makeText(context, if (ok) tr("Applied: {0}", n) else tr("This profile is empty"), Toast.LENGTH_SHORT).show()
                },
                trailing = {
                    Row {
                        IconButton(onClick = { exporting = n; exportLauncher.launch("layout-$n.json") }) {
                            Icon(Icons.Rounded.Share, tr("Export"))
                        }
                        IconButton(onClick = { confirmDelete = n }) {
                            Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
        ActionItem(
            Icons.Rounded.FileDownload, "Import a profile file", "From a friend. Only layout values are read from it",
            onClick = { importLauncher.launch(arrayOf("*/*")) }
        )
    }
    if (naming) NameDialog("Name this profile", { naming = false }) { name ->
        naming = false
        if (LayoutProfiles.save(context, name)) tick++ else Toast.makeText(context, tr("Could not save: empty name or too many profiles"), Toast.LENGTH_LONG).show()
    }
    confirmDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Удалить «{0}»?", n)) },
            confirmButton = {
                Button(onClick = { LayoutProfiles.delete(context, n); confirmDelete = null; tick++ }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена")) } }
        )
    }
}

// ---------------------------------------------------------------------------------------------- typing stats

@Composable
private fun StatRow(label: String, value: String) {
    GroupItem {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun TypingStatsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    TypingHistory.ensure(context)
    // saving bumps this number, so the page redraws with fresh counts
    @Suppress("UNUSED_VARIABLE") val saved = TypingHistory.version
    LaunchedEffect(Unit) { TypingHistory.flush(context) }
    var confirmClear by remember { mutableStateOf(false) }
    val data = TypingHistory.data
    val today = TypingHistory.today()

    SettingsSection(null) {
        ToggleItem(
            Icons.Rounded.BarChart, "Record typing stats",
            "Counts keys, speed and words on this phone only. Nothing is recorded in password fields or incognito, and nothing leaves the phone",
            settings.typingStats
        ) { KeyboardPrefs.edit(context) { putBoolean("typing_stats", it) } }
    }
    SettingsSection("Speed") {
        StatRow("Keys today", data.keysOn(today).toString())
        val avg = data.averageWpm(today, 7)
        StatRow("Average speed, last 7 days", if (avg > 0f) "${avg.roundToInt()} " + tr("wpm") else tr("Not enough typing yet"))
        val best = data.bestWpm()
        StatRow("Best speed", if (best > 0f) "${best.roundToInt()} " + tr("wpm") else "-")
    }
    SettingsSection("Streak") {
        StatRow("Current streak", "${data.streak(today)} " + tr("days"))
        StatRow("Longest streak", "${data.longestStreak()} " + tr("days"))
    }
    SettingsSection("Last 7 days") {
        GroupItem {
            val days = (6 downTo 0).map { today - it }
            val counts = days.map { data.keysOn(it) }
            val top = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
            val letters = "SMTWTFS"
            Row(
                Modifier.fillMaxWidth().height(140.dp).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom
            ) {
                for (i in days.indices) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        val frac = counts[i] / top.toFloat()
                        Box(
                            Modifier.fillMaxWidth().height((80f * frac).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (counts[i] == 0) 0.25f else 1f))
                        )
                        // 1 Jan 1970 was a Thursday
                        Text(letters[((days[i] + 4) % 7 + 7) % 7].toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    SettingsSection("Most used words") {
        val top = data.top(10)
        if (top.isEmpty()) StatRow("No words yet", "-")
        for ((w, n) in top) StatRow(w, "$n")
    }
    SettingsSection("Totals") {
        StatRow("All keys", data.totalKeys().toString())
        StatRow("All words", data.totalWords().toString())
        ActionItem(
            Icons.Rounded.DeleteOutline, "Delete all typing stats", "Counts, streaks and words are wiped from this phone",
            onClick = { confirmClear = true }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Delete all typing stats?")) },
            text = { Text(tr("This cannot be undone.")) },
            confirmButton = { Button(onClick = { TypingHistory.clear(context); confirmClear = false }) { Text(tr("Удалить")) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Отмена")) } }
        )
    }
}
