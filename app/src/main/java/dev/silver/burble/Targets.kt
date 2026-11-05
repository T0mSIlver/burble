package dev.silver.burble

/**
 * Where the words go: the last text field the user tapped while dictating, else the field the
 * dictation started in; null when neither takes them, and the words go to the clipboard.
 * [insert] tries one and says whether the words landed.
 */
internal fun <T> pickTarget(tapped: T?, start: T?, insert: (T) -> Boolean): T? =
    listOfNotNull(tapped, start).distinct().firstOrNull(insert)

/**
 * The field a dictation starts in: the first of [candidates] that [isField] (editable, not a
 * password) accepts, where [candidates] go from the surest to the last resort: input focus in the
 * active window, input focus in any window, a focused field inside a focused container, then the
 * last field an app focused by itself. No tap needed: a chat app that focuses its box on opening
 * has the field the user means.
 */
internal fun <T> startField(candidates: Sequence<T?>, isField: (T) -> Boolean): T? =
    candidates.filterNotNull().firstOrNull(isField)
