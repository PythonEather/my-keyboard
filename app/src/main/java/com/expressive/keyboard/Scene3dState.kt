package com.expressive.keyboard

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * The scene of the built-in 3D engine: what a mod describes in `scene: { ... }` of a gl element and changes with kb.scene(id, patch).
 * Plain Kotlin with no Android classes, so it is unit-tested on a plain JVM.
 */

class SceneCamera(
    var pos: FloatArray = floatArrayOf(0f, 0f, 0f),
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var roll: Float = 0f,
    var fov: Float = 60f,
    var near: Float = 0.05f,
    var far: Float = 200f,
    /** When set the camera looks at this point and yaw/pitch are ignored. */
    var target: FloatArray? = null
) {
    fun copy() = SceneCamera(pos.copyOf(), yaw, pitch, roll, fov, near, far, target?.copyOf())

    fun apply(m: Map<*, *>) {
        if (m.containsKey("pos")) pos = SceneValues.vec3(m["pos"], pos)
        if (m.containsKey("yaw")) yaw = SceneValues.num(m["yaw"], yaw)
        if (m.containsKey("pitch")) pitch = SceneValues.num(m["pitch"], pitch).coerceIn(-89.9f, 89.9f)
        if (m.containsKey("roll")) roll = SceneValues.num(m["roll"], roll)
        if (m.containsKey("fov")) fov = SceneValues.num(m["fov"], fov).coerceIn(5f, 170f)
        if (m.containsKey("far")) far = SceneValues.num(m["far"], far).coerceIn(1f, 100_000f)
        if (m.containsKey("near")) near = SceneValues.num(m["near"], near).coerceIn(0.001f, 1000f)
        if (near >= far) near = far / 1000f
        if (m.containsKey("target")) target = (m["target"] as? List<*>)?.let { SceneValues.vec3(it, floatArrayOf(0f, 0f, 0f)) }
    }
}

/** Point light (position) or directional light (the direction the light travels, like the sun's rays). */
class SceneLight(val directional: Boolean, val vector: FloatArray, val color: FloatArray, val range: Float) {
    companion object {
        fun parse(v: Any?): SceneLight? {
            val m = v as? Map<*, *> ?: return null
            val c = SceneValues.color(m["color"], floatArrayOf(1f, 1f, 1f, 1f))
            val k = SceneValues.num(m["intensity"], 1f).coerceIn(0f, 100f)
            val col = floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
            return if (m["type"] == "directional" || m["type"] == "sun") {
                val dir = Mat4.normalize(SceneValues.vec3(m["dir"], floatArrayOf(0f, -1f, 0f)))
                // stored as the direction TOWARD the light, which is what the shader wants
                SceneLight(true, floatArrayOf(-dir[0], -dir[1], -dir[2]), col, 0f)
            } else {
                SceneLight(false, SceneValues.vec3(m["pos"], floatArrayOf(0f, 0f, 0f)), col, SceneValues.num(m["range"], 0f).coerceIn(0f, 100_000f))
            }
        }
    }
}

