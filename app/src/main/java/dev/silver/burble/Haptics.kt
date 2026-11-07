package dev.silver.burble

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK as CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK as LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL as QUICK_FALL
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE as QUICK_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD as THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK as TICK

/** What the bubble marks with a vibration. */
enum class Haptic {
    /** Listening starts. */
    START,
    /** Listening stops: the words are on their way. */
    STOP,
    INSERTED,
    COPIED,
    ERROR,
    NOTHING_HEARD,
    /** A drag reaches ✕. */
    ARMED,
    /** A drag leaves ✕. */
    DISARMED,
    /** Dropped on ✕. */
    DROPPED,
    /** Let go after a drag: it snaps to its edge. */
    DOCKED,
}

/** One primitive of a composition: which, how strong, and how long after the one before. */
data class Beat(val primitive: Int, val scale: Float, val delayMs: Int = 0)

/**
 * Burble's haptics, one place for every event: short compositions of Android's haptic primitives,
 * modest, on the touch-feedback channel, so they follow the system's touch feedback setting and
 * stay silent when it's off. A phone without a primitive gets the event's plain
 * HapticFeedbackConstants instead.
 */
object Haptics {
    /** The composition for [event], given the primitives the vibrator [has]; null: the fallback. */
    fun beats(event: Haptic, has: (Int) -> Boolean): List<Beat>? {
        val beats = when (event) {
            Haptic.START -> listOf(Beat(QUICK_RISE, 0.5f), Beat(CLICK, 0.6f))
            Haptic.STOP -> listOf(Beat(QUICK_FALL, 0.6f))
            Haptic.INSERTED -> listOf(Beat(CLICK, 0.8f))
            Haptic.COPIED -> listOf(Beat(TICK, 0.5f), Beat(TICK, 0.5f, 80))
            Haptic.ERROR -> if (has(THUD)) listOf(Beat(THUD, 0.6f)) else listOf(Beat(LOW_TICK, 0.8f), Beat(LOW_TICK, 0.8f, 80))
            Haptic.NOTHING_HEARD -> listOf(Beat(LOW_TICK, 0.7f))
            Haptic.ARMED -> listOf(Beat(TICK, 0.6f))
            Haptic.DOCKED -> listOf(Beat(LOW_TICK, 0.35f))
            Haptic.DISARMED, Haptic.DROPPED -> return null
        }
        return beats.takeIf { b -> b.all { has(it.primitive) } }
    }

    /** What [event] plays where the primitives aren't there; null: nothing. */
    fun fallback(event: Haptic, sdk: Int = Build.VERSION.SDK_INT): Int? = when (event) {
        Haptic.START -> HapticFeedbackConstants.GESTURE_START
        Haptic.STOP -> HapticFeedbackConstants.GESTURE_END
        Haptic.INSERTED, Haptic.COPIED, Haptic.DROPPED -> HapticFeedbackConstants.CONFIRM
        Haptic.ERROR, Haptic.NOTHING_HEARD -> HapticFeedbackConstants.REJECT
        // Android 12 and 13 have no threshold effects: a clock tick marks the snap alone.
        Haptic.ARMED -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.CLOCK_TICK
        Haptic.DISARMED -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else null
        Haptic.DOCKED -> null
    }

    /**
     * Plays [event] from [view]. While listening only the start plays: the microphone would hear
     * the motor.
     */
    fun play(view: View, event: Haptic, listening: Boolean = Dictation.state.value == Dictation.State.Listening) {
        if (listening && event != Haptic.START) return
        val context = view.context
        if (!touchFeedbackOn(context)) return
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        val beats = vibrator?.takeIf { it.hasVibrator() }?.let { v -> beats(event) { v.areAllPrimitivesSupported(it) } }
        if (vibrator == null || beats == null) {
            fallback(event)?.let(view::performHapticFeedback)
            return
        }
        val effect = VibrationEffect.startComposition().apply {
            for (b in beats) addPrimitive(b.primitive, b.scale, b.delayMs)
        }.compose()
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    }

    /**
     * The system's touch feedback setting. Android 13+ silences touch vibrations itself when
     * it's off; Android 12 leaves that to the app.
     */
    private fun touchFeedbackOn(context: Context) =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
