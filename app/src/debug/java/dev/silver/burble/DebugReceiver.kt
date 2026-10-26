package dev.silver.burble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: drive the app from adb, where the emulator's microphone hears silence.
 *
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es text 'Hello there'
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --ef level 0.8
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es do start|stop|retry|fake
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es error NOTHING_HEARD
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --ei size 80
 *
 * `--ei after N` shows the loader for N ms first. `text` delivers a finished dictation;
 * `level` replaces the mic level for the current dictation; `do` acts as the bubble would.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("text")?.let {
            Dictation.fakeResult(context, it, intent.getIntExtra("after", 0).toLong())
        }
        when (intent.getStringExtra("do")) {
            "start" -> Dictation.start(context)
            "stop" -> Dictation.stop()
            "retry" -> Dictation.retry()
            "fake" -> Dictation.fakeListening()
        }
        intent.getStringExtra("error")?.let { Dictation.fakeError(Dictation.ErrorKind.valueOf(it), intent.getIntExtra("after", 0).toLong()) }
        if (intent.hasExtra("size")) Prefs.update { it.copy(bubbleSize = intent.getIntExtra("size", 64)) }
        if (intent.hasExtra("level")) Dictation.fakeLevel(intent.getFloatExtra("level", 0f))
    }
}
