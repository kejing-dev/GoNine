package io.embodit.gonine.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.gonine.R
import io.embodit.gonine.ui.AppRoot
import io.embodit.gonine.domain.game.EndReason
import io.embodit.gonine.domain.game.GameState
import io.embodit.gonine.domain.game.Mode
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.platform.GoGraph
import io.embodit.gonine.platform.WindowCtl
import io.embodit.gonine.ui.board.scene.BoardFrameSystem
import io.embodit.gonine.ui.board.scene.BoardLayout
import io.embodit.gonine.ui.board.scene.BoardScene
import io.embodit.gonine.ui.board.scene.BoardSceneHolder
import io.embodit.gonine.ui.board.scene.FrameHooks
import io.embodit.gonine.ui.board.scene.SceneResources
import io.embodit.gonine.ui.board.scene.transform
import io.embodit.gonine.ui.theme.GoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The board volume (default container): the 3D board, bowls and lids, plus three small cards in front of the
 * board (no buttons). It asks for the control window at launch through the level gate, and a pinch anywhere in
 * the volume while the window is closed only reopens it (S10).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { GoGraph.from(context) }
    val game by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    DisposableEffect(navigator) {
        WindowCtl.navigator = navigator
        FrameHooks.onFrame = { WindowCtl.stepGate() }
        if (WindowCtl.isClosed) WindowCtl.openWindow(io.embodit.gonine.domain.window.PanelPlacement.why(io.embodit.gonine.domain.window.PanelPlacement.Entry.LAUNCH))
        onDispose {
            FrameHooks.onFrame = null
            WindowCtl.cancel()
            runCatching { navigator.closeWindowContainer(io.embodit.gonine.platform.Containers.CONTROL, io.embodit.gonine.platform.Containers.CONTROL_TAG) }
        }
    }

    val owner = remember(game.positions.size, game.dead, game.phase) {
        if (game.phase == Phase.SCORING || (game.phase == Phase.RESULT && game.result?.reason == EndReason.COUNTED)) Go.score(game.cells, game.dead).owner else null
    }
    val ui = BoardUiState.of(game, settings.tilt, settings.reducedMotion, owner)

    val handle: (SceneEvent) -> Unit = { e -> onBoardEvent(e, graph) }
    DisposableEffect(Unit) {
        BoardInput.handler = handle
        onDispose { if (BoardInput.handler === handle) BoardInput.handler = null }
    }
    BoardView(ui, game, handle)
}

/** The board's input path; debug commands use it too, so they see exactly what a pinch sees (S10 included). */
object BoardInput {
    @Volatile
    var handler: ((SceneEvent) -> Unit)? = null
}

private fun onBoardEvent(e: SceneEvent, graph: GoGraph) {
    run {
        when (e) {
            is SceneEvent.MoveFinished -> graph.flow.moveFinished(e.id)
            is SceneEvent.Sound -> graph.sounds.play(e.kind)
            else -> {
                // S10: with the control window closed, the first pinch only brings it back.
                if (WindowCtl.isClosed && !WindowCtl.opening) {
                    Log.i(GoGraph.TAG, "TAP consumed: reopen panel")
                    WindowCtl.openWindow(io.embodit.gonine.domain.window.PanelPlacement.why(io.embodit.gonine.domain.window.PanelPlacement.Entry.PINCH_VOLUME))
                    return@run
                }
                when (e) {
                    is SceneEvent.TapPoint -> graph.flow.tapPoint(e.point)
                    SceneEvent.TapBowl, SceneEvent.TapEmpty -> graph.flow.tapBowl()
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun BoardView(state: BoardUiState, game: GameState, onEvent: (SceneEvent) -> Unit) {
    val context = LocalContext.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    val scene = remember { BoardScene { currentOnEvent(it) } }

    DisposableEffect(scene) {
        BoardSceneHolder.scene = scene
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (BoardSceneHolder.scene === scene) BoardSceneHolder.scene = null
            scene.dispose()
        }
    }

    // Meshes, materials, textures and shapes wait for the renderer: create them off the main thread.
    LaunchedEffect(scene) {
        if (scene.hasResources) return@LaunchedEffect
        val t0 = android.os.SystemClock.elapsedRealtime()
        val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources() }
        Log.i(BoardScene.TAG, "SCENE resources in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        scene.provide(resources)
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { tap ->
                    val name = tap.targetEntity?.getName()
                    val point = scene.pointOf(name)
                    Log.i(BoardScene.TAG, "TAP target=$name point=${point?.let { Go.name(it) }}")
                    when {
                        point != null -> currentOnEvent(SceneEvent.TapPoint(point))
                        scene.isBowlOrLid(name) -> currentOnEvent(SceneEvent.TapBowl)
                        else -> currentOnEvent(SceneEvent.TapEmpty)
                    }
                }
            },
        attachments = {
            AttachmentPanel(id = BoardLayout.CARD_SEAT) { AppRoot { SeatCard(game) } }
            AttachmentPanel(id = BoardLayout.CARD_RIGHT) { AppRoot { CountCard(game, right = true) } }
            AttachmentPanel(id = BoardLayout.CARD_LEFT) { AppRoot { CountCard(game, right = false) } }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            for (card in BoardLayout.cards()) {
                attachments.entity(card.id)?.let { e ->
                    e.transform().setPosition(Vector3(card.center.x, card.center.y, card.center.z))
                    // Lean back 30°: the face turns up toward the player's eyes.
                    e.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), Math.toRadians(-BoardLayout.CARD_TILT_DEG.toDouble()).toFloat()))
                    scene.root.addChild(e)
                }
            }
            Log.i(BoardScene.TAG, "SCENE attached")
        },
        update = { content, _ ->
            val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
            if (abs(corner.y) > 0.05f) scene.setRegion(abs(corner.y))
            scene.apply(state)
        },
    )
}

