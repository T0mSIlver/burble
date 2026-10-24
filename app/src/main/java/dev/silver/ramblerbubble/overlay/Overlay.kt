package dev.silver.ramblerbubble.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
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
import dev.silver.ramblerbubble.OverlayHost
import dev.silver.ramblerbubble.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating dictation overlay: a Compose window outside any activity that renders
 * [dev.silver.ramblerbubble.Dictation]. Drag it anywhere; on release it springs to the nearest
 * side edge and remembers where it sits. [windowType] is the host's: TYPE_APPLICATION_OVERLAY
 * from a service, TYPE_ACCESSIBILITY_OVERLAY from the accessibility service.
 *
 * A configuration change (rotation, fold, dark mode) only re-places the window: the view, its
 * composition and the dictation all carry on.
 */
class Overlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
) : OverlayHost {
    /** When false, the overlay shows only while a dictation is under way, not the idle bubble. */
    var idleBubble: Boolean
        get() = showIdle.value
        set(value) { showIdle.value = value }
    private val showIdle = mutableStateOf(true)

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
    ).apply { title = "Rambler Bubble" }

    /** Pinned to the right edge; then [WindowManager.LayoutParams.x] counts from the right, so
     * the pill grows towards the middle of the screen whichever side it sits on. */
    private val alignEnd = mutableStateOf(prefs.getBoolean(KEY_RIGHT, true))
    private var onRight
        get() = alignEnd.value
        set(value) { alignEnd.value = value }
    /** Vertical centre as a fraction of the usable height, so it survives rotation. */
    private var fraction = prefs.getFloat(KEY_FRACTION, 0.4f)

    val isShown get() = root != null

    override fun show() {
        if (root != null) return
        val o = Owner().also { owner = it }
        scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
        val frame = DragFrame(context)
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(ComposeView(context).apply {
            setContent { AppTheme { LiveOverlay(showIdle, alignEnd) } }
        })
        frame.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            // A state change resized the window (the pill, the held text): keep it on screen.
            if (r - l != oldR - oldL || b - t != oldB - oldT) place(animate = true)
        }
        place(animate = false)
        windowManager.addView(frame, params)
        root = frame
        o.resume()
    }

    override fun hide() {
        val frame = root ?: return
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val b = m.bounds
            return Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom) to
                b.width()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
        // Android 10 has no inset metrics outside a window: assume the usual bar heights.
        return Rect(0, (24 * dm.density).roundToInt(), dm.widthPixels,
            dm.heightPixels - (48 * dm.density).roundToInt()) to dm.widthPixels
    }

    private fun width() = root?.width?.takeIf { it > 0 } ?: (BUBBLE_DP * context.resources.displayMetrics.density).roundToInt()
    private fun height() = root?.height?.takeIf { it > 0 } ?: width()

    /** Puts the window at its remembered side and height, clamped to the current screen. */
    private fun place(animate: Boolean) {
        val (area, screenW) = area()
        val h = height()
        val y = (area.top + fraction * area.height() - h / 2f).roundToInt()
        moveTo(edgeX(area, screenW), clampY(y, area, h), animate)
    }

    // The window's own padding, room for the shadow, keeps the bubble off the very edge.
    private fun edgeX(area: Rect, screenW: Int) = if (onRight) screenW - area.right else area.left

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

    /** After a drag: pick the nearer side, spring to it, remember it. */
    private fun release() {
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
                    snap?.cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > slop) {
                        dragging = true
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        params.x = startX + ((if (onRight) -1 else 1) * dx).roundToInt()
                        params.y = startY + dy.roundToInt()
                        apply()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    release()
                    return true
                }
            }
            return super.dispatchTouchEvent(e)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            dragging = false
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
        const val BUBBLE_DP = 68
    }
}
