package dev.silver.burble

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The wave's level, as Gboard's Rambler bar measures it: how far the voice stands above the
 * room, not how loud it is. Each 512-sample block's spread (standard deviation) is compared
 * with a noise floor that creeps up slowly and drops fast; 10 × log10(spread / floor) from -2
 * to 10 maps to 0..1. A quiet room settles near 0.17; the wave treats anything under 0.5 as
 * silence. One instance per dictation, fed every buffer in order.
 */
class VoiceLevel {
    private var floor = 75.0

    /** The level of 16-bit little-endian PCM: the loudest of its 512-sample blocks. */
    fun feed(pcm: ByteArray, n: Int): Float {
        var best = 0f
        var start = 0
        while (start + 1 < n) {
            val end = minOf(n, start + BLOCK * 2)
            best = maxOf(best, block(pcm, start, end))
            start = end
        }
        return best
    }

    private fun block(pcm: ByteArray, from: Int, to: Int): Float {
        var sum = 0.0
        var sq = 0.0
        var count = 0
        var i = from
        while (i + 1 < to) {
            val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toDouble()
            sum += v
            sq += v * v
            count++
            i += 2
        }
        val spread = if (count == 0) 0.0 else sqrt(maxOf(0.0, (sq * count - sum * sum) / (count.toDouble() * count)))
        floor = if (floor < spread) floor * 0.999 + 0.001 * spread else floor * 0.95 + 0.05 * spread
        val db = if (floor > 0 && spread / floor > 1e-6) 10 * log10(spread / floor) else -120.0
        return ((db.coerceIn(-2.0, 10.0) + 2) / 12).toFloat()
    }

    private companion object {
        const val BLOCK = 512
    }
}
