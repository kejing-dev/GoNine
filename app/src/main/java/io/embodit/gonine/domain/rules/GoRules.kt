package io.embodit.gonine.domain.rules

/**
 * 9 x 9 Go rules, pure Kotlin (no Android): legal points, captures, no suicide, positional superko
 * (Tromp-Taylor: after a move the whole board may not repeat any earlier board of this game),
 * passes and area scoring with komi.
 *
 * Points are 0..80, `index = row * 9 + col`; row 0 is the near edge (line "1"), col 0 the left edge ("A").
 * A cell holds [EMPTY], [BLACK] or [WHITE].
 */
object Go {
    const val N = 9
    const val SIZE = N * N
    const val EMPTY: Byte = 0
    const val BLACK: Byte = 1
    const val WHITE: Byte = 2
    const val PASS = -1

    fun other(c: Byte): Byte = if (c == BLACK) WHITE else BLACK
    fun row(p: Int) = p / N
    fun col(p: Int) = p % N
    fun point(row: Int, col: Int) = row * N + col

    /** Neighbour lists, precomputed. */
    val NEIGHBORS: Array<IntArray> = Array(SIZE) { p ->
        val r = row(p)
        val c = col(p)
        buildList {
            if (r > 0) add(p - N)
            if (r < N - 1) add(p + N)
            if (c > 0) add(p - 1)
            if (c < N - 1) add(p + 1)
        }.toIntArray()
    }

    /** Star points: 3-3, 3-7, 7-3, 7-7 and tengen 5-5 (1-based lines). */
    val STARS: IntArray = intArrayOf(point(2, 2), point(2, 6), point(6, 2), point(6, 6), point(4, 4))

    /**
     * Handicap stones, fixed on star points: 2 = the 3-7 / 7-3 diagonal, 3 adds 7-7, 4 = all four corners.
     * (Rows count from the near edge, so 3-7 is row 2 col 6.)
     */
    fun handicapPoints(n: Int): IntArray = when (n) {
        2 -> intArrayOf(point(2, 6), point(6, 2))
        3 -> intArrayOf(point(2, 6), point(6, 2), point(6, 6))
        4 -> intArrayOf(point(2, 2), point(2, 6), point(6, 2), point(6, 6))
        else -> IntArray(0)
    }

    /** "A1" … "J9" (letters skip I like a real Go board); PASS is "pass". */
    fun name(p: Int): String = if (p == PASS) "pass" else "${LETTERS[col(p)]}${row(p) + 1}"

    fun parse(s: String): Int? {
        val t = s.trim().uppercase()
        if (t == "PASS") return PASS
        if (t.length < 2) return null
        val c = LETTERS.indexOf(t[0])
        val r = t.substring(1).toIntOrNull()?.minus(1) ?: return null
        if (c < 0 || r !in 0 until N) return null
        return point(r, c)
    }

    private const val LETTERS = "ABCDEFGHJ"

    // ---- Zobrist ----
    private val ZOBRIST: Array<LongArray> = run {
        val rnd = java.util.Random(0x9e3779b97f4a7c15uL.toLong())
        Array(3) { LongArray(SIZE) { rnd.nextLong() } }
    }

    fun hash(cells: ByteArray): Long {
        var h = 0L
        for (p in 0 until SIZE) if (cells[p] != EMPTY) h = h xor ZOBRIST[cells[p].toInt()][p]
        return h
    }

    fun zobrist(color: Byte, p: Int): Long = ZOBRIST[color.toInt()][p]

    // ---- groups and liberties ----

    /** The block containing [p] (which must hold a stone) and its liberties. */
    fun group(cells: ByteArray, p: Int): Group {
        val color = cells[p]
        val stones = ArrayList<Int>()
        val libs = LinkedHashSet<Int>()
        val seen = BooleanArray(SIZE)
        val stack = IntArray(SIZE)
        var top = 0
        stack[top++] = p
        seen[p] = true
        while (top > 0) {
            val q = stack[--top]
            stones += q
            for (n in NEIGHBORS[q]) {
                val v = cells[n]
                if (v == EMPTY) libs += n
                else if (v == color && !seen[n]) { seen[n] = true; stack[top++] = n }
            }
        }
        return Group(color, stones, libs.toList())
    }

