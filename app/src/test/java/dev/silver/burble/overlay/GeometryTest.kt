package dev.silver.burble.overlay

import dev.silver.burble.WhenIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble's geometry over every input that matters: every size, appearance, side and
 * height, portrait and landscape, with and without a cutout, at every step of the transition.
 */
class GeometryTest {
    private val d = 2.625f

    /** A 1080 × 2400 phone; the cutout is at the top in portrait, on the left in landscape. */
    private fun screens() = listOf(
        "portrait" to Screen(1080f, 2400f, Box(0f, 63f, 1080f, 2400f - 63f), d),
        "portrait, cutout" to Screen(1080f, 2400f, Box(0f, 136f, 1080f, 2400f - 63f), d),
        "landscape" to Screen(2400f, 1080f, Box(0f, 63f, 2400f - 126f, 1080f), d),
        "landscape, cutout" to Screen(2400f, 1080f, Box(136f, 63f, 2400f - 126f, 1080f), d),
    )
    private val sizes = (40..96 step 8).toList()
    private val heights = listOf(0f, 0.5f, 1f)
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    private fun each(block: (String, Screen, Anchor, Int, WhenIdle) -> Unit) {
        for ((name, screen) in screens()) for (edge in Edge.entries) for (f in heights)
            for (size in sizes) for (look in WhenIdle.entries) block("$name $edge $f $size $look", screen, Anchor(edge, f), size, look)
    }

    private fun layout(screen: Screen, anchor: Anchor, size: Int, look: WhenIdle, p: Float, opacity: Float = 0.5f) =
        Geometry.layout(screen, anchor, size, look, p, opacity)

    private fun near(msg: String, a: Float, b: Float) = assertEquals(msg, a, b, 0.01f)

    @Test fun dockedIsFlushToItsEdgeAtTheSameHeightWhateverTheSize() {
        for ((name, screen) in screens()) for (edge in Edge.entries) for (f in heights)
            for (look in listOf(WhenIdle.SHRINK, WhenIdle.DOT)) {
                val ys = sizes.map { size ->
                    val b = layout(screen, Anchor(edge, f), size, look, 1f).bubble
                    val gap = if (edge == Edge.RIGHT) screen.safe.right - b.right else b.left - screen.safe.left
                    near("$name $edge $f $size $look: inset", Geometry.INSET_DP * d, gap)
                    b.centerY
                }
                ys.forEach { near("$name $edge $f $look: height", ys[0], it) }
            }
    }

    @Test fun theFullBubblesCentreNeverMoves() {
        for ((name, screen) in screens()) for (edge in Edge.entries) for (f in heights) {
            // Full is progress 0 whatever the size, appearance or opacity: one centre height, and
            // one outer edge distance per size.
            val centres = mutableSetOf<Float>()
            for (size in sizes) for (look in WhenIdle.entries) for (opacity in listOf(0.2f, 1f)) {
                val b = layout(screen, Anchor(edge, f), size, look, 0f, opacity).bubble
                centres += b.centerY
                val gap = if (edge == Edge.RIGHT) screen.safe.right - b.right else b.left - screen.safe.left
                near("$name $edge $size: padding", Geometry.PAD_DP * d, gap)
            }
            val first = centres.first()
            centres.forEach { near("$name $edge $f: centre", first, it) }
        }
    }

    @Test fun nothingLeavesTheScreenOrGoesUnderBarsOrCutouts() = each { name, screen, anchor, size, look ->
        for (p in steps) {
            val l = layout(screen, anchor, size, look, p)
            assertTrue("$name $p bubble", screen.safe.contains(l.bubble))
            assertTrue("$name $p window", screen.safe.contains(l.window))
            assertTrue("$name $p touch", screen.safe.contains(l.touch))
            assertTrue("$name $p pill", screen.safe.contains(l.pill))
        }
    }

    @Test fun theTouchAreaIsAtLeast48dpAndHoldsTheBubble() = each { name, screen, anchor, size, look ->
        for (p in listOf(0f, 1f)) {
            val l = layout(screen, anchor, size, look, p)
            assertTrue("$name $p width", l.touch.width >= Geometry.MIN_TOUCH_DP * d - 0.01f)
            assertTrue("$name $p height", l.touch.height >= Geometry.MIN_TOUCH_DP * d - 0.01f)
            assertTrue("$name $p holds the bubble", l.touch.contains(l.bubble))
        }
    }

    @Test fun theWindowNeverChangesSize() = each { name, screen, anchor, size, look ->
        for (p in steps) {
            val l = layout(screen, anchor, size, look, p)
            near("$name width", Geometry.windowSide(d), l.window.width)
            near("$name height", Geometry.windowSide(d), l.window.height)
            assertTrue("$name $p bubble in window", l.window.contains(l.bubble))
        }
    }

    @Test fun theTransitionIsOneInterpolation() = each { name, screen, anchor, size, look ->
        val from = layout(screen, anchor, size, look, 0f)
        val to = layout(screen, anchor, size, look, 1f)
        for (p in steps) {
            val at = layout(screen, anchor, size, look, p)
            near("$name $p x", from.bubble.centerX + (to.bubble.centerX - from.bubble.centerX) * p, at.bubble.centerX)
            near("$name $p y", from.bubble.centerY, at.bubble.centerY)
            near("$name $p size", from.bubble.width + (to.bubble.width - from.bubble.width) * p, at.bubble.width)
            near("$name $p alpha", from.alpha + (to.alpha - from.alpha) * p, at.alpha)
            assertEquals("$name $p window", from.window, at.window)
            assertEquals("$name $p pill", from.pill, at.pill)
        }
    }

    @Test fun rotationKeepsTheSideAndTheShareOfTheHeight() {
        val (_, portrait) = screens()[1]
        val (_, landscape) = screens()[3]
        for (edge in Edge.entries) for (f in listOf(0.3f, 0.5f, 0.7f)) {
            val anchor = Anchor(edge, f)
            for (screen in listOf(portrait, landscape)) {
                val b = layout(screen, anchor, 64, WhenIdle.DOT, 1f).bubble
                assertEquals(edge, Geometry.anchorAt(screen, b.centerX, b.centerY).edge)
                near("$edge $f share", f, (b.centerY - screen.safe.top) / screen.safe.height)
            }
        }
    }

    @Test fun aDragKeepsTheBubbleOnScreenAndDropsWhereItWas() {
        val (_, screen) = screens()[0]
        for (drag in listOf(-5000f to -5000f, -400f to 300f, 5000f to 5000f)) {
            val l = Geometry.layout(screen, Anchor(Edge.RIGHT, 0.5f), 64, WhenIdle.SHRINK, 0f, 1f, drag)
            assertTrue("$drag on screen", Box(0f, 0f, screen.width, screen.height).contains(l.bubble))
            val anchor = Geometry.anchorAt(screen, l.bubble.centerX, l.bubble.centerY)
            val dropped = Geometry.layout(screen, anchor, 64, WhenIdle.SHRINK, 0f, 1f).bubble
            near("$drag height kept", l.bubble.centerY.coerceIn(dropped.centerY, dropped.centerY), dropped.centerY)
        }
    }
}
