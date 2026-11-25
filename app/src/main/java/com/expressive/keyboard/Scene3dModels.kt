package com.expressive.keyboard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Models for the built-in 3D engine: Wavefront OBJ (+ MTL), glTF 2.0 (.glb and .gltf) and a few built-in shapes.
 * No Android classes here, so everything is unit-tested on a plain JVM. A loaded model is a list of parts, one per material,
 * with positions, normals and texture coordinates (0,0 = bottom-left of the picture, like the GL layer's textures).
 * Not supported yet: skeletal animation and morph targets (a skinned model is drawn in its rest pose), Draco/meshopt compression.
 */

/** Surface of a model part. Colours are not premultiplied. */
class ModelMaterial(
    val name: String,
    val color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    val specular: Float = 0.2f,
    val shininess: Float = 32f,
    /** A picture file inside the plugin (OBJ/MTL `map_Kd`), already joined with the model's folder. */
    val textureFile: String? = null,
    /** A picture stored inside the model (glTF), as the bytes of a PNG/JPEG. */
    val textureBytes: ByteArray? = null,
    val doubleSided: Boolean = false,
    /** Pixels with alpha below this are not drawn (glTF alphaMode MASK). 0 = off. */
    val alphaCut: Float = 0f,
    /** Drawn after the opaque objects with blending (glTF alphaMode BLEND, or a colour with alpha below 1). */
    val translucent: Boolean = false
)

class ModelPart(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
    val material: ModelMaterial?
) {
    val vertexCount: Int get() = positions.size / 3

    /** position(3), normal(3), uv(2) per vertex: the layout the engine's vertex buffer uses. */
    fun interleaved(): FloatArray {
        val n = vertexCount
        val o = FloatArray(n * 8)
        for (i in 0 until n) {
            o[i * 8] = positions[i * 3]; o[i * 8 + 1] = positions[i * 3 + 1]; o[i * 8 + 2] = positions[i * 3 + 2]
            o[i * 8 + 3] = normals[i * 3]; o[i * 8 + 4] = normals[i * 3 + 1]; o[i * 8 + 5] = normals[i * 3 + 2]
            o[i * 8 + 6] = uvs[i * 2]; o[i * 8 + 7] = uvs[i * 2 + 1]
        }
        return o
    }
}

class ModelData(val parts: List<ModelPart>) {
    val triangleCount: Int get() = parts.sumOf { it.indices.size / 3 }
    val vertexCount: Int get() = parts.sumOf { it.vertexCount }

    /** minX, minY, minZ, maxX, maxY, maxZ */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in parts) for (i in 0 until p.vertexCount) {
            for (a in 0 until 3) {
                val v = p.positions[i * 3 + a]
                if (v < b[a]) b[a] = v
                if (v > b[3 + a]) b[3 + a] = v
            }
        }
        if (b[0] > b[3]) return FloatArray(6)
        return b
    }

    /**
     * The model after the options of `models: { name: { rotate, center, fit, scale } }`:
     * rotate (degrees, fixes a model exported with Z up), then center ("base": feet on y = 0 and centred in x/z, "center", or "none"),
     * then fit (the longest side becomes this many units) and scale.
     */
    fun baked(rotate: FloatArray?, center: String, fit: Float, scale: Float): ModelData {
        var data = this
        if (rotate != null && (rotate[0] != 0f || rotate[1] != 0f || rotate[2] != 0f)) {
            val m = Mat4.rotation(rotate[0], rotate[1], rotate[2])
            data = data.transformed(m, Mat4.normalMatrix(m))
        }
        val b = data.bounds()
        val size = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
        var s = scale
        if (fit > 0f && size > 1e-9f) s *= fit / size
        var tx = 0f
        var ty = 0f
        var tz = 0f
        when (center) {
            "base" -> { tx = -(b[0] + b[3]) / 2f; ty = -b[1]; tz = -(b[2] + b[5]) / 2f }
            "center" -> { tx = -(b[0] + b[3]) / 2f; ty = -(b[1] + b[4]) / 2f; tz = -(b[2] + b[5]) / 2f }
        }
        if (tx == 0f && ty == 0f && tz == 0f && s == 1f) return data
        val m = Mat4.multiply(Mat4.scale(s, s, s), Mat4.translation(tx, ty, tz))
        return data.transformed(m, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    }

    private fun transformed(m: FloatArray, nm: FloatArray): ModelData = ModelData(parts.map { p ->
        val pos = FloatArray(p.positions.size)
        val nor = FloatArray(p.normals.size)
        for (i in 0 until p.vertexCount) {
            Mat4.transformPoint(m, p.positions[i * 3], p.positions[i * 3 + 1], p.positions[i * 3 + 2], pos, i * 3)
            applyNormal(nm, p.normals, i * 3, nor)
        }
        ModelPart(pos, nor, p.uvs, p.indices, p.material)
    })

    companion object {
        const val MAX_VERTICES = 600_000

        /** Turns the normal at [at] in [src] with [nm] and writes it, normalised, to [out] at [outAt]. */
        fun applyNormal(nm: FloatArray, src: FloatArray, at: Int, out: FloatArray, outAt: Int = at) {
            val x = src[at]; val y = src[at + 1]; val z = src[at + 2]
            var nx = nm[0] * x + nm[3] * y + nm[6] * z
            var ny = nm[1] * x + nm[4] * y + nm[7] * z
            var nz = nm[2] * x + nm[5] * y + nm[8] * z
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) { nx /= len; ny /= len; nz /= len }
            out[outAt] = nx; out[outAt + 1] = ny; out[outAt + 2] = nz
        }
    }
}

