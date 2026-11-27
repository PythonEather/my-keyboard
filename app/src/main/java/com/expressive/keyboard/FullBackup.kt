package com.expressive.keyboard

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup: every file in the app's files folder and every SharedPreferences, in one .zip.
 * Layout: manifest.json, prefs/<name>.json, files/<path>.
 */
internal object FullBackup {
    private const val FORMAT = 1
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    /** Tied to this phone (folder permission), never copied. */
    private val DEVICE_PREFS = setOf("expressive_keyboard_local")
    private val PERSONAL_PREFS = setOf(WordPredictorEngine.DATA_PREFS, "typing_history")
    private val PERSONAL_FILES = setOf("clip_history.json", "clips", "clip_categories.json")
    private val PREF_NAME = Regex("[A-Za-z0-9._-]+")

    fun export(context: Context, out: OutputStream, personal: Boolean, keys: Boolean) {
        WordPredictorEngine.flush()
        TypingHistory.flush(context)
        Thread.sleep(500)
        ZipOutputStream(out.buffered()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            text("manifest.json", JSONObject()
                .put("format", FORMAT)
                .put("personal", personal)
                .put("keys", keys)
                .put("filesDir", context.filesDir.absolutePath)
                .toString())
            val prefFiles = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            for (file in prefFiles) {
                if (file.extension != "xml") continue
                val name = file.nameWithoutExtension
                if (!PREF_NAME.matches(name) || name in DEVICE_PREFS || (!personal && name in PERSONAL_PREFS)) continue
                val json = JSONObject()
                for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                    var v: Any = value ?: continue
                    if (key in SecretBox.SECRET_PREFS) {
                        if (!keys) continue
                        v = SecretBox.decrypt(v.toString())
                    }
                    val pair = when (v) {
                        is Boolean -> JSONArray().put("b").put(v)
                        is Int -> JSONArray().put("i").put(v)
                        is Long -> JSONArray().put("l").put(v)
                        is Float -> if (v.isNaN() || v.isInfinite()) null else JSONArray().put("f").put(v.toDouble())
                        is String -> JSONArray().put("s").put(v)
                        is Set<*> -> JSONArray().put("set").put(JSONArray(v.toList()))
                        else -> null
                    }
                    if (pair != null) json.put(key, pair)
                }
                text("prefs/$name.json", json.toString())
            }
            val root = context.filesDir
            root.walkTopDown().filter { it.isFile && it.extension != "tmp" }.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (personal || path.substringBefore('/') !in PERSONAL_FILES) {
                    zip.putNextEntry(ZipEntry("files/$path"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads and checks the whole archive first; the app's data is only touched if all of it is good. */
    fun restore(context: Context, input: InputStream) {
        val stage = File(context.cacheDir, "full_restore")
        val old = File(context.cacheDir, "full_restore_old")
        stage.deleteRecursively()
        old.deleteRecursively()
        val files = File(stage, "files").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            val prefs = LinkedHashMap<String, JSONObject>()
            var total = 0L
            var count = 0
            ZipInputStream(input.buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                fun read(sink: OutputStream) {
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_BYTES) { "Backup is too big" }
                        sink.write(buffer, 0, n)
                    }
                }
                fun json(): JSONObject {
                    val bytes = java.io.ByteArrayOutputStream()
                    read(bytes)
                    return JSONObject(bytes.toString("UTF-8"))
                }
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= MAX_ENTRIES) { "Too many files" }
                    if (entry.isDirectory) continue
                    val name = entry.name
                    when {
                        name == "manifest.json" -> manifest = json()
                        name.startsWith("prefs/") -> {
                            val pref = name.removePrefix("prefs/").removeSuffix(".json")
                            require(PREF_NAME.matches(pref)) { "Bad name: $name" }
                            prefs[pref] = json()
                        }
                        name.startsWith("files/") -> {
                            val target = File(files, name.removePrefix("files/"))
                            require(target.canonicalPath.startsWith(files.canonicalPath + File.separator)) { "Unsafe path: $name" }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { read(it) }
                        }
                    }
                }
            }
            val info = requireNotNull(manifest) { "This is not a full backup" }
            require(info.optInt("format") == FORMAT) { "Unsupported backup version" }
            val personal = info.optBoolean("personal")
            val keys = info.optBoolean("keys")
            val oldPath = info.optString("filesDir")
            val newPath = context.filesDir.absolutePath
            fun moved(s: String) = if (oldPath.isEmpty()) s else s.replace(oldPath, newPath)
            // parse every value before changing anything
            val parsed = prefs.filterKeys { it !in DEVICE_PREFS && (personal || it !in PERSONAL_PREFS) }.mapValues { (_, json) ->
                json.keys().asSequence().associateWith { key ->
                    val pair = json.getJSONArray(key)
                    val value: Any = when (pair.getString(0)) {
                        "b" -> pair.getBoolean(1)
                        "i" -> pair.getInt(1)
                        "l" -> pair.getLong(1)
                        "f" -> pair.getDouble(1).toFloat()
                        "s" -> moved(pair.getString(1))
                        "set" -> pair.getJSONArray(1).let { a -> (0 until a.length()).map { moved(a.getString(it)) }.toSet() }
                        else -> error("Unknown value type")
                    }
                    value
                }
            }
            swapFiles(context.filesDir, files, old, personal)
            for ((name, values) in parsed) {
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                // keys that were left out of the backup stay as they are on this phone
                val kept = if (keys) emptyMap<String, String>() else
                    SecretBox.SECRET_PREFS.mapNotNull { k -> (sp.all[k] as? String)?.let { k to it } }.toMap()
                val e = sp.edit().clear()
                kept.forEach { (k, v) -> e.putString(k, v) }
                for ((key, v) in values) {
                    when (v) {
                        is Boolean -> e.putBoolean(key, v)
                        is Int -> e.putInt(key, v)
                        is Long -> e.putLong(key, v)
                        is Float -> e.putFloat(key, v)
                        is String -> e.putString(key, if (key in SecretBox.SECRET_PREFS) SecretBox.encrypt(v) else v)
                        is Set<*> -> e.putStringSet(key, v.map { it.toString() }.toSet())
                    }
                }
                check(e.commit()) { "Could not save settings" }
            }
        } finally {
            stage.deleteRecursively()
        }
        old.deleteRecursively()
    }

    private fun swapFiles(root: File, staged: File, old: File, personal: Boolean) {
        old.mkdirs()
        val added = mutableListOf<File>()
        try {
            root.listFiles().orEmpty().filter { personal || it.name !in PERSONAL_FILES }.forEach {
                check(it.renameTo(File(old, it.name))) { "Could not move ${it.name}" }
            }
            staged.listFiles().orEmpty().filter { personal || it.name !in PERSONAL_FILES }.forEach {
                val target = File(root, it.name)
                check(it.renameTo(target)) { "Could not restore ${it.name}" }
                added += target
            }
        } catch (e: Exception) {
            added.forEach { it.deleteRecursively() }
            old.listFiles().orEmpty().forEach { it.renameTo(File(root, it.name)) }
            throw e
        }
    }
}

