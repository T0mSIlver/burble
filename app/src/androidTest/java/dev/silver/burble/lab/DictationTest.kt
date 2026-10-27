package dev.silver.burble.lab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.silver.burble.Dictation
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap on the bubble over another app's field, clip.wav as the microphone: the words land in
 * that field, through Burble's accessibility service, whatever the keyboard.
 */
@RunWith(AndroidJUnit4::class)
class DictationTest {

    @After fun tearDown() {
        Lab.device.setOrientationNatural()
        if (Dictation.state.value != Dictation.State.Idle) Dictation.cancel()
    }

    /** The only test that may reach Google's server: with `server on`, one short request. */
    @Test fun landsInAnotherAppsField() {
        Lab.setUp(answer = if (Lab.serverOn) null else "Burble lab one")
        Lab.openHost()
        val states = Lab.States()
        Lab.tapBubble()
        val done = states.done()
        Lab.assertLanded(done) { Lab.hostText() }
        Lab.screenshot("landed")
    }

    /**
     * The phone's own messaging app, its own keyboard up (Samsung Keyboard on a Samsung): the
     * compose box of the default SMS app, or a new contact's name where there is none.
     */
    @Test fun landsInTheStockMessagingApp() {
        Lab.setUp(answer = "Burble lab two")
        val field = openStockField()
        assumeTrue("no stock app with a text field opened", field != null)
        val pkg = field!!.applicationPackage
        Lab.log("stock field in $pkg")
        val states = Lab.States()
        Lab.tapBubble()
        val done = states.done()
        Lab.assertLanded(done) {
            Lab.device.findObjects(By.pkg(pkg).clazz("android.widget.EditText")).joinToString("\n") { it.text.orEmpty() }
        }
        Lab.screenshot("stock-landed")
    }

    /**
     * The screen turns while listening, over an app that keeps its window (as Burble's own Try it
     * does): the dictation, the bubble and the microphone's service carry on, the words land.
     */
    @Test fun survivesRotationMidDictation() {
        Lab.setUp(answer = "Burble lab three")
        Lab.openHost(kept = true)
        rotateWhileListening()
    }

    /**
     * The same over an app that is recreated on rotation, as most apps are: its field is in a new
     * window, which InsertionService.current refuses, so the words go to the clipboard. Found by
     * this suite on the emulator, 2026-10-27; for Tom to decide (RMB-29).
     */
    @Ignore("Burble copies instead of inserting when the app under it is recreated mid-dictation (RMB-29)")
    @Test fun survivesRotationOfARecreatedApp() {
        Lab.setUp(answer = "Burble lab three")
        Lab.openHost()
        rotateWhileListening()
    }

    private fun rotateWhileListening() {
        val states = Lab.States()
        Lab.tapBubble()
        Lab.device.setOrientationLeft()
        SystemClock.sleep(1500)
        Lab.screenshot("rotated-listening")
        val done = states.done()
        Lab.assertLanded(done) { Lab.hostText() }
        // Still on the screen, inside it, after the turn.
        val b = Lab.bubbleBounds()
        val w = Lab.device.displayWidth; val h = Lab.device.displayHeight
        if (b.left < 0 || b.top < 0 || b.right > w || b.bottom > h) Lab.die("bubble off screen after rotation: $b in ${w}x$h")
    }

    private fun openStockField(): UiObject2? {
        val tries = listOf(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:5550100")),
            Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE),
        )
        for (intent in tries) {
            // Started blind: Burble can't see other apps' activities (package visibility).
            try {
                Lab.app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (e: ActivityNotFoundException) {
                continue
            }
            Lab.device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000)
            SystemClock.sleep(1500)
            val pkg = Lab.device.currentPackageName
            if (pkg == null || pkg == Lab.PKG || pkg == Lab.hostPkg || pkg.contains("launcher") || pkg == "android") continue
            val fields = Lab.device.findObjects(By.pkg(pkg).clazz("android.widget.EditText"))
            Lab.log("$pkg: ${fields.size} fields")
            if (fields.isEmpty()) continue
            // The compose box sits below the recipient; a contact's name comes first.
            val field = fields.firstOrNull { it.isFocused } ?: if (intent.action == Intent.ACTION_SENDTO) fields.last() else fields.first()
            field.click()
            SystemClock.sleep(1500)
            Lab.screenshot("stock-field")
            return field
        }
        return null
    }
}
