package io.embodit.gonine.ui.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.gonine.R
import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.game.ColorChoice
import io.embodit.gonine.domain.game.Confirm
import io.embodit.gonine.domain.game.EndReason
import io.embodit.gonine.domain.game.GameConfig
import io.embodit.gonine.domain.game.GameState
import io.embodit.gonine.domain.game.Hint
import io.embodit.gonine.domain.game.Mode
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.domain.game.Settings
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.platform.GoGraph
import io.embodit.gonine.ui.theme.GoColors

@Composable
private fun colorName(c: Byte) = stringResource(if (c == Go.BLACK) R.string.black else R.string.white)

@Composable
internal fun levelName(l: AiLevel) = stringResource(
    when (l) { AiLevel.BEGINNER -> R.string.level_beginner; AiLevel.INTERMEDIATE -> R.string.level_intermediate; AiLevel.CHALLENGE -> R.string.level_challenge },
)

private fun komiText(k: Double) = if (k == k.toInt().toDouble()) k.toInt().toString() else k.toString()

// ---- play page (S03 – S09, S13) ----

@Composable
internal fun PlayPage(game: GameState, settings: Settings, graph: GoGraph) {
    val flow = graph.flow
    if (!game.inGame && game.phase != Phase.RESULT) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.play_no_game), PicoTheme.typography.titleMedium, "play/none", maxLines = 2)
            Gap(16.dp)
            PrimaryButton(stringResource(R.string.btn_to_new), { ControlNav.page = Page.NEW }, "play/toNew", icon = R.drawable.ic_add_circle)
        }
        return
    }
    when (game.phase) {
        Phase.SCORING -> { ScoringPanel(game, graph); return }
        Phase.RESULT -> { ResultPanel(game, graph); return }
        else -> Unit
    }
    Column(Modifier.fillMaxSize()) {
        // Whose turn, with the seat colour.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (game.toPlay == Go.BLACK) GoColors.BlackStone else GoColors.WhiteStone))
            Box(Modifier.padding(start = 10.dp)) {
                val turn = when {
                    game.isVsAi && game.isAiTurn -> stringResource(R.string.turn_ai)
                    game.isVsAi -> stringResource(R.string.turn_you, colorName(game.toPlay))
                    else -> stringResource(R.string.turn_color, colorName(game.toPlay))
                }
                Label(turn, PicoTheme.typography.headlineSmall, "play/turn")
            }
            if (game.phase == Phase.THINKING) CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(22.dp))
        }
        Gap(4.dp)
        val pos = game.position
        val caps = if (game.isVsAi) stringResource(R.string.captures_vs_ai, pos.capturedBy(game.humanColor), pos.capturedBy(game.aiColor))
        else stringResource(R.string.captures_local, pos.capturedByBlack, pos.capturedByWhite)
        Label(caps, PicoTheme.typography.bodyLarge, "play/captures", color = PicoTheme.colorScheme.labelSecondary)
        Gap(6.dp)
        // One-line status (S04 / S08).
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Label(statusLine(game), PicoTheme.typography.bodyLarge, "play/status", maxLines = 2, color = if (game.hint.name.startsWith("ILLEGAL")) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary)
        }
        Gap(10.dp)
        val confirm = game.confirm
        if (confirm != null && confirm != Confirm.NEW_GAME) {
            ConfirmBox(confirm, graph)
        } else {
            val idle = game.phase in setOf(Phase.IDLE, Phase.GHOST) && !game.isAiTurn
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.btn_undo), { flow.undo() }, "undo", Modifier.weight(1f), R.drawable.ic_undo, enabled = game.record.isNotEmpty() && game.phase in setOf(Phase.IDLE, Phase.GHOST, Phase.THINKING))
                SecondaryButton(stringResource(R.string.btn_redo), { flow.redo() }, "redo", Modifier.weight(1f), R.drawable.ic_redo, enabled = game.redo.isNotEmpty() && idle)
                SecondaryButton(stringResource(R.string.btn_pass), { flow.requestPass() }, "pass", Modifier.weight(1f), R.drawable.ic_skip_next, enabled = idle)
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(if (game.hintsOn) R.string.btn_hint_on else R.string.btn_hint_off), { flow.setHints(!game.hintsOn) }, "hint", Modifier.weight(1f), R.drawable.ic_lightbulb)
                SecondaryButton(
                    stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_muted),
                    { graph.settings.update { it.copy(sound = !it.sound) }; graph.sounds.apply(graph.settings.value) },
                    "mute", Modifier.weight(1f), if (settings.sound) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
                )
                SecondaryButton(stringResource(R.string.btn_resign), { flow.requestResign() }, "resign", Modifier.weight(1f), R.drawable.ic_flag, enabled = idle)
            }
        }
        Gap(10.dp)
        val handicap = if (game.config.handicap > 0) stringResource(R.string.meta_handicap, game.config.handicap) else stringResource(R.string.meta_even)
        val meta = if (game.isVsAi) stringResource(R.string.play_meta_ai, game.moveCount, levelName(game.config.level), handicap, komiText(game.config.effectiveKomi))
        else stringResource(R.string.play_meta_local, game.moveCount, handicap, komiText(game.config.effectiveKomi))
        Label(meta, PicoTheme.typography.bodySmall, "play/meta", color = PicoTheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun statusLine(game: GameState): String = when (game.hint) {
    Hint.TAP_AGAIN -> stringResource(R.string.hint_tap_again)
    Hint.ILLEGAL_SUICIDE -> stringResource(R.string.hint_suicide)
    Hint.ILLEGAL_KO -> stringResource(R.string.hint_ko)
    Hint.ILLEGAL_SUPERKO -> stringResource(R.string.hint_superko)
    Hint.ILLEGAL_OCCUPIED -> stringResource(R.string.hint_occupied)
    Hint.ATARI -> stringResource(R.string.hint_atari)
    Hint.AI_PASSED -> stringResource(R.string.hint_ai_passed)
    Hint.AI_PLAYS_ON -> stringResource(R.string.hint_ai_plays_on)
    Hint.YOUR_PASS_SEEN -> stringResource(R.string.hint_you_passed)
    Hint.BOTH_PASSED -> stringResource(R.string.hint_both_passed)
    Hint.PASS_HANDOFF -> stringResource(R.string.hint_handoff)
    Hint.NONE -> when {
        game.phase == Phase.THINKING -> stringResource(R.string.hint_thinking)
        game.firstGame && game.suggestions.isNotEmpty() -> stringResource(R.string.hint_first_game)
        else -> stringResource(R.string.hint_your_move)
    }
}

/** S13: the question in place, two equal buttons in one row. */
@Composable
private fun ConfirmBox(c: Confirm, graph: GoGraph) {
    val (q, body, yes) = when (c) {
        Confirm.PASS -> Triple(R.string.confirm_pass, R.string.confirm_pass_body, R.string.confirm_pass_yes)
        Confirm.RESIGN -> Triple(R.string.confirm_resign, R.string.confirm_resign_body, R.string.confirm_resign_yes)
        Confirm.NEW_GAME -> Triple(R.string.confirm_new, R.string.confirm_new_body, R.string.confirm_new_yes)
    }
    Column {
        Label(stringResource(q), PicoTheme.typography.titleMedium, "confirm/q/$c")
        Label(stringResource(body), PicoTheme.typography.bodyMedium, "confirm/body/$c", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(yes), { graph.flow.confirm() }, "confirm/yes/$c", Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.confirm_think), { graph.flow.cancelConfirm() }, "confirm/no/$c", Modifier.weight(1f), height = 56.dp)
        }
    }
}