private fun l(en: String, ru: String) = if (I18n.lang == "ru") ru else en

@Composable
internal fun FullBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var personal by remember { mutableStateOf(true) }
    var keys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("Cannot open file")
                    out.use { FullBackup.export(context, it, personal, keys) }
                }
            }
            busy = false
            toast(r.fold({ l("Full backup saved", "Полная копия сохранена") }, { l("Backup failed: ", "Ошибка копии: ") + it.message }))
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> pending = uri }

    SettingsSection(l("Full backup", "Полная копия")) {
        ToggleItem(
            Icons.Rounded.History, l("Include typed data", "Включить набранные данные"),
            l("Learned words, clipboard, typing stats", "Выученные слова, буфер, статистика"), personal
        ) { personal = it }
        ToggleItem(
            Icons.Rounded.Key, l("Include API keys", "Включить ключи API"),
            l("Saved unprotected: do not share the file", "Хранятся без защиты: не передавайте файл"), keys
        ) { keys = it }
        ActionItem(
            Icons.Rounded.Upload, l("Save everything", "Сохранить всё"),
            if (busy) l("Working, keep this screen open…", "Идёт работа, не закрывайте экран…")
            else l("Settings, mods and their data, themes, pictures, sounds → .zip", "Настройки, моды и их данные, темы, картинки, звуки → .zip"),
            onClick = { if (!busy) save.launch("expressive-keyboard-full-backup.zip") }
        )
        ActionItem(
            Icons.Rounded.Download, l("Restore everything", "Восстановить всё"),
            l("From a full backup .zip", "Из полной копии .zip"),
            onClick = { if (!busy) runCatching { open.launch(arrayOf("*/*")) }.onFailure { toast(l("No file picker", "Нет выбора файлов")) } }
        )
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(l("Restore everything?", "Восстановить всё?")) },
            text = {
                Text(l(
                    "Current settings and mods will be replaced, and mods may be switched on with their permissions. Only restore a backup you made yourself. The app will close when done.",
                    "Настройки и моды будут заменены, моды могут включиться со своими разрешениями. Восстанавливайте только свою копию. Потом приложение закроется."
                ))
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
                                input.use { FullBackup.restore(context, it) }
                            }
                        }
                        busy = false
                        if (r.isSuccess) {
                            toast(l("Restored. Open the app again", "Готово. Откройте приложение снова"))
                            delay(700)
                            // a fresh process, so nothing in memory overwrites the restored data
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } else toast(l("Restore failed: ", "Ошибка восстановления: ") + r.exceptionOrNull()?.message)
                    }
                }) { Text(l("Restore", "Восстановить")) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(l("Cancel", "Отмена")) } }
        )
    }
}
