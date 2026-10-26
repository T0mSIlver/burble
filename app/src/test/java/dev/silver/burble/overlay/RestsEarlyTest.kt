package dev.silver.burble.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A touch or a scroll in the app underneath sends the idle bubble to rest at once; nothing else
// does, and not the bubble's own gesture.
class RestsEarlyTest {
    private val now = 10_000L
    private val longAgo = now - 5_000L

    @Test fun anIdleUntouchedBubbleRestsAtOnce() = assertTrue(restsEarly(idle = true, touching = false, now, longAgo))

    @Test fun neverDuringADictationAResultOrAnError() = assertFalse(restsEarly(idle = false, touching = false, now, longAgo))

    @Test fun neverWithAFingerOnIt() = assertFalse(restsEarly(idle = true, touching = true, now, longAgo))

    @Test fun notTheBubblesOwnGesture() {
        assertFalse(restsEarly(idle = true, touching = false, now, now - 100))
        assertFalse(restsEarly(idle = true, touching = false, now, now - OWN_TOUCH_MS + 1))
        assertTrue(restsEarly(idle = true, touching = false, now, now - OWN_TOUCH_MS))
    }
}
