package dev.silver.burble.overlay

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import dev.silver.burble.WhenIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Full to rest and back is one transition on the overlay's own spring and geometry: size, dock
// and opacity move together, each one way, with no step where only one of them has changed.
class RestTransitionTest {
    private fun frames(from: Float, to: Float): List<Float> {
        val animation = TargetBasedAnimation(RestSpec, Float.VectorConverter, from, to)
        val ms = animation.durationNanos / 1_000_000
        return (0..ms step 16).map { animation.getValueFromNanos(it * 1_000_000) } + to
    }

    private fun check(from: Float, to: Float) {
        // A 64 dp bubble on the right going to a dot at 40 %, as the overlay lays it out.
        val screen = Screen(1080f, 2400f, Box(0f, 63f, 1080f, 2337f), 2.625f)
        val looks = frames(from, to).map {
            Geometry.layout(screen, Anchor(Edge.RIGHT, 0.5f), 64, WhenIdle.DOT, it, 0.4f).let { l -> Triple(l.bubble.width, l.bubble.centerX, l.alpha) }
        }
        for ((a, b) in looks.zipWithNext()) {
            val shrinking = b.first <= a.first
            assertEquals("alpha moves with size", shrinking, b.third <= a.third)
            assertEquals("dock moves with size", shrinking, b.second >= a.second)
            assertTrue("one way only", if (to > from) shrinking else b.first >= a.first)
        }
        val ends = listOf(looks.first(), looks.last()).sortedBy { it.first }
        assertEquals(12 * 2.625f, ends[0].first, 1e-2f)
        assertEquals(0.4f, ends[0].third, 1e-4f)
        assertEquals(64 * 2.625f, ends[1].first, 1e-2f)
        assertEquals(1f, ends[1].third, 1e-4f)
    }

    @Test fun toRest() = check(0f, 1f)

    @Test fun backOnTouch() = check(1f, 0f)
}
