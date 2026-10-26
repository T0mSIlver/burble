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

/**
 * What most people never change: the dictation languages, which follow the keyboard unless the
 * user picks their own, the keyboard while dictating, and a test of the connection to Google.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Advanced(
    settings: Settings,
    keyboard: List<String>,
    test: TestAudio.State,
    actions: HomeActions,
    onBack: () -> Unit,
    languagesOpen: Boolean = false,
    testOpen: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(languagesOpen) }
    var testing by rememberSaveable { mutableStateOf(testOpen) }
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
            ))
        }
        item { SectionHeader(stringResource(R.string.section_troubleshooting)) }
        item {
            Group(listOf(
                linkRow(Icons.Outlined.RecordVoiceOver, stringResource(R.string.test_connection), null) {
                    testing = true
                },
            ))
        }
    }
    if (picking) LanguagesScreen(
        current = settings.languages,
        onDone = { tags -> set { it.copy(languages = tags, languagesFromKeyboard = false) }; picking = false },
        onDismiss = { picking = false },
    )
    if (testing) TestSheet(test, actions.sendTestAudio) { testing = false }
}

/** Sends a recorded sentence and shows what came back: the check for "is Google answering?". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TestSheet(state: TestAudio.State, onRun: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.test_connection), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.test_hint),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            when (state) {
                is TestAudio.State.Heard -> Result(
                    if (state.text.isBlank()) stringResource(R.string.empty_result) else stringResource(R.string.test_result, state.text),
                    colors.surfaceContainerHighest, colors.onSurface,
                )
                is TestAudio.State.Failed -> Result(
                    stringResource(R.string.test_failed, state.detail.orEmpty()), colors.errorContainer, colors.onErrorContainer,
                )
                else -> Unit
            }
            val sending = state == TestAudio.State.Sending
            Button(
                onClick = onRun,
                enabled = !sending,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.align(Alignment.End).padding(top = 24.dp),
            ) {
                if (sending) {
                    LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.sending))
                } else {
                    Text(stringResource(R.string.send_test_audio))
                }
            }
        }
    }
}

@Composable
private fun Result(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