/** S07: the play page turns into the count. */
@Composable
private fun ScoringPanel(game: GameState, graph: GoGraph) {
    val sc = graph.flow.score(game)
    val komi = game.config.effectiveKomi
    val deadBlocks = Go.groups(game.cells).filter { g -> g.stones.any { it in game.dead } }
    val deadColor = deadBlocks.firstOrNull()?.color
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Label(stringResource(R.string.scoring_title), PicoTheme.typography.headlineSmall, "scoring/title")
        Gap(6.dp)
        val line = when {
            deadBlocks.isNotEmpty() -> stringResource(R.string.scoring_dead, deadBlocks.size, colorName(deadColor ?: Go.WHITE))
            else -> stringResource(R.string.scoring_no_dead)
        }
        Label(line, PicoTheme.typography.bodyLarge, "scoring/dead", maxLines = 2)
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.scoring_black, sc.black), PicoTheme.typography.titleMedium, "scoring/black")
            Label(stringResource(R.string.scoring_white, sc.white, komiText(komi), komiText(sc.white + komi)), PicoTheme.typography.titleMedium, "scoring/white")
        }
        val margin = sc.margin(komi)
        Label(stringResource(R.string.scoring_lead, colorName(if (margin > 0) Go.BLACK else Go.WHITE), komiText(kotlin.math.abs(margin))), PicoTheme.typography.bodyMedium, "scoring/lead", color = PicoTheme.colorScheme.labelSecondary)
        Gap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.btn_accept), { graph.flow.acceptScore() }, "scoring/accept", Modifier.weight(1f), R.drawable.ic_check)
            SecondaryButton(stringResource(R.string.btn_play_on), { graph.flow.continuePlay() }, "scoring/playOn", Modifier.weight(1f), R.drawable.ic_replay, height = 56.dp)
        }
        Gap(10.dp)
        Label(stringResource(R.string.rule_area), PicoTheme.typography.bodySmall, "scoring/rule", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

/** S09: the result card, the rule in small print under it. */
@Composable
private fun ResultPanel(game: GameState, graph: GoGraph) {
    val r = game.result ?: return
    val stats by graph.stats.stats.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val head = if (r.reason == EndReason.COUNTED) stringResource(R.string.result_counted, colorName(r.winner), komiText(r.margin)) else stringResource(R.string.result_resigned, colorName(r.winner))
        Label(head, PicoTheme.typography.headlineMedium, "result/head")
        if (game.isVsAi) Label(stringResource(if (r.winner == game.humanColor) R.string.result_you_won else R.string.result_you_lost), PicoTheme.typography.titleMedium, "result/you")
        Gap(4.dp)
        val detail = if (r.reason == EndReason.COUNTED) stringResource(R.string.result_detail_counted, r.moves, r.black, r.white, komiText(r.komi)) else stringResource(R.string.result_detail_resigned, r.moves)
        Label(detail, PicoTheme.typography.bodyLarge, "result/detail", color = PicoTheme.colorScheme.labelSecondary)
        if (r.reason == EndReason.COUNTED) Label(stringResource(R.string.rule_area), PicoTheme.typography.bodySmall, "result/rule", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.btn_again), { graph.flow.again() }, "result/again", Modifier.weight(1f), R.drawable.ic_refresh)
            SecondaryButton(stringResource(R.string.btn_menu), { graph.flow.toSetup(); ControlNav.page = Page.NEW }, "result/menu", Modifier.weight(1f), R.drawable.ic_home, height = 56.dp)
        }
        if (game.isVsAi) {
            Gap(10.dp)
            val l = game.config.level
            Label(stringResource(R.string.result_stats, levelName(l), stats.won(l), stats.lost(l)), PicoTheme.typography.bodySmall, "result/stats", color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

// ---- new game (S02) ----

@Composable
internal fun NewPage(game: GameState, graph: GoGraph) {
    var draft by remember(game.gameId) { mutableStateOf(game.config) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(stringResource(R.string.tab_new), PicoTheme.typography.headlineSmall, "title/new")
        if (game.inGame && game.moveCount > 0) {
            Gap(4.dp)
            PrimaryButton(stringResource(R.string.new_continue, game.moveCount), { ControlNav.page = Page.PLAY }, "new/continue", Modifier.fillMaxWidth(), R.drawable.ic_play_arrow)
        }
        if (game.confirm == Confirm.NEW_GAME) {
            Gap(6.dp)
            ConfirmBox(Confirm.NEW_GAME, graph)
            return@Column
        }
        Gap(6.dp)
        ChoiceRow(stringResource(R.string.new_who), "who", labelWidth = 124.dp) {
            ChoiceButton(stringResource(R.string.new_vs_ai), draft.mode == Mode.VS_AI, { draft = draft.copy(mode = Mode.VS_AI, handicap = if (draft.level == AiLevel.BEGINNER && draft.color == ColorChoice.BLACK) 2 else 0, komi = 7.5) }, "who/ai", Modifier.weight(1f))
            ChoiceButton(stringResource(R.string.new_local), draft.mode == Mode.LOCAL, { draft = draft.copy(mode = Mode.LOCAL, handicap = 0, komi = 7.5) }, "who/local", Modifier.weight(1f))
        }
        if (draft.mode == Mode.VS_AI) {
            ChoiceRow(stringResource(R.string.new_you_play), "color", labelWidth = 124.dp) {
                listOf(ColorChoice.BLACK to R.string.black, ColorChoice.WHITE to R.string.white, ColorChoice.RANDOM to R.string.new_random).forEach { (c, res) ->
                    // With a handicap you always play black.
                    ChoiceButton(stringResource(res), draft.color == c || (draft.handicap > 0 && c == ColorChoice.BLACK), { draft = draft.copy(color = c, handicap = if (c != ColorChoice.BLACK) 0 else draft.handicap) }, "color/$c", Modifier.weight(1f), enabled = draft.handicap == 0 || c == ColorChoice.BLACK)
                }
            }
            ChoiceRow(stringResource(R.string.new_level), "level", labelWidth = 124.dp) {
                AiLevel.entries.forEach { l -> ChoiceButton(levelName(l), draft.level == l, { draft = draft.copy(level = l, handicap = if (l == AiLevel.BEGINNER && draft.color == ColorChoice.BLACK) 2 else draft.handicap) }, "level/$l", Modifier.weight(1f)) }
            }
            Label(stringResource(R.string.new_level_note), PicoTheme.typography.bodySmall, "new/levelNote", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        ChoiceRow(stringResource(if (draft.mode == Mode.VS_AI) R.string.new_handicap else R.string.new_handicap_local), "handicap", labelWidth = 124.dp) {
            for (h in listOf(0, 2, 3, 4)) ChoiceButton("$h", draft.handicap == h, { draft = draft.copy(handicap = h, color = if (h > 0) ColorChoice.BLACK else draft.color) }, "handicap/$h", Modifier.weight(1f))
        }
        if (draft.mode == Mode.VS_AI) Label(stringResource(R.string.new_handicap_note), PicoTheme.typography.bodySmall, "new/handicapNote", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        ChoiceRow(stringResource(R.string.new_komi), "komi", labelWidth = 124.dp) {
            if (draft.handicap > 0) Label(stringResource(R.string.new_komi_handicap), PicoTheme.typography.labelLarge, "komi/handicap", color = PicoTheme.colorScheme.labelSecondary)
            else for (k in listOf(7.5, 5.5)) ChoiceButton(komiText(k), draft.komi == k, { draft = draft.copy(komi = k) }, "komi/$k", Modifier.weight(1f))
        }
        Gap(8.dp)
        PrimaryButton(stringResource(R.string.btn_start), { ControlNav.page = Page.PLAY; graph.flow.requestNewGame(draft); if (graph.flow.current.confirm == Confirm.NEW_GAME) ControlNav.page = Page.NEW }, "new/start", Modifier.fillMaxWidth(), R.drawable.ic_play_arrow)
        Gap(16.dp)
    }
}

// ---- rules (S01 / S12) ----

@Composable
internal fun RulesPage(first: Boolean, graph: GoGraph) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Label(stringResource(R.string.tab_rules), PicoTheme.typography.headlineSmall, "title/rules")
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RuleTile(R.string.rules_lib_title, R.string.rules_lib_body, Modifier.weight(1f)) { drawLiberties() }
            RuleTile(R.string.rules_cap_title, R.string.rules_cap_body, Modifier.weight(1f)) { drawCapture() }
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RuleTile(R.string.rules_ko_title, R.string.rules_ko_body, Modifier.weight(1f)) { drawKo() }
            RuleTile(R.string.rules_pass_title, R.string.rules_pass_body, Modifier.weight(1f)) { drawCount() }
        }
        if (first) {
            Gap(12.dp)
            PrimaryButton(stringResource(R.string.btn_first_game), {
                graph.settings.update { it.copy(firstRunDone = true) }
                graph.flow.startFirstGame()
                ControlNav.page = Page.PLAY
            }, "rules/first", Modifier.fillMaxWidth(), R.drawable.ic_play_arrow)
        }
        Gap(12.dp)
        Label(stringResource(R.string.rules_mistakes), PicoTheme.typography.titleMedium, "rules/mistakes")
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).forEach {
            Label("· " + stringResource(it), PicoTheme.typography.bodyMedium, "rules/mistake", maxLines = 3, modifier = Modifier.padding(top = 4.dp))
        }
        Gap(6.dp)
        Label(stringResource(R.string.rules_recall), PicoTheme.typography.bodySmall, "rules/recall", color = PicoTheme.colorScheme.labelSecondary, maxLines = 4)
        Label(stringResource(R.string.rules_two_players), PicoTheme.typography.bodySmall, "rules/twoPlayers", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Gap(16.dp)
    }
}

@Composable
private fun RuleTile(title: Int, body: Int, modifier: Modifier, draw: DrawScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(64.dp)) { draw() }
        Column(Modifier.padding(start = 10.dp)) {
            Label(stringResource(title), PicoTheme.typography.titleSmall, "rules/tile", color = GoColors.CardInk)
            Label(stringResource(body), PicoTheme.typography.bodySmall, "rules/tileBody", color = GoColors.CardInk, maxLines = 4)
        }
    }
}

