package dev.silver.burble

import android.util.Log

/**
 * Each step of a dictation, from the tap, on a monotonic clock: `adb logcat -s Latency` shows
 * where the wait for the text goes.
 */
internal object Latency {
    @Volatile private var t0 = System.nanoTime()

    fun start(step: String) {
        t0 = System.nanoTime()
        mark(step)
    }

    fun mark(step: String) {
        Log.d("Latency", "%6d ms  %s".format((System.nanoTime() - t0) / 1_000_000, step))
    }
}
