package dev.silver.burble

/**
 * When the bubble shows, without Android: what [InsertionService] decides from focus, the lock
 * screen and ✕. [F] is whatever tells two fields apart.
 */
internal class Visibility<F> {
    /** Dropped on ✕: hidden until the next unlock, or with "While typing" the next text field. */
    var snoozed = false
        private set
    private var snoozedIn: F? = null
    private var focusLeft = false

    /** ✕ while [field] had input focus (or none). */
    fun snooze(field: F?) {
        snoozed = true
        snoozedIn = field
        focusLeft = field == null
    }

    /** The next unlock ends a snooze; turning Burble back on does too. */
    fun wake() {
        snoozed = false
    }

    /**
     * Focus is now on [field] (null: no text field). With "While typing", another field, or the
     * same one after focus left it, ends a snooze.
     */
    fun focus(field: F?, mode: BubbleShow) {
        if (!snoozed || mode != BubbleShow.TYPING) return
        when {
            field == null -> focusLeft = true
            focusLeft || field != snoozedIn -> snoozed = false
        }
    }

    /**
     * A dictation always shows, snoozed or not, since the bubble is all there is to see of it;
     * otherwise the mode decides, unless snoozed. Burble's own screens are like any app's: there
     * the bubble shows what the settings do to it.
     */
    fun shows(dictating: Boolean, mode: BubbleShow, fieldFocused: Boolean) =
        dictating || !snoozed && (mode == BubbleShow.ALWAYS || fieldFocused)
}
