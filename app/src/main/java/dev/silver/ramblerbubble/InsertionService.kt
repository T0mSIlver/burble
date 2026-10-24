package dev.silver.ramblerbubble

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts dictated text into the focused editable field; when there is none,
 * copies it to the clipboard and says so.
 */
class InsertionService : AccessibilityService() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            insert(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(receiver, IntentFilter(ACTION_INSERT), RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun insert(text: String) {
        val root = rootInActiveWindow
        val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && node.isEditable) {
            val existing = node.text?.toString() ?: ""
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
        const val ACTION_INSERT = "dev.silver.ramblerbubble.INSERT"
        const val EXTRA_TEXT = "text"

        fun deliver(context: Context, text: String) {
            context.sendBroadcast(Intent(ACTION_INSERT)
                .setPackage(context.packageName).putExtra(EXTRA_TEXT, text))
        }
    }
}