internal class FloatList(cap: Int = 64) {
    var a = FloatArray(cap)
    var size = 0
    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(max(16, a.size * 2))
        a[size++] = v
    }
    fun add3(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
    fun toArray(): FloatArray = a.copyOf(size)
}

internal class IntList(cap: Int = 64) {
    var a = IntArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(max(16, a.size * 2))
        a[size++] = v
    }
    fun toArray(): IntArray = a.copyOf(size)
}

/** Collects the vertices and triangles of one part. */
internal class PartBuilder(val material: ModelMaterial?) {
    val pos = FloatList()
    val nrm = FloatList()
    val uv = FloatList()
    val idx = IntList()
    val shared = HashMap<Long, Int>()
    val vertexCount: Int get() = pos.size / 3

    fun vertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
        require(vertexCount < ModelData.MAX_VERTICES) { "the model has more than ${ModelData.MAX_VERTICES} vertices" }
        pos.add3(px, py, pz); nrm.add3(nx, ny, nz); uv.add(u); uv.add(v)
        return vertexCount - 1
    }

    fun tri(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }

    fun build(): ModelPart = ModelPart(pos.toArray(), nrm.toArray(), uv.toArray(), idx.toArray(), material)
}

internal fun faceNormal(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float): FloatArray {
    val ux = bx - ax; val uy = by - ay; val uz = bz - az
    val vx = cx - ax; val vy = cy - ay; val vz = cz - az
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    return if (len < 1e-12f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(nx / len, ny / len, nz / len)
}

// ====================================================================== built-in shapes

object Primitives {
    val NAMES = setOf("cube", "plane", "sphere", "cylinder", "quad")

    fun byName(name: String): ModelData? = when (name) {
        "cube" -> cube()
        "plane" -> plane()
        "quad" -> quad()
        "sphere" -> sphere(24, 16)
        "cylinder" -> cylinder(24)
        else -> null
    }

    private fun one(b: PartBuilder) = ModelData(listOf(b.build()))

    /** 1 x 1 x 1, centred on the origin. */
    fun cube(): ModelData {
        val b = PartBuilder(null)
        val h = 0.5f
        // each face: four corners counter-clockwise seen from outside, then its normal
        val faces = arrayOf(
            floatArrayOf(-h, -h, h, h, -h, h, h, h, h, -h, h, h, 0f, 0f, 1f),
            floatArrayOf(h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h, 0f, 0f, -1f),
            floatArrayOf(h, -h, h, h, -h, -h, h, h, -h, h, h, h, 1f, 0f, 0f),
            floatArrayOf(-h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -1f, 0f, 0f),
            floatArrayOf(-h, h, h, h, h, h, h, h, -h, -h, h, -h, 0f, 1f, 0f),
            floatArrayOf(-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h, 0f, -1f, 0f)
        )
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        for (f in faces) {
            val base = b.vertexCount
            for (k in 0 until 4) b.vertex(f[k * 3], f[k * 3 + 1], f[k * 3 + 2], f[12], f[13], f[14], uvs[k * 2], uvs[k * 2 + 1])
            b.tri(base, base + 1, base + 2)
            b.tri(base, base + 2, base + 3)
        }
        return one(b)
    }

    /** 1 x 1 in the x/z plane, facing +Y (a floor). */
    fun plane(): ModelData {
        val b = PartBuilder(null)
        val h = 0.5f
        b.vertex(-h, 0f, h, 0f, 1f, 0f, 0f, 0f)
        b.vertex(h, 0f, h, 0f, 1f, 0f, 1f, 0f)
        b.vertex(h, 0f, -h, 0f, 1f, 0f, 1f, 1f)
        b.vertex(-h, 0f, -h, 0f, 1f, 0f, 0f, 1f)
        b.tri(0, 1, 2); b.tri(0, 2, 3)
        return one(b)
    }

    /** 1 x 1 in the x/y plane, facing +Z (a sign or a flat sprite). */
    fun quad(): ModelData {
        val b = PartBuilder(null)
        val h = 0.5f
        b.vertex(-h, -h, 0f, 0f, 0f, 1f, 0f, 0f)
        b.vertex(h, -h, 0f, 0f, 0f, 1f, 1f, 0f)
        b.vertex(h, h, 0f, 0f, 0f, 1f, 1f, 1f)
        b.vertex(-h, h, 0f, 0f, 0f, 1f, 0f, 1f)
        b.tri(0, 1, 2); b.tri(0, 2, 3)
        return one(b)
    }

    /** Diameter 1, centred on the origin. */
    fun sphere(segments: Int, rings: Int): ModelData {
        val b = PartBuilder(null)
        for (i in 0..rings) {
            val v = i.toFloat() / rings
            val phi = (v * PI).toFloat()
            for (j in 0..segments) {
                val u = j.toFloat() / segments
                val theta = (u * 2.0 * PI).toFloat()
                val nx = sin(phi) * cos(theta)
                val ny = cos(phi)
                val nz = sin(phi) * sin(theta)
                b.vertex(nx * 0.5f, ny * 0.5f, nz * 0.5f, nx, ny, nz, u, 1f - v)
            }
        }
        val stride = segments + 1
        for (i in 0 until rings) for (j in 0 until segments) {
            val a = i * stride + j
            val c = a + stride
            b.tri(a, a + 1, c)
            b.tri(a + 1, c + 1, c)
        }
        return one(b)
    }

    /** Diameter 1, height 1, centred on the origin, closed with two caps. */
    fun cylinder(segments: Int): ModelData {
        val b = PartBuilder(null)
        val h = 0.5f
        for (j in 0..segments) {
            val u = j.toFloat() / segments
            val a = (u * 2.0 * PI).toFloat()
            val nx = cos(a)
            val nz = sin(a)
            b.vertex(nx * 0.5f, -h, nz * 0.5f, nx, 0f, nz, u, 0f)
            b.vertex(nx * 0.5f, h, nz * 0.5f, nx, 0f, nz, u, 1f)
        }
        for (j in 0 until segments) {
            val a = j * 2
            b.tri(a + 2, a, a + 1)
            b.tri(a + 2, a + 1, a + 3)
        }
        for (cap in 0..1) {
            val y = if (cap == 0) h else -h
            val ny = if (cap == 0) 1f else -1f
            val centre = b.vertex(0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f)
            val first = b.vertexCount
            for (j in 0..segments) {
                val a = (j.toFloat() / segments * 2.0 * PI).toFloat()
                b.vertex(cos(a) * 0.5f, y, sin(a) * 0.5f, 0f, ny, 0f, 0.5f + cos(a) * 0.5f, 0.5f + sin(a) * 0.5f)
            }
            for (j in 0 until segments) {
                if (cap == 0) b.tri(centre, first + j + 1, first + j) else b.tri(centre, first + j, first + j + 1)
            }
        }
        return one(b)
    }
}

// ====================================================================== Wavefront OBJ

object ObjParser {
    /** [dir] is the folder of the .obj inside the plugin ("" or "models"); [readText] reads another text file of the plugin (the .mtl). */
    fun parse(text: String, dir: String, readText: (String) -> String?): ModelData {
        val pos = FloatList()
        val tex = FloatList()
        val nor = FloatList()
        val parts = LinkedHashMap<String, PartBuilder>()
        var materials: Map<String, ModelMaterial> = emptyMap()
        var current: PartBuilder? = null
        var skipped = 0

        fun builderFor(name: String): PartBuilder = parts.getOrPut(name) { PartBuilder(materials[name]) }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val t = line.split(' ', '\t').filter { it.isNotEmpty() }
            when (t[0]) {
                "v" -> if (t.size >= 4) pos.add3(t[1].toFloatOrNull() ?: 0f, t[2].toFloatOrNull() ?: 0f, t[3].toFloatOrNull() ?: 0f)
                "vt" -> if (t.size >= 3) { tex.add(t[1].toFloatOrNull() ?: 0f); tex.add(t[2].toFloatOrNull() ?: 0f) }
                "vn" -> if (t.size >= 4) nor.add3(t[1].toFloatOrNull() ?: 0f, t[2].toFloatOrNull() ?: 0f, t[3].toFloatOrNull() ?: 0f)
                "mtllib" -> if (t.size > 1) {
                    val file = joinPath(dir, line.substring(t[0].length).trim())
                    readText(file)?.let { materials = materials + parseMtl(it, dir) }
                }
                "usemtl" -> current = builderFor(if (t.size > 1) t[1] else "")
                "f" -> {
                    if (t.size < 4) { skipped++; continue }
                    val b = current ?: builderFor("").also { current = it }
                    val n = t.size - 1
                    val vi = IntArray(n)
                    val ti = IntArray(n)
                    val ni = IntArray(n)
                    var ok = true
                    var hasNormals = true
                    for (k in 0 until n) {
                        val parts3 = t[k + 1].split('/')
                        vi[k] = resolve(parts3[0], pos.size / 3)
                        ti[k] = if (parts3.size > 1) resolve(parts3[1], tex.size / 2) else -1
                        ni[k] = if (parts3.size > 2) resolve(parts3[2], nor.size / 3) else -1
                        if (vi[k] < 0) ok = false
                        if (ni[k] < 0) hasNormals = false
                    }
                    if (!ok) { skipped++; continue }
                    for (k in 1 until n - 1) {
                        val corner = intArrayOf(0, k, k + 1)
                        if (hasNormals) {
                            val ids = IntArray(3)
                            for (c in 0 until 3) {
                                val q = corner[c]
                                val key = (vi[q].toLong() shl 42) or ((ti[q] + 1).toLong() shl 21) or (ni[q] + 1).toLong()
                                ids[c] = b.shared.getOrPut(key) {
                                    b.vertex(
                                        pos.a[vi[q] * 3], pos.a[vi[q] * 3 + 1], pos.a[vi[q] * 3 + 2],
                                        nor.a[ni[q] * 3], nor.a[ni[q] * 3 + 1], nor.a[ni[q] * 3 + 2],
                                        if (ti[q] >= 0) tex.a[ti[q] * 2] else 0f, if (ti[q] >= 0) tex.a[ti[q] * 2 + 1] else 0f
                                    )
                                }
                            }
                            b.tri(ids[0], ids[1], ids[2])
                        } else {
                            // no normals in the file: a flat normal per triangle, so its vertices are not shared
                            val p0 = vi[corner[0]] * 3; val p1 = vi[corner[1]] * 3; val p2 = vi[corner[2]] * 3
                            val fn = faceNormal(pos.a[p0], pos.a[p0 + 1], pos.a[p0 + 2], pos.a[p1], pos.a[p1 + 1], pos.a[p1 + 2], pos.a[p2], pos.a[p2 + 1], pos.a[p2 + 2])
                            val ids = IntArray(3)
                            for (c in 0 until 3) {
                                val q = corner[c]
                                ids[c] = b.vertex(
                                    pos.a[vi[q] * 3], pos.a[vi[q] * 3 + 1], pos.a[vi[q] * 3 + 2], fn[0], fn[1], fn[2],
                                    if (ti[q] >= 0) tex.a[ti[q] * 2] else 0f, if (ti[q] >= 0) tex.a[ti[q] * 2 + 1] else 0f
                                )
                            }
                            b.tri(ids[0], ids[1], ids[2])
                        }
                    }
                }
            }
        }
        val built = parts.values.filter { it.idx.size > 0 }.map { it.build() }
        require(built.isNotEmpty()) { "the OBJ file has no faces" + if (skipped > 0) " ($skipped unusable)" else "" }
        return ModelData(built)
    }

    private fun resolve(s: String, count: Int): Int {
        if (s.isEmpty()) return -1
        val n = s.toIntOrNull() ?: return -1
        val i = if (n > 0) n - 1 else if (n < 0) count + n else -1
        return if (i in 0 until count) i else -1
    }

    fun parseMtl(text: String, dir: String): Map<String, ModelMaterial> {
        val out = LinkedHashMap<String, ModelMaterial>()
        var name: String? = null
        var kd = floatArrayOf(1f, 1f, 1f)
        var ke = floatArrayOf(0f, 0f, 0f)
        var spec = 0.2f
        var ns = 32f
        var alpha = 1f
        var map: String? = null

        fun flush() {
            val n = name ?: return
            val shine = ns.coerceIn(1f, 256f)
            out[n] = ModelMaterial(
                n, floatArrayOf(kd[0], kd[1], kd[2], alpha.coerceIn(0f, 1f)), ke, spec.coerceIn(0f, 1f), shine,
                textureFile = map?.let { joinPath(dir, it) }, translucent = alpha < 0.999f
            )
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val t = line.split(' ', '\t').filter { it.isNotEmpty() }
            fun rgb(): FloatArray? = if (t.size >= 4) floatArrayOf(t[1].toFloatOrNull() ?: 0f, t[2].toFloatOrNull() ?: 0f, t[3].toFloatOrNull() ?: 0f) else null
            when (t[0]) {
                "newmtl" -> {
                    flush()
                    name = line.substringAfter("newmtl").trim()
                    kd = floatArrayOf(1f, 1f, 1f); ke = floatArrayOf(0f, 0f, 0f); spec = 0.2f; ns = 32f; alpha = 1f; map = null
                }
                "Kd" -> rgb()?.let { kd = it }
                "Ke" -> rgb()?.let { ke = it }
                "Ks" -> rgb()?.let { spec = (it[0] + it[1] + it[2]) / 3f }
                "Ns" -> t.getOrNull(1)?.toFloatOrNull()?.let { ns = it }
                "d" -> t.getOrNull(1)?.toFloatOrNull()?.let { alpha = it }
                "Tr" -> t.getOrNull(1)?.toFloatOrNull()?.let { alpha = 1f - it }
                "map_Kd" -> if (t.size > 1) map = t.last().replace('\\', '/')
            }
        }
        flush()
        return out
    }
}

