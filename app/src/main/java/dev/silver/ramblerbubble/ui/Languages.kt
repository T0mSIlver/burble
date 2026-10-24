package dev.silver.ramblerbubble.ui

import java.util.Locale

/** Common dictation languages, as BCP-47 tags. Rambler takes any of them; the list stays short. */
val Languages = listOf(
    "en-US", "en-GB", "en-IN", "en-AU", "en-CA",
    "fr-FR", "fr-CA", "de-DE", "es-ES", "es-US", "es-MX", "it-IT", "pt-BR", "pt-PT",
    "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "cs-CZ", "ro-RO", "tr-TR", "el-GR",
    "ru-RU", "uk-UA", "ar-SA", "he-IL", "hi-IN", "id-ID", "vi-VN", "th-TH", "ja-JP", "ko-KR",
)

/** "English (United States)" in the phone's language. */
fun languageName(tag: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
