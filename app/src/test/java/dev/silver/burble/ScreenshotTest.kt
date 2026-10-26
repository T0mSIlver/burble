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
import dev.silver.burble.ui.ReportScreen
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Both onboarding screens, with the unlock of a file install, and the home screen, light and dark, in the Google blue fallback and on a
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

    private fun step(name: String, access: Access, step: Step, unlockStage: Int = 0) = capture("onboarding-$name") {
        Onboarding(access, AccessActions.None, onDone = {}, initial = step.ordinal, initialUnlockStage = unlockStage)
    }

    @Test fun welcome() = step("welcome", Access.None, Step.WELCOME)
    @Test fun insertion() = step("insertion", Access.None.copy(microphone = true, restricted = false), Step.INSERTION)
    // Installed from a file: Android's unlock, then past the refusal, App info is next.
    @Test fun unlock() = step("unlock", Access.None.copy(microphone = true), Step.INSERTION)
    @Test fun unlockAppInfo() = step("unlock-appinfo", Access.None.copy(microphone = true), Step.INSERTION, unlockStage = 1)

    private val keyboard = listOf("en-US", "fr-FR")
    private val settings = Settings(onboarded = true, dictated = true, languages = listOf("en-US", "fr-FR"))
    private val release = Release(
        version = "0.2.0",
        notes = "- The bubble keeps your words when the screen rotates.\n- Languages: pick several; the first is the main one.\n- Fixes a crash when the network drops mid-dictation.",
        apkUrl = "",
        apkSize = 0,
    )

    // Tall enough for the whole page: top and bottom of home in one image.
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    @Test fun home() = capture("home") {
        Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Available(release), HomeActions.None, "0.1.0")
    }

    // Before the first dictation: the main switch says how.
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    @Test fun homeReady() = capture("home-ready") {
        Home(settings.copy(dictated = false), Access.All, TestAudio.State.Idle, Updater.State.UpToDate, HomeActions.None, "0.1.0")
    }

    @Test fun homeOff() = capture("home-off") {
        Home(settings.copy(bubbleOn = false), Access.All, TestAudio.State.Idle, Updater.State.UpToDate, HomeActions.None, "0.1.0")
    }

    @Test fun homeSetup() = capture("home-setup") {
        Home(settings.copy(shape = BubbleShape.SUNNY), Access.None, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0")
    }

    // On in Settings but stopped, with battery optimisation on: the usual cause.
    @Test fun homeStopped() = capture("home-stopped") {
        Home(settings, Access.All.copy(stopped = true, battery = false), TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0")
    }

    // The idle look in the preview: a dot at 60 %.
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    @Test fun homeUpdating() = capture("home-updating") {
        Home(
            settings.copy(bubbleSize = 80, shape = BubbleShape.CLOVER_4, idleOpacity = 60, whenIdle = WhenIdle.DOT),
            Access.All, TestAudio.State.Idle, Updater.State.Downloading(release, 0.4f), HomeActions.None, "0.1.0", previewIdle = true,
        )
    }

    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    @Test fun report() = capture("report") {
        val draft = Report.Draft(
            phone = "- Burble 0.1.0\n- Google Pixel 9\n- Android 16 (SDK 36)\n- Languages: en-US, fr-FR\n",
            log = "10-26 15:02:11.204 D/Insert: insert fell back: android.widget.EditText in com.example.chat refused the text\n" +
                "10-26 15:02:11.209 D/Dictation: insert fell back: no field had input focus at the start",
        )
        ReportScreen(draft, onCopy = {}, onOpen = {}, onBack = {})
    }

    @Test fun licences() = capture("licences") {
        Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0", licencesOpen = true)
    }

    @Test fun advanced() = capture("advanced") {
        Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0", keyboard, advancedOpen = true)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun languages() {
        themed { Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Idle, HomeActions.None, "0.1.0", keyboard, advancedOpen = true, languagesOpen = true) }
        captureScreenRoboImage("screenshots/home-languages-$name.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun testSheet() {
        themed { Home(settings, Access.All, TestAudio.State.Heard("Hello, this is a test of dictation."), Updater.State.Idle, HomeActions.None, "0.1.0", keyboard, advancedOpen = true, testOpen = true) }
        captureScreenRoboImage("screenshots/home-test-$name.png")
    }

    // Twice the font size: nothing cut, nothing overlapping, the button still on screen.
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    @Test fun homeLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        capture("home-font200") {
            Home(settings, Access.All, TestAudio.State.Idle, Updater.State.Available(release), HomeActions.None, "0.1.0")
        }
    }

    @Test fun unlockLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        step("unlock-font200", Access.None.copy(microphone = true), Step.INSERTION)
    }
}
