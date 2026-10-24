package dev.silver.burble

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.silver.burble.ui.Access
import dev.silver.burble.ui.AccessActions
import dev.silver.burble.ui.Home
import dev.silver.burble.ui.HomeActions
import dev.silver.burble.ui.Onboarding
import dev.silver.burble.ui.Step
import dev.silver.burble.ui.TestAudio
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Every onboarding step and the home screen, light and dark, in the Google blue fallback and on a
// sample wallpaper's dynamic colours. `recordRoborazziDebug` writes app/screenshots/;
// `verifyRoborazziDebug` fails when a screen drifts.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ScreenshotTest(private val wallpaper: Wallpaper?, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun cases() = listOf(null, Wallpaper.Green).flatMap { w -> listOf(arrayOf<Any?>(w, false), arrayOf<Any?>(w, true)) }
    }

    @get:Rule val compose = createComposeRule()

    private val name get() = "${wallpaper?.name?.lowercase() ?: "google"}-${if (dark) "dark" else "light"}"

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme(dark = dark, dynamic = false, colors = wallpaper?.scheme(dark)) { content() }
        }
        compose.waitForIdle()
    }

    private fun capture(screen: String, content: @Composable () -> Unit) {
        themed(content)
        compose.onRoot().captureRoboImage("screenshots/$screen-$name.png")
    }

    private val steps = Step.entries

    private fun step(step: Step, access: Access) = capture("onboarding-${step.name.lowercase()}") {
        Onboarding(access, AccessActions.None, steps, onDone = {}, initial = steps.indexOf(step))
    }

    @Test fun welcome() = step(Step.WELCOME, Access.None)
    @Test fun microphone() = step(Step.MICROPHONE, Access.None)
    @Test fun notifications() = step(Step.NOTIFICATIONS, Access.None)
    @Test fun unlock() = step(Step.UNLOCK, Access.None)
    @Test fun insertion() = step(Step.INSERTION, Access.None)

    // The check mark the user sees on coming back from Settings.
    @Test fun insertionGranted() = capture("onboarding-insertion-granted") {
        Onboarding(Access.All, AccessActions.None, steps, onDone = {}, initial = steps.indexOf(Step.INSERTION))
    }

    private val settings = Settings(onboarded = true)

    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    @Test fun home() = capture("home") {
        Home(settings, Access.All, test = TestAudio.State.Heard("Hello, this is a test of dictation."), actions = HomeActions.None, version = "0.1.0")
    }

    @Test fun homeSetup() = capture("home-setup") {
        Home(
            settings.copy(output = Output.HOLD, shape = BubbleShape.SUNNY),
            Access.None,
            test = TestAudio.State.Idle,
            actions = HomeActions.None,
            version = "0.1.0",
        )
    }

    // Every trigger on: the accessibility button and tile rows offer their Android settings.
    @Config(qualifiers = "w412dp-h1200dp-xxhdpi")
    @Test fun homeTriggers() = capture("home-triggers") {
        Home(settings.copy(triggers = Trigger.entries.toSet()), Access.All, test = TestAudio.State.Sending, actions = HomeActions.None, version = "0.1.0")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun language() {
        themed { Home(settings.copy(language = "fr-FR"), Access.All, TestAudio.State.Idle, HomeActions.None, "0.1.0", languageOpen = true) }
        captureScreenRoboImage("screenshots/home-language-$name.png")
    }
}
