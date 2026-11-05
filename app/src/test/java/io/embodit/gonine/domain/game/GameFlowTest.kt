package io.embodit.gonine.domain.game

import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.engine.Candidate
import io.embodit.gonine.domain.engine.SearchResult
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameFlowTest {
    /** A predictable engine: plays the first legal point from a fixed list, passes when the human passed. */
    private class FakeEngine(var replies: ArrayDeque<String> = ArrayDeque()) : Engine {
        override fun choose(pos: Position, komi: Double, level: AiLevel, cancelled: () -> Boolean): SearchResult {
            val move = if (pos.passes >= 1) Go.PASS else generateSequence { replies.removeFirstOrNull() }.map { Go.parse(it)!! }.firstOrNull { pos.isLegal(it) } ?: pos.legalPoints().first()
            return SearchResult(move, listOf(Candidate(move, 1, 0.6)), 0.6, FloatArray(Go.SIZE) { 0.5f }, 1, 1)
        }
        override fun suggest(pos: Position, komi: Double, millis: Long, cancelled: () -> Boolean) =
            SearchResult(pos.legalPoints().first(), pos.legalPoints().take(3).map { Candidate(it, 1, 0.5) }, 0.5, FloatArray(Go.SIZE) { 0.5f }, 1, 1)
        override fun deadStones(pos: Position, komi: Double) = emptySet<Int>()
        override fun reseed(seed: Long) = Unit
    }

    private fun setup(store: GameStore = MemoryGameStore(), engine: FakeEngine = FakeEngine()): Triple<TestScope, GameFlow, GameStore> {
        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        return Triple(scope, GameFlow(scope, store, engine, d), store)
    }

    private fun p(s: String) = Go.parse(s)!!

    @Test fun first_run_opens_welcome_then_first_game_is_beginner_with_two_stones() {
        val (scope, flow) = setup()
        flow.start(firstRunDone = false)
        assertEquals(Phase.WELCOME, flow.current.phase)
        flow.startFirstGame()
        assertEquals(AiLevel.BEGINNER, flow.current.config.level)
        assertEquals(2, flow.current.cells.count { it == Go.BLACK })
        assertEquals(Go.WHITE, flow.current.toPlay) // white first in a handicap game
        scope.advanceUntilIdle()
        assertTrue(flow.current.phase == Phase.ANIMATING || flow.current.phase == Phase.IDLE)
    }

    @Test fun ghost_then_second_tap_commits_and_animation_report_hands_over() {
        val (scope, flow) = setup(engine = FakeEngine(ArrayDeque(listOf("A9"))))
        flow.newGame(GameConfig(handicap = 0))
        flow.tapPoint(p("E5"))
        assertEquals(Phase.GHOST, flow.current.phase)
        assertEquals(p("E5"), flow.current.ghost)
        flow.tapPoint(p("D4")) // tapping elsewhere moves the ghost
        assertEquals(p("D4"), flow.current.ghost)
        flow.tapBowl() // the bowl cancels
        assertNull(flow.current.ghost)
        flow.tapPoint(p("E5")); flow.tapPoint(p("E5"))
        val anim = assertNotNull(flow.current.anim).let { flow.current.anim!! }
        assertEquals(Phase.ANIMATING, flow.current.phase)
        assertEquals(Go.BLACK, flow.current.cells[p("E5")]) // committed before anything moves
        flow.moveFinished(anim.id)
        assertEquals(Phase.THINKING, flow.current.phase)
        scope.advanceUntilIdle()
        // AI answered A9 and its animation ended through the watchdog.
        assertEquals(Go.WHITE, flow.current.cells[p("A9")])
        assertEquals(Phase.IDLE, flow.current.phase)
    }

    @Test fun illegal_tap_flashes_and_does_not_play() {
        val (_, flow) = setup()
        flow.loadPreset(Position.of(listOf(".........", ".........", ".........", ".........", ".........", ".........", "O........", "XO.......", ".XO......")))
        flow.tapPoint(p("A1"))
        assertEquals(Hint.ILLEGAL_SUICIDE, flow.current.hint)
        assertNotNull(flow.current.flash)
        assertEquals(Go.EMPTY, flow.current.cells[p("A1")])
        assertEquals(0, flow.current.record.size)
    }

    @Test fun pass_asks_first_and_two_passes_open_scoring_then_result() {
        val (scope, flow) = setup()
        flow.newGame(GameConfig(handicap = 0))
        flow.requestPass()
        assertEquals(Confirm.PASS, flow.current.confirm)
        flow.cancelConfirm()
        assertNull(flow.current.confirm)
        flow.requestPass(); flow.confirm()
        scope.advanceUntilIdle()
        assertEquals(Phase.SCORING, flow.current.phase) // the fake AI passes back
        flow.acceptScore()
        assertEquals(Phase.RESULT, flow.current.phase)
        assertEquals(Go.WHITE, flow.current.result!!.winner) // empty board: komi wins
    }

    @Test fun play_on_after_scoring_clears_passes_and_keeps_history() {
        val (scope, flow) = setup()
        flow.newGame(GameConfig(handicap = 0))
        flow.requestPass(); flow.confirm()
        scope.advanceUntilIdle()
        val hashes = flow.current.position.history.size
        flow.continuePlay()
        assertEquals(0, flow.current.position.passes)
        assertEquals(hashes, flow.current.position.history.size)
        assertEquals(Record.RESUME, flow.current.record.last())
    }

    @Test fun undo_takes_back_the_whole_round_and_redo_replays_it() {
        val (scope, flow) = setup(engine = FakeEngine(ArrayDeque(listOf("A9", "B9"))))
        flow.newGame(GameConfig(handicap = 0))
        flow.tapPoint(p("E5")); flow.tapPoint(p("E5"))
        scope.advanceUntilIdle()
        assertEquals(2, flow.current.moveCount)
        flow.undo()
        assertEquals(0, flow.current.moveCount)
        assertEquals(Go.BLACK, flow.current.toPlay)
        flow.redo()
        assertEquals(2, flow.current.moveCount)
        assertEquals(Go.WHITE, flow.current.cells[p("A9")])
    }

    @Test fun undo_while_thinking_cancels_and_takes_back_only_the_human_move() {
        val (scope, flow) = setup()
        flow.newGame(GameConfig(handicap = 0))
        flow.tapPoint(p("E5")); flow.tapPoint(p("E5"))
        flow.moveFinished(flow.current.anim!!.id)
        assertEquals(Phase.THINKING, flow.current.phase)
        flow.undo()
        scope.advanceUntilIdle()
        assertEquals(0, flow.current.moveCount)
        assertEquals(Phase.IDLE, flow.current.phase)
    }

    @Test fun save_restores_the_game_after_a_kill_mid_move() {
        val store = MemoryGameStore()
        val (_, flow) = setup(store)
        flow.newGame(GameConfig(handicap = 0))
        flow.tapPoint(p("E5")); flow.tapPoint(p("E5")) // killed while flying
        val (scope2, again) = setup(store)
        again.start(firstRunDone = true)
        assertEquals(Go.BLACK, again.current.cells[p("E5")])
        assertTrue(again.current.hasSave)
        scope2.advanceUntilIdle()
    }

    @Test fun scoring_tap_flips_a_whole_block() {
        val (_, flow) = setup()
        flow.loadPreset(Position.of(List(9) { i -> if (i == 4) ".OO.XO..." else "....XO..." }), GameConfig(mode = Mode.LOCAL, handicap = 0))
        flow.pass(byAi = false); flow.pass(byAi = false)
        assertEquals(Phase.SCORING, flow.current.phase)
        flow.toggleDead(p("B5"))
        assertEquals(setOf(p("B5"), p("C5")), flow.current.dead)
        flow.toggleDead(p("C5"))
        assertTrue(flow.current.dead.isEmpty())
    }

    @Test fun save_codec_round_trip() {
        val s = GameState(config = GameConfig(Mode.LOCAL, ColorChoice.WHITE, AiLevel.CHALLENGE, 3, 5.5), humanColor = Go.BLACK, record = listOf(1, -1, Record.RESUME, 40), redo = listOf(5), dead = setOf(3), phase = Phase.SCORING)
        val back = SaveCodec.decode(SaveCodec.encode(s))!!
        assertEquals(s.config, back.config)
        assertEquals(s.record, back.record)
        assertEquals(s.redo, back.redo)
        assertEquals(s.dead, back.dead)
        assertEquals(Phase.SCORING, back.phase)
    }
}
