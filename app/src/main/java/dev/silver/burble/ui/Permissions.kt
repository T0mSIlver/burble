package dev.silver.burble.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.silver.burble.InsertionService

/** What the app may do right now; read again whenever the user comes back from Settings. */
data class Access(
    val microphone: Boolean,
    /** Always true below Android 13, where apps need no permission to notify. */
    val notifications: Boolean,
    /** The accessibility service that inserts text is on. */
    val insertion: Boolean,
    /**
     * The app came from a file on Android 13+ and the accessibility service is off, so Android
     * probably blocks it until the user allows restricted settings.
     */
    val restricted: Boolean,
) {
    /** The accessibility service hosts the bubble, the accessibility button and insertion. */
    val ready get() = microphone && insertion

    companion object {
        val All = Access(microphone = true, notifications = true, insertion = true, restricted = false)
        val None = Access(microphone = false, notifications = false, insertion = false, restricted = true)
    }
}

object Permissions {

    fun read(context: Context) = Access(
        microphone = granted(context, Manifest.permission.RECORD_AUDIO),
        notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
        insertion = insertion(context),
        restricted = restricted(context),
    )

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun insertion(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${context.packageName}/${InsertionService::class.java.name}") == true

    /**
     * Android 13+ greys out the accessibility switch of apps installed from a file (browser
     * download, file manager) until the user taps App info → ⋮ → Allow restricted settings.
     */
    private fun restricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || insertion(context)) return false
        // The app op behind that menu item would say whether the user allowed it, but Android
        // refuses to show it to apps (GET_APP_OPS_STATS), so only the install source is known.
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }
            .getOrDefault(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appInfo(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
