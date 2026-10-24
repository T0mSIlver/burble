package dev.silver.ramblerbubble

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.silver.ramblerbubble.overlay.Overlay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Inserts dictated text into the focused editable field; when there is none,
 * copies it to the clipboard and says so. Also hosts the bubble overlay. The accessibility
 * button is [ButtonService]'s.
 */
class InsertionService : AccessibilityService() {

    private val scope = MainScope()
    private var overlay: OverlayHost? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            insert(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(receiver, IntentFilter(ACTION_INSERT), RECEIVER_NOT_EXPORTED)
        _connected.value = true
        scope.launch {
            Prefs.settings.map { it.triggers }.distinctUntilChanged().collect { triggers ->
                setOverlay(Trigger.BUBBLE in triggers)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _connected.value = false
        scope.cancel()
        setOverlay(false)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    /**
     * Hosts the bubble as TYPE_ACCESSIBILITY_OVERLAY: no draw-over-apps permission, its
     * touches are trusted (Android 12 blocks touches passing through other apps' overlays),
     * apps that hide overlays (setHideOverlayWindows) don't hide it, and the windows under
     * it stay visible to [rootInActiveWindow], which [insert] needs.
     */
    private fun setOverlay(on: Boolean) {
        if (on && overlay == null) {
            overlay = newOverlay().also { it.show() }
        } else if (!on) {
            overlay?.hide()
            overlay = null
        }
    }

    // This service's own WindowManager: the accessibility overlay type is only granted to its token.
    private fun newOverlay(): OverlayHost = Overlay(this, getSystemService(WindowManager::class.java),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun insert(text: String) {
        val root = rootInActiveWindow
        val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && node.isEditable) {
            // An empty field reports its hint as its text.
            val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd
            val (base, at) = if (selStart in 0..existing.length && selStart == selEnd) {
                // Insert at the cursor.
                StringBuilder(existing).insert(selStart, text).toString() to selStart
            } else {
                existing + text to existing.length
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                // Park the cursor after the inserted text when the field reports selections.
                runCatching {
                    val sel = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at + text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at + text.length)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                }
                return
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
        android.widget.Toast.makeText(this,
            getString(R.string.copied, text.takeLast(40)), android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        /** True while Android has this service bound, which every background trigger needs. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        const val ACTION_INSERT = "dev.silver.ramblerbubble.INSERT"
        const val EXTRA_TEXT = "text"

        fun deliver(context: Context, text: String) {
            context.sendBroadcast(Intent(ACTION_INSERT)
                .setPackage(context.packageName).putExtra(EXTRA_TEXT, text))
        }
    }
}

/** The floating overlay as [InsertionService] drives it; overlay/Overlay.kt provides it. */
interface OverlayHost {
    fun show()
    fun hide()
}
