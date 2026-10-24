package dev.silver.burble

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where finished text goes. */
enum class Output {
    /** Into the focused field; the clipboard when no field has focus. */
    INSERT,
    /** Kept in the overlay until the user inserts, copies or dismisses it. */
    HOLD,
    /** Straight to the clipboard, so Android's own clipboard preview can edit it. */
    CLIPBOARD,
}

/** Ways to start and stop dictation. Any combination can be on. */
enum class Trigger { BUBBLE, ACCESSIBILITY_BUTTON, QUICK_SETTINGS }

/** The bubble's shape, by MaterialShapes name. */
enum class BubbleShape { COOKIE_9, SUNNY, CLOVER_4, CIRCLE, PILL, SOFT_BURST }

data class Settings(
    val output: Output = Output.INSERT,
    val triggers: Set<Trigger> = setOf(Trigger.BUBBLE),
    val shape: BubbleShape = BubbleShape.COOKIE_9,
    /** BCP-47 tag sent to the server. */
    val language: String = "en-US",
    val onboarded: Boolean = false,
)

/** App settings in SharedPreferences, mirrored in a StateFlow every screen and service reads. */
object Prefs {
    private const val FILE = "settings"
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _settings.value = read()
    }

    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        sp.edit()
            .putString("output", next.output.name)
            .putStringSet("triggers", next.triggers.map { it.name }.toSet())
            .putString("shape", next.shape.name)
            .putString("language", next.language)
            .putBoolean("onboarded", next.onboarded)
            .apply()
        _settings.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            output = enumOr(sp.getString("output", null), d.output),
            triggers = sp.getStringSet("triggers", null)
                ?.mapNotNull { runCatching { Trigger.valueOf(it) }.getOrNull() }?.toSet()
                ?: d.triggers,
            shape = enumOr(sp.getString("shape", null), d.shape),
            language = sp.getString("language", null) ?: d.language,
            onboarded = sp.getBoolean("onboarded", d.onboarded),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
