package dev.silver.burble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.silver.burble.BubbleShape
import dev.silver.burble.BubbleSize
import dev.silver.burble.R
import dev.silver.burble.Settings
import dev.silver.burble.updater.Release
import dev.silver.burble.updater.Updater

class HomeActions(
    val access: AccessActions,
    val sendTestAudio: () -> Unit,
    val update: ((Settings) -> Settings) -> Unit,
    val checkForUpdate: () -> Unit,
    val installUpdate: (Release) -> Unit,
    val openUrl: (String) -> Unit,
) {
    companion object {
        val None = HomeActions(AccessActions.None, {}, {}, {}, {}, {})
    }
}

private const val SOURCE = "https://github.com/T0mSIlver/burble"

/** The bubble's diameter on screen; the overlay draws it at the same sizes. */
val BubbleSize.dp: Dp
    get() = when (this) {
        BubbleSize.SMALL -> 52.dp
        BubbleSize.MEDIUM -> 64.dp
        BubbleSize.LARGE -> 76.dp
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Home(
    settings: Settings,
    access: Access,
    test: TestAudio.State,
    update: Updater.State,
    actions: HomeActions,
    version: String,
    languagesOpen: Boolean = false,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var picking by rememberSaveable { mutableStateOf(languagesOpen) }
    val set = actions.update
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item { Status(access, actions.access) }

            item { SectionHeader(stringResource(R.string.section_dictation)) }
            item {
                Group(listOf(
                    switchRow(Icons.Outlined.KeyboardHide, stringResource(R.string.hide_keyboard), stringResource(R.string.hide_keyboard_hint), settings.hideKeyboard) { on ->
                        set { it.copy(hideKeyboard = on) }
                    },
                    linkRow(Icons.Outlined.Language, stringResource(R.string.languages), settings.languages.joinToString(", ") { languageName(it) }) {
                        picking = true
                    },
                    contentRow(Icons.Outlined.WifiTethering, stringResource(R.string.test_connection)) {
                        TestConnection(test, actions.sendTestAudio)
                    },
                ))
            }

            item { SectionHeader(stringResource(R.string.section_bubble)) }
            item {
                Group(listOf(
                    contentRow(Icons.Outlined.Interests, stringResource(R.string.bubble_shape)) {
                        ShapePicker(settings.shape) { s -> set { it.copy(shape = s) } }
                    },
                    contentRow(Icons.Outlined.OpenInFull, stringResource(R.string.bubble_size)) {
                        SizePicker(settings.shape, settings.bubbleSize) { s -> set { it.copy(bubbleSize = s) } }
                    },
                ))
            }

            item { SectionHeader(stringResource(R.string.section_updates)) }
            item {
                Updates(settings.checkUpdates, update, version, actions)
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                Group(listOf(
                    linkRow(Icons.Outlined.PrivacyTip, stringResource(R.string.privacy), stringResource(R.string.privacy_hint)) {
                        actions.openUrl("$SOURCE/blob/main/PRIVACY.md")
                    },
                    linkRow(Icons.Outlined.Gavel, stringResource(R.string.licence), stringResource(R.string.licence_hint)) {
                        actions.openUrl("$SOURCE/blob/main/LICENSE")
                    },
                    linkRow(Icons.Outlined.Code, stringResource(R.string.source), SOURCE.removePrefix("https://")) {
                        actions.openUrl(SOURCE)
                    },
                ))
            }
            item {
                Text(
                    stringResource(R.string.disclaimer_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
        }
    }
    if (picking) LanguagesDialog(
        current = settings.languages,
        onDone = { tags -> set { it.copy(languages = tags) }; picking = false },
        onDismiss = { picking = false },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Status(access: Access, actions: AccessActions) {
    val ready = access.ready
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = if (ready) colors.tertiaryContainer else colors.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).background(if (ready) colors.tertiary else colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ready) Icons.Rounded.Check else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = if (ready) colors.onTertiary else colors.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(if (ready) R.string.status_ready else R.string.status_setup), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(if (ready) R.string.status_ready_body else R.string.status_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ready) colors.onTertiaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            if (!ready) {
                Spacer(Modifier.height(8.dp))
                if (!access.microphone) Missing(R.string.onb_mic_title, null, R.string.onb_allow, actions.requestMicrophone)
                when {
                    !access.insertion ->
                        Missing(R.string.onb_insert_title, if (access.restricted) R.string.status_restricted else null, R.string.open_settings, actions.openAccessibility)
                    access.stopped -> {
                        Missing(R.string.status_stopped, R.string.status_stopped_hint, R.string.open_settings, actions.openAccessibility)
                        // A battery saver is the usual reason Android stops the service.
                        if (!access.battery) Missing(R.string.onb_battery_title, R.string.status_battery_hint, R.string.onb_allow, actions.openBattery)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Missing(label: Int, detail: Int?, action: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(action)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TestConnection(state: TestAudio.State, onSend: () -> Unit) {
    val sending = state == TestAudio.State.Sending
    val (text, color) = when (state) {
        TestAudio.State.Idle, TestAudio.State.Sending -> stringResource(R.string.test_hint) to MaterialTheme.colorScheme.onSurfaceVariant
        is TestAudio.State.Heard ->
            (if (state.text.isBlank()) stringResource(R.string.empty_result) else stringResource(R.string.test_result, state.text)) to
                MaterialTheme.colorScheme.onSurface
        is TestAudio.State.Failed -> stringResource(R.string.test_failed, state.detail.orEmpty()) to MaterialTheme.colorScheme.error
    }
    Text(text, color = color)
    OutlinedButton(onClick = onSend, enabled = !sending, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) {
        if (sending) {
            LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.sending))
        } else {
            Text(stringResource(R.string.send_test_audio))
        }
    }
}

private val BubbleShape.label
    get() = when (this) {
        BubbleShape.COOKIE_9 -> R.string.shape_cookie
        BubbleShape.SUNNY -> R.string.shape_sunny
        BubbleShape.CLOVER_4 -> R.string.shape_clover
        BubbleShape.CIRCLE -> R.string.shape_circle
        BubbleShape.PILL -> R.string.shape_pill
        BubbleShape.SOFT_BURST -> R.string.shape_burst
    }

private val BubbleSize.label
    get() = when (this) {
        BubbleSize.SMALL -> R.string.size_small
        BubbleSize.MEDIUM -> R.string.size_medium
        BubbleSize.LARGE -> R.string.size_large
    }

/** The idle bubble as the overlay draws it: the shape in primary container with a mic glyph. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BubblePreview(shape: BubbleShape, size: Dp, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val outline = shape.polygon.toShape()
    Box(
        modifier.size(size + 12.dp)
            .then(if (selected) Modifier.border(2.dp, colors.primary, outline) else Modifier)
            .padding(6.dp)
            .background(if (selected) colors.primary else colors.primaryContainer, outline),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = null,
            tint = if (selected) colors.onPrimary else colors.onPrimaryContainer,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun ShapePicker(selected: BubbleShape, onPick: (BubbleShape) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
        BubbleShape.entries.forEach { shape ->
            val name = stringResource(shape.label)
            BubblePreview(
                shape, 36.dp, shape == selected,
                Modifier.selectable(selected = shape == selected, role = Role.RadioButton, onClick = { onPick(shape) })
                    .semantics { contentDescription = name },
            )
        }
    }
}

/** Each size drawn at its real diameter, so the choice is what the screen will show. */
@Composable
private fun SizePicker(shape: BubbleShape, selected: BubbleSize, onPick: (BubbleSize) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        BubbleSize.entries.forEach { size ->
            Column(
                Modifier.selectable(selected = size == selected, role = Role.RadioButton, onClick = { onPick(size) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BubblePreview(shape, size.dp, size == selected)
                Text(
                    stringResource(size.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (size == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Several languages; the order of picking is kept, and the first one is the main one. */
@Composable
private fun LanguagesDialog(current: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by rememberSaveable { mutableStateOf(current) }
    // The ones in use first, so a long list doesn't hide them.
    val list = remember(current) { current + Languages.filterNot { it in current } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages)) },
        text = {
            Column {
                Text(stringResource(R.string.languages_hint), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(list) { tag ->
                        val on = tag in picked
                        Row(
                            Modifier.fillMaxWidth().height(48.dp)
                                // The last language can't go: dictation needs one.
                                .toggleable(on, enabled = !(on && picked.size == 1), role = Role.Checkbox) {
                                    picked = if (it) picked + tag else picked - tag
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, enabled = !(on && picked.size == 1))
                            Spacer(Modifier.width(16.dp))
                            Text(languageName(tag), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (picked.firstOrNull() == tag) {
                                Text(stringResource(R.string.languages_main), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
