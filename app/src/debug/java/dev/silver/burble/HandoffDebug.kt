package dev.silver.burble

/**
 * Debug builds only: adb's shell (or root, on an emulator) counts as the keyboard for the
 * handoff, so `start` can be fired with fake key bounds without a patched Gboard:
 *
 *     adb shell content call --uri content://dev.silver.burble.handoff --method hello
 *     adb shell content call --uri content://dev.silver.burble.handoff --method start \
 *         --extra keyLeft:i:880 --extra keyTop:i:1700 --extra keyRight:i:1000 --extra keyBottom:i:1830 \
 *         --extra keyboardTop:i:1500 --extra keyboardHeight:i:900 --extra keyFill:i:-1
 *
 * A text field must still have focus.
 */
object HandoffDebug {
    fun trusts(uid: Int) = uid == SHELL_UID || uid == ROOT_UID

    private const val SHELL_UID = 2000
    private const val ROOT_UID = 0
}
