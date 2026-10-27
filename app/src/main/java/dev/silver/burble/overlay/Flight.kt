package dev.silver.burble.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.silver.burble.ui.theme.Motion

/**
 * The handoff's motion, every number in one place, from the motion design
 * (Burble's design notes material/images/handoff-motion/handoff-motion.md): a keyboard's mic key
 * starts a dictation, and the bubble comes out of the key's disc, a copy of it at first, and
 * rises to the corner just above where the keyboard's top edge was as the keyboard slides away,
 * the light fading in mid-flight; when the words land it flies back into the key as the
 * keyboard slides up, and hands over to it in one frame.
 */
internal object HandoffMotion {
    /** Place, size and shape, both ways: tracks the keyboard's own 285 ms slide closely. */
    val spatial: FiniteAnimationSpec<Float> get() = Motion.spatial()
    /** The key's fill into the bubble's tonal colour, and its shadow; back again. */
    val skin: FiniteAnimationSpec<Float> get() = Motion.effects()
    /** The key's glyph out, and back: 90 % gone in 63 ms. It never scales. */
    val icon: FiniteAnimationSpec<Float> get() = Motion.fastEffects()
    /** The listening light, once out of the key: as from idle to listening on the bubble. */
    const val LIGHT_AFTER_MS = 60L
    val light: FiniteAnimationSpec<Float> get() = tween(120)
    /** Its light out as the words land, with no mic showing: Inserted's 166 ms fade. */
    val lightOut: FiniteAnimationSpec<Float> get() = tween(166)
    /** Fading back in at home after the keyboard covered the key and went again. */
    val fadeIn: FiniteAnimationSpec<Float> get() = Motion.effects()

    /** From the keyboard's first sign of coming back to the flight back: both land together. */
    const val RETURN_AFTER_MS = 60L
    /** Into the flight back: the fill and shadow turn back to the key's, then its glyph. */
    const val SKIN_BACK_AFTER_MS = 60L
    const val ICON_BACK_AFTER_MS = 110L
    /** Into the flight back: within 0.5 px of the key, the key shows and the bubble goes. */
    const val HANDOVER_MS = 400L

    /**
     * After the words land, how long the bubble waits for the keyboard (its window, or its
     * hello) before going home instead, when Burble is bringing it back to a field. The design
     * says 250 ms; a keyboard that ignores the first click comes back after 1 to 2 s on an
     * emulator (InsertionService.showKeyboard). With no field to bring it to, it goes at once.
     */
    const val WAIT_MS = 2000L

    /**
     * Where the bubble's centre waits during the dictation, for a bubble [size] px across: in its
     * own column on the key's side, [Geometry.PAD_DP] from the edge, and that far above
     * [keyboardTop]; inside the area clear of bars and cutouts.
     */
    fun spot(screen: Screen, key: Box, keyboardTop: Float, size: Float): Pair<Float, Float> {
        val safe = screen.safe
        val half = size / 2 + Geometry.PAD_DP * screen.density
        val x = if (key.centerX >= safe.centerX) safe.right - half else safe.left + half
        val y = (keyboardTop - half).coerceIn(safe.top + half, maxOf(safe.top + half, safe.bottom - half))
        return x to y
    }
}

/** What a keyboard says about its mic key: the disc's bounds (screen px), its fill and its glyph. */
internal class Key(val bounds: Box, val fill: Color? = null, val icon: ImageBitmap? = null)

/**
 * One flight between a keyboard's [key] and the dictation spot. [fromKey] and [toSpot] are the
 * window's offsets from the bubble's home for each end; [p] goes from 0 at the key to 1 at the
 * spot. Everything is read while drawing.
 */
internal class Flight(var key: Key, var fromKey: Pair<Float, Float>, private val toSpot: Pair<Float, Float>) {
    val p = Animatable(0f)
    /** 1: the key's fill and no shadow; 0: the bubble's. */
    val skin = Animatable(1f)
    /** The key's glyph. */
    val icon = Animatable(1f)
    /** What the bubble shows inside: the listening light, or after it the mic. */
    val light = Animatable(0f)
    /** Once back home after the keyboard covered the key: the bubble fades in there. */
    val fade = Animatable(1f)
    /** The dictation is over: the bubble goes back into the key once the keyboard comes. */
    var returning = false
    /** Flying into the key: shown, though the keyboard that hands off is up. */
    val homing = mutableStateOf(false)
    /** When the bubble hands over to the key, in uptimeMillis; 0 until a flight back starts. */
    var handoverAt = 0L

    /** The window's offset from home at [p]. */
    fun offset(p: Float = this.p.value) =
        (fromKey.first + (toSpot.first - fromKey.first) * p) to (fromKey.second + (toSpot.second - fromKey.second) * p)

    /**
     * [at], the bubble at home, as it is [p] of the way from the key: the size overshoots with
     * the spring, the shape doesn't.
     */
    fun place(at: Placement, sizePx: Float): Placement {
        val p = p.value
        val q = p.coerceIn(0f, 1f)
        val b = key.bounds
        val w = b.width / sizePx
        val h = b.height / sizePx
        val sx = w + (1f - w) * p
        val sy = h + (1f - h) * p
        return Placement(
            centre = at.centre,
            scale = at.scale * sx,
            squash = sy / sx,
            alpha = at.alpha * fade.value,
            glyphs = at.glyphs * light.value,
            round = at.round,
            key = 1f - q,
            skin = skin.value,
            fill = key.fill ?: Color.Unspecified,
            icon = key.icon,
            iconAlpha = icon.value,
        )
    }
}
