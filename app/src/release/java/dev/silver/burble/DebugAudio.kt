package dev.silver.burble

import android.content.Context

/** Release builds always hear the microphone and ask the server; src/debug's version can stand in for both. */
object DebugAudio {
    @Suppress("UNUSED_PARAMETER")
    fun mic(context: Context): (() -> PcmSource)? = null

    @Suppress("UNUSED_PARAMETER")
    fun recognizer(context: Context, onDone: (Result<String>) -> Unit): Recognizer? = null
}
