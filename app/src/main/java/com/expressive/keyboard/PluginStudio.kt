package com.expressive.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Plugin Studio: edit a plugin inside the app, run it at once, see its errors, undo a bad edit.
// The pure parts (patches, saved versions, the shell) are in PluginStudioCore.kt.

/** Longer sources open without colours: highlighting runs on every key press. */
private const val HIGHLIGHT_LIMIT = 60_000
/** Longer sources are not shown in the editor at all (the text field gets slow); patches still work on them. */
private const val EDITOR_LIMIT = 250_000

private val JS_WORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
    "new", "this", "typeof", "instanceof", "in", "of", "try", "catch", "finally", "throw", "true", "false", "null", "undefined",
    "async", "await", "class", "extends", "default", "delete", "void"
)
private val LUA_WORDS = setOf(
    "local", "function", "return", "if", "then", "else", "elseif", "end", "for", "while", "do", "repeat", "until", "break",
    "in", "and", "or", "not", "true", "false", "nil", "goto"
)

/** Colours for the editor: comments, strings, numbers and keywords. */
private class CodeHighlight(
    private val lua: Boolean,
    private val comment: Color,
    private val string: Color,
    private val number: Color,
    private val keyword: Color
) : VisualTransformation {
    private val rx: Regex = if (lua)
        Regex("(--\\[(=*)\\[[\\s\\S]*?\\]\\2\\]|--[^\\n]*)|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z_0-9]*\\b)")
    else
        Regex("(/\\*[\\s\\S]*?\\*/|//[^\\n]*)|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`[^`]*`)|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_\$][A-Za-z_\$0-9]*\\b)")

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.length > HIGHLIGHT_LIMIT) return TransformedText(text, OffsetMapping.Identity)
        val words = if (lua) LUA_WORDS else JS_WORDS
        // the Lua pattern has an extra group (the = signs of a long comment), so its groups are numbered one higher after the comment
        val out = buildAnnotatedString {
            append(src)
            for (m in rx.findAll(src)) {
                val g = m.groups
                val color: Color? = if (lua) when {
                    g[1] != null -> comment
                    g[3] != null -> string
                    g[4] != null -> number
                    g[5] != null && g[5]!!.value in words -> keyword
                    else -> null
                } else when {
                    g[1] != null -> comment
                    g[2] != null -> string
                    g[3] != null -> number
                    g[4] != null && g[4]!!.value in words -> keyword
                    else -> null
                }
                if (color != null) addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

/** A plain-text report to paste to whoever is helping with the plugin: versions, errors, the last log lines and the code around the error. */
internal fun buildPluginReport(context: Context, p: KeyboardPlugin, source: String): String {
    val sb = StringBuilder()
    sb.append("Plugin report\n")
    sb.append("Plugin: ").append(p.name).append(" (").append(p.id).append(") v").append(p.version).append('\n')
    sb.append("Engine: ").append(p.scriptEngine.name.lowercase()).append("  ·  enabled: ").append(PluginRepository.isEnabled(p.id)).append('\n')
    if (p.permissions.isNotEmpty()) sb.append("Permissions: ").append(p.permissions.sorted().joinToString(", ")).append('\n')
    sb.append("App: Expressive Keyboard Pro ").append(BuildConfig.VERSION_NAME).append('\n')
    sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")  ·  ")
        .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
    sb.append("Source: ").append(source.length).append(" characters, ").append(source.count { it == '\n' } + 1).append(" lines\n")
    ScriptHost.diagnostics(p.id)?.let { sb.append("\nDiagnostics\n").append(it).append('\n') }
    PluginRepository.errors.filter { it.contains(p.id) }.takeIf { it.isNotEmpty() }?.let {
        sb.append("\nLoad errors\n").append(it.joinToString("\n")).append('\n')
    }
    val log = ScriptHost.exportLog(p.id).lines().filter { it.isNotBlank() }
    if (log.isNotEmpty()) sb.append("\nLast log lines\n").append(log.takeLast(20).joinToString("\n")).append('\n')
    val lastError = ScriptHost.logs[p.id].orEmpty().lastOrNull { it.level >= 3 }
    val lineNo = lastError?.let { errorLine(it.text) }
    if (lineNo != null) {
        val lines = source.lines()
        if (lineNo in 1..lines.size) {
            sb.append("\nCode around line ").append(lineNo).append('\n')
            for (i in (lineNo - 3).coerceAtLeast(1)..(lineNo + 3).coerceAtMost(lines.size)) {
                sb.append(if (i == lineNo) ">> " else "   ").append(i).append(": ").append(lines[i - 1].take(200)).append('\n')
            }
        }
    }
    return sb.toString().trimEnd()
}

/** The line number an error message points to ("line 42", ":42:" or "42:"), or null. */
internal fun errorLine(text: String): Int? {
    val m = Regex("(?:line\\s+|:)(\\d{1,6})(?:\\b|:)").find(text) ?: return null
    return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
}

private fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun clipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

private class ConsoleLine(val text: String, val kind: Int) // 0 output, 1 command, 2 error, 3 info

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginStudioDialog(plugin: KeyboardPlugin, onClose: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val id = plugin.id
    val snaps = remember { PluginSnapshots(File(context.filesDir, "plugin_snapshots")) }
    val editable = !plugin.builtIn && plugin.files.isEmpty()
    val lua = plugin.scriptEngine == PluginScriptEngine.LUA

    // the source as it is installed now; "saved" follows what was last written by Save & Run
    var loaded by remember(id) { mutableStateOf(false) }
    var saved by remember(id) { mutableStateOf("") }
    var tfv by remember(id) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(id) {
        val text = withContext(Dispatchers.IO) {
            PluginRepository.exportScript(context, plugin) ?: PluginRepository.exportSource(context, plugin)?.second ?: ""
        }
        saved = text
        tfv = TextFieldValue(text)
        loaded = true
    }
    val dirty = loaded && tfv.text != saved
    val tooBig = tfv.text.length > EDITOR_LIMIT

    // undo inside the editor (separate from the saved versions)
    val history = remember(id) { mutableStateListOf<String>() }
    var lastPush by remember(id) { mutableLongStateOf(0L) }
    fun edit(v: TextFieldValue) {
        if (v.text != tfv.text) {
            val now = System.currentTimeMillis()
            if (now - lastPush > 700L || history.isEmpty()) {
                history += tfv.text
                if (history.size > 60) history.removeAt(0)
            }
            lastPush = now
        }
        tfv = v
    }

    var tab by remember { mutableIntStateOf(0) }
    var runAt by remember(id) { mutableLongStateOf(0L) }
    var status by remember(id) { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    val vScroll = rememberScrollState()
    val lineHeightPx = with(density) { 18.sp.toPx() }

    fun jumpToLine(n: Int) {
        val lines = tfv.text.split('\n')
        val target = n.coerceIn(1, lines.size)
        var off = 0
        for (i in 0 until target - 1) off += lines[i].length + 1
        tfv = tfv.copy(selection = TextRange(off))
        tab = 0
        scope.launch { vScroll.animateScrollTo(((target - 4).coerceAtLeast(0) * lineHeightPx).toInt()) }
    }

    fun saveAndRun(text: String = tfv.text, closeAfter: Boolean = false) {
        scope.launch {
            val previous = saved
            val r = withContext(Dispatchers.IO) {
                snaps.save(id, previous)
                PluginRepository.importText(context, text)
            }
            r.onSuccess { p ->
                saved = text
                if (text != tfv.text) tfv = TextFieldValue(text)
                runAt = System.currentTimeMillis()
                status = null
                val added = p.permissions - plugin.permissions
                val msg = when {
                    p.id != id -> tr("The id changed, so a new plugin «{0}» was installed; the old one is still there", p.name)
                    added.isNotEmpty() -> tr("Saved and reloaded. New permissions: {0}", added.joinToString(", "))
                    else -> tr("Saved and reloaded")
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (closeAfter) onClose()
            }.onFailure {
                status = it.message ?: it.toString()
                runAt = System.currentTimeMillis()
                Toast.makeText(context, tr("Not saved: {0}", it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun restore(text: String) {
        history += tfv.text
        tfv = TextFieldValue(text)
        saveAndRun(text)
    }

    fun requestClose() { if (dirty) confirmClose = true else onClose() }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                // ---------- top bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { requestClose() }) { Icon(Icons.Rounded.Close, tr("Close")) }
                    Column(Modifier.weight(1f)) {
                        Text(tr(plugin.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            (if (!editable) tr("read only") else if (dirty) tr("not saved") else tr("saved")) + " · " + (if (lua) "Lua" else "JavaScript") + " · " + tfv.text.count { it == '\n' }.plus(1) + " " + tr("lines"),
                            style = MaterialTheme.typography.bodySmall, color = if (dirty) cs.tertiary else cs.onSurfaceVariant
                        )
                    }
                    if (editable) {
                        IconButton(onClick = {
                            if (history.isNotEmpty()) { tfv = TextFieldValue(history.removeAt(history.lastIndex)); lastPush = 0L }
                        }, enabled = history.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, tr("Undo")) }
                        IconButton(onClick = { versionsOpen = true }) { Icon(Icons.Rounded.History, tr("Versions")) }
                        Button(onClick = { saveAndRun() }, enabled = loaded) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Save & Run"))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
                // ---------- result of the last run
                val lastError = ScriptHost.logs[id].orEmpty().lastOrNull { it.level >= 3 && it.at >= runAt && runAt > 0L }
                val banner = status ?: lastError?.text
                if (banner != null) {
                    val n = errorLine(banner)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(cs.errorContainer)
                            .clickable { if (n != null) jumpToLine(n) else tab = 1 }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = cs.onErrorContainer, modifier = Modifier.size(18.dp))
                        Text(
                            banner.take(220) + if (n != null) "  → " + tr("tap to go to line {0}", n) else "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onErrorContainer, maxLines = 4
                        )
                    }
                } else if (runAt > 0L && ScriptHost.stoppedMods[id] == null) {
                    Text("✓ " + tr("Running the saved version"), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                }
                ScriptHost.stoppedMods[id]?.let {
                    Text("⏸ " + it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
                // ---------- tabs
                SecondaryTabRow(selectedTabIndex = tab) {
                    listOf(tr("Code"), tr("Log"), tr("Patch"), tr("Shell")).forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        tab == 0 -> StudioCodeTab(
                            tfv = tfv, onChange = { edit(it) }, editable = editable, tooBig = tooBig, lua = lua,
                            vScroll = vScroll, plugin = plugin
                        )
                        tab == 1 -> StudioLogTab(plugin, tfv.text, onJump = { jumpToLine(it) })
                        tab == 2 -> StudioPatchTab(
                            editable = editable,
                            current = tfv.text,
                            onApplied = { text, run ->
                                history += tfv.text
                                tfv = TextFieldValue(text)
                                if (run) saveAndRun(text) else tab = 0
                            }
                        )
                        else -> StudioShellTab(
                            plugin = plugin, source = tfv.text,
                            onUndo = {
                                val last = snaps.list(id).firstOrNull()
                                if (last == null) "no saved versions yet" else { restore(snaps.read(last)); "restored the version from " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(last.at)) }
                            },
                            onVersions = { snaps.list(id).size }
                        )
                    }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(tr("Close without saving?")) },
            text = { Text(tr("The changes in the editor are not saved.")) },
            confirmButton = { TextButton(onClick = { confirmClose = false; saveAndRun(closeAfter = true) }) { Text(tr("Save & Run")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false }) { Text(tr("Keep editing")) }
                    TextButton(onClick = { confirmClose = false; onClose() }) { Text(tr("Discard"), color = cs.error) }
                }
            }
        )
    }
    if (versionsOpen) {
        val list = remember(versionsOpen, saved) { snaps.list(id) }
        val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { versionsOpen = false },
            title = { Text(tr("Saved versions")) },
            text = {
                if (list.isEmpty()) Text(tr("Each Save & Run keeps the version it replaces. Nothing is saved yet."))
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                versionsOpen = false
                                restore(snaps.read(s))
                            }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(fmt.format(java.util.Date(s.at)))
                            Text("${s.size / 1024 + 1} KB", color = cs.onSurfaceVariant)
                        }
                    }
                    Text(tr("Tap a version to restore it and run it. The current text is kept as a version too."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { versionsOpen = false }) { Text(tr("Close")) } }
        )
    }
}

@Composable
private fun StudioCodeTab(
    tfv: TextFieldValue, onChange: (TextFieldValue) -> Unit, editable: Boolean, tooBig: Boolean, lua: Boolean,
    vScroll: androidx.compose.foundation.ScrollState, plugin: KeyboardPlugin
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    if (tooBig) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("This source is {0} characters: too long for the phone editor. Use the Patch tab to change it with small find/replace patches, or copy the whole text.", tfv.text.length),
                color = cs.onSurfaceVariant
            )
            OutlinedButton(onClick = { copyText(context, "plugin source", tfv.text); Toast.makeText(context, tr("Copied"), Toast.LENGTH_SHORT).show() }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Copy source"))
            }
        }
        return
    }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurface)
    val highlight = remember(lua, cs.outline, cs.tertiary, cs.secondary, cs.primary) {
        CodeHighlight(lua, cs.outline, cs.tertiary, cs.secondary, cs.primary)
    }
    val lineCount = remember(tfv.text) { tfv.text.count { it == '\n' } + 1 }
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        if (!editable && plugin.files.isNotEmpty()) {
            Text(tr("Plugins installed from a .zip cannot be edited here yet: they carry pictures and data files. Edit the files on your computer and import the .zip again."), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (editable) {
            // keys a phone keyboard hides behind other layers
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("⇥" to "  ", "{" to "{", "}" to "}", "(" to "(", ")" to ")", "[" to "[", "]" to "]", ";" to ";", "=" to "=", "\"" to "\"", "'" to "'", ":" to ":", "." to ".", "," to ",", "<" to "<", ">" to ">", "/" to "/", "\\" to "\\", "_" to "_", "+" to "+", "-" to "-", "*" to "*", "!" to "!", "&" to "&", "|" to "|", "#" to "#", "\$" to "\$").forEach { (label, ins) ->
                    FilledTonalButton(
                        onClick = {
                            val s = tfv.selection
                            val t = tfv.text.replaceRange(s.min, s.max, ins)
                            onChange(TextFieldValue(t, TextRange(s.min + ins.length)))
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp).widthIn(min = 36.dp)
                    ) { Text(label, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll).padding(top = 4.dp)) {
            Text(gutter, style = mono.copy(color = cs.outline, textAlign = TextAlign.End), modifier = Modifier.padding(start = 6.dp, end = 8.dp).widthIn(min = 28.dp))
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                BasicTextField(
                    value = tfv,
                    onValueChange = onChange,
                    readOnly = !editable,
                    textStyle = mono,
                    cursorBrush = SolidColor(cs.primary),
                    visualTransformation = highlight,
                    modifier = Modifier.padding(end = 24.dp).defaultMinSize(minWidth = 300.dp, minHeight = 300.dp)
                )
            }
        }
    }
}

@Composable
private fun StudioLogTab(plugin: KeyboardPlugin, source: String, onJump: (Int) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val id = plugin.id
    val lines = ScriptHost.logs[id].orEmpty()
    var minLevel by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val stamp = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US) }
    val diag = remember(id, lines.size, refresh) { ScriptHost.diagnostics(id) }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val palette = listOf(cs.outline, cs.onSurfaceVariant, cs.tertiary, cs.error)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                copyText(context, "plugin report", buildPluginReport(context, plugin, source))
                Toast.makeText(context, tr("Report copied: paste it where you ask for help"), Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Rounded.BugReport, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Copy report")) }
            OutlinedButton(onClick = { refresh++ }) { Text(tr("Refresh")) }
            OutlinedButton(onClick = { ScriptHost.clearLog(id) }) { Text(tr("Clear")) }
        }
        if (diag != null) {
            Text(diag, style = mono, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lv, name) in listOf(0 to "All", 2 to "Warnings", 3 to "Errors")) {
                FilterChip(selected = minLevel == lv, onClick = { minLevel = lv }, label = { Text(tr(name)) })
            }
        }
        val shown = lines.filter { it.level >= minLevel }.takeLast(200)
        if (shown.isEmpty()) {
            Text(tr("Empty"), style = mono, color = cs.onSurfaceVariant)
        } else {
            shown.forEach { l ->
                val n = errorLine(l.text)
                Text(
                    stamp.format(java.util.Date(l.at)) + "  " + l.text,
                    style = mono, color = palette[l.level.coerceIn(0, 3)],
                    modifier = Modifier.fillMaxWidth().then(if (n != null) Modifier.clickable { onJump(n) } else Modifier)
                )
            }
            Text(tr("Lines with a number can be tapped: the editor jumps to that line."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun StudioPatchTab(editable: Boolean, current: String, onApplied: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var patch by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurface)
    fun run(andRun: Boolean) {
        runCatching { PluginPatch.apply(current, patch) }
            .onSuccess { r ->
                failed = false
                message = tr("Applied {0} change(s)", r.blocks)
                onApplied(r.text, andRun)
            }
            .onFailure { failed = true; message = it.message ?: it.toString() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("Paste a patch to change a few lines without importing the whole plugin again. All changes are applied together, or none if one of them does not match."),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
        )
        Text(
            "@@ find\nvar speed = 4;\n@@ replace\nvar speed = 6;\n@@ end",
            style = mono.copy(color = cs.onSurfaceVariant), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp)
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(10.dp)) {
            BasicTextField(value = patch, onValueChange = { patch = it; message = null }, textStyle = mono, cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
            if (patch.isEmpty()) Text(tr("Patch text"), style = mono, color = cs.outline)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { patch = clipboardText(context); message = null }) {
                Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Paste"))
            }
            OutlinedButton(onClick = { run(false) }, enabled = editable && patch.isNotBlank()) { Text(tr("Apply")) }
            Button(onClick = { run(true) }, enabled = editable && patch.isNotBlank()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("Apply & Run"))
            }
        }
        message?.let { Text(it, color = if (failed) cs.error else cs.primary, style = MaterialTheme.typography.bodyMedium) }
        if (!editable) Text(tr("This plugin cannot be edited here."), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun StudioShellTab(plugin: KeyboardPlugin, source: String, onUndo: () -> String, onVersions: () -> Int) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shell = remember { PluginShell(context.filesDir) }
    val out = remember { mutableStateListOf(
        ConsoleLine("Basic shell (/system/bin/sh). Type help. It runs with the keyboard's own permissions and only from this screen: plugins cannot use it.", 3)
    ) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(shell.prompt()) }
    val listScroll = rememberScrollState()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    fun add(t: String, kind: Int = 0) {
        if (t.isEmpty()) return
        out += ConsoleLine(t, kind)
        while (out.size > 300) out.removeAt(0)
    }
    LaunchedEffect(out.size) { listScroll.animateScrollTo(listScroll.maxValue) }

    fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        add(prompt + " " + cmd, 1)
        val parts = cmd.split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrElse(1) { "" }
        when (parts[0]) {
            "help" -> add(
                "help            this list\n" +
                    "reload          load all plugins again\n" +
                    "log [n]         last n log lines of this plugin (default 20)\n" +
                    "errors          errors of this plugin and plugins that did not load\n" +
                    "report          copy the report for this plugin\n" +
                    "undo            restore the previous saved version and run it\n" +
                    "versions        how many saved versions exist\n" +
                    "plugins         ids of all installed plugins\n" +
                    "version         app version\n" +
                    "clear           clear this screen\n" +
                    "cd, pwd, ls, cat, grep …  any other command goes to the shell\n" +
                    "the keyboard's private files are in ~ (plugins are in ~/plugins)", 3
            )
            "clear" -> out.clear()
            "reload" -> { PluginRepository.reload(context); add("plugins reloaded", 3) }
            "log" -> {
                val n = arg.toIntOrNull()?.coerceIn(1, 200) ?: 20
                val lines = ScriptHost.exportLog(plugin.id).lines().filter { it.isNotBlank() }.takeLast(n)
                add(if (lines.isEmpty()) "(log is empty)" else lines.joinToString("\n"))
            }
            "errors" -> {
                val errs = ScriptHost.exportLog(plugin.id).lines().filter { it.contains("[E]") }.takeLast(20) + PluginRepository.errors
                add(if (errs.isEmpty()) "no errors" else errs.joinToString("\n"), if (errs.isEmpty()) 0 else 2)
            }
            "report" -> { copyText(context, "plugin report", buildPluginReport(context, plugin, source)); add("report copied", 3) }
            "undo" -> add(onUndo(), 3)
            "versions" -> add("${onVersions()} saved version(s)", 3)
            "plugins" -> add(PluginRepository.plugins.joinToString("\n") { it.id + (if (it.builtIn) "  (built-in)" else "") + (if (PluginRepository.isEnabled(it.id)) "" else "  (off)") })
            "version" -> add("Expressive Keyboard Pro " + BuildConfig.VERSION_NAME + "  ·  Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            "cd" -> { shell.cd(arg)?.let { add(it, 2) }; prompt = shell.prompt() }
            else -> {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { shell.run(cmd) }
                    add(r.text, if (r.exitCode == 0) 0 else 2)
                    if (r.timedOut) add("stopped: the command took longer than 10 seconds", 2)
                    else if (r.exitCode != null && r.exitCode != 0 && r.text.isEmpty()) add("exit code ${r.exitCode}", 2)
                    busy = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("help", "reload", "log", "errors", "undo", "ls", "pwd").forEach { c ->
                AssistChip(onClick = { runCommand(c) }, label = { Text(c, fontFamily = FontFamily.Monospace) })
            }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest)) {
            Column(Modifier.fillMaxSize().verticalScroll(listScroll).padding(10.dp)) {
                out.forEach { l ->
                    Text(
                        l.text, style = mono,
                        color = when (l.kind) { 1 -> cs.primary; 2 -> cs.error; 3 -> cs.tertiary; else -> cs.onSurface }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(prompt, style = mono, color = cs.primary)
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = input, onValueChange = { input = it.replace("\n", "") },
                    textStyle = mono.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { val c = input; input = ""; runCommand(c) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            IconButton(onClick = { val c = input; input = ""; runCommand(c) }, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.Send, tr("Run")) }
        }
    }
}
