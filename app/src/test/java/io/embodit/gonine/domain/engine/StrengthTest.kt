package io.embodit.gonine.domain.engine

import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI strength (design acceptance): fixed seeds, colours alternate. Slow, so it runs only with
 * `-Pstrength=N` (games per pairing): ./gradlew :app:testDebugUnitTest --tests '*StrengthTest*' -Pstrength=100
 */
class StrengthTest {
    private val games = (System.getProperty("strength") ?: "0").toInt()

    /** The scripted opponent: capture if possible, escape atari, else random (no own-eye fill). */
    private fun scripted(pos: Position, rnd: Random): Int {
        val me = pos.toPlay
        val legal = pos.legalPoints()
        val groups = Go.groups(pos.cells).filter { it.liberties.size == 1 }
        groups.filter { it.color != me }.map { it.liberties[0] }.firstOrNull { it in legal }?.let { return it }
        groups.filter { it.color == me }.map { it.liberties[0] }.firstOrNull { it in legal }?.let { return it }
        val fb = FastBoard().apply { load(pos.cells, me) }
        val ok = legal.filter { !fb.isEye(it, me) }
        return if (ok.isEmpty()) Go.PASS else ok.random(rnd)
    }

    private fun play(black: (Position) -> Int, white: (Position) -> Int, handicap: Int, komi: Double): Byte {
        var pos = Position.start(handicap)
        var n = 0
        while (pos.passes < 2 && n < 160) {
            val m = if (pos.toPlay == Go.BLACK) black(pos) else white(pos)
            pos = (pos.play(if (pos.isLegal(m)) m else Go.PASS) as Position.Result.Ok).position
            n++
        }
        return if (Go.score(pos.cells).margin(komi) > 0) Go.BLACK else Go.WHITE
    }

    private fun match(name: String, a: AiLevel, b: AiLevel) {
        var aWins = 0
        repeat(games) { g ->
            val ea = Mcts(1000L + g); val eb = Mcts(5000L + g)
            val aBlack = g % 2 == 0
            val fa = { p: Position -> ea.chooseMove(p, 7.5, a).move }
            val fb = { p: Position -> eb.chooseMove(p, 7.5, b).move }
            val w = if (aBlack) play(fa, fb, 0, 7.5) else play(fb, fa, 0, 7.5)
            if ((w == Go.BLACK) == aBlack) aWins++
        }
        println("STRENGTH $name ${a} vs ${b}: $aWins / $games = ${100 * aWins / games}%")
    }

    @Test fun challenge_vs_beginner() { assumeTrue(games > 0); match("challenge", AiLevel.CHALLENGE, AiLevel.BEGINNER) }

    /** Neighbouring levels must be ordered too (critic S1): challenge vs intermediate ≥ 60 %. */
    @Test fun challenge_vs_intermediate() { assumeTrue(games > 0); match("challenge", AiLevel.CHALLENGE, AiLevel.INTERMEDIATE) }

    @Test fun intermediate_vs_beginner() { assumeTrue(games > 0); match("intermediate", AiLevel.INTERMEDIATE, AiLevel.BEGINNER) }

    @Test fun beginner_giving_two_stones_vs_script() {
        assumeTrue(games > 0)
        var scriptWins = 0
        repeat(games) { g ->
            val ai = Mcts(9000L + g); val rnd = Random(g)
            // The script plays black with two stones, the beginner AI white, komi 0.5 (the first-game setting).
            val w = play({ scripted(it, rnd) }, { ai.chooseMove(it, 0.5, AiLevel.BEGINNER).move }, 2, 0.5)
            if (w == Go.BLACK) scriptWins++
        }
        println("STRENGTH script(2 stones) vs beginner: script won $scriptWins / $games = ${100 * scriptWins / games}%")
    }
}
