package dev.silver.burble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.silver.burble.R
import dev.silver.burble.Settings
import dev.silver.burble.updater.Updater

/**
 * What most people never change: the dictation languages, which follow the keyboard unless the
 * user picks their own, the keyboard while dictating, and the daily update check.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Advanced(
    settings: Settings,
    keyboard: List<String>,
    update: Updater.State,
    version: String,
    actions: HomeActions,
    onBack: () -> Unit,
    languagesOpen: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(languagesOpen) }
    val set = actions.update
    val names = { tags: List<String> -> tags.joinToString(", ") { languageName(it) } }
    SubScreen(stringResource(R.string.advanced), onBack) {
        item { SectionHeader(stringResource(R.string.languages)) }
        item {
            Group(listOf(
                radioRow(stringResource(R.string.languages_keyboard), names(keyboard), settings.languagesFromKeyboard) {
                    set { it.copy(languagesFromKeyboard = true) }
                },
                radioRow(stringResource(R.string.languages_custom), names(settings.languages), !settings.languagesFromKeyboard) {
                    set { it.copy(languagesFromKeyboard = false) }
                    picking = true
                },
            ))
        }
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Group(listOf(
                switchRow(Icons.Outlined.KeyboardHide, stringResource(R.string.hide_keyboard), stringResource(R.string.hide_keyboard_hint), settings.hideKeyboard) { on ->
                    set { it.copy(hideKeyboard = on) }
                },
                updateSwitch(settings.checkUpdates, update, version, actions),
            ))
        }
    }
    if (picking) LanguagesScreen(
        current = settings.languages,
        onDone = { tags -> set { it.copy(languages = tags, languagesFromKeyboard = false) }; picking = false },
        onDismiss = { picking = false },
    )
}