/** What a mod may override on the material of an object; null = keep what the model has. */
class SceneMaterial(
    var color: FloatArray? = null,
    var emissive: FloatArray? = null,
    var specular: Float? = null,
    var shininess: Float? = null,
    /** null: the model's own picture; "": no picture; otherwise a plugin file, `data:` URI or `user:` source. */
    var texture: String? = null,
    var unlit: Boolean? = null,
    var doubleSided: Boolean? = null,
    var alphaCut: Float? = null
) {
    fun copy() = SceneMaterial(color?.copyOf(), emissive?.copyOf(), specular, shininess, texture, unlit, doubleSided, alphaCut)

    fun apply(m: Map<*, *>) {
        if (m.containsKey("color")) color = m["color"]?.let { SceneValues.color(it, floatArrayOf(1f, 1f, 1f, 1f)) }
        if (m.containsKey("emissive")) emissive = m["emissive"]?.let { SceneValues.color(it, floatArrayOf(0f, 0f, 0f, 1f)) }
        if (m.containsKey("specular")) specular = (m["specular"] as? Number)?.toFloat()?.coerceIn(0f, 4f)
        if (m.containsKey("shininess")) shininess = (m["shininess"] as? Number)?.toFloat()?.coerceIn(1f, 512f)
        if (m.containsKey("texture")) texture = m["texture"]?.toString()?.let { if (it.isBlank()) "" else GlTextures.cleanSrc(it) ?: "" }
        if (m.containsKey("unlit")) unlit = m["unlit"] as? Boolean
        if (m.containsKey("doubleSided")) doubleSided = m["doubleSided"] as? Boolean
        if (m.containsKey("alphaCut")) alphaCut = (m["alphaCut"] as? Number)?.toFloat()?.coerceIn(0f, 1f)
    }
}

class SceneObject(
    val id: String,
    var model: String,
    var pos: FloatArray = floatArrayOf(0f, 0f, 0f),
    var rot: FloatArray = floatArrayOf(0f, 0f, 0f),
    var scale: FloatArray = floatArrayOf(1f, 1f, 1f),
    var visible: Boolean = true,
    var material: SceneMaterial = SceneMaterial()
) {
    fun copy() = SceneObject(id, model, pos.copyOf(), rot.copyOf(), scale.copyOf(), visible, material.copy())

    fun apply(m: Map<*, *>) {
        if (m["model"] is String) model = m["model"] as String
        if (m.containsKey("pos")) pos = SceneValues.vec3(m["pos"], pos)
        if (m.containsKey("rot")) rot = SceneValues.vec3(m["rot"], rot)
        if (m.containsKey("scale")) scale = SceneValues.vec3(m["scale"], scale, spread = true)
        if (m.containsKey("visible")) visible = m["visible"] != false
        (m["material"] as? Map<*, *>)?.let { material.apply(it) }
    }
}

class SceneFog(val color: FloatArray, val density: Float)

class SceneModelSpec(val src: String, val rotate: FloatArray?, val center: String, val fit: Float, val scale: Float) {
    /** Cache key: the same file with other options is another model. */
    val key: String get() = src + "|" + (rotate?.joinToString(",") ?: "") + "|" + center + "|" + fit + "|" + scale
}

class SceneState(
    val camera: SceneCamera = SceneCamera(),
    var ambient: FloatArray = floatArrayOf(0.2f, 0.2f, 0.2f),
    var fog: SceneFog? = null,
    var lights: List<SceneLight> = emptyList(),
    val objects: LinkedHashMap<String, SceneObject> = LinkedHashMap()
) {
    fun copy(): SceneState {
        val o = LinkedHashMap<String, SceneObject>()
        for ((k, v) in objects) o[k] = v.copy()
        return SceneState(camera.copy(), ambient.copyOf(), fog, lights, o)
    }

    /** Changes only what the patch names. `objects` is a map id → fields (null removes one), or a list that replaces all of them. */
    fun apply(patch: Map<*, *>) {
        (patch["camera"] as? Map<*, *>)?.let { camera.apply(it) }
        if (patch.containsKey("ambient")) ambient = SceneValues.color(patch["ambient"], ambient).copyOf(3)
        if (patch.containsKey("fog")) fog = SceneParser.fog(patch["fog"])
        if (patch.containsKey("lights")) lights = SceneParser.lights(patch["lights"])
        when (val o = patch["objects"]) {
            is Map<*, *> -> for ((k, v) in o) {
                val id = k?.toString() ?: continue
                if (v == null) { objects.remove(id); continue }
                val fields = v as? Map<*, *> ?: continue
                val existing = objects[id]
                if (existing != null) existing.apply(fields)
                else if (objects.size < SceneParser.MAX_OBJECTS && fields["model"] is String) {
                    objects[id] = SceneObject(id, fields["model"] as String).also { it.apply(fields) }
                }
            }
            is List<*> -> {
                objects.clear()
                SceneParser.objects(o).forEach { objects[it.id] = it }
            }
        }
    }

    /** Every picture the objects name, so the host can make the files known to the GL thread. */
    fun textureSources(): List<String> {
        val out = LinkedHashSet<String>()
        for (o in objects.values) o.material.texture?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.toList()
    }
}

