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

enum class BubbleSize { SMALL, MEDIUM, LARGE }

data class Settings(
    val output: Output = Output.INSERT,
    val triggers: Set<Trigger> = setOf(Trigger.BUBBLE),
    val shape: BubbleShape = BubbleShape.COOKIE_9,
    /** BCP-47 tag sent to the server. */
    val language: String = "en-US",
    val onboarded: Boolean = false,
    val bubbleSize: BubbleSize = BubbleSize.MEDIUM,
    val checkUpdates: Boolean = true,
    /** BCP-47 tags the user speaks; the first is [language], the others go as secondary locales. */
    val languages: List<String> = listOf(language),
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
        // languages leads: the first is the primary language every reader of language gets.
        val next = change(_settings.value).let { it.copy(language = it.languages.firstOrNull() ?: it.language) }
        sp.edit()
            .putString("output", next.output.name)
            .putStringSet("triggers", next.triggers.map { it.name }.toSet())
            .putString("shape", next.shape.name)
            .putString("language", next.language)
            .putBoolean("onboarded", next.onboarded)
            .putString("bubbleSize", next.bubbleSize.name)
            .putBoolean("checkUpdates", next.checkUpdates)
            .putString("languages", next.languages.joinToString(","))
            .apply()
        _settings.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        val language = sp.getString("language", null) ?: d.language
        return Settings(
            output = enumOr(sp.getString("output", null), d.output),
            triggers = sp.getStringSet("triggers", null)
                ?.mapNotNull { runCatching { Trigger.valueOf(it) }.getOrNull() }?.toSet()
                ?: d.triggers,
            shape = enumOr(sp.getString("shape", null), d.shape),
            language = language,
            onboarded = sp.getBoolean("onboarded", d.onboarded),
            bubbleSize = enumOr(sp.getString("bubbleSize", null), d.bubbleSize),
            checkUpdates = sp.getBoolean("checkUpdates", d.checkUpdates),
            languages = sp.getString("languages", null)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(language),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
