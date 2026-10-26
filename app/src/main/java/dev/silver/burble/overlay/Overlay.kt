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
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
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
 * The bubble: a Compose window outside any activity that renders [Dictation]. Drag it anywhere;
 * on release it springs to the nearest side edge and remembers where it sits. While dragging, a
 * ✕ target shows at the bottom: dropping the bubble there cancels a dictation, drops the audio
 * of an error waiting for retry or text waiting for a field, or else hides the bubble until
 * [onSnooze]'s owner shows it again. [windowType] is the host's, TYPE_ACCESSIBILITY_OVERLAY from
 * the accessibility service.
 *
 * A configuration change (rotation, fold, dark mode) only re-places the window: the view, its
 * composition and the dictation all carry on.
 */
class Overlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    /** Dropped on ✕ while idle: hidden for now, not turned off. */
    private val onSnooze: () -> Unit,
) {
    /** While true (the lock screen is up) nothing shows; a dictation carries on unseen. */
    var locked: Boolean
        get() = hidden.value
        set(value) { hidden.value = value }
    private val hidden = mutableStateOf(false)

    /** While true (no field has focus, in "only while typing") the bubble fades out. */
    var away: Boolean
        get() = awayState.value
        set(value) { awayState.value = value }
    private val awayState = mutableStateOf(false)

    /** A finger is on the bubble, dragging or not: it shows at full size and opacity. */
    private val touching = mutableStateOf(false)

    /** How far out past its edge position the window sits while the idle bubble is docked. */
    private val shift = mutableFloatStateOf(0f)
    private var dock: Job? = null

    private val density = context.resources.displayMetrics.density
    private var target: ComposeView? = null
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
    private var root: DragFrame? = null
    private var owner: Owner? = null
    private var scope: CoroutineScope? = null
    private var snap: Job? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { title = "Burble" }

    /** Pinned to the right edge; then [WindowManager.LayoutParams.x] counts from the right, so
     * the error label grows towards the middle of the screen whichever side it sits on. */
    private val alignEnd = mutableStateOf(prefs.getBoolean(KEY_RIGHT, true))
    private var onRight
        get() = alignEnd.value
        set(value) { alignEnd.value = value }
    /** Vertical centre as a fraction of the usable height, so it survives rotation. */
    private var fraction = prefs.getFloat(KEY_FRACTION, 0.4f)

    val isShown get() = root != null

    fun show() {
        if (root != null) return
        dropShapeThatHung()
        val o = Owner().also { owner = it }
        scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
        val frame = DragFrame(context)
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(ComposeView(context).apply {
            setContent { AppTheme { LiveOverlay(alignEnd, hidden, awayState, touching, shift, ::dockTo) } }
        })
        frame.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            // The error label resized the window: keep it on screen.
            if (r - l != oldR - oldL || b - t != oldB - oldT) place(animate = true)
            // At the screen's edge, a drag from the bubble moves it rather than going back.
            frame.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
        place(animate = false)
        windowManager.addView(frame, params)
        root = frame
        o.resume()
        scope?.launch {
            Prefs.settings.map { it.shape }.distinctUntilChanged().collect { guard(it, frame) }
        }
    }

    fun hide() {
        val frame = root ?: return
        hideTarget()
        root = null
        scope?.cancel()
        scope = null
        runCatching { windowManager.removeView(frame) }
        owner?.destroy()
        owner = null
    }

    // ---- position ----

    /** The screen area clear of system bars and cutouts, in screen coordinates. */
    private fun area(): Pair<Rect, Int> {
        val m = windowManager.currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val b = m.bounds
        return Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom) to
            b.width()
    }

    private fun width() = root?.width?.takeIf { it > 0 } ?: (BUBBLE_DP * density).roundToInt()
    private fun height() = root?.height?.takeIf { it > 0 } ?: width()

    /** Puts the window at its remembered side and height, clamped to the current screen. */
    private fun place(animate: Boolean) {
        val (area, screenW) = area()
        val h = height()
        val y = (area.top + fraction * area.height() - h / 2f).roundToInt()
        moveTo(edgeX(area, screenW), clampY(y, area, h), animate)
    }

    // The window's own padding, room for the shadow, keeps the bubble off the very edge; docked,
    // the window reaches out past it.
    private fun edgeX(area: Rect, screenW: Int) = baseX(area, screenW) - shift.floatValue.roundToInt()

    private fun baseX(area: Rect, screenW: Int) = if (onRight) screenW - area.right else area.left

    /** Springs the window [target] pixels out past its edge position, or back with 0. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun dockTo(target: Float) {
        val s = scope ?: return
        if (touching.value || root == null) return
        dock?.cancel()
        snap?.cancel()
        val (area, screenW) = area()
        val base = baseX(area, screenW)
        dock = s.launch {
            Animatable(shift.floatValue).animateTo(target, MotionScheme.expressive().defaultSpatialSpec()) {
                shift.floatValue = value
                params.x = base - value.roundToInt()
                apply()
            }
        }
    }

    private fun clampY(y: Int, area: Rect, h: Int) = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))

    private fun apply() {
        params.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
        root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun moveTo(x: Int, y: Int, animate: Boolean) {
        snap?.cancel()
        val s = scope
        if (!animate || s == null || root == null) {
            params.x = x; params.y = y
            apply()
            return
        }
        val spec = MotionScheme.expressive().defaultSpatialSpec<Float>()
        snap = s.launch {
            val ax = Animatable(params.x.toFloat())
            val ay = Animatable(params.y.toFloat())
            launch { ax.animateTo(x.toFloat(), spec) { params.x = value.roundToInt(); apply() } }
            launch { ay.animateTo(y.toFloat(), spec) { params.y = value.roundToInt(); apply() } }
        }
    }

    // ---- shape guard ----

    // A shape is marked before it is drawn and unmarked once a frame with it reaches the screen.
    // A mark left at the next start means the app hung or died drawing it (Skia's shadow once hung
    // on SoftBurst at every launch): that shape is dropped for the cookie, so a bad choice can't
    // lock the user out of the app.
    private fun guard(shape: BubbleShape, frame: FrameLayout) {
        prefs.edit().putString(KEY_DRAWING, shape.name).commit()
        frame.viewTreeObserver.registerFrameCommitCallback {
            if (prefs.getString(KEY_DRAWING, null) == shape.name) prefs.edit().remove(KEY_DRAWING).apply()
        }
        frame.invalidate()
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
        val (area, _) = area()
        targetParams.y = windowManager.currentWindowMetrics.bounds.bottom - area.bottom +
            (TARGET_MARGIN_DP * density).roundToInt()
        armed.value = false
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent { AppTheme { DismissTarget(armed.value) } }
        }
        runCatching { windowManager.addView(view, targetParams) }
        target = view
    }

    private fun hideTarget() {
        target?.let { runCatching { windowManager.removeView(it) } }
        target = null
        armed.value = false
    }

    /** The target's centre and the bubble's, in screen pixels; arms the target when close. */
    private fun track() {
        val frame = root ?: return
        val b = windowManager.currentWindowMetrics.bounds
        val tx = b.width() / 2f
        val half = TARGET_DP * density / 2
        val ty = b.bottom - targetParams.y - half
        val pad = PADDING_DP * density
        val bubble = frame.height - 2 * pad
        val left = if (onRight) b.width() - params.x - frame.width else params.x
        val cx = left + if (onRight) frame.width - pad - bubble / 2 else pad + bubble / 2
        val cy = params.y + frame.height / 2f
        val near = kotlin.math.hypot(cx - tx, cy - ty) < ARM_DP * density
        if (near != armed.value) {
            armed.value = near
            if (near) frame.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        }
    }

    /** After a drag: drop on ✕, or pick the nearer side, spring to it, remember it. */
    private fun release() {
        val drop = armed.value
        hideTarget()
        if (drop) {
            root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
            place(animate = true)
            return
        }
        val (area, screenW) = area()
        val w = width()
        val h = height()
        val left = if (onRight) screenW - params.x - w else params.x
        val toRight = left + w / 2 > (area.left + area.right) / 2
        if (toRight != onRight) {
            // Re-express the current spot from the other edge before springing to it.
            onRight = toRight
            params.x = if (onRight) screenW - left - w else left
            apply()
        }
        val y = clampY(params.y, area, h)
        fraction = ((y + h / 2f - area.top) / area.height()).coerceIn(0f, 1f)
        prefs.edit().putBoolean(KEY_RIGHT, onRight).putFloat(KEY_FRACTION, fraction).apply()
        moveTo(edgeX(area, screenW), y, animate = true)
    }

    /**
     * Moves the window under the finger once a touch passes the slop, and cancels the touch for
     * the buttons inside; shorter touches reach them untouched.
     */
    @SuppressLint("ViewConstructor")
    private inner class DragFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching.value = true
                    snap?.cancel()
                    // Back to where the full bubble sits: the bubble grows there, drawn against
                    // the window, so it doesn't jump.
                    dock?.cancel()
                    if (shift.floatValue != 0f) {
                        params.x += shift.floatValue.roundToInt()
                        shift.floatValue = 0f
                        apply()
                    }
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > slop) {
                        dragging = true
                        showTarget()
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        params.x = startX + ((if (onRight) -1 else 1) * dx).roundToInt()
                        params.y = startY + dy.roundToInt()
                        apply()
                        track()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching.value = false
                    if (dragging) {
                        dragging = false
                        release()
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(e)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            dragging = false
            touching.value = false
            hideTarget()
            // The new window metrics land after this callback.
            post { place(animate = false) }
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

    private companion object {
        const val KEY_RIGHT = "right"
        const val KEY_FRACTION = "fraction"
        const val KEY_DRAWING = "drawing_shape"
        const val TAG = "Overlay"
        const val BUBBLE_DP = 80
        const val PADDING_DP = 8
        const val TARGET_DP = 64
        const val TARGET_MARGIN_DP = 32
        /** How close the bubble's centre must come to the target's to drop there. */
        const val ARM_DP = 72
    }
}
