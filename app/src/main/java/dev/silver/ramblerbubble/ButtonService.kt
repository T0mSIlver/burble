package dev.silver.ramblerbubble

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility button trigger, as its own service. A service that requests the button
 * (flagRequestAccessibilityButton) can only be turned on through an accessibility shortcut:
 * Android's settings page for it has no plain switch, and turning on the shortcut puts a
 * floating button on screen in gesture navigation. Kept apart, [InsertionService] gets a plain
 * switch and only users who want the button get one.
 *
 * The flag is static in accessibility_button.xml: from Android 11 a service can't change it at
 * runtime, and without it a press turns the service off instead of calling back.
 */
class ButtonService : AccessibilityService() {

    private val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) =
            Dictation.toggle(this@ButtonService)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityButtonController.registerAccessibilityButtonCallback(callback)
    }

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(callback)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
