package dev.silver.burble.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import kotlin.math.exp

/**
 * Burble's motion, M3 Expressive, in one place for the app and the overlay: springs for all that
 * moves, so an interrupted motion carries on from where it is at its speed; a tween only where a
 * duration must be fixed, on M3's easing curves and token durations.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Motion {
    private val scheme = MotionScheme.expressive()

    /** Size, position, shape: the expressive spatial springs, which overshoot a little. */
    fun <T> spatial(): FiniteAnimationSpec<T> = scheme.defaultSpatialSpec()
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = scheme.fastSpatialSpec()
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = scheme.slowSpatialSpec()

    /** Colour and opacity: the effects springs, which don't overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> = scheme.defaultEffectsSpec()
    fun <T> fastEffects(): FiniteAnimationSpec<T> = scheme.fastEffectsSpec()
    fun <T> slowEffects(): FiniteAnimationSpec<T> = scheme.slowEffectsSpec()

    /**
     * The bubble between full (0) and its idle look (1): the spatial spring, run until under a
     * pixel from the end; its default stop, at 1 %, snapped a 96 dp bubble by 2 px.
     */
    val rest: FiniteAnimationSpec<Float> = (scheme.defaultSpatialSpec<Float>() as SpringSpec<Float>)
        .let { spring(it.dampingRatio, it.stiffness, visibilityThreshold = 0.002f) }

    /**
     * A level that follows the voice, such as the wave's glow: critically damped, so it rises and
     * falls without overshoot, 10 to 90 % in [LEVEL_RISE_MS] like Gboard's 200 ms ramps.
     */
    val level: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = omega(LEVEL_RISE_MS).let { it * it }, visibilityThreshold = 0.001f)

    /** M3's easing curves, for motions with a fixed duration. */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** M3's duration tokens, in ms. */
    const val SHORT_4 = 200
    const val MEDIUM_2 = 300
    const val MEDIUM_4 = 400
    const val LONG_2 = 500

    /** Something appearing with a fixed duration: emphasized decelerate. */
    fun <T> enter(ms: Int = MEDIUM_2): FiniteAnimationSpec<T> = tween(ms, easing = emphasizedDecelerate)

    /** Something leaving with a fixed duration: emphasized accelerate, shorter. */
    fun <T> exit(ms: Int = SHORT_4): FiniteAnimationSpec<T> = tween(ms, easing = emphasizedAccelerate)

    const val LEVEL_RISE_MS = 190.0

    /** The natural frequency, in rad/s, of a critically damped spring rising 10 to 90 % in [riseMs]. */
    fun omega(riseMs: Double): Float = (3.357 / (riseMs / 1000)).toFloat()
}

/**
 * A value following a target on a critically damped spring, stepped by the caller's frame clock
 * where no Compose animation runs (the wave's host). A new target keeps the value and its speed:
 * an interrupted rise turns smoothly, never restarting from zero. [riseMs] is the 10 to 90 % time
 * of a step from rest.
 */
class Follow(value: Float, riseMs: Double = Motion.LEVEL_RISE_MS) {
    private val w = Motion.omega(riseMs).toDouble()
    var value = value
        private set
    private var speed = 0.0
    var target = value

    /** Snaps to [v], at rest. */
    fun snap(v: Float) {
        value = v
        target = v
        speed = 0.0
    }

    /** Advances [dtMs] along the exact critically damped motion towards [target]. */
    fun step(dtMs: Double) {
        if (dtMs <= 0) return
        val t = dtMs / 1000
        val c1 = (value - target).toDouble()
        val c2 = speed + w * c1
        val e = exp(-w * t)
        value = (target + (c1 + c2 * t) * e).toFloat()
        speed = (c2 - w * (c1 + c2 * t)) * e
    }
}
