package io.embodit.gonine.domain.engine

import io.embodit.gonine.domain.rules.Go
import kotlin.random.Random

/**
 * A mutable 9 x 9 board for random playouts: simple ko only (the tree root filters superko with the real
 * rules), no history, scratch arrays reused between calls so a playout allocates nothing.
 */
internal class FastBoard {
    val cells = ByteArray(Go.SIZE)
    var toPlay: Byte = Go.BLACK
    var koPoint = -1
    var passes = 0
    var moves = 0

    private val mark = IntArray(Go.SIZE)
    private var stamp = 0
    private val stack = IntArray(Go.SIZE)
    private val captureBuf = IntArray(Go.SIZE)
    private val empties = IntArray(Go.SIZE)

    fun load(source: ByteArray, toPlay: Byte, koPoint: Int = -1) {
        source.copyInto(cells)
        this.toPlay = toPlay
        this.koPoint = koPoint
        passes = 0
        moves = 0
    }

    fun copyFrom(o: FastBoard) {
        o.cells.copyInto(cells)
        toPlay = o.toPlay
        koPoint = o.koPoint
        passes = o.passes
        moves = o.moves
    }

    /** Liberty count of the block at [p], stopping once it reaches [limit]. */
    fun libertiesUpTo(p: Int, limit: Int): Int {
        val color = cells[p]
        stamp++
        if (stamp == Int.MAX_VALUE) { mark.fill(0); stamp = 1 }
        val libStamp = -stamp
        var top = 0
        var libs = 0
        stack[top++] = p
        mark[p] = stamp
        while (top > 0) {
            val q = stack[--top]
            for (n in Go.NEIGHBORS[q]) {
                val v = cells[n]
                if (v == Go.EMPTY) {
                    if (mark[n] != libStamp) { mark[n] = libStamp; libs++; if (libs >= limit) return libs }
                } else if (v == color && mark[n] != stamp) {
                    mark[n] = stamp; stack[top++] = n
                }
            }
        }
        return libs
    }

    /** True for an empty point whose four sides are all [color] (or the edge) and that is not a false eye. */
    fun isEye(p: Int, color: Byte): Boolean {
        if (cells[p] != Go.EMPTY) return false
        for (n in Go.NEIGHBORS[p]) if (cells[n] != color) return false
        val r = Go.row(p)
        val c = Go.col(p)
        var enemyDiag = 0
        var offBoard = 0
        val enemy = Go.other(color)
        for (dr in intArrayOf(-1, 1)) for (dc in intArrayOf(-1, 1)) {
            val rr = r + dr
            val cc = c + dc
            if (rr !in 0 until Go.N || cc !in 0 until Go.N) { offBoard++; continue }
            if (cells[Go.point(rr, cc)] == enemy) enemyDiag++
        }
        return if (offBoard > 0) enemyDiag == 0 else enemyDiag < 2
    }

    /** Would [color] at [p] be legal here (empty, not the simple-ko point, not suicide)? */
    fun isLegal(p: Int, color: Byte): Boolean {
        if (cells[p] != Go.EMPTY || p == koPoint) return false
        val enemy = Go.other(color)
        for (n in Go.NEIGHBORS[p]) {
            val v = cells[n]
            if (v == Go.EMPTY) return true
        }
        // No empty neighbour: legal if it captures something, or joins a friendly block that keeps a liberty.
        cells[p] = color
        var ok = false
        for (n in Go.NEIGHBORS[p]) {
            val v = cells[n]
            if (v == enemy && libertiesUpTo(n, 1) == 0) { ok = true; break }
            if (v == color && libertiesUpTo(n, 1) > 0) { ok = true; break }
        }
        cells[p] = Go.EMPTY
        return ok
    }

    /** Plays a move assumed legal; returns the number of stones captured. */
    fun play(p: Int): Int {
        moves++
        if (p == Go.PASS) { passes++; koPoint = -1; toPlay = Go.other(toPlay); return 0 }
        passes = 0
        val color = toPlay
        val enemy = Go.other(color)
        cells[p] = color
        var captured = 0
        var lastCaptured = -1
        for (n in Go.NEIGHBORS[p]) {
            if (cells[n] == enemy && libertiesUpTo(n, 1) == 0) {
                val k = removeBlock(n)
                captured += k
                lastCaptured = n
            }
        }
        // Simple ko: a single stone captured by a single stone that now has exactly one liberty.
        koPoint = if (captured == 1 && libertiesUpTo(p, 2) == 1 && isLoneStone(p)) lastCaptured else -1
        toPlay = enemy
        return captured
    }

    private fun isLoneStone(p: Int): Boolean {
        val c = cells[p]
        for (n in Go.NEIGHBORS[p]) if (cells[n] == c) return false
        return true
    }

    private fun removeBlock(p: Int): Int {
        val color = cells[p]
        var top = 0
        var count = 0
        captureBuf[top++] = p
        cells[p] = Go.EMPTY
        while (top > 0) {
            val q = captureBuf[--top]
            count++
            for (n in Go.NEIGHBORS[q]) if (cells[n] == color) { cells[n] = Go.EMPTY; captureBuf[top++] = n }
        }
        return count
    }

    /**
     * A random legal move for the side to play that does not fill its own eye; PASS when there is none.
     * With [captureFirst], a move that captures an enemy block in atari is tried first (playout heuristic).
     */
    fun randomMove(rnd: Random): Int {
        var n = 0
        for (p in 0 until Go.SIZE) if (cells[p] == Go.EMPTY) empties[n++] = p
        val color = toPlay
        while (n > 0) {
            val i = rnd.nextInt(n)
            val p = empties[i]
            empties[i] = empties[--n]
            if (!isEye(p, color) && isLegal(p, color)) return p
        }
        return Go.PASS
    }

    /** Area owner of every point after a playout: stone colour, or the only colour an empty point touches. */
    fun owners(out: ByteArray) {
        stamp++
        if (stamp == Int.MAX_VALUE) { mark.fill(0); stamp = 1 }
        for (p in 0 until Go.SIZE) {
            val v = cells[p]
            if (v != Go.EMPTY) { out[p] = v; continue }
            if (mark[p] == stamp) continue
            // Flood the empty region, note which colours it touches, then paint it.
            var top = 0
            var size = 0
            var b = false
            var w = false
            stack[top++] = p
            mark[p] = stamp
            while (top > 0) {
                val q = stack[--top]
                captureBuf[size++] = q
                for (n in Go.NEIGHBORS[q]) {
                    when (cells[n]) {
                        Go.BLACK -> b = true
                        Go.WHITE -> w = true
                        else -> if (mark[n] != stamp) { mark[n] = stamp; stack[top++] = n }
                    }
                }
            }
            val who = if (b && !w) Go.BLACK else if (w && !b) Go.WHITE else Go.EMPTY
            for (i in 0 until size) out[captureBuf[i]] = who
        }
    }

    /** Black's area minus white's, from [owners]. */
    fun areaDiff(owner: ByteArray): Int {
        var d = 0
        for (p in 0 until Go.SIZE) when (owner[p]) { Go.BLACK -> d++; Go.WHITE -> d-- }
        return d
    }
}
