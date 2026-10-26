package dev.silver.burble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        /** [output] says where the text went: the field, or only the clipboard. */
        data class Done(val text: String, val output: Output, val delivered: Boolean) : State
        /**
         * No field to type into yet: [text] is on the clipboard and goes into the next field the
         * user focuses. Until then the keyboard stays hidden.
         */
        data class Ready(val text: String) : State
        /** [canRetry]: the audio is kept, and [retry] sends it again. */
        data class Error(
            val kind: ErrorKind,
            val detail: String? = null,
            val canRetry: Boolean = false,
        ) : State {
            /**
             * The error waits for the user, who taps to retry or drags to ✕ to drop the audio.
             * Quota and rejection don't: retrying now would fail the same way.
             */
            val waits get() = canRetry && kind != ErrorKind.QUOTA && kind != ErrorKind.REJECTED
        }
    }

    enum class ErrorKind { NO_MIC, NOTHING_HEARD, QUOTA, REJECTED, NETWORK, OTHER }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The voice against the room ([VoiceLevel]), 0..1, every 32 ms while listening.
     * Drives the wave, which treats anything under 0.5 as silence.
     */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: DictationSession? = null
    /** The field the dictation started in; the text goes back there even if it lost focus. */
    private var target: InsertionService.Target? = null
    @Volatile private var lastStream: S3Stream? = null
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
        target = InsertionService.instance?.captureTarget()
        lastStream = null
        levelOverride = null
        debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val settings = Prefs.settings.value
        // Read now, before the keyboard hides: the one the user is typing with says the language.
        val languages = if (settings.languagesFromKeyboard) KeyboardLanguages.read(app) else settings.languages
        val request = S3Request(
            language = languages.firstOrNull() ?: Locale.getDefault().toLanguageTag(),
            extraLanguages = languages.drop(1),
            triggerApp = InsertionService.focusedApp(),
        )
        lateinit var s: DictationSession
        s = DictationSession(
            dir = java.io.File(pending, System.currentTimeMillis().toString()),
            // The last stream holds the final result's intent: later segments and retries make new ones.
            newRecognizer = { onDone -> S3Stream(request, onDone).also { lastStream = it } },
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
        target = null
        _level.value = 0f
        _state.value = State.Idle
    }

    /**
     * Returns to [State.Idle] once an overlay has shown a [State.Done] or an [State.Error] that
     * doesn't wait; one that [State.Error.waits] keeps its audio for the user (P5).
     */
    fun acknowledge() {
        val st = _state.value
        if (st is State.Done || (st is State.Error && !st.waits)) _state.value = State.Idle
    }

    /** Drops text waiting in [State.Ready]; it stays on the clipboard. */
    fun discard() {
        if (_state.value is State.Ready) _state.value = State.Idle
    }

    /** [State.Ready]'s text went into a field the user focused. */
    internal fun readyInserted() {
        val ready = _state.value as? State.Ready ?: return
        inserted()
        _state.value = State.Done(ready.text, Output.INSERT, delivered = true)
    }

    /** Home says how to dictate until this first happens. */
    private fun inserted() {
        if (!Prefs.settings.value.dictated) Prefs.update { it.copy(dictated = true) }
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
    }

    /** Replaces the mic level until the dictation ends; for the debug build's test hook. */
    internal fun fakeLevel(level: Float) {
        levelOverride = level
    }

    /** Delivers [text] as if the server had sent it; for the debug build's test hook. */
    internal fun fakeResult(context: Context, text: String, intent: Int = UserIntent.DICTATION) {
        if (_state.value != State.Listening) target = InsertionService.instance?.captureTarget()
        session?.cancel()
        session = null
        _state.value = State.Finishing
        finish(context.applicationContext, null, Result.success(text), intent, text)
    }

    private fun finish(context: Context, from: DictationSession?, r: Result<String>) {
        val last = lastStream
        finish(context, from, r, last?.intent ?: UserIntent.DICTATION, last?.transcript)
    }

    private fun finish(context: Context, from: DictationSession?, r: Result<String>, intent: Int, transcript: String?) {
        if (_state.value == State.Idle || from !== session) return // cancelled or replaced
        _level.value = 0f
        // A failed dictation keeps its session, and with it the audio and the field, for retry.
        val retryable = r.isFailure && kindOf(r.exceptionOrNull()!!) != ErrorKind.NO_MIC
        val into = target
        if (!retryable) {
            session?.discard()
            session = null
            target = null
        }
        _state.value = r.fold(
            onSuccess = { text ->
                if (text.isBlank()) State.Error(ErrorKind.NOTHING_HEARD)
                else deliver(context, text, intent, transcript, into)
            },
            onFailure = { e ->
                android.util.Log.e(TAG, "dictation failed", e)
                State.Error(kindOf(e), e.message?.take(120), canRetry = retryable)
            },
        )
    }

    /**
     * Into the field the dictation started in while it still exists. Otherwise onto the
     * clipboard, where Android's own preview offers paste and edit, and [State.Ready] until the
     * user focuses a field. A result the server read as a command (edit, rewrite, compose) is
     * never typed as if dictated: the user's own words go to the clipboard only.
     */
    private fun deliver(
        context: Context, text: String, intent: Int, transcript: String?, into: InsertionService.Target?,
    ): State {
        if (!UserIntent.isDictation(intent)) {
            val words = transcript?.takeIf { it.isNotBlank() } ?: text
            copy(context, words)
            return State.Done(words, Output.CLIPBOARD, delivered = true)
        }
        if (into != null && InsertionService.instance?.insert(into, text) == true) {
            inserted()
            return State.Done(text, Output.INSERT, delivered = true)
        }
        if (into == null) Log.d(TAG, "insert fell back: no field had input focus at the start")
        copy(context, text)
        return State.Ready(text)
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
