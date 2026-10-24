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

/** When the bubble shows. It always shows while dictating or while a card is open. */
enum class BubbleVisibility { FIELD_FOCUSED, ALWAYS }

/** Where text goes when its field is gone: the start app closed, or no field had focus. */
enum class Fallback { CARD, CLIPBOARD }

data class Settings(
    val output: Output = Output.INSERT,
    val triggers: Set<Trigger> = setOf(Trigger.BUBBLE),
    val shape: BubbleShape = BubbleShape.COOKIE_9,
    /** BCP-47 tag sent to the server. */
    val language: String = "en-US",
    val onboarded: Boolean = false,
    /** Always show the card instead of inserting. Replaces [Output.HOLD]. */
    val reviewBeforeInserting: Boolean = false,
    /** Copy the text instead of inserting it. Replaces [Output.CLIPBOARD]. */
    val copyInsteadOfInserting: Boolean = false,
    val fallback: Fallback = Fallback.CARD,
    val hideKeyboard: Boolean = true,
    /** Send the field's text as context, so "make it formal" edits it. */
    val voiceEditing: Boolean = true,
    /** Perform the field's IME action, or click the app's send button, after inserting. */
    val sendAfterInserting: Boolean = false,
    /** Seconds of silence that stop a dictation; 0 never stops. */
    val stopAfterSilenceSeconds: Int = 120,
    val maskOffensiveWords: Boolean = false,
    val bubbleVisibility: BubbleVisibility = BubbleVisibility.FIELD_FOCUSED,
    val bubbleSize: BubbleSize = BubbleSize.MEDIUM,
    val sounds: Boolean = false,
    /** Days recent dictations stay on the phone; 0 keeps none. */
    val historyDays: Int = 1,
    /** Wallpaper colours on Android 12+; off is Google blue. */
    val materialYou: Boolean = true,
    val checkUpdates: Boolean = true,
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
            .putBoolean("reviewBeforeInserting", next.reviewBeforeInserting)
            .putBoolean("copyInsteadOfInserting", next.copyInsteadOfInserting)
            .putString("fallback", next.fallback.name)
            .putBoolean("hideKeyboard", next.hideKeyboard)
            .putBoolean("voiceEditing", next.voiceEditing)
            .putBoolean("sendAfterInserting", next.sendAfterInserting)
            .putInt("stopAfterSilenceSeconds", next.stopAfterSilenceSeconds)
            .putBoolean("maskOffensiveWords", next.maskOffensiveWords)
            .putString("bubbleVisibility", next.bubbleVisibility.name)
            .putString("bubbleSize", next.bubbleSize.name)
            .putBoolean("sounds", next.sounds)
            .putInt("historyDays", next.historyDays)
            .putBoolean("materialYou", next.materialYou)
            .putBoolean("checkUpdates", next.checkUpdates)
            .apply()
        _settings.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        val output = enumOr(sp.getString("output", null), d.output)
        return Settings(
            output = output,
            triggers = sp.getStringSet("triggers", null)
                ?.mapNotNull { runCatching { Trigger.valueOf(it) }.getOrNull() }?.toSet()
                ?: d.triggers,
            shape = enumOr(sp.getString("shape", null), d.shape),
            language = sp.getString("language", null) ?: d.language,
            onboarded = sp.getBoolean("onboarded", d.onboarded),
            // Installs from before these flags carry the choice in output.
            reviewBeforeInserting = sp.getBoolean("reviewBeforeInserting", output == Output.HOLD),
            copyInsteadOfInserting = sp.getBoolean("copyInsteadOfInserting", output == Output.CLIPBOARD),
            fallback = enumOr(sp.getString("fallback", null), d.fallback),
            hideKeyboard = sp.getBoolean("hideKeyboard", d.hideKeyboard),
            voiceEditing = sp.getBoolean("voiceEditing", d.voiceEditing),
            sendAfterInserting = sp.getBoolean("sendAfterInserting", d.sendAfterInserting),
            stopAfterSilenceSeconds = sp.getInt("stopAfterSilenceSeconds", d.stopAfterSilenceSeconds),
            maskOffensiveWords = sp.getBoolean("maskOffensiveWords", d.maskOffensiveWords),
            bubbleVisibility = enumOr(sp.getString("bubbleVisibility", null), d.bubbleVisibility),
            bubbleSize = enumOr(sp.getString("bubbleSize", null), d.bubbleSize),
            sounds = sp.getBoolean("sounds", d.sounds),
            historyDays = sp.getInt("historyDays", d.historyDays),
            materialYou = sp.getBoolean("materialYou", d.materialYou),
            checkUpdates = sp.getBoolean("checkUpdates", d.checkUpdates),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
