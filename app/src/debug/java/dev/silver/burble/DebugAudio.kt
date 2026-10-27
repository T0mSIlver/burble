package dev.silver.burble

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: stand-ins for the microphone and the server, for instrumented tests and the
 * device lab (docs/testlab.md), where nobody speaks. Every dictation, however it starts, takes
 * them while they're set; src/release's version has neither.
 *
 *  - clip: the dictation hears assets/clip.wav (the first 4.3 s of test12s.opus, 16 kHz mono)
 *    instead of the microphone, then stops 300 ms after it, as a tap would ([ClipMic]).
 *  - answer: the server isn't called; the dictation's text is this, 300 ms after its audio ends.
 *
 * From adb (or a test's instrumentation arguments, androidTest's DeviceSetup):
 *
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es audio clip|mic
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es answer 'Hello there'
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es answer ''   # the server again
 *
 * Kept in files/debug-audio, so they outlast a restart of the process.
 */
object DebugAudio {
    data class Mode(val clip: Boolean = false, val answer: String? = null)

    @Volatile private var mode: Mode? = null

    fun set(context: Context, m: Mode) {
        mode = m
        file(context).writeText("${if (m.clip) "clip" else "mic"}\n${m.answer ?: ""}")
        Log.i(TAG, "audio ${if (m.clip) "clip.wav" else "microphone"}, text from ${if (m.answer != null) "\"${m.answer}\"" else "the server"}")
    }

    fun mode(context: Context): Mode = mode ?: run {
        val lines = file(context).takeIf { it.exists() }?.readLines().orEmpty()
        Mode(clip = lines.getOrNull(0) == "clip", answer = lines.getOrNull(1)?.takeIf { it.isNotEmpty() })
    }.also { mode = it }

    /** The microphone's stand-in, or null for the microphone. */
    fun mic(context: Context): (() -> PcmSource)? {
        if (!mode(context).clip) return null
        val app = context.applicationContext
        return {
            Log.i(TAG, "clip.wav plays as the microphone")
            ClipMic(pcm(app.assets.open("clip.wav").use { it.readBytes() }), stopAfterMs = 300)
        }
    }

    /** The server's stand-in, or null for the server. */
    fun recognizer(context: Context, onDone: (Result<String>) -> Unit): Recognizer? =
        mode(context).answer?.let { Answer(it, onDone) }

    /** The samples of a PCM16 WAV: its data chunk. */
    internal fun pcm(wav: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "data") return wav.copyOfRange(at + 8, minOf(wav.size, at + 8 + size))
            at += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    /** Takes the audio and answers [text] once it ends, as the server would, offline. */
    private class Answer(private val text: String, private val onDone: (Result<String>) -> Unit) : Recognizer {
        @Volatile private var cancelled = false
        override fun start() = Unit
        override fun feed(chunk: ByteArray) = Unit
        override fun finish() {
            Thread {
                Thread.sleep(300)
                if (!cancelled) onDone(Result.success(text))
            }.start()
        }
        override fun cancel() { cancelled = true }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "debug-audio")
    private const val TAG = "DebugAudio"
}
