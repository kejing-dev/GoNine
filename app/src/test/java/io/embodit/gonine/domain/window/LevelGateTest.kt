package io.embodit.gonine.domain.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGateTest {
    @Test fun level_head_opens_after_the_dwell() {
        val g = LevelGate(10f, 500L, 1500L)
        g.ask("launch", 0)
        assertNull(g.step(0, 2f))
        assertNull(g.step(400, 3f))
        assertEquals("launch", g.step(520, 1f))
        assertFalse(g.waiting)
    }

    @Test fun looking_down_waits_and_wants_the_hint() {
        val g = LevelGate(10f, 500L, 1500L)
        g.ask("recall", 0)
        for (t in 0L..10_000L step 100) assertNull(g.step(t, -30f))
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(10_100, 0f))
        assertEquals("recall", g.step(10_700, 0f))
    }

    @Test fun no_head_pose_opens_after_the_timeout_from_the_first_frame() {
        val g = LevelGate(10f, 500L, 1500L)
        g.ask("launch", 0)
        assertNull(g.step(5_000, null)) // first frame late: count from here
        assertNull(g.step(6_000, null))
        assertEquals("launch/nohead", g.step(6_600, null))
    }

    @Test fun looking_down_too_long_opens_anyway_after_max_wait() {
        val g = LevelGate(10f, 500L, 1500L, maxWaitMs = 4000L)
        g.ask("launch", 0)
        for (t in 0L..3900L step 100) assertNull(g.step(t, -25f))
        assertEquals("launch/timeout", g.step(4000, -25f))
    }

    @Test fun nothing_asked_nothing_opens() {
        assertNull(LevelGate().step(1000, 0f))
        assertFalse(LevelGate().hintWanted(-40f))
    }
}
