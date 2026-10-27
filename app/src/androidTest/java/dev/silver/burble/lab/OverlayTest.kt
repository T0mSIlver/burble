package dev.silver.burble.lab

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.silver.burble.Dictation
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bubble over another app: it shows, docks to the edge once idle (2 s untouched, "Smaller"),
 * comes back full size for a dictation, and docks again after it.
 */
@RunWith(AndroidJUnit4::class)
class OverlayTest {

    @After fun tearDown() {
        if (Dictation.state.value != Dictation.State.Idle) Dictation.cancel()
    }

    @Test fun showsDocksAndComesBack() {
        Lab.setUp(answer = "Burble lab dock")
        Lab.openHost()
        val w = Lab.device.displayWidth
        // Idle and untouched: docked, the touch area narrowed against a side edge.
        SystemClock.sleep(3500)
        val docked = Lab.bubbleBounds()
        Lab.screenshot("docked")
        Lab.log("docked at $docked, screen ${w}x${Lab.device.displayHeight}")
        assertTrue("docked bubble not against an edge: $docked", docked.left <= 2 || docked.right >= w - 2)

        val states = Lab.States()
        Lab.tapBubble()
        SystemClock.sleep(800)
        val full = Lab.device.findObject(androidx.test.uiautomator.By.pkg(Lab.PKG).desc(
            Lab.app.getString(dev.silver.burble.R.string.overlay_stop)))?.visibleBounds
            ?: Lab.die("no listening bubble on screen")
        Lab.screenshot("listening")
        Lab.log("listening at $full")
        assertTrue("listening bubble ($full) not bigger than docked ($docked)", full.width() > docked.width())
        Lab.assertLanded(states.done()) { Lab.hostText() }

        SystemClock.sleep(3500)
        val again = Lab.bubbleBounds()
        Lab.screenshot("docked-again")
        assertTrue("not docked again after the dictation: $again", again.width() < full.width() && (again.left <= 2 || again.right >= w - 2))
    }
}