    /** Every block on the board, each once. */
    fun groups(cells: ByteArray): List<Group> {
        val seen = BooleanArray(SIZE)
        val out = ArrayList<Group>()
        for (p in 0 until SIZE) {
            if (cells[p] == EMPTY || seen[p]) continue
            val g = group(cells, p)
            g.stones.forEach { seen[it] = true }
            out += g
        }
        return out
    }

    /**
     * Plays [color] at [p] on a copy of [cells]. Returns the outcome without looking at superko;
     * [Position.play] adds that check.
     */
    fun place(cells: ByteArray, color: Byte, p: Int): Placed {
        if (p !in 0 until SIZE) return Placed.Illegal(IllegalReason.OCCUPIED)
        if (cells[p] != EMPTY) return Placed.Illegal(IllegalReason.OCCUPIED)
        val next = cells.copyOf()
        next[p] = color
        val enemy = other(color)
        val captured = ArrayList<Int>()
        for (n in NEIGHBORS[p]) {
            if (next[n] != enemy) continue
            if (captured.contains(n)) continue
            val g = group(next, n)
            if (g.liberties.isEmpty()) captured += g.stones
        }
        for (q in captured) next[q] = EMPTY
        if (captured.isEmpty() && group(next, p).liberties.isEmpty()) return Placed.Illegal(IllegalReason.SUICIDE)
        return Placed.Ok(next, captured.distinct())
    }

    // ---- scoring ----

    /**
     * Tromp-Taylor area score: each colour's stones plus the empty regions that reach only that colour.
     * [dead] stones are taken off first (they count for the other side through the regions they leave).
     */
    fun score(cells: ByteArray, dead: Set<Int> = emptySet()): AreaScore {
        val board = cells.copyOf()
        for (p in dead) board[p] = EMPTY
        val owner = ByteArray(SIZE)
        var black = 0
        var white = 0
        val seen = BooleanArray(SIZE)
        for (p in 0 until SIZE) {
            when (board[p]) {
                BLACK -> { black++; owner[p] = BLACK }
                WHITE -> { white++; owner[p] = WHITE }
                else -> {
                    if (seen[p]) continue
                    val region = ArrayList<Int>()
                    var touchB = false
                    var touchW = false
                    val stack = ArrayDeque<Int>()
                    stack.add(p); seen[p] = true
                    while (stack.isNotEmpty()) {
                        val q = stack.removeLast()
                        region += q
                        for (n in NEIGHBORS[q]) {
                            when (board[n]) {
                                BLACK -> touchB = true
                                WHITE -> touchW = true
                                else -> if (!seen[n]) { seen[n] = true; stack.add(n) }
                            }
                        }
                    }
                    val who = if (touchB && !touchW) BLACK else if (touchW && !touchB) WHITE else EMPTY
                    for (q in region) owner[q] = who
                    if (who == BLACK) black += region.size else if (who == WHITE) white += region.size
                }
            }
        }
        return AreaScore(black, white, owner)
    }
}

data class Group(val color: Byte, val stones: List<Int>, val liberties: List<Int>)

enum class IllegalReason { OCCUPIED, SUICIDE, KO, SUPERKO, NOT_YOUR_TURN }

sealed interface Placed {
    data class Ok(val cells: ByteArray, val captured: List<Int>) : Placed
    data class Illegal(val reason: IllegalReason) : Placed
}

/** Area totals and, per point, who owns it (EMPTY = neutral / dame). */
class AreaScore(val black: Int, val white: Int, val owner: ByteArray) {
    /** Black minus white minus komi; > 0 means black wins. */
    fun margin(komi: Double): Double = black - white - komi
}

/** One played move or pass, with what it took. */
data class Move(val color: Byte, val point: Int, val captured: List<Int>) {
    val isPass get() = point == Go.PASS
}

