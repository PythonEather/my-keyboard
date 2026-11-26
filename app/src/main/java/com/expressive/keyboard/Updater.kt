package com.expressive.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ============================================================================
// ОБНОВЛЕНИЯ ПО ВОЗДУХУ (OTA)
// Новая версия — это релиз на GitHub (PythonEather/my-keyboard) с приложенным APK. Его делает workflow «Release APK»
// сам, как только в app/build.gradle.kts поднимается versionName и изменение попадает в main; можно и вручную:
// Releases → Draft a new release → тег v11.2.16 → прикрепить APK → Publish.
// Приложение смотрит последние релизы (черновики пропускает, пре-релизы и «не latest» учитывает), берёт самую новую версию
// с подходящим APK, сравнивает её со своей, скачивает, проверяет, что это тот же пакет и версия новее, и открывает
// системную установку. Обновление встанет поверх, только если APK подписан тем же ключом.
// ============================================================================

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

/** One release of the GitHub API, reduced to what the updater needs (plain data, so the choice below is unit-tested). */
internal data class ReleaseRow(val tag: String, val draft: Boolean, val notes: String, val page: String, val assets: List<AssetRow>)

internal data class AssetRow(val name: String, val url: String, val size: Long)

/** Выбор релиза и сравнение версий: чистая логика без Android и без состояния, поэтому её проверяют обычные тесты. */
internal object UpdateLogic {
    /** «11.1.10» > «11.1.9»: сравниваем числа по порядку. Метка вроде «v11.2.15-beta» читается как 11.2.15. */
    internal fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = versionOf(v).split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private val VERSION = Regex("\\d+(?:\\.\\d+)*")

    /** Первое число вида 11.2.15 в метке релиза («v11.2.15», «Expressive 11.2.15»); без чисел — «0». */
    internal fun versionOf(tag: String): String = VERSION.find(tag)?.value ?: "0"

    /** APK, который можно поставить: не debug и не unsigned; с понятным именем — в приоритете. */
    internal fun apkOf(assets: List<AssetRow>): AssetRow? {
        var best: AssetRow? = null
        for (a in assets) {
            val n = a.name.lowercase()
            if (!n.endsWith(".apk") || "debug" in n || "unsigned" in n || a.url.isEmpty()) continue
            if (best == null || a.name.startsWith("ExpressiveKeyboard-")) best = a
        }
        return best
    }

    /** Самая новая версия среди релизов с подходящим APK (черновики пропускаем); null — такого релиза нет. */
    internal fun pick(releases: List<ReleaseRow>): UpdateInfo? {
        var best: UpdateInfo? = null
        for (r in releases) {
            if (r.draft || r.tag.isEmpty()) continue
            val apk = apkOf(r.assets) ?: continue
            val v = versionOf(r.tag)
            if (best == null || newer(v, best.version)) {
                best = UpdateInfo(v, r.notes.trim(), apk.url, apk.size, r.page)
            }
        }
        return best
    }
}

object Updater {
    private const val REPO = "PythonEather/my-keyboard"
    private const val PREF_AUTO = "auto_update_check"

    /** Найденное обновление (null — его нет или ещё не проверяли). */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Скачано, 0…1; null — не качаем. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Проверяли и обновлений нет — для кнопки «Проверить обновления». */
    var upToDate by mutableStateOf(false)
        private set
    /** Проверяли, но на GitHub нет ни одного релиза с APK: «последняя версия» тут было бы неправдой. */
    var noRelease by mutableStateOf(false)
        private set
    private var autoChecked = false

    fun autoEnabled(context: Context): Boolean = KeyboardPrefs.prefs(context).getBoolean(PREF_AUTO, true)
    fun setAuto(context: Context, on: Boolean) = KeyboardPrefs.edit(context) { putBoolean(PREF_AUTO, on) }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty().substringBefore('-')

    /** Один раз за запуск, если не выключено в настройках. */
    suspend fun autoCheck(context: Context) {
        if (autoChecked || !autoEnabled(context)) return
        autoChecked = true
        check(context, quiet = true)
    }

