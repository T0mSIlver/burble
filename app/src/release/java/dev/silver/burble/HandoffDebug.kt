package dev.silver.burble

/** Release builds trust no caller but the keyboard; src/debug's version also takes adb's shell. */
object HandoffDebug {
    @Suppress("UNUSED_PARAMETER")
    fun trusts(uid: Int) = false
}
