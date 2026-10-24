package dev.silver.burble.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silver.burble.DictationTile
import dev.silver.burble.Prefs

/** Onboarding until the user finishes it, then home. */
@Composable
fun BurbleApp() {
    val context = LocalContext.current
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val test by TestAudio.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(Permissions.read(context)) }
    LifecycleResumeEffect(Unit) {
        access = Permissions.read(context)
        onPauseOrDispose {}
    }

    val open = { intent: Intent -> context.startActivity(intent) }
    var micAsked by rememberSaveable { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = Permissions.read(context) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = Permissions.read(context) }
    val actions = AccessActions(
        requestMicrophone = {
            // After a second refusal Android stops showing the prompt; only App info can grant it.
            val blocked = micAsked && (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            if (blocked) open(Permissions.appInfo(context)) else askMic.launch(Manifest.permission.RECORD_AUDIO)
            micAsked = true
        },
        requestNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
        openAccessibility = { open(Permissions.accessibilitySettings()) },
        openAppInfo = { open(Permissions.appInfo(context)) },
    )

    if (!settings.onboarded) {
        val steps = remember { onboardingSteps(access) }
        Onboarding(access, actions, steps, onDone = { Prefs.update { it.copy(onboarded = true) } })
    } else {
        Home(
            settings = settings,
            access = access,
            test = test,
            actions = HomeActions(
                access = actions,
                // The service's own page needs OPEN_ACCESSIBILITY_DETAILS_SETTINGS, a privileged permission.
                openShortcutSettings = { open(Permissions.accessibilitySettings()) },
                addTile = { DictationTile.requestAdd(context) {} },
                sendTestAudio = { TestAudio.send(context) },
                update = Prefs::update,
            ),
            version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
        )
    }
}
