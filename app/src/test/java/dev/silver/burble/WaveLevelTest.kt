package dev.silver.burble

import dev.silver.burble.overlay.WaveHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// The wave follows Gboard's bar: the voice measured against the room, silence under 0.5, and a
// glow that takes 200 ms to rise.
class WaveLevelTest {
    /** 100 ms of 16-bit PCM: noise at [noiseDbfs], plus a 220 Hz tone at [toneDbfs] if given. */
    private fun buffer(noiseDbfs: Double, toneDbfs: Double? = null, random: Random = Random(1)): ByteArray {
        val noise = 32768 * 10.0.pow(noiseDbfs / 20) * 1.732
        val tone = toneDbfs?.let { 32768 * 10.0.pow(it / 20) * 1.414 } ?: 0.0
        val pcm = ByteArray(3200)
        for (i in 0 until 1600) {
            val v = (noise * (random.nextDouble() * 2 - 1) + tone * sin(2 * PI * 220 * i / 16000)).roundToInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    @Test fun voiceStandsAboveTheRoom() {
        val level = VoiceLevel()
        val random = Random(1)
        var room = 0f
        repeat(30) { room = level.feed(buffer(-60.0, random = random), 3200) }
        assertTrue("room $room", room < 0.5f)
        val voice = level.feed(buffer(-60.0, -35.0, random), 3200)
        assertTrue("voice $voice", voice > 0.9f)
    }

    @Test fun openingFlashOutlastsSilence() {
        val host = WaveHost(0.0)
        for (ms in 4..160 step 4) {
            if (ms % 32 == 0) host.feed(0.17f, ms.toDouble())
            host.step(ms.toDouble())
        }
        assertEquals(1.5f, host.frame(160.0).dots, 0.001f)
        for (ms in 164..800 step 4) host.step(ms.toDouble())
        assertEquals(0f, host.frame(800.0).dots, 0.01f)
    }

    // Gboard's 200 ms ramp as a critically damped spring: quiet is ignored, a voice swells the
    // glow 10 to 90 % in the ramp's 160 ms, never past its level.
    @Test fun glowRisesLikeGboardsRampAndIgnoresQuiet() {
        val host = WaveHost(0.0)
        host.feed(0.45f, 0.0)
        for (ms in 4..400 step 4) host.step(ms.toDouble())
        assertEquals(0f, host.frame(400.0).glow, 0f)
        host.feed(0.8f, 400.0)
        var tenAt = 0; var ninetyAt = 0
        for (ms in 404..900 step 4) {
            host.step(ms.toDouble())
            val g = host.frame(ms.toDouble()).glow
            assertTrue("no overshoot at $ms: $g", g <= 0.8f + 1e-4f)
            if (tenAt == 0 && g >= 0.08f) tenAt = ms
            if (ninetyAt == 0 && g >= 0.72f) ninetyAt = ms
        }
        assertEquals(160.0, (ninetyAt - tenAt).toDouble(), 8.0)
        assertEquals(0.8f, host.frame(900.0).glow, 0.005f)
        assertEquals(1.5f, host.frame(900.0).dots, 0.01f)
    }
}
