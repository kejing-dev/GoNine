package io.embodit.gonine.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.engine.Mcts
import io.embodit.gonine.domain.game.ColorChoice
import io.embodit.gonine.domain.game.GameConfig
import io.embodit.gonine.domain.game.Language
import io.embodit.gonine.domain.game.Mode
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import io.embodit.gonine.ui.board.BoardInputPath
import io.embodit.gonine.ui.board.SceneEvent
import io.embodit.gonine.ui.board.scene.BoardScene
import io.embodit.gonine.ui.board.scene.BoardSceneHolder
import io.embodit.gonine.ui.control.ControlNav
import io.embodit.gonine.ui.control.Page

/**
 * Debug builds only. Drives the app from adb through the same flow functions as the board and the panel
 * (design verification.debugHooks):
 *
 *     adb shell am broadcast -a io.embodit.gonine.DEBUG -p io.embodit.gonine --es cmd tap --es a E5
 *
 * Commands (`--es a <argument>`):
 *   tap <pt> · ghost <pt> (one tap) · place <pt> (two taps) · bowl (tap a bowl) · pass (asks + confirms) · resign
 *   confirm · cancel · undo · redo · hint on|off · mark <pt> · accept · playon · again · menu · kick
 *   new <ai|local>,<b|w|r>,<1|2|3>,<handicap>,<komi> · first (S01 [下第一盘])
 *   preset clean|dead|seki|ko|atari|suicide · goto S01|S02|S03|S04|S06|S07|S09|S10|S11|S12|S13
 *   page play|new|rules|settings · lang system|zh|en · tilt 0|15|25 · sound on|off · volume 0|1|2 · reduced on|off
 *   seed N · auto on|off (engine plays the human too: soak) · closepanel · reopen · recall
 *   bench (playouts / s on this device, and a timed search per level) · state · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = GoGraph.from(context)
        val flow = graph.flow
        val a = intent.getStringExtra("a").orEmpty().trim()
        val on = a == "on" || a == "true" || a == "1"
        val cmd = intent.getStringExtra("cmd")
        fun pt(s: String = a) = Go.parse(s)
        when (cmd) {
            "tap", "ghost" -> pt()?.let { input(SceneEvent.TapPoint(it)) { flow.tapPoint(it) } }
            "place" -> pt()?.let { repeat(2) { _ -> input(SceneEvent.TapPoint(it)) { flow.tapPoint(it) } } }
            "bowl" -> input(SceneEvent.TapBowl) { flow.tapBowl() }
            "pass" -> { flow.requestPass(); flow.confirm() }
            "askpass" -> flow.requestPass()
            "resign" -> flow.requestResign()
            "confirm" -> flow.confirm()
            "cancel" -> flow.cancelConfirm()
            "undo" -> flow.undo()
            "redo" -> flow.redo()
            "hint" -> flow.setHints(on)
            "mark" -> pt()?.let { flow.toggleDead(it) }
            "accept" -> flow.acceptScore()
            "playon" -> flow.continuePlay()
            "again" -> flow.again()
            "menu" -> { flow.toSetup(); ControlNav.page = Page.NEW }
            "kick", "resume" -> flow.kick()
            "first" -> { graph.settings.update { it.copy(firstRunDone = true) }; flow.startFirstGame(); ControlNav.page = Page.PLAY }
            "new" -> {
                val p = a.split(",").map { it.trim().lowercase() }
                val config = GameConfig(
                    mode = if (p.getOrNull(0) == "local") Mode.LOCAL else Mode.VS_AI,
                    color = when (p.getOrNull(1)) { "w" -> ColorChoice.WHITE; "r" -> ColorChoice.RANDOM; else -> ColorChoice.BLACK },
                    level = when (p.getOrNull(2)) { "2" -> AiLevel.INTERMEDIATE; "3" -> AiLevel.CHALLENGE; else -> AiLevel.BEGINNER },
                    handicap = p.getOrNull(3)?.toIntOrNull() ?: 0,
                    komi = p.getOrNull(4)?.toDoubleOrNull() ?: 7.5,
                )
                flow.newGame(config); ControlNav.page = Page.PLAY
            }
            "preset" -> { presets[a]?.let { flow.loadPreset(it) } ?: Log.w(TAG, "unknown preset $a"); ControlNav.page = Page.PLAY }
            "goto" -> goto(a, graph)
            "page" -> ControlNav.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> graph.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in setOf(0, 15, 25) }?.let { t -> graph.settings.update { it.copy(tilt = t) } }
            "sound" -> { graph.settings.update { it.copy(sound = on) }; graph.sounds.apply(graph.settings.value) }
            "volume" -> a.toIntOrNull()?.let { v -> graph.settings.update { it.copy(volume = v.coerceIn(0, 2)) }; graph.sounds.apply(graph.settings.value) }
            "reduced" -> graph.settings.update { it.copy(reducedMotion = on) }
            "seed" -> a.toLongOrNull()?.let { flow.reseed(it) }
            "auto" -> flow.autoPilot = on
            "closepanel" -> WindowCtl.close()
            "reopen" -> WindowCtl.openWindow("debug")
            "recall" -> WindowCtl.recall()
            "bench" -> bench()
            "hover" -> BoardSceneHolder.scene?.let { sc -> sc.hoverHold = a != "off"; sc.setHover(if (a == "off") null else pt()) }
            "flash" -> BoardScene.flashSeconds = if (a == "hold") 3f else 0.3f
            "icons" -> renderIcons(context)
            "state", "dump" -> Log.i(TAG, "DUMP phase=${flow.current.phase} toPlay=${flow.current.toPlay} moves=${flow.current.moveCount} ghost=${flow.current.ghost?.let { Go.name(it) }} hint=${flow.current.hint} confirm=${flow.current.confirm} dead=${flow.current.dead.size} result=${flow.current.result} panel=${WindowCtl.panelState} page=${ControlNav.page} head=${HeadPitch.support}/${HeadPitch.last}\n${board(flow.current.cells)}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${BoardSceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
        Log.i(TAG, "CMD $cmd a=$a phase=${flow.current.phase} n=${flow.current.moveCount}")
    }

    /** Through the board's own input path when the board is up (same as a pinch), else straight to the flow. */
    private fun input(e: SceneEvent, fallback: () -> Unit) {
        BoardInputPath.handler?.invoke(e) ?: fallback()
    }

    private fun goto(id: String, graph: GoGraph) {
        val flow = graph.flow
        fun ensureGame() { if (!flow.current.inGame) { flow.newGame(GameConfig.FIRST); } }
        when (id.uppercase()) {
            "S01" -> { flow.toSetup(); graph.settings.update { it.copy(firstRunDone = false) }; flow.start(false); ControlNav.page = Page.RULES }
            "S02" -> ControlNav.page = Page.NEW
            "S03" -> { ensureGame(); ControlNav.page = Page.PLAY }
            "S04" -> { ensureGame(); ControlNav.page = Page.PLAY; val p = flow.current.position.legalPoints().firstOrNull { it == Go.parse("E5") } ?: flow.current.position.legalPoints().first(); flow.tapPoint(p) }
            "S06" -> { flow.newGame(GameConfig(handicap = 2)); ControlNav.page = Page.PLAY }
            "S07" -> { flow.loadPreset(presets.getValue("dead")); ControlNav.page = Page.PLAY; flow.pass(byAi = false); flow.pass(byAi = false) }
            "S09" -> { goto("S07", graph); Handler(Looper.getMainLooper()).postDelayed({ flow.acceptScore() }, 1500) }
            "S10" -> WindowCtl.close()
            "S11" -> ControlNav.page = Page.SETTINGS
            "S12" -> ControlNav.page = Page.RULES
            "S13" -> { ensureGame(); ControlNav.page = Page.PLAY; flow.requestPass() }
            else -> Log.w(TAG, "goto $id: not reachable from adb (S00 = cold start, S05 = place, S08 = tap an illegal point, S16 = force-stop + start)")
        }
    }

    /** D23: every ic_* drawable rendered into one sheet (files/icons.png; adb pull via run-as). */
    private fun renderIcons(context: Context) {
        val fields = io.embodit.gonine.R.drawable::class.java.fields.filter { it.name.startsWith("ic_") && it.name != "ic_launcher_foreground" }.sortedBy { it.name }
        val cell = 160
        val cols = 5
        val rows = (fields.size + cols - 1) / cols
        val bmp = android.graphics.Bitmap.createBitmap(cols * cell, rows * cell, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 16f; textAlign = android.graphics.Paint.Align.CENTER }
        fields.forEachIndexed { i, f ->
            val d = context.getDrawable(f.getInt(null))!!.mutate()
            d.setTint(android.graphics.Color.BLACK)
            val x = (i % cols) * cell; val y = (i / cols) * cell
            d.setBounds(x + 40, y + 20, x + 120, y + 100)
            d.draw(c)
            c.drawText(f.name.removePrefix("ic_"), x + cell / 2f, y + 135f, text)
        }
        val out = java.io.File(context.filesDir, "icons.png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "ICONS ${fields.size} -> ${out.absolutePath}")
    }

    private fun bench() {
        Thread {
            val m = Mcts(1)
            m.benchmark(300)
            val rate = m.benchmark(2000)
            val parts = AiLevel.entries.map { l ->
                val r = Mcts(2).search(Position.start(2), 0.5, l)
                "$l sims=${r.sims} ms=${r.millis}"
            }
            Log.i(TAG, "BENCH playoutsPerSecond=${"%.0f".format(rate)} ${parts.joinToString(" · ")}")
        }.start()
    }

    private fun board(cells: ByteArray): String = (Go.N - 1 downTo 0).joinToString("\n") { r ->
        "${r + 1} " + (0 until Go.N).joinToString(" ") { c -> when (cells[Go.point(r, c)]) { Go.BLACK -> "X"; Go.WHITE -> "O"; else -> "." } }
    }

    private companion object {
        const val TAG = GoGraph.TAG

        /** Scoring presets (design S07: clean / one dead group / seki) and rule cases (ko, atari, suicide). */
        val presets: Map<String, Position> = mapOf(
            "clean" to Position.of(List(9) { "...XO...." }),
            "dead" to Position.of(List(9) { i -> if (i == 4) ".O.XO...." else "...XO...." }),
            "seki" to Position.of(listOf("XO.XO....", "XO.XO....", "XOXXO....", "XXOOO....", "XXO......", "XXO......", ".XO......", "XXO......", ".XO......")),
            "ko" to Position.of(listOf(".........", ".........", ".........", ".........", "...XO....", "..XO.O...", "...XO....", ".........", ".........")),
            "atari" to Position.of(listOf(".........", ".........", ".........", ".........", "...XOX...", "....X....", ".........", ".........", ".........")),
            "suicide" to Position.of(listOf(".........", ".........", ".........", ".........", ".........", ".........", "O........", "XO.......", ".XO......"), toPlay = Go.BLACK),
        )
    }
}
