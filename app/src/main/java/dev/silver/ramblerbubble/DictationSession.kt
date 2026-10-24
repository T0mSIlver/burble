package dev.silver.ramblerbubble

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * One dictation session: AudioRecord (16 kHz mono PCM16) -> OggOpusEncoder -> S3Stream.
 * Runs its own thread; callbacks arrive on the session's handler thread.
 */
class DictationSession(
    private val onPartial: (String) -> Unit,
    private val onResult: (Result<String>) -> Unit,
    /** Microphone RMS level, 0..1, per 100 ms buffer. */
    private val onLevel: (Float) -> Unit = {},
    private val language: String = "en-US",
    private val maxMs: Long = 295_000, // server cuts the session at 300 s
) {
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private val done = java.util.concurrent.atomic.AtomicBoolean(false)
    private var stream: S3Stream? = null

    val isActive get() = thread?.isAlive == true && !stopping

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (thread != null) return
        val s = S3Stream(onPartial, ::finish, language)
        stream = s
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 32000))
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord init failed: ${r.state}")
                }
                record = r
                s.start()
                r.startRecording()
                val pcm = ByteArray(3200) // 100 ms
                val t0 = android.os.SystemClock.elapsedRealtime()
                while (!stopping && android.os.SystemClock.elapsedRealtime() - t0 < maxMs) {
                    val n = r.read(pcm, 0, pcm.size)
                    if (n <= 0) break
                    onLevel(rms(pcm, n))
                    val ogg = encoder.get().encode(pcm.copyOf(n))
                    if (ogg.isNotEmpty()) s.feed(ogg)
                }
                if (!stopping) stop() // hit the session cap: behave like a tap
                val tail = encoder.get().flush()
                if (tail.isNotEmpty()) s.feed(tail)
                s.finish()
            } catch (e: Exception) {
                s.cancel()
                finish(Result.failure(e))
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                encoder.remove()
            }
        }.apply { start() }
    }

    fun stop() {
        stopping = true
    }

    fun cancel() {
        stopping = true
        stream?.cancel()
    }

    private fun finish(r: Result<String>) {
        if (!done.compareAndSet(false, true)) return
        stopping = true
        thread = null
        onResult(r)
    }

    /** RMS of 16-bit little-endian PCM, scaled so normal speech sits around 0.3–0.7. */
    private fun rms(pcm: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += v.toDouble() * v
            i += 2
        }
        val r = kotlin.math.sqrt(sum / maxOf(1, n / 2)) / 32768.0
        return (r * 6).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        // The encoder is thread-local because the session thread is created once per session.
        private val encoder = object : InheritableThreadLocal<OggOpusEncoder>() {
            override fun initialValue() = OggOpusEncoder()
        }
    }
}