/**
 * A position in a game: the board, who plays, capture counts, consecutive passes, and the hash of every
 * board so far (for positional superko). Immutable; [play] returns the next one.
 */
class Position(
    val cells: ByteArray,
    val toPlay: Byte,
    /** Stones captured BY black / BY white. */
    val capturedByBlack: Int,
    val capturedByWhite: Int,
    val passes: Int,
    /** Board hashes in game order, the current board last. */
    val history: List<Long>,
    val moveNumber: Int,
    val lastMove: Move?,
) {
    val hash: Long get() = history.last()

    fun capturedBy(color: Byte) = if (color == Go.BLACK) capturedByBlack else capturedByWhite

    sealed interface Result {
        data class Ok(val position: Position, val move: Move) : Result
        data class Illegal(val reason: IllegalReason) : Result
    }

    fun check(p: Int): IllegalReason? = (play(p) as? Result.Illegal)?.reason

    fun isLegal(p: Int) = p == Go.PASS || play(p) is Result.Ok

    fun play(p: Int): Result {
        if (p == Go.PASS) {
            val move = Move(toPlay, Go.PASS, emptyList())
            return Result.Ok(Position(cells, Go.other(toPlay), capturedByBlack, capturedByWhite, passes + 1, history + hash, moveNumber + 1, move), move)
        }
        return when (val placed = Go.place(cells, toPlay, p)) {
            is Placed.Illegal -> Result.Illegal(placed.reason)
            is Placed.Ok -> {
                val h = Go.hash(placed.cells)
                if (h in history) {
                    // A single stone retaken straight back to the board before the opponent's move: the classic ko.
                    val classic = placed.captured.size == 1 && history.size >= 2 && history[history.size - 2] == h
                    Result.Illegal(if (classic) IllegalReason.KO else IllegalReason.SUPERKO)
                } else {
                    val move = Move(toPlay, p, placed.captured)
                    val n = placed.captured.size
                    Result.Ok(
                        Position(
                            placed.cells, Go.other(toPlay),
                            capturedByBlack + if (toPlay == Go.BLACK) n else 0,
                            capturedByWhite + if (toPlay == Go.WHITE) n else 0,
                            0, history + h, moveNumber + 1, move,
                        ),
                        move,
                    )
                }
            }
        }
    }

    /** The same position with the pass count cleared (scoring was refused:「不对，继续下」). History carries on. */
    fun withPassesReset(): Position = Position(cells, toPlay, capturedByBlack, capturedByWhite, 0, history, moveNumber, lastMove)

    fun legalPoints(): List<Int> = (0 until Go.SIZE).filter { cells[it] == Go.EMPTY && play(it) is Result.Ok }

    companion object {
        fun empty(): Position = start(0)

        /** A fresh game; with [handicap] 2–4 the black stones stand on their star points and white moves first. */
        fun start(handicap: Int): Position {
            val cells = ByteArray(Go.SIZE)
            val h = handicap.takeIf { it in 2..4 } ?: 0
            for (p in Go.handicapPoints(h)) cells[p] = Go.BLACK
            return Position(cells, if (h > 0) Go.WHITE else Go.BLACK, 0, 0, 0, listOf(Go.hash(cells)), 0, null)
        }

        /** A set-up position (tests, presets): board as 9 strings from row 9 (far) down to row 1, `X` black `O` white `.` empty. */
        fun of(rowsFarToNear: List<String>, toPlay: Byte = Go.BLACK): Position {
            require(rowsFarToNear.size == Go.N)
            val cells = ByteArray(Go.SIZE)
            rowsFarToNear.forEachIndexed { i, line ->
                val r = Go.N - 1 - i
                val chars = line.filter { !it.isWhitespace() }
                require(chars.length == Go.N) { "row $line" }
                chars.forEachIndexed { c, ch ->
                    cells[Go.point(r, c)] = when (ch) { 'X', 'x', 'B' -> Go.BLACK; 'O', 'o', 'W' -> Go.WHITE; else -> Go.EMPTY }
                }
            }
            return Position(cells, toPlay, 0, 0, 0, listOf(Go.hash(cells)), 0, null)
        }
    }
}
