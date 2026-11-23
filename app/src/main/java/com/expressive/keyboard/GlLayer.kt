package com.expressive.keyboard

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

// ============================================================================
// GLSL ДЛЯ МОДОВ (OpenGL ES 2.0 / 3.0), работает на Android 8+ (в отличие от AGSL, которому нужен Android 13).
// Элемент kb.overlay / kb.background: { type: "gl", frag, vert?, uniforms?, mesh?, x, y, w, h, scale, fps, depth, clear }.
// Рисует отдельный поток в TextureView: скрипт и интерфейс клавиатуры его не тормозят.
// ============================================================================

object GlSupport {
    /** Сколько GL-слоёв одновременно в одном списке (kb.overlay / kb.background); каждый — свой поток и контекст. */
    const val MAX_LAYERS = 4
    const val MAX_SRC = 64_000
    const val TAG = "ModGl"

    private val errors = ConcurrentHashMap<Int, String>()

    private const val MAX_MESHES = 32

    /**
     * Общий кэш сеток: "мод/ключ" -> сетка. Раньше сетка жила только в том рендерере, куда её прислали, и { key } без данных
     * молча ничего не рисовал, если элемент попадал в другой рендерер. LRU, чтобы память не росла.
     */
    private val meshes: MutableMap<String, GlMesh> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, GlMesh>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GlMesh>?): Boolean = size > MAX_MESHES
        }
    )

    fun cacheMesh(owner: String, m: GlMesh) {
        val k = m.key ?: return
        if (m.reuse) return
        meshes[owner + "/" + k] = m
        liveVersion++
    }

    /** Bumped whenever live uniforms or cached meshes change: a renderer with a static shader redraws only when this moves. */
    @Volatile var liveVersion = 0

    private val statDraws = java.util.concurrent.atomic.AtomicLong()
    private val statSkips = java.util.concurrent.atomic.AtomicLong()
    private val statNanos = java.util.concurrent.atomic.AtomicLong()
    private val statMaxNanos = java.util.concurrent.atomic.AtomicLong()

    fun recordDraw(nanos: Long) {
        statDraws.incrementAndGet()
        statNanos.addAndGet(nanos)
        var m = statMaxNanos.get()
        while (nanos > m && !statMaxNanos.compareAndSet(m, nanos)) m = statMaxNanos.get()
    }

    fun recordSkip() {
        statSkips.incrementAndGet()
    }

    /** One line for the script diagnostics: GL frames drawn and skipped, average and worst CPU time per frame. */
    fun statsLine(): String? {
        val n = statDraws.get()
        if (n == 0L) return null
        val avg = statNanos.get() / 1_000_000.0 / n
        return "gl: frames " + n + "  ·  skipped idle: " + statSkips.get() + "  ·  avg: " +
            String.format(java.util.Locale.US, "%.2f", avg) + " ms  ·  max: " +
            String.format(java.util.Locale.US, "%.2f", statMaxNanos.get() / 1_000_000.0) + " ms"
    }

    fun cachedMesh(owner: String, key: String): GlMesh? = meshes[owner + "/" + key]

    /** Значения uniform, присланные через kb.glUniforms: рендерер читает их каждый кадр, список элементов не пересобирается. */
    private val live = ConcurrentHashMap<String, ConcurrentHashMap<String, FloatArray>>()

    fun liveKey(pluginId: String, id: String): String = pluginId + "/" + id

    fun toFloats(v: Any?): FloatArray? = when (v) {
        is Number -> floatArrayOf(v.toFloat())
        is Boolean -> floatArrayOf(if (v) 1f else 0f)
        is List<*> -> FloatArray(v.size) { j -> (v[j] as? Number)?.toFloat() ?: 0f }
        is String -> PluginParser.parseColor(v)?.let { c ->
            floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, ((c ushr 24) and 255) / 255f)
        }
        else -> null
    }

    fun putUniforms(key: String, patch: Map<String, Any?>) {
        if (live.size > 64 && !live.containsKey(key)) live.clear()
        val m = live.getOrPut(key) { ConcurrentHashMap() }
        for ((k, v) in patch) {
            val f = toFloats(v)
            if (f != null) m[k] = f
        }
        liveVersion++
    }

    fun dropLive(key: String) {
        live.remove(key)
        liveVersion++
    }

    fun liveFor(key: String?): Map<String, FloatArray>? = if (key == null) null else live[key]

    fun fail(frag: String, message: String) {
        if (errors.size > 32) errors.clear()
        errors[frag.hashCode()] = message
        Log.w(TAG, message)
    }

    fun clear(frag: String) {
        errors.remove(frag.hashCode())
    }

    /** Текст последней ошибки сборки этого фрагментного шейдера или null (kb.glError). */
    fun error(frag: String): String? = errors[frag.hashCode()]
}

