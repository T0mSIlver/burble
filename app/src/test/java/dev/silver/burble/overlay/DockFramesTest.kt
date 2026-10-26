package dev.silver.burble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.burble.BubbleShape
import dev.silver.burble.WhenIdle
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Full to the idle look and back, every 16 ms on a paused clock, as a 60 Hz screen shows it:
// Dot and Smaller, docked right and left. Frames go to build/dock/, for review.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w480dp-h200dp-xxhdpi")
class DockFramesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frames() {
        val rest = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, dynamic = false) {
                Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    for (whenIdle in listOf(WhenIdle.DOT, WhenIdle.SHRINK)) for (end in listOf(true, false)) {
                        OverlayContent(Look.Idle, BubbleShape.COOKIE_9, 64.dp, alignEnd = end, labelled = false,
                            rest = rest.value, whenIdle = whenIdle, idleOpacity = 0.6f) { _, _ -> }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        for ((name, value) in listOf("dock" to true, "back" to false)) {
            rest.value = value
            for (frame in 0..FRAMES) {
                compose.onRoot().captureRoboImage("build/dock/$name-%03d.png".format(frame))
                compose.mainClock.advanceTimeBy(16)
            }
        }
    }

    private companion object { const val FRAMES = 60 }
}
