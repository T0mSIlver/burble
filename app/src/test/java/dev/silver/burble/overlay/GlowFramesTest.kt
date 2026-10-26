package dev.silver.burble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.burble.BubbleShape
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// The wave as a 60 Hz screen shows it while a voice starts and stops: quiet, a voice from 100 ms,
// quiet again from 600 ms, the level updated every 32 ms as the microphone sends it. Frames go to
// build/glow/, and the glow per frame to build/glow/levels.txt, for review.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w200dp-h200dp-xxhdpi")
class GlowFramesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frames() {
        val host = WaveHost(0.0)
        val frame = mutableStateOf(host.frame(0.0))
        compose.setContent {
            AppTheme(dark = false, dynamic = false) {
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    OverlayContent(Look.Listening, BubbleShape.COOKIE_9, 64.dp, alignEnd = false, labelled = false) { color, modifier ->
                        Wave({ frame.value }, color, modifier)
                    }
                }
            }
        }
        val levels = StringBuilder()
        var ms = 0
        while (ms <= 1000) {
            if (ms % 32 == 0) host.feed(if (ms in 100 until 600) 0.9f else 0.2f, ms.toDouble())
            host.step(ms.toDouble())
            if (ms % 16 == 0) {
                frame.value = host.frame(ms.toDouble())
                compose.waitForIdle()
                compose.onRoot().captureRoboImage("build/glow/frame-%04d.png".format(ms))
                levels.append("$ms ${frame.value.glow} ${frame.value.bands}\n")
            }
            ms += 4
        }
        File("build/glow").mkdirs()
        File("build/glow/levels.txt").writeText(levels.toString())
    }
}
