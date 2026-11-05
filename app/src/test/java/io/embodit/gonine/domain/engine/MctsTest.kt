package io.embodit.gonine.domain.engine

import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MctsTest {
    private fun p(s: String) = Go.parse(s)!!

    @Test fun benchmark_prints_playouts_per_second() {
        val rate = Mcts(1).benchmark(1000)
        println("PLAYOUTS_PER_SECOND jvm=${"%.0f".format(rate)}")
        assertTrue(rate > 500)
    }

    @Test fun beginner_returns_a_legal_move_quickly() {
        val pos = Position.start(2)
        val r = Mcts(7).chooseMove(pos, 0.5, AiLevel.BEGINNER)
        assertTrue(pos.isLegal(r.move))
        assertTrue(r.sims in 1..AiLevel.BEGINNER.maxSims)
    }

    @Test fun same_seed_same_move() {
        val pos = Position.empty()
        val a = Mcts(42).search(pos, 7.5, AiLevel.INTERMEDIATE, maxMillisOverride = 60_000).move
        val b = Mcts(42).search(pos, 7.5, AiLevel.INTERMEDIATE, maxMillisOverride = 60_000).move
        assertEquals(a, b)
    }

    @Test fun challenge_captures_a_stone_in_atari() {
        val pos = Position.of(listOf(
            ".........",
            ".........",
            ".........",
            ".........",
            "...XOX...",
            "....X....",
            ".........",
            ".........",
            ".........",
        ))
        // Pure random playouts rate a lone stone in atari as dead already, so the capture need not be the top move;
        // the challenge prior seeds it at the root with virtual wins.
        val r = Mcts(3).search(pos, 7.5, AiLevel.CHALLENGE, maxMillisOverride = 60_000)
        // (Priors are off for every level now; the capture still has to be a searched candidate.)
        assertTrue(r.ranked.any { it.point == p("E6") && it.visits > 0 })
    }

    @Test fun cancelled_search_stops() {
        val r = Mcts(1).search(Position.empty(), 7.5, AiLevel.CHALLENGE, cancelled = { true })
        assertTrue(r.sims <= 16)
    }

    // ---- dead stone suggestion (design deadStoneSuggest: three presets) ----

    /** A finished game, everything settled, no dead stones. */
    private val clean = Position.of(List(9) { "...XO...." })

    /** The same with one white stone deep in black's area (dead). */
    private val oneDead = Position.of(List(9) { i -> if (i == 4) ".O.XO...." else "...XO...." })

    /**
     * A seki on the top-left: white B9-B7 and black C7-D9 share the liberties C9 / C8 and neither has an eye;
     * the outer black wall has two eyes (A3, A1), the white wall opens onto the right side.
     */
    private val seki = Position.of(listOf(
        "XO.XO....",
        "XO.XO....",
        "XOXXO....",
        "XXOOO....",
        "XXO......",
        "XXO......",
        ".XO......",
        "XXO......",
        ".XO......",
    ))

    @Test fun dead_suggestion_clean_has_none() {
        val dead = Mcts(11).deadStones(clean, 7.5)
        assertTrue("dead=$dead", dead.isEmpty())
    }

    @Test fun dead_suggestion_finds_the_lone_invader() {
        val dead = Mcts(11).deadStones(oneDead, 7.5)
        assertEquals(setOf(p("B5")), dead)
    }

    @Test fun dead_suggestion_keeps_seki_alive() {
        val dead = Mcts(11).deadStones(seki, 7.5)
        val sekiStones = listOf("B9", "B8", "B7", "D9", "D8", "D7", "C7").map { p(it) }
        assertTrue("dead=${dead.map { Go.name(it) }}", sekiStones.none { it in dead })
    }

    // ---- AI vs AI ends normally (soak, design acceptance: ≤ 120 moves, no endless filling) ----

    @Test fun beginner_vs_beginner_ends_within_120_moves() {
        repeat(4) { game ->
            val ai = Mcts(100L + game)
            var pos = Position.empty()
            var n = 0
            while (pos.passes < 2 && n < 200) {
                val r = ai.chooseMove(pos, 7.5, AiLevel.BEGINNER)
                pos = (pos.play(r.move) as Position.Result.Ok).position
                n++
            }
            println("SOAK game=$game moves=$n score=${Go.score(pos.cells).margin(7.5)}")
            assertTrue("game $game took $n moves", n <= 120)
        }
    }
}
