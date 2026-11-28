package com.expressive.keyboard

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * The extra button shapes (11.2.36). They are made with RoundedPolygon, the same way MaterialShapes are, so they draw,
 * clip and scale exactly like the ready ones. Each one is a list of corner points plus how round each corner is.
 * A shape that fails to build is drawn as a circle by unitPath(), so a mistake here can never crash the keyboard.
 */
internal object ShapeBank {

    private fun flat(points: List<Pair<Float, Float>>): FloatArray {
        val out = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            out[i * 2] = p.first
            out[i * 2 + 1] = p.second
        }
        return out
    }

    /** [n] corners on a circle; the first one points up. [turn] rotates the whole thing, in degrees. */
    private fun ring(n: Int, turn: Float = 0f): List<Pair<Float, Float>> = List(n) { i ->
        val a = Math.toRadians(-90.0 + turn + 360.0 * i / n)
        cos(a).toFloat() to sin(a).toFloat()
    }

    /** [n] tips with a notch between each pair: stars, flowers, cookies and badges. */
    private fun spiky(n: Int, inner: Float, turn: Float = 0f): List<Pair<Float, Float>> = List(n * 2) { i ->
        val r = if (i % 2 == 0) 1f else inner
        val a = Math.toRadians(-90.0 + turn + 180.0 * i / n)
        (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
    }

    /** A gear: [teeth] flat-topped teeth that are [depth] tall. */
    private fun gearRing(teeth: Int, depth: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val step = 360.0 / teeth
        val low = 1f - depth
        val spots = listOf(-0.30 to low, -0.19 to 1f, 0.19 to 1f, 0.30 to low)
        for (i in 0 until teeth) {
            val c = -90.0 + step * i
            for ((off, r) in spots) {
                val a = Math.toRadians(c + step * off)
                out.add((r * cos(a)).toFloat() to (r * sin(a)).toFloat())
            }
        }
        return out
    }

    private fun turned(points: List<Pair<Float, Float>>, deg: Double): List<Pair<Float, Float>> {
        val a = Math.toRadians(deg)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return points.map { (x, y) -> (x * c - y * s) to (x * s + y * c) }
    }

    /** Every corner rounded the same. */
    private fun soft(points: List<Pair<Float, Float>>, r: Float): RoundedPolygon =
        RoundedPolygon(flat(points), CornerRounding(r))

    /** Tips rounded by [outer], notches by [inner] (the points must alternate tip, notch, tip...). */
    private fun alternating(points: List<Pair<Float, Float>>, outer: Float, inner: Float): RoundedPolygon =
        RoundedPolygon(flat(points), perVertexRounding = List(points.size) { CornerRounding(if (it % 2 == 0) outer else inner) })

    /** One rounding per corner. */
    private fun each(points: List<Pair<Float, Float>>, r: List<Float>): RoundedPolygon =
        RoundedPolygon(flat(points), perVertexRounding = r.map { CornerRounding(it) })

    private fun cookie(n: Int) = alternating(spiky(n, 0.84f), 0.5f, 0.5f)
    private fun flower(n: Int, inner: Float) = alternating(spiky(n, inner), 1f, 0.12f)
    private fun badge(n: Int, inner: Float) = alternating(spiky(n, inner), 0.22f, 0.22f)

    private fun blob(radii: List<Float>): RoundedPolygon {
        val pts = List(radii.size) { i ->
            val a = Math.toRadians(-90.0 + 360.0 * i / radii.size)
            (radii[i] * cos(a)).toFloat() to (radii[i] * sin(a)).toFloat()
        }
        return soft(pts, 0.9f)
    }

    private val plusPoints: List<Pair<Float, Float>> = run {
        val w = 0.34f
        listOf(
            -w to -1f, w to -1f, w to -w, 1f to -w, 1f to w, w to w,
            w to 1f, -w to 1f, -w to w, -1f to w, -1f to -w, -w to -w
        )
    }

    /** The polygon of one of the new presets, scaled to the same 0..1 box as MaterialShapes. */
    fun build(p: ExpressiveShapePreset): RoundedPolygon = when (p) {
        ExpressiveShapePreset.SQUIRCLE -> soft(ring(4, 45f), 0.6f)
        ExpressiveShapePreset.SQUARE_SOFT -> soft(ring(4, 45f), 0.3f)

        ExpressiveShapePreset.COOKIE_5 -> cookie(5)
        ExpressiveShapePreset.COOKIE_8 -> cookie(8)
        ExpressiveShapePreset.COOKIE_10 -> cookie(10)
        ExpressiveShapePreset.COOKIE_14 -> cookie(14)
        ExpressiveShapePreset.COOKIE_16 -> cookie(16)
        ExpressiveShapePreset.COOKIE_20 -> cookie(20)

        ExpressiveShapePreset.FLOWER_5 -> flower(5, 0.6f)
        ExpressiveShapePreset.FLOWER_7 -> flower(7, 0.62f)
        ExpressiveShapePreset.FLOWER_8 -> flower(8, 0.65f)
        ExpressiveShapePreset.FLOWER_10 -> flower(10, 0.72f)
        ExpressiveShapePreset.DAISY_12 -> flower(12, 0.58f)
        ExpressiveShapePreset.CLOVER_3 -> flower(3, 0.36f)
        ExpressiveShapePreset.CLOVER_5 -> flower(5, 0.5f)
        ExpressiveShapePreset.CLOVER_6 -> flower(6, 0.55f)

        ExpressiveShapePreset.STAR_5 -> alternating(spiky(5, 0.42f), 0.1f, 0f)
        ExpressiveShapePreset.STAR_5_SOFT -> alternating(spiky(5, 0.55f), 0.3f, 0.3f)
        ExpressiveShapePreset.STAR_6 -> alternating(spiky(6, 0.5f), 0.08f, 0f)
        ExpressiveShapePreset.STAR_7 -> alternating(spiky(7, 0.5f), 0.08f, 0f)
        ExpressiveShapePreset.STAR_8 -> alternating(spiky(8, 0.55f), 0.08f, 0f)
        ExpressiveShapePreset.STAR_10 -> alternating(spiky(10, 0.6f), 0.06f, 0f)
        ExpressiveShapePreset.STAR_12 -> alternating(spiky(12, 0.65f), 0.05f, 0f)
        ExpressiveShapePreset.STAR_16 -> alternating(spiky(16, 0.78f), 0.04f, 0f)
        ExpressiveShapePreset.SPARKLE_4 -> alternating(spiky(4, 0.28f), 0.15f, 0.35f)
        ExpressiveShapePreset.SPARKLE_6 -> alternating(spiky(6, 0.35f), 0.12f, 0.3f)
        ExpressiveShapePreset.SPARKLE_8 -> alternating(spiky(8, 0.45f), 0.1f, 0.25f)
        ExpressiveShapePreset.SHURIKEN -> alternating(spiky(4, 0.3f), 0.03f, 0f)

        ExpressiveShapePreset.BADGE_8 -> badge(8, 0.86f)
        ExpressiveShapePreset.BADGE_10 -> badge(10, 0.88f)
        ExpressiveShapePreset.BADGE_12 -> badge(12, 0.9f)
        ExpressiveShapePreset.BADGE_16 -> badge(16, 0.92f)
        ExpressiveShapePreset.BADGE_20 -> badge(20, 0.94f)
        ExpressiveShapePreset.GEAR_6 -> soft(gearRing(6, 0.18f), 0.05f)
        ExpressiveShapePreset.GEAR_8 -> soft(gearRing(8, 0.16f), 0.05f)
        ExpressiveShapePreset.GEAR_10 -> soft(gearRing(10, 0.14f), 0.04f)
        ExpressiveShapePreset.GEAR_12 -> soft(gearRing(12, 0.12f), 0.04f)
        ExpressiveShapePreset.SAW_12 -> alternating(spiky(12, 0.8f), 0.02f, 0f)
        ExpressiveShapePreset.COG_SOFT_8 -> soft(gearRing(8, 0.2f), 0.14f)

        ExpressiveShapePreset.TRIANGLE_SOFT -> soft(ring(3), 0.3f)
        ExpressiveShapePreset.TRIANGLE_ROUND -> soft(ring(3), 0.7f)
        ExpressiveShapePreset.TRIANGLE_DOWN -> soft(ring(3, 180f), 0.35f)
        ExpressiveShapePreset.TILT_SQUARE -> soft(ring(4), 0.3f)
        ExpressiveShapePreset.PENTAGON_SOFT -> soft(ring(5), 0.3f)
        ExpressiveShapePreset.HEXAGON -> soft(ring(6), 0.12f)
        ExpressiveShapePreset.HEXAGON_FLAT -> soft(ring(6, 30f), 0.12f)
        ExpressiveShapePreset.HEXAGON_SOFT -> soft(ring(6), 0.45f)
        ExpressiveShapePreset.HEPTAGON -> soft(ring(7), 0.2f)
        ExpressiveShapePreset.OCTAGON -> soft(ring(8, 22.5f), 0.15f)
        ExpressiveShapePreset.OCTAGON_SOFT -> soft(ring(8, 22.5f), 0.4f)
        ExpressiveShapePreset.NONAGON -> soft(ring(9), 0.22f)
        ExpressiveShapePreset.DECAGON -> soft(ring(10), 0.25f)
        ExpressiveShapePreset.DODECAGON -> soft(ring(12), 0.25f)

        ExpressiveShapePreset.PLUS -> soft(plusPoints, 0.12f)
        ExpressiveShapePreset.CROSS -> soft(turned(plusPoints, 45.0), 0.12f)
        ExpressiveShapePreset.DROP -> each(
            listOf(0f to -1f, 0.82f to 0.28f, 0.5f to 0.9f, -0.5f to 0.9f, -0.82f to 0.28f),
            listOf(0.05f, 0.9f, 0.6f, 0.6f, 0.9f)
        )
        ExpressiveShapePreset.LEAF -> each(
            listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f),
            listOf(0.95f, 0f, 0.95f, 0f)
        )
        ExpressiveShapePreset.SHIELD -> each(
            listOf(-0.9f to -0.9f, 0.9f to -0.9f, 0.9f to 0.05f, 0f to 1f, -0.9f to 0.05f),
            listOf(0.25f, 0.25f, 0.5f, 0.35f, 0.5f)
        )
        ExpressiveShapePreset.HOUSE -> soft(listOf(0f to -1f, 1f to -0.2f, 0.85f to 0.95f, -0.85f to 0.95f, -1f to -0.2f), 0.14f)
        ExpressiveShapePreset.ARROWHEAD -> soft(listOf(0f to -1f, 0.95f to 0.9f, 0f to 0.4f, -0.95f to 0.9f), 0.12f)
        ExpressiveShapePreset.KITE -> soft(listOf(0f to -1f, 0.72f to -0.15f, 0f to 1f, -0.72f to -0.15f), 0.2f)
        ExpressiveShapePreset.TRAPEZOID -> soft(listOf(-0.62f to -0.7f, 0.62f to -0.7f, 1f to 0.7f, -1f to 0.7f), 0.18f)

        ExpressiveShapePreset.BLOB_1 -> blob(listOf(1.0f, 0.86f, 0.98f, 0.82f, 1.0f, 0.88f, 0.96f, 0.84f))
        ExpressiveShapePreset.BLOB_2 -> blob(listOf(0.9f, 1.0f, 0.84f, 0.96f, 0.88f, 1.0f, 0.8f, 0.94f))
        ExpressiveShapePreset.BLOB_3 -> blob(listOf(1.0f, 0.78f, 0.92f, 1.0f, 0.8f, 0.9f, 0.96f, 0.82f, 0.98f))
        ExpressiveShapePreset.BLOB_4 -> blob(listOf(0.85f, 0.95f, 1.0f, 0.8f, 0.9f, 1.0f, 0.82f, 0.92f))
        ExpressiveShapePreset.BLOB_5 -> blob(listOf(1.0f, 0.9f, 0.8f, 0.88f, 1.0f, 0.92f, 0.78f, 0.86f, 0.96f, 0.84f))
        ExpressiveShapePreset.BLOB_6 -> blob(listOf(0.92f, 1.0f, 0.76f, 0.9f, 1.0f, 0.8f))
        ExpressiveShapePreset.BLOB_7 -> blob(listOf(1.0f, 0.84f, 1.0f, 0.84f, 0.94f, 0.7f, 0.94f))
        ExpressiveShapePreset.BLOB_8 -> blob(listOf(0.9f, 0.9f, 1.0f, 0.78f, 0.88f, 0.88f, 1.0f, 0.8f, 0.9f))

        // an old shape never reaches this branch (MaterialShapes covers them); a circle is the safe answer
        else -> soft(ring(12), 1f)
    }.normalized()

    /** New shapes a letter fits inside (round or regular). The old ones keep their old rule. */
    val letterFriendly: Set<ExpressiveShapePreset> by lazy {
        val ids = setOf(
            "SQUIRCLE", "SQUARE_SOFT", "COOKIE_5", "COOKIE_8", "COOKIE_10", "COOKIE_14", "COOKIE_16", "COOKIE_20",
            "FLOWER_5", "FLOWER_7", "FLOWER_8", "FLOWER_10", "DAISY_12", "CLOVER_3", "CLOVER_5", "CLOVER_6",
            "STAR_5_SOFT", "BADGE_8", "BADGE_10", "BADGE_12", "BADGE_16", "BADGE_20", "GEAR_6", "GEAR_8", "GEAR_10", "GEAR_12", "COG_SOFT_8",
            "PENTAGON_SOFT", "HEXAGON", "HEXAGON_FLAT", "HEXAGON_SOFT", "HEPTAGON", "OCTAGON", "OCTAGON_SOFT", "NONAGON", "DECAGON", "DODECAGON",
            "BLOB_1", "BLOB_2", "BLOB_3", "BLOB_4", "BLOB_5", "BLOB_6", "BLOB_7", "BLOB_8"
        )
        ExpressiveShapePreset.entries.filter { it.id in ids }.toSet()
    }
}