// Tiny 5 x 5 diagrams for the rule tiles (drawn, no images).
private fun DrawScope.grid() {
    drawRect(GoColors.Kaya)
    val step = size.width / 5f
    for (i in 0 until 5) {
        val c = step / 2 + i * step
        drawLine(GoColors.GridInk, Offset(step / 2, c), Offset(size.width - step / 2, c), 1.5f)
        drawLine(GoColors.GridInk, Offset(c, step / 2), Offset(c, size.height - step / 2), 1.5f)
    }
}

private fun DrawScope.at(col: Int, row: Int): Offset { val s = size.width / 5f; return Offset(s / 2 + col * s, s / 2 + row * s) }

private fun DrawScope.stone(col: Int, row: Int, black: Boolean, alpha: Float = 1f) {
    val r = size.width / 5f * 0.42f
    drawCircle(if (black) GoColors.BlackStone.copy(alpha = alpha) else GoColors.WhiteStone.copy(alpha = alpha), r, at(col, row))
    if (!black) drawCircle(GoColors.GridInk.copy(alpha = 0.6f * alpha), r, at(col, row), style = Stroke(1.2f))
}

private fun DrawScope.glow(col: Int, row: Int) = drawCircle(GoColors.LibertyGlow, size.width / 5f * 0.18f, at(col, row))

