package io.embodit.gonine.ui.board.scene

import io.embodit.gonine.domain.rules.Go
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A plain 3D point / vector in metres (no SDK types, so the layout is unit-testable). */
data class P3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = P3(x * k, y * k, z * k)
    fun length() = sqrt(x * x + y * y + z * z)
    fun lerp(o: P3, t: Float) = P3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/**
 * Every size and position of the board scene (design spatialLayout), in **scene metres**: origin on the volume
 * floor at its centre, +X right, +Y up, +Z toward the player. (The design's coordinates are relative to the
 * volume centre; scene y = design y + 0.18.)
 *
 * ```
 * sceneRoot (floor)
 * ├ tiltRoot  pivot on the board's near top edge (z = +0.23, y = 0.03), turned about +X by the tilt
 * │ └ boardRoot  origin on the board's top centre; row 0 (line "1") near, col 0 ("A") left
 * └ bowls, lids, cards (flat on the floor, not tilted)
 * ```
 */
object BoardLayout {
    const val VOLUME_W = 0.80f
    const val VOLUME_H = 0.36f
    const val VOLUME_D = 0.52f

    const val PITCH = 0.05f
    const val BOARD = 0.46f
    const val BOARD_THICK = 0.03f
    const val MARGIN = (BOARD - 8 * PITCH) / 2f // 0.03
    const val PIVOT_Z = BOARD / 2f // near edge at z = +0.23

    // Stones: a bi-convex lens Ø 3.8 cm, 1.0 cm thick at the centre.
    const val STONE_D = 0.038f
    const val STONE_T = 0.010f
    const val STONE_REST = STONE_T / 2f
    const val GHOST_LIFT = 0.015f

    // Point colliders: 0.92 x pitch, 3 mm thin.
    const val POINT_PAD = PITCH * 0.92f
    const val POINT_PAD_T = 0.003f

    // Bowls Ø 11.5 x 8 cm and lids Ø 12 x 1.5 cm turned over in front of them.
    const val BOWL_R = 0.0575f
    const val BOWL_H = 0.08f
    const val BOWL_X = 0.31f
    const val BOWL_Z = -0.08f
    const val LID_R = 0.06f
    const val LID_H = 0.015f
    const val LID_X = 0.31f
    const val LID_Z = 0.08f

    // Capture flight.
    const val CAPTURE_APEX = 0.08f
    const val CAPTURE_MS = 320
    const val CAPTURE_STAGGER_MS = 40
    const val PLACE_MS = 220

    // Cards: 4 cm tall, leaning back 30°, in front of the board's near edge.
    const val CARD_H = 0.04f
    const val CARD_TILT_DEG = 30f
    const val CARD_BOTTOM_Z = 0.252f
    const val SEAT_CARD_W = 0.14f
    const val COUNT_CARD_W = 0.10f

    // Markers.
    const val LIGHT_D = 0.012f
    const val CUBE = 0.014f
    const val CUBE_T = 0.006f

    val TILTS = listOf(0, 15, 25)

    fun tiltRad(deg: Int) = Math.toRadians(deg.toDouble()).toFloat()

    /** Intersection in boardRoot space (board top = y 0). */
    fun pointLocal(p: Int, lift: Float = 0f): P3 =
        P3(-4 * PITCH + Go.col(p) * PITCH, lift, 4 * PITCH - Go.row(p) * PITCH)

    /** boardRoot space → scene space for [tiltDeg]. */
    fun boardToScene(local: P3, tiltDeg: Int): P3 {
        // boardRoot sits in tiltRoot at (0, 0, -BOARD/2); tiltRoot on the near TOP edge, turned about +X. Pivoting on
        // the near bottom edge (the design's first drawing) pushes the near top edge 1.3 cm toward the player at 25°
        // and into the cards; on the top edge it stays at z = +0.23. The pivot is lowered by 3 cm·(1 − cos a) so the
        // near bottom edge still rests on the floor (critic R8: it floated 1.0 / 2.8 mm at 15 / 25°).
        val t = P3(local.x, local.y, local.z - BOARD / 2f)
        val a = tiltRad(tiltDeg)
        val y = t.y * cos(a) - t.z * sin(a)
        val z = t.y * sin(a) + t.z * cos(a)
        val pivot = tiltPivot(tiltDeg)
        return P3(t.x + pivot.x, y + pivot.y, z + pivot.z)
    }

    /** Inverse of [boardToScene] (the tilt is a rigid motion). */
    fun sceneToBoard(scene: P3, tiltDeg: Int): P3 {
        val pivot = tiltPivot(tiltDeg)
        val y = scene.y - pivot.y
        val z = scene.z - pivot.z
        val a = tiltRad(tiltDeg)
        val ly = y * cos(a) + z * sin(a)
        val lz = -y * sin(a) + z * cos(a)
        return P3(scene.x - pivot.x, ly, lz + BOARD / 2f)
    }

