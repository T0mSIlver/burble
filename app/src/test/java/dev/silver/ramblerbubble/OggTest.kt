package dev.silver.ramblerbubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class OggTest {

    @Test fun crcMatchesRealPage() {
        val ogg = javaClass.classLoader!!.getResourceAsStream("hello.opus")!!.readBytes()
        // First page: 27-byte header + 1 lacing byte + 19-byte OpusHead = 47 bytes.
        val pageLen = 27 + 1 + 19
        val page = ogg.copyOfRange(0, pageLen)
        val stored = (page[22].toInt() and 0xFF) or
            ((page[23].toInt() and 0xFF) shl 8) or
            ((page[24].toInt() and 0xFF) shl 16) or
            ((page[25].toInt() and 0xFF) shl 24)
        val zeroed = page.copyOf().also { java.util.Arrays.fill(it, 22, 26, 0.toByte()) }
        assertEquals(stored.toLong() and 0xFFFFFFFFL, OggOpusEncoder.crc32Mpeg(zeroed))
    }

    @Test fun lacingMatchesRealHeader() {
        val ogg = javaClass.classLoader!!.getResourceAsStream("hello.opus")!!.readBytes()
        // Offset 26 holds the segment count (1); offset 27 the lacing value (19 < 255).
        assertEquals(1, ogg[26].toInt())
        assertEquals(19, ogg[27].toInt())
        assertTrue(String(ogg.copyOfRange(28, 36), Charsets.ISO_8859_1) == "OpusHead")
    }

    @Test fun protobufRoundTrip() {
        val msg = Pb.str(1, "beyond-a2a-recognizer") + Pb.bool(2, true) +
            Pb.msg(293100, Pb.int(3, 10) + Pb.f32(2, 16000.0f) + Pb.int(4, 1) + Pb.int(9, 42))
        val fields = Pb.decode(msg)!!
        assertEquals(3, fields.size)
        assertEquals("beyond-a2a-recognizer",
            String(Pb.first(msg, 1)!!, Charsets.UTF_8))
        assertEquals(1L, Pb.firstVarint(msg, 2))
        val aasw = Pb.first(msg, 293100)!!
        assertEquals(10L, Pb.firstVarint(aasw, 3))
        assertEquals(16000.0f, java.lang.Float.intBitsToFloat(
            Pb.decode(aasw)!!.first { it.num == 2 && it.wt == 5 }
                .let { val b = it.value as ByteArray
                    (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24) }), 0f)
        assertEquals(42L, Pb.firstVarint(aasw, 9))
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(Pb.decode(byteArrayOf(0x0F, 0xFF.toByte()))
        )
    }
}