private fun DrawScope.cross(col: Int, row: Int) {
    val c = at(col, row); val d = size.width / 5f * 0.3f
    drawLine(GoColors.Error, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), 3f)
    drawLine(GoColors.Error, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), 3f)
}

private fun DrawScope.drawLiberties() { grid(); stone(2, 2, true); glow(2, 1); glow(1, 2); glow(3, 2); glow(2, 3) }

private fun DrawScope.drawCapture() { grid(); stone(2, 1, true); stone(1, 2, true); stone(3, 2, true); stone(2, 3, true); stone(2, 2, false, alpha = 0.35f) }

private fun DrawScope.drawKo() { grid(); stone(1, 2, true); stone(2, 1, true); stone(2, 3, true); stone(3, 1, false); stone(3, 3, false); stone(4, 2, false); stone(3, 2, true); cross(2, 2) }

private fun DrawScope.drawCount() {
    grid()
    stone(2, 0, true); stone(2, 1, true); stone(2, 2, true); stone(2, 3, true); stone(2, 4, true)
    stone(3, 0, false); stone(3, 1, false); stone(3, 2, false); stone(3, 3, false); stone(3, 4, false)
    val s = size.width / 5f * 0.32f
    for (r in 0 until 5) {
        for (c in 0..1) { val o = at(c, r); drawRect(GoColors.BlackStone.copy(alpha = 0.7f), Offset(o.x - s / 2, o.y - s / 2), androidx.compose.ui.geometry.Size(s, s)) }
        val o = at(4, r); drawRect(Color.White, Offset(o.x - s / 2, o.y - s / 2), androidx.compose.ui.geometry.Size(s, s))
    }
}
