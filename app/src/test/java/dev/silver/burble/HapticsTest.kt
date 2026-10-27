package dev.silver.burble

import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Every event has a composition on a phone with all primitives, and a plain fallback otherwise;
// the error's thud becomes two low ticks where there's no thud.
class HapticsTest {
    private val all = { _: Int -> true }

    @Test fun everyEventPlaysSomethingOrFallsBack() {
        for (e in Haptic.entries) {
            val beats = Haptics.beats(e, all)
            assertTrue("$e", beats != null || e in listOf(Haptic.DISARMED, Haptic.DROPPED))
            beats?.forEach { assertTrue("$e scale ${it.scale}", it.scale in 0.3f..0.8f) }
        }
        for (e in Haptic.entries - Haptic.DOCKED) assertNotNull("$e", Haptics.fallback(e, sdk = 36))
    }

    @Test fun anErrorWithoutAThudIsTwoLowTicks() {
        assertEquals(listOf(PRIMITIVE_THUD), Haptics.beats(Haptic.ERROR, all)!!.map { it.primitive })
        val ticks = Haptics.beats(Haptic.ERROR) { it != PRIMITIVE_THUD }!!
        assertEquals(listOf(PRIMITIVE_LOW_TICK, PRIMITIVE_LOW_TICK), ticks.map { it.primitive })
    }

    @Test fun aMissingPrimitiveFallsBackToTheConstant() {
        assertNull(Haptics.beats(Haptic.START) { false })
        assertEquals(HapticFeedbackConstants.GESTURE_START, Haptics.fallback(Haptic.START))
    }

    @Test fun androidTwelveHasNoThresholdEffects() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.fallback(Haptic.ARMED, sdk = 31))
        assertNull(Haptics.fallback(Haptic.DISARMED, sdk = 31))
    }
}