/** `dir` + `/` + `name`, with `\`, `./` and `x/../` tidied. A name that climbs out of the plugin folder is kept as it is (the file is then simply not found). */
fun joinPath(dir: String, name: String): String {
    val parts = ArrayList<String>()
    for (p in (dir.replace('\\', '/') + "/" + name.replace('\\', '/')).split('/')) {
        when {
            p.isEmpty() || p == "." -> {}
            p == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else parts.add(p)
            else -> parts.add(p)
        }
    }
    return parts.joinToString("/")
}

// ====================================================================== glTF 2.0

object GltfParser {
    private const val MAX_IMAGE = 16 * 1024 * 1024

    /** [bytes]: a .glb file, or the text of a .gltf. [resolve] reads a file next to the model (an external .bin or picture) by its uri. */
    fun parse(bytes: ByteArray, resolve: (String) -> ByteArray?): ModelData {
        val json: Map<*, *>
        var glbBin: ByteArray? = null
        if (bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() && bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getInt(4) == 2) { "only glTF 2.0 is supported" }
            var at = 12
            var jsonText: String? = null
            while (at + 8 <= bytes.size) {
                val len = bb.getInt(at)
                val type = bb.getInt(at + 4)
                require(len >= 0 && at + 8 + len <= bytes.size) { "the GLB file is cut off" }
                if (type == 0x4E4F534A && jsonText == null) jsonText = String(bytes, at + 8, len, Charsets.UTF_8)
                else if (type == 0x004E4942 && glbBin == null) glbBin = bytes.copyOfRange(at + 8, at + 8 + len)
                at += 8 + ((len + 3) and 3.inv())
            }
            json = MiniJson.parse(jsonText ?: throw IllegalArgumentException("the GLB file has no JSON part")) as? Map<*, *> ?: throw IllegalArgumentException("the GLB JSON is not an object")
        } else {
            json = MiniJson.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *> ?: throw IllegalArgumentException("not a glTF file")
        }
        val required = (json["extensionsRequired"] as? List<*>)?.map { it.toString() } ?: emptyList()
        for (r in required) {
            require(r != "KHR_draco_mesh_compression" && r != "EXT_meshopt_compression") { "the model is compressed with $r: export it without compression" }
        }
        return Reader(json, glbBin, resolve).read()
    }

    private class Accessor(val data: FloatArray, val comps: Int, val count: Int)

    private class Reader(val j: Map<*, *>, val glbBin: ByteArray?, val resolve: (String) -> ByteArray?) {
        val buffers = ArrayList<ByteArray?>()
        val builders = LinkedHashMap<Int, PartBuilder>()
        val materials = HashMap<Int, ModelMaterial>()

        fun list(key: String): List<*> = j[key] as? List<*> ?: emptyList<Any?>()
        fun map(v: Any?): Map<*, *> = v as? Map<*, *> ?: emptyMap<Any?, Any?>()
        fun int(v: Any?, d: Int = 0): Int = (v as? Number)?.toInt() ?: d

        fun buffer(i: Int): ByteArray {
            while (buffers.size <= i) buffers.add(null)
            buffers[i]?.let { return it }
            val b = map(list("buffers").getOrNull(i))
            val uri = b["uri"] as? String
            val data = when {
                uri == null -> glbBin ?: throw IllegalArgumentException("glTF: buffer $i has no data")
                uri.startsWith("data:") -> decodeDataUri(uri)
                else -> resolve(uri) ?: throw IllegalArgumentException("glTF: the file \"$uri\" is missing from the plugin")
            }
            buffers[i] = data
            return data
        }

        fun view(i: Int): Triple<ByteArray, Int, Int> {
            val v = map(list("bufferViews").getOrNull(i))
            require(v.isNotEmpty()) { "glTF: bufferView $i does not exist" }
            return Triple(buffer(int(v["buffer"])), int(v["byteOffset"]), int(v["byteLength"]))
        }

        fun accessor(i: Int, asIndex: Boolean = false): Accessor {
            val a = map(list("accessors").getOrNull(i))
            require(a.isNotEmpty()) { "glTF: accessor $i does not exist" }
            val count = int(a["count"])
            val comps = when (a["type"]) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> throw IllegalArgumentException("glTF: accessor type ${a["type"]}") }
            val ctype = int(a["componentType"])
            val csize = when (ctype) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw IllegalArgumentException("glTF: component type $ctype") }
            val normalized = a["normalized"] == true
            val out = FloatArray(count * comps)
            val bvIndex = a["bufferView"]
            if (bvIndex == null) return Accessor(out, comps, count)
            val bv = map(list("bufferViews").getOrNull(int(bvIndex)))
            val (data, viewOffset, viewLen) = view(int(bvIndex))
            val stride = int(bv["byteStride"]).takeIf { it > 0 } ?: (comps * csize)
            val start = viewOffset + int(a["byteOffset"])
            val last = start + stride * (count - 1) + comps * csize
            require(viewOffset + viewLen <= data.size) { "glTF: bufferView $bvIndex is larger than its buffer" }
            require(count == 0 || (start >= 0 && last <= data.size)) { "glTF: accessor $i reads outside its buffer" }
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (e in 0 until count) for (c in 0 until comps) {
                val at = start + e * stride + c * csize
                out[e * comps + c] = when (ctype) {
                    5126 -> bb.getFloat(at)
                    5121 -> (bb.get(at).toInt() and 255).let { if (normalized && !asIndex) it / 255f else it.toFloat() }
                    5120 -> bb.get(at).toFloat().let { if (normalized) max(it / 127f, -1f) else it }
                    5123 -> (bb.getShort(at).toInt() and 0xFFFF).let { if (normalized && !asIndex) it / 65535f else it.toFloat() }
                    5122 -> bb.getShort(at).toFloat().let { if (normalized) max(it / 32767f, -1f) else it }
                    else -> (bb.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
                }
            }
            return Accessor(out, comps, count)
        }

        fun indices(i: Int): IntArray {
            val a = map(list("accessors").getOrNull(i))
            val count = int(a["count"])
            val ct = int(a["componentType"])
            if (ct == 5125) {
                // exact 32-bit values: a float would lose the low bits of big indices
                val bvIndex = a["bufferView"] ?: return IntArray(count)
                val bv = map(list("bufferViews").getOrNull(int(bvIndex)))
                val (data, off, _) = view(int(bvIndex))
                val stride = int(bv["byteStride"]).takeIf { it > 0 } ?: 4
                val start = off + int(a["byteOffset"])
                require(count == 0 || start + stride * (count - 1) + 4 <= data.size) { "glTF: indices read outside their buffer" }
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                return IntArray(count) { bb.getInt(start + it * stride) }
            }
            val acc = accessor(i, true)
            return IntArray(acc.count) { acc.data[it].toInt() }
        }

        fun image(index: Int): ByteArray? {
            val img = map(list("images").getOrNull(index))
            val uri = img["uri"] as? String
            val bytes = when {
                uri != null && uri.startsWith("data:") -> decodeDataUri(uri)
                uri != null -> resolve(uri)
                img["bufferView"] != null -> {
                    val (data, off, len) = view(int(img["bufferView"]))
                    require(off + len <= data.size) { "glTF: an image reads outside its buffer" }
                    data.copyOfRange(off, off + len)
                }
                else -> null
            } ?: return null
            return if (bytes.size > MAX_IMAGE) null else bytes
        }

        fun material(index: Int): ModelMaterial? {
            if (index < 0) return null
            materials[index]?.let { return it }
            val m = map(list("materials").getOrNull(index))
            if (m.isEmpty()) return null
            val pbr = map(m["pbrMetallicRoughness"])
            val base = (pbr["baseColorFactor"] as? List<*>)?.let { SceneValues.color(it, floatArrayOf(1f, 1f, 1f, 1f)) } ?: floatArrayOf(1f, 1f, 1f, 1f)
            val metallic = SceneValues.num(pbr["metallicFactor"], 1f)
            val rough = SceneValues.num(pbr["roughnessFactor"], 1f).coerceIn(0f, 1f)
            val emissive = (m["emissiveFactor"] as? List<*>)?.let { SceneValues.vec3(it, floatArrayOf(0f, 0f, 0f)) } ?: floatArrayOf(0f, 0f, 0f)
            var texBytes: ByteArray? = null
            val ti = map(pbr["baseColorTexture"])["index"]
            if (ti != null) {
                val tex = map(list("textures").getOrNull(int(ti)))
                if (tex["source"] != null) texBytes = image(int(tex["source"]))
            }
            val alphaMode = m["alphaMode"] as? String ?: "OPAQUE"
            val mat = ModelMaterial(
                (m["name"] as? String) ?: "material$index", base, emissive,
                specular = ((1f - rough) * 0.6f + metallic * 0.2f).coerceIn(0f, 1f),
                shininess = 4f + (1f - rough).pow(2) * 124f,
                textureBytes = texBytes,
                doubleSided = m["doubleSided"] == true,
                alphaCut = if (alphaMode == "MASK") SceneValues.num(m["alphaCutoff"], 0.5f) else 0f,
                translucent = alphaMode == "BLEND" || base[3] < 0.999f
            )
            materials[index] = mat
            return mat
        }

        fun nodeMatrix(n: Map<*, *>): FloatArray {
            (n["matrix"] as? List<*>)?.takeIf { it.size == 16 }?.let { l -> return FloatArray(16) { SceneValues.num(l[it], if (it % 5 == 0) 1f else 0f) } }
            val t = SceneValues.vec3(n["translation"], floatArrayOf(0f, 0f, 0f))
            val s = SceneValues.vec3(n["scale"], floatArrayOf(1f, 1f, 1f))
            val q = (n["rotation"] as? List<*>)?.takeIf { it.size == 4 }
            val r = if (q != null) Mat4.fromQuaternion(SceneValues.num(q[0], 0f), SceneValues.num(q[1], 0f), SceneValues.num(q[2], 0f), SceneValues.num(q[3], 1f)) else Mat4.identity()
            return Mat4.multiply(Mat4.translation(t[0], t[1], t[2]), Mat4.multiply(r, Mat4.scale(s[0], s[1], s[2])))
        }

        fun visit(node: Int, parent: FloatArray, depth: Int) {
            require(depth < 64) { "glTF: the node tree is too deep" }
            val n = map(list("nodes").getOrNull(node))
            if (n.isEmpty()) return
            val world = Mat4.multiply(parent, nodeMatrix(n))
            n["mesh"]?.let { addMesh(int(it), world) }
            (n["children"] as? List<*>)?.forEach { visit(int(it, -1), world, depth + 1) }
        }

        fun addMesh(index: Int, world: FloatArray) {
            val mesh = map(list("meshes").getOrNull(index))
            val nm = Mat4.normalMatrix(world)
            val mirrored = Mat4.determinant3(world) < 0f
            for (p in (mesh["primitives"] as? List<*>) ?: emptyList<Any?>()) {
                val prim = map(p)
                if (int(prim["mode"], 4) != 4) continue // only triangle lists
                val attrs = map(prim["attributes"])
                val posIndex = attrs["POSITION"] ?: continue
                val pos = accessor(int(posIndex))
                if (pos.comps != 3) continue
                val nor = attrs["NORMAL"]?.let { accessor(int(it)) }?.takeIf { it.comps == 3 && it.count == pos.count }
                val uv = attrs["TEXCOORD_0"]?.let { accessor(int(it)) }?.takeIf { it.comps == 2 && it.count == pos.count }
                val idx = prim["indices"]?.let { indices(int(it)) } ?: IntArray(pos.count) { it }
                require(idx.all { it in 0 until pos.count }) { "glTF: an index points outside the vertices" }
                val key = int(prim["material"], -1)
                val b = builders.getOrPut(key) { PartBuilder(material(key)) }
                val tris = idx.size / 3
                val tmp = FloatArray(3)
                if (nor != null) {
                    val base = b.vertexCount
                    val nx = FloatArray(3)
                    for (v in 0 until pos.count) {
                        Mat4.transformPoint(world, pos.data[v * 3], pos.data[v * 3 + 1], pos.data[v * 3 + 2], tmp, 0)
                        ModelData.applyNormal(nm, nor.data, v * 3, nx, 0)
                        b.vertex(tmp[0], tmp[1], tmp[2], nx[0], nx[1], nx[2], uv?.data?.get(v * 2) ?: 0f, if (uv != null) 1f - uv.data[v * 2 + 1] else 0f)
                    }
                    for (t in 0 until tris) {
                        if (mirrored) b.tri(base + idx[t * 3], base + idx[t * 3 + 2], base + idx[t * 3 + 1])
                        else b.tri(base + idx[t * 3], base + idx[t * 3 + 1], base + idx[t * 3 + 2])
                    }
                } else {
                    // no normals: flat shading, so every triangle gets its own vertices
                    val wp = FloatArray(pos.count * 3)
                    for (v in 0 until pos.count) Mat4.transformPoint(world, pos.data[v * 3], pos.data[v * 3 + 1], pos.data[v * 3 + 2], wp, v * 3)
                    for (t in 0 until tris) {
                        var i0 = idx[t * 3]; var i1 = idx[t * 3 + 1]; var i2 = idx[t * 3 + 2]
                        if (mirrored) { val x = i1; i1 = i2; i2 = x }
                        val fn = faceNormal(wp[i0 * 3], wp[i0 * 3 + 1], wp[i0 * 3 + 2], wp[i1 * 3], wp[i1 * 3 + 1], wp[i1 * 3 + 2], wp[i2 * 3], wp[i2 * 3 + 1], wp[i2 * 3 + 2])
                        val ids = IntArray(3)
                        val src = intArrayOf(i0, i1, i2)
                        for (c in 0 until 3) {
                            val v = src[c]
                            ids[c] = b.vertex(wp[v * 3], wp[v * 3 + 1], wp[v * 3 + 2], fn[0], fn[1], fn[2], uv?.data?.get(v * 2) ?: 0f, if (uv != null) 1f - uv.data[v * 2 + 1] else 0f)
                        }
                        b.tri(ids[0], ids[1], ids[2])
                    }
                }
            }
        }

        fun read(): ModelData {
            val scenes = list("scenes")
            val roots = ArrayList<Int>()
            if (scenes.isNotEmpty()) {
                val scene = map(scenes.getOrNull(int(j["scene"])) ?: scenes[0])
                (scene["nodes"] as? List<*>)?.forEach { roots.add(int(it, -1)) }
            } else {
                val children = HashSet<Int>()
                list("nodes").forEach { n -> (map(n)["children"] as? List<*>)?.forEach { children.add(int(it, -1)) } }
                for (i in list("nodes").indices) if (i !in children) roots.add(i)
            }
            for (r in roots) visit(r, Mat4.identity(), 0)
            val parts = builders.values.filter { it.idx.size > 0 }.map { it.build() }
            require(parts.isNotEmpty()) { "the glTF file has no triangle meshes" }
            return ModelData(parts)
        }
    }

    private fun decodeDataUri(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        require(comma > 0 && uri.substring(0, comma).contains(";base64")) { "glTF: a data: uri must be base64" }
        return java.util.Base64.getMimeDecoder().decode(uri.substring(comma + 1))
    }
}

/** Reads any supported model file by its name: .obj, .glb, .gltf. */
object ModelLoader {
    fun isFile(src: String): Boolean = !Primitives.NAMES.contains(src)

    fun load(src: String, readBytes: (String) -> ByteArray?): ModelData {
        if (src in Primitives.NAMES) return Primitives.byName(src)!!
        val bytes = readBytes(src) ?: throw IllegalArgumentException("no file \"$src\" in the plugin")
        require(bytes.size <= GlTextures.MAX_FILE_BYTES) { "file \"$src\" is larger than 16 MB" }
        val dir = src.substringBeforeLast('/', "")
        return when (src.substringAfterLast('.', "").lowercase()) {
            "obj" -> ObjParser.parse(String(bytes, Charsets.UTF_8), dir) { path -> readBytes(path)?.let { String(it, Charsets.UTF_8) } }
            "glb", "gltf" -> GltfParser.parse(bytes) { uri -> readBytes(joinPath(dir, uri.replace("%20", " "))) }
            else -> throw IllegalArgumentException("\"$src\" is not a .obj, .glb or .gltf model")
        }
    }
}
