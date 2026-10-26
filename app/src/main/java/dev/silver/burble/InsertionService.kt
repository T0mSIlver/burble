package dev.silver.burble

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.silver.burble.overlay.Overlay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The accessibility service: hosts the bubble, remembers the field a dictation started in,
 * types the text into it (or, when it's gone, into the next field the user focuses), and hides
 * the keyboard while dictating. In "only while typing", it shows the bubble while a field has
 * input focus.
 */
class InsertionService : AccessibilityService() {

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var overlay: Overlay? = null
    private var keyboardHidden = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateLock()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
        scope.launch {
            Prefs.settings.map { it.bubbleOn }.distinctUntilChanged().collect(::setOverlay)
        }
        scope.launch { Dictation.state.collect(::followDictation) }
        scope.launch {
            Prefs.settings.map { it.bubbleShow }.distinctUntilChanged().collect { updateAway() }
        }
        updateLock()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _connected.value = false
        if (instance === this) instance = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        showKeyboard()
        setOverlay(false)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    // ---- bubble ----

    /**
     * Hosts the bubble as TYPE_ACCESSIBILITY_OVERLAY: no draw-over-apps permission, its
     * touches are trusted (Android 12 blocks touches passing through other apps' overlays),
     * apps that hide overlays (setHideOverlayWindows) don't hide it, and the windows under
     * it stay visible to [rootInActiveWindow], which insertion needs.
     */
    private fun setOverlay(on: Boolean) {
        if (on && overlay == null) {
            snoozed = false
            // Starts hidden when it should, rather than showing until the delayed hide.
            main.removeCallbacks(goAway)
            away = !shouldShow()
            // This service's own WindowManager: the accessibility overlay type is only granted
            // to its token.
            overlay = Overlay(this, getSystemService(WindowManager::class.java),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, onSnooze = ::snooze).also {
                it.locked = locked
                it.away = away
                it.show()
            }
        } else if (!on) {
            overlay?.hide()
            overlay = null
        }
    }

    private var locked = false
        set(value) { field = value; overlay?.locked = value }

    private fun updateLock() {
        val was = locked
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (was && !locked && snoozed) {
            snoozed = false
            updateAway()
        }
    }

    // ---- snooze ----

    /**
     * Dropped on ✕: hidden until the next unlock, or, in "only while typing", until the next
     * text field: another one, or the same one once focus has left it and come back.
     */
    private var snoozed = false
    private var snoozedIn: FieldKey? = null
    private var focusLeft = false

    /**
     * A field, as compared across nodes: AccessibilityNodeInfo.equals is identity before
     * Android 13, so two nodes for one field never match there.
     */
    private data class FieldKey(val window: Int, val id: String?, val bounds: Rect)

    private fun AccessibilityNodeInfo.key() = FieldKey(windowId, viewIdResourceName, Rect().also(::getBoundsInScreen))

    private fun snooze() {
        snoozed = true
        snoozedIn = focusedField()?.key()
        focusLeft = snoozedIn == null
        main.removeCallbacks(goAway)
        away = true
    }

    private fun wakeOnField(field: AccessibilityNodeInfo?) {
        if (!snoozed || Prefs.settings.value.bubbleShow != BubbleShow.TYPING) return
        when {
            field == null -> focusLeft = true
            focusLeft || field.key() != snoozedIn -> snoozed = false
        }
    }

    private var away = false
        set(value) { field = value; overlay?.away = value }

    /**
     * Keyed on input focus, not on the keyboard, which a dictation hides. A dictation, its result
     * and text waiting for a field keep the bubble up; it goes [AWAY_MS] after focus leaves, so
     * moving between fields doesn't flicker it.
     */
    private fun updateAway() {
        main.removeCallbacks(goAway)
        val field = focusedField()
        wakeOnField(field)
        if (shouldShow(field)) away = false else if (!away) main.postDelayed(goAway, AWAY_MS)
    }

    /** A dictation always shows, snoozed or not: the bubble is all there is to see of it. */
    private fun shouldShow(field: AccessibilityNodeInfo? = focusedField()) =
        Dictation.state.value != Dictation.State.Idle ||
            !snoozed && (Prefs.settings.value.bubbleShow == BubbleShow.ALWAYS || field != null)

    private val goAway = Runnable { away = true }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not every unlock sends ACTION_USER_PRESENT, but a window always changes when the lock
        // screen goes.
        if (locked) updateLock()
        if (Dictation.state.value !is Dictation.State.Ready && Prefs.settings.value.bubbleShow == BubbleShow.ALWAYS && !snoozed) return
        // Focus moves in bursts (a window opens, then a field takes focus): look once it settles.
        main.removeCallbacks(focusCheck)
        main.postDelayed(focusCheck, 120)
    }

    private val focusCheck = Runnable {
        deliverReady()
        updateAway()
    }

    /** Text is ready and a field has focus: that's where the user wants it. */
    private fun deliverReady() {
        val ready = Dictation.state.value as? Dictation.State.Ready ?: return
        val target = captureTarget() ?: return
        if (insert(target, ready.text)) Dictation.readyInserted()
    }

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    override fun onInterrupt() = Unit

    // ---- keyboard ----

    // Hidden from the start of a dictation until its text is in a field, so the keyboard never
    // flashes up in between.
    private fun followDictation(state: Dictation.State) {
        when (state) {
            Dictation.State.Listening, Dictation.State.Finishing ->
                if (Prefs.settings.value.hideKeyboard) hideKeyboard()
            is Dictation.State.Ready -> {
                if (Prefs.settings.value.hideKeyboard) hideKeyboard()
                deliverReady()
            }
            else -> showKeyboard()
        }
        updateAway()
    }

    private fun hideKeyboard() {
        if (keyboardHidden) return
        keyboardHidden = softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
    }

    private fun showKeyboard() {
        if (!keyboardHidden) return
        softKeyboardController.setShowMode(SHOW_MODE_AUTO)
        keyboardHidden = false
    }

    // ---- insertion ----

    /** The field a dictation would go into now, with its app. */
    fun captureTarget(): Target? {
        val root = rootInActiveWindow ?: return null
        val node = focusedField() ?: return null
        return Target(node, root.packageName?.toString(), node.windowId, node.viewIdResourceName,
            Rect().also(node::getBoundsInScreen))
    }

    /**
     * Types [text] into [target] at its cursor, replacing any selection, or at its end when it
     * no longer has focus. False when the field is gone: its window closed, or its app went to
     * the background.
     */
    fun insert(target: Target, text: String): Boolean {
        val node = current(target) ?: return false
        val focused = node.isFocused
        // An empty field reports its hint as its text.
        val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (!focused || start !in 0..existing.length || end !in start..existing.length) {
            start = existing.length
            end = existing.length
        }
        val before = existing.substring(0, start)
        val spaced = if (before.isNotEmpty() && !before.last().isWhitespace() &&
            text.firstOrNull()?.let { it.isLetterOrDigit() } == true) " $text" else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before + spaced + existing.substring(end))
        }
        // Some apps drop the field's focus when the keyboard hides; some fields only take text
        // with it. Focus also leaves the cursor where the words went.
        if (!focused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Log.d(TAG, "insert fell back: ${node.className} in ${target.packageName} refused the text")
            return false
        }
        // Park the cursor after the inserted text when the field reports selections.
        val at = start + spaced.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
        })
        return true
    }

    /**
     * [target]'s field as it is now. Apps that redraw their input bar when the keyboard hides
     * (chat apps in Compose do) replace the node a dictation started with, so a stale one is
     * looked for again in the same window: the field with input focus, else the one with the
     * same id, else the only field, else the nearest one at the same left and right edges at or
     * below where it was (the bar moves down as the keyboard goes).
     */
    private fun current(target: Target): AccessibilityNodeInfo? {
        val node = target.node
        if (node.refresh() && node.isEditable) return node
        val root = rootInActiveWindow
        if (root == null || root.windowId != target.windowId) {
            Log.d(TAG, "insert fell back: ${target.packageName}'s window is no longer in front")
            return null
        }
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (n.isEditable) fields += n
            if (depth < 64) for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val bounds = { n: AccessibilityNodeInfo -> Rect().also(n::getBoundsInScreen) }
        val found = fields.firstOrNull { it.isFocused }
            ?: target.viewId?.let { id -> fields.firstOrNull { it.viewIdResourceName == id } }
            ?: fields.singleOrNull()
            ?: fields.filter { bounds(it).let { b -> b.left == target.bounds.left && b.right == target.bounds.right && b.top >= target.bounds.top } }
                .minByOrNull { bounds(it).top }
        Log.d(TAG, if (found == null) "insert fell back: the start field is gone (${fields.size} fields in ${target.packageName})"
            else "the start field was redrawn; found it again")
        return found
    }

    /** A text field and the app it belongs to, with what finds it again once redrawn. */
    class Target(
        val node: AccessibilityNodeInfo,
        val packageName: String?,
        val windowId: Int,
        val viewId: String?,
        val bounds: Rect,
    )

    companion object {
        private const val AWAY_MS = 400L
        private const val TAG = "Insert"
        private val _connected = MutableStateFlow(false)
        /** True while Android has this service bound, which every background trigger needs. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** The connected service; null when the user hasn't turned it on. */
        @Volatile var instance: InsertionService? = null
            private set

        /** Package of the app whose field has input focus; Rambler gets it as the trigger app. */
        fun focusedApp(): String? = runCatching {
            instance?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.packageName?.toString()
        }.getOrNull()
    }
}
