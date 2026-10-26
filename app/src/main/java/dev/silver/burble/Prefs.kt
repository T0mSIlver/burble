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
enum class BubbleShape { COOKIE_9, SUNNY, CLOVER_4, CIRCLE, OVAL, SOFT_BURST }

/** What the bubble becomes after 3 s untouched in Idle. */
enum class WhenIdle { FULL, SHRINK, DOT }

/** When the bubble shows. */
enum class BubbleShow {
    ALWAYS,
    /** While an editable field has input focus, and through a dictation and its waiting text. */
    TYPING,
}

data class Settings(
    val output: Output = Output.INSERT,
    val triggers: Set<Trigger> = setOf(Trigger.BUBBLE),
    val shape: BubbleShape = BubbleShape.COOKIE_9,
    /** BCP-47 tag sent to the server. */
    val language: String = "en-US",
    val onboarded: Boolean = false,
    /** The bubble's side, 40 to 96 dp in steps of 8. */
    val bubbleSize: Int = 64,
    val hideKeyboard: Boolean = true,
    val checkUpdates: Boolean = true,
    /** BCP-47 tags the user chose; the first is [language], the others go as secondary locales. */
    val languages: List<String> = listOf(language),
    /** Dictate in the keyboard's languages, read at each start, rather than [languages]. */
    val languagesFromKeyboard: Boolean = true,
    /** "Use Burble": the main switch on home and the Quick Settings tile. Off, the bubble is gone. */
    val bubbleOn: Boolean = true,
    /** A dictation has gone into a field once: home stops saying how to dictate. */
    val dictated: Boolean = false,
    val bubbleShow: BubbleShow = BubbleShow.ALWAYS,
    /** The bubble's opacity in Idle, 20 to 100 %; every other state is opaque. */
    val idleOpacity: Int = 100,
    val whenIdle: WhenIdle = WhenIdle.SHRINK,
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
            .remove("bubbleSize")
            .putInt("bubbleSizeDp", next.bubbleSize)
            .putBoolean("checkUpdates", next.checkUpdates)
            .putBoolean("hideKeyboard", next.hideKeyboard)
            .putString("languages", next.languages.joinToString(","))
            .putBoolean("languagesFromKeyboard", next.languagesFromKeyboard)
            .putBoolean("bubbleOn", next.bubbleOn)
            .putBoolean("dictated", next.dictated)
            .putString("bubbleShow", next.bubbleShow.name)
            .putInt("idleOpacity", next.idleOpacity)
            .putString("whenIdle", next.whenIdle.name)
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
            // The pill became the oval.
            shape = enumOr(sp.getString("shape", null)?.replace("PILL", "OVAL"), d.shape),
            language = language,
            onboarded = sp.getBoolean("onboarded", d.onboarded),
            // The first versions had three sizes.
            bubbleSize = sp.getInt("bubbleSizeDp", 0).takeIf { it > 0 } ?: when (sp.getString("bubbleSize", null)) {
                "SMALL" -> 48
                "LARGE" -> 80
                else -> d.bubbleSize
            },
            checkUpdates = sp.getBoolean("checkUpdates", d.checkUpdates),
            hideKeyboard = sp.getBoolean("hideKeyboard", d.hideKeyboard),
            languages = sp.getString("languages", null)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(language),
            languagesFromKeyboard = sp.getBoolean("languagesFromKeyboard", d.languagesFromKeyboard),
            bubbleOn = sp.getBoolean("bubbleOn", d.bubbleOn),
            dictated = sp.getBoolean("dictated", d.dictated),
            bubbleShow = enumOr(sp.getString("bubbleShow", null), d.bubbleShow),
            idleOpacity = sp.getInt("idleOpacity", d.idleOpacity),
            whenIdle = enumOr(sp.getString("whenIdle", null), d.whenIdle),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
