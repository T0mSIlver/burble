package dev.silver.burble

import android.net.Uri
import android.os.Build

/**
 * A bug report for a phone we never tested on: versions, the device, and Burble's recent log
 * lines. The user reads it on [dev.silver.burble.ui.ReportScreen] before it goes anywhere; app
 * names in the log stay out unless they keep them.
 */
object Report {
    /** Tags whose lines carry no dictated text. S3DBG, debug-only, can include transcripts. */
    private val TAGS = listOf("Dictation", "DictationSession", "Insert", "MicService", "Overlay", "Updater", "OggOpusEncoder", "S3Stream")
    private const val MAX_LOG = 4000

    class Draft(val phone: String, val log: String) {
        fun body(keepAppNames: Boolean) = buildString {
            append("**What happened**\n\n\n\n**What you expected**\n\n\n\n")
            append("**Phone**\n").append(phone).append('\n')
            append("**Log**\n```\n").append(if (keepAppNames) log else redact(log)).append("\n```\n")
        }
    }

    /** Reads logcat, which spawns a process: call it off the main thread. */
    fun collect(version: String, languages: List<String>) = Draft(
        phone = buildString {
            append("- Burble ").append(version).append('\n')
            append("- ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("- Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- Languages: ").append(languages.joinToString(", ")).append('\n')
        },
        log = recentLog(),
    )

    fun issueUrl(source: String, body: String): String =
        Uri.parse("$source/issues/new").buildUpon().appendQueryParameter("body", body).build().toString()

    /** Package-like names (com.example.chat) other than Android's, Java's and Burble's own. */
    private val PACKAGE = Regex("""\b[a-z][a-z0-9_]*(?:\.[A-Za-z0-9_]+)+\b""")
    private val KEPT = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "dev.silver.burble")

    /** Puts `<app>` in place of every app name in [log]. */
    internal fun redact(log: String): String = PACKAGE.replace(log) { m ->
        val name = m.value
        if (KEPT.any { name.startsWith(it) } || name.endsWith(".com")) name else "<app>"
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
