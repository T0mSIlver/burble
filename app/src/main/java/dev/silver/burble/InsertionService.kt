package dev.silver.burble

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.silver.burble.overlay.Overlay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The accessibility service: hosts the bubble, remembers the field a dictation started in and
 * the last one the user taps during it, types the text into one of them while it's on screen,
 * and hides the keyboard while dictating. In "only while typing", it shows the bubble while a field has
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
            Prefs.settings.map { it.bubbleOn }.distinctUntilChanged().collect { on ->
                // Switched off mid-dictation: the words so far still land. Text waiting for a
                // field stays on the clipboard, and the keyboard comes back.
                if (!on && Dictation.state.value == Dictation.State.Listening) Dictation.stop()
            }
        }
        // The bubble stays until a dictation ends, so the microphone never runs without it.
        scope.launch {
            combine(Prefs.settings.map { it.bubbleOn }, Dictation.state.map { it.recording }) { on, recording -> on || recording }
                .distinctUntilChanged().collect(::setOverlay)
        }
        scope.launch { Dictation.state.collect(::followDictation) }
        scope.launch { combine(OwnScreens.inFront, OwnScreens.wantsBubble) { a, b -> a && !b }.collect { updateAway() } }
        scope.launch {
            Prefs.settings.map { it.bubbleShow }.distinctUntilChanged().collect { updateAway() }
        }
        updateLock()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopListening()
        _connected.value = false
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** The bubble is going: end the recording there, and deliver what it heard. */
    private fun stopListening() {
        if (Dictation.state.value == Dictation.State.Listening) Dictation.stop()
    }

    override fun onDestroy() {
        stopListening()
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
            visibility.wake()
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
        if (was == locked) return
        if (locked) {
            // The lock screen's password may need the keyboard: never keep it hidden there.
            showKeyboard()
        } else {
            visibility.wake()
            followDictation(Dictation.state.value)
        }
    }

    // ---- snooze ----

    private val visibility = Visibility<FieldKey>()

    /**
     * A field, as compared across nodes: AccessibilityNodeInfo.equals is identity before
     * Android 13, so two nodes for one field never match there.
     */
    private data class FieldKey(val window: Int, val id: String?, val bounds: Rect)

    private fun AccessibilityNodeInfo.key() = FieldKey(windowId, viewIdResourceName, Rect().also(::getBoundsInScreen))

    private fun snooze() {
        visibility.snooze(focusedField()?.key())
        main.removeCallbacks(goAway)
        away = true
    }

    private var away = false
        set(value) { field = value; overlay?.away = value }

    /**
     * Keyed on input focus, not on the keyboard, which a dictation hides. A dictation and its result
     * keep the bubble up; it goes [AWAY_MS] after focus leaves, so
     * moving between fields doesn't flicker it.
     */
    private fun updateAway() {
        main.removeCallbacks(goAway)
        val field = focusedField()
        val mode = Prefs.settings.value.bubbleShow
        visibility.focus(field?.key(), mode)
        if (shouldShow(field)) away = false else if (!away) main.postDelayed(goAway, AWAY_MS)
    }

    private fun shouldShow(field: AccessibilityNodeInfo? = focusedField()) =
        visibility.shows(Dictation.state.value != Dictation.State.Idle, Prefs.settings.value.bubbleShow, field != null,
            ownScreen = OwnScreens.inFront.value && !OwnScreens.wantsBubble.value)

    private val goAway = Runnable { away = true }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not every unlock sends ACTION_USER_PRESENT, but a window always changes when the lock
        // screen goes.
        if (locked) updateLock()
        // Switched off, Burble doesn't follow focus at all.
        if (!Prefs.settings.value.bubbleOn) return
        if (event != null && Dictation.state.value.recording && tappedField(event)) noteTap(event)
        if (Prefs.settings.value.bubbleShow == BubbleShow.ALWAYS && !visibility.snoozed) return
        // Focus moves in bursts (a window opens, then a field takes focus): look once it settles.
        main.removeCallbacks(focusCheck)
        main.postDelayed(focusCheck, 120)
    }

    private val focusCheck = Runnable { updateAway() }

    // ---- the field tapped while dictating ----

    /**
     * The last text field the user tapped while dictating: where they want the words. A tap, not
     * a focus change an app makes on its own; never a password field. The bubble has no text
     * field, so it never counts.
     */
    private var tapped: Target? = null

    /**
     * A tap on a field that hasn't focus only focuses it: Android sends no click. So a focus
     * change counts when a finger went down outside the bubble just before; a click always does.
     */
    private fun tappedField(event: AccessibilityEvent) = when (event.eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> true
        AccessibilityEvent.TYPE_VIEW_FOCUSED ->
            SystemClock.uptimeMillis() - (overlay?.touchedOutsideAt ?: 0L) < TAP_MS
        else -> false
    }

    private fun noteTap(event: AccessibilityEvent) {
        val node = event.source ?: return
        if (!node.isEditable || node.isPassword) return
        tapped = Target(node, event.packageName?.toString(), node.windowId, node.viewIdResourceName,
            Rect().also(node::getBoundsInScreen))
    }

    fun tappedTarget(): Target? = tapped

    fun forgetTapped() {
        tapped = null
    }

    /** The text field with input focus. Never a password field: no words go there, spoken aloud. */
    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }

    override fun onInterrupt() = Unit

    // ---- keyboard ----

    // Hidden while listening and finishing only: back the moment the words land, or go to the
    // clipboard, or fail.
    private fun followDictation(state: Dictation.State) {
        if (state.recording) {
            if (Prefs.settings.value.hideKeyboard) hideKeyboard()
        } else {
            showKeyboard()
        }
        updateAway()
    }

    private fun hideKeyboard() {
        if (keyboardHidden) return
        keyboardWasUp = keyboardShown()
        keyboardWindow = focusedField()?.windowId
        keyboardHidden = softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
    }

    /** Whether the keyboard was up when the dictation hid it, and in which window. */
    private var keyboardWasUp = false
    private var keyboardWindow: Int? = null

    /**
     * The show mode alone doesn't bring the keyboard back: Android waits for the app to ask for
     * it again. A click on [field], the one the words went into, else the field with focus, asks
     * again; never a password field, never one off screen. A field that asked while the
     * keyboard was hidden (tapped during the dictation, or its app came back to the front) is
     * worse: its app takes the keyboard for shown and drops every new request. Taking the
     * window's focus for a frame makes Android tell the app it's hidden, and then the click
     * works. [Dictation] calls this the moment the words land, a few frames before its state
     * changes.
     */
    fun showKeyboard(field: AccessibilityNodeInfo? = null) {
        if (!keyboardHidden) return
        keyboardHidden = false
        val node = (field ?: focusedField())?.takeIf(::canTakeKeyboard)
        if (node == null) {
            Log.d(TAG, "keyboard: no field on screen to bring it back to")
            softKeyboardController.setShowMode(SHOW_MODE_AUTO)
            return
        }
        val show = {
            if (!keyboardHidden) {
                softKeyboardController.setShowMode(SHOW_MODE_AUTO)
                askForKeyboard(node)
                watchKeyboard(node)
            }
        }
        val askedWhileHidden = !keyboardWasUp || tapped != null || node.windowId != keyboardWindow
        if (askedWhileHidden) blipFocus(node, show) else show()
    }

    /**
     * No keyboard [KEYBOARD_WAIT_MS] after the click (none came, or one came and went): takes
     * focus for a frame, once, and clicks again.
     */
    private fun watchKeyboard(node: AccessibilityNodeInfo) {
        main.postDelayed({
            if (keyboardHidden || keyboardShown() || !canTakeKeyboard(node)) return@postDelayed
            Log.d(TAG, "keyboard didn't come back from a click: taking focus for a frame")
            blipFocus(node) { if (!keyboardHidden) askForKeyboard(node) }
        }, KEYBOARD_WAIT_MS)
    }

    /**
     * A click on [node]: its app asks for the keyboard. A web page (Chrome, a WebView) ignores a
     * click on the field it has focused, so its field loses focus and takes it again first.
     */
    private fun askForKeyboard(node: AccessibilityNodeInfo) {
        val web = node.extras.containsKey(CHROME_ROLE)
        if (web) node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        if (web || !node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun canTakeKeyboard(node: AccessibilityNodeInfo) =
        node.refresh() && node.isEditable && !node.isPassword && node.isVisibleToUser

    private fun keyboardShown() = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /**
     * Takes input focus from [node]'s window for a frame, then runs [then] once the window has
     * it back: before that, its app can't ask for the keyboard.
     */
    private fun blipFocus(node: AccessibilityNodeInfo, then: () -> Unit) {
        val wm = getSystemService(WindowManager::class.java)
        val view = View(this)
        var removed = false
        val start = SystemClock.uptimeMillis()
        val back = object : Runnable {
            override fun run() {
                val focused = windows.any { it.id == node.windowId && it.isFocused }
                // A web page reconnects to the keyboard a few frames after its window has focus.
                if (focused) main.postDelayed(then, if (node.extras.containsKey(CHROME_ROLE)) WEB_SETTLE_MS else 0)
                else if (SystemClock.uptimeMillis() - start > BLIP_TIMEOUT_MS) then()
                else main.postDelayed(this, 16)
            }
        }
        val end = Runnable {
            if (removed) return@Runnable
            removed = true
            runCatching { wm.removeView(view) }
            main.post(back)
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener { if (it) main.post(end) }
        runCatching {
            wm.addView(view, WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSPARENT))
        }.onFailure { removed = true; then(); return }
        main.postDelayed(end, BLIP_TIMEOUT_MS)
    }

    // ---- insertion ----

    /** The field a dictation would go into now, with its app; never a password field. */
    fun captureTarget(): Target? {
        val root = rootInActiveWindow ?: return null
        val node = focusedField()?.takeUnless { it.isPassword } ?: return null
        return Target(node, root.packageName?.toString(), node.windowId, node.viewIdResourceName,
            Rect().also(node::getBoundsInScreen))
    }

    /**
     * Types [text] into [target] at its cursor, replacing any selection, or at its end when it
     * no longer has focus. Returns the field, or null when it's gone (its window closed, or its
     * app went to the background), can't be told apart from another, or takes a password.
     */
    fun insert(target: Target, text: String): AccessibilityNodeInfo? {
        val node = current(target) ?: return null
        if (node.isPassword) {
            Log.d(TAG, "insert fell back: a password field")
            return null
        }
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
            return null
        }
        // Park the cursor after the inserted text when the field reports selections.
        val at = start + spaced.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
        })
        return node
    }

    /**
     * [target]'s field as it is now. Apps that redraw their input bar when the keyboard hides
     * (chat apps in Compose do) replace the node a dictation started with, so a stale one is
     * looked for again in the same window of the same app: the one field with the same id, else
     * the one field at the same left and right edges at or below where it was (the bar moves down
     * as the keyboard goes). Two candidates, or none, and the text waits for the user instead: it
     * never goes into a field that merely has focus now.
     */
    private fun current(target: Target): AccessibilityNodeInfo? {
        // Only into the window on screen now: a field in an app the user left gets nothing, now
        // or later; the words go to the clipboard.
        val root = rootInActiveWindow
        if (root == null || root.windowId != target.windowId || root.packageName?.toString() != target.packageName) {
            Log.d(TAG, "insert fell back: ${target.packageName}'s window is no longer in front")
            return null
        }
        val node = target.node
        if (node.refresh() && node.isEditable && node.windowId == target.windowId) return node
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (n.isEditable && !n.isPassword) fields += n
            if (depth < 64) for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val found = findAgain(target.viewId, target.bounds, fields.map { it.viewIdResourceName to Rect().also(it::getBoundsInScreen) })
            ?.let(fields::get)
        Log.d(TAG, if (found == null) "insert fell back: the start field is gone or ambiguous (${fields.size} fields in ${target.packageName})"
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
        /**
         * Which of [fields] (each its view id and bounds) is the start field, by its [viewId] or
         * else its [bounds]; null unless exactly one matches.
         */
        internal fun findAgain(viewId: String?, bounds: Rect, fields: List<Pair<String?, Rect>>): Int? {
            if (viewId != null) {
                val same = fields.indices.filter { fields[it].first == viewId }
                if (same.isNotEmpty()) return same.singleOrNull()
            }
            val below = fields.indices.filter {
                val b = fields[it].second
                b.left == bounds.left && b.right == bounds.right && b.top >= bounds.top
            }
            return below.singleOrNull()
        }

        private const val AWAY_MS = 400L
        private const val TAG = "Insert"
        /** How long after a finger goes down its tap may still move focus. */
        private const val TAP_MS = 1500L
        /** Set on every node of a page in Chrome or a WebView. */
        private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
        /** A keyboard asked for has its window within about 350 ms on an emulator. */
        private const val KEYBOARD_WAIT_MS = 700L
        /** Long enough for a window to get focus, or get it back. */
        private const val BLIP_TIMEOUT_MS = 400L
        private const val WEB_SETTLE_MS = 100L
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
