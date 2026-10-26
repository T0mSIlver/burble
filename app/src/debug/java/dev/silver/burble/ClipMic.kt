package dev.silver.burble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Plays [pcm] (16 kHz mono PCM16) in real time as if the microphone heard it, then silence; a
 * stop follows [stopAfterMs] after the clip, as a tap would.
 */
internal class ClipMic(private val pcm: ByteArray, private val stopAfterMs: Long) : PcmSource {
    private val startMs = SystemClock.elapsedRealtime()
    private var at = 0L
    private var stopPosted = false

    override fun read(buf: ByteArray): Int {
        // Each buffer comes when its last sample would have been heard.
        val due = startMs + (at + buf.size) * 1000 / BYTES_PER_S
        SystemClock.sleep(maxOf(0, due - SystemClock.elapsedRealtime()))
        buf.fill(0)
        if (at < pcm.size) System.arraycopy(pcm, at.toInt(), buf, 0, minOf(buf.size.toLong(), pcm.size - at).toInt())
        at += buf.size
        if (!stopPosted && at >= pcm.size + stopAfterMs * BYTES_PER_S / 1000) {
            stopPosted = true
            Handler(Looper.getMainLooper()).post { Dictation.stop() }
        }
        return buf.size
    }

    override fun close() = Unit

    companion object {
        private const val BYTES_PER_S = 32_000L

        /** [ms] of the clip at files/clip.pcm, which the timing script pushes. */
        fun fromFile(file: File, ms: Int, stopAfterMs: Long): ClipMic {
            val all = file.readBytes()
            val n = if (ms > 0) minOf(all.size, ms * 32) else all.size
            return ClipMic(all.copyOf(n - n % 2), stopAfterMs)
        }
    }
}
