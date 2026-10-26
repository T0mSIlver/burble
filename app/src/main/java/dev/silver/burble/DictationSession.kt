package dev.silver.burble

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream

/**
 * One dictation: AudioRecord (16 kHz mono PCM16) -> [AudioEncoder] -> [Recognizer], one encoder
 * and one server session per segment. The server ends a session at 300 s, so a long dictation is
 * a chain of segments: near the cap the next one opens and the audio goes on there with no gap,
 * and the segments' texts are joined ([combine]).
 *
 * Each segment's Ogg bytes are written to [dir] as they are made. A segment whose server session
 * fails is sent again from its file, once by itself when its audio ends and then on [retry], so
 * no failure loses the words. [discard] deletes the files once the text is delivered.
 *
 * [onResult] runs on a network or recording thread, again after each [retry].
 */
class DictationSession(
    private val dir: File,
    private val newRecognizer: (onDone: (Result<Heard>) -> Unit) -> Recognizer,
    private val onResult: (Result<Heard>) -> Unit,
    /** The wave's level every 32 ms, 0..1: the voice against the room ([VoiceLevel]). */
    private val onLevel: (Float) -> Unit = {},
    /** The recording ended, by [stop], silence or a broken microphone; on the recording thread. */
    private val onAudioEnd: () -> Unit = {},
    /** Stops after this long without voice; 0 never does. */
    private val silenceMs: Long = 30_000,
    private val newEncoder: () -> AudioEncoder = { OggOpusEncoder() },
    private val limits: Limits = Limits(),
) {
    /**
     * Segments switch at the first quiet buffer after [switchFromMs], and by [switchByMs] at the
     * latest, which leaves the old session 20 s to answer before the server's 300 s cap (Gboard
     * warns at 280 s).
     */
    data class Limits(val switchFromMs: Long = 265_000, val switchByMs: Long = 280_000)

    /** Only the recording thread touches [encoder], and never under [lock]: codec calls wait. */
    private inner class Segment(val index: Int, val startMs: Long, val encoder: AudioEncoder) {
        val file = File(dir, "$index.ogg")
        val out = FileOutputStream(file)
        /** Null once its live session has failed: the audio then only goes to [file]. */
        var recognizer: Recognizer? = null
        var attempt = 0
        var recording = true
        var result: Result<Heard>? = null
    }

    private val lock = Any()
    private val segments = ArrayList<Segment>()
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private var audioEnded = false
    private var reported = false
    private var cancelled = false
    private var lastVoiceMs = 0L
    private val voice = VoiceLevel()

    val isActive get() = thread?.isAlive == true && !stopping

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (thread != null) return
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 32000))
                record = r
                if (r.state != AudioRecord.STATE_INITIALIZED) throw MicException("init ${r.state}")
                r.startRecording()
                // Another app holding the mic (a call, a recorder) fails here, not at init.
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicException("busy")
                begin(SystemClock.elapsedRealtime())
                // Read 32 ms at a time, as Gboard measures its level, and hand the audio on in
                // the same 100 ms buffers as before.
                val chunk = ByteArray(LEVEL_BYTES)
                val pcm = ByteArray(3200) // 100 ms
                var fill = 0
                var more = true
                while (!stopping && more) {
                    val n = r.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    hear(chunk, n)
                    var at = 0
                    while (at < n) {
                        val k = minOf(n - at, pcm.size - fill)
                        System.arraycopy(chunk, at, pcm, fill, k)
                        fill += k
                        at += k
                        if (fill == pcm.size) {
                            fill = 0
                            if (!audio(pcm.copyOf(), SystemClock.elapsedRealtime())) { more = false; break }
                        }
                    }
                }
                if (more && fill > 0) audio(pcm.copyOf(fill), SystemClock.elapsedRealtime())
                stopping = true
                endAudio()
                if (!cancelled) onAudioEnd()
            } catch (e: Exception) {
                stopping = true
                // Audio already captured is kept: end it like a stop. A failure before any audio
                // is the microphone's.
                val none = synchronized(lock) {
                    segments.isEmpty().also { if (it) { reported = true; audioEnded = true } }
                }
                if (none) onResult(Result.failure(e)) else {
                    endAudio()
                    if (!cancelled) onAudioEnd()
                }
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
        }.apply { start() }
    }

    /** Ends the audio; the result follows. */
    fun stop() {
        stopping = true
    }

    /** Drops the dictation: no result follows, and its audio is deleted. */
    fun cancel() {
        stopping = true
        synchronized(lock) {
            cancelled = true
            segments.forEach { it.recognizer?.cancel() }
        }
        discard()
    }

    /**
     * Sends the segments that failed again, from their files, after a failed result. False when
     * there is nothing to send again.
     */
    fun retry(): Boolean = synchronized(lock) {
        if (cancelled || !reported || segments.none { it.result?.isFailure == true }) return false
        reported = false
        segments.filter { it.result?.isFailure == true }.forEach {
            it.result = null
            resend(it)
        }
        true
    }

    /** Deletes the recorded audio. */
    fun discard() {
        synchronized(lock) { segments.forEach { runCatching { it.out.close() } } }
        dir.deleteRecursively()
    }

    // ---- driven by the recording thread; internal for tests ----

    internal fun begin(nowMs: Long) {
        val encoder = newEncoder()
        synchronized(lock) {
            if (cancelled) return@synchronized null
            dir.mkdirs()
            lastVoiceMs = nowMs
            open(nowMs, encoder)
        } ?: release(encoder)
    }

    /** Takes 32 ms of PCM for the wave's level. */
    internal fun hear(pcm: ByteArray, n: Int) {
        if (!cancelled) onLevel(voice.feed(pcm, n))
    }

    /** Takes one PCM buffer; false when silence has lasted [silenceMs]. */
    internal fun audio(pcm: ByteArray, nowMs: Long): Boolean {
        val level = levelOf(pcm, pcm.size)
        val quiet = level < QUIET
        val (seg, switch, more) = synchronized(lock) {
            if (cancelled) return false
            if (!quiet) lastVoiceMs = nowMs
            val seg = segments.last()
            val age = nowMs - seg.startMs
            Triple(seg, age >= limits.switchFromMs && (quiet || age >= limits.switchByMs),
                silenceMs <= 0 || nowMs - lastVoiceMs < silenceMs)
        }
        var into = seg
        if (switch) {
            // The next session opens before this one ends, so no audio falls between them.
            val encoder = newEncoder()
            into = synchronized(lock) { if (cancelled) null else open(nowMs, encoder) }
                ?: run { release(encoder); return false }
            close(seg)
        }
        val ogg = into.encoder.encode(pcm)
        synchronized(lock) { if (!cancelled) write(into, ogg) }
        return more
    }

    internal fun endAudio() {
        val last = synchronized(lock) {
            if (audioEnded) return
            audioEnded = true
            segments.lastOrNull()?.takeIf { it.recording }
        }
        // Closing a cancelled segment still frees its codec.
        last?.let(::close)
        synchronized(lock) { if (!cancelled) reportIfDone() }
    }

    // ---- segments ----

    private fun open(nowMs: Long, encoder: AudioEncoder): Segment {
        val seg = Segment(segments.size, nowMs, encoder)
        segments.add(seg)
        seg.recognizer = connect(seg)
        return seg
    }

    private fun connect(seg: Segment): Recognizer {
        val attempt = ++seg.attempt
        return newRecognizer { r -> done(seg, attempt, r) }.also { it.start() }
    }

    private fun write(seg: Segment, ogg: ByteArray) {
        if (ogg.isEmpty()) return
        seg.out.write(ogg)
        seg.recognizer?.feed(ogg)
    }

    /** Ends a segment's audio; a segment whose live session failed is sent again whole. */
    private fun close(seg: Segment) {
        // A codec that broke loses only its last few frames; the rest is already written.
        val tail = runCatching { seg.encoder.flush() }.getOrElse { ByteArray(0) }
        synchronized(lock) {
            if (cancelled) return
            runCatching { write(seg, tail) }
            runCatching { seg.out.close() }
            seg.recording = false
            val live = seg.recognizer
            if (live != null) live.finish() else resend(seg)
        }
    }

    private fun release(encoder: AudioEncoder) {
        runCatching { encoder.flush() }
    }

    private fun resend(seg: Segment) {
        val r = connect(seg)
        seg.recognizer = r
        r.feed(seg.file.readBytes())
        r.finish()
    }

    private fun done(seg: Segment, attempt: Int, r: Result<Heard>) = synchronized(lock) {
        if (cancelled || attempt != seg.attempt) return
        if (r.isFailure && seg.recording) {
            // The rest of this segment's audio goes to its file only, and is sent when it ends.
            android.util.Log.w(TAG, "segment ${seg.index} failed while recording", r.exceptionOrNull())
            seg.recognizer = null
            return
        }
        seg.result = r
        reportIfDone()
    }

    private fun reportIfDone() {
        if (!audioEnded || reported || segments.any { it.result == null }) return
        reported = true
        val failure = segments.firstNotNullOfOrNull { it.result?.exceptionOrNull() }
        onResult(if (failure != null) Result.failure(failure)
            else Result.success(combine(segments.map { it.result!!.getOrThrow() })))
    }

    companion object {
        private const val TAG = "DictationSession"
        /** Level under which a buffer counts as silence: -50 dBFS, under speech at arm's length. */
        private const val QUIET = 0.25f
        /** 512 samples, 32 ms: Gboard's level cadence. */
        private const val LEVEL_BYTES = 1024

        /**
         * Loudness of 16-bit little-endian PCM, 0..1: -55 dBFS (a quiet room) to -25 dBFS (normal
         * speech close to the phone), linear in decibels. VOICE_RECOGNITION audio has no gain
         * control, so speech at arm's length sits near -40 dBFS. Tells voice from silence for
         * segment switches and the silence stop; the wave uses [VoiceLevel].
         */
        internal fun levelOf(pcm: ByteArray, n: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                sum += v.toDouble() * v
                i += 2
            }
            val rms = kotlin.math.sqrt(sum / maxOf(1, n / 2)) / 32768.0
            val db = 20 * kotlin.math.log10(maxOf(rms, 1e-6))
            return ((db + 55) / 30).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/**
 * One result for a chain of segments, each read by the server on its own. The words go as
 * dictation unless every segment that heard any was a command: a command heard in one segment
 * never takes the others' words to the clipboard, and gives its own words, not the server's
 * answer, to the text.
 */
internal fun combine(parts: List<Heard>): Heard {
    val said = parts.filter { it.words.isNotBlank() }
    if (said.isNotEmpty() && said.all { it.isCommand }) {
        val words = joinTexts(said.map { it.words })
        return Heard(words, said.first().intent, words)
    }
    return Heard(joinTexts(parts.map { it.words }))
}

/**
 * Joins the texts of consecutive segments. A space goes between two texts unless one side
 * already has whitespace, the next starts with closing punctuation, or either side is in a
 * script written without spaces (Chinese, Japanese, Thai and their neighbours).
 */
internal fun joinTexts(parts: List<String>): String {
    val out = StringBuilder()
    for (part in parts) {
        if (part.isBlank()) continue
        if (out.isNotEmpty() && needsSpace(out.last(), part.first())) out.append(' ')
        out.append(part)
    }
    return out.toString()
}

private fun needsSpace(before: Char, after: Char): Boolean {
    if (before.isWhitespace() || after.isWhitespace()) return false
    if (after in ",.;:!?)]}»”’…、。，．！？：；）」』") return false
    if (before in "([{«“‘¿¡（「『") return false
    return !unspaced(before) && !unspaced(after)
}

private fun unspaced(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
    Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
    Character.UnicodeScript.TIBETAN -> true
    else -> false
}

/** The microphone could not be opened: permission revoked, or another app holds it. */
class MicException(message: String) : Exception(message)
