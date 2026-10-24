package dev.silver.ramblerbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Microphone foreground service, running exactly while [Dictation.state] is Listening or
 * Finishing. It keeps the process and its microphone access alive when the user leaves the
 * app mid-dictation, and shows the recording notification with Stop and Cancel.
 *
 * Starting it from the background is the hard part. A microphone foreground service needs
 * the while-in-use RECORD_AUDIO permission at creation; from Android 14 creating one while
 * the app is in the background throws SecurityException
 * (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions).
 * Every trigger starts it while the app holds that permission:
 * - Accessibility button: comes through [ButtonService], itself an accessibility service.
 * - Quick Settings tile: needs [InsertionService] connected.
 *   The system binds accessibility services with BIND_INCLUDE_CAPABILITIES and
 *   BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS (AOSP AccessibilityServiceConnection.bindLocked),
 *   so our process inherits system_server's microphone capability and may start foreground
 *   services from the background ("your app can start an activity from the background").
 *   A tile click also puts us on the power-save temp allowlist for 15 s
 *   (AOSP TileLifecycleManager.onClick, REASON_TILE_ONCLICK).
 * - Bubble: hosted by [InsertionService] as an accessibility overlay, same binding as above.
 * - In-app buttons: the activity is visible.
 */
class MicService : Service() {

    private val scope = MainScope()
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Notification actions arrive through startService, which needs no startForeground.
            ACTION_STOP -> Dictation.stop()
            ACTION_CANCEL -> Dictation.cancel()
            // startForeground must come before any stopSelf: stopping a service started with
            // startForegroundService before it goes foreground crashes the app.
            else -> try {
                startForeground(NOTIF_ID, notification(Dictation.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: RuntimeException) {
                // Started without while-in-use access. Recording may still work while a trigger
                // keeps us foreground; the notification is what's lost.
                Log.w(TAG, "could not go foreground", e)
            }
        }
        if (!watching) {
            watching = true
            scope.launch {
                Dictation.state.collect { st ->
                    if (st.recording) {
                        nm().notify(NOTIF_ID, notification(st))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun notification(st: Dictation.State): Notification {
        nm().createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.mic_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        // Android 12+ delays foreground service notifications by up to 10 s; a recording
        // indicator must show at once.
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return if (st == Dictation.State.Finishing) {
            b.setContentTitle(getString(R.string.mic_finishing))
                .addAction(action(ACTION_CANCEL, R.string.mic_cancel, 3))
                .build()
        } else {
            b.setContentTitle(getString(R.string.mic_listening))
                .addAction(action(ACTION_CANCEL, R.string.mic_cancel, 3))
                .addAction(action(ACTION_STOP, R.string.mic_stop, 2))
                .build()
        }
    }

    private fun action(action: String, label: Int, code: Int) = Notification.Action.Builder(
        null, getString(label),
        PendingIntent.getService(this, code, Intent(this, MicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE),
    ).build()

    companion object {
        private const val TAG = "MicService"
        private const val CHANNEL = "listening"
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "dev.silver.ramblerbubble.mic.STOP"
        private const val ACTION_CANCEL = "dev.silver.ramblerbubble.mic.CANCEL"

        /** Called by [Dictation.start] once the state is Listening. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MicService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+): no trigger exemption
                // applied. Dictation goes on while the app stays foreground.
                Log.w(TAG, "foreground service start refused", e)
            }
        }
    }
}

/** True while audio is being captured or the final text is pending. */
val Dictation.State.recording: Boolean
    get() = this == Dictation.State.Listening || this == Dictation.State.Finishing
