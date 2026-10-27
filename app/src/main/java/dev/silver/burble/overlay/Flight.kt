package dev.silver.burble.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.mutableStateOf
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import dev.silver.burble.ui.theme.Motion

/**
 * The handoff's motion (spec/handoff.md), every number in one place for the motion designer's
 * to replace (material/images/handoff-motion/handoff-motion.md): a keyboard's mic key starts a
 * dictation, and the bubble comes out of the key at its size and shape, rises to just above where
 * the keyboard's top edge was as the keyboard slides away, the wave starting mid-flight; on
 * finish it flies back into the key as the keyboard slides up.
 */
internal object HandoffMotion {
    /**
     * Out of the key: the slow expressive spatial spring, moving place, size and shape as one,
     * about as long as the keyboard takes to slide away.
     */
    val out: FiniteAnimationSpec<Float> get() = Motion.slowSpatial()
    /** Back into the key, from when the keyboard's window comes: as long as its slide up. */
    val back: FiniteAnimationSpec<Float> get() = Motion.spatial()
    /** Fading back in at home when no keyboard covered the key; opacity, so an effects spring. */
    val fadeIn: FiniteAnimationSpec<Float> get() = Motion.effects()
    /** Between the bubble's bottom and where the keyboard's top edge was, in dp. */
    const val GAP_DP = 16f
    /** The share of the way from the key over which the bubble fades in (out, on the way back). */
    const val FADE = 0.15f
    /** The wave fades in between these shares of the way: it starts mid-flight. */
    const val WAVE_FROM = 0.35f
    const val WAVE_TO = 0.75f
    /** The key's corners, as a share of its shorter half side. */
    const val KEY_CORNER = 0.45f
    /**
     * After the words land, how long the bubble waits for the keyboard before going home instead:
     * a keyboard that ignores the first click comes back after about 1.5 s (InsertionService.showKeyboard).
     */
    const val HOLD_MS = 3000L

    /** The key's outline in the unit square, the bubble's shape morphs from. */
    val keyPolygon: RoundedPolygon = RoundedPolygon.rectangle(2f, 2f, rounding = CornerRounding(KEY_CORNER)).normalized()

    /**
     * Where the bubble's centre waits during the dictation, for a bubble [size] px across: above
     * [key], [GAP_DP] above [keyboardTop], inside the area clear of bars and cutouts.
     */
    fun spot(screen: Screen, key: Box, keyboardTop: Float, size: Float): Pair<Float, Float> {
        val safe = screen.safe
        val half = size / 2 + Geometry.PAD_DP * screen.density
        val x = key.centerX.coerceIn(safe.left + half, maxOf(safe.left + half, safe.right - half))
        val y = (keyboardTop - GAP_DP * screen.density - size / 2).coerceIn(safe.top + half, maxOf(safe.top + half, safe.bottom - half))
        return x to y
    }
}

/**
 * One flight between [key], in screen px and no larger than the bubble's window, and the dictation spot. [fromKey] and [toSpot] are the
 * window's offsets from the bubble's home for each end; [p] goes from 0 at the key to 1 at the
 * spot, read while drawing.
 */
internal class Flight(val key: Box, private val fromKey: Pair<Float, Float>, private val toSpot: Pair<Float, Float>) {
    val p = Animatable(0f)
    /** Once back home without a keyboard to cover the key: the bubble fades in there. */
    val fade = Animatable(1f)
    /** The dictation is over: the bubble goes back into the key once the keyboard comes. */
    var returning = false
    /** Flying into the key: shown, though the keyboard that hands off is up. */
    val homing = mutableStateOf(false)

    /** The window's offset from home at [p]. */
    fun offset(p: Float = this.p.value) =
        (fromKey.first + (toSpot.first - fromKey.first) * p) to (fromKey.second + (toSpot.second - fromKey.second) * p)

    /** [at], the bubble at home, as it is [p] of the way from the key: its size, shape, light. */
    fun place(at: Placement, sizePx: Float): Placement {
        val p = p.value
        val q = p.coerceIn(0f, 1f)
        val w = key.width / sizePx
        val h = key.height / sizePx
        val sx = w + (1f - w) * q
        val sy = h + (1f - h) * q
        return Placement(
            centre = at.centre,
            scale = at.scale * sx,
            squash = sy / sx,
            alpha = at.alpha * (p / HandoffMotion.FADE).coerceIn(0f, 1f) * fade.value,
            glyphs = at.glyphs * smoothstep(HandoffMotion.WAVE_FROM, HandoffMotion.WAVE_TO, p),
            round = at.round,
            key = 1f - q,
        )
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
