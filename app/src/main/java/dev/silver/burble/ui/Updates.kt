package dev.silver.burble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.silver.burble.R
import dev.silver.burble.updater.Updater
import dev.silver.burble.updater.Updater.State

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Updates(auto: Boolean, state: State, version: String, actions: HomeActions) {
    Group(listOf(
        switchRow(Icons.Outlined.Update, stringResource(R.string.updates_auto), stringResource(R.string.updates_auto_hint), auto) { on ->
            actions.update { it.copy(checkUpdates = on) }
        },
        contentRow(Icons.Outlined.SystemUpdate, stringResource(R.string.version, version)) {
            UpdateBody(state, actions)
        },
    ))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateBody(state: State, actions: HomeActions) {
    val release = when (state) {
        is State.Available -> state.release
        is State.Downloading -> state.release
        is State.Installing -> state.release
        is State.Failed -> state.release
        else -> null
    }
    val status = when (state) {
        State.Idle -> null
        State.Checking -> stringResource(R.string.updates_checking)
        State.UpToDate -> stringResource(R.string.updates_none)
        is State.Available -> stringResource(R.string.updates_available, state.release.version)
        is State.Downloading -> stringResource(R.string.updates_downloading, state.release.version)
        is State.Installing -> stringResource(R.string.updates_installing, state.release.version)
        is State.Failed -> stringResource(
            when (state.reason) {
                Updater.Reason.NETWORK -> R.string.updates_failed_network
                Updater.Reason.NO_APK -> R.string.updates_failed_apk
                Updater.Reason.WRONG_SIGNATURE -> R.string.updates_failed_signature
                Updater.Reason.NOT_NEWER -> R.string.updates_failed_older
                Updater.Reason.INSTALL -> R.string.updates_failed_install
            },
        )
    }
    Column {
        if (status != null) {
            Text(status, color = if (state is State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (release != null && release.notes.isNotBlank() && state !is State.Failed) {
            // Release notes as written on GitHub; Markdown stays readable as plain text.
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
        when (state) {
            is State.Downloading -> LinearWavyProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            is State.Available -> Button(
                onClick = { actions.installUpdate(state.release) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.updates_install)) }
            is State.Installing, State.Checking -> Unit
            else -> OutlinedButton(
                onClick = if (state is State.Failed && release != null && state.reason == Updater.Reason.NETWORK) {
                    { actions.installUpdate(release) }
                } else {
                    actions.checkForUpdate
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(if (state is State.Failed && release != null && state.reason == Updater.Reason.NETWORK) R.string.updates_retry else R.string.updates_check)) }
        }
    }
}