class GlAttrib(val size: Int, val data: FloatArray)

/**
 * Сетка для вершинного шейдера. attribs: имя атрибута -> размер (1..4) и плоский массив чисел.
 * key без attribs: использовать уже загруженную сетку с этим ключом (не пересылать данные каждый кадр).
 */
class GlMesh(
    val mode: Int,
    val count: Int,
    val attribs: Map<String, GlAttrib>,
    val indices: IntArray?,
    val key: String?,
    val reuse: Boolean
) {
    /** Отпечаток содержимого: одинаковую сетку, присланную заново, рендерер не загружает в видеопамять второй раз. */
    /** Some index is above 65535: the mesh needs 32-bit indices (core in OpenGL ES 3, an extension in ES 2). */
    val wide: Boolean by lazy { indices?.any { it > 65535 } == true }

    val sig: Long by lazy {
        var h = mode * 31L + count
        for ((k, a) in attribs) {
            h = h * 1000003L + k.hashCode()
            h = h * 1000003L + a.size
            h = h * 1000003L + java.util.Arrays.hashCode(a.data)
        }
        indices?.let { h = h * 1000003L + java.util.Arrays.hashCode(it) }
        h
    }

    companion object {
        private const val MAX_FLOATS = 1_000_000
        private const val MAX_INDICES = 1_000_000

        /** Полноэкранный прямоугольник для обычного фрагментного шейдера. */
        val QUAD = GlMesh(
            GLES20.GL_TRIANGLE_STRIP, 4,
            mapOf("aPos" to GlAttrib(2, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))),
            null, null, false
        )

        fun parse(v: Any?): GlMesh? {
            val o = v as? Map<*, *> ?: return null
            val key = o["key"]?.toString()?.take(64)
            val mode = when (o["mode"]?.toString()) {
                "points" -> GLES20.GL_POINTS
                "lines" -> GLES20.GL_LINES
                "lineStrip" -> GLES20.GL_LINE_STRIP
                "strip" -> GLES20.GL_TRIANGLE_STRIP
                "fan" -> GLES20.GL_TRIANGLE_FAN
                else -> GLES20.GL_TRIANGLES
            }
            val attrs = LinkedHashMap<String, GlAttrib>()
            var total = 0
            (o["attribs"] as? Map<*, *>)?.forEach { (k, a) ->
                val m = a as? Map<*, *> ?: return@forEach
                val size = (m["size"] as? Number)?.toInt()?.coerceIn(1, 4) ?: 3
                val list = m["data"] as? List<*> ?: return@forEach
                if (attrs.size >= 8 || total + list.size > MAX_FLOATS) return@forEach
                val arr = FloatArray(list.size) { i -> (list[i] as? Number)?.toFloat() ?: 0f }
                total += arr.size
                attrs[k.toString()] = GlAttrib(size, arr)
            }
            if (attrs.isEmpty()) {
                return if (key != null) GlMesh(mode, 0, emptyMap(), null, key, true) else null
            }
            val idx = (o["indices"] as? List<*>)?.let { l ->
                IntArray(minOf(l.size, MAX_INDICES)) { i -> ((l[i] as? Number)?.toInt() ?: 0).coerceAtLeast(0) }
            }
            val first = attrs.values.first()
            val count = idx?.size ?: (first.data.size / first.size)
            if (count <= 0) return null
            return GlMesh(mode, count, attrs, idx, key, false)
        }
    }
}

/** Built-in uniforms whose value changes without any message from the mod. */
private val DYNAMIC_UNIFORMS = hashSetOf("iTime", "iFrame", "iTouch", "iTilt", "iAudio")

private const val VERT2 = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n"
private const val VERT3 = "#version 300 es\nin vec2 aPos;\nout vec2 vUv;\nvoid main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n"

private class GlPrepared(val item: OverlayItem.Gl, val floats: Map<String, FloatArray>)

