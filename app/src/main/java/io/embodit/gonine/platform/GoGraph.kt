package io.embodit.gonine.platform

import android.content.Context
import android.util.Log
import io.embodit.gonine.data.FileGameStore
import io.embodit.gonine.data.SettingsRepository
import io.embodit.gonine.data.StatsRepository
import io.embodit.gonine.domain.game.FlowSound
import io.embodit.gonine.domain.game.GameFlow
import io.embodit.gonine.domain.game.MctsEngine
import io.embodit.gonine.domain.game.Mode
import io.embodit.gonine.ui.board.SoundKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled object graph shared by the board volume and the control window: the game flow (the one live
 * game), settings, stats and sounds.
 */
class GoGraph(context: Context) {
    val settings = SettingsRepository(context)
    val stats = StatsRepository(context)
    val sounds = SoundPlayer(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = MctsEngine()
    val versionName: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    val flow = GameFlow(
        scope = scope,
        store = FileGameStore(context),
        engine = engine,
        log = { Log.i(TAG, it) },
        sound = { s ->
            sounds.play(
                when (s) {
                    FlowSound.ILLEGAL -> SoundKind.ILLEGAL
                    FlowSound.PASS -> SoundKind.PASS
                    FlowSound.WIN -> SoundKind.WIN
                    FlowSound.LOSE -> SoundKind.LOSE
                    FlowSound.ALERT -> SoundKind.ALERT
                    FlowSound.PICK -> SoundKind.PICK
                },
            )
        },
        onResult = { result, state ->
            if (state.config.mode == Mode.VS_AI) stats.record(state.config.level, result.winner == state.humanColor)
        },
    )

    init {
        sounds.apply(settings.value)
        flow.start(settings.value.firstRunDone)
    }

    companion object {
        const val TAG = "GoNine"

        fun from(context: Context): GoGraph = (context.applicationContext as SpatialApplication).graph
    }
}
