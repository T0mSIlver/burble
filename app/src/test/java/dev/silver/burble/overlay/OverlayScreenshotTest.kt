package dev.silver.burble.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import dev.silver.burble.BubbleSize
import dev.silver.burble.Dictation.ErrorKind
import dev.silver.burble.Wallpaper
import dev.silver.burble.ui.theme.AppTheme
import dev.silver.burble.ui.theme.LocalWallpaperColors
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

    @Test fun looks() = capture("looks") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Labelled("small, medium, large") {
                for (size in BubbleSize.entries) Bubble(Look.Idle, size = size)
            }
            Labelled("shapes") {
                for (shape in listOf(BubbleShape.COOKIE_9, BubbleShape.SUNNY, BubbleShape.CLOVER_4)) Bubble(Look.Idle, shape = shape)
            }
            Labelled("listening 0, 0.4, 0.9") {
                for (level in listOf(0f, 0.4f, 0.9f)) Bubble(Look.Listening, level = level)
            }
            Labelled("finishing, done, ready") {
                Bubble(Look.Finishing)
                Bubble(Look.Done)
                Bubble(Look.Ready)
            }
            Bubble(Look.Failed(ErrorKind.NETWORK, waits = true), alignEnd = true)
            for (kind in ErrorKind.entries) Bubble(Look.Failed(kind, waits = kind == ErrorKind.NETWORK || kind == ErrorKind.OTHER))
            Labelled("after 4 s: waiting for retry, no label") {
                Bubble(Look.Failed(ErrorKind.NETWORK, waits = true), labelled = false)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                DismissTarget(armed = false)
                DismissTarget(armed = true)
                DismissTarget(armed = true, hint = "Hidden. Bring it back from Quick Settings or the Burble app.")
            }
        }
    }

    /**
     * The wave over 1.2 s at 10 fps, speaking then pausing, as in the Gboard study's motion
     * strip: each row a bar of Gboard's proportions, then the bubble at the same moment.
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
        // Levels after the attack and release smoothing, sampled every 0.1 s.
        val levels = listOf(0.85f, 0.9f, 0.75f, 0.55f, 0.35f, 0.2f, 0.1f, 0.05f, 0.02f, 0.3f, 0.7f, 0.85f)
        val c = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEachIndexed { i, level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 300.dp, height = 45.dp).clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                        .background(c.surfaceContainerLowest)) {
                        Wave({ level }, { i * 0.1f }, c.waveColor(LocalWallpaperColors.current), Modifier.fillMaxSize(), shader = shader)
                    }
                    Bubble(Look.Listening, level = level, time = i * 0.1f, shader = shader)
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
    private fun Bubble(
        look: Look,
        level: Float = 0f,
        time: Float = 0.7f,
        shape: BubbleShape = BubbleShape.COOKIE_9,
        size: BubbleSize = BubbleSize.MEDIUM,
        alignEnd: Boolean = false,
        shader: Boolean = true,
        labelled: Boolean = true,
    ) {
        OverlayContent(look, shape, size, alignEnd, labelled, onTap = {}) { color, modifier ->
            Wave({ level }, { time }, color, modifier, shader = shader)
        }
    }
}