private class GlUniform(val name: String, val loc: Int, val type: Int, val size: Int)

/** Один GL-контекст и поток отрисовки на один TextureView. Всё GL — только в этом потоке. */
private class GlRenderer(private val st: SurfaceTexture) : Thread("mod-gl") {
    @Volatile private var alive = true
    @Volatile var paused = false
    @Volatile private var prepared: GlPrepared? = null
    @Volatile private var density = 1f
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glVersion = 2
    private var depthOk = false

    private var program = 0
    private var programKey: String? = null
    private var uniforms: List<GlUniform> = emptyList()

    private var meshIds = IntArray(0)
    private var meshOwner: GlMesh? = null
    private var meshKey: String? = null
    private var lastBufW = 0
    private var lastBufH = 0
    private var missingWarned = false
    private var depthWarned = false

    /** Uniform values already on the GPU for the current program: unchanged arrays are not uploaded again. */
    private val sent = HashMap<String, FloatArray>()

    /** The shader reads time, touch, tilt or audio, so every frame differs. A static shader is redrawn only when something changed. */
    private var animated = true
    private var uintOk = false
    private var wideWarned = false

    init {
        isDaemon = true
    }

    fun setSize(w: Int, h: Int) {
        viewW = w
        viewH = h
    }

    fun setItem(item: OverlayItem.Gl, d: Float) {
        density = d
        if (prepared?.item === item) return
        val fl = HashMap<String, FloatArray>()
        for ((k, v) in item.uniforms) GlSupport.toFloats(v)?.let { fl[k] = it }
        prepared = GlPrepared(item, fl)
        LockSupport.unpark(this)
    }

    fun shutdown() {
        alive = false
        LockSupport.unpark(this)
        try {
            join(800)
        } catch (e: InterruptedException) {
            // остановка всё равно идёт
        }
    }

    override fun run() {
        try {
            if (!initEgl()) {
                prepared?.item?.let { GlSupport.fail(it.frag, "GL: could not create an OpenGL ES context") }
                return
            }
            loop()
        } catch (t: Throwable) {
            Log.w(GlSupport.TAG, "gl thread failed", t)
            prepared?.item?.let { GlSupport.fail(it.frag, "GL: " + (t.message ?: t.javaClass.simpleName)) }
        } finally {
            releaseEgl()
        }
    }

    private fun chooseConfig(depth: Int, renderable: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, depth,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return null
        return cfgs[0]
    }

