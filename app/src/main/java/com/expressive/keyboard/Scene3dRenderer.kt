package com.expressive.keyboard

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Draws the scene of the built-in 3D engine inside a GL layer, on the layer's GL thread (see GlRenderer in GlLayer.kt).
 * The objects are drawn into the SAME framebuffer and depth buffer as the layer's own shader, after it. That is how a mesh
 * and a raycaster/raymarcher share depth: the background shader can write gl_FragDepth, and the objects are hidden behind it.
 * The code that decides what a scene means (models, lights, patches, camera) is in Scene3dCore/Models/State.kt and is unit-tested.
 */
internal object Scene3dShaders {
    const val MAX_LIGHTS = 8

    const val VERT = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform mat3 uNormalMat;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUv;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vWorld = w.xyz;
  vNormal = uNormalMat * aNormal;
  vUv = aUv;
  gl_Position = uViewProj * w;
}
"""

    const val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#define MAXL 8
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUv;
uniform vec3 uCamPos;
uniform vec3 uAmbient;
uniform vec4 uFog;
uniform vec4 uLightPos[MAXL];
uniform vec4 uLightCol[MAXL];
uniform float uLightCount;
uniform vec4 uColor;
uniform vec3 uEmissive;
uniform vec2 uSpec;
uniform float uHasTex;
uniform float uUnlit;
uniform float uAlphaCut;
uniform sampler2D uTex;
void main() {
  vec4 base = uColor;
  if (uHasTex > 0.5) {
    vec4 t = texture2D(uTex, vUv);
    if (t.a > 0.001) t.rgb = t.rgb / t.a;
    base *= t;
  }
  if (base.a < uAlphaCut) discard;
  vec3 N = normalize(vNormal);
  if (!gl_FrontFacing) N = -N;
  vec3 V = normalize(uCamPos - vWorld);
  vec3 col;
  if (uUnlit > 0.5) {
    col = base.rgb;
  } else {
    vec3 lit = uAmbient;
    vec3 spec = vec3(0.0);
    for (int i = 0; i < MAXL; i++) {
      if (float(i) >= uLightCount) break;
      vec3 L;
      float att = 1.0;
      if (uLightPos[i].w < 0.5) {
        L = normalize(uLightPos[i].xyz);
      } else {
        vec3 d = uLightPos[i].xyz - vWorld;
        float dist = length(d);
        L = d / max(dist, 0.0001);
        float r = uLightCol[i].w;
        if (r > 0.0) {
          float x = clamp(1.0 - (dist * dist) / (r * r), 0.0, 1.0);
          att = x * x;
        } else {
          att = 1.0 / (1.0 + dist * dist);
        }
      }
      float nl = max(dot(N, L), 0.0);
      lit += uLightCol[i].rgb * (nl * att);
      if (nl > 0.0) {
        vec3 H = normalize(L + V);
        spec += uLightCol[i].rgb * (pow(max(dot(N, H), 0.0), uSpec.y) * uSpec.x * att);
      }
    }
    col = base.rgb * lit + spec;
  }
  col += uEmissive;
  float camDist = length(uCamPos - vWorld);
  float fogK = exp(-uFog.a * uFog.a * camDist * camDist);
  col = mix(uFog.rgb, col, fogK);
  gl_FragColor = vec4(col * base.a, base.a);
}
"""
}

