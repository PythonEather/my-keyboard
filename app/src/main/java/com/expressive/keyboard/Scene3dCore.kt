package com.expressive.keyboard

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Core of the built-in 3D engine (scene3d): matrices, a small JSON reader for glTF, colours.
 * No Android classes in this file, so it is unit-tested on a plain JVM.
 *
 * Conventions (the same as OpenGL and glTF): right-handed, +Y is up, the camera looks down -Z at yaw 0,
 * matrices are column-major FloatArrays of 16 (3x3 normal matrices: 9), angles are degrees.
 */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** a * b: b is applied first. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()
        val o = FloatArray(16)
        o[0] = f / aspect
        o[5] = f
        o[10] = (far + near) / (near - far)
        o[11] = -1f
        o[14] = 2f * far * near / (near - far)
        return o
    }

    /** View matrix from an orthonormal camera basis: [right], [up] and [forward] (the direction the camera looks). */
    fun view(eye: FloatArray, right: FloatArray, up: FloatArray, forward: FloatArray): FloatArray {
        val o = identity()
        o[0] = right[0]; o[4] = right[1]; o[8] = right[2]
        o[1] = up[0]; o[5] = up[1]; o[9] = up[2]
        o[2] = -forward[0]; o[6] = -forward[1]; o[10] = -forward[2]
        o[12] = -dot(right, eye)
        o[13] = -dot(up, eye)
        o[14] = dot(forward, eye)
        return o
    }

    fun translation(x: Float, y: Float, z: Float): FloatArray = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun scale(x: Float, y: Float, z: Float): FloatArray = identity().also { it[0] = x; it[5] = y; it[10] = z }

    /** Rotation by Euler angles in degrees: R = Ry(y) * Rx(x) * Rz(z) (yaw, pitch, roll). */
    fun rotation(xDeg: Float, yDeg: Float, zDeg: Float): FloatArray {
        val rx = rotX(xDeg)
        val ry = rotY(yDeg)
        val rz = rotZ(zDeg)
        return multiply(ry, multiply(rx, rz))
    }

    private fun rotX(d: Float): FloatArray {
        val c = cos(Math.toRadians(d.toDouble())).toFloat()
        val s = sin(Math.toRadians(d.toDouble())).toFloat()
        return identity().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c }
    }

    private fun rotY(d: Float): FloatArray {
        val c = cos(Math.toRadians(d.toDouble())).toFloat()
        val s = sin(Math.toRadians(d.toDouble())).toFloat()
        return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    }

    private fun rotZ(d: Float): FloatArray {
        val c = cos(Math.toRadians(d.toDouble())).toFloat()
        val s = sin(Math.toRadians(d.toDouble())).toFloat()
        return identity().also { it[0] = c; it[1] = s; it[4] = -s; it[5] = c }
    }

    /** T * R * S for an object: position, rotation (degrees) and scale. */
    fun trs(pos: FloatArray, rotDeg: FloatArray, scale: FloatArray): FloatArray =
        multiply(translation(pos[0], pos[1], pos[2]), multiply(rotation(rotDeg[0], rotDeg[1], rotDeg[2]), scale(scale[0], scale[1], scale[2])))

    /** glTF rotation quaternion (x, y, z, w) as a matrix. */
    fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-8f } ?: return identity()
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        val o = identity()
        o[0] = 1f - 2f * (qy * qy + qz * qz); o[1] = 2f * (qx * qy + qz * qw); o[2] = 2f * (qx * qz - qy * qw)
        o[4] = 2f * (qx * qy - qz * qw); o[5] = 1f - 2f * (qx * qx + qz * qz); o[6] = 2f * (qy * qz + qx * qw)
        o[8] = 2f * (qx * qz + qy * qw); o[9] = 2f * (qy * qz - qx * qw); o[10] = 1f - 2f * (qx * qx + qy * qy)
        return o
    }

    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12f) return null
        val d = 1f / det
        for (i in 0 until 16) inv[i] *= d
        return inv
    }

    /** Inverse transpose of the upper-left 3x3 of [m] (column-major 9 numbers): the matrix that turns normals into world space. */
    fun normalMatrix(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val c00 = e * i - f * h
        val c01 = f * g - d * i
        val c02 = d * h - e * g
        val det = a * c00 + b * c01 + c * c02
        if (abs(det) < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val inv = 1f / det
        // cofactor K[r][c] of A; the normal matrix N = K / det (the inverse transpose of A), element (row r, column c) at index c * 3 + r
        val k00 = c00
        val k01 = c01
        val k02 = c02
        val k10 = -(b * i - c * h)
        val k11 = a * i - c * g
        val k12 = -(a * h - b * g)
        val k20 = b * f - c * e
        val k21 = -(a * f - c * d)
        val k22 = a * e - b * d
        val n = FloatArray(9)
        n[0] = k00 * inv; n[1] = k10 * inv; n[2] = k20 * inv
        n[3] = k01 * inv; n[4] = k11 * inv; n[5] = k21 * inv
        n[6] = k02 * inv; n[7] = k12 * inv; n[8] = k22 * inv
        return n
    }

    /** Determinant of the upper-left 3x3: negative means the matrix mirrors, so triangles must be wound the other way. */
    fun determinant3(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        out[at] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[at + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[at + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    fun normalize(a: FloatArray): FloatArray {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return if (n < 1e-12f) floatArrayOf(0f, 0f, -1f) else floatArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }
}

/** A JSON reader for glTF files: Map, List, Double, String, Boolean or null. Throws IllegalArgumentException on bad input. */
object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.skip()
        require(p.i == text.length) { "JSON: extra text after the value" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON: nested too deep" }
            skip()
            require(i < s.length) { "JSON: unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("JSON: unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "JSON: bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw IllegalArgumentException("JSON: bad number at $st")
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "JSON: unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "JSON: bad escape" }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            '/', '\\', '"' -> sb.append(e)
                            'u' -> {
                                require(i + 4 <= s.length) { "JSON: bad \\u escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("JSON: bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value(depth + 1))
                skip()
                require(i < s.length) { "JSON: unterminated array" }
                val c = s[i++]
                if (c == ']') return out
                require(c == ',') { "JSON: expected , or ] at ${i - 1}" }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skip()
                require(i < s.length && s[i] == '"') { "JSON: expected a key at $i" }
                val k = str()
                skip()
                require(i < s.length && s[i] == ':') { "JSON: expected : at $i" }
                i++
                out[k] = value(depth + 1)
                skip()
                require(i < s.length) { "JSON: unterminated object" }
                val c = s[i++]
                if (c == '}') return out
                require(c == ',') { "JSON: expected , or } at ${i - 1}" }
            }
        }
    }
}

/** Colours and small value readers shared by the scene parser. */
object SceneValues {
    /** `"#RGB"`, `"#RRGGBB"`, `"#AARRGGBB"` (alpha first, like everywhere else in plugins) or `[r, g, b, a?]` in 0..1 → r, g, b, a (not premultiplied). */
    fun color(v: Any?, fallback: FloatArray): FloatArray {
        when (v) {
            is String -> {
                val h = v.trim().removePrefix("#")
                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return fallback
                return when (h.length) {
                    3 -> floatArrayOf(hex(h[0], h[0]), hex(h[1], h[1]), hex(h[2], h[2]), 1f)
                    6 -> floatArrayOf(hex(h[0], h[1]), hex(h[2], h[3]), hex(h[4], h[5]), 1f)
                    8 -> floatArrayOf(hex(h[2], h[3]), hex(h[4], h[5]), hex(h[6], h[7]), hex(h[0], h[1]))
                    else -> fallback
                }
            }
            is List<*> -> {
                if (v.size < 3) return fallback
                return floatArrayOf(num(v[0], 0f), num(v[1], 0f), num(v[2], 0f), if (v.size > 3) num(v[3], 1f) else 1f)
            }
        }
        return fallback
    }

    private fun hex(a: Char, b: Char): Float = (Character.digit(a, 16) * 16 + Character.digit(b, 16)) / 255f

    fun num(v: Any?, fallback: Float): Float = (v as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: fallback

    /** `[x, y, z]` → 3 floats; a single number fills all three when [spread] is true. */
    fun vec3(v: Any?, fallback: FloatArray, spread: Boolean = false): FloatArray {
        if (v is Number && spread) { val n = num(v, fallback[0]); return floatArrayOf(n, n, n) }
        if (v is List<*> && v.size >= 3) return floatArrayOf(num(v[0], fallback[0]), num(v[1], fallback[1]), num(v[2], fallback[2]))
        return fallback
    }
}
