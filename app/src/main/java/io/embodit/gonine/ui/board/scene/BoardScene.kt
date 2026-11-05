package io.embodit.gonine.ui.board.scene

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.ui.board.BoardUiState
import io.embodit.gonine.ui.board.SceneEvent
import io.embodit.gonine.ui.board.SoundKind
import kotlin.math.PI
import kotlin.math.sin

/**
 * Owns every entity of the board and plays what the game commits:
 *
 * ```
 * root (volume floor, centre)
 * ├ tiltRoot (near top edge of the board, turned by the tilt)
 * │ └ boardRoot: slab, face, 81 point pads, 81 stones, ghost, crosses, last-move dot, hint rings, lights, cubes
 * ├ bowls (+ heap, + the stone floating at the mouth), lids (+ 40 slots each)
 * ├ flyers (stones in flight between a bowl / lid and the board)
 * └ cards (attachment panels, added by BoardView)
 * ```
 *
 * The board is built in slices over several frames (design budget: pads and stones are many); the first
 * frame already shows the slab and both bowls. All calls come from the main thread.
 */
internal class BoardScene(private val onEvent: (SceneEvent) -> Unit) {
    val root: Entity = Entity().apply { setName("goRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var built = false
    private var buildQueue = ArrayDeque<() -> Unit>()
    private var buildStarted = 0L

    private class Holder(val holder: Entity, val model: ModelEntity)

    private val pads = arrayOfNulls<Entity>(Go.SIZE)
    private val stones = arrayOfNulls<Holder>(Go.SIZE)
    private var ghost: Holder? = null
    private var flashCross: Entity? = null
    private val deadCrosses = ArrayList<Entity>()
    private var lastDot: ModelEntity? = null
    private val rings = ArrayList<ModelEntity>()
    private val lights = ArrayList<ModelEntity>()
    private val cubes = arrayOfNulls<ModelEntity>(Go.SIZE)
    private val flyers = ArrayList<ModelEntity>()
    private val bowlHeap = HashMap<Boolean, ModelEntity>()
    private val bowlBody = HashMap<Boolean, ModelEntity>()
    private val mouth = HashMap<Boolean, ModelEntity>()
    private val lidStones = HashMap<Boolean, List<ModelEntity>>()

    private var state = BoardUiState()
    /** What the board currently shows (lags the state while a move is in flight). */
    private val shown = ByteArray(Go.SIZE)
    private var shownLid = intArrayOf(0, 0) // [right, left]
    private var shownGameId = -1
    private var playingAnim = -1
    private var timeline: Timeline? = null
    private var flashUntil = 0f
    private var flashId = -1
    private var cubesShownAt = -1f
    private var clock = 0f
    private var regionHalfY = BoardLayout.VOLUME_H / 2f
    private var appliedTilt = -1

    val hasResources: Boolean get() = res != null
    val isBuilt: Boolean get() = built

    fun provide(resources: SceneResources) {
        if (res == null) res = resources else resources.close()
        startBuild()
    }

    /** Puts the floor of the scene on the volume floor ([halfY] = half the volume height in metres). */
    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f && root.transform().position.y != 0f) return
        regionHalfY = halfY
        root.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    // ---- building ----

    private fun startBuild() {
        val r = res ?: return
        if (built || buildQueue.isNotEmpty()) return
        buildStarted = SystemClock.elapsedRealtime()
        root.transform().setPosition(Vector3(0f, -regionHalfY, 0f))
        root.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        boardRoot.transform().setPosition(Vector3(0f, 0f, -BoardLayout.BOARD / 2f))
        setTilt(state.tilt)
        // First frame: the slab and both bowls (the fallback shapes of S00), then the rest in slices.
        model(r.slab, r.kaya, P3(0f, -BoardLayout.BOARD_THICK / 2f, 0f), "slab", boardRoot)
        for (right in listOf(true, false)) buildBowl(r, right)
        buildQueue += { buildFace(r) }
        for (chunk in (0 until Go.SIZE).chunked(PER_FRAME)) buildQueue += { chunk.forEach { buildPoint(r, it) } }
        buildQueue += { for (right in listOf(true, false)) buildLid(r, right) }
        buildQueue += { buildMarkers(r) }
        buildQueue += { for (p in 0 until Go.SIZE) buildCube(r, p) }
        buildQueue += {
            built = true
            SceneStatus.ready = true
            Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - buildStarted} ms entities=${countEntities()}")
            apply(state, force = true)
        }
        Log.i(TAG, "SCENE build queued slices=${buildQueue.size}")
    }

