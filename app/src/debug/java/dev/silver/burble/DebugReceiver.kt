package dev.silver.burble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: drive the app from adb, where the emulator's microphone hears silence.
 *
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es text 'Hello there'
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --ef level 0.8
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es do start|stop|retry
 *
 * `text` delivers a finished dictation;
 * `level` replaces the mic level for the current dictation; `do` acts as the bubble would.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("text")?.let {
            Dictation.fakeResult(context, it)
        }
        when (intent.getStringExtra("do")) {
            "start" -> Dictation.start(context)
            "stop" -> Dictation.stop()
            "retry" -> Dictation.retry()
        }
        if (intent.hasExtra("level")) Dictation.fakeLevel(intent.getFloatExtra("level", 0f))
    }
}
