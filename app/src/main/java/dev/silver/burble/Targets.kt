package dev.silver.burble

/**
 * Where the words go: the last text field the user tapped while dictating, else the field the
 * dictation started in. [insert] tries one and answers whether the words landed, maybe later;
 * [done] gets the field that took them, or null when neither did and they go to the clipboard.
 */
internal fun <T> pickTarget(tapped: T?, start: T?, insert: (T, (Boolean) -> Unit) -> Unit, done: (T?) -> Unit) {
    val fields = listOfNotNull(tapped, start).distinct()
    fun tryFrom(i: Int) {
        if (i == fields.size) return done(null)
        insert(fields[i]) { took -> if (took) done(fields[i]) else tryFrom(i + 1) }
    }
    tryFrom(0)
}

/**
 * The field a dictation starts in: the first of [candidates] that [isField] (editable, not a
 * password) accepts, where [candidates] go from the surest to the last resort: input focus in the
 * active window, input focus in any window, a focused field inside a focused container, then the
 * last field an app focused by itself. No tap needed: a chat app that focuses its box on opening
 * has the field the user means.
 */
internal fun <T> startField(candidates: Sequence<T?>, isField: (T) -> Boolean): T? =
    candidates.filterNotNull().firstOrNull(isField)
