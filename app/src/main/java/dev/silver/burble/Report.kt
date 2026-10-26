package dev.silver.burble

import android.net.Uri
import android.os.Build

/**
 * A GitHub issue prefilled with what a bug report needs from a phone we never tested on:
 * versions, the device, and Burble's recent log lines. The user sees and edits it before sending.
 */
object Report {
    /** Tags whose lines carry no dictated text. S3DBG, debug-only, can include transcripts. */
    private val TAGS = listOf("Dictation", "DictationSession", "Insert", "MicService", "Overlay", "Updater")
    private const val MAX_LOG = 4000

    fun issueUrl(source: String, version: String, languages: List<String>): String {
        val body = buildString {
            append("**What happened**\n\n\n\n**What you expected**\n\n\n\n")
            append("**Phone**\n")
            append("- Burble ").append(version).append('\n')
            append("- ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("- Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- Languages: ").append(languages.joinToString(", ")).append("\n\n")
            append("**Log** (app names appear where an insert fell back; delete what you'd rather not share)\n")
            append("```\n").append(recentLog()).append("\n```\n")
        }
        return Uri.parse("$source/issues/new").buildUpon().appendQueryParameter("body", body).build().toString()
    }

    /** An app reads only its own lines from logcat; the newest that fit the URL win. */
    private fun recentLog(): String = runCatching {
        val filter = TAGS.map { "$it:D" } + "*:S"
        val p = ProcessBuilder(listOf("logcat", "-d", "-v", "time") + filter).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (text.length <= MAX_LOG) text else text.takeLast(MAX_LOG).substringAfter('\n')
    }.getOrElse { "(log unavailable: ${it.message})" }.ifEmpty { "(no lines)" }
}
