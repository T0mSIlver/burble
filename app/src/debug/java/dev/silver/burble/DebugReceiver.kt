package dev.silver.burble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: drive the app from adb, where the emulator's microphone hears silence.
 *
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es output HOLD
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --es text 'Hello there'
 *     adb shell am broadcast -n dev.silver.burble/.DebugReceiver --ef level 0.8
 *
 * `output` sets [Settings.output]; `text` delivers a finished dictation through that output;
 * `level` replaces the mic level for the current dictation.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("output")?.let { name ->
            Prefs.update { it.copy(output = Output.valueOf(name)) }
        }
        intent.getStringExtra("text")?.let { Dictation.fakeResult(context, it) }
        if (intent.hasExtra("level")) Dictation.fakeLevel(intent.getFloatExtra("level", 0f))
    }
}
