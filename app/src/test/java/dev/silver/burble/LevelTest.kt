package dev.silver.burble

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The wave only moves if speech maps to the middle of 0..1. A linear amplitude scale put
// speech from the unprocessed VOICE_RECOGNITION source near 0, and the wave sat still.
class LevelTest {
    /** 100 ms of a 440 Hz sine whose RMS is [dbfs], as 16-bit little-endian PCM. */
    private fun tone(dbfs: Double): ByteArray {
        val amplitude = 32768 * 10.0.pow(dbfs / 20) * sqrt(2.0)
        val pcm = ByteArray(3200)
        for (i in 0 until 1600) {
            val v = (amplitude * sin(2 * PI * 440 * i / 16000)).roundToInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    @Test fun speechLandsMidScale() {
        for ((dbfs, level) in listOf(-70.0 to 0f, -60.0 to 0f, -40.0 to 0.5f, -30.0 to 0.75f, -20.0 to 1f, -6.0 to 1f)) {
            assertEquals("$dbfs dBFS", level, DictationSession.levelOf(tone(dbfs), 3200), 0.02f)
        }
    }
}
