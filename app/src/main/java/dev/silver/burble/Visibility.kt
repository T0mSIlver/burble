package dev.silver.burble

/**
 * When the bubble shows, without Android: what [InsertionService] decides from focus, the lock
 * screen and ✕. [F] is whatever tells two fields apart.
 */
internal class Visibility<F> {
    /**
     * Set by ✕: hidden until the next unlock, or with "While typing" the next text field. [field]
     * had input focus then (null: none); [focusLeft] once focus has since left it.
     */
    private class Snooze<F>(val field: F?, var focusLeft: Boolean = field == null)

    private var snooze: Snooze<F>? = null

    val snoozed get() = snooze != null

    /** ✕ while [field] had input focus (or none). */
    fun snooze(field: F?) {
        snooze = Snooze(field)
    }

    /** The next unlock ends a snooze; turning Burble back on does too. */
    fun wake() {
        snooze = null
    }

    /**
     * Focus is now on [field] (null: no text field). With "While typing", another field, or the
     * same one after focus left it, ends a snooze.
     */
    fun focus(field: F?, mode: BubbleShow) {
        val s = snooze ?: return
        if (mode != BubbleShow.TYPING) return
        when {
            field == null -> s.focusLeft = true
            s.focusLeft || field != s.field -> snooze = null
        }
    }

    /**
     * A dictation always shows, snoozed or not, since the bubble is all there is to see of it;
     * otherwise the mode decides, unless snoozed: [typing] is a text field focused with the
     * keyboard up. Burble's own screens are like any app's: there the bubble shows what the
     * settings do to it. While a keyboard that hands off to Burble is up ([keyboardHandsOff]),
     * its mic key stands in for the bubble, which steps aside.
     */
    fun shows(dictating: Boolean, mode: BubbleShow, typing: Boolean, keyboardHandsOff: Boolean = false) =
        dictating || !snoozed && !keyboardHandsOff && (mode == BubbleShow.ALWAYS || typing)
}