    suspend fun check(context: Context, quiet: Boolean = false) {
        if (checking) return
        checking = true
        error = null
        upToDate = false
        noRelease = false
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            val local = currentVersion(context)
            available = info?.takeIf { local.isNotEmpty() && UpdateLogic.newer(it.version, local) }
            noRelease = info == null
            upToDate = info != null && available == null
        } catch (e: Exception) {
            // не вышло — в следующий раз попробуем снова, а не «раз за запуск»
            autoChecked = false
            if (!quiet) error = tr("Не удалось проверить обновления: нет связи с GitHub")
        } finally {
            checking = false
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        // /releases/latest не показывает пре-релизы, а если «latest» отметили не на новом релизе — отдаёт старый: смотрим список сами
        val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=20").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ExpressiveKeyboard")
        }
        val body = try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val arr = org.json.JSONArray(body)
        val rows = ArrayList<ReleaseRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val assets = ArrayList<AssetRow>()
            val list = o.optJSONArray("assets")
            if (list != null) for (j in 0 until list.length()) {
                val a = list.optJSONObject(j) ?: continue
                assets.add(AssetRow(a.optString("name"), a.optString("browser_download_url"), a.optLong("size")))
            }
            rows.add(ReleaseRow(o.optString("tag_name"), o.optBoolean("draft"), o.optString("body"), o.optString("html_url"), assets))
        }
        return UpdateLogic.pick(rows)
    }

    /** Скачать APK и открыть установку. Без разрешения «Установка неизвестных приложений» — сначала просим его. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            error = tr("Разрешите установку обновлений и нажмите ещё раз")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        error = null
        progress = 0f
        try {
            // файл уже скачан (например, окно установки закрыли касанием снаружи): ставим его, не качая заново
            val kept = withContext(Dispatchers.IO) { downloaded(context, info) }
            val file = kept ?: withContext(Dispatchers.IO) { download(context, info) }
            // не тот пакет, не тот ключ или версия не новее: система только написала бы «приложение не установлено», без причины
            val bad = checkApk(context, file)
            if (bad != null) {
                file.delete()
                error = bad
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            error = tr("Не удалось скачать обновление")
        } finally {
            progress = null
        }
    }

    /** Уже скачанный и целый APK этой версии из кэша или null. Файл остаётся до следующего обновления. */
    private fun downloaded(context: Context, info: UpdateInfo): File? {
        val f = File(File(context.cacheDir, "updates"), "ExpressiveKeyboard-${info.version}.apk")
        if (!f.isFile || f.length() == 0L) return null
        if (info.size > 0 && f.length() != info.size) return null
        return if (checkApk(context, f) == null) f else null
    }

    /** SHA-256 сертификатов, которыми подписан пакет (пусто, если система их не отдала). */
    private fun signers(pi: android.content.pm.PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs: Array<android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        val md = { b: ByteArray -> java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
        return sigs?.map { md(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    /** Текст ошибки, если скачанный файл не подходит для обновления этого приложения, иначе null. */
    private fun checkApk(context: Context, file: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return tr("Скачанный файл — не APK или повреждён")
        if (archive.packageName != context.packageName) return tr("Скачанный APK — другое приложение, им это не обновить")
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return null
        // другой ключ подписи: Android откажет с общим «не удалось установить», пока не удалить старую версию
        val a = signers(archive)
        val b = signers(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            return tr("Обновление подписано другим ключом, чем установленное приложение. Удалите приложение и поставьте новую версию заново")
        }
        if (androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive) <= androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed)) {
            return tr("В скачанном APK версия не новее установленной")
        }
        return null
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "ExpressiveKeyboard-${info.version}.apk")
        dir.listFiles()?.forEach { if (it.name != out.name) it.delete() }
        out.delete()
        var url = URL(info.apkUrl)
        // GitHub отдаёт файл через переадресацию на другой адрес — идём по ней сами
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ExpressiveKeyboard")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, next)
                continue
            }
            break
        }
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && out.length() != info.size) throw java.io.IOException("incomplete download")
        return out
    }
}

/**
 * Иконка приложения, вокруг которой во время загрузки бежит волнистое кольцо прогресса, как в Google Play.
 * Пока загрузки нет, кольцо скрыто, а иконка занимает всё место.
 */
@Composable
internal fun AppIconWithProgress(size: Dp, progress: Float?, content: @Composable () -> Unit) {
    val ring = 14.dp
    val inner by animateDpAsState(
        if (progress != null) size - ring else size,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "iconShrink"
    )
    Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(size + 12.dp))
        }
        Box(Modifier.size(inner)) { content() }
    }
}

/** Карточка «Доступна новая версия»: что нового (сворачивается) и состояние загрузки. */
@Composable
internal fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.autoCheck(context) }
    val info = Updater.available
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(info != null) {
        val u = info ?: return@AnimatedVisibility
        var expanded by remember(u.version) { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(glassSurface(cs.surfaceBright)).padding(20.dp)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 400f)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = cs.primary)
                Text(
                    tr("Доступна версия {0}", u.version), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold, color = cs.onSurface
                )
            }
            if (u.notes.isNotEmpty()) {
                Text(
                    tr("Что нового"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.primary
                )
                Text(
                    u.notes, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(tr(if (expanded) "Свернуть" else "Подробнее"))
                }
            }
            val p = Updater.progress
            if (p != null) {
                // кольцо прогресса бежит вокруг иконки приложения вверху страницы, здесь только проценты
                Text(tr("Скачиваю… {0}%", (p * 100).toInt()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
            } else {
                Button(onClick = { scope.launch { Updater.downloadAndInstall(context, u) } }) {
                    Text(tr("Обновить"))
                }
            }
            Updater.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
        }
    }
}
