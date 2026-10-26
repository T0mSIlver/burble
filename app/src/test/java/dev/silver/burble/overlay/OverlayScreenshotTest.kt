package dev.silver.burble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.burble.BubbleShape
import dev.silver.burble.WhenIdle
import dev.silver.burble.Dictation.ErrorKind
import dev.silver.burble.Wallpaper
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Every look of the bubble on one sheet per theme: Google blue (no dynamic colour) and a warm
// wallpaper, light and dark. Written to app/screenshots/overlay/.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1000dp-xxhdpi")
class OverlayScreenshotTest(private val palette: String, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun cases() = listOf("google", "warm").flatMap { p -> listOf(arrayOf<Any>(p, false), arrayOf<Any>(p, true)) }
    }

    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, wallpaper: Wallpaper? = null, content: @Composable () -> Unit) {
        val scheme = wallpaper?.name?.lowercase() ?: palette
        compose.setContent {
            AppTheme(
                dark = dark,
                dynamic = false,
                colors = wallpaper?.scheme(dark) ?: if (palette == "warm") Wallpaper.Warm.scheme(dark) else null,
            ) {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/overlay/$name-$scheme-${if (dark) "dark" else "light"}.png")
    }

    /**
     * Each state over an app's text, where the bubble lives: idle full, smaller and as a dot at
     * 60 %, then listening, finishing, inserted, text ready and an error with its label.
     */
    @Test fun overContent() = capture("over-content") {
        val c = MaterialTheme.colorScheme
        val text = "The meeting moved to Thursday at ten. Can you send the agenda before then, and the notes from last week? " +
            "I'll book the room on the second floor."
        val states: List<Pair<String, @Composable () -> Unit>> = listOf(
            "Idle" to { Bubble(Look.Idle, idleOpacity = 0.6f, alignEnd = true) },
            "Idle, smaller" to { Bubble(Look.Idle, idleOpacity = 0.6f, rest = true, alignEnd = true) },
            "Idle, dot" to { Bubble(Look.Idle, idleOpacity = 0.6f, rest = true, whenIdle = WhenIdle.DOT, alignEnd = true) },
            "Listening" to { Bubble(Look.Listening, level = 0.6f, alignEnd = true) },
            "Finishing" to { Bubble(Look.Finishing, alignEnd = true) },
            "Inserted" to { Bubble(Look.Done, alignEnd = true) },
            "Text ready" to { Bubble(Look.Ready, alignEnd = true) },
            "Error" to { Bubble(Look.Failed(ErrorKind.QUOTA), alignEnd = true) },
        )
        Column(Modifier.width(412.dp).background(c.surface)) {
            for ((label, bubble) in states) Box(Modifier.fillMaxWidth().height(112.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = c.primary)
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, maxLines = 3)
                }
                Box(Modifier.align(Alignment.CenterEnd)) { bubble() }
            }
        }
    }

    @Test fun looks() = capture("looks") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Labelled("40, 64, 96 dp") {
                for (size in listOf(40, 64, 96)) Bubble(Look.Idle, size = size)
            }
            Labelled("idle at 50 %: just touched, shrunk, dot") {
                Bubble(Look.Idle, idleOpacity = 0.5f)
                Bubble(Look.Idle, idleOpacity = 0.5f, rest = true)
                Bubble(Look.Idle, idleOpacity = 0.5f, rest = true, whenIdle = WhenIdle.DOT)
            }
            for (row in BubbleShape.entries.chunked(3)) Labelled("shapes") {
                for (shape in row) Bubble(Look.Idle, shape = shape)
            }
            Labelled("listening: silence, 0.6, 0.9") {
                for (level in listOf(0f, 0.6f, 0.9f)) Bubble(Look.Listening, level = level)
            }
            Labelled("finishing, done, ready") {
                Bubble(Look.Finishing)
                Bubble(Look.Done)
                Bubble(Look.Ready)
            }
            Bubble(Look.Failed(ErrorKind.NETWORK, waits = true), alignEnd = true)
            for (kind in ErrorKind.entries) Bubble(Look.Failed(kind, waits = kind != ErrorKind.NOTHING_HEARD && kind != ErrorKind.NO_MIC))
            Labelled("after 4 s: waiting for retry, no label") {
                Bubble(Look.Failed(ErrorKind.NETWORK, waits = true), labelled = false)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                DismissTarget(armed = false)
                DismissTarget(armed = true)
            }
        }
    }

    /**
     * The wave over 1.2 s at 10 fps, as the live overlay runs it: silence, a word's onset,
     * speech, then the fall after it stops, as in the Gboard study's motion strip.
     */
    @Test fun waveStudy() = capture("wave-study") { WaveStudy(shader = true) }

    /** In a pink wallpaper's colours, as in the owner's Gboard recording, for a side-by-side. */
    @Test fun waveStudyPink() {
        assumeTrue(palette == "google") // one copy per light and dark
        capture("wave-study", Wallpaper.Pink) { WaveStudy(shader = true) }
    }

    /** The Canvas wave of Android 12, to compare with the shader it must match at a glance. */
    @Test fun waveStudyCanvas() = capture("wave-study-canvas") { WaveStudy(shader = false) }

    @Composable
    private fun WaveStudy(shader: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in studyFrames().chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in row) Bubble(Look.Listening, frame = f, shader = shader)
            }
        }
    }

    /** Frames every 0.1 s: a quiet room until 0.3 s, speech until 0.9 s, then quiet again. */
    private fun studyFrames(): List<WaveFrame> {
        val host = WaveHost(0.0)
        val frames = ArrayList<WaveFrame>()
        var ms = 0
        while (ms <= 1200) {
            if (ms % 100 == 0) host.feed(if (ms in 300 until 900) 0.85f else 0.2f, ms.toDouble())
            host.step(ms.toDouble())
            if (ms > 0 && ms % 100 == 0) frames.add(host.frame(ms.toDouble()))
            ms += 4
        }
        return frames
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
    private fun Bubble(
        look: Look,
        level: Float = 0f,
        time: Float = 0.7f,
        frame: WaveFrame? = null,
        shape: BubbleShape = BubbleShape.COOKIE_9,
        size: Int = 64,
        idleOpacity: Float = 1f,
        rest: Boolean = false,
        whenIdle: WhenIdle = WhenIdle.SHRINK,
        alignEnd: Boolean = false,
        shader: Boolean = true,
        labelled: Boolean = true,
    ) {
        OverlayContent(look, shape, size.dp, alignEnd, labelled, rest, whenIdle, idleOpacity, onTap = {}) { color, modifier ->
            Wave({ frame ?: WaveFrame.steady(level, time) }, color, modifier, shader = shader)
        }
    }
}
