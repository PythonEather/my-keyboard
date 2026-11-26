package com.expressive.keyboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlin.math.max

/**
 * Live wallpaper: a muted looping video behind the keyboard keys or behind the Settings pages.
 * Plain MediaPlayer + TextureView, so it works on every Android version. The clip is copied into the app's private
 * storage when picked, and it pauses whenever the window is hidden, so a closed keyboard costs nothing.
 */
internal object LiveWallpaper {
    private const val MAX_BYTES = 60L * 1024 * 1024

    fun file(context: Context, target: String) = File(context.filesDir, "live_$target.mp4")

    fun remove(context: Context, target: String) {
        file(context, target).delete()
    }

    /** Copies the picked clip and checks that it really has a video track. Returns false if it does not work. */
    fun import(context: Context, uri: Uri, target: String): Boolean = runCatching {
        val dest = file(context, target)
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { tmp.delete(); return false }
                    out.write(buf, 0, n)
                }
            }
        } ?: return false
        val mmr = MediaMetadataRetriever()
        val hasVideo = try {
            mmr.setDataSource(tmp.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } finally {
            runCatching { mmr.release() }
        }
        if (!hasVideo) { tmp.delete(); return false }
        dest.delete()
        tmp.renameTo(dest)
    }.getOrDefault(false)

    /** Runs [import] off the main thread and reports back on it. */
    fun importAsync(context: Context, uri: Uri, target: String, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val ok = import(app, uri, target)
            Handler(Looper.getMainLooper()).post { done(ok) }
        }.start()
    }
}

/** A looping muted video that fills its bounds like "cover". [version] restarts it when the clip is replaced. */
@Composable
internal fun LiveWallpaperView(
    file: File,
    version: Long,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    scrim: Color = Color.Transparent
) {
    Box(modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha },
            factory = { ctx -> LiveVideoView(ctx).also { it.setClip(file.absolutePath, version) } },
            update = { it.setClip(file.absolutePath, version) },
            onRelease = { it.shutDown() }
        )
        if (scrim.alpha > 0f) Box(Modifier.matchParentSize().background(scrim))
    }
}

internal class LiveVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var path: String? = null
    private var version = -1L
    private var videoW = 0
    private var videoH = 0
    private var windowShown = true

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setClip(newPath: String, newVersion: Long) {
        if (newPath == path && newVersion == version) return
        path = newPath
        version = newVersion
        if (surface != null) start()
    }

    private fun start() {
        stop()
        val p = path ?: return
        val s = surface ?: return
        // battery saver: the clip does not play, the normal background shows instead
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power?.isPowerSaveMode == true) return
        runCatching {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(p)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; applyCover() }
            mp.setOnPreparedListener { m -> if (windowShown && player === m) m.start() }
            // a broken clip must never take the keyboard down: drop the player and keep the normal background
            mp.setOnErrorListener { m, _, _ -> if (player === m) stop(); true }
            mp.prepareAsync()
        }.onFailure { stop() }
    }

    private fun stop() {
        val mp = player ?: return
        player = null
        runCatching { mp.setOnPreparedListener(null); mp.setOnErrorListener(null); mp.reset() }
        runCatching { mp.release() }
    }

    fun shutDown() {
        stop()
        surface?.release()
        surface = null
    }

    /** TextureView stretches the video over the view; this scales it back so the picture fills the view uncropped-aspect ("cover"). */
    private fun applyCover() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val scale = max(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(scale * videoW / vw, scale * videoH / vh, vw / 2f, vh / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCover()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        val mp = player ?: return
        runCatching { if (windowShown) { if (!mp.isPlaying) mp.start() } else if (mp.isPlaying) mp.pause() }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCover()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stop()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
