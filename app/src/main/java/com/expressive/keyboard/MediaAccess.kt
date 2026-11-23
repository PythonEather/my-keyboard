package com.expressive.keyboard

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Entry point that makes Android let the keyboard read media sessions ("Notification access"). It never looks at
 * notifications: both notification callbacks are left as they are, only the media-session API uses this component.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() = MediaBridge.accessChanged(applicationContext)
    override fun onListenerDisconnected() = MediaBridge.stop()
}

/**
 * The "media" plugin permission: what is playing now, plus play/pause/next/previous.
 * Without Notification access only "is music playing" and the media keys work; with it the title, artist, album,
 * position and cover are available too. Nothing is read while no plugin with the permission listens.
 */
internal object MediaBridge {
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var listening = false
    private var controllers: List<MediaController> = emptyList()
    private var lastKey: List<Any?>? = null
    private var artKey: String? = null
    private var artUri = ""

    val EMPTY: Map<String, Any?> = mapOf(
        "access" to false, "active" to false, "playing" to false, "title" to "", "artist" to "", "album" to "",
        "app" to "", "position" to 0.0, "duration" to 0.0
    )

    /** Is Notification access granted to this app (the user turns it on in the system settings)? */
    fun hasAccess(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val me = ComponentName(app, MediaListenerService::class.java)
        val flat = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun musicActive(app: Context): Boolean =
        (app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true

    private fun sessions(app: Context): List<MediaController> {
        if (!hasAccess(app)) return emptyList()
        val m = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return emptyList()
        return runCatching { m.getActiveSessions(ComponentName(app, MediaListenerService::class.java)) }.getOrDefault(emptyList())
    }

    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()

    private fun build(c: MediaController?): Map<String, Any?> {
        if (c == null) return EMPTY + ("access" to true)
        val md = c.metadata
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        var pos = ps?.position ?: 0L
        if (playing && ps != null) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        return mapOf(
            "access" to true, "active" to true, "playing" to playing,
            "title" to (md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").take(200),
            "artist" to (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "").take(200),
            "album" to (md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").take(200),
            "app" to c.packageName,
            "position" to max(pos, 0L).toDouble(),
            "duration" to max(md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L, 0L).toDouble()
        )
    }

    /** kb.media.state(): the current state, read right now. */
    fun state(ctx: Context): Map<String, Any?> {
        val app = ctx.applicationContext
        if (!hasAccess(app)) return EMPTY + ("playing" to musicActive(app))
        return build(pick(sessions(app)))
    }

    /** kb.media.art(): the cover as a small data: URI (up to 128 px), or an empty string. */
    fun art(ctx: Context): String {
        val c = pick(sessions(ctx.applicationContext)) ?: return ""
        val md = c.metadata ?: return ""
        val key = c.packageName + "|" + md.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        if (key == artKey) return artUri
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = if (bmp == null || bmp.width <= 0 || bmp.height <= 0) "" else runCatching {
            val k = 128f / max(bmp.width, bmp.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")
        artKey = key
        artUri = uri
        return uri
    }

    /** kb.media.play/pause/toggle/next/previous/seek. Without access the system media keys are used. */
    fun control(ctx: Context, cmd: String, arg: Long = 0L): Boolean {
        val app = ctx.applicationContext
        val c = pick(sessions(app))
        if (c != null) {
            val t = c.transportControls
            when (cmd) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "seek" -> t.seekTo(max(arg, 0L))
                else -> return false
            }
            return true
        }
        val code = when (cmd) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    // ---- the "media" event: only while a plugin with the permission listens and the keyboard is on screen

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }

    fun sync(ctx: Context, want: Boolean) {
        val app = ctx.applicationContext
        if (want && hasAccess(app)) start(app) else stop()
    }

    fun accessChanged(@Suppress("UNUSED_PARAMETER") app: Context) {
        main.post { ScriptHost.refreshDevices() }
    }

    private fun start(app: Context) {
        if (listening) return
        val m = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val cn = ComponentName(app, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            manager = m
            listening = true
            attach(runCatching { m.getActiveSessions(cn) }.getOrNull())
        } catch (_: SecurityException) {
            listening = false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        for (c in controllers) runCatching { c.unregisterCallback(callback) }
        controllers = emptyList()
        manager = null
        lastKey = null
    }

    private fun attach(list: List<MediaController>?) {
        for (c in controllers) runCatching { c.unregisterCallback(callback) }
        controllers = list.orEmpty()
        for (c in controllers) runCatching { c.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        val s = build(pick(controllers))
        val key = listOf(s["title"], s["artist"], s["album"], s["playing"], s["app"], s["duration"])
        if (key == lastKey) return
        lastKey = key
        ScriptHost.emit("media", s)
    }
}
