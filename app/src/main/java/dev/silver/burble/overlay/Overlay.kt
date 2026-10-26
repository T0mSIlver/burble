package dev.silver.burble.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.os.Build
import android.view.HapticFeedbackConstants
import dev.silver.burble.Dictation
import dev.silver.burble.Prefs
import dev.silver.burble.recording
import dev.silver.burble.ui.theme.AppTheme
import dev.silver.burble.BubbleShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bubble: Compose windows outside any activity that render [Dictation]. Drag it anywhere;
 * on release it springs to the nearest side edge and remembers where it sits. While dragging, a
 * ✕ target shows at the bottom: dropping the bubble there cancels a dictation, drops the audio
 * of an error waiting for retry or text waiting for a field, or else hides the bubble until
 * [onSnooze]'s owner shows it again. [windowType] is the host's, TYPE_ACCESSIBILITY_OVERLAY from
 * the accessibility service.
 *
 * Three windows, so that nothing seen ever moves because a window changed size: Android applies a
 * window's new size and its new position on different frames, and a bubble drawn in a window that
 * grows or shrinks jumps for a frame. The bubble's own window has one size, the bubble's, and only
 * ever moves whole; it takes no touches. An invisible window over it takes them, as wide as the
 * bubble or, docked, as the strip by the edge. The error's label has a window of its own beside
 * the bubble.
 *
 * A configuration change (rotation, fold, dark mode) only re-places the windows: the views, their
 * composition and the dictation all carry on.
 */
class Overlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    /** Dropped on ✕ while idle: hidden for now, not turned off. */
    private val onSnooze: () -> Unit,
) {
    private val ui = OverlayUi()

    /** While true (the lock screen is up) nothing shows; a dictation carries on unseen. */
    var locked: Boolean
        get() = ui.locked.value
        set(value) { ui.locked.value = value; touchable() }

    /** While true (no field has focus, in "while typing", or snoozed) the bubble fades out. */
    var away: Boolean
        get() = ui.away.value
        set(value) { ui.away.value = value; touchable() }

    private val density = context.resources.displayMetrics.density
    private var target: ComposeView? = null
    /** The current target's: it shows while true, and leaves on its own animation. */
    private var targetShown = mutableStateOf(false)
    private val armed = mutableStateOf(false)
    private val targetParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Burble dismiss"
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private var bubble: ComposeView? = null
    private var touch: TouchFrame? = null
    private var pill: ComposeView? = null
    private var owner: Owner? = null
    private var scope: CoroutineScope? = null
    private var snap: Job? = null

    private fun overlayParams(title: String, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or flags,
        PixelFormat.TRANSLUCENT,
    ).apply { this.title = title }

    /** The bubble's window: the bubble's size, never touched. Its x and y place all three. */
    private val params = overlayParams("Burble", WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    private val touchParams = overlayParams("Burble touch", 0)
    private val pillParams = overlayParams("Burble label", 0)

    init {
        // The side edge and the centre's share of the usable height, so it survives rotation.
        ui.anchor.value = Anchor(if (prefs.getBoolean(KEY_RIGHT, true)) Edge.RIGHT else Edge.LEFT, prefs.getFloat(KEY_FRACTION, 0.4f))
    }

    val isShown get() = bubble != null

    fun show() {
        if (bubble != null) return
        dropShapeThatHung()
        val o = Owner().also { owner = it }
        scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main + DurationScale(context))
        val view = ComposeView(context).apply {
            owned(o)
            // The touch window speaks for the bubble.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                AppTheme {
                    LiveBubble(ui, onGone = ::removeAll)
                }
            }
        }
        val label = ComposeView(context).apply {
            owned(o)
            setContent { AppTheme { LivePill(ui) } }
        }
        val frame = TouchFrame(context)
        ui.screen.value = screen()
        apply()
        windowManager.addView(view, params)
        windowManager.addView(label, pillParams)
        windowManager.addView(frame, touchParams)
        bubble = view; pill = label; touch = frame
        touchable()
        o.resume()
        scope?.launch {
            Prefs.settings.map { it.shape }.distinctUntilChanged().collect { guard(it, view) }
        }
        // Docking narrows the touch window; size and look change where it lies.
        scope?.launch { snapshotFlow { ui.docked.value }.collect { apply() } }
        scope?.launch { Prefs.settings.collect { apply() } }
        scope?.launch {
            Dictation.state.collect { st -> frame.contentDescription = context.getString(st.look().action()) }
        }
        // Only a label that shows and opens the app takes touches; otherwise touches go through.
        scope?.launch {
            snapshotFlow {
                val kind = (Dictation.state.value as? Dictation.State.Error)?.kind
                (kind == Dictation.ErrorKind.NO_MIC || kind == Dictation.ErrorKind.REJECTED) && ui.labelled.value &&
                    !ui.dragging.value && !ui.moving.value && !ui.locked.value && !ui.away.value && !ui.closing.value
            }.collect { opens ->
                setFlag(pillParams, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, !opens)
                pill?.let { runCatching { windowManager.updateViewLayout(it, pillParams) } }
            }
        }
    }

    /** Fades the bubble out, then takes its windows away. */
    fun hide() {
        if (bubble == null) return
        hideTarget()
        ui.closing.value = true
        touchable()
    }

    private fun removeAll() {
        val view = bubble ?: return
        bubble = null
        scope?.cancel()
        scope = null
        // The target too, should Burble go mid-drag before its own exit ran.
        for (v in listOfNotNull(view, touch, pill, target)) runCatching { windowManager.removeView(v) }
        touch = null; pill = null; target = null
        owner?.destroy()
        owner = null
    }

    private fun View.owned(o: Owner) {
        setViewTreeLifecycleOwner(o)
        setViewTreeSavedStateRegistryOwner(o)
        setViewTreeViewModelStoreOwner(o)
    }

    // ---- position ----

    /** The screen and the area clear of system bars and cutouts, for [Geometry]. */
    private fun screen(): Screen {
        val m = windowManager.currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val b = m.bounds
        return Screen(b.width().toFloat(), b.height().toFloat(),
            Box(i.left.toFloat(), i.top.toFloat(), (b.width() - i.right).toFloat(), (b.height() - i.bottom).toFloat()), density,
            context.resources.configuration.fontScale)
    }

    /** The geometry now, full or docked: the drawn bubble's own progress lives in its window. */
    private fun layout(progress: Float = if (ui.docked.value) 1f else 0f): BubbleLayout {
        val s = Prefs.settings.value
        return Geometry.layout(ui.screen.value, ui.anchor.value, s.bubbleSize, s.whenIdle, progress, s.idleOpacity / 100f, ui.drag.value)
    }

    /** Puts the three windows where [Geometry] says; only the invisible touch window resizes. */
    private fun apply() {
        val l = layout()
        fun WindowManager.LayoutParams.at(b: Box, sized: Boolean) {
            gravity = Gravity.TOP or Gravity.START
            x = b.left.roundToInt(); y = b.top.roundToInt()
            if (sized) { width = b.width.roundToInt(); height = b.height.roundToInt() }
        }
        params.at(l.window, sized = true)
        touchParams.at(l.touch, sized = true)
        pillParams.at(l.pill, sized = true)
        bubble?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        applyTouch()
        pill?.let { runCatching { windowManager.updateViewLayout(it, pillParams) } }
    }

    private fun applyTouch() {
        touch?.let { runCatching { windowManager.updateViewLayout(it, touchParams) } }
    }

    /** The touch window takes touches only while the bubble shows. */
    private fun touchable() {
        val hidden = ui.locked.value || ui.away.value || ui.closing.value
        setFlag(touchParams, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, hidden)
        // Not a TalkBack target either while there's nothing to see.
        touch?.importantForAccessibility =
            if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        applyTouch()
    }

    private fun setFlag(p: WindowManager.LayoutParams, flag: Int, on: Boolean) {
        p.flags = if (on) p.flags or flag else p.flags and flag.inv()
    }

    /** Springs the drag offset back to 0: the bubble settles at its anchor. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun settle() {
        snap?.cancel()
        val s = scope ?: return
        val spec = MotionScheme.expressive().defaultSpatialSpec<Float>()
        snap = s.launch {
            ui.moving.value = true
            val (x0, y0) = ui.drag.value
            val ax = Animatable(x0)
            val ay = Animatable(y0)
            val jobs = listOf(
                launch { ax.animateTo(0f, spec) { ui.drag.value = value to ui.drag.value.second; apply() } },
                launch { ay.animateTo(0f, spec) { ui.drag.value = ui.drag.value.first to value; apply() } },
            )
            jobs.forEach { it.join() }
            ui.moving.value = false
        }
    }

    // ---- shape guard ----

    // A shape is marked before it is drawn and unmarked once a frame with it reaches the screen.
    // A mark left at the next start means the app hung or died drawing it (Skia's shadow once hung
    // on SoftBurst at every launch): that shape is dropped for the cookie, so a bad choice can't
    // lock the user out of the app.
    private fun guard(shape: BubbleShape, view: View) {
        prefs.edit().putString(KEY_DRAWING, shape.name).commit()
        view.viewTreeObserver.registerFrameCommitCallback {
            if (prefs.getString(KEY_DRAWING, null) == shape.name) prefs.edit().remove(KEY_DRAWING).apply()
        }
        view.invalidate()
    }

    private fun dropShapeThatHung() {
        val hung = prefs.getString(KEY_DRAWING, null) ?: return
        prefs.edit().remove(KEY_DRAWING).commit()
        Log.w(TAG, "the last start never drew $hung; back to the cookie")
        Prefs.update { if (it.shape.name == hung) it.copy(shape = BubbleShape.COOKIE_9) else it }
    }

    // ---- dismiss target ----

    private fun showTarget() {
        if (target != null) return
        val screen = ui.screen.value
        targetParams.y = (screen.height - screen.safe.bottom + TARGET_MARGIN_DP * density).roundToInt()
        armed.value = false
        val shown = mutableStateOf(true).also { targetShown = it }
        val view = ComposeView(context).apply {
            owner?.let { owned(it) }
            setContent { AppTheme { LiveTarget(shown, armed) { removeTarget(this) } } }
        }
        runCatching { windowManager.addView(view, targetParams) }
        target = view
    }

    /** The target leaves on its own animation; [removeTarget] takes its window once it's gone. */
    private fun hideTarget() {
        targetShown.value = false
        armed.value = false
        target = null
    }

    private fun removeTarget(view: View) {
        runCatching { windowManager.removeView(view) }
    }

    /** The target's centre and the bubble's, in screen pixels; arms the target when close. */
    private fun track(frame: View) {
        val b = windowManager.currentWindowMetrics.bounds
        val tx = b.width() / 2f
        val half = TARGET_DP * density / 2
        val ty = b.bottom - targetParams.y - half
        val bubble = layout(0f).bubble
        val cx = bubble.centerX
        val cy = bubble.centerY
        val near = kotlin.math.hypot(cx - tx, cy - ty) < ARM_DP * density
        if (near != armed.value) {
            armed.value = near
            snapHaptic(frame, near)
        }
    }

    /**
     * Snapping onto ✕ clicks, and Android 14+ marks letting go of it too; Android 12 and 13 have
     * no threshold effects, so a clock tick marks the snap alone.
     */
    private fun snapHaptic(frame: View, near: Boolean) {
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (near) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            near -> HapticFeedbackConstants.CLOCK_TICK
            else -> return
        }
        frame.performHapticFeedback(effect)
    }

    /** After a drag: drop on ✕, or pick the nearer side, spring to it, remember it. */
    private fun release(frame: View) {
        val drop = armed.value
        hideTarget()
        if (drop) {
            frame.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val st = Dictation.state.value
            when {
                st.recording || (st as? Dictation.State.Error)?.waits == true -> Dictation.cancel()
                st is Dictation.State.Ready -> Dictation.discard()
                // A check mark or an error that doesn't wait goes with the bubble, or its state
                // would show it again until it times out.
                else -> {
                    Dictation.acknowledge()
                    onSnooze()
                }
            }
            settle()
            return
        }
        // To the nearer side edge, at the height it was dropped, from where it is now.
        val now = layout(0f).bubble
        val anchor = Geometry.anchorAt(ui.screen.value, now.centerX, now.centerY)
        ui.anchor.value = anchor
        ui.drag.value = 0f to 0f
        val home = layout(0f).bubble
        ui.drag.value = (now.centerX - home.centerX) to (now.centerY - home.centerY)
        prefs.edit().putBoolean(KEY_RIGHT, anchor.edge == Edge.RIGHT).putFloat(KEY_FRACTION, anchor.fraction).apply()
        settle()
    }

    /**
     * The touch window: invisible, over the bubble. A touch that passes the slop drags all three
     * windows; a shorter one is a tap. TalkBack reads and clicks it as the bubble.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TouchFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var start = 0f to 0f
        private var dragging = false

        init {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { tapBubble(context) }
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                // At the screen's edge, a drag from the bubble moves it rather than going back.
                systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ui.touching.value = true
                    ui.pressed.value = true
                    snap?.cancel()
                    ui.moving.value = false
                    downX = e.rawX; downY = e.rawY
                    start = ui.drag.value
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > slop) {
                        dragging = true
                        ui.dragging.value = true
                        ui.pressed.value = false
                        showTarget()
                    }
                    if (dragging) {
                        ui.drag.value = (start.first + dx) to (start.second + dy)
                        apply()
                        track(this)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tap = !dragging && e.actionMasked == MotionEvent.ACTION_UP
                    ui.touching.value = false
                    ui.pressed.value = false
                    if (dragging) {
                        dragging = false
                        ui.dragging.value = false
                        release(this)
                    }
                    if (tap) performClick()
                }
            }
            return true
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            dragging = false
            ui.dragging.value = false
            ui.touching.value = false
            ui.pressed.value = false
            hideTarget()
            // The new window metrics land after this callback. The anchor keeps its side and its
            // share of the height.
            post {
                snap?.cancel()
                ui.moving.value = false
                ui.drag.value = 0f to 0f
                ui.screen.value = screen()
                apply()
            }
        }
    }

    /** What an activity would give the ComposeView: a lifecycle, saved state, view models. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    /**
     * The system's animator duration scale for the windows' own springs, as Compose applies it
     * inside them: 0, with animations removed, puts them straight at their end.
     */
    private class DurationScale(context: Context) : MotionDurationScale {
        override val scaleFactor = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private companion object {
        const val KEY_RIGHT = "right"
        const val KEY_FRACTION = "fraction"
        const val KEY_DRAWING = "drawing_shape"
        const val TAG = "Overlay"
        const val PADDING_DP = 8
        /** The label's window: its tallest, two lines and padding, with room for its shadow. */
        const val PILL_DP = 72
        const val TARGET_DP = 64
        const val TARGET_MARGIN_DP = 32
        /** How close the bubble's centre must come to the target's to drop there. */
        const val ARM_DP = 72
    }
}
