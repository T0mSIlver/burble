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

/**
 * The start field when no field has focus: the one text field under [root], found by [isText]
 * (editable and on screen, password fields included so they count). Null when there is none, two
 * or more, or the tree has more than [maxNodes] nodes: then the words go to the clipboard, as
 * before. The Claude app (1.261102.19) stopped focusing its message box when a chat opens, so a
 * dictation started without a tap had no field (RMB-46).
 */
internal fun <T> soleField(root: T, children: (T) -> List<T>, isText: (T) -> Boolean, maxNodes: Int): T? {
    var found: T? = null
    var seen = 0
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        if (++seen > maxNodes) return null
        val n = pending.removeLast()
        if (isText(n)) {
            if (found != null) return null
            found = n
        }
        pending.addAll(children(n))
    }
    return found
}