/** The shapes in groups, for the collapsible picker. A shape nobody lists lands in "More". */
internal object ShapeGroups {
    private fun of(vararg names: String): List<ExpressiveShapePreset> =
        names.mapNotNull { n -> runCatching { ExpressiveShapePreset.valueOf(n) }.getOrNull() }

    val all: List<Pair<String, List<ExpressiveShapePreset>>> by lazy {
        val named = listOf(
            "Soft and round" to of(
                "CIRCLE", "OVAL", "PILL", "SQUARE", "SQUIRCLE", "SQUARE_SOFT", "SLANTED", "ARCH", "SEMICIRCLE",
                "BUN", "PUFFY", "PUFFY_DIAMOND", "GHOSTISH", "CLAMSHELL"
            ),
            "Cookies and suns" to of(
                "COOKIE_4", "COOKIE_5", "COOKIE_6", "COOKIE_7", "COOKIE_8", "COOKIE_9", "COOKIE_10", "COOKIE_12",
                "COOKIE_14", "COOKIE_16", "COOKIE_20", "SUNNY_8", "VERY_SUNNY", "BURST", "SOFT_BURST", "BOOM", "SOFT_BOOM"
            ),
            "Flowers and clovers" to of(
                "FLOWER_5", "FLOWER_6", "FLOWER_7", "FLOWER_8", "FLOWER_10", "DAISY_12",
                "CLOVER_3", "CLOVER_4", "CLOVER_5", "CLOVER_6", "CLOVER_8"
            ),
            "Stars and sparkles" to of(
                "STAR_5", "STAR_5_SOFT", "STAR_6", "STAR_7", "STAR_8", "STAR_10", "STAR_12", "STAR_16",
                "SPARKLE_4", "SPARKLE_6", "SPARKLE_8", "SHURIKEN"
            ),
            "Badges and gears" to of(
                "BADGE_8", "BADGE_10", "BADGE_12", "BADGE_16", "BADGE_20",
                "GEAR_6", "GEAR_8", "GEAR_10", "GEAR_12", "SAW_12", "COG_SOFT_8"
            ),
            "Polygons" to of(
                "TRIANGLE", "TRIANGLE_SOFT", "TRIANGLE_ROUND", "TRIANGLE_DOWN", "TILT_SQUARE", "DIAMOND",
                "PENTAGON", "PENTAGON_SOFT", "HEXAGON", "HEXAGON_FLAT", "HEXAGON_SOFT", "HEPTAGON",
                "OCTAGON", "OCTAGON_SOFT", "NONAGON", "DECAGON", "DODECAGON"
            ),
            "Symbols" to of(
                "HEART", "PLUS", "CROSS", "DROP", "LEAF", "SHIELD", "HOUSE", "ARROWHEAD", "ARROW", "KITE",
                "TRAPEZOID", "FAN", "GEM", "PIXEL_CIRCLE", "PIXEL_TRIANGLE"
            ),
            "Blobs" to of("BLOB_1", "BLOB_2", "BLOB_3", "BLOB_4", "BLOB_5", "BLOB_6", "BLOB_7", "BLOB_8")
        )
        val used = named.flatMap { it.second }.toSet()
        val rest = ExpressiveShapePreset.entries.filter { it !in used }
        if (rest.isEmpty()) named else named + ("More" to rest)
    }
}