    /** Does the segment a–b pass through the tilted board slab itself (not its axis-aligned bound)? */
    fun segmentHitsBoard(a: P3, b: P3, tiltDeg: Int, steps: Int = 400): Boolean {
        for (i in 0..steps) {
            val q = sceneToBoard(a.lerp(b, i / steps.toFloat()), tiltDeg)
            if (kotlin.math.abs(q.x) <= BOARD / 2 && kotlin.math.abs(q.z) <= BOARD / 2 && q.y <= STONE_T && q.y >= -BOARD_THICK) return true
        }
        return false
    }

    /** Where tiltRoot sits in scene space for [tiltDeg]. */
    fun tiltPivot(tiltDeg: Int) = P3(0f, BOARD_THICK * cos(tiltRad(tiltDeg)), PIVOT_Z)

    fun pointScene(p: Int, tiltDeg: Int, lift: Float = 0f) = boardToScene(pointLocal(p, lift), tiltDeg)

    /** Bowl centres; [right] is the player's side. */
    fun bowlCenter(right: Boolean) = P3(if (right) BOWL_X else -BOWL_X, BOWL_H / 2f, BOWL_Z)

    /** Where the stone for the side to move floats above its bowl. */
    fun bowlMouth(right: Boolean) = P3(if (right) BOWL_X else -BOWL_X, BOWL_H + 0.03f, BOWL_Z)

    fun lidCenter(right: Boolean) = P3(if (right) LID_X else -LID_X, LID_H / 2f, LID_Z)

    /**
     * Slot [n] of a lid's pile: hexagonal layers of 7 (centre + 6 round at 3.9 cm), each layer turned 30° and
     * lifted one stone thickness, 6 layers for 40 stones; beyond 40 they stack on the centre.
     */
    fun lidSlot(right: Boolean, n: Int): P3 {
        val c = lidCenter(right)
        val layer = if (n >= LID_SLOTS) 5 + (n - LID_SLOTS + 1) else n / 7
        val idx = if (n >= LID_SLOTS) 0 else n % 7
        val base = LID_H * 0.75f + STONE_T / 2f
        val y = base + layer * (STONE_T + 0.0002f)
        if (idx == 0) return P3(c.x, y, c.z)
        val ang = Math.toRadians(((idx - 1) * 60 + (layer % 2) * 30).toDouble())
        val r = 0.039f
        return P3(c.x + (r * cos(ang)).toFloat(), y, c.z + (r * sin(ang)).toFloat())
    }

    const val LID_SLOTS = 40

    /** Card centres and their lean (scene space). */
    data class Card(val id: String, val center: P3, val width: Float)

    fun cards(): List<Card> {
        val tilt = Math.toRadians(CARD_TILT_DEG.toDouble())
        // Bottom edge at (y 0, z 0.252); leaning back 30°: the top edge is 4 cm up the slope.
        val cy = (CARD_H / 2 * cos(tilt)).toFloat()
        val cz = (CARD_BOTTOM_Z - CARD_H / 2 * sin(tilt)).toFloat()
        return listOf(
            Card(CARD_SEAT, P3(0f, cy, cz), SEAT_CARD_W),
            Card(CARD_RIGHT, P3(LID_X, cy, cz), COUNT_CARD_W),
            Card(CARD_LEFT, P3(-LID_X, cy, cz), COUNT_CARD_W),
        )
    }