internal class Scene3dRenderer(
    /** Bytes of a file of the plugin (a model, its .mtl, its pictures), or null. */
    private val readFile: (String) -> ByteArray?,
    /** Changes whenever the file changes (the plugin was updated), so a model is loaded again. */
    private val stampOf: (String) -> Int,
    /** Reports an error to the mod's Script log (the host shows it once). */
    private val report: (String) -> Unit,
    private val glVersion: () -> Int,
    private val uintOk: () -> Boolean,
    /** Binds the picture of a texture spec to [unit] and points the sampler at [loc] with it. */
    private val bindTexture: (GlTexSpec, Int, Int) -> Unit
) {
    private class GpuPart(val vbo: Int, val ibo: Int, val count: Int, val wide: Boolean, val material: ModelMaterial?, val texture: GlTexSpec?)
    private class GpuModel(val key: String, val parts: List<GpuPart>)

    /** One surface after the model's own material and the mod's overrides are combined. */
    private class Surface(
        val part: GpuPart, val model: FloatArray, val normal: FloatArray, val color: FloatArray, val emissive: FloatArray,
        val specular: Float, val shininess: Float, val texture: GlTexSpec?, val unlit: Boolean, val doubleSided: Boolean, val alphaCut: Float,
        val translucent: Boolean, val distance: Float
    )

    private var program = 0
    private var programFailed = false
    private var locs = HashMap<String, Int>()
    private val models = HashMap<String, GpuModel>()
    private val failed = HashSet<String>()
    private val reported = HashSet<String>()
    private val specs = LinkedHashMap<String, GlTexSpec>()
    private var syncedSpec: SceneSpec? = null
    private var wideWarned = false

    /** The pictures this scene uses (inside models and named by objects): the texture pool must keep them. */
    fun textureSpecs(): Collection<GlTexSpec> = specs.values

    private fun say(msg: String) {
        if (reported.size > 64) reported.clear()
        if (reported.add(msg)) report(msg)
    }

    /** A new scene item: models it no longer lists are deleted from the GPU and failed ones are tried again. */
    fun sync(spec: SceneSpec) {
        if (spec === syncedSpec) return
        syncedSpec = spec
        failed.clear()
        val keep = spec.models.values.map { it.key }.toHashSet()
        val it = models.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.value.key.startsWith("prim:") && !keep.any { k -> e.value.key.startsWith(k) }) {
                free(e.value)
                it.remove()
            }
        }
    }

    fun release() {
        // the context is going away with its thread: the buffers go with it
        models.clear()
        specs.clear()
        objectSpecs.clear()
        program = 0
        programFailed = false
        locs.clear()
    }

    private fun free(m: GpuModel) {
        for (p in m.parts) GLES20.glDeleteBuffers(2, intArrayOf(p.vbo, p.ibo), 0)
    }

    // ------------------------------------------------------------------ program

    private fun ensureProgram(): Boolean {
        if (program != 0) return true
        if (programFailed) return false
        val vs = compile(GLES20.GL_VERTEX_SHADER, Scene3dShaders.VERT)
        val fs = if (vs != 0) compile(GLES20.GL_FRAGMENT_SHADER, Scene3dShaders.FRAG) else 0
        if (vs == 0 || fs == 0) {
            programFailed = true
            return false
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aNormal")
        GLES20.glBindAttribLocation(p, 2, "aUv")
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            say("scene: the 3D shader did not link: " + GLES20.glGetProgramInfoLog(p).trim())
            GLES20.glDeleteProgram(p)
            programFailed = true
            return false
        }
        program = p
        locs = HashMap()
        for (n in arrayOf(
            "uViewProj", "uModel", "uNormalMat", "uCamPos", "uAmbient", "uFog", "uLightPos", "uLightCol", "uLightCount",
            "uColor", "uEmissive", "uSpec", "uHasTex", "uUnlit", "uAlphaCut", "uTex"
        )) locs[n] = GLES20.glGetUniformLocation(p, n)
        return true
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            say("scene: the 3D ${if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"} shader did not compile: " + GLES20.glGetShaderInfoLog(s).trim())
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun loc(name: String): Int = locs[name] ?: -1

    // ------------------------------------------------------------------ models

    private val objectSpecs = HashMap<String, GlTexSpec>()

    /** The picture an object's material names; made once per source, not every frame. */
    private fun texSpec(src: String): GlTexSpec = objectSpecs.getOrPut(src) {
        GlTexSpec("scene:$src", src, GlTexSpec.Wrap.REPEAT, false, true).also { specs.putIfAbsent(it.key, it) }
    }

    private fun gpuModel(name: String, spec: SceneSpec): GpuModel? {
        val ms = spec.models[name]
        val key: String
        val src: String
        if (ms != null) {
            key = ms.key + "#" + (if (ModelLoader.isFile(ms.src)) stampOf(ms.src) else 0)
            src = ms.src
        } else if (name in Primitives.NAMES) {
            key = "prim:$name"
            src = name
        } else {
            say("scene: no model \"$name\" (declare it in models: { $name: \"file.glb\" } or use cube, plane, quad, sphere, cylinder)")
            return null
        }
        models[name]?.let { if (it.key == key) return it else { free(it); models.remove(name) } }
        if (key in failed) return null
        return try {
            var data = ModelLoader.load(src, readFile)
            if (ms != null) data = data.baked(ms.rotate, ms.center, ms.fit, ms.scale)
            val gpu = upload(key, data)
            models[name] = gpu
            gpu
        } catch (t: Throwable) {
            failed.add(key)
            say("scene: model $name: " + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    private fun upload(key: String, data: ModelData): GpuModel {
        val parts = ArrayList<GpuPart>()
        val wideOk = glVersion() >= 3 || uintOk()
        for (p in data.parts) {
            val wide = p.vertexCount > 65535
            if (wide && !wideOk) {
                if (!wideWarned) { wideWarned = true; say("scene: this device cannot draw a model part of more than 65535 vertices (no OES_element_index_uint)") }
                continue
            }
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)
            val vb = p.interleaved()
            val vbuf = ByteBuffer.allocateDirect(vb.size * 4).order(ByteOrder.nativeOrder())
            vbuf.asFloatBuffer().put(vb)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vb.size * 4, vbuf, GLES20.GL_STATIC_DRAW)
            val ibuf: ByteBuffer
            if (wide) {
                ibuf = ByteBuffer.allocateDirect(p.indices.size * 4).order(ByteOrder.nativeOrder())
                ibuf.asIntBuffer().put(p.indices)
            } else {
                ibuf = ByteBuffer.allocateDirect(p.indices.size * 2).order(ByteOrder.nativeOrder())
                val sb = ibuf.asShortBuffer()
                for (i in p.indices) sb.put(i.toShort())
            }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibuf.capacity(), ibuf, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            val m = p.material
            val picBytes = m?.textureBytes
            val picFile = m?.textureFile
            val tex = when {
                picBytes != null -> GlTexSpec("model:" + key.hashCode() + ":" + parts.size, "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(picBytes), GlTexSpec.Wrap.REPEAT, false, true)
                picFile != null -> GlTexSpec("model:$picFile", picFile, GlTexSpec.Wrap.REPEAT, false, true)
                else -> null
            }
            tex?.let { specs.putIfAbsent(it.key, it) }
            parts.add(GpuPart(ids[0], ids[1], p.indices.size, wide, m, tex))
        }
        return GpuModel(key, parts)
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the objects of [state] seen by [frame]. [depthBuffer] is false when the phone gave no depth buffer
     * (objects then simply overlap in drawing order). [blendOn] is the layer's own blend state, restored afterwards.
     */
    fun draw(spec: SceneSpec, state: SceneState, frame: SceneFrame, depthBuffer: Boolean, blendOn: Boolean) {
        sync(spec)
        if (!ensureProgram()) return
        val opaque = ArrayList<Surface>()
        val translucent = ArrayList<Surface>()
        val resolved = HashMap<String, GpuModel?>()
        val cam = frame.pos
        for (o in state.objects.values) {
            if (!o.visible) continue
            val gpu = resolved.getOrPut(o.model) { gpuModel(o.model, spec) } ?: continue
            val model = Mat4.trs(o.pos, o.rot, o.scale)
            val normal = Mat4.normalMatrix(model)
            val dx = o.pos[0] - cam[0]
            val dy = o.pos[1] - cam[1]
            val dz = o.pos[2] - cam[2]
            val dist = dx * dx + dy * dy + dz * dz
            val ov = o.material
            for (p in gpu.parts) {
                val m = p.material
                val color = ov.color ?: m?.color ?: floatArrayOf(1f, 1f, 1f, 1f)
                val emissive = ov.emissive ?: m?.emissive ?: floatArrayOf(0f, 0f, 0f)
                val tex = when {
                    ov.texture == null -> p.texture
                    ov.texture!!.isEmpty() -> null
                    else -> texSpec(ov.texture!!)
                }
                val s = Surface(
                    p, model, normal, color, emissive,
                    ov.specular ?: m?.specular ?: 0.2f, ov.shininess ?: m?.shininess ?: 32f, tex,
                    ov.unlit ?: false, ov.doubleSided ?: m?.doubleSided ?: false, ov.alphaCut ?: m?.alphaCut ?: 0f,
                    color[3] < 0.999f || m?.translucent == true, dist
                )
                if (s.translucent) translucent.add(s) else opaque.add(s)
            }
        }
        if (opaque.isEmpty() && translucent.isEmpty()) return
        translucent.sortByDescending { it.distance }

        GLES20.glUseProgram(program)
        GLES20.glUniform1i(loc("uTex"), 1)
        GLES20.glUniformMatrix4fv(loc("uViewProj"), 1, false, frame.viewProj, 0)
        GLES20.glUniform3fv(loc("uCamPos"), 1, frame.pos, 0)
        GLES20.glUniform3fv(loc("uAmbient"), 1, state.ambient, 0)
        val fog = state.fog
        GLES20.glUniform4f(loc("uFog"), fog?.color?.get(0) ?: 0f, fog?.color?.get(1) ?: 0f, fog?.color?.get(2) ?: 0f, fog?.density ?: 0f)
        val n = minOf(state.lights.size, Scene3dShaders.MAX_LIGHTS)
        val lp = FloatArray(Scene3dShaders.MAX_LIGHTS * 4)
        val lc = FloatArray(Scene3dShaders.MAX_LIGHTS * 4)
        for (i in 0 until n) {
            val l = state.lights[i]
            lp[i * 4] = l.vector[0]; lp[i * 4 + 1] = l.vector[1]; lp[i * 4 + 2] = l.vector[2]; lp[i * 4 + 3] = if (l.directional) 0f else 1f
            lc[i * 4] = l.color[0]; lc[i * 4 + 1] = l.color[1]; lc[i * 4 + 2] = l.color[2]; lc[i * 4 + 3] = l.range
        }
        GLES20.glUniform4fv(loc("uLightPos"), Scene3dShaders.MAX_LIGHTS, lp, 0)
        GLES20.glUniform4fv(loc("uLightCol"), Scene3dShaders.MAX_LIGHTS, lc, 0)
        GLES20.glUniform1f(loc("uLightCount"), n.toFloat())

        if (depthBuffer) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        } else {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        var culling = false
        for (s in opaque) culling = drawSurface(s, culling)
        if (translucent.isNotEmpty()) {
            // after the solid objects, far to near, blended over them; they do not write depth, so they cannot hide each other wrongly
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            for (s in translucent) culling = drawSurface(s, culling)
            GLES20.glDepthMask(true)
        }
        if (culling) GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        if (blendOn) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        for (a in 0..2) GLES20.glDisableVertexAttribArray(a)
    }

    /** Draws one surface; returns whether back-face culling is on afterwards. */
    private fun drawSurface(s: Surface, culling: Boolean): Boolean {
        var cull = culling
        if (s.doubleSided) {
            if (cull) { GLES20.glDisable(GLES20.GL_CULL_FACE); cull = false }
        } else if (!cull) {
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            cull = true
        }
        GLES20.glUniformMatrix4fv(loc("uModel"), 1, false, s.model, 0)
        GLES20.glUniformMatrix3fv(loc("uNormalMat"), 1, false, s.normal, 0)
        GLES20.glUniform4fv(loc("uColor"), 1, s.color, 0)
        GLES20.glUniform3fv(loc("uEmissive"), 1, s.emissive, 0)
        GLES20.glUniform2f(loc("uSpec"), s.specular, s.shininess)
        GLES20.glUniform1f(loc("uUnlit"), if (s.unlit) 1f else 0f)
        GLES20.glUniform1f(loc("uAlphaCut"), s.alphaCut)
        if (s.texture != null) {
            GLES20.glUniform1f(loc("uHasTex"), 1f)
            bindTexture(s.texture, loc("uTex"), 1)
        } else {
            GLES20.glUniform1f(loc("uHasTex"), 0f)
        }
        val p = s.part
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, 32, 24)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.count, if (p.wide) GLES20.GL_UNSIGNED_INT else GLES20.GL_UNSIGNED_SHORT, 0)
        return cull
    }
}
