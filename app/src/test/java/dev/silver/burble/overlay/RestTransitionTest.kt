package dev.silver.burble.overlay

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Full to rest and back is one transition: size, dock and opacity move together, each one way,
// with no step where only one of them has changed.
class RestTransitionTest {
    private fun frames(from: Float, to: Float): List<Float> {
        val animation = TargetBasedAnimation(RestSpec, Float.VectorConverter, from, to)
        val ms = animation.durationNanos / 1_000_000
        return (0..ms step 16).map { animation.getValueFromNanos(it * 1_000_000) } + to
    }

    private fun check(from: Float, to: Float) {
        // A 64 dp bubble going to a dot at 40 %, docked 30 px out.
        val looks = frames(from, to).map { restLook(it, idleScale = 12f / 64, idleOpacity = 0.4f, dock = 30f) }
        for ((a, b) in looks.zipWithNext()) {
            val shrinking = b.scale <= a.scale
            assertEquals("alpha moves with scale", shrinking, b.alpha <= a.alpha)
            assertEquals("dock moves with scale", shrinking, b.offset >= a.offset)
            assertTrue("one way only", if (to > from) shrinking else b.scale >= a.scale)
        }
        val (first, last) = looks.first() to looks.last()
        val ends = listOf(first, last).sortedBy { it.scale }
        assertEquals(12f / 64, ends[0].scale, 1e-4f)
        assertEquals(0.4f, ends[0].alpha, 1e-4f)
        assertEquals(1f, ends[1].scale, 1e-4f)
        assertEquals(1f, ends[1].alpha, 1e-4f)
    }

    @Test fun toRest() = check(0f, 1f)

    @Test fun backOnTouch() = check(1f, 0f)
}
