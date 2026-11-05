package io.embodit.gonine.domain.game

import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position

/**
 * The saved game as a few `key=value` lines (no JSON dependency, so it round-trips in JVM tests):
 * the config, the human colour, the record (points / PASS / RESUME), the redo stack, dead marks and the
 * stable phase. Loading replays the record through the rules, which rebuilds the superko hash set and
 * recomputes every legal point (design gameSpec.save).
 */
object SaveCodec {
    const val VERSION = 1

    data class Saved(
        val config: GameConfig,
        val humanColor: Byte,
        val record: List<Int>,
        val redo: List<Int>,
        val dead: Set<Int>,
        val phase: Phase,
        val firstGame: Boolean,
        val result: GameResult?,
    )

    fun encode(s: GameState): String = buildString {
        appendLine("v=$VERSION")
        appendLine("mode=${s.config.mode}")
        appendLine("color=${s.config.color}")
        appendLine("level=${s.config.level}")
        appendLine("handicap=${s.config.handicap}")
        appendLine("komi=${s.config.komi}")
        appendLine("human=${s.humanColor}")
        appendLine("record=${s.record.joinToString(",")}")
        appendLine("redo=${s.redo.joinToString(",")}")
        appendLine("dead=${s.dead.sorted().joinToString(",")}")
        appendLine("phase=${stablePhase(s.phase)}")
        appendLine("first=${s.firstGame}")
        s.result?.let { r -> appendLine("result=${r.winner},${r.reason},${r.margin},${r.black},${r.white},${r.komi},${r.moves}") }
    }

    /** A move in flight is already committed, so it restores as the position after it (S16). */
    fun stablePhase(p: Phase): Phase = when (p) {
        Phase.GHOST, Phase.ANIMATING -> Phase.IDLE
        Phase.LOADING, Phase.WELCOME -> Phase.SETUP
        else -> p
    }

    fun decode(text: String): Saved? = runCatching {
        val map = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (map["v"]?.toIntOrNull() != VERSION) return null
        fun ints(key: String) = map[key].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        val config = GameConfig(
            mode = Mode.valueOf(map.getValue("mode")),
            color = ColorChoice.valueOf(map.getValue("color")),
            level = AiLevel.valueOf(map.getValue("level")),
            handicap = map.getValue("handicap").toInt(),
            komi = map.getValue("komi").toDouble(),
        )
        val result = map["result"]?.split(',')?.let { r ->
            GameResult(r[0].toByte(), EndReason.valueOf(r[1]), r[2].toDouble(), r[3].toInt(), r[4].toInt(), r[5].toDouble(), r[6].toInt())
        }
        Saved(config, map.getValue("human").toByte(), ints("record"), ints("redo"), ints("dead").toSet(), Phase.valueOf(map.getValue("phase")), map["first"] == "true", result)
    }.getOrNull()

    /** Replays [record] from the start position of [config]; null if any entry is illegal (a corrupt save). */
    fun replay(config: GameConfig, record: List<Int>): List<Position>? {
        val out = ArrayList<Position>()
        var pos = Position.start(config.handicap)
        out += pos
        for (m in record) {
            pos = if (m == Record.RESUME) pos.withPassesReset() else when (val r = pos.play(m)) {
                is Position.Result.Ok -> r.position
                is Position.Result.Illegal -> return null
            }
            out += pos
        }
        return out
    }

    fun humanOf(config: GameConfig, random: () -> Boolean): Byte = when {
        config.mode == Mode.LOCAL -> Go.BLACK
        config.handicap > 0 -> Go.BLACK
        config.color == ColorChoice.BLACK -> Go.BLACK
        config.color == ColorChoice.WHITE -> Go.WHITE
        else -> if (random()) Go.BLACK else Go.WHITE
    }
}
