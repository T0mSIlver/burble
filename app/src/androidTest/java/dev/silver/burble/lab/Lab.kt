package dev.silver.burble.lab

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.silver.burble.BubbleShow
import dev.silver.burble.DebugAudio
import dev.silver.burble.Dictation
import dev.silver.burble.InsertionService
import dev.silver.burble.Output
import dev.silver.burble.Prefs
import dev.silver.burble.WhenIdle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What every device-lab test shares: the device set up the way Test Lab allows (adb's shell, no
 * human), the bubble, the host app's field, and the dictation's states as they pass.
 *
 * Instrumentation arguments (`-e` locally, `--environment-variables` on Test Lab):
 *  - `server on`: the one test that may ([DictationTest.landsInAnotherAppsField]) sends the clip
 *    to Google's server. Off by default: every dictation then gets its text from [DebugAudio].
 */
object Lab {
    const val PKG = "dev.silver.burble"
    const val SERVICE = "$PKG/$PKG.InsertionService"
    private const val TAG = "BurbleLab"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    /** Burble's own context: the tests run in its process. */
    val app: Context get() = instrumentation.targetContext
    /** The host app's package: the test APK's. */
    val hostPkg: String get() = instrumentation.context.packageName

    val serverOn: Boolean get() = InstrumentationRegistry.getArguments().getString("server") == "on"

    init {
        // uiautomator otherwise silences every other accessibility service while it runs, Burble's too.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    }

    val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** Runs [cmd] as adb's shell and returns what it printed. */
    fun shell(cmd: String): String {
        val pfd = automation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * The microphone and notification permissions, Burble set up as after onboarding, its
     * accessibility service on and connected, the screen on and unlocked. [clip] plays
     * clip.wav as the microphone; [answer] is the text, or null to ask the server.
     */
    fun setUp(clip: Boolean = true, answer: String? = "Burble lab") {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("pm grant $PKG android.permission.RECORD_AUDIO")
        shell("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        shell("settings put system accelerometer_rotation 0")
        DebugAudio.set(app, DebugAudio.Mode(clip = clip, answer = answer))
        Prefs.update {
            it.copy(
                onboarded = true, bubbleOn = true, bubbleShow = BubbleShow.ALWAYS, hideKeyboard = true,
                output = Output.INSERT, languagesFromKeyboard = false, languages = listOf("en-US"),
                whenIdle = WhenIdle.SHRINK, idleOpacity = 100,
            )
        }
        enableService()
        if (Dictation.state.value !is Dictation.State.Idle) Dictation.cancel()
        log("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, " +
            "keyboard ${shell("settings get secure default_input_method").trim()}")
    }

    /**
     * As `adb shell settings` turns it on: no restricted-settings prompt, which only guards the
     * Settings switch. Under Test Lab's orchestrator each test starts a new process while the
     * setting still lists the service from the last one, and Android doesn't bind it again
     * (Samsung, OPPO, Motorola): writing the list without it, then with it, makes it bind.
     */
    fun enableService() {
        if (InsertionService.instance != null) return
        val others = shell("settings get secure enabled_accessibility_services").trim()
            .split(':').filter { it.isNotEmpty() && it != "null" && it != SERVICE }
        // Right after an install the first toggle can be lost too; a second one binds it.
        repeat(2) { attempt ->
            shell("settings put secure enabled_accessibility_services ${others.joinToString(":").ifEmpty { "\"\"" }}")
            SystemClock.sleep(500) // two writes at once can reach the system as one
            shell("settings put secure enabled_accessibility_services ${(others + SERVICE).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            if (attempt == 1) waitFor("the accessibility service to connect", 15_000) { InsertionService.instance != null }
            else if (poll(10_000) { InsertionService.instance != null }) return
        }
    }

    fun disableService() {
        val on = shell("settings get secure enabled_accessibility_services").trim()
        val rest = on.split(':').filter { it.isNotEmpty() && it != "null" && it != SERVICE }
        shell("settings put secure enabled_accessibility_services ${rest.joinToString(":").ifEmpty { "\"\"" }}")
        waitFor("the accessibility service to go", 10_000) { InsertionService.instance == null }
    }

    /**
     * Opens the host app's field and puts the cursor in it; the keyboard comes up. [kept]: the
     * host keeps its window through a rotation rather than being recreated.
     */
    fun openHost(kept: Boolean = false): UiObject2 {
        val activity = if (kept) KeptHostActivity::class.java else HostActivity::class.java
        app.startActivity(Intent().setComponent(ComponentName(hostPkg, activity.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val field = device.wait(Until.findObject(hostField()), 10_000) ?: die("the host app's field never showed")
        field.click()
        SystemClock.sleep(1500)
        return field
    }

    fun hostField(): BySelector = By.pkg(hostPkg).clazz("android.widget.EditText")

    /** The text in the host's field, its hint not counted. */
    fun hostText(): String {
        val f = device.wait(Until.findObject(hostField()), 5_000) ?: return ""
        return f.text?.takeIf { it != HostActivity.HINT }.orEmpty()
    }

    /** The bubble's touch area, which carries its action as its description. */
    fun bubble(timeoutMs: Long = 10_000): UiObject2 =
        device.wait(Until.findObject(By.pkg(PKG).desc(app.getString(dev.silver.burble.R.string.overlay_start))), timeoutMs)
            ?: die("no bubble on screen")

    fun bubbleBounds(): Rect = bubble().visibleBounds

    /** Every state the dictation goes through from now, caught as it's set. */
    class States {
        val seen = CopyOnWriteArrayList<Dictation.State>()
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { Dictation.state.collect { seen.add(it) } }
        fun done(timeoutMs: Long = 40_000): Dictation.State.Done {
            waitFor("the dictation's text", timeoutMs) { seen.any { it is Dictation.State.Done || it is Dictation.State.Error } }
            job.cancel()
            val end = seen.first { it is Dictation.State.Done || it is Dictation.State.Error }
            if (end is Dictation.State.Error) die("the dictation failed: ${end.kind} ${end.detail ?: ""}")
            return end as Dictation.State.Done
        }
        fun stop() = job.cancel()
    }

    /** Taps the bubble, as a user starts a dictation, and waits for it to listen. */
    fun tapBubble() {
        bubble().click()
        waitFor("the dictation to start", 5_000) { Dictation.state.value == Dictation.State.Listening }
    }

    /** Asserts the dictation's text went into a field, and that field shows it. */
    fun assertLanded(done: Dictation.State.Done, fieldText: () -> String) {
        log("text: \"${done.text}\", ${done.output}")
        assertTrue("the text went to ${done.output}, not the field", done.output == Output.INSERT)
        waitFor("the text in the field", 5_000) { fieldText().contains(done.text.trim()) }
    }

    fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean) {
        if (!poll(timeoutMs, check)) die("timed out after $timeoutMs ms waiting for $what")
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    /** Fails the test, with a screenshot of how the screen looked. */
    fun die(msg: String): Nothing {
        screenshot(msg.take(40).replace(Regex("[^a-z0-9]+"), "-"))
        throw AssertionError(msg)
    }

    /** Saved where testlab.sh pulls from: /sdcard/Android/data/dev.silver.burble/files/lab. */
    fun screenshot(name: String) {
        val dir = File(app.getExternalFilesDir(null), "lab").apply { mkdirs() }
        runCatching { device.takeScreenshot(File(dir, "${System.currentTimeMillis()}-$name.png")) }
    }

    fun log(msg: String) = Log.i(TAG, msg)
}
