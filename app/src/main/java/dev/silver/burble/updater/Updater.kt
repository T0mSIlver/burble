package dev.silver.burble.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.silver.burble.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Updates from the GitHub releases of the app's repository: check the latest release, download
 * its APK, check it is signed with this app's key, and install it through a PackageInstaller
 * session, which shows Android's own confirmation.
 */
object Updater {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        /** Waiting for Android's install confirmation, or installing. */
        data class Installing(val release: Release) : State
        data class Failed(val reason: Reason, val release: Release? = null) : State
    }

    enum class Reason { NETWORK, NO_APK, WRONG_SIGNATURE, NOT_NEWER, INSTALL }

    private const val TAG = "Updater"
    private const val PREFS = "updater"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** At most once a day, for the automatic check when the app opens. */
    fun checkIfDue(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("checked", 0) < DAY_MS) return
        sp.edit().putLong("checked", now).apply()
        check(context, quiet = true)
    }

    /** With [quiet], a failed check leaves the state as it was: nobody asked for it. */
    fun check(context: Context, quiet: Boolean = false) {
        val busy = _state.value
        if (busy is State.Checking || busy is State.Downloading || busy is State.Installing) return
        val before = busy
        _state.value = State.Checking
        val current = installedVersion(context)
        Thread {
            _state.value = try {
                val release = latest()
                when {
                    release == null -> State.UpToDate
                    newer(release.version, current) -> State.Available(release)
                    else -> State.UpToDate
                }
            } catch (e: IOException) {
                Log.w(TAG, "update check failed", e)
                if (quiet) before else State.Failed(Reason.NETWORK)
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "update check: unexpected answer", e)
                if (quiet) before else State.Failed(Reason.NETWORK)
            }
        }.start()
    }

    /** The latest release with an APK; null when the repository has none, or isn't public yet. */
    private fun latest(): Release? {
        val req = Request.Builder().url(BuildConfig.UPDATES_URL)
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val json = JSONObject(r.body!!.string())
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
            )
        }
    }

    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        // Android asks once per installer app; without it the session would fail.
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = State.Downloading(release, 0f)
        Thread {
            val file = File(app.cacheDir, "updates/burble-${release.version}.apk")
            try {
                download(release, file)
                val problem = verify(app, file)
                if (problem != null) {
                    file.delete()
                    _state.value = State.Failed(problem, release)
                    return@Thread
                }
                _state.value = State.Installing(release)
                commit(app, file)
            } catch (e: IOException) {
                Log.w(TAG, "update download failed", e)
                file.delete()
                _state.value = State.Failed(Reason.NETWORK, release)
            }
        }.start()
    }

    private fun download(release: Release, file: File) {
        file.parentFile!!.apply { deleteRecursively(); mkdirs() }
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) {
                            shown = pct
                            _state.value = State.Downloading(release, pct / 100f)
                        }
                    }
                }
            }
        }
    }

    /**
     * Refuses an APK that isn't this app, isn't newer, or isn't signed with this app's key.
     * Android would refuse a different key too, but only after the user confirmed, with a
     * message that doesn't say why.
     */
    private fun verify(context: Context, file: File): Reason? {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return Reason.NO_APK
        if (apk.packageName != context.packageName) return Reason.NO_APK
        val self = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk.longVersionCode <= self.longVersionCode) return Reason.NOT_NEWER
        val theirs = apk.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet() ?: return Reason.WRONG_SIGNATURE
        val ours = self.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet() ?: return Reason.WRONG_SIGNATURE
        return if (theirs == ours) null else Reason.WRONG_SIGNATURE
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Once this app installed itself, Android 12+ lets later updates go without a prompt.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("burble.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    internal fun installFailed() {
        val s = _state.value
        _state.value = State.Failed(Reason.INSTALL, (s as? State.Installing)?.release)
    }

    /** Back to the release offer, when the user cancelled Android's confirmation. */
    internal fun installCancelled() {
        val s = _state.value
        if (s is State.Installing) _state.value = State.Available(s.release)
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** "0.2.0" is newer than "0.1.9"; a suffix such as "-rc.1" or "+main.abc" is ignored. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