    private fun countEntities() = Go.SIZE * 3 + 2 * BoardLayout.LID_SLOTS + lights.size + rings.size + flyers.size + deadCrosses.size + 12

    private fun model(mesh: MeshResource, material: Material, at: P3, name: String, parent: Entity): ModelEntity {
        val e = ModelEntity(mesh, material).apply {
            setName(name)
            transform().setPosition(Vector3(at.x, at.y, at.z))
        }
        parent.addChild(e)
        return e
    }

    private fun collider(name: String, shape: ShapeResource, at: P3, parent: Entity, r: SceneResources): Entity {
        val e = Entity().apply {
            setName(name)
            transform().setPosition(Vector3(at.x, at.y, at.z))
            components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
            components.set(InteractableComponent())
        }
        parent.addChild(e)
        return e
    }

    private fun lensScale(e: Entity, k: Float = 1f) {
        e.transform().setScaleVector(Vector3(k, k * BoardLayout.STONE_T / BoardLayout.STONE_D, k))
    }

    private fun buildFace(r: SceneResources) {
        model(r.facePlane, r.face, P3(0f, 0.0006f, 0f), "face", boardRoot).apply {
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        }
    }

    private fun buildPoint(r: SceneResources, p: Int) {
        // The pad is an invisible collider: 0.92 x pitch, 3 mm thin (design input).
        pads[p] = collider("$PAD$p", r.padShape, BoardLayout.pointLocal(p, BoardLayout.POINT_PAD_T / 2f), boardRoot, r)
        val holder = collider("$STONE$p", r.stoneShape, BoardLayout.pointLocal(p, BoardLayout.STONE_REST), boardRoot, r)
        val m = model(r.stone, r.black, P3(0f, 0f, 0f), "stoneModel:$p", holder)
        lensScale(m)
        holder.enabled = false
        stones[p] = Holder(holder, m)
    }

    private fun buildBowl(r: SceneResources, right: Boolean) {
        val c = BoardLayout.bowlCenter(right)
        val holder = collider(if (right) BOWL_R else BOWL_L, r.bowlShape, c, root, r)
        bowlBody[right] = model(r.bowl, r.bowlWood, P3(0f, 0f, 0f), "bowlBody", holder)
        val heap = model(r.heap, r.black, P3(0f, BoardLayout.BOWL_H / 2f, 0f), "heap", holder)
        heap.transform().setScaleVector(Vector3(1f, 0.22f, 1f))
        bowlHeap[right] = heap
        val m = model(r.stone, r.black, BoardLayout.bowlMouth(right), "mouth", root)
        lensScale(m)
        m.enabled = false
        mouth[right] = m
    }

    private fun buildLid(r: SceneResources, right: Boolean) {
        val c = BoardLayout.lidCenter(right)
        val holder = collider(if (right) LID_R else LID_L, r.lidShape, P3(c.x, 0.015f, c.z), root, r)
        model(r.lid, r.lidWood, P3(0f, c.y - 0.015f, 0f), "lidBody", holder)
        model(r.lidHollow, r.bowlWood, P3(0f, BoardLayout.LID_H - 0.015f + 0.001f, 0f), "lidHollow", holder)
        lidStones[right] = (0 until BoardLayout.LID_SLOTS).map { n ->
            model(r.stone, r.white, BoardLayout.lidSlot(right, n), "lid:${if (right) "R" else "L"}:$n", root).apply { lensScale(this); enabled = false }
        }
    }