class SceneSpec(val models: Map<String, SceneModelSpec>, val initial: SceneState) {
    /** Files the models need: the model files themselves (the host also makes their folder's pictures and .mtl known). */
    fun modelFiles(): List<String> = models.values.map { it.src }.filter { ModelLoader.isFile(it) }.distinct()
}

object SceneParser {
    const val MAX_OBJECTS = 256
    const val MAX_LIGHTS = 8
    const val MAX_MODELS = 16
    private val NAME = Regex("[A-Za-z0-9_.-]{1,40}")

    fun parse(raw: Any?): SceneSpec? {
        val m = raw as? Map<*, *> ?: return null
        val models = LinkedHashMap<String, SceneModelSpec>()
        (m["models"] as? Map<*, *>)?.forEach { (k, v) ->
            val name = k?.toString().orEmpty()
            if (models.size >= MAX_MODELS || !NAME.matches(name)) return@forEach
            val spec = when (v) {
                is String -> cleanModel(v)?.let { SceneModelSpec(it, null, "none", 0f, 1f) }
                is Map<*, *> -> cleanModel(v["src"]?.toString().orEmpty())?.let { src ->
                    SceneModelSpec(
                        src,
                        (v["rotate"] as? List<*>)?.let { SceneValues.vec3(it, floatArrayOf(0f, 0f, 0f)) },
                        when (v["center"]?.toString()) { "base", "center" -> v["center"].toString(); else -> "none" },
                        SceneValues.num(v["fit"], 0f).coerceIn(0f, 100_000f),
                        SceneValues.num(v["scale"], 1f).coerceIn(0.00001f, 100_000f)
                    )
                }
                else -> null
            }
            if (spec != null) models[name] = spec
        }
        val state = SceneState()
        (m["camera"] as? Map<*, *>)?.let { state.camera.apply(it) }
        if (m.containsKey("ambient")) state.ambient = SceneValues.color(m["ambient"], state.ambient).copyOf(3)
        state.fog = fog(m["fog"])
        state.lights = lights(m["lights"])
        objects(m["objects"] as? List<*>).forEach { state.objects[it.id] = it }
        return SceneSpec(models, state)
    }

    /** A primitive name (`cube`), or a path of a .obj / .glb / .gltf file in the plugin. */
    private fun cleanModel(s: String): String? {
        if (s in Primitives.NAMES) return s
        val p = GlTextures.cleanSrc(s) ?: return null
        if (p.startsWith("data:")) return null
        return p
    }

    fun fog(v: Any?): SceneFog? {
        val m = v as? Map<*, *> ?: return null
        val d = SceneValues.num(m["density"], 0.05f).coerceIn(0f, 10f)
        if (d <= 0f) return null
        val c = SceneValues.color(m["color"], floatArrayOf(0f, 0f, 0f, 1f))
        return SceneFog(floatArrayOf(c[0], c[1], c[2]), d)
    }

    fun lights(v: Any?): List<SceneLight> = (v as? List<*>)?.mapNotNull { SceneLight.parse(it) }?.take(MAX_LIGHTS) ?: emptyList()

