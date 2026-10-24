package dev.silver.ramblerbubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: drive the app from adb, where the emulator's microphone hears silence.
 *
 *     adb shell am broadcast -n dev.silver.ramblerbubble/.DebugReceiver --es output HOLD
 *     adb shell am broadcast -n dev.silver.ramblerbubble/.DebugReceiver --es text 'Hello there'
 *
 * `output` sets [Settings.output]; `text` delivers a finished dictation through that output.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("output")?.let { name ->
            Prefs.update { it.copy(output = Output.valueOf(name)) }
        }
        intent.getStringExtra("text")?.let { Dictation.fakeResult(context, it) }
    }
}
