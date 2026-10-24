package dev.silver.ramblerbubble

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams PCM16 mono 16 kHz through MediaCodec's Opus encoder and produces Ogg Opus bytes,
 * mirroring the stream Gboard sends (16 kHz, 24 kbps, Ogg container, OpusHead preskip 312).
 * Feed with [encode]; call [flush] at end of audio.
 */
class OggOpusEncoder {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val stopped = AtomicBoolean(false)
    private var pages = ByteArrayOutputStream()
    private var pageSeq = 0
    private var granule = 0L
    private var pendingSegments = ArrayList<ByteArray>()
    private var pendingBytes = 0

    // Ogg page serial, fixed per stream.
    private val serial = java.util.Random().nextInt()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val head = headerPacket()
        pages.write(oggPage(head, lacingFor(head.size), 0x02, 0))
        val tags = tagsPacket()
        pages.write(oggPage(tags, lacingFor(tags.size), 0x00, 0))
    }

    /** Encodes one PCM16 buffer; returns Ogg bytes accumulated since the last call. */
    fun encode(pcm: ByteArray): ByteArray {
        if (stopped.get()) return ByteArray(0)
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            buf.put(pcm)
            codec.queueInputBuffer(idx, 0, pcm.size, 0, 0)
        }
        drain()
        return takePages(force = false)
    }

    /** Flushes the encoder; returns the remaining Ogg bytes. */
    fun flush(): ByteArray {
        if (!stopped.compareAndSet(false, true)) return ByteArray(0)
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(eos = true)
        val out = takePages(force = true)
        codec.stop()
        codec.release()
        return out
    }

    private fun drain(eos: Boolean = false) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* headers written by us */ }
                idx >= 0 -> {
                    val packet = ByteArray(info.size).also {
                        codec.getOutputBuffer(idx)!!.get(it)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                    // One 20 ms frame at 48 kHz per packet.
                    granule += SAMPLES_PER_PACKET
                    addPacket(packet)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!eos || idx != MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
    }

    private fun addPacket(packet: ByteArray) {
        val lacing = lacingCount(packet.size)
        if (pendingSegments.isNotEmpty() &&
            (pendingSegments.size + lacing > 255 || pendingBytes + packet.size > 32_000)
        ) {
            emitPage(0x00)
        }
        pendingSegments.add(packet)
        pendingBytes += packet.size
    }

    private fun takePages(force: Boolean): ByteArray {
        if (force && pendingSegments.isNotEmpty()) emitPage(0x04) // EOS
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
        pendingBytes = 0
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
        val vendor = "rambler-bubble".toByteArray(Charsets.ISO_8859_1)
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
