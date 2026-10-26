package dev.silver.burble.overlay

import dev.silver.burble.WhenIdle
import kotlin.math.max

/** A rectangle in pixels, screen coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun contains(b: Box, slack: Float = 0.01f) =
        b.left >= left - slack && b.top >= top - slack && b.right <= right + slack && b.bottom <= bottom + slack

    fun moved(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun centred(x: Float, y: Float, w: Float, h: Float) = Box(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    }
}

/**
 * The screen: its size, the area clear of system bars and cutouts, its density, and the font
 * scale the label's text grows by.
 */
data class Screen(val width: Float, val height: Float, val safe: Box, val density: Float, val fontScale: Float = 1f)

enum class Edge { LEFT, RIGHT }

/** Where the bubble lives: the side edge, and its centre's height as a share of the safe area. */
data class Anchor(val edge: Edge, val fraction: Float)

/**
 * Everything the overlay draws and listens on. [window] is the bubble's own window, always the
 * size of the largest bubble so it never resizes; [bubble] is the bubble as drawn in it; [touch]
 * takes the touches; [pill] holds an error's label beside the bubble.
 */
data class BubbleLayout(val window: Box, val bubble: Box, val alpha: Float, val touch: Box, val pill: Box)

/**
 * All of the bubble's geometry, in one pure function, so the windows, the drawing and the
 * settings preview can't disagree, and its invariants are tested over every input. Nothing here
 * depends on a window's size: Android applies a window's new size and its new place on different
 * frames, so a bubble that depended on one would jump.
 */
object Geometry {
    const val PAD_DP = 8f
    const val MAX_DP = 96f
    const val INSET_DP = 4f
    const val MIN_TOUCH_DP = 48f
    const val DOT_DP = 12f
    const val SMALLER = 0.6f
    const val PILL_GAP_DP = 8f
    /** The label's window: wide and tall enough for its longest two lines, and their shadow. */
    const val PILL_WIDTH_DP = 256f
    const val PILL_HEIGHT_DP = 72f
    /** How far past full or the idle look a layout goes, as a share of the way between them. */
    const val OVERSHOOT = 0.03f

    /** The bubble's window side: the largest bubble and room for its shadow. */
    fun windowSide(density: Float) = (MAX_DP + 2 * PAD_DP) * density

    /** The idle look's share of the full size. */
    fun idleScale(appearance: WhenIdle, sizeDp: Int) = when (appearance) {
        WhenIdle.FULL -> 1f
        WhenIdle.SHRINK -> SMALLER
        WhenIdle.DOT -> DOT_DP / sizeDp
    }

    /**
     * The layout at [progress] from full (0) to the idle look (1), moved by [drag] while a finger
     * drags it. Every in-between layout is a straight interpolation of the two ends. A spring
     * passes the ends a little: up to [OVERSHOOT] past either still lays out on screen, so the
     * motion settles rather than stopping dead.
     */
    fun layout(
        screen: Screen,
        anchor: Anchor,
        sizeDp: Int,
        appearance: WhenIdle,
        progress: Float,
        idleOpacity: Float,
        drag: Pair<Float, Float> = 0f to 0f,
    ): BubbleLayout {
        val d = screen.density
        val safe = screen.safe
        val s = sizeDp * d
        val pad = PAD_DP * d
        val w = windowSide(d)
        val right = anchor.edge == Edge.RIGHT
        // Clamped for the largest bubble, so the centre doesn't move when the size does.
        val cy = (safe.top + anchor.fraction * safe.height).coerceIn(safe.top + w / 2, max(safe.top + w / 2, safe.bottom - w / 2))
        val fullX = if (right) safe.right - pad - s / 2 else safe.left + pad + s / 2
        val k = idleScale(appearance, sizeDp)
        val v = s * k
        val inset = INSET_DP * d
        val dockX = when {
            appearance == WhenIdle.FULL -> fullX
            right -> safe.right - inset - v / 2
            else -> safe.left + inset + v / 2
        }
        val p = progress.coerceIn(-OVERSHOOT, 1f + OVERSHOOT)
        val cx = fullX + (dockX - fullX) * p
        val size = s + (v - s) * p
        // A drag keeps the whole bubble on screen.
        val dx = drag.first.coerceIn(s / 2 - fullX, screen.width - s / 2 - fullX)
        val dy = drag.second.coerceIn(s / 2 - cy, screen.height - s / 2 - cy)
        val window = (if (right) Box(safe.right - w, cy - w / 2, safe.right, cy + w / 2)
            else Box(safe.left, cy - w / 2, safe.left + w, cy + w / 2)).moved(dx, dy)
        val bubble = Box.centred(cx, cy, size, size).moved(dx, dy)
        val minTouch = MIN_TOUCH_DP * d
        val touch = if (p >= 1f && appearance != WhenIdle.FULL) {
            // Docked: a strip along the edge, at least minTouch each way.
            val tw = max(minTouch, inset + v + pad)
            val th = max(minTouch, v + 2 * pad)
            if (right) Box(safe.right - tw, cy - th / 2, safe.right, cy + th / 2)
            else Box(safe.left, cy - th / 2, safe.left + tw, cy + th / 2)
        } else {
            val t = max(minTouch, s + 2 * pad)
            Box.centred(fullX, cy, t, t)
        }.moved(dx, dy)
        val pw = PILL_WIDTH_DP * d
        // Tall enough for the label at a larger font size: its text grows in sp.
        val ph = PILL_HEIGHT_DP * d * max(1f, screen.fontScale)
        val gap = PILL_GAP_DP * d
        val pillTop = (cy - ph / 2).coerceIn(safe.top, max(safe.top, safe.bottom - ph))
        val pillLeft = (if (right) fullX - s / 2 - gap - pw else fullX + s / 2 + gap)
            .coerceIn(safe.left, max(safe.left, safe.right - pw))
        val pill = Box(pillLeft, pillTop, pillLeft + pw, pillTop + ph).moved(dx, dy)
        val alpha = (1f + (idleOpacity - 1f) * p).coerceIn(0f, 1f)
        return BubbleLayout(window, bubble, alpha, touch, pill)
    }

    /** Where a bubble dropped with its full centre at [x], [y] belongs: the nearer side edge. */
    fun anchorAt(screen: Screen, x: Float, y: Float) = Anchor(
        if (x > screen.safe.centerX) Edge.RIGHT else Edge.LEFT,
        ((y - screen.safe.top) / screen.safe.height).coerceIn(0f, 1f),
    )
}
