package io.embodit.gonine.ui.board

import io.embodit.gonine.domain.game.GameState
import io.embodit.gonine.domain.game.IllegalFlash
import io.embodit.gonine.domain.game.MoveAnim
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.domain.rules.Go

/** What the board draws, derived from the game state and the settings. */
data class BoardUiState(
    val gameId: Int = 0,
    val phase: Phase = Phase.LOADING,
    val cells: ByteArray = ByteArray(Go.SIZE),
    val toPlay: Byte = Go.BLACK,
    val ghost: Int? = null,
    val flash: IllegalFlash? = null,
    val anim: MoveAnim? = null,
    val lastMove: Int? = null,
    val suggestions: List<Int> = emptyList(),
    val lights: List<Int> = emptyList(),
    val dead: Set<Int> = emptySet(),
    /** Per point, the area owner while counting (null outside S07 / S09). */
    val owner: ByteArray? = null,
    val winner: Byte? = null,
    val rightColor: Byte = Go.BLACK,
    val capturedByBlack: Int = 0,
    val capturedByWhite: Int = 0,
    val tilt: Int = 15,
    val reducedMotion: Boolean = false,
) {
    companion object {
        fun of(s: GameState, tilt: Int, reducedMotion: Boolean, owner: ByteArray?): BoardUiState {
            val pos = s.position
            val last = pos.lastMove?.takeIf { !it.isPass }?.point
            return BoardUiState(
                gameId = s.gameId,
                phase = s.phase,
                cells = pos.cells,
                toPlay = pos.toPlay,
                ghost = s.ghost,
                flash = s.flash,
                anim = s.anim,
                lastMove = last,
                suggestions = if (s.phase in setOf(Phase.IDLE, Phase.GHOST)) s.suggestions else emptyList(),
                lights = if (s.phase in setOf(Phase.IDLE, Phase.GHOST, Phase.THINKING)) s.atariLiberties else emptyList(),
                dead = if (s.phase == Phase.SCORING || s.phase == Phase.RESULT) s.dead else emptySet(),
                owner = owner,
                winner = s.result?.winner,
                rightColor = s.rightColor,
                capturedByBlack = pos.capturedByBlack,
                capturedByWhite = pos.capturedByWhite,
                tilt = tilt,
                reducedMotion = reducedMotion,
            )
        }
    }
}

/** What the board reports back. */
sealed interface SceneEvent {
    data class TapPoint(val point: Int) : SceneEvent
    data object TapBowl : SceneEvent
    data object TapEmpty : SceneEvent
    data class MoveFinished(val id: Int) : SceneEvent
    data class Sound(val kind: SoundKind) : SceneEvent
}

enum class SoundKind { PICK, PLACE, PLACE_2, PLACE_3, CAPTURE, ILLEGAL, ALERT, PASS, SCORE, WIN, LOSE }
