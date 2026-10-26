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
import dev.silver.burble.updater.Release
import dev.silver.burble.updater.Updater
import dev.silver.burble.updater.Updater.State

/**
 * The version, how its check went, and the daily check's switch, in one row; turning it on
 * checks at once. A row with the changelog and the install follows only while an update exists.
 */
@Composable
fun updateRows(auto: Boolean, state: State, version: String, actions: HomeActions): List<SettingRow> {
    val status = when (state) {
        State.Idle -> null
        State.Checking -> R.string.updates_checking
        State.UpToDate -> R.string.updates_up_to_date
        is State.Available -> R.string.updates_new
        is State.Downloading, is State.Installing -> R.string.updates_updating
        is State.Failed -> if (state.release == null) R.string.updates_not_checked else R.string.updates_new
    }
    val title = status?.let { stringResource(R.string.version_status, version, stringResource(it)) }
        ?: stringResource(R.string.version, version)
    return listOfNotNull(
        switchRow(Icons.Outlined.Update, title, stringResource(R.string.updates_auto_hint), auto) { on ->
            actions.update { it.copy(checkUpdates = on) }
            if (on) actions.checkForUpdate()
        },
        state.release?.let { release ->
            contentRow(Icons.Outlined.SystemUpdate, stringResource(R.string.updates_available, release.version)) {
                UpdateBody(state, release, actions)
            }
        },
    )
}

private val State.release get() = when (this) {
    is State.Available -> release
    is State.Downloading -> release
    is State.Installing -> release
    is State.Failed -> release
    else -> null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateBody(state: State, release: Release, actions: HomeActions) {
    Column {
        if (state is State.Failed) {
            Text(
                stringResource(
                    when (state.reason) {
                        Updater.Reason.NETWORK -> R.string.updates_failed_network
                        Updater.Reason.NO_APK -> R.string.updates_failed_apk
                        Updater.Reason.WRONG_SIGNATURE -> R.string.updates_failed_signature
                        Updater.Reason.NOT_NEWER -> R.string.updates_failed_older
                        Updater.Reason.INSTALL -> R.string.updates_failed_install
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (release.notes.isNotBlank() && state !is State.Failed) {
            // Release notes as written on GitHub; Markdown stays readable as plain text.
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
        when (state) {
            is State.Downloading -> LinearWavyProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            is State.Installing -> Text(
                stringResource(R.string.updates_installing, release.version),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            is State.Failed -> if (state.reason == Updater.Reason.NETWORK) OutlinedButton(
                onClick = { actions.installUpdate(release) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.updates_retry)) }
            else -> Button(
                onClick = { actions.installUpdate(release) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.updates_install)) }
        }
    }
}
