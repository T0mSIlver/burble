package dev.silver.burble

import dev.silver.burble.ui.theme.Follow
import dev.silver.burble.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The level follower: Gboard's 200 ms ramps as a critically damped spring, and a new target never
// restarts the motion from zero.
class MotionTest {
    private fun timeTo(f: Follow, fraction: Float): Double {
        var ms = 0.0
        while (f.value < fraction) { f.step(1.0); ms += 1 }
        return ms
    }

    @Test fun risesTenToNinetyInAbout190ms() {
        val f = Follow(0f).apply { target = 1f }
        timeTo(f, 0.1f)
        // Counted on from 10 %.
        assertEquals(Motion.LEVEL_RISE_MS, timeTo(f, 0.9f), 2.0)
    }

    @Test fun neverOvershoots() {
        val f = Follow(0f).apply { target = 1f }
        repeat(1000) { f.step(1.0); assertTrue(f.value <= 1f) }
    }

    @Test fun aNewTargetCarriesOnFromWhereItIs() {
        val f = Follow(0f).apply { target = 1f }
        repeat(80) { f.step(1.0) }
        val at = f.value
        f.target = 0.2f
        f.step(1.0)
        // Still rising for a moment at its speed, then turning: no jump, no restart from zero.
        assertTrue(f.value >= at && f.value - at < 0.02f)
    }
}
