package dev.silver.ramblerbubble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.ramblerbubble.BubbleShape
import dev.silver.ramblerbubble.Dictation.ErrorKind
import dev.silver.ramblerbubble.Wallpaper
import dev.silver.ramblerbubble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Every look of the overlay on one sheet per theme: Google blue (no dynamic colour) and a warm
// wallpaper, light and dark. Written to app/screenshots/overlay/.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1600dp-xxhdpi")
class OverlayScreenshotTest(private val palette: String, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun cases() = listOf("google", "warm").flatMap { p -> listOf(arrayOf<Any>(p, false), arrayOf<Any>(p, true)) }
    }

    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme(
                dark = dark,
                dynamic = false,
                colors = if (palette == "warm") Wallpaper.Warm.scheme(dark) else null,
            ) {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/overlay/$name-$palette-${if (dark) "dark" else "light"}.png")
    }

    @Test fun looks() = capture("looks") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                for (shape in listOf(BubbleShape.COOKIE_9, BubbleShape.SUNNY, BubbleShape.CLOVER_4)) {
                    Look(Look.Idle, shape = shape)
                }
                Look(Look.Finishing)
                Look(Look.Inserted)
            }
            for (level in listOf(0f, 0.4f, 0.9f)) Labelled("level $level") { Look(Look.Listening, level) }
            Look(Look.Copied)
            Look(Look.Held("Remind me to call the dentist on Tuesday morning, and book the car in for its service before the trip."))
            for (kind in ErrorKind.entries) Look(Look.Failed(kind))
        }
    }

    /** The wave over two seconds, a frame every 0.2 s, while the voice rises and falls. */
    @Test fun waveFrames() = capture("wave-frames") {
        WaveStrip(shader = false)
    }

    /** The AGSL wave, to compare with the Canvas one it must match at a glance. */
    @Test fun waveFramesShader() = capture("wave-frames-agsl") {
        WaveStrip(shader = true)
    }

    @Composable
    private fun WaveStrip(shader: Boolean) {
        val c = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..10) {
                val t = i * 0.2f
                val level = listOf(0f, 0.2f, 0.6f, 0.9f, 0.7f, 0.4f, 0.8f, 1f, 0.5f, 0.2f, 0f)[i]
                Labelled("t=${"%.1f".format(t)} level=$level") {
                    Box(Modifier.background(c.surfaceContainerHigh)) {
                        Wave({ level }, { t }, c.primary, Modifier.width(264.dp).height(44.dp), shader = shader)
                    }
                }
            }
        }
    }

    @Composable
    private fun Labelled(label: String, content: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
            Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Look(look: Look, level: Float = 0f, shape: BubbleShape = BubbleShape.COOKIE_9) {
        OverlayContent(look, shape, OverlayActions.None, alignEnd = false) { color, modifier ->
            Wave({ level }, { 0.7f }, color, modifier, shader = true)
        }
    }
}