    fun objects(list: List<*>?): List<SceneObject> {
        if (list == null) return emptyList()
        val out = ArrayList<SceneObject>()
        val seen = HashSet<String>()
        for ((i, v) in list.withIndex()) {
            if (out.size >= MAX_OBJECTS) break
            val m = v as? Map<*, *> ?: continue
            val model = m["model"] as? String ?: continue
            var id = m["id"]?.toString()?.take(40)?.takeIf { it.isNotBlank() } ?: "o$i"
            if (!seen.add(id)) id = "o$i"
            seen.add(id)
            out.add(SceneObject(id, model).also { it.apply(m) })
        }
        return out
    }
}

/** What the camera sees this frame: matrices and the numbers a background shader needs to match the engine's camera. */
class SceneFrame(
    val view: FloatArray,
    val proj: FloatArray,
    val viewProj: FloatArray,
    val invViewProj: FloatArray,
    val pos: FloatArray,
    val forward: FloatArray,
    val right: FloatArray,
    val up: FloatArray,
    /** tan(fov / 2), aspect (width / height), near, far */
    val params: FloatArray
) {
    /** The value of a uniform a background shader may declare (`uViewProj`, `uCamPos`, ...), or null. */
    fun uniform(name: String): FloatArray? = when (name) {
        "uViewProj" -> viewProj
        "uInvViewProj" -> invViewProj
        "uCamPos" -> pos
        "uCamForward" -> forward
        "uCamRight" -> right
        "uCamUp" -> up
        "uCamParams" -> params
        else -> null
    }

    companion object {
        val UNIFORMS = setOf("uViewProj", "uInvViewProj", "uCamPos", "uCamForward", "uCamRight", "uCamUp", "uCamParams")

        fun of(c: SceneCamera, aspect: Float): SceneFrame {
            val fwd: FloatArray
            val t = c.target
            if (t != null) {
                fwd = Mat4.normalize(floatArrayOf(t[0] - c.pos[0], t[1] - c.pos[1], t[2] - c.pos[2]))
            } else {
                val yaw = Math.toRadians(c.yaw.toDouble())
                val pitch = Math.toRadians(c.pitch.toDouble())
                fwd = floatArrayOf((-sin(yaw) * cos(pitch)).toFloat(), sin(pitch).toFloat(), (-cos(yaw) * cos(pitch)).toFloat())
            }
            var right = Mat4.cross(fwd, floatArrayOf(0f, 1f, 0f))
            if (right[0] * right[0] + right[1] * right[1] + right[2] * right[2] < 1e-8f) right = floatArrayOf(1f, 0f, 0f)
            right = Mat4.normalize(right)
            var up = Mat4.cross(right, fwd)
            if (c.roll != 0f) {
                val r = Math.toRadians(c.roll.toDouble())
                val cr = cos(r).toFloat()
                val sr = sin(r).toFloat()
                val nr = floatArrayOf(right[0] * cr + up[0] * sr, right[1] * cr + up[1] * sr, right[2] * cr + up[2] * sr)
                val nu = floatArrayOf(-right[0] * sr + up[0] * cr, -right[1] * sr + up[1] * cr, -right[2] * sr + up[2] * cr)
                right = nr
                up = nu
            }
            val a = if (aspect > 0.0001f) aspect else 1f
            val view = Mat4.view(c.pos, right, up, fwd)
            val proj = Mat4.perspective(c.fov, a, c.near, c.far)
            val vp = Mat4.multiply(proj, view)
            val inv = Mat4.invert(vp) ?: Mat4.identity()
            val tanHalf = tan(Math.toRadians(c.fov.toDouble() / 2.0)).toFloat()
            return SceneFrame(view, proj, vp, inv, c.pos.copyOf(), fwd, right, up, floatArrayOf(tanHalf, a, c.near, c.far))
        }

        /** The depth-buffer value (0..1) of a point that is [viewZ] units in front of the camera: what a background shader writes to gl_FragDepth. */
        fun depthOf(viewZ: Float, near: Float, far: Float): Float {
            val ndc = (far + near) / (far - near) - 2f * far * near / ((far - near) * viewZ)
            return ndc * 0.5f + 0.5f
        }
    }
}
