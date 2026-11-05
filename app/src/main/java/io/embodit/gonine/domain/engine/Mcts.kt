package io.embodit.gonine.domain.engine

import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The three AI levels (design gameSpec.ai). Simulation caps are tuned from the step-0 speed spike. */
enum class AiLevel(val maxSims: Int, val maxMillis: Long, val topRandom: Int, val priors: Boolean) {
    /** 入门: ≤ 400 simulations or 1.0 s, a random pick among the top 3. */
    BEGINNER(400, 1000, 3, false),
    /**
     * 进阶: ≤ 3000 simulations or 2.0 s, the best. (Design 1500; the step-0 spike measured 26 k playouts / s on the
     * emulator and 36 k on the JVM, so the caps were raised to keep the levels apart — see build handoff.)
     */
    INTERMEDIATE(3000, 2000, 1, false),
    /**
     * 挑战: ≤ 12000 simulations or 3.0 s (design 4000, raised after the spike), the best. The design's capture /
     * atari-escape root prior is off: with it, 100 fixed-seed games against the beginner went 58 % (below the
     * intermediate level's 86 %), because pure random playouts already rate most atari moves low.
     */
    CHALLENGE(12000, 3000, 1, false),
}

data class Candidate(val point: Int, val visits: Int, val winrate: Double)

data class SearchResult(
    /** The move chosen for the level (PASS when the pass policy says so). */
    val move: Int,
    /** Root children by visits, most visited first. */
    val ranked: List<Candidate>,
    /** The side to move's estimated chance to win with its best move. */
    val winrate: Double,
    /** Per point, the share of playouts in which black owned it at the end. */
    val blackOwnership: FloatArray,
    val sims: Int,
    val millis: Long,
    val passReason: String? = null,
)

/**
 * UCT Monte Carlo tree search with uniform random playouts that never fill their own eyes or commit
 * suicide (design gameSpec.ai). Each playout runs at most [MAX_PLAYOUT_MOVES] moves or until two passes,
 * then the board is scored by area with [komi]. No evaluation function. [cancelled] is polled between
 * simulations so the search can be cut short (undo, new game, app to background).
 */