private var loggedDp = false
private var loggedFont = false
private const val CARD_GLYPH_M = 0.018f
private const val CJK_GLYPH_RATIO = 0.88f

/** Size of a card in dp for its physical size, so the 1.8 cm text is really 1.8 cm (D13). */
@Composable
private fun metresToDp(m: Float): Float {
    val conv = LocalPhysicalLengthConverter.current
    val perDp = with(conv) { conv.dpToLength(1.dp, LengthUnit.Meters) }
    if (!loggedDp) { loggedDp = true; Log.i(BoardScene.TAG, "CARD dp→m 1 dp = ${perDp * 1000f} mm (attachment panel)") }
    return if (perDp > 0f) m / perDp else m * 1000f
}

@Composable
private fun Card(widthM: Float, text: String, onTap: (() -> Unit)? = null) {
    val w = metresToDp(widthM)
    val h = metresToDp(BoardLayout.CARD_H)
    // mustPass M5: 1.8 cm glyphs. A CJK glyph is ≈ 0.88 × the font size, so the font is 1.8 / 0.88 = 2.05 cm.
    val font = metresToDp(CARD_GLYPH_M / CJK_GLYPH_RATIO)
    if (!loggedFont) { loggedFont = true; Log.i(BoardScene.TAG, "CARD font ${"%.1f".format(font)} sp, glyph ≈ ${"%.1f".format(CARD_GLYPH_M * 1000)} mm (CJK) / ${"%.1f".format(CARD_GLYPH_M / CJK_GLYPH_RATIO * 0.72f * 1000)} mm (Latin capitals)") }
    Box(
        modifier = Modifier.widthIn(min = w.dp).height(h.dp).clip(RoundedCornerShape((h * 0.3f).dp)).background(GoColors.CardBack).then(if (onTap != null) Modifier.clickable { onTap() } else Modifier).padding(horizontal = (h * 0.3f).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text, color = GoColors.CardInk, fontSize = font.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(BoardScene.TAG, "OVERFLOW card/$text") },
        )
    }
}

/** The seat card doubles as the in-volume way to bring the panel back in front (critic R3: not only inside the panel). */
@Composable
private fun SeatCard(game: GameState) {
    val colorName = stringResource(if (game.toPlay == Go.BLACK) R.string.black else R.string.white)
    val text = when {
        WindowCtl.levelHint -> stringResource(R.string.level_gate)
        WindowCtl.isClosed && !WindowCtl.opening && game.phase != Phase.LOADING -> stringResource(R.string.panel_closed)
        game.phase == Phase.SCORING -> stringResource(R.string.turn_scoring)
        game.phase == Phase.RESULT -> stringResource(R.string.turn_over)
        game.phase == Phase.SETUP || game.phase == Phase.WELCOME || game.phase == Phase.LOADING -> stringResource(R.string.app_name)
        game.isVsAi && game.isAiTurn -> stringResource(R.string.turn_ai)
        game.lastAiPassed -> stringResource(R.string.card_ai_passed)
        game.config.mode == Mode.LOCAL -> stringResource(R.string.turn_handoff, colorName)
        else -> stringResource(R.string.turn_you, colorName)
    }
    Card(BoardLayout.SEAT_CARD_W, text, onTap = {
        Log.i(BoardScene.TAG, "CARD seat tapped: recall panel")
        WindowCtl.recall(io.embodit.gonine.domain.window.PanelPlacement.Entry.SEAT_CARD)
    })
}

@Composable
private fun CountCard(game: GameState, right: Boolean) {
    val owner = if (right) game.rightColor else Go.other(game.rightColor)
    val n = game.position.capturedBy(owner)
    val text = when {
        !game.isVsAi -> stringResource(R.string.card_color_took, stringResource(if (owner == Go.BLACK) R.string.black else R.string.white), n)
        owner == game.humanColor -> stringResource(R.string.card_you_took, n)
        else -> stringResource(R.string.card_it_took, n)
    }
    Card(BoardLayout.COUNT_CARD_W, text)
}