    /** The four corners of a card in scene space. */
    fun cardCorners(card: Card): List<P3> {
        val tilt = Math.toRadians(CARD_TILT_DEG.toDouble())
        val up = P3(0f, cos(tilt).toFloat(), -sin(tilt).toFloat())
        val half = card.width / 2
        val h = CARD_H / 2
        return listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
            P3(card.center.x + sx * half, card.center.y + up.y * h * sy, card.center.z + up.z * h * sy)
        }
    }

    const val CARD_SEAT = "seat"
    const val CARD_RIGHT = "capRight"
    const val CARD_LEFT = "capLeft"

    /** Axis-aligned box (scene space). */
    data class Box(val name: String, val min: P3, val max: P3) {
        fun contains(p: P3) = p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z

        /** Segment a–b intersects the box (slab test). */
        fun hits(a: P3, b: P3): Boolean {
            var t0 = 0f
            var t1 = 1f
            val d = b - a
            for (axis in 0..2) {
                val o = when (axis) { 0 -> a.x; 1 -> a.y; else -> a.z }
                val dd = when (axis) { 0 -> d.x; 1 -> d.y; else -> d.z }
                val lo = when (axis) { 0 -> min.x; 1 -> min.y; else -> min.z }
                val hi = when (axis) { 0 -> max.x; 1 -> max.y; else -> max.z }
                if (kotlin.math.abs(dd) < 1e-9f) { if (o < lo || o > hi) return false; continue }
                var ta = (lo - o) / dd
                var tb = (hi - o) / dd
                if (ta > tb) { val s = ta; ta = tb; tb = s }
                t0 = maxOf(t0, ta)
                t1 = minOf(t1, tb)
                if (t0 > t1) return false
            }
            return true
        }
    }

    /**
     * Boxes of everything solid in the volume for [tiltDeg] (design occlusionCheck.boxes): the tilted board
     * (bounded), a full board of stones and a ghost, both bowls, both lids with 40 stones, and the capture
     * flight path sampled at 8 points.
     */
    fun solidBoxes(tiltDeg: Int): List<Box> {
        val out = ArrayList<Box>()
        // The board slab: bound its 8 corners after the tilt.
        val corners = ArrayList<P3>()
        for (x in listOf(-BOARD / 2, BOARD / 2)) for (y in listOf(-BOARD_THICK, 0f)) for (z in listOf(-BOARD / 2, BOARD / 2)) corners += boardToScene(P3(x, y, z), tiltDeg)
        out += bound("board", corners)
        // Stones on every point + a ghost (lifted): bound them as a lens box each.
        val stonePts = ArrayList<P3>()
        for (p in 0 until Go.SIZE) for (lift in listOf(0f, STONE_T, GHOST_LIFT + STONE_T)) {
            for (dx in listOf(-STONE_D / 2, STONE_D / 2)) for (dz in listOf(-STONE_D / 2, STONE_D / 2)) stonePts += pointScene(p, tiltDeg, lift).let { P3(it.x + dx, it.y, it.z + dz) }
        }
        out += bound("stones", stonePts)
        for (right in listOf(true, false)) {
            val b = bowlCenter(right)
            out += Box("bowl", P3(b.x - BOWL_R, 0f, b.z - BOWL_R), P3(b.x + BOWL_R, BOWL_H + STONE_T * 2, b.z + BOWL_R))
            val l = lidCenter(right)
            val top = lidSlot(right, LID_SLOTS - 1).y + STONE_T / 2
            out += Box("lid", P3(l.x - LID_R, 0f, l.z - LID_R), P3(l.x + LID_R, top, l.z + LID_R))
            // Capture flights into this lid from the nearest and farthest corners.
            for (from in listOf(0, 8, 72, 80)) {
                val path = captureArc(pointScene(from, tiltDeg, STONE_REST), lidSlot(right, 0))
                for (i in 0..8) {
                    val q = path(i / 8f)
                    out += Box("arc", P3(q.x - STONE_D / 2, q.y - STONE_T / 2, q.z - STONE_D / 2), P3(q.x + STONE_D / 2, q.y + STONE_T / 2, q.z + STONE_D / 2))
                }
            }
        }
        return out
    }

    private fun bound(name: String, pts: List<P3>) =
        Box(name, P3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }), P3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }))

    /** The capture arc: a parabola whose apex is the chord's midpoint lifted [CAPTURE_APEX]. */
    fun captureArc(from: P3, to: P3): (Float) -> P3 = { t ->
        val base = from.lerp(to, t)
        P3(base.x, base.y + CAPTURE_APEX * 4f * t * (1f - t), base.z)
    }

    /** The placement arc from a bowl mouth to a point: a low hop (3 cm). */
    fun placeArc(from: P3, to: P3): (Float) -> P3 = { t ->
        val base = from.lerp(to, t)
        P3(base.x, base.y + 0.03f * 4f * t * (1f - t), base.z)
    }

    /**
     * Eye positions of the occlusion check (design occlusionCheck.eye), converted to scene space:
     * sitting E1, low E0, standing E2, each straight on and 30° to either side.
     */
    fun eyes(): List<Pair<String, P3>> {
        val ys = listOf("E1" to 0.27f, "E0" to 0.17f, "E2" to 0.75f)
        val out = ArrayList<Pair<String, P3>>()
        for ((n, y) in ys) {
            out += n to P3(0f, y + 0.18f, 0.80f)
            out += "${n}L" to P3(-0.40f, y + 0.18f, 0.69f)
            out += "${n}R" to P3(0.40f, y + 0.18f, 0.69f)
        }
        return out
    }
}

/** The entity's transform (every entity has one). */
internal fun com.pico.spatial.core.ecs.Entity.transform(): com.pico.spatial.core.ecs.TransformComponent =
    components[com.pico.spatial.core.ecs.TransformComponent::class.java]!!