class Mcts(seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)
    private val board = FastBoard()
    private val scratch = FastBoard()
    private val owner = ByteArray(Go.SIZE)

    private class Node(val move: Int, val parent: Node?, val mover: Byte) {
        var visits = 0
        var wins = 0.0
        var children: ArrayList<Node>? = null
        var untried: IntArray? = null
        var untriedCount = 0
    }

    fun search(
        pos: Position,
        komi: Double,
        level: AiLevel,
        cancelled: () -> Boolean = { false },
        maxSimsOverride: Int? = null,
        maxMillisOverride: Long? = null,
    ): SearchResult {
        val started = System.nanoTime()
        val maxSims = maxSimsOverride ?: level.maxSims
        val maxNanos = (maxMillisOverride ?: level.maxMillis) * 1_000_000L
        val me = pos.toPlay
        val root = Node(Go.PASS, null, Go.other(me))
        // Root moves obey the full rules (superko); the rest of the tree uses simple ko.
        val rootMoves = pos.legalPoints().filter { !isOwnEye(pos.cells, it, me) }.toIntArray()
        root.untried = rootMoves.copyOf()
        root.untriedCount = rootMoves.size
        val blackOwn = IntArray(Go.SIZE)
        if (rootMoves.isEmpty()) {
            return SearchResult(Go.PASS, emptyList(), 0.5, FloatArray(Go.SIZE) { 0.5f }, 0, 0, "no moves")
        }
        if (level.priors) seedPriors(root, pos, me)
        var sims = 0
        while (sims < maxSims) {
            if ((sims and 15) == 0) {
                if (cancelled()) break
                if (System.nanoTime() - started > maxNanos) break
            }
            board.load(pos.cells, me, koPointOf(pos))
            // Selection
            var node = root
            while (node.untriedCount == 0 && !node.children.isNullOrEmpty()) {
                node = select(node)
                board.play(node.move)
            }
            // Expansion
            if (node.untried == null) initUntried(node)
            if (node.untriedCount > 0) {
                val i = rnd.nextInt(node.untriedCount)
                val arr = node.untried!!
                val m = arr[i]
                arr[i] = arr[--node.untriedCount]
                val child = Node(m, node, board.toPlay)
                (node.children ?: ArrayList<Node>().also { node.children = it }).add(child)
                board.play(m)
                node = child
            }
            // Simulation
            val blackWins = playout(board, komi)
            for (p in 0 until Go.SIZE) if (owner[p] == Go.BLACK) blackOwn[p]++
            // Backpropagation
            var n: Node? = node
            while (n != null) {
                n.visits++
                if ((n.mover == Go.BLACK) == blackWins) n.wins += 1.0
                n = n.parent
            }
            sims++
        }
        val ranked = (root.children ?: arrayListOf())
            .sortedByDescending { it.visits }
            .map { Candidate(it.move, it.visits, if (it.visits == 0) 0.5 else it.wins / it.visits) }
        val ownership = FloatArray(Go.SIZE) { if (sims == 0) 0.5f else blackOwn[it].toFloat() / sims }
        val best = ranked.firstOrNull()
        val winrate = best?.winrate ?: 0.5
        val millis = (System.nanoTime() - started) / 1_000_000
        val pick = when {
            best == null -> Go.PASS
            level.topRandom > 1 -> {
                // A random pick among the top N, ignoring ones far worse than the best (never a blunder by design).
                val pool = ranked.take(level.topRandom).filter { it.visits > 0 && it.winrate >= winrate - 0.25 }
                pool.random(rnd).point
            }
            else -> best.point
        }
        return SearchResult(pick, ranked, winrate, ownership, sims, millis)
    }

    /**
     * The AI's move with the pass policy (design gameSpec.ai.passPolicy): pass when the opponent just passed and
     * the AI is not behind, when every playable point is already settled territory (ownership ≥ 0.9 either
     * way), or once the game is long past a normal 9 x 9 length.
     */
    fun chooseMove(pos: Position, komi: Double, level: AiLevel, cancelled: () -> Boolean = { false }): SearchResult {
        val r = search(pos, komi, level, cancelled)
        if (r.ranked.isEmpty()) return r.copy(move = Go.PASS, passReason = "no moves")
        // The beginner always answers a pass with a pass, so a new player's game can end (B27; critic R4).
        if (pos.passes >= 1 && (r.winrate >= 0.5 || level == AiLevel.BEGINNER)) return r.copy(move = Go.PASS, passReason = "opponent passed, winrate ${"%.2f".format(r.winrate)} level=$level")
        val playable = r.ranked.map { it.point }
        val settled = playable.all { p -> r.blackOwnership[p] >= 0.9f || r.blackOwnership[p] <= 0.1f }
        if (settled && pos.moveNumber >= 20) return r.copy(move = Go.PASS, passReason = "all points settled")
        if (pos.moveNumber >= MAX_GAME_MOVES) return r.copy(move = Go.PASS, passReason = "move cap")
        return r
    }

    /**
     * Dead-stone suggestion for scoring: [sims] playouts from [pos]; a block whose points end up owned by its own
     * colour in less than [threshold] of them is suggested dead. Returns the dead stones.
     */
    fun deadStones(pos: Position, komi: Double, sims: Int = 400, threshold: Float = 0.4f): Set<Int> {
        // Half the playouts start with each colour to move, so the suggestion does not depend on whose turn it was.
        val blackOwn = IntArray(Go.SIZE)
        repeat(sims) { i ->
            board.load(pos.cells, if (i % 2 == 0) Go.BLACK else Go.WHITE, -1)
            playout(board, komi)
            for (p in 0 until Go.SIZE) if (owner[p] == Go.BLACK) blackOwn[p]++
        }
        val dead = HashSet<Int>()
        for (g in Go.groups(pos.cells)) {
            val own = g.stones.map { p -> val b = blackOwn[p].toFloat() / sims; if (g.color == Go.BLACK) b else 1f - b }.average()
            if (own < threshold) dead += g.stones
        }
        return dead
    }

    /** Uniform playouts per second on this device, for the step-0 spike. */
    fun benchmark(millis: Long = 1000): Double {
        val start = System.nanoTime()
        var n = 0
        val empty = ByteArray(Go.SIZE)
        while (System.nanoTime() - start < millis * 1_000_000L) {
            board.load(empty, Go.BLACK)
            playout(board, 7.5)
            n++
        }
        return n / ((System.nanoTime() - start) / 1e9)
    }

    private fun koPointOf(pos: Position): Int {
        val last = pos.lastMove ?: return -1
        if (last.isPass || last.captured.size != 1) return -1
        // The simple-ko point is the captured stone's point if retaking it would capture just the lone stone.
        val p = last.point
        val g = Go.group(pos.cells, p)
        return if (g.stones.size == 1 && g.liberties.size == 1) last.captured[0] else -1
    }

    private fun isOwnEye(cells: ByteArray, p: Int, color: Byte): Boolean {
        scratch.load(cells, color)
        return scratch.isEye(p, color)
    }

    private fun initUntried(node: Node) {
        val color = board.toPlay
        val list = IntArray(Go.SIZE)
        var n = 0
        for (p in 0 until Go.SIZE) if (!board.isEye(p, color) && board.isLegal(p, color)) list[n++] = p
        node.untried = list
        node.untriedCount = n
    }

    private fun select(node: Node): Node {
        val children = node.children!!
        val logN = ln(node.visits.toDouble().coerceAtLeast(1.0))
        var best = children[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in children) {
            val score = if (c.visits == 0) 1e9 else c.wins / c.visits + UCT_C * sqrt(logN / c.visits)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /** Challenge level: captures and atari escapes start with a few virtual wins at the root. */
    private fun seedPriors(root: Node, pos: Position, me: Byte) {
        val enemy = Go.other(me)
        val urgent = HashSet<Int>()
        for (g in Go.groups(pos.cells)) {
            if (g.liberties.size != 1) continue
            val lib = g.liberties[0]
            // Capture an enemy block in atari, or extend our own block out of atari.
            if (g.color == enemy || g.color == me) urgent += lib
        }
        val arr = root.untried ?: return
        val children = ArrayList<Node>()
        var i = 0
        while (i < root.untriedCount) {
            val m = arr[i]
            if (m in urgent) {
                val child = Node(m, root, me)
                child.visits = PRIOR_VISITS
                child.wins = PRIOR_VISITS * 0.7
                children += child
                arr[i] = arr[--root.untriedCount]
            } else i++
        }
        if (children.isNotEmpty()) {
            root.children = children
            root.visits += children.sumOf { it.visits }
        }
    }

    /** Plays [b] out at random; leaves the final owners in [owner]; returns true when black wins. */
    private fun playout(b: FastBoard, komi: Double): Boolean {
        var steps = 0
        while (b.passes < 2 && steps < MAX_PLAYOUT_MOVES) {
            b.play(b.randomMove(rnd))
            steps++
        }
        b.owners(owner)
        return b.areaDiff(owner) - komi > 0
    }

    companion object {
        const val MAX_PLAYOUT_MOVES = 120
        const val MAX_GAME_MOVES = 110
        const val UCT_C = 0.8
        const val PRIOR_VISITS = 8
    }
}
