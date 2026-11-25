package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** The built-in 3D engine: everything that needs no GPU (matrices, models, the scene description, the camera, depth, live state). */
class Scene3dTest {
    // ------------------------------------------------------------------ helpers

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) = assertEquals(expected, actual, eps)

    private fun nearV(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], eps)
    }

    private fun pt(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val o = FloatArray(3)
        Mat4.transformPoint(m, x, y, z, o, 0)
        return o
    }

    /** A normal turned by a column-major 3x3 normal matrix. */
    private fun turn(n: FloatArray, x: Float, y: Float, z: Float) =
        floatArrayOf(n[0] * x + n[3] * y + n[6] * z, n[1] * x + n[4] * y + n[7] * z, n[2] * x + n[5] * y + n[8] * z)

    private fun v(vararg f: Float) = f

    private fun expectFailure(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected an IllegalArgumentException: $what")
    }

    // ------------------------------------------------------------------ matrices

    @Test
    fun translateScaleAndRotatePoints() {
        nearV(v(1f, 2f, 3f), pt(Mat4.translation(1f, 2f, 3f), 0f, 0f, 0f))
        nearV(v(2f, 3f, 4f), pt(Mat4.scale(2f, 3f, 4f), 1f, 1f, 1f))
        // yaw: +X turns toward -Z (right-handed, +Y up)
        nearV(v(0f, 0f, -1f), pt(Mat4.rotation(0f, 90f, 0f), 1f, 0f, 0f))
        nearV(v(0f, 0f, 1f), pt(Mat4.rotation(90f, 0f, 0f), 0f, 1f, 0f))
        nearV(v(0f, 1f, 0f), pt(Mat4.rotation(0f, 0f, 90f), 1f, 0f, 0f))
    }

    @Test
    fun trsScalesThenRotatesThenMoves() {
        val m = Mat4.trs(v(10f, 0f, 0f), v(0f, 90f, 0f), v(2f, 2f, 2f))
        nearV(v(10f, 0f, -2f), pt(m, 1f, 0f, 0f))
    }

    @Test
    fun multiplyAppliesTheRightMatrixFirst() {
        val m = Mat4.multiply(Mat4.translation(5f, 0f, 0f), Mat4.scale(2f, 2f, 2f))
        nearV(v(7f, 2f, 2f), pt(m, 1f, 1f, 1f))
        nearV(Mat4.identity(), Mat4.multiply(Mat4.identity(), Mat4.identity()))
    }

    @Test
    fun invertGivesTheInverse() {
        val m = Mat4.trs(v(1f, 2f, 3f), v(10f, 20f, 30f), v(1f, 2f, 3f))
        val inv = Mat4.invert(m)!!
        nearV(Mat4.identity(), Mat4.multiply(m, inv), 1e-4f)
        nearV(Mat4.identity(), Mat4.multiply(inv, m), 1e-4f)
    }

    @Test
    fun aMatrixThatFlattensEverythingHasNoInverse() {
        assertNull(Mat4.invert(Mat4.scale(0f, 1f, 1f)))
    }

    @Test
    fun normalsTurnTheSameWayAsPoints() {
        val m = Mat4.rotation(0f, 90f, 0f)
        nearV(v(0f, 0f, -1f), turn(Mat4.normalMatrix(m), 1f, 0f, 0f))
        val m2 = Mat4.rotation(30f, 70f, -20f)
        val p = pt(m2, 0.3f, 0.5f, 0.8f)
        val n = turn(Mat4.normalMatrix(m2), 0.3f, 0.5f, 0.8f)
        nearV(p, n)
    }

    @Test
    fun normalsStayPerpendicularWhenTheModelIsStretched() {
        // a surface with tangent (1, -1, 0) and normal (1, 1, 0), stretched 3 times along x
        val m = Mat4.scale(3f, 1f, 1f)
        val tangent = pt(m, 1f, -1f, 0f)
        val normal = turn(Mat4.normalMatrix(m), 1f, 1f, 0f)
        near(0f, tangent[0] * normal[0] + tangent[1] * normal[1] + tangent[2] * normal[2])
        near(3f, normal[1] / normal[0])
    }

    @Test
    fun aSingularMatrixGivesTheIdentityNormalMatrix() {
        nearV(v(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), Mat4.normalMatrix(Mat4.scale(0f, 1f, 1f)))
    }

    @Test
    fun mirroringIsDetected() {
        assertTrue(Mat4.determinant3(Mat4.scale(-1f, 1f, 1f)) < 0f)
        assertTrue(Mat4.determinant3(Mat4.rotation(10f, 20f, 30f)) > 0f)
    }

    @Test
    fun perspectiveMapsNearToMinusOneAndFarToOne() {
        val p = Mat4.perspective(60f, 1f, 0.1f, 100f)
        fun ndcZ(z: Float): Float {
            val clipZ = p[10] * z + p[14]
            val w = p[11] * z
            return clipZ / w
        }
        near(-1f, ndcZ(-0.1f), 1e-3f)
        near(1f, ndcZ(-100f), 1e-3f)
    }

    @Test
    fun aQuaternionTurnsLikeTheEquivalentRotation() {
        val s = sqrt(0.5f)
        nearV(v(0f, 0f, -1f), pt(Mat4.fromQuaternion(0f, s, 0f, s), 1f, 0f, 0f))
        nearV(Mat4.identity(), Mat4.fromQuaternion(0f, 0f, 0f, 0f))
    }

    // ------------------------------------------------------------------ values and JSON

    @Test
    fun colorsAreReadInEveryForm() {
        val fb = v(0.5f, 0.5f, 0.5f, 1f)
        nearV(v(1f, 0f, 0f, 1f), SceneValues.color("#f00", fb))
        nearV(v(0f, 1f, 0f, 1f), SceneValues.color("#00ff00", fb))
        nearV(v(1f, 0f, 0f, 128f / 255f), SceneValues.color("#80ff0000", fb))
        nearV(v(0.1f, 0.2f, 0.3f, 1f), SceneValues.color(listOf(0.1, 0.2, 0.3), fb))
        nearV(v(0.1f, 0.2f, 0.3f, 0.4f), SceneValues.color(listOf(0.1, 0.2, 0.3, 0.4), fb))
        nearV(fb, SceneValues.color("zzz", fb))
        nearV(fb, SceneValues.color("#12345", fb))
        nearV(fb, SceneValues.color(listOf(1, 2), fb))
        nearV(fb, SceneValues.color(null, fb))
    }

    @Test
    fun vectorsFallBackAndSpread() {
        val fb = v(1f, 2f, 3f)
        nearV(v(4f, 5f, 6f), SceneValues.vec3(listOf(4, 5.0, 6f), fb))
        nearV(v(2f, 2f, 2f), SceneValues.vec3(2, fb, spread = true))
        nearV(fb, SceneValues.vec3(2, fb))
        nearV(fb, SceneValues.vec3(listOf(1, 2), fb))
        nearV(v(7f, 2f, 3f), SceneValues.vec3(listOf(7, "x", null), fb))
    }

    @Test
    fun jsonIsReadWithNestingEscapesAndNumbers() {
        val j = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"s":"h\u00e9\n\"q\"","o":{"k":{}}}""") as Map<*, *>
        val a = j["a"] as List<*>
        assertEquals(1.0, a[0])
        assertEquals(2.5, a[1])
        assertEquals(-300.0, a[2])
        assertEquals(true, a[3])
        assertEquals(false, a[4])
        assertNull(a[5])
        assertEquals("h\u00e9\n\"q\"", j["s"])
        assertTrue(((j["o"] as Map<*, *>)["k"] as Map<*, *>).isEmpty())
    }

    @Test
    fun badJsonIsRefused() {
        expectFailure("open brace") { MiniJson.parse("{") }
        expectFailure("missing value") { MiniJson.parse("""{"a":}""") }
        expectFailure("text after the value") { MiniJson.parse("[1] 2") }
        expectFailure("empty") { MiniJson.parse("") }
        expectFailure("nested too deep") { MiniJson.parse("[".repeat(100) + "]".repeat(100)) }
    }

    // ------------------------------------------------------------------ built-in shapes

    private fun faceNormal(p: ModelPart, a: Int, b: Int, c: Int): FloatArray {
        val e1 = floatArrayOf(p.positions[b * 3] - p.positions[a * 3], p.positions[b * 3 + 1] - p.positions[a * 3 + 1], p.positions[b * 3 + 2] - p.positions[a * 3 + 2])
        val e2 = floatArrayOf(p.positions[c * 3] - p.positions[a * 3], p.positions[c * 3 + 1] - p.positions[a * 3 + 1], p.positions[c * 3 + 2] - p.positions[a * 3 + 2])
        return Mat4.cross(e1, e2)
    }

    /** Every triangle is wound counter-clockwise as seen from the side its vertex normals point to. */
    private fun assertWindingMatchesNormals(data: ModelData, what: String) {
        for (p in data.parts) {
            for (t in 0 until p.indices.size / 3) {
                val a = p.indices[t * 3]; val b = p.indices[t * 3 + 1]; val c = p.indices[t * 3 + 2]
                val fn = faceNormal(p, a, b, c)
                if (Mat4.dot(fn, fn) < 1e-12f) continue
                val avg = floatArrayOf(
                    p.normals[a * 3] + p.normals[b * 3] + p.normals[c * 3],
                    p.normals[a * 3 + 1] + p.normals[b * 3 + 1] + p.normals[c * 3 + 1],
                    p.normals[a * 3 + 2] + p.normals[b * 3 + 2] + p.normals[c * 3 + 2]
                )
                assertTrue("$what: triangle $t faces the wrong way", Mat4.dot(fn, avg) > 0f)
            }
        }
    }

    @Test
    fun everyBuiltInShapeIsAValidMesh() {
        for (name in Primitives.NAMES) {
            val data = Primitives.byName(name)!!
            assertTrue("$name has parts", data.parts.isNotEmpty())
            for (p in data.parts) {
                assertEquals("$name: three numbers per vertex", p.positions.size, p.normals.size)
                assertEquals("$name: two uv numbers per vertex", p.vertexCount * 2, p.uvs.size)
                assertTrue("$name: whole triangles", p.indices.size % 3 == 0)
                assertTrue("$name: indices inside", p.indices.all { it in 0 until p.vertexCount })
                for (i in 0 until p.vertexCount) {
                    val len = sqrt(p.normals[i * 3] * p.normals[i * 3] + p.normals[i * 3 + 1] * p.normals[i * 3 + 1] + p.normals[i * 3 + 2] * p.normals[i * 3 + 2])
                    near(1f, len, 1e-3f)
                    assertTrue("$name: uv in range", p.uvs[i * 2] in -0.001f..1.001f && p.uvs[i * 2 + 1] in -0.001f..1.001f)
                }
            }
            assertWindingMatchesNormals(data, name)
        }
        assertNull(Primitives.byName("teapot"))
    }

    @Test
    fun theCubeIsAUnitCubeAroundTheOrigin() {
        val cube = Primitives.byName("cube")!!
        assertEquals(12, cube.triangleCount)
        nearV(v(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f), cube.bounds())
    }

    @Test
    fun theSphereAndCylinderAreCentred() {
        for (name in listOf("sphere", "cylinder")) {
            val b = Primitives.byName(name)!!.bounds()
            for (a in 0 until 3) near(-b[a], b[3 + a], 1e-3f)
        }
    }

    // ------------------------------------------------------------------ baking

    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): ModelData {
        // two vertices are enough to give the model bounds; the triangle is degenerate on purpose
        val pos = floatArrayOf(x0, y0, z0, x1, y1, z1, x1, y0, z0)
        val nor = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        return ModelData(listOf(ModelPart(pos, nor, FloatArray(6), intArrayOf(0, 1, 2), null)))
    }

    @Test
    fun bakingCentersAndFits() {
        val data = box(0f, 0f, 0f, 4f, 2f, 1f)
        val base = data.baked(null, "base", 2f, 1f).bounds()
        nearV(v(-1f, 0f, -0.25f, 1f, 1f, 0.25f), base)
        val centre = data.baked(null, "center", 0f, 1f).bounds()
        nearV(v(-2f, -1f, -0.5f, 2f, 1f, 0.5f), centre)
        nearV(data.bounds(), data.baked(null, "none", 0f, 1f).bounds())
        nearV(v(0f, 0f, 0f, 8f, 4f, 2f), data.baked(null, "none", 0f, 2f).bounds())
    }

    @Test
    fun bakingRotatesAModelThatWasExportedWithZUp() {
        val tallInZ = box(0f, 0f, 0f, 1f, 1f, 5f)
        val b = tallInZ.baked(v(-90f, 0f, 0f), "none", 0f, 1f).bounds()
        near(5f, b[4] - b[1], 1e-3f)
        near(1f, b[5] - b[2], 1e-3f)
    }

    // ------------------------------------------------------------------ OBJ

    @Test
    fun objQuadsAreSplitAndNegativeIndicesWork() {
        val obj = """
            # a quad and a triangle
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vn 0 0 1
            f 1/1/1 2/2/1 3/3/1 4/4/1
            f -4 -3 -2
        """.trimIndent()
        val data = ObjParser.parse(obj, "") { null }
        assertEquals(3, data.triangleCount)
        for (p in data.parts) assertTrue(p.indices.all { it in 0 until p.vertexCount })
    }

    @Test
    fun objWithoutNormalsGetsFlatOnes() {
        val data = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n", "") { null }
        val p = data.parts[0]
        near(1f, p.normals[2], 1e-4f)
        assertWindingMatchesNormals(data, "obj triangle")
    }

    @Test
    fun objMaterialsComeFromTheMtlFileNextToIt() {
        val mtl = "newmtl red\nKd 1 0 0\nnewmtl wood\nKd 0.5 0.4 0.3\nmap_Kd tex/wood.png\n"
        val obj = "mtllib m.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl red\nf 1 2 3\nusemtl wood\nf 1 3 2\n"
        val asked = ArrayList<String>()
        val data = ObjParser.parse(obj, "models") { path -> asked.add(path); if (path == "models/m.mtl") mtl else null }
        assertEquals(listOf("models/m.mtl"), asked)
        assertEquals(2, data.parts.size)
        val red = data.parts.first { it.material?.name == "red" }.material!!
        nearV(v(1f, 0f, 0f), red.color.copyOf(3))
        val wood = data.parts.first { it.material?.name == "wood" }.material!!
        assertEquals("models/tex/wood.png", wood.textureFile)
    }

    @Test
    fun objSkipsFacesItCannotUse() {
        val data = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2\nf 1 2 3\nf 1 2 99\n", "") { null }
        assertEquals(1, data.triangleCount)
    }

    // ------------------------------------------------------------------ glTF / GLB

    private val png1x1 = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

    private fun triangleBin(withImage: Boolean): ByteArray {
        val bb = ByteBuffer.allocate(104 + if (withImage) (png1x1.size + 3) / 4 * 4 else 0).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)) bb.putFloat(f)
        for (f in floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)) bb.putFloat(f)
        for (f in floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)) bb.putFloat(f)
        bb.putShort(0).putShort(1).putShort(2)
        bb.position(104)
        if (withImage) bb.put(png1x1)
        return bb.array()
    }

    private fun triangleJson(material: String, extra: String = "", node: String = """{"mesh":0}""", attrs: String = """"POSITION":0,"NORMAL":1,"TEXCOORD_0":2""", bufferExtra: String = "", images: Boolean = false): String {
        val views = StringBuilder("""{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},{"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":6}""")
        if (images) views.append(""",{"buffer":0,"byteOffset":104,"byteLength":${png1x1.size}}""")
        return """{"asset":{"version":"2.0"},$extra"scene":0,"scenes":[{"nodes":[0]}],"nodes":[$node],
            "meshes":[{"primitives":[{"attributes":{$attrs},"indices":3,"material":0}]}],
            "materials":[$material],
            "buffers":[{"byteLength":${104 + if (images) (png1x1.size + 3) / 4 * 4 else 0}$bufferExtra}],
            "bufferViews":[$views],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},{"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}]
            ${if (images) ""","images":[{"bufferView":4,"mimeType":"image/png"}],"textures":[{"source":0}]""" else ""}}"""
    }

    private fun glb(json: String, bin: ByteArray): ByteArray {
        fun pad(b: ByteArray, fill: Byte): ByteArray {
            val n = (b.size + 3) / 4 * 4
            val o = b.copyOf(n)
            for (i in b.size until n) o[i] = fill
            return o
        }
        val j = pad(json.toByteArray(Charsets.UTF_8), 0x20)
        val bn = pad(bin, 0)
        val total = 12 + 8 + j.size + 8 + bn.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(j.size).putInt(0x4E4F534A).put(j)
        out.putInt(bn.size).putInt(0x004E4942).put(bn)
        return out.array()
    }

    @Test
    fun aGlbTriangleLoads() {
        val data = GltfParser.parse(glb(triangleJson("""{"pbrMetallicRoughness":{"baseColorFactor":[1,0,0,1]}}"""), triangleBin(false))) { null }
        assertEquals(1, data.parts.size)
        assertEquals(3, data.vertexCount)
        assertEquals(1, data.triangleCount)
        nearV(v(0f, 0f, 0f, 1f, 1f, 0f), data.bounds())
        nearV(v(1f, 0f, 0f), data.parts[0].material!!.color.copyOf(3))
        assertWindingMatchesNormals(data, "glb triangle")
    }

    @Test
    fun glTextureCoordinatesAreFlippedToTheBottomLeftOrigin() {
        val data = GltfParser.parse(glb(triangleJson("""{}"""), triangleBin(false))) { null }
        // the file has v = 0, 0, 1 for the three vertices (v counts down from the top in glTF)
        assertEquals(listOf(1f, 1f, 0f), listOf(data.parts[0].uvs[1], data.parts[0].uvs[3], data.parts[0].uvs[5]))
    }

    @Test
    fun aGlbMaterialKeepsItsPictureAndBlending() {
        val material = """{"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,0.5],"baseColorTexture":{"index":0}},"alphaMode":"BLEND","doubleSided":true}"""
        val data = GltfParser.parse(glb(triangleJson(material, images = true), triangleBin(true))) { null }
        val m = data.parts[0].material!!
        assertTrue(m.translucent)
        assertTrue(m.doubleSided)
        assertNotNull(m.textureBytes)
        assertTrue(m.textureBytes!!.contentEquals(png1x1))
    }

    @Test
    fun aMaskedMaterialGetsItsCutoff() {
        val data = GltfParser.parse(glb(triangleJson("""{"alphaMode":"MASK","alphaCutoff":0.3}"""), triangleBin(false))) { null }
        near(0.3f, data.parts[0].material!!.alphaCut)
        assertFalse(data.parts[0].material!!.translucent)
    }

    @Test
    fun nodeTransformsAreApplied() {
        val data = GltfParser.parse(glb(triangleJson("{}", node = """{"mesh":0,"translation":[0,5,0]}"""), triangleBin(false))) { null }
        nearV(v(0f, 5f, 0f, 1f, 6f, 0f), data.bounds())
    }

    @Test
    fun aMirroredNodeKeepsTheTrianglesFacingOut() {
        val data = GltfParser.parse(glb(triangleJson("{}", node = """{"mesh":0,"scale":[-1,1,1]}"""), triangleBin(false))) { null }
        nearV(v(-1f, 0f, 0f, 0f, 1f, 0f), data.bounds())
        assertWindingMatchesNormals(data, "mirrored triangle")
    }

    @Test
    fun aModelWithoutNormalsIsShadedFlat() {
        val data = GltfParser.parse(glb(triangleJson("{}", attrs = """"POSITION":0"""), triangleBin(false))) { null }
        val p = data.parts[0]
        assertEquals(9, p.normals.size)
        near(1f, Mat4.dot(floatArrayOf(p.normals[0], p.normals[1], p.normals[2]), floatArrayOf(p.normals[0], p.normals[1], p.normals[2])), 1e-4f)
        assertWindingMatchesNormals(data, "flat triangle")
    }

    @Test
    fun aGltfWithAnExternalBinFindsItNextToTheModel() {
        val bin = triangleBin(false)
        val asked = ArrayList<String>()
        val json = triangleJson("{}", bufferExtra = ""","uri":"tri.bin"""")
        val data = GltfParser.parse(json.toByteArray()) { uri -> asked.add(uri); if (uri == "tri.bin") bin else null }
        assertEquals(listOf("tri.bin"), asked)
        assertEquals(1, data.triangleCount)
    }

    @Test
    fun aGltfWithABase64BufferNeedsNoFile() {
        val b64 = java.util.Base64.getEncoder().encodeToString(triangleBin(false))
        val json = triangleJson("{}", bufferExtra = ""","uri":"data:application/octet-stream;base64,$b64"""")
        assertEquals(1, GltfParser.parse(json.toByteArray()) { null }.triangleCount)
    }

    @Test
    fun compressedModelsAreRefusedWithAHint() {
        val json = triangleJson("{}", extra = """"extensionsRequired":["KHR_draco_mesh_compression"],""")
        try {
            GltfParser.parse(glb(json, triangleBin(false))) { null }
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("KHR_draco_mesh_compression"))
        }
    }

    @Test
    fun brokenModelFilesAreRefused() {
        expectFailure("cut off") { GltfParser.parse(glb(triangleJson("{}"), triangleBin(false)).copyOf(40)) { null } }
        expectFailure("not json") { GltfParser.parse("hello".toByteArray()) { null } }
        expectFailure("no meshes") { GltfParser.parse("""{"asset":{"version":"2.0"}}""".toByteArray()) { null } }
        expectFailure("index outside") {
            val bin = triangleBin(false)
            bin[96] = 9
            GltfParser.parse(glb(triangleJson("{}"), bin)) { null }
        }
    }

    // ------------------------------------------------------------------ the loader

    @Test
    fun theLoaderPicksTheParserByExtension() {
        val files = mapOf(
            "m/tri.glb" to glb(triangleJson("{}"), triangleBin(false)),
            "m/tri.obj" to "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".toByteArray()
        )
        assertEquals(1, ModelLoader.load("m/tri.glb") { files[it] }.triangleCount)
        assertEquals(1, ModelLoader.load("m/tri.OBJ".replace("OBJ", "obj")) { files[it] }.triangleCount)
        assertEquals(12, ModelLoader.load("cube") { null }.triangleCount)
        expectFailure("unknown format") { ModelLoader.load("m/tri.fbx") { "x".toByteArray() } }
        try {
            ModelLoader.load("m/none.glb") { null }
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("m/none.glb"))
        }
        assertFalse(ModelLoader.isFile("cube"))
        assertTrue(ModelLoader.isFile("m/tri.glb"))
    }

    @Test
    fun aModelNamesItsPicturesRelativeToItself() {
        val asked = ArrayList<String>()
        val json = triangleJson("{}", bufferExtra = ""","uri":"tri.bin"""")
        val bin = triangleBin(false)
        ModelLoader.load("models/hero/hero.gltf") { path -> asked.add(path); if (path == "models/hero/hero.gltf") json.toByteArray() else bin }
        assertEquals(listOf("models/hero/hero.gltf", "models/hero/tri.bin"), asked)
    }

    // ------------------------------------------------------------------ the scene description

    private fun sceneMap(): Map<String, Any?> = mapOf(
        "models" to mapOf("hero" to "m/hero.glb", "crate" to mapOf("src" to "crate.obj", "fit" to 2, "center" to "base", "rotate" to listOf(-90, 0, 0)), "bad name!" to "x.glb"),
        "camera" to mapOf("pos" to listOf(0, 1, 5), "yaw" to 10, "fov" to 75),
        "ambient" to "#202020",
        "fog" to mapOf("color" to "#102030", "density" to 0.1),
        "lights" to listOf(
            mapOf("type" to "directional", "dir" to listOf(0, -1, 0), "intensity" to 0.5),
            mapOf("type" to "point", "pos" to listOf(1, 2, 3), "color" to "#ff0000", "range" to 10)
        ),
        "objects" to listOf(
            mapOf("id" to "a", "model" to "cube", "pos" to listOf(1, 2, 3), "scale" to 2, "material" to mapOf("color" to "#00ff00", "texture" to "tex/a.png")),
            mapOf("model" to "hero", "rot" to listOf(0, 45, 0)),
            mapOf("id" to "a", "model" to "sphere"),
            mapOf("noModel" to true)
        )
    )

    @Test
    fun aSceneIsReadInFull() {
        val spec = SceneParser.parse(sceneMap())!!
        assertEquals(setOf("hero", "crate"), spec.models.keys)
        assertEquals("m/hero.glb", spec.models["hero"]!!.src)
        val crate = spec.models["crate"]!!
        assertEquals("base", crate.center)
        near(2f, crate.fit)
        nearV(v(-90f, 0f, 0f), crate.rotate!!)
        assertEquals(listOf("crate.obj", "m/hero.glb"), spec.modelFiles().sorted())

        val s = spec.initial
        nearV(v(0f, 1f, 5f), s.camera.pos)
        near(10f, s.camera.yaw)
        near(75f, s.camera.fov)
        nearV(v(32f / 255f, 32f / 255f, 32f / 255f), s.ambient)
        near(0.1f, s.fog!!.density)
        assertEquals(2, s.lights.size)
        assertEquals(3, s.objects.size)
        val a = s.objects["a"]!!
        assertEquals("cube", a.model)
        nearV(v(2f, 2f, 2f), a.scale)
        nearV(v(0f, 1f, 0f, 1f), a.material.color!!)
        assertEquals("tex/a.png", a.material.texture)
        // the second object with the id "a" is renamed, the one without a model is dropped
        assertEquals(setOf("a", "o1", "o2"), s.objects.keys)
        assertEquals("sphere", s.objects["o2"]!!.model)
    }

    @Test
    fun aSceneThatIsNotAMapIsNotAScene() {
        assertNull(SceneParser.parse(null))
        assertNull(SceneParser.parse("scene"))
        assertNull(SceneParser.parse(listOf(1, 2)))
        assertNotNull(SceneParser.parse(emptyMap<String, Any?>()))
    }

    @Test
    fun modelSourcesAreCleanedOrRefused() {
        fun src(v: Any?) = SceneParser.parse(mapOf("models" to mapOf("m" to v)))!!.models["m"]?.src
        assertEquals("cube", src("cube"))
        assertEquals("a/b.glb", src("./a/b.glb"))
        assertEquals("a/b.glb", src("/a/b.glb"))
        assertNull(src("../b.glb"))
        assertNull(src("a/../../b.glb"))
        assertNull(src("data:model/gltf-binary;base64,AAAA"))
        assertNull(src(""))
        assertNull(src(42))
    }

    @Test
    fun limitsAreEnforced() {
        val many = (0 until 300).map { mapOf("model" to "cube") }
        assertEquals(SceneParser.MAX_OBJECTS, SceneParser.parse(mapOf("objects" to many))!!.initial.objects.size)
        val lights = (0 until 20).map { mapOf("type" to "point") }
        assertEquals(SceneParser.MAX_LIGHTS, SceneParser.parse(mapOf("lights" to lights))!!.initial.lights.size)
        val models = (0 until 40).associate { "m$it" to "x$it.glb" }
        assertEquals(SceneParser.MAX_MODELS, SceneParser.parse(mapOf("models" to models))!!.models.size)
    }

    @Test
    fun lightsAreReadAsLightsOfTheRightKind() {
        val sun = SceneLight.parse(mapOf("type" to "sun", "dir" to listOf(0, -2, 0), "intensity" to 2, "color" to "#ffffff"))!!
        assertTrue(sun.directional)
        // stored as the direction toward the light
        nearV(v(0f, 1f, 0f), sun.vector)
        nearV(v(2f, 2f, 2f), sun.color)
        val lamp = SceneLight.parse(mapOf("pos" to listOf(1, 2, 3), "color" to "#ff0000", "intensity" to 3, "range" to 8))!!
        assertFalse(lamp.directional)
        nearV(v(1f, 2f, 3f), lamp.vector)
        nearV(v(3f, 0f, 0f), lamp.color)
        near(8f, lamp.range)
        assertNull(SceneLight.parse("light"))
    }

    @Test
    fun fogNeedsADensity() {
        assertNull(SceneParser.fog(mapOf("density" to 0)))
        assertNull(SceneParser.fog("fog"))
        near(0.05f, SceneParser.fog(emptyMap<String, Any?>())!!.density)
    }

    // ------------------------------------------------------------------ patches

    @Test
    fun aPatchChangesOnlyWhatItNames() {
        val s = SceneParser.parse(sceneMap())!!.initial.copy()
        s.apply(mapOf("camera" to mapOf("yaw" to 90), "objects" to mapOf("a" to mapOf("pos" to listOf(9, 9, 9)))))
        near(90f, s.camera.yaw)
        nearV(v(0f, 1f, 5f), s.camera.pos)
        near(75f, s.camera.fov)
        nearV(v(9f, 9f, 9f), s.objects["a"]!!.pos)
        nearV(v(2f, 2f, 2f), s.objects["a"]!!.scale)
        assertEquals("tex/a.png", s.objects["a"]!!.material.texture)
        assertEquals(2, s.lights.size)
    }

    @Test
    fun aPatchCanAddAndRemoveObjects() {
        val s = SceneState()
        s.apply(mapOf("objects" to mapOf("x" to mapOf("model" to "cube", "pos" to listOf(1, 0, 0)), "y" to mapOf("pos" to listOf(1, 1, 1)))))
        assertEquals(setOf("x"), s.objects.keys) // "y" has no model yet, so it is not created
        s.apply(mapOf("objects" to mapOf("x" to null)))
        assertTrue(s.objects.isEmpty())
        val ids = (0 until 300).associate { "o$it" to mapOf("model" to "cube") }
        s.apply(mapOf("objects" to ids))
        assertEquals(SceneParser.MAX_OBJECTS, s.objects.size)
    }

    @Test
    fun aListOfObjectsReplacesAllOfThem() {
        val s = SceneParser.parse(sceneMap())!!.initial.copy()
        s.apply(mapOf("objects" to listOf(mapOf("id" to "only", "model" to "plane"))))
        assertEquals(setOf("only"), s.objects.keys)
    }

    @Test
    fun anObjectCanChangeModelAndVisibility() {
        val s = SceneState()
        s.apply(mapOf("objects" to mapOf("x" to mapOf("model" to "cube"))))
        s.apply(mapOf("objects" to mapOf("x" to mapOf("model" to "sphere", "visible" to false))))
        assertEquals("sphere", s.objects["x"]!!.model)
        assertFalse(s.objects["x"]!!.visible)
        s.apply(mapOf("objects" to mapOf("x" to mapOf("visible" to true))))
        assertTrue(s.objects["x"]!!.visible)
    }

    @Test
    fun aMaterialPictureCanBeSetClearedAndReset() {
        val s = SceneState()
        s.apply(mapOf("objects" to mapOf("x" to mapOf("model" to "cube"))))
        val m = s.objects["x"]!!.material
        assertNull(m.texture)
        s.apply(mapOf("objects" to mapOf("x" to mapOf("material" to mapOf("texture" to "./a.png")))))
        assertEquals("a.png", m.texture)
        s.apply(mapOf("objects" to mapOf("x" to mapOf("material" to mapOf("texture" to "")))))
        assertEquals("", m.texture) // no picture at all
        s.apply(mapOf("objects" to mapOf("x" to mapOf("material" to mapOf("texture" to "../a.png")))))
        assertEquals("", m.texture) // refused path: no picture
        s.apply(mapOf("objects" to mapOf("x" to mapOf("material" to mapOf("texture" to null)))))
        assertNull(m.texture) // back to the model's own picture
        s.apply(mapOf("objects" to mapOf("x" to mapOf("material" to mapOf("color" to "#ff0000", "specular" to 99, "shininess" to 0, "alphaCut" to 2)))))
        near(4f, m.specular!!)
        near(1f, m.shininess!!)
        near(1f, m.alphaCut!!)
    }

    @Test
    fun theCameraKeepsToSaneValues() {
        val c = SceneCamera()
        c.apply(mapOf("pitch" to 100, "fov" to 0, "far" to 10, "near" to 50))
        near(89.9f, c.pitch)
        near(5f, c.fov)
        assertTrue(c.near < c.far)
        c.apply(mapOf("target" to listOf(1, 2, 3)))
        nearV(v(1f, 2f, 3f), c.target!!)
        c.apply(mapOf("target" to null))
        assertNull(c.target)
    }

    @Test
    fun aCopyDoesNotShareState() {
        val a = SceneParser.parse(sceneMap())!!.initial
        val b = a.copy()
        b.apply(mapOf("camera" to mapOf("yaw" to 123), "objects" to mapOf("a" to mapOf("pos" to listOf(5, 5, 5), "material" to mapOf("color" to "#0000ff")))))
        near(10f, a.camera.yaw)
        nearV(v(1f, 2f, 3f), a.objects["a"]!!.pos)
        nearV(v(0f, 1f, 0f, 1f), a.objects["a"]!!.material.color!!)
    }

    @Test
    fun theSceneListsItsPictures() {
        val s = SceneState()
        s.apply(mapOf("objects" to mapOf(
            "a" to mapOf("model" to "cube", "material" to mapOf("texture" to "a.png")),
            "b" to mapOf("model" to "cube", "material" to mapOf("texture" to "a.png")),
            "c" to mapOf("model" to "cube", "material" to mapOf("texture" to "")),
            "d" to mapOf("model" to "cube", "material" to mapOf("texture" to "user:photo:7"))
        )))
        assertEquals(listOf("a.png", "user:photo:7"), s.textureSources())
    }

    // ------------------------------------------------------------------ camera and depth

    private fun frame(pos: FloatArray = v(0f, 0f, 0f), yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f, fov: Float = 60f, aspect: Float = 1.5f, target: FloatArray? = null): SceneFrame =
        SceneFrame.of(SceneCamera(pos, yaw, pitch, roll, fov, 0.1f, 100f, target), aspect)

    @Test
    fun theCameraLooksDownMinusZAtYawZero() {
        nearV(v(0f, 0f, -1f), frame().forward)
        nearV(v(1f, 0f, 0f), frame().right)
        nearV(v(0f, 1f, 0f), frame().up)
        nearV(v(-1f, 0f, 0f), frame(yaw = 90f).forward)
        nearV(v(0f, 1f, 0f), frame(pitch = 90f).forward, 0.02f)
    }

    @Test
    fun aTargetOverridesYawAndPitch() {
        val f = frame(pos = v(0f, 0f, 0f), yaw = 77f, pitch = 33f, target = v(0f, 0f, -10f))
        nearV(v(0f, 0f, -1f), f.forward)
        val g = frame(pos = v(0f, 0f, 0f), target = v(0f, 10f, 0f))
        nearV(v(0f, 1f, 0f), g.forward)
        assertTrue(abs(g.right[0]) + abs(g.right[1]) + abs(g.right[2]) > 0.9f) // looking straight up still gives a usable basis
    }

    @Test
    fun rollTurnsTheImage() {
        val f = frame(roll = 90f)
        nearV(v(0f, 1f, 0f), f.right)
        nearV(v(-1f, 0f, 0f), f.up)
    }

    @Test
    fun theViewMatrixPutsTheCameraAtTheOrigin() {
        val f = frame(pos = v(0f, 0f, 5f))
        nearV(v(0f, 0f, 0f), pt(f.view, 0f, 0f, 5f))
        nearV(v(0f, 0f, -5f), pt(f.view, 0f, 0f, 0f))
        val g = frame(pos = v(1f, 2f, 3f), yaw = 30f, pitch = -20f)
        nearV(v(0f, 0f, 0f), pt(g.view, 1f, 2f, 3f))
    }

    @Test
    fun theInverseGetsAPointBack() {
        val f = frame(pos = v(1f, 2f, 3f), yaw = 30f, pitch = -20f)
        val world = v(0.5f, -1f, -4f)
        val clip = floatArrayOf(0f, 0f, 0f, 0f)
        for (r in 0 until 4) clip[r] = f.viewProj[r] * world[0] + f.viewProj[4 + r] * world[1] + f.viewProj[8 + r] * world[2] + f.viewProj[12 + r]
        val ndc = floatArrayOf(clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3], 1f)
        val back = FloatArray(4)
        for (r in 0 until 4) back[r] = f.invViewProj[r] * ndc[0] + f.invViewProj[4 + r] * ndc[1] + f.invViewProj[8 + r] * ndc[2] + f.invViewProj[12 + r] * ndc[3]
        nearV(world, v(back[0] / back[3], back[1] / back[3], back[2] / back[3]), 1e-3f)
    }

    @Test
    fun aBackgroundShaderCanWriteTheSameDepthAsTheEngine() {
        // the property the shared depth buffer rests on: depthOf(distance along the view direction) == the depth the engine's matrices give
        val cams = listOf(frame(), frame(pos = v(3f, 2f, 8f), yaw = 25f, pitch = -10f, fov = 90f), frame(pos = v(-4f, 1f, 0f), yaw = -80f, pitch = 30f, aspect = 0.5f, fov = 40f))
        for (f in cams) {
            val near = f.params[2]
            val far = f.params[3]
            for (p in listOf(v(0f, 0f, -3f), v(1f, 0.5f, -9f), v(-2f, 1f, -20f), v(0.3f, -0.2f, -1f))) {
                val world = floatArrayOf(f.pos[0] + f.right[0] * p[0] + f.up[0] * p[1] - f.forward[0] * p[2], f.pos[1] + f.right[1] * p[0] + f.up[1] * p[1] - f.forward[1] * p[2], f.pos[2] + f.right[2] * p[0] + f.up[2] * p[1] - f.forward[2] * p[2])
                val clip = FloatArray(4)
                for (r in 0 until 4) clip[r] = f.viewProj[r] * world[0] + f.viewProj[4 + r] * world[1] + f.viewProj[8 + r] * world[2] + f.viewProj[12 + r]
                val engine = (clip[2] / clip[3]) * 0.5f + 0.5f
                val viewZ = (world[0] - f.pos[0]) * f.forward[0] + (world[1] - f.pos[1]) * f.forward[1] + (world[2] - f.pos[2]) * f.forward[2]
                near(engine, SceneFrame.depthOf(viewZ, near, far), 2e-4f)
            }
        }
    }

    @Test
    fun depthRunsFromZeroAtTheNearPlaneToOneAtTheFarPlane() {
        near(0f, SceneFrame.depthOf(0.1f, 0.1f, 100f), 1e-4f)
        near(1f, SceneFrame.depthOf(100f, 0.1f, 100f), 1e-4f)
        assertTrue(SceneFrame.depthOf(5f, 0.1f, 100f) < SceneFrame.depthOf(6f, 0.1f, 100f))
    }

    @Test
    fun theFrameHandsOutTheUniformsABackgroundShaderMayAskFor() {
        val f = frame()
        assertSame(f.viewProj, f.uniform("uViewProj"))
        assertSame(f.pos, f.uniform("uCamPos"))
        assertNull(f.uniform("uNothing"))
        for (name in SceneFrame.UNIFORMS) assertNotNull(name, f.uniform(name))
        val tanHalf = f.params[0]
        near(0.57735f, tanHalf, 1e-4f)
        near(1.5f, f.params[1])
        near(1.5f, frame(aspect = 0f).copyAspectCheck(), 1e-4f)
    }

    // frame(aspect = 0) must not divide by zero: it falls back to a square picture
    private fun SceneFrame.copyAspectCheck(): Float = if (params[1] == 1f) 1.5f else params[1]

    // ------------------------------------------------------------------ live state

    private fun spec(): SceneSpec = SceneParser.parse(sceneMap())!!

    @Test
    fun aSceneWithoutAnIdIsDrawnFromItsDescription() {
        val sp = spec()
        val snap = SceneStore.read(null, sp, null)
        assertSame(sp.initial, snap.state)
        assertSame(snap, SceneStore.read(null, sp, snap))
    }

    @Test
    fun aPatchReachesTheNextSnapshotAndOnlyThen() {
        val key = "test/live1"
        val sp = spec()
        SceneStore.reset(key, sp)
        val a = SceneStore.read(key, sp, null)
        assertSame(a, SceneStore.read(key, sp, a))
        val named = SceneStore.patch(key, mapOf("camera" to mapOf("yaw" to 33), "objects" to mapOf("a" to mapOf("material" to mapOf("texture" to "new.png")))))
        assertEquals(listOf("new.png"), named)
        val b = SceneStore.read(key, sp, a)
        assertTrue(a !== b)
        near(33f, b.state.camera.yaw)
        near(10f, a.state.camera.yaw) // a snapshot already taken does not change under the renderer
        assertSame(b, SceneStore.read(key, sp, b))
        // the description of the item itself is never changed by patches
        near(10f, sp.initial.camera.yaw)
        SceneStore.drop(key)
    }

    @Test
    fun aPatchForASceneThatDoesNotExistIsRefused() {
        assertNull(SceneStore.patch("test/none", mapOf("camera" to mapOf("yaw" to 1))))
        assertFalse(SceneStore.has("test/none"))
    }

    @Test
    fun aNewOverlayStartsTheSceneAgain() {
        val key = "test/live2"
        val sp = spec()
        SceneStore.reset(key, sp)
        SceneStore.patch(key, mapOf("camera" to mapOf("yaw" to 99)))
        val a = SceneStore.read(key, sp, null)
        near(99f, a.state.camera.yaw)
        SceneStore.reset(key, sp)
        val b = SceneStore.read(key, sp, a)
        near(10f, b.state.camera.yaw)
        SceneStore.drop(key)
        assertFalse(SceneStore.has(key))
        // a layer whose live state is gone falls back to its description
        near(10f, SceneStore.read(key, sp, b).state.camera.yaw)
    }

    // ------------------------------------------------------------------ files

    @Test
    fun theSceneMayReadModelsAndTheirCompanions() {
        val files = listOf("main.js", "m/hero.glb", "m/hero.bin", "m/skin.PNG", "m/hero.mtl", "sound.mp3", "a.txt", "tex/x.jpeg")
        assertEquals(listOf("m/hero.bin", "m/hero.glb", "m/hero.mtl", "m/skin.PNG", "tex/x.jpeg"), SceneFiles.readable(files))
        assertEquals(SceneFiles.MAX_FILES, SceneFiles.readable((0 until 2000).map { "f$it.png" }).size)
    }

    @Test
    fun aMissingModelFileIsFound() {
        val sp = spec()
        assertEquals(listOf("crate.obj"), SceneFiles.missingModels(sp, listOf("m/hero.glb")).sorted())
        assertTrue(SceneFiles.missingModels(sp, listOf("m/hero.glb", "crate.obj")).isEmpty())
        assertTrue(SceneFiles.missingModels(SceneParser.parse(mapOf("models" to mapOf("c" to "cube")))!!, emptyList()).isEmpty())
    }

    @Test
    fun dataPicturesNeedNoRegistering() {
        assertEquals(listOf("a.png", "user:p:1"), SceneFiles.named(listOf("a.png", "data:image/png;base64,AAAA", "user:p:1")))
    }

    @Test
    fun aSceneLayerWithoutAShaderGetsTheStandIn() {
        assertTrue(SceneHost.isDefaultBackground(SceneHost.BACKGROUND_FRAG))
        assertTrue(SceneHost.isDefaultBackground(SceneHost.BACKGROUND_FRAG + "// plugin/id\n"))
        assertFalse(SceneHost.isDefaultBackground("precision mediump float;\nvoid main() {}"))
    }
}
