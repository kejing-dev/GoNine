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

    @Test fun beginner_answers_a_pass_with_a_pass() {
        // Behind or not, the beginner passes back so a new player's game ends (critic R4).
        val pos = (Position.start(2).play(Go.PASS) as Position.Result.Ok).position
        assertEquals(Go.PASS, Mcts(5).chooseMove(pos, 0.5, AiLevel.BEGINNER).move)
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
        for (seed in 1L..20L) { val dead = Mcts(seed).deadStones(clean, 7.5); assertTrue("seed $seed dead=$dead", dead.isEmpty()) }
    }

    @Test fun dead_suggestion_finds_the_lone_invader() {
        for (seed in 1L..20L) assertEquals("seed $seed", setOf(p("B5")), Mcts(seed).deadStones(oneDead, 7.5))
    }

    @Test fun dead_suggestion_keeps_seki_alive() {
        val sekiStones = listOf("B9", "B8", "B7", "D9", "D8", "D7", "C7").map { p(it) }
        for (seed in 1L..20L) { val dead = Mcts(seed).deadStones(seki, 7.5); assertTrue("seed $seed dead=${dead.map { Go.name(it) }}", sekiStones.none { it in dead }) }
    }

    // ---- AI vs AI ends normally (soak, design acceptance: ≤ 120 moves, no endless filling) ----

    /** Design acceptance: AI vs AI beginner, 20 games, each ends normally within 120 moves (critic R7). */
    @Test fun beginner_vs_beginner_ends_within_120_moves() {
        repeat(20) { game ->
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
