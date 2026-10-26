package dev.silver.burble

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** PCM16 mono 16 kHz in, one Ogg stream out. Each server session needs a fresh one. */
interface AudioEncoder {
    /** Encodes one PCM16 buffer; returns the Ogg bytes completed since the last call. */
    fun encode(pcm: ByteArray): ByteArray
    /** Ends the stream and frees the encoder; returns the remaining Ogg bytes. */
    fun flush(): ByteArray
}

/**
 * The few MediaCodec calls the encoder makes, so tests can stall them. Every wait is bounded by
 * its timeout.
 */
internal interface OpusCodec {
    /** Queues up to [length] bytes of [pcm] from [offset]; returns how many, 0 when no buffer freed up in time. */
    fun queue(pcm: ByteArray, offset: Int, length: Int, timeoutUs: Long): Int
    /** Queues the end of the stream; false when no buffer freed up in time. */
    fun queueEnd(timeoutUs: Long): Boolean
    /** The next encoded packet, null when none came in time. */
    fun take(timeoutUs: Long): Packet?
    fun release()

    /** [config]: codec setup data, not audio. [end]: the last packet of the stream. */
    class Packet(val bytes: ByteArray, val config: Boolean, val end: Boolean)
}

/** Android's own Opus encoder, configured as Gboard's: 16 kHz mono at 24 kbps. */
internal class MediaCodecOpus : OpusCodec {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun queue(pcm: ByteArray, offset: Int, length: Int, timeoutUs: Long): Int {
        val idx = codec.dequeueInputBuffer(timeoutUs)
        if (idx < 0) return 0
        val buf = codec.getInputBuffer(idx)!!
        buf.clear()
        val n = minOf(length, buf.remaining())
        buf.put(pcm, offset, n)
        codec.queueInputBuffer(idx, 0, n, 0, 0)
        return n
    }

    override fun queueEnd(timeoutUs: Long): Boolean {
        val idx = codec.dequeueInputBuffer(timeoutUs)
        if (idx < 0) return false
        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }

    override fun take(timeoutUs: Long): OpusCodec.Packet? {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue // headers written by us
            if (idx < 0) return null
            val bytes = ByteArray(info.size).also { codec.getOutputBuffer(idx)!!.get(it) }
            codec.releaseOutputBuffer(idx, false)
            return OpusCodec.Packet(bytes,
                config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
                end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
        }
    }

    override fun release() {
        runCatching { codec.stop() }
        codec.release()
    }
}

/**
 * Streams PCM16 mono 16 kHz through MediaCodec's Opus encoder and produces Ogg Opus bytes,
 * mirroring the stream Gboard sends (16 kHz, 24 kbps, Ogg container, OpusHead preskip 312).
 * Feed with [encode]; call [flush] at end of audio.
 *
 * A busy codec never drops audio: PCM it can't take yet waits here and goes in first on the next
 * call. Every wait on the codec is bounded, so a hung codec fails with [CodecStalled] instead of
 * blocking the caller.
 */
