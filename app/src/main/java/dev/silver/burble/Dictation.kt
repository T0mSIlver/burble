package dev.silver.burble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The one dictation in progress, shared by every trigger (bubble, accessibility button,
 * Quick Settings tile). Triggers call [toggle] or [cancel]; overlays and the tile render [state].
 */
object Dictation {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        /** Audio ended; waiting for the server's final text. */
        data object Finishing : State
        /** [delivered] is false when the text is held for the user ([Output.HOLD]). */
        data class Done(val text: String, val output: Output, val delivered: Boolean) : State
        /** [canRetry]: the audio is kept, and [retry] sends it again. */
        data class Error(
            val kind: ErrorKind,
            val detail: String? = null,
            val canRetry: Boolean = false,
        ) : State
    }

    enum class ErrorKind { NO_MIC, NOTHING_HEARD, QUOTA, REJECTED, NETWORK, OTHER }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Microphone level, 0..1, about ten updates a second while listening. Drives the wave. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: DictationSession? = null
    private var debug = false
    @Volatile private var levelOverride: Float? = null

    fun toggle(context: Context) {
        when (_state.value) {
            State.Listening -> stop()
            // The session is no longer active while finishing; starting here would run two.
            State.Finishing -> Unit
            else -> if (!retry()) start(context)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (session?.isActive == true) return
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.Error(ErrorKind.NO_MIC)
            return
        }
        // Starting over drops the audio of a failed dictation, and anything a crash left behind.
        session?.cancel()
        val pending = java.io.File(app.noBackupFilesDir, "pending")
        pending.deleteRecursively()
        _state.value = State.Listening
        levelOverride = null
        debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val settings = Prefs.settings.value
        val request = S3Request(
            language = settings.language.ifBlank { Locale.getDefault().toLanguageTag() },
            extraLanguages = settings.languages.drop(1),
            triggerApp = InsertionService.focusedApp(),
        )
        lateinit var s: DictationSession
        s = DictationSession(
            dir = java.io.File(pending, System.currentTimeMillis().toString()),
            newRecognizer = { onDone -> S3Stream(request, onDone) },
            onLevel = { heard ->
                val l = levelOverride ?: heard
                _level.value = l
                if (debug) Log.d(TAG, "level %.2f".format(l))
            },
            onResult = { r -> main.post { finish(app, s, r) } },
        )
        session = s
        MicService.start(app)
        s.start()
    }

    /** Ends the audio and waits for the final text. */
    fun stop() {
        val s = session ?: return
        if (!s.isActive) return
        s.stop()
        _level.value = 0f
        _state.value = State.Finishing
    }

    /**
     * Sends the kept audio of a failed dictation again; the bubble calls it when tapped in the
     * error state. False when there is nothing to send.
     */
    fun retry(): Boolean {
        val st = _state.value as? State.Error ?: return false
        val s = session ?: return false
        if (!st.canRetry || !s.retry()) return false
        _state.value = State.Finishing
        return true
    }

    /** Drops the dictation without delivering anything. */
    fun cancel() {
        session?.cancel()
        session = null
        _level.value = 0f
        _state.value = State.Idle
    }

    /** Returns to [State.Idle] once an overlay has shown a [State.Done] or [State.Error]. */
    fun acknowledge() {
        if (_state.value is State.Done || _state.value is State.Error) _state.value = State.Idle
    }

    /** Types [text] into the focused field, as [Output.INSERT] does; used for held text. */
    fun insert(context: Context, text: String) = InsertionService.deliver(context, text)

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
    }

    /** Replaces the mic level until the dictation ends; for the debug build's test hook. */
    internal fun fakeLevel(level: Float) {
        levelOverride = level
    }

    /** Delivers [text] as if the server had sent it; for the debug build's test hook. */
    internal fun fakeResult(context: Context, text: String) {
        session?.cancel()
        session = null
        _state.value = State.Finishing
        finish(context.applicationContext, null, Result.success(text))
    }

    private fun finish(context: Context, from: DictationSession?, r: Result<String>) {
        if (_state.value == State.Idle || from !== session) return // cancelled or replaced
        _level.value = 0f
        // A failed dictation keeps its session, and with it the audio, for retry.
        val retryable = r.isFailure && kindOf(r.exceptionOrNull()!!) != ErrorKind.NO_MIC
        if (!retryable) {
            session?.discard()
            session = null
        }
        _state.value = r.fold(
            onSuccess = { text ->
                if (text.isBlank()) State.Error(ErrorKind.NOTHING_HEARD)
                else deliver(context, text)
            },
            onFailure = { e ->
                android.util.Log.e(TAG, "dictation failed", e)
                State.Error(kindOf(e), e.message?.take(120), canRetry = retryable)
            },
        )
    }

    private fun deliver(context: Context, text: String): State.Done {
        // Without the accessibility service the insert broadcast reaches no one: copy instead,
        // and say so.
        val output = Prefs.settings.value.output.let {
            if (it == Output.INSERT && !canInsert(context)) Output.CLIPBOARD else it
        }
        when (output) {
            Output.INSERT -> InsertionService.deliver(context, text)
            Output.CLIPBOARD -> copy(context, text)
            Output.HOLD -> Unit
        }
        return State.Done(text, output, delivered = output != Output.HOLD)
    }

    private fun canInsert(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    internal fun kindOf(e: Throwable): ErrorKind = when (e) {
        is MicException, is SecurityException -> ErrorKind.NO_MIC
        is S3Exception -> when (e.grpcStatus) {
            8 -> ErrorKind.QUOTA // RESOURCE_EXHAUSTED
            // PERMISSION_DENIED, UNAUTHENTICATED: the key or client is refused. UNIMPLEMENTED:
            // the method is gone. Each needs a new app version, not a retry.
            7, 16, 12 -> ErrorKind.REJECTED
            4, 14 -> ErrorKind.NETWORK // DEADLINE_EXCEEDED, UNAVAILABLE
            else -> ErrorKind.OTHER
        }
        is java.io.IOException -> ErrorKind.NETWORK
        else -> ErrorKind.OTHER
    }

    private const val TAG = "Dictation"
}
