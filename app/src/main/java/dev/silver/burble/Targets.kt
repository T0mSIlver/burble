package dev.silver.burble

/**
 * Where the words go: the last text field the user tapped while dictating, else the field the
 * dictation started in; null when neither takes them, and the words go to the clipboard.
 * [insert] tries one and says whether the words landed.
 */
internal fun <T> pickTarget(tapped: T?, start: T?, insert: (T) -> Boolean): T? =
    listOfNotNull(tapped, start).distinct().firstOrNull(insert)
