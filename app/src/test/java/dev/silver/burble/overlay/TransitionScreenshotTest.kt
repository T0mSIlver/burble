package dev.silver.burble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.burble.BubbleShape
import dev.silver.burble.Dictation.ErrorKind
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Each change of look, frame by frame on a paused clock: the bubble keeps its own shape through
// every one (the old build passed the cookie through a circle). Frames go to build/, for review.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h300dp-xxhdpi")
class TransitionScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transitions() {
        val from = listOf(Look.Idle, Look.Listening, Look.Finishing, Look.Listening, Look.Listening)
        val to = listOf(Look.Listening, Look.Finishing, Look.Done, Look.Ready, Look.Failed(ErrorKind.NETWORK, waits = true))
        val looks = mutableStateOf(from)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, dynamic = false) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp)) {
                    for (shader in listOf(true, false)) Row {
                        looks.value.forEach { look ->
                            Box {
                                OverlayContent(look, BubbleShape.COOKIE_9, 64.dp, alignEnd = false,
                                    labelled = false) { color, modifier ->
                                    Wave({ WaveFrame.steady(0.7f, 0.4f) }, color, modifier, shader = shader)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        looks.value = to
        var at = 0L
        for (ms in listOf(0L, 32L, 64L, 100L, 150L, 250L, 400L, 700L)) {
            compose.mainClock.advanceTimeBy(ms - at)
            at = ms
            compose.onRoot().captureRoboImage("build/transitions/frame-%03d.png".format(ms))
        }
    }
}
