package dev.silver.ramblerbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service (microphone type) that owns the floating bubble.
 * Tap the bubble to start dictation, tap again to stop and insert the text.
 * Drag to move.
 */
class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private val scope = kotlinx.coroutines.MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        addBubble()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---- bubble ----

    private fun addBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 400
        }

        val view = ImageView(this).apply {
            setImageDrawable(androidx.core.content.ContextCompat
                .getDrawable(this@BubbleService, R.drawable.ic_mic))
            background = androidx.core.content.ContextCompat
                .getDrawable(this@BubbleService, R.drawable.bubble)
            backgroundTintList = ColorStateList.valueOf(0xFF424243.toInt())
            elevation = 8f
            contentDescription = getString(R.string.bubble_desc)
        }
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        view.setOnTouchListener { _, e ->
            // Double-tap-and-hold to talk; single tap toggles. Drag moves the bubble.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 12 || abs(e.rawY - downY) > 12) moved = true
                    if (moved) {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        bubble = view
    }

    private fun toggle() = Dictation.toggle(this)

    /** Mirrors [Dictation.state] in the bubble's colour; errors and empty results toast. */
    private fun observe() {
        scope.launch {
            Dictation.state.collect { st ->
                when (st) {
                    Dictation.State.Idle -> setState(State.IDLE)
                    Dictation.State.Listening -> setState(State.LISTENING)
                    Dictation.State.Finishing -> setState(State.STOPPING)
                    is Dictation.State.Done -> {
                        setState(State.DONE)
                        bubble?.postDelayed({ Dictation.acknowledge() }, 1500)
                    }
                    is Dictation.State.Error -> {
                        setState(State.ERROR)
                        toast(when (st.kind) {
                            Dictation.ErrorKind.NO_MIC -> getString(R.string.no_mic)
                            Dictation.ErrorKind.NOTHING_HEARD -> getString(R.string.empty_result)
                            Dictation.ErrorKind.QUOTA -> getString(R.string.quota)
                            else -> getString(R.string.failed, st.detail ?: st.kind.name)
                        })
                        bubble?.postDelayed({ Dictation.acknowledge() }, 1500)
                    }
                }
            }
        }
    }

    private fun setState(state: State) {
        val v = bubble ?: return
        val color = when (state) {
            State.IDLE -> 0xFF424243.toInt()
            State.LISTENING -> 0xFFB3261E.toInt()
            State.STOPPING -> 0xFF7D5260.toInt()
            State.DONE -> 0xFF2E6B34.toInt()
            State.ERROR -> 0xFF9A4A00.toInt()
        }
        v.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun teardown() {
        Dictation.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    // ---- notification ----

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel),
            NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getService(this, 1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), pi).build())
            .build()
    }

    private enum class State { IDLE, LISTENING, STOPPING, DONE, ERROR }

    companion object {
        private const val TAG = "RamblerBubble"
        private const val CHANNEL = "rambler"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.silver.ramblerbubble.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleService::class.java).setAction(ACTION_STOP))
        }
    }
}
