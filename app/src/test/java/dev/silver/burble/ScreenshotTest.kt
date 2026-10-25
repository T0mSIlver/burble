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
import dev.silver.burble.updater.Release
import dev.silver.burble.updater.Updater
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

    // Past the refusal: App info is next.
    @Test fun unlockAppInfo() = capture("onboarding-unlock-appinfo") {
        Onboarding(Access.None, AccessActions.None, steps, onDone = {}, initial = steps.indexOf(Step.UNLOCK), initialUnlockStage = 1)
    }
    @Test fun insertion() = step(Step.INSERTION, Access.None.copy(restricted = false))
    @Test fun battery() = step(Step.BATTERY, Access.None)
    @Test fun tile() = step(Step.TILE, Access.None)

    // The check mark the user sees on coming back from Settings.
    @Test fun insertionGranted() = capture("onboarding-insertion-granted") {
        Onboarding(Access.All, AccessActions.None, steps, onDone = {}, initial = steps.indexOf(Step.INSERTION))
    }

    private val settings = Settings(onboarded = true, languages = listOf("en-US", "fr-FR"))
    private val release = Release(
        version = "0.2.0",
        notes = "- The bubble keeps your words when the screen rotates.\n- Languages: pick several; the first is the main one.\n- Fixes a crash when the network drops mid-dictation.",
        apkUrl = "",
        apkSize = 0,
    )

    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun home() = capture("home") {
        Home(settings, Access.All, TestAudio.State.Heard("Hello, this is a test of dictation."), Updater.State.Available(release), HomeActions.None, "0.1.0")
    }

    @Test fun homeSetup() = capture("home-setup") {
        Home(settings.copy(shape = BubbleShape.SUNNY), Access.None, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0")
    }

    // On in Settings but stopped, with battery optimisation on: the usual cause.
    @Test fun homeStopped() = capture("home-stopped") {
        Home(settings, Access.All.copy(stopped = true, battery = false), TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0")
    }

    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun homeUpdating() = capture("home-updating") {
        Home(
            settings.copy(bubbleSize = 80, shape = BubbleShape.CLOVER_4, idleOpacity = 60, whenIdle = WhenIdle.DOT),
            Access.All, TestAudio.State.Sending, Updater.State.Downloading(release, 0.4f), HomeActions.None, "0.1.0",
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun languages() {
        themed { Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0", languagesOpen = true) }
        captureScreenRoboImage("screenshots/home-languages-$name.png")
    }
}
