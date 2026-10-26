package dev.silver.burble

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.silver.burble.overlay.Look
import dev.silver.burble.overlay.OverlayContent
import dev.silver.burble.overlay.Wave
import dev.silver.burble.overlay.WaveFrame
import dev.silver.burble.ui.Access
import dev.silver.burble.ui.Home
import dev.silver.burble.ui.HomeActions
import dev.silver.burble.ui.TestAudio
import dev.silver.burble.ui.theme.AppTheme
import dev.silver.burble.updater.Updater
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Every bubble shape, picked, renders the settings screen and the bubble in each look without
// crashing. SoftBurst once crashed the app at every launch.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ShapesTest(private val shape: BubbleShape) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = BubbleShape.entries.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun picked() {
        compose.setContent {
            AppTheme(dynamic = false) {
                Home(Settings(shape = shape), Access.All, Updater.State.Idle, HomeActions.None, "0.1.0")
            }
        }
        compose.waitForIdle()
    }

    @Test fun bubble() {
        val looks = listOf(Look.Idle, Look.Listening, Look.Finishing, Look.Done, Look.Copied, Look.Failed(Dictation.ErrorKind.NETWORK, true))
        compose.setContent {
            AppTheme(dynamic = false) {
                Column {
                    for (look in looks) for (size in listOf(40, 64, 96)) {
                        OverlayContent(look, shape, size.dp, alignEnd = false, labelled = true) { color, modifier ->
                            Wave({ WaveFrame.steady(0.6f, 0.3f) }, color, modifier)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