class OggOpusEncoder internal constructor(
    private val codec: OpusCodec,
    /** How long [encode] waits for the codec before leaving the rest for the next call. */
    private val encodeWaitMs: Long = 40,
    /** How long the codec may take no audio at all before [encode] gives up on it. */
    private val stallMs: Long = 3_000,
    /** How long [flush] waits for the codec to take the last audio and end the stream. */
    private val flushMs: Long = 2_000,
) : AudioEncoder {

    constructor() : this(MediaCodecOpus())

    private val stopped = AtomicBoolean(false)
    private var pages = ByteArrayOutputStream()
    private var pageSeq = 0
    private var granule = 0L
    private var pendingSegments = ArrayList<ByteArray>()
    private var pendingLacing = 0
    /** PCM the codec hasn't taken yet, oldest first. */
    private val unqueued = ArrayDeque<ByteArray>()
    private var unqueuedAt = 0
    private var lastTakenMs = now()
    private var ended = false

    // Ogg page serial, fixed per stream.
    private val serial = java.util.Random().nextInt()

    init {
        val head = headerPacket()
        pages.write(oggPage(head, lacingFor(head.size), 0x02, 0))
        val tags = tagsPacket()
        pages.write(oggPage(tags, lacingFor(tags.size), 0x00, 0))
    }

    override fun encode(pcm: ByteArray): ByteArray {
        if (stopped.get() || released) return ByteArray(0)
        if (pcm.isNotEmpty()) unqueued.addLast(pcm)
        queueUnqueued(now() + encodeWaitMs)
        drain(0)
        if (unqueued.isNotEmpty() && now() - lastTakenMs > stallMs) {
            // The pages made so far stay for [flush].
            release()
            throw CodecStalled("the codec took no audio for $stallMs ms")
        }
        return takePages(force = false)
    }

    override fun flush(): ByteArray {
        if (!stopped.compareAndSet(false, true)) return ByteArray(0)
        if (released) return takePages(force = true)
        try {
            val deadline = now() + flushMs
            queueUnqueued(deadline)
            while (!codec.queueEnd(WAIT_US)) {
                drain(0)
                if (now() >= deadline) break
            }
            while (!ended && now() < deadline) drain(WAIT_US)
            if (unqueued.isNotEmpty() || !ended) Log.w(TAG, "the codec didn't finish within $flushMs ms; its last frames are lost")
            return takePages(force = true)
        } finally {
            release()
        }
    }

    private var released = false

    private fun release() {
        if (released) return
        released = true
        runCatching { codec.release() }
    }

    /** Hands the codec what it will take before [deadline]; the rest stays queued here. */
    private fun queueUnqueued(deadline: Long) {
        while (unqueued.isNotEmpty()) {
            val pcm = unqueued.first()
            val n = codec.queue(pcm, unqueuedAt, pcm.size - unqueuedAt, WAIT_US)
            if (n > 0) {
                lastTakenMs = now()
                unqueuedAt += n
                if (unqueuedAt == pcm.size) {
                    unqueued.removeFirst()
                    unqueuedAt = 0
                }
                continue
            }
            // Output the codec holds may be what keeps its input buffers busy.
            drain(0)
            if (now() >= deadline) return
        }
    }

    private fun drain(timeoutUs: Long) {
        while (!ended) {
            val packet = codec.take(timeoutUs) ?: return
            if (packet.config) continue
            addPacket(packet.bytes)
            if (packet.end) ended = true
        }
    }

    private fun addPacket(packet: ByteArray) {
        val lacing = lacingCount(packet.size)
        if (pendingSegments.isNotEmpty() && pendingLacing + lacing > 255) emitPage(0x00)
        pendingSegments.add(packet)
        pendingLacing += lacing
        // One 20 ms frame at 48 kHz per packet.
        granule += SAMPLES_PER_PACKET
    }

    /**
     * Every call ends a page, as Gboard's native writer does in its low-latency mode: the server
     * hears each buffer as it comes, so little is left to recognize after the stop.
     */
    private fun takePages(force: Boolean): ByteArray {
        if (pendingSegments.isNotEmpty()) emitPage(if (force) 0x04 else 0x00) // 0x04: EOS
        val out = pages.toByteArray()
        pages.reset()
        return out
    }

    private fun emitPage(flags: Int) {
        val body = ByteArrayOutputStream().also { b -> pendingSegments.forEach { b.write(it) } }
        val lac = ByteArrayOutputStream()
        pendingSegments.forEach { p -> lac.write(lacingFor(p.size)) }
        pages.write(oggPage(body.toByteArray(), lac.toByteArray(), flags, granule))
        pendingSegments = ArrayList()
        pendingLacing = 0
    }

    private fun lacingCount(size: Int) = size / 255 + 1

    /** Lacing values for one packet: a run of 255s then the remainder. */
    private fun lacingFor(size: Int): ByteArray {
        val out = ArrayList<Int>()
        var n = size
        while (n >= 255) { out.add(255); n -= 255 }
        out.add(n)
        return ByteArray(out.size) { i -> out[i].toByte() }
    }

    private fun headerPacket(): ByteArray {
        // OpusHead v1, 1 channel, preskip 312, 16 kHz input, gain 0, mapping family 0.
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray(Charsets.ISO_8859_1))
        b.write(1)
        b.write(1)
        b.write(312 and 0xFF); b.write(312 shr 8)
        writeIntLE(b, 16000)
        writeShortLE(b, 0)
        b.write(0)
        return b.toByteArray()
    }

    private fun tagsPacket(): ByteArray {
        val vendor = Identity.OGG_VENDOR.toByteArray(Charsets.ISO_8859_1)
        val b = ByteArrayOutputStream()
        b.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        writeIntLE(b, vendor.size)
        b.write(vendor)
        writeIntLE(b, 0)
        return b.toByteArray()
    }

    /** Builds one Ogg page from body bytes, lacing values and flags, with granulepos [gpos]. */
    private fun oggPage(body: ByteArray, lacing: ByteArray, flags: Int, gpos: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // version
        out.write(flags)
        writeLongLE(out, gpos)
        writeIntLE(out, serial)
        writeIntLE(out, pageSeq++)
        writeIntLE(out, 0) // CRC placeholder
        out.write(lacing.size)
        out.write(lacing)
        out.write(body)
        val page = out.toByteArray()
        val crc = crc32Mpeg(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = (crc shr 8 and 0xFF).toByte()
        page[24] = (crc shr 16 and 0xFF).toByte()
        page[25] = (crc shr 24 and 0xFF).toByte()
        return page
    }

    private fun writeIntLE(b: ByteArrayOutputStream, v: Int) {
        b.write(v and 0xFF); b.write(v shr 8 and 0xFF)
        b.write(v shr 16 and 0xFF); b.write(v shr 24 and 0xFF)
    }

    private fun writeShortLE(b: ByteArrayOutputStream, v: Int) {
        b.write(v and 0xFF); b.write(v shr 8 and 0xFF)
    }

    private fun writeLongLE(b: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) b.write((v shr (8 * i) and 0xFF).toInt())
    }

    companion object {
        private const val TAG = "OggOpusEncoder"
        private const val WAIT_US = 10_000L
        private fun now() = System.nanoTime() / 1_000_000
        const val SAMPLES_PER_PACKET = 960L // 20 ms at the Opus clock of 48 kHz

        /** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0, no reflection, no xorout), as Ogg requires. */
        fun crc32Mpeg(buf: ByteArray): Long {
            var crc = 0L
            for (byte in buf) {
                crc = crc xor ((byte.toInt() and 0xFF).toLong() shl 24)
                repeat(8) {
                    crc = if (crc and 0x80000000L != 0L) (crc shl 1) xor 0x04C11DB7L else crc shl 1
                    crc = crc and 0xFFFFFFFFL
                }
            }
            return crc
        }
    }
}

/** The codec stopped taking audio. */
class CodecStalled(message: String) : IOException(message)