    private fun buildMarkers(r: SceneResources) {
        val g = collider(GHOST, r.stoneShape, P3(0f, 0f, 0f), boardRoot, r)
        val gm = model(r.stone, r.ghostBlack, P3(0f, 0f, 0f), "ghostModel", g)
        lensScale(gm)
        g.enabled = false
        ghost = Holder(g, gm)
        flashCross = crossEntity(r, "flash")
        repeat(DEAD_CROSSES) { deadCrosses += crossEntity(r, "dead:$it") }
        lastDot = model(r.dot, r.lastDot, P3(0f, 0f, 0f), "lastDot", boardRoot).apply { enabled = false }
        repeat(3) { rings += model(r.ring, r.hintRing, P3(0f, 0f, 0f), "hint:$it", boardRoot).apply { enabled = false } }
        repeat(LIGHTS) { lights += model(r.lightBall, r.light, P3(0f, 0f, 0f), "light:$it", boardRoot).apply { enabled = false } }
        repeat(FLYERS) { flyers += model(r.stone, r.black, P3(0f, 0f, 0f), "flyer:$it", root).apply { lensScale(this); enabled = false } }
    }

    private fun crossEntity(r: SceneResources, name: String): Entity {
        val e = Entity().apply { setName(name) }
        boardRoot.addChild(e)
        for (a in listOf(PI / 4, -PI / 4)) {
            model(r.crossBar, r.cross, P3(0f, 0f, 0f), "$name:bar", e).transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), a.toFloat()))
        }
        e.enabled = false
        return e
    }

    private fun buildCube(r: SceneResources, p: Int) {
        cubes[p] = model(r.cube, r.cubeBlack, BoardLayout.pointLocal(p, BoardLayout.CUBE_T / 2f), "cube:$p", boardRoot).apply { enabled = false }
    }

    // ---- state ----

    fun apply(next: BoardUiState, force: Boolean = false) {
        state = next
        if (next.tilt != appliedTilt) setTilt(next.tilt)
        if (!built) return
        val r = res ?: return
        val anim = next.anim
        when {
            force || next.gameId != shownGameId && timeline == null && anim == null && shownGameId == -1 -> snap(next)
            anim != null && anim.id != playingAnim -> {
                timeline?.finish()
                playMove(next)
            }
            timeline == null && (next.gameId != shownGameId || !next.cells.contentEquals(shown)) -> playDiff(next)
            timeline == null -> syncLids(next)
        }
        shownGameId = next.gameId
        updateMarkers(next, r)
    }

    private fun setTilt(deg: Int) {
        appliedTilt = deg
        val pivot = BoardLayout.tiltPivot(deg)
        tiltRoot.transform().setPosition(Vector3(pivot.x, pivot.y, pivot.z))
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), BoardLayout.tiltRad(deg)))
        Log.i(TAG, "TILT $deg")
    }

    private fun colorMat(c: Byte, r: SceneResources) = if (c == Go.BLACK) r.black else r.white

    private fun setMat(e: ModelEntity, m: Material) {
        e.components[ModelComponent::class.java]?.materials?.set(0, m)
    }

    private fun showStone(p: Int, c: Byte, r: SceneResources) {
        val h = stones[p] ?: return
        if (c == Go.EMPTY) { h.holder.enabled = false; return }
        setMat(h.model, colorMat(c, r))
        h.holder.enabled = true
    }

    /** Everything straight to the state, no flight (first build, restore S16, reduced motion). */
    private fun snap(s: BoardUiState) {
        val r = res ?: return
        timeline = null
        for (p in 0 until Go.SIZE) { shown[p] = s.cells[p]; showStone(p, s.cells[p], r) }
        syncLids(s)
        playingAnim = s.anim?.id ?: playingAnim
        s.anim?.let { onEvent(SceneEvent.MoveFinished(it.id)) }
        Log.i(TAG, "SCENE snap game=${s.gameId} stones=${s.cells.count { it != Go.EMPTY }}")
    }

    private fun lidCount(s: BoardUiState, right: Boolean): Int {
        val owner = if (right) s.rightColor else Go.other(s.rightColor)
        return if (owner == Go.BLACK) s.capturedByBlack else s.capturedByWhite
    }

    private fun syncLids(s: BoardUiState) {
        val r = res ?: return
        for (right in listOf(true, false)) {
            val n = lidCount(s, right)
            val owner = if (right) s.rightColor else Go.other(s.rightColor)
            val mat = colorMat(Go.other(owner), r)
            lidStones[right]?.forEachIndexed { i, e -> if (i < n) { setMat(e, mat); e.enabled = true } else e.enabled = false }
            shownLid[if (right) 0 else 1] = n
        }
        updateHeaps(s)
    }

    private fun updateHeaps(s: BoardUiState) {
        val r = res ?: return
        for (right in listOf(true, false)) {
            val color = if (right) s.rightColor else Go.other(s.rightColor)
            val total = if (color == Go.BLACK) 41 else 40
            val onBoard = shown.count { it == color }
            val lost = if (color == Go.BLACK) s.capturedByWhite else s.capturedByBlack
            val left = (total - onBoard - lost).coerceAtLeast(0)
            val heap = bowlHeap[right] ?: continue
            setMat(heap, colorMat(color, r))
            val tier = when { left > 27 -> 0.30f; left > 13 -> 0.22f; left > 0 -> 0.14f; else -> 0f }
            heap.enabled = tier > 0f
            heap.transform().setScaleVector(Vector3(1f, tier, 1f))
            mouth[right]?.let { setMat(it, colorMat(color, r)) }
        }
    }

    /** A committed move: the stone flies from the bowl mouth to the point, captured stones arc into the lid. */
    private fun playMove(s: BoardUiState) {
        val r = res ?: return
        val anim = s.anim ?: return
        playingAnim = anim.id
        val move = anim.move
        val color = move.color
        val right = color == s.rightColor
        val reduced = s.reducedMotion
        val tilt = s.tilt
        val clips = ArrayList<Clip>()
        val to = BoardLayout.pointScene(move.point, tilt, BoardLayout.STONE_REST)
        val from = BoardLayout.bowlMouth(right)
        val neighbours = Go.NEIGHBORS[move.point].count { shown[it] != Go.EMPTY }
        val placeSound = when { neighbours >= 3 -> SoundKind.PLACE_3; neighbours >= 1 -> SoundKind.PLACE_2; else -> SoundKind.PLACE }
        val placeDur = if (reduced) 0.05f else BoardLayout.PLACE_MS / 1000f
        val flyer = flyers.getOrNull(0)
        clips += Clip(
            start = 0f, duration = placeDur,
            onStart = {
                mouth[right]?.enabled = false
                flyer?.let { setMat(it, colorMat(color, r)); it.enabled = !reduced }
            },
            update = { u ->
                flyer?.let {
                    val e = Easing.inOut(u)
                    val q = BoardLayout.placeArc(from, to)(e)
                    it.transform().setPosition(Vector3(q.x, q.y, q.z))
                    it.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), BoardLayout.tiltRad(tilt) * e))
                }
            },
            onEnd = {
                flyer?.enabled = false
                shown[move.point] = color
                showStone(move.point, color, r)
                onEvent(SceneEvent.Sound(placeSound))
            },
        )
        // Captures: each stone 320 ms into the capturer's lid, 40 ms apart.
        val lidRight = right
        var slot = anim.capturesBefore
        move.captured.forEachIndexed { i, p ->
            val start = placeDur + 0.06f + i * BoardLayout.CAPTURE_STAGGER_MS / 1000f
            val dur = if (reduced) 0.05f else BoardLayout.CAPTURE_MS / 1000f
            val target = BoardLayout.lidSlot(lidRight, slot++)
            val fromP = BoardLayout.pointScene(p, tilt, BoardLayout.STONE_REST)
            val f = flyers.getOrNull(1 + i % (FLYERS - 1))
            val victim = Go.other(color)
            val slotIndex = slot - 1
            clips += Clip(
                start = start, duration = dur,
                onStart = {
                    shown[p] = Go.EMPTY
                    showStone(p, Go.EMPTY, r)
                    f?.let { setMat(it, colorMat(victim, r)); it.enabled = !reduced }
                    if (i == 0) onEvent(SceneEvent.Sound(SoundKind.CAPTURE))
                },
                update = { u ->
                    f?.let {
                        val e = Easing.inOut(u)
                        val q = BoardLayout.captureArc(fromP, target)(e)
                        it.transform().setPosition(Vector3(q.x, q.y, q.z))
                        it.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), BoardLayout.tiltRad(tilt) * (1f - e)))
                    }
                },
                onEnd = {
                    f?.enabled = false
                    lidStones[lidRight]?.getOrNull(slotIndex)?.let { setMat(it, colorMat(victim, r)); it.enabled = true }
                },
            )
        }
        val id = anim.id
        timeline = Timeline(clips, tail = 0.05f) {
            timeline = null
            // Whatever the state says now is the truth (the move is already committed).
            for (p in 0 until Go.SIZE) if (shown[p] != state.cells[p] && state.anim?.id == id) { shown[p] = state.cells[p]; showStone(p, state.cells[p], r) }
            syncLids(state)
            Log.i(TAG, "MOVE played id=$id")
            onEvent(SceneEvent.MoveFinished(id))
        }
        Log.i(TAG, "MOVE play id=$id ${Go.name(move.point)} captures=${move.captured.size} reduced=$reduced")
    }

    /**
     * Any other change (new game with handicap stones, undo, redo, restore): stones that appear fly in from
     * their bowl (or back out of a lid if that lid shrank), stones that leave fly back to their bowl.
     */
    private fun playDiff(s: BoardUiState) {
        val r = res ?: return
        if (s.reducedMotion || shownGameId == -1) { snap(s); return }
        val clips = ArrayList<Clip>()
        val tilt = s.tilt
        var k = 0
        val lidDrop = mutableMapOf(true to (shownLid[0] - lidCount(s, true)).coerceAtLeast(0), false to (shownLid[1] - lidCount(s, false)).coerceAtLeast(0))
        for (p in 0 until Go.SIZE) {
            val before = shown[p]
            val after = s.cells[p]
            if (before == after) continue
            val f = flyers.getOrNull(k % FLYERS) ?: continue
            val start = k * 0.06f
            k++
            if (after != Go.EMPTY) {
                // Back out of the capturer's lid if it gave stones back (undo of a capture), else from the bowl.
                val capRight = Go.other(after) == s.rightColor
                val fromLid = (lidDrop[capRight] ?: 0) > 0
                if (fromLid) lidDrop[capRight] = lidDrop.getValue(capRight) - 1
                val from = if (fromLid) BoardLayout.lidSlot(capRight, shownLid[if (capRight) 0 else 1] - 1 - (lidDrop[capRight] ?: 0)) else BoardLayout.bowlMouth(after == s.rightColor)
                val to = BoardLayout.pointScene(p, tilt, BoardLayout.STONE_REST)
                if (before != Go.EMPTY) { shown[p] = Go.EMPTY; showStone(p, Go.EMPTY, r) }
                clips += Clip(start, BoardLayout.PLACE_MS / 1000f,
                    onStart = { setMat(f, colorMat(after, r)); f.enabled = true },
                    update = { u -> val q = BoardLayout.placeArc(from, to)(Easing.inOut(u)); f.transform().setPosition(Vector3(q.x, q.y, q.z)) },
                    onEnd = { f.enabled = false; shown[p] = after; showStone(p, after, r) })
            } else {
                val from = BoardLayout.pointScene(p, tilt, BoardLayout.STONE_REST)
                val to = BoardLayout.bowlMouth(before == s.rightColor)
                clips += Clip(start, BoardLayout.PLACE_MS / 1000f,
                    onStart = { shown[p] = Go.EMPTY; showStone(p, Go.EMPTY, r); setMat(f, colorMat(before, r)); f.enabled = true },
                    update = { u -> val q = BoardLayout.placeArc(from, to)(Easing.inOut(u)); f.transform().setPosition(Vector3(q.x, q.y, q.z)) },
                    onEnd = { f.enabled = false })
            }
        }
        if (clips.isEmpty()) { syncLids(s); return }
        timeline = Timeline(clips, tail = 0.05f) {
            timeline = null
            if (!state.cells.contentEquals(shown)) playDiff(state) else syncLids(state)
        }
        Log.i(TAG, "SCENE diff stones=$k game=${s.gameId}")
    }

    private fun updateMarkers(s: BoardUiState, r: SceneResources) {
        // Ghost stone (S04): see-through, 1.5 cm above the point.
        val g = ghost
        if (g != null) {
            val p = s.ghost
            if (p != null && s.phase == Phase.GHOST) {
                setMat(g.model, if (s.toPlay == Go.BLACK) r.ghostBlack else r.ghostWhite)
                val at = BoardLayout.pointLocal(p, BoardLayout.STONE_REST + BoardLayout.GHOST_LIFT)
                g.holder.transform().setPosition(Vector3(at.x, at.y, at.z))
                g.holder.setName("$GHOST$p")
                g.holder.enabled = true
            } else g.holder.enabled = false
        }
        // Red cross on an illegal point, 300 ms (S08).
        val f = s.flash
        if (f != null && f.id != flashId) {
            flashId = f.id
            flashUntil = clock + flashSeconds
            flashCross?.let { c ->
                val at = BoardLayout.pointLocal(f.point, BoardLayout.STONE_T + 0.004f)
                c.transform().setPosition(Vector3(at.x, at.y, at.z))
                c.enabled = true
            }
            Log.i(TAG, "FLASH ${Go.name(f.point)}")
        }
        // Last move: a small vermilion dot on top of the stone.
        lastDot?.let { d ->
            val p = s.lastMove
            if (p != null && timeline == null && shown[p] != Go.EMPTY && s.owner == null) {
                val at = BoardLayout.pointLocal(p, BoardLayout.STONE_T + 0.0009f)
                d.transform().setPosition(Vector3(at.x, at.y, at.z))
                d.enabled = true
            } else d.enabled = false
        }
        // Hint rings (S03 hint on / first game).
        rings.forEachIndexed { i, ring ->
            val p = s.suggestions.getOrNull(i)
            if (p != null && s.cells[p] == Go.EMPTY) {
                val at = BoardLayout.pointLocal(p, 0.0015f)
                ring.transform().setPosition(Vector3(at.x, at.y, at.z))
                ring.enabled = true
            } else ring.enabled = false
        }
        // Liberty lights: one warm bead on each liberty of a block in atari.
        lights.forEachIndexed { i, l ->
            val p = s.lights.getOrNull(i)
            if (p != null && timeline == null) {
                val at = BoardLayout.pointLocal(p, BoardLayout.GHOST_LIFT)
                l.transform().setPosition(Vector3(at.x, at.y, at.z))
                l.enabled = true
            } else l.enabled = false
        }
        // Scoring: dead stones see-through with a cross; territory cubes on empty / dead points (S07 / S09).
        var crossIndex = 0
        for (p in 0 until Go.SIZE) {
            val h = stones[p] ?: continue
            val c = shown[p]
            if (c == Go.EMPTY) continue
            val mat = when {
                p in s.dead -> if (c == Go.BLACK) r.ghostBlack else r.ghostWhite
                s.winner != null && s.winner == c && s.owner != null -> if (c == Go.BLACK) r.blackGlow else r.whiteGlow
                else -> colorMat(c, r)
            }
            setMat(h.model, mat)
            if (p in s.dead && crossIndex < deadCrosses.size) {
                val e = deadCrosses[crossIndex++]
                val at = BoardLayout.pointLocal(p, BoardLayout.STONE_T + 0.003f)
                e.transform().setPosition(Vector3(at.x, at.y, at.z))
                e.enabled = true
            }
        }
        for (i in crossIndex until deadCrosses.size) deadCrosses[i].enabled = false
        val owner = s.owner
        if (owner != null) {
            if (cubesShownAt < 0f) {
                cubesShownAt = clock
                onEvent(SceneEvent.Sound(SoundKind.SCORE))
            }
            for (p in 0 until Go.SIZE) {
                val cube = cubes[p] ?: continue
                val own = owner[p]
                val show = own != Go.EMPTY && (shown[p] == Go.EMPTY || p in s.dead)
                if (show) setMat(cube, if (own == Go.BLACK) r.cubeBlack else r.cubeWhite)
                cube.setName(if (show) "cube:$p" else "cubeOff:$p")
            }
            revealCubes()
        } else {
            cubesShownAt = -1f
            cubes.forEach { it?.enabled = false }
        }
        // The stone floating over the bowl of the side to move.
        val waiting = s.phase in setOf(Phase.IDLE, Phase.GHOST, Phase.THINKING) && timeline == null
        for (right in listOf(true, false)) {
            val color = if (right) s.rightColor else Go.other(s.rightColor)
            mouth[right]?.enabled = waiting && s.toPlay == color
        }
    }

    /** Cubes appear row by row over 0.6 s (design S07); reduced motion shows them at once. */
    private fun revealCubes() {
        val owner = state.owner ?: return
        val elapsed = if (state.reducedMotion) 1f else (clock - cubesShownAt) / 0.6f
        for (p in 0 until Go.SIZE) {
            val cube = cubes[p] ?: continue
            val wanted = cube.getName().startsWith("cube:") && owner[p] != Go.EMPTY
            cube.enabled = wanted && elapsed >= Go.row(p) / 9f
        }
    }

    // ---- per frame ----

    fun onFrame(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, 0.1f)
        clock += dt
        if (!built) {
            // Build a slice per frame.
            buildQueue.removeFirstOrNull()?.invoke()
            return
        }
        timeline?.advance(dt)
        if (flashUntil > 0f && clock >= flashUntil) { flashUntil = 0f; flashCross?.enabled = false }
        if (state.owner != null) revealCubes()
        // The waiting stone bobs a little over its bowl; the lights breathe.
        for (right in listOf(true, false)) {
            val m = mouth[right] ?: continue
            if (!m.enabled) continue
            val base = BoardLayout.bowlMouth(right)
            val bob = if (state.reducedMotion) 0f else 0.004f * (0.5f + 0.5f * sin(clock * 3.2f))
            m.transform().setPosition(Vector3(base.x, base.y + bob, base.z))
        }
        if (!state.reducedMotion) {
            val k = 1f + 0.18f * sin(clock * 4f)
            for (l in lights) if (l.enabled) l.transform().setScaleVector(Vector3(k, k, k))
        }
        if (timeline == null && lastTimelineState) { lastTimelineState = false; res?.let { updateMarkers(state, it) } }
        if (timeline != null) lastTimelineState = true
    }

    private var lastTimelineState = false

    /** Which point, if any, an entity name denotes (pads, stones and the ghost all map to their point). */
    fun pointOf(name: String?): Int? {
        if (name == null) return null
        for (prefix in listOf(PAD, STONE, GHOST)) if (name.startsWith(prefix)) return name.removePrefix(prefix).toIntOrNull()
        return null
    }

    fun isBowlOrLid(name: String?) = name == BOWL_R || name == BOWL_L || name == LID_R || name == LID_L

    /** Measured bounds and counts, for the debug dump. */
    fun evidence(): String {
        val visual = runCatching { root.getVisualBounds(root.getParent(), true, true) }.getOrNull()
        return "built=$built tilt=$appliedTilt shownStones=${shown.count { it != Go.EMPTY }} lid=${shownLid.toList()} visualMin=${visual?.min} visualMax=${visual?.max} rootY=${root.transform().position.y}"
    }

    fun dispose() {
        timeline = null
        root.destroy()
        res?.close()
        res = null
    }

    companion object {
        const val TAG = "GoNine"

        /** How long the red cross stays (S08: 300 ms; debug `flash hold` keeps it 3 s so QA can photograph it, D36). */
        @Volatile
        var flashSeconds = 0.3f
        const val PAD = "pt:"
        const val STONE = "st:"
        const val GHOST = "ghost:"
        const val BOWL_R = "bowl:R"
        const val BOWL_L = "bowl:L"
        const val LID_R = "lid:R"
        const val LID_L = "lid:L"
        const val PER_FRAME = 27
        const val LIGHTS = 40
        const val FLYERS = 8
        const val DEAD_CROSSES = 30
    }
}

/** Whether the board finished building (the control window shows S00 until then). */
object SceneStatus {
    var ready by androidx.compose.runtime.mutableStateOf(false)
}

/** The board on screen (at most one), for the ECS system and debug commands. */
internal object BoardSceneHolder {
    @Volatile
    var scene: BoardScene? = null
}
