package dev.silver.burble.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silver.burble.InsertionService
import dev.silver.burble.Prefs
import dev.silver.burble.Report
import dev.silver.burble.updater.Updater

/** Onboarding until the user finishes it, then home. */
@Composable
fun BurbleApp() {
    val context = LocalContext.current
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val test by TestAudio.state.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val connected by InsertionService.connected.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(Permissions.read(context)) }
    LifecycleResumeEffect(Unit) {
        access = Permissions.read(context)
        if (Prefs.settings.value.checkUpdates) Updater.checkIfDue(context)
        onPauseOrDispose {}
    }
    // Android binds the service a moment after the user turns it on, and may stop it any time.
    LaunchedEffect(connected) { access = Permissions.read(context, connected) }

    val open = { intent: Intent -> context.startActivity(intent) }
    var micAsked by rememberSaveable { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = Permissions.read(context) }
    val actions = AccessActions(
        requestMicrophone = {
            // After a second refusal Android stops showing the prompt; only App info can grant it.
            val blocked = micAsked && (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            if (blocked) open(Permissions.appInfo(context)) else askMic.launch(Manifest.permission.RECORD_AUDIO)
            micAsked = true
        },
        openAccessibility = { open(Permissions.accessibilitySettings()) },
        openAppInfo = { open(Permissions.appInfo(context)) },
        openBattery = { open(Permissions.batterySettings(context)) },
    )

    if (!settings.onboarded) {
        Onboarding(access, actions, onDone = { Prefs.update { it.copy(onboarded = true, bubbleOn = true) } })
    } else {
        Home(
            settings = settings,
            access = access,
            test = test,
            update = update,
            actions = HomeActions(
                access = actions,
                sendTestAudio = { TestAudio.send(context) },
                update = Prefs::update,
                checkForUpdate = { Updater.check(context) },
                installUpdate = { Updater.install(context, it) },
                openUrl = { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                reportProblem = {
                    val version = Updater.installedVersion(context)
                    val languages = settings.languages
                    // Reading logcat spawns a process: keep it off the main thread.
                    Thread {
                        val url = Report.issueUrl(SOURCE, version, languages)
                        context.mainExecutor.execute { open(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }.start()
                },
            ),
            version = Updater.installedVersion(context),
        )
    }
}
