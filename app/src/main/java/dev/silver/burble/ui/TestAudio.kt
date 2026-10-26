package dev.silver.burble.ui

import android.content.Context
import dev.silver.burble.S3Request
import dev.silver.burble.S3Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Streams the bundled 12 s English clip through the app's own client, to check the server. */
object TestAudio {

    sealed interface State {
        data object Idle : State
        data object Sending : State
        data class Heard(val text: String) : State
        data class Failed(val detail: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun send(context: Context) {
        if (_state.value == State.Sending) return
        _state.value = State.Sending
        val assets = context.applicationContext.assets
        Thread {
            val audio = assets.open("test12s.opus").use { it.readBytes() }
            val s = S3Stream(
                S3Request(),
                onDone = { r ->
                    _state.value = r.fold(
                        onSuccess = { State.Heard(it.text.trim()) },
                        onFailure = { State.Failed(it.message?.take(120)) },
                    )
                },
            )
            s.start()
            s.feed(audio)
            s.finish()
        }.start()
    }
}
