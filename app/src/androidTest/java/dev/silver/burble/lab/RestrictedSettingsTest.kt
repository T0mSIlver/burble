package dev.silver.burble.lab

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The restricted-settings unlock a sideloaded install needs on Android 13+, through the vendor's
 * own Settings screens as docs/install.md tells users: the accessibility switch is refused, then
 * App info → ⋮ → Allow restricted settings unlocks it.
 *
 * Test Lab installs over adb, which Android doesn't restrict, so the test puts the restriction
 * back with the app op Android sets for a file install. Where Settings doesn't honour that alone
 * (the switch just works), the test is skipped, not failed. The screenshots and the video are the
 * point: they show each vendor's version of the flow.
 */
@RunWith(AndroidJUnit4::class)
class RestrictedSettingsTest {

    @After fun tearDown() {
        Lab.shell("appops set ${Lab.PKG} ACCESS_RESTRICTED_SETTINGS allow")
        Lab.device.pressBack(); Lab.device.pressBack()
        Lab.device.pressHome()
        Lab.enableService()
    }

    @Test fun unlockThroughAppInfo() {
        assumeTrue("restricted settings start with Android 13", Build.VERSION.SDK_INT >= 33)
        Lab.setUp()
        Lab.disableService()
        Lab.shell("appops set ${Lab.PKG} ACCESS_RESTRICTED_SETTINGS deny")

        // Accessibility → Burble → its switch: refused.
        openBurbleInAccessibility() ?: Lab.die("Burble not found in Accessibility settings")
        Lab.screenshot("restricted-details")
        val switch = Lab.device.wait(Until.findObject(By.checkable(true)), 5_000)
        switch?.click()
        SystemClock.sleep(1500)
        Lab.screenshot("restricted-refused")
        if (Lab.shell("settings get secure enabled_accessibility_services").contains(Lab.SERVICE)) {
            Lab.screenshot("not-restricted")
            assumeTrue("this Settings doesn't restrict an adb install through the app op alone", false)
        }
        Lab.device.findObject(By.text(Pattern.compile("(?i)(OK|Got it|Close)")))?.click()

        // App info → ⋮ → Allow restricted settings.
        Lab.app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Lab.PKG}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val allowPattern = Pattern.compile("(?i)allow restricted settings?")
        val allowText = By.text(allowPattern)
        val more = Lab.device.wait(Until.findObject(By.desc(Pattern.compile("(?i)more options"))), 10_000)
        val allow = if (more != null) {
            more.click()
            Lab.device.wait(Until.findObject(allowText), 5_000).also { Lab.screenshot("app-info-menu") }
                ?: Lab.die("no Allow restricted settings in App info's menu")
        } else {
            // OxygenOS 14 (OnePlus 11, 2026-11-04) showed App info without ⋮ here: the app op
            // alone may not stand in for a file install there. Look down the page, then skip.
            Lab.screenshot("app-info-no-menu")
            findScrolling(allowPattern).also { Lab.screenshot("app-info-bottom") }
        }
        assumeTrue("App info shows no Allow restricted settings for an adb install here", allow != null)
        allow!!.click()
        SystemClock.sleep(1500)
        Lab.screenshot("allowed")
        // Some versions confirm with the screen lock; Test Lab's phones have none.
        val op = Lab.shell("appops get ${Lab.PKG} ACCESS_RESTRICTED_SETTINGS")
        Lab.log("after the unlock: $op")
        assertTrue("restricted settings still denied: $op", "allow" in op)

        // Now the switch takes.
        openBurbleInAccessibility() ?: Lab.die("Burble not found in Accessibility settings after the unlock")
        Lab.device.wait(Until.findObject(By.checkable(true)), 5_000)?.click()
        SystemClock.sleep(1000)
        // Android's own confirmation of a service's full control.
        Lab.device.findObject(By.text(Pattern.compile("(?i)(allow|ok)")))?.click()
        SystemClock.sleep(1500)
        Lab.screenshot("enabled")
        assertTrue("the switch didn't turn the service on after the unlock",
            Lab.shell("settings get secure enabled_accessibility_services").contains(Lab.SERVICE))
    }

    /** Opens Burble's page in Accessibility settings, through the vendor's submenu for downloaded apps if it has one. */
    private fun openBurbleInAccessibility(): UiObject2? {
        Lab.app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(2000)
        findScrolling("Burble")?.let { it.click(); SystemClock.sleep(1500); return it }
        // Samsung: Installed apps; Xiaomi: Downloaded apps; others: Downloaded / Installed services.
        val sub = findScrolling(Pattern.compile("(?i)(installed|downloaded) (apps|services)")) ?: return null
        sub.click(); SystemClock.sleep(1500)
        return findScrolling("Burble")?.also { it.click(); SystemClock.sleep(1500) }
    }

    private fun findScrolling(text: Any): UiObject2? {
        val sel = if (text is Pattern) By.text(text) else By.text(text as String)
        repeat(6) {
            Lab.device.findObject(sel)?.let { return it }
            Lab.device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
            SystemClock.sleep(500)
        }
        return Lab.device.findObject(sel)
    }
}
