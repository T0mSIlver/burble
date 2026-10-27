package dev.silver.burble.lab

import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.silver.burble.Dictation
import dev.silver.burble.InsertionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What vendors' battery and microphone rules can break: Burble left in the background with the
 * screen off, and the microphone opened by a foreground service from another app's screen.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundTest {

    @After fun tearDown() {
        if (Dictation.state.value != Dictation.State.Idle) Dictation.cancel()
        Lab.shell("input keyevent KEYCODE_WAKEUP")
        Lab.shell("wm dismiss-keyguard")
    }

    /**
     * A minute on the home screen, the screen off, then a dictation. The tests run in Burble's
     * process, so a vendor that kills it ends the run (Test Lab shows a crash); one that only
     * unbinds the accessibility service fails here.
     */
    @Test fun survivesAMinuteInTheBackground() {
        Lab.setUp(answer = "Burble lab four")
        val pid = Process.myPid()
        val service = InsertionService.instance
        Lab.device.pressHome()
        SystemClock.sleep(2000)
        Lab.shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(60_000)
        Lab.shell("input keyevent KEYCODE_WAKEUP")
        Lab.shell("wm dismiss-keyguard")
        SystemClock.sleep(2000)
        assertEquals("Burble's process changed", pid, Process.myPid())
        assertTrue("the accessibility service was stopped", InsertionService.instance != null && InsertionService.connected.value)
        assertTrue("a different service instance: it was restarted", InsertionService.instance === service)
        val bound = Lab.shell("dumpsys accessibility")
        assertTrue("Android no longer lists the service as bound", Lab.SERVICE in bound || "InsertionService" in bound)
        Lab.screenshot("after-a-minute")

        Lab.openHost()
        val states = Lab.States()
        Lab.tapBubble()
        Lab.assertLanded(states.done()) { Lab.hostText() }
    }

    /**
     * The real microphone, opened from a tap on the bubble over another app: the foreground
     * service runs with the microphone type, and Android doesn't silence the recording (what
     * happens to a service started from the background without an exemption). Nothing is sent.
     */
    @Test fun microphoneServiceIsNotSilenced() {
        Lab.setUp(clip = false, answer = "Burble lab mic")
        Lab.openHost()
        Lab.tapBubble()
        SystemClock.sleep(1500)
        val services = Lab.shell("dumpsys activity services ${Lab.PKG}/.MicService")
        Lab.log(services.lines().filter { "isForeground" in it || "foregroundServiceType" in it || "ServiceRecord" in it }.joinToString(" | "))
        assertTrue("MicService isn't a foreground service", "isForeground=true" in services)
        val am = Lab.app.getSystemService(AudioManager::class.java)
        val ours = am.activeRecordingConfigurations.filter { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION }
        Lab.log("recordings: ${ours.map { "session ${it.clientAudioSessionId} silenced=${it.isClientSilenced}" }}")
        assertTrue("no voice recognition recording is active", ours.isNotEmpty())
        assertTrue("Android silenced the recording", ours.none { it.isClientSilenced })
        Lab.screenshot("microphone")
        Dictation.cancel()
    }
}