    /** Пробует версию GL (3 или 2) с буфером глубины, потом без него. Нашёл — запоминает контекст и возвращает конфиг. */
    private fun tryContext(version: Int, renderable: Int): EGLConfig? {
        for (d in intArrayOf(16, 0)) {
            val cfg = chooseConfig(d, renderable) ?: continue
            val ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0)
            if (ctx != EGL14.EGL_NO_CONTEXT) {
                context = ctx
                glVersion = version
                depthOk = d > 0
                return cfg
            }
        }
        return null
    }

    private fun initEgl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        // сначала ES 3.0 (конфиг с битом ES3), иначе ES 2.0
        val cfg = tryContext(3, EGLExt.EGL_OPENGL_ES3_BIT_KHR) ?: tryContext(2, EGL14.EGL_OPENGL_ES2_BIT) ?: return false
        surface = EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        uintOk = glVersion >= 3 || (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "").contains("GL_OES_element_index_uint")
        return true
    }

    private fun releaseEgl() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        } catch (t: Throwable) {
            Log.w(GlSupport.TAG, "egl release", t)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun loop() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        var n = 0
        var lastPrepared: GlPrepared? = null
        var lastLive = -1
        var lastW = 0
        var lastH = 0
        var wasPaused = true
        while (alive) {
            val p = prepared
            if (paused || p == null || viewW <= 0 || viewH <= 0) {
                wasPaused = true
                LockSupport.parkNanos(30_000_000L)
                continue
            }
            val live = GlSupport.liveVersion
            val changed = wasPaused || p !== lastPrepared || live != lastLive || viewW != lastW || viewH != lastH
            if (!changed && !animated) {
                // nothing moved and the shader does not animate: the last frame stays on the screen
                GlSupport.recordSkip()
                LockSupport.parkNanos(16_000_000L)
                continue
            }
            wasPaused = false
            lastPrepared = p
            lastLive = live
            lastW = viewW
            lastH = viewH
            val start = SystemClock.elapsedRealtime()
            val startNs = System.nanoTime()
            val t = ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000_000.0 % 3600.0).toFloat()
            if (!draw(p, t, n++)) break
            GlSupport.recordDraw(System.nanoTime() - startNs)
            val wait = 1000L / p.item.fps - (SystemClock.elapsedRealtime() - start)
            if (wait > 0L) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    // woke up early: keep drawing
                }
            }
        }
    }

    private fun compile(type: Int, src: String, errKey: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            GlSupport.fail(errKey, kind + ": " + GLES20.glGetShaderInfoLog(s).trim())
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun ensureProgram(item: OverlayItem.Gl): Boolean {
        val key = item.frag + "\u0000" + item.vert
        if (key == programKey) return program != 0
        programKey = key
        sent.clear()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        val es3 = item.frag.trimStart().startsWith("#version 300 es")
        if (es3 && glVersion < 3) {
            GlSupport.fail(item.frag, "GL: this device needs OpenGL ES 3.0 for #version 300 es")
            return false
        }
        // В GLSL ES 1.00 фрагментному шейдеру обязательна точность: добавляем, если мод забыл
        val fragSrc = if (!es3 && !item.frag.contains("precision")) "precision mediump float;\n" + item.frag else item.frag
        val vertSrc = if (item.vert.isNotBlank()) item.vert else if (es3) VERT3 else VERT2
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertSrc, item.frag)
        if (vs == 0) return false
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragSrc, item.frag)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return false
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            GlSupport.fail(item.frag, "link: " + GLES20.glGetProgramInfoLog(p).trim())
            GLES20.glDeleteProgram(p)
            return false
        }
        program = p
        GlSupport.clear(item.frag)
        val cnt = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_ACTIVE_UNIFORMS, cnt, 0)
        val list = ArrayList<GlUniform>()
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until cnt[0]) {
            val raw = GLES20.glGetActiveUniform(p, i, size, 0, type, 0)
            val name = raw.removeSuffix("[0]")
            val loc = GLES20.glGetUniformLocation(p, name)
            if (loc >= 0) list.add(GlUniform(name, loc, type[0], size[0]))
        }
        uniforms = list
        animated = list.any { it.name in DYNAMIC_UNIFORMS }
        return true
    }

    private fun intBuf(a: IntArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().also {
        it.put(a)
        it.position(0)
    }

    private fun floatBuf(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
        it.put(a)
        it.position(0)
    }

    private fun shortBuf(a: ShortArray) = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also {
        it.put(a)
        it.position(0)
    }

    private fun uploadMesh(m: GlMesh) {
        if (meshIds.isNotEmpty()) GLES20.glDeleteBuffers(meshIds.size, meshIds, 0)
        val n = m.attribs.size + if (m.indices != null) 1 else 0
        val ids = IntArray(n)
        GLES20.glGenBuffers(n, ids, 0)
        var i = 0
        for (a in m.attribs.values) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[i])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.data.size * 4, floatBuf(a.data), GLES20.GL_DYNAMIC_DRAW)
            i++
        }
        if (m.indices != null) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[i])
            if (m.wide) {
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, intBuf(m.indices), GLES20.GL_DYNAMIC_DRAW)
            } else {
                val sh = ShortArray(m.indices.size) { k -> m.indices[k].toShort() }
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, sh.size * 2, shortBuf(sh), GLES20.GL_DYNAMIC_DRAW)
            }
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        meshIds = ids
        meshOwner = m
        meshKey = m.key
    }

    private fun setFloats(un: GlUniform, f: FloatArray) {
        val n = un.size
        when (un.type) {
            GLES20.GL_FLOAT -> if (f.isNotEmpty()) GLES20.glUniform1fv(un.loc, minOf(n, f.size), f, 0)
            GLES20.GL_FLOAT_VEC2 -> if (f.size >= 2) GLES20.glUniform2fv(un.loc, minOf(n, f.size / 2), f, 0)
            GLES20.GL_FLOAT_VEC3 -> if (f.size >= 3) GLES20.glUniform3fv(un.loc, minOf(n, f.size / 3), f, 0)
            GLES20.GL_FLOAT_VEC4 -> if (f.size >= 4) GLES20.glUniform4fv(un.loc, minOf(n, f.size / 4), f, 0)
            GLES20.GL_FLOAT_MAT2 -> if (f.size >= 4) GLES20.glUniformMatrix2fv(un.loc, minOf(n, f.size / 4), false, f, 0)
            GLES20.GL_FLOAT_MAT3 -> if (f.size >= 9) GLES20.glUniformMatrix3fv(un.loc, minOf(n, f.size / 9), false, f, 0)
            GLES20.GL_FLOAT_MAT4 -> if (f.size >= 16) GLES20.glUniformMatrix4fv(un.loc, minOf(n, f.size / 16), false, f, 0)
            GLES20.GL_INT, GLES20.GL_BOOL -> if (f.isNotEmpty()) {
                val iv = IntArray(minOf(n, f.size)) { f[it].toInt() }
                GLES20.glUniform1iv(un.loc, iv.size, iv, 0)
            }
        }
    }

    /** Uploads only when the values differ from what this program already has (the arrays are never changed in place). */
    private fun setCached(un: GlUniform, f: FloatArray) {
        val prev = sent[un.name]
        if (prev != null && (prev === f || prev.contentEquals(f))) return
        setFloats(un, f)
        sent[un.name] = f
    }

    private fun setUniforms(p: GlPrepared, w: Int, h: Int, t: Float, frame: Int) {
        val dens = density
        val item = p.item
        val ratio = if (viewW > 0) w.toFloat() / viewW else 1f
        for (un in uniforms) {
            when (un.name) {
                "iTime" -> setFloats(un, floatArrayOf(t))
                "iFrame" -> setFloats(un, floatArrayOf(frame.toFloat()))
                "iResolution" -> setCached(un, floatArrayOf(w.toFloat(), h.toFloat(), 1f))
                "iDensity" -> setCached(un, floatArrayOf(dens))
                "iTouch" -> {
                    // пиксели буфера, начало — левый верхний угол слоя; следит за пальцем сама клавиатура, подписка мода на pointer не нужна
                    val down = ModFx.pointerCount > 0
                    val tx = (ModFx.lastX * dens - item.x * dens) * ratio
                    val ty = (ModFx.lastY * dens - item.y * dens) * ratio
                    setFloats(un, floatArrayOf(tx, ty, if (down) 1f else 0f))
                }
                "iTilt" -> setFloats(un, floatArrayOf(ModSensors.tiltX, ModSensors.tiltY, ModSensors.tiltZ))
                "iAudio" -> setFloats(un, floatArrayOf(ModAudio.level))
                else -> (GlSupport.liveFor(item.liveKey)?.get(un.name) ?: p.floats[un.name])?.let { setCached(un, it) }
            }
        }
    }

    /** false — поверхность пропала, поток должен остановиться. */
    private fun draw(p: GlPrepared, t: Float, frame: Int): Boolean {
        val item = p.item
        val dw = max(2, (viewW * item.scale).toInt())
        val dh = max(2, (viewH * item.scale).toInt())
        if (dw != lastBufW || dh != lastBufH) {
            st.setDefaultBufferSize(dw, dh)
            lastBufW = dw
            lastBufH = dh
        }
        val sw = IntArray(1)
        val sh = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, sw, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, sh, 0)
        val w = max(1, sw[0])
        val h = max(1, sh[0])
        GLES20.glViewport(0, 0, w, h)
        val a = item.clear.alpha
        GLES20.glClearColor(item.clear.red * a, item.clear.green * a, item.clear.blue * a, a)
        val useDepth = item.depth && depthOk
        if (useDepth) GLES20.glEnable(GLES20.GL_DEPTH_TEST) else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or if (useDepth) GLES20.GL_DEPTH_BUFFER_BIT else 0)

        if (!ensureProgram(item)) animated = false
        else {
            if (item.depth && !depthOk && !depthWarned) {
                depthWarned = true
                GlSupport.fail(item.frag, "GL: this device gave no depth buffer, depth: true is ignored")
            }
            drawMesh(p, w, h, t, frame)
        }

        if (!EGL14.eglSwapBuffers(display, surface)) {
            val err = EGL14.eglGetError()
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW || err == EGL14.EGL_CONTEXT_LOST) return false
        }
        return true
    }

    private fun drawMesh(p: GlPrepared, w: Int, h: Int, t: Float, frame: Int) {
        val item = p.item
        val given = item.mesh ?: GlMesh.QUAD
        val use: GlMesh
        if (given.reuse) {
            // { key } без данных: берём сетку из общего кэша, а не только из этого рендерера
            val k = given.key
            val cached = if (k != null) GlSupport.cachedMesh(item.pluginId, k) else null
            use = when {
                cached != null -> cached
                meshOwner != null && meshKey == k -> meshOwner!!
                else -> {
                    if (!missingWarned) {
                        missingWarned = true
                        GlSupport.fail(item.frag, "GL: mesh \"$k\" is not loaded, send it once with its attribs before using { key } alone")
                    }
                    return
                }
            }
        } else {
            if (given.key != null) GlSupport.cacheMesh(item.pluginId, given)
            use = given
        }
        if (use !== meshOwner) {
            val cur = meshOwner
            if (cur != null && use.sig == cur.sig && use.key == cur.key && use.count == cur.count) {
                // та же сетка прислана заново (мод шлёт её при каждом обновлении): буферы на месте, загрузку пропускаем
                meshOwner = use
            } else {
                uploadMesh(use)
            }
        }
        val m = meshOwner ?: return
        GLES20.glUseProgram(program)
        setUniforms(p, w, h, t, frame)
        var i = 0
        val enabled = ArrayList<Int>()
        for ((name, a) in m.attribs) {
            val loc = GLES20.glGetAttribLocation(program, name)
            if (loc >= 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshIds[i])
                GLES20.glEnableVertexAttribArray(loc)
                GLES20.glVertexAttribPointer(loc, a.size, GLES20.GL_FLOAT, false, 0, 0)
                enabled.add(loc)
            }
            i++
        }
        if (m.indices != null) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshIds[i])
            if (m.wide) {
                // 32-bit indices: core in OpenGL ES 3, otherwise the OES_element_index_uint extension
                if (glVersion >= 3 || uintOk) {
                    GLES20.glDrawElements(m.mode, m.count, GLES20.GL_UNSIGNED_INT, 0)
                } else if (!wideWarned) {
                    wideWarned = true
                    GlSupport.fail(item.frag, "GL: this device cannot draw more than 65536 vertices with indices (no OES_element_index_uint)")
                }
            } else {
                GLES20.glDrawElements(m.mode, m.count, GLES20.GL_UNSIGNED_SHORT, 0)
            }
        } else {
            GLES20.glDrawArrays(m.mode, 0, m.count)
        }
        for (loc in enabled) GLES20.glDisableVertexAttribArray(loc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

/** Прозрачный TextureView с отдельным потоком OpenGL. Касания не перехватывает. */
internal class GlTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var renderer: GlRenderer? = null
    private var item: OverlayItem.Gl? = null
    private var density = 1f

    init {
        surfaceTextureListener = this
        isOpaque = false
        isClickable = false
        isFocusable = false
    }

    fun setItem(i: OverlayItem.Gl, d: Float) {
        item = i
        density = d
        renderer?.setItem(i, d)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.shutdown()
        val r = GlRenderer(surface)
        r.setSize(width, height)
        item?.let { r.setItem(it, density) }
        r.paused = windowVisibility != View.VISIBLE
        renderer = r
        r.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.setSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer?.shutdown()
        renderer = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        renderer?.paused = visibility != View.VISIBLE
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

@Composable
internal fun BoxScope.GlItemLayer(item: OverlayItem.Gl) {
    val d = LocalDensity.current.density
    val place = if (item.w > 0f && item.h > 0f) Modifier.offset(item.x.dp, item.y.dp).size(item.w.dp, item.h.dp) else Modifier.matchParentSize()
    AndroidView(
        factory = { GlTextureView(it) },
        modifier = place.graphicsLayer {
            alpha = item.alpha
            rotationZ = item.rotation
        },
        update = { it.setItem(item, d) }
    )
}

/** GL-слои из списка элементов: рисуются под обычными фигурами того же слоя. */
@Composable
internal fun BoxScope.GlItems(items: List<OverlayItem>) {
    var n = 0
    for (it in items) {
        if (it is OverlayItem.Gl && n < GlSupport.MAX_LAYERS) {
            key(n) { GlItemLayer(it) }
            n++
        }
    }
}
