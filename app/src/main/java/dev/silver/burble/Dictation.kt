package dev.silver.burble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
        data class Error(val kind: ErrorKind, val detail: String? = null) : State
    }

    enum class ErrorKind { NO_MIC, NOTHING_HEARD, QUOTA, REJECTED, NETWORK, OTHER }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Microphone level, 0..1, about ten updates a second while listening. Drives the wave. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: DictationSession? = null

    fun toggle(context: Context) {
        when (_state.value) {
            State.Listening -> stop()
            // The session is no longer active while finishing; starting here would run two.
            State.Finishing -> Unit
            else -> start(context)
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
        _state.value = State.Listening
        val s = DictationSession(
            onPartial = {},
            onLevel = { _level.value = it },
            onResult = { r -> main.post { finish(app, r) } },
            language = Prefs.settings.value.language.ifBlank { Locale.getDefault().toLanguageTag() },
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

    /** Delivers [text] as if the server had sent it; for the debug build's test hook. */
    internal fun fakeResult(context: Context, text: String) {
        session?.cancel()
        session = null
        _state.value = State.Finishing
        finish(context.applicationContext, Result.success(text))
    }

    private fun finish(context: Context, r: Result<String>) {
        if (_state.value == State.Idle) return // cancelled
        session = null
        _level.value = 0f
        _state.value = r.fold(
            onSuccess = { text ->
                if (text.isBlank()) State.Error(ErrorKind.NOTHING_HEARD)
                else deliver(context, text)
            },
            onFailure = { e ->
                android.util.Log.e(TAG, "dictation failed", e)
                State.Error(kindOf(e), e.message?.take(120))
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
