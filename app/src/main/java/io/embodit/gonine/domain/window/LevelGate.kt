package io.embodit.gonine.domain.window

import kotlin.math.abs

/**
 * Opens a system window only while the head is level (native-window-move.md §7.9, profile D9 / D29): the
 * caller feeds the time and the head pitch every frame (degrees, + up; null = no valid head pose). A request
 * opens once |pitch| ≤ [maxPitchDeg] has held for [dwellMs]; with no head pose for [noHeadMs] (counted from
 * the first frame, not from the request) it opens anyway. Pure Kotlin, unit-tested.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun waitedMs(now: Long) = if (pending == null) 0L else now - askedMs

    /** Returns why to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    fun hintWanted(pitchDeg: Float?) = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
