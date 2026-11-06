package io.embodit.gonine.ui.board.scene

import io.embodit.gonine.domain.rules.Go
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Geometry checks and the D35 / D20 occlusion test of the design (spatialLayout.occlusionCheck.unitTest). */
class BoardLayoutTest {
    @Test fun points_sit_three_cm_inside_the_board_edge() {
        for (p in 0 until Go.SIZE) {
            val q = BoardLayout.pointLocal(p)
            assertTrue(abs(q.x) <= BoardLayout.BOARD / 2 - 0.03f + 1e-5f)
            assertTrue(abs(q.z) <= BoardLayout.BOARD / 2 - 0.03f + 1e-5f)
        }
        assertEquals(0.20f, BoardLayout.pointLocal(Go.point(0, 0)).z, 1e-5f) // line 1 is near
        assertEquals(-0.20f, BoardLayout.pointLocal(Go.point(0, 0)).x, 1e-5f) // column A is left
    }

    @Test fun point_pads_do_not_overlap() {
        assertTrue(BoardLayout.POINT_PAD < BoardLayout.PITCH)
        assertTrue(BoardLayout.STONE_D < BoardLayout.PITCH)
        assertTrue(BoardLayout.PITCH >= 0.045f) // D17
    }

    @Test fun lid_slots_do_not_intersect_and_stay_in_the_lid() {
        for (right in listOf(true, false)) {
            val slots = (0 until BoardLayout.LID_SLOTS).map { BoardLayout.lidSlot(right, it) }
            val c = BoardLayout.lidCenter(right)
            for (s in slots) {
                val r = kotlin.math.hypot((s.x - c.x).toDouble(), (s.z - c.z).toDouble())
                assertTrue("slot outside lid r=$r", r + BoardLayout.STONE_D / 2 <= BoardLayout.LID_R + 1e-4)
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val a = slots[i]; val b = slots[j]
                val flat = kotlin.math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
                val apart = abs(a.y - b.y) >= BoardLayout.STONE_T - 1e-5f || flat >= BoardLayout.STONE_D - 1e-4
                assertTrue("slots $i and $j intersect", apart)
            }
        }
    }

    @Test fun everything_stays_inside_the_volume_for_every_tilt() {
        for (tilt in BoardLayout.TILTS) for (b in BoardLayout.solidBoxes(tilt)) {
            assertTrue("${b.name} tilt $tilt min ${b.min}", b.min.y >= -1e-4f && abs(b.min.x) <= BoardLayout.VOLUME_W / 2 && b.min.z >= -BoardLayout.VOLUME_D / 2)
            assertTrue("${b.name} tilt $tilt max ${b.max}", b.max.y <= BoardLayout.VOLUME_H && b.max.x <= BoardLayout.VOLUME_W / 2 && b.max.z <= BoardLayout.VOLUME_D / 2)
        }
    }

    @Test fun scene_to_board_inverts_board_to_scene() {
        for (tilt in BoardLayout.TILTS) for (p in listOf(0, 40, 80)) {
            val local = BoardLayout.pointLocal(p, 0.01f)
            val back = BoardLayout.sceneToBoard(BoardLayout.boardToScene(local, tilt), tilt)
            assertEquals(local.x, back.x, 1e-5f); assertEquals(local.y, back.y, 1e-5f); assertEquals(local.z, back.z, 1e-5f)
        }
    }

    @Test fun board_near_edge_never_moves_toward_the_cards() {
        for (tilt in BoardLayout.TILTS) {
            val nearTop = BoardLayout.boardToScene(P3(0f, 0f, BoardLayout.BOARD / 2), tilt)
            assertTrue(nearTop.z <= BoardLayout.PIVOT_Z + 1e-5f)
            val nearBottom = BoardLayout.boardToScene(P3(0f, -BoardLayout.BOARD_THICK, BoardLayout.BOARD / 2), tilt)
            assertEquals("near bottom edge rests on the floor at $tilt°", 0f, nearBottom.y, 1e-5f)
        }
    }

    /** D35: card corners are outside every box and the eye→corner segments cross none (9 eyes x 3 tilts), at default and widest card widths. */
    @Test fun cards_are_never_hidden_or_inside_the_pieces() {
        for (tilt in BoardLayout.TILTS) for (cards in listOf(BoardLayout.cards(), BoardLayout.cards(BoardLayout.SEAT_CARD_MAX_W, BoardLayout.COUNT_CARD_MAX_W))) {
            val boxes = BoardLayout.solidBoxes(tilt)
            for (card in cards) {
                val corners = BoardLayout.cardCorners(card)
                for (corner in corners) for (b in boxes) assertFalse("${card.id} corner in ${b.name} tilt $tilt", b.contains(corner))
                for ((eye, e) in BoardLayout.eyes()) for (corner in corners) for (b in boxes) {
                    assertFalse("$eye→${card.id} crosses ${b.name} at tilt $tilt", b.hits(e, corner))
                }
            }
        }
    }

    /** The widest seat card and the widest count cards never touch each other (q3-1: bigger card font). */
    @Test fun widest_cards_do_not_overlap() {
        val c = BoardLayout.cards(BoardLayout.SEAT_CARD_MAX_W, BoardLayout.COUNT_CARD_MAX_W)
        val seat = c.first { it.id == BoardLayout.CARD_SEAT }
        for (count in c.filter { it.id != BoardLayout.CARD_SEAT }) {
            assertTrue(kotlin.math.abs(count.center.x) - count.width / 2 > seat.width / 2 + 0.01f)
            assertTrue(kotlin.math.abs(count.center.x) + count.width / 2 <= BoardLayout.VOLUME_W / 2)
        }
    }

    /** D20: the cards stay below the sight line to the first line of points (they never cover the board). */
    @Test fun cards_stay_below_the_sight_line_to_line_one() {
        for (tilt in BoardLayout.TILTS) for ((eye, e) in BoardLayout.eyes()) for (c in 0 until Go.N) {
            val target = BoardLayout.pointScene(Go.point(0, c), tilt, BoardLayout.STONE_T)
            for (card in BoardLayout.cards(BoardLayout.SEAT_CARD_MAX_W, BoardLayout.COUNT_CARD_MAX_W)) {
                val top = BoardLayout.cardCorners(card).maxByOrNull { it.y }!!
                if (abs(target.x - card.center.x) > card.width) continue
                // Height of the eye→point line where it passes the card's top edge.
                val t = (e.z - top.z) / (e.z - target.z)
                val y = e.y + (target.y - e.y) * t
                assertTrue("$eye line 1 col $c tilt $tilt: sight y=$y card top=${top.y}", y > top.y)
            }
        }
    }
}
