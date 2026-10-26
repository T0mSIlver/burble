package dev.silver.burble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EncoderBackpressureTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Takes nothing while [busy] says so, and turns each input it takes into one packet. */
    private class FakeCodec(var busy: () -> Boolean = { false }, val ends: Boolean = true) : OpusCodec {
        val taken = ByteArrayOutputStream()
        val out = ArrayDeque<OpusCodec.Packet>()
        var released = false

        override fun queue(pcm: ByteArray, offset: Int, length: Int, timeoutUs: Long): Int {
            if (busy()) { Thread.sleep(timeoutUs / 1000); return 0 }
            taken.write(pcm, offset, length)
            out.addLast(OpusCodec.Packet(pcm.copyOfRange(offset, offset + length), config = false, end = false))
            return length
        }

        override fun queueEnd(timeoutUs: Long): Boolean {
            if (busy()) { Thread.sleep(timeoutUs / 1000); return false }
            if (ends) out.addLast(OpusCodec.Packet(ByteArray(0), config = false, end = true))
            return true
        }

        override fun take(timeoutUs: Long): OpusCodec.Packet? = out.removeFirstOrNull()
            ?: run { if (timeoutUs > 0) Thread.sleep(timeoutUs / 1000); null }

        override fun release() { released = true }
    }

    private fun pcm(i: Int) = ByteArray(3200) { (i + it).toByte() }

    @Test fun aBusyCodecDropsNoAudio() {
        var refusals = 0
        val codec = FakeCodec(busy = { refusals++ % 7 < 5 })
        val enc = OggOpusEncoder(codec, encodeWaitMs = 20)
        val fed = ByteArrayOutputStream()
        for (i in 0 until 20) pcm(i).also { fed.write(it); enc.encode(it) }
        enc.flush()
        assertArrayEquals(fed.toByteArray(), codec.taken.toByteArray())
        assertTrue(codec.released)
    }

    @Test fun flushEndsInTimeWhenTheCodecNeverFinishes() {
        val codec = FakeCodec(ends = false)
        val enc = OggOpusEncoder(codec, flushMs = 200)
        enc.encode(pcm(0))
        val t0 = System.nanoTime()
        val tail = enc.flush()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertTrue(tail.isNotEmpty()) // the frame it did encode
        assertTrue(codec.released)
    }

    @Test fun aHungCodecFailsInsteadOfBlocking() {
        val codec = FakeCodec(busy = { true })
        val enc = OggOpusEncoder(codec, encodeWaitMs = 10, stallMs = 100, flushMs = 50)
        try {
            repeat(50) { enc.encode(pcm(it)) }
            fail("no CodecStalled")
        } catch (_: CodecStalled) {}
        assertTrue(codec.released)
        enc.flush()
    }

    @Test fun cancelDoesNotWaitForTheCodec() {
        val inCodec = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val stuck = object : AudioEncoder {
            override fun encode(pcm: ByteArray): ByteArray { inCodec.countDown(); unblock.await(); return pcm }
            override fun flush() = ByteArray(0)
        }
        val s = DictationSession(
            dir = tmp.newFolder(),
            newRecognizer = { done -> object : Recognizer {
                override fun start() {}
                override fun feed(chunk: ByteArray) {}
                override fun finish() = done(Result.success("x"))
                override fun cancel() {}
            } },
            onResult = {},
            silenceMs = 0,
            newEncoder = { stuck },
        )
        s.begin(0)
        val recording = Thread { s.audio(pcm(0), 0) }.apply { start() }
        assertTrue(inCodec.await(1, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        s.cancel()
        assertEquals(true, (System.nanoTime() - t0) / 1_000_000 < 500)
        unblock.countDown()
        recording.join(1000)
    }
}
