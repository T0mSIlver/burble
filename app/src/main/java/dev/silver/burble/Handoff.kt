package dev.silver.burble

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.graphics.Bitmap
import androidx.core.os.BundleCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import dev.silver.burble.overlay.Box
import dev.silver.burble.overlay.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Gboard → Burble handoff (Burble's design notes spec/handoff.md, v1): a patched keyboard's mic key
 * starts Burble's dictation instead of its own. Off in release builds until Tom approves it
 * ([BuildConfig.HANDOFF], and the provider's `@bool/handoff`).
 */
object Handoff {
    const val VERSION = 1

    /**
     * The keyboard that said hello in this process's life. The bubble steps aside while it's up,
     * as long as it is still the default input method ([activeFor]).
     */
    @Volatile var ime: String? = null
        private set

    /** The default input method's package, from `Settings.Secure.DEFAULT_INPUT_METHOD`. */
    fun currentIme(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)?.packageName

    /** Whether the keyboard that said hello is the one in use now. */
    fun activeFor(context: Context) = BuildConfig.HANDOFF && ime != null && ime == currentIme(context)

    /**
     * The caller check: no signing key to pin, since users patch Gboard with their own, so only
     * the default input method may start a dictation, and only while a text field has focus.
     * [callers] are the calling uid's packages.
     */
    internal fun allows(callers: Array<String>?, defaultIme: String?, fieldFocused: Boolean) =
        defaultIme != null && callers?.contains(defaultIme) == true && fieldFocused

    /** Hello from [pkg], the default input method: the bubble steps aside while it's up. */
    internal fun heardFrom(pkg: String) {
        if (ime == pkg) return
        ime = pkg
        Handler(Looper.getMainLooper()).post { InsertionService.instance?.followKeyboard() }
    }
}

/**
 * `content://dev.silver.burble.handoff`, used only through [ContentResolver.call]: `hello`,
 * `start` and `state`, as spec/handoff.md defines them.
 */
class HandoffProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (!BuildConfig.HANDOFF) return Bundle().apply { putInt("version", Handoff.VERSION); putBoolean("enabled", false) }
        val uid = Binder.getCallingUid()
        val ime = Handoff.currentIme(context)
        // Debug builds also take adb's shell as the keyboard, to test without a patched one.
        val caller = context.packageManager.getPackagesForUid(uid)
            .let { if (HandoffDebug.trusts(uid) && ime != null) arrayOf(ime) else it }
        val fromIme = Handoff.allows(caller, ime, fieldFocused = true)
        return when (method) {
            "hello" -> Bundle().apply {
                if (fromIme) Handoff.heardFrom(ime!!)
                putInt("version", Handoff.VERSION)
                putBoolean("enabled", fromIme && onMain { usable() })
                // A bubble on its way back into the key: the key stays hidden until it lands.
                val key = key(extras)
                putLong("holdKeyUntil", if (fromIme) onMain(0L) { InsertionService.instance?.keyboardBack(key) ?: 0L } else 0L)
            }
            "start" -> Bundle().apply {
                // Returns once the bubble's first frame, a copy of the key, is on screen: the
                // keyboard then hides its key.
                val drawn = CountDownLatch(1)
                val accepted = fromIme && onMain(false) { start(context, caller, ime, extras) { drawn.countDown() } }
                if (accepted) drawn.await(FIRST_FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
                putBoolean("accepted", accepted)
            }
            "state" -> Bundle().apply {
                putBoolean("listening", fromIme && Dictation.state.value == Dictation.State.Listening)
            }
            else -> null
        }
    }

    /** Burble's accessibility service is on, and so is Use Burble. */
    private fun usable() = InsertionService.instance != null && Prefs.settings.value.bubbleOn

    /**
     * As a tap on the bubble: starts a dictation into the focused field (or stops the one
     * listening, retries one whose audio waits). Accepted only if one is now under way.
     */
    private fun start(context: Context, caller: Array<String>?, ime: String?, extras: Bundle?, onDrawn: () -> Unit): Boolean {
        val service = InsertionService.instance
        if (!usable() || service == null) return false
        if (!Handoff.allows(caller, ime, fieldFocused = service.captureTarget() != null)) {
            Log.d(TAG, "start refused: no text field has focus")
            return false
        }
        ime?.let(Handoff::heardFrom)
        val key = key(extras)
        val wasIdle = !Dictation.state.value.recording
        Dictation.toggle(context)
        val st = Dictation.state.value
        val started = wasIdle && st == Dictation.State.Listening
        // The bubble comes out of the key only for a dictation this tap began.
        if (started && key != null) service.emergeFrom(key, extras.px("keyboardTop") ?: key.bounds.top, onDrawn)
        else onDrawn()
        return st.recording
    }

    /**
     * The mic key in [extras]: its disc's bounds in screen px, and if sent its fill (ARGB) and
     * glyph (a bitmap at screen px, the bounds' size); null without valid bounds.
     */
    private fun key(extras: Bundle?): Key? {
        val e = extras ?: return null
        val l = e.px("keyLeft"); val t = e.px("keyTop"); val r = e.px("keyRight"); val b = e.px("keyBottom")
        if (l == null || t == null || r == null || b == null || r <= l || b <= t) return null
        val fill = if (e.containsKey("keyFill")) Color(e.getInt("keyFill")) else null
        // Another app's parcel: a bad or oversized one only costs the glyph.
        val icon = runCatching { BundleCompat.getParcelable(e, "keyIcon", Bitmap::class.java) }.getOrNull()
            ?.takeIf { it.width <= MAX_ICON_PX && it.height <= MAX_ICON_PX }?.asImageBitmap()
        return Key(Box(l, t, r, b), fill, icon)
    }

    /** Runs [block] on the main thread, where [Dictation] and the service live; [otherwise] if it hangs. */
    private fun <T> onMain(otherwise: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result = otherwise
        val done = CountDownLatch(1)
        main.post {
            try { result = block() } finally { done.countDown() }
        }
        return if (done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) result else otherwise
    }

    private fun onMain(block: () -> Boolean) = onMain(false, block)

    /** A length in px, whether the keyboard sent it as an int or a float. */
    @Suppress("DEPRECATION")
    private fun Bundle?.px(key: String): Float? = (this?.get(key) as? Number)?.toFloat()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val TAG = "Handoff"
        const val MAIN_WAIT_MS = 1000L
        /** `start` waits at most this long for the bubble's first frame. */
        const val FIRST_FRAME_WAIT_MS = 50L
        /** A key's glyph larger than this, in px, isn't a glyph. */
        const val MAX_ICON_PX = 512
    }
}
