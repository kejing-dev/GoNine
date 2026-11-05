package io.embodit.gonine.domain.game

import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.IllegalReason
import io.embodit.gonine.domain.rules.Move
import io.embodit.gonine.domain.rules.Position

/** Design states[] the flow can be in (S00–S09; S10–S13 / S16 are panel or lifecycle states on top). */
enum class Phase(val stateId: String) {
    LOADING("S00"),
    WELCOME("S01"),
    SETUP("S02"),
    IDLE("S03"),
    GHOST("S04"),
    ANIMATING("S05"),
    THINKING("S06"),
    SCORING("S07"),
    RESULT("S09"),
}

enum class Mode { VS_AI, LOCAL }

enum class ColorChoice { BLACK, WHITE, RANDOM }

enum class EndReason { COUNTED, RESIGNED }

enum class Language { SYSTEM, ZH, EN }

data class GameConfig(
    val mode: Mode = Mode.VS_AI,
    val color: ColorChoice = ColorChoice.BLACK,
    val level: AiLevel = AiLevel.BEGINNER,
    /** 0, 2, 3 or 4 stones; with a handicap the human (vs AI) is black and white moves first. */
    val handicap: Int = 2,
    /** 7.5 or 5.5; a handicap game uses 0.5. */
    val komi: Double = 7.5,
) {
    val effectiveKomi: Double get() = if (handicap > 0) 0.5 else komi

    companion object {
        /** The first game: beginner, two stones for you (S01 [下第一盘]). */
        val FIRST = GameConfig()
    }
}

/** One entry of the game record: a point, [Go.PASS], or [RESUME] (passes reset by「不对，继续下」). */
object Record {
    const val RESUME = -2
}

data class GameResult(
    val winner: Byte,
    val reason: EndReason,
    /** Winning margin in points for a counted game. */
    val margin: Double,
    val black: Int,
    val white: Int,
    val komi: Double,
    val moves: Int,
)

/** What the flow says about the last tap, shown on the play page (S04 / S08). */
enum class Hint {
    NONE,
    TAP_AGAIN,
    ILLEGAL_SUICIDE,
    ILLEGAL_KO,
    ILLEGAL_SUPERKO,
    ILLEGAL_OCCUPIED,
    ATARI,
    AI_PASSED,
    /** The AI answered your pass with a move: one more pass and we count. */
    AI_PLAYS_ON,
    YOUR_PASS_SEEN,
    BOTH_PASSED,
    PASS_HANDOFF,
    ;

    companion object {
        fun of(reason: IllegalReason): Hint = when (reason) {
            IllegalReason.SUICIDE -> ILLEGAL_SUICIDE
            IllegalReason.KO -> ILLEGAL_KO
            IllegalReason.SUPERKO -> ILLEGAL_SUPERKO
            else -> ILLEGAL_OCCUPIED
        }
    }
}

/** An animated move the board must play: a stone flies from its bowl, captured stones fly into the capturer's lid. */
data class MoveAnim(val id: Int, val move: Move, val capturesBefore: Int)

/** A red cross flashed on an illegal point. */
data class IllegalFlash(val id: Int, val point: Int)

/**
 * The single source of truth for both windows. Everything the board draws and the panel shows is derived
 * from this; the flow replaces it as a whole.
 */
data class GameState(
    val gameId: Int = 0,
    val phase: Phase = Phase.LOADING,
    val config: GameConfig = GameConfig.FIRST,
    val humanColor: Byte = Go.BLACK,
    /** Game record (points, PASS, RESUME); [positions] has one more entry than this. */
    val record: List<Int> = emptyList(),
    val positions: List<Position> = listOf(Position.start(0)),
    val redo: List<Int> = emptyList(),
    val ghost: Int? = null,
    val hint: Hint = Hint.NONE,
    val flash: IllegalFlash? = null,
    val anim: MoveAnim? = null,
    val dead: Set<Int> = emptySet(),
    val result: GameResult? = null,
    /** Engine's recommended points (hint on / first game), best first. */
    val suggestions: List<Int> = emptyList(),
    val hintsOn: Boolean = false,
    val firstGame: Boolean = false,
    /** A confirmation shown in place on the play page (S13). */
    val confirm: Confirm? = null,
    val hasSave: Boolean = false,
    val savedMoves: Int = 0,
    val lastAiPassed: Boolean = false,
) {
    val position: Position get() = positions.last()
    val cells: ByteArray get() = position.cells
    val toPlay: Byte get() = position.toPlay
    val isVsAi: Boolean get() = config.mode == Mode.VS_AI
    val aiColor: Byte get() = Go.other(humanColor)
    val isAiTurn: Boolean get() = isVsAi && toPlay == aiColor
    val inGame: Boolean get() = phase in setOf(Phase.IDLE, Phase.GHOST, Phase.ANIMATING, Phase.THINKING, Phase.SCORING)
    val moveCount: Int get() = record.count { it != Record.RESUME }

    /** Bowl on the player's right: the human's colour vs AI, black in a local game (design seats). */
    val rightColor: Byte get() = if (isVsAi) humanColor else Go.BLACK

    /** Blocks with one liberty and those liberties (hint on, or the first game). */
    val atariLiberties: List<Int>
        get() = if (!(hintsOn || firstGame)) emptyList() else Go.groups(cells).filter { it.liberties.size == 1 }.flatMap { it.liberties }.distinct()
}

enum class Confirm { PASS, RESIGN, NEW_GAME }

data class Stats(val wins: Map<AiLevel, Int> = emptyMap(), val losses: Map<AiLevel, Int> = emptyMap()) {
    fun won(level: AiLevel) = wins[level] ?: 0
    fun lost(level: AiLevel) = losses[level] ?: 0
}

data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    /** 0 low, 1 medium, 2 high. */
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    /** 0, 15 or 25 degrees. */
    val tilt: Int = 15,
    val firstRunDone: Boolean = false,
)
