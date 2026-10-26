package dev.silver.burble

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * The languages the keyboard types in, which Burble dictates in unless the user chose others:
 * the keyboard's current language first, then its other enabled ones, as Gboard's own voice
 * typing sends them. A keyboard that lists none gives the phone's languages.
 */
object KeyboardLanguages {

    fun read(context: Context): List<String> {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val keyboard = runCatching {
            if (Build.VERSION.SDK_INT >= 34) imm.currentInputMethodInfo
            else Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { id -> imm.enabledInputMethodList.firstOrNull { it.id == id } }
        }.getOrNull()
        val enabled = keyboard?.let { runCatching { imm.getEnabledInputMethodSubtypeList(it, true) }.getOrNull() }.orEmpty()
        val current = runCatching { imm.currentInputMethodSubtype }.getOrNull()
        val system = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        return order(current?.tag(), enabled.mapNotNull { it.tag() }, system)
    }

    /** [current] first when it is a language, then [enabled], once each; else [system]. */
    internal fun order(current: String?, enabled: List<String>, system: List<String>): List<String> {
        val tags = (listOfNotNull(current) + enabled).distinct()
        return tags.ifEmpty { system.distinct() }
    }

    /** A keyboard layout's language as a BCP-47 tag; null for emoji, handwriting or voice. */
    private fun InputMethodSubtype.tag(): String? {
        if (mode.isNotEmpty() && mode != "keyboard") return null
        @Suppress("DEPRECATION")
        val tag = languageTag.ifEmpty { locale.replace('_', '-') }
        return tag.takeIf { it.isNotBlank() && it != "und" }
    }
}
