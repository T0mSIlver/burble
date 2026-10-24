package dev.silver.burble.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
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
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.silver.burble.BubbleShape
import dev.silver.burble.Output
import dev.silver.burble.R
import dev.silver.burble.Settings
import dev.silver.burble.Trigger

class HomeActions(
    val access: AccessActions,
    val openShortcutSettings: () -> Unit,
    /** Asks Android 13+ to add the Quick Settings tile. */
    val addTile: () -> Unit,
    val sendTestAudio: () -> Unit,
    val update: ((Settings) -> Settings) -> Unit,
) {
    companion object {
        val None = HomeActions(AccessActions.None, {}, {}, {}, {})
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Home(
    settings: Settings,
    access: Access,
    test: TestAudio.State,
    actions: HomeActions,
    version: String,
    languageOpen: Boolean = false,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var picking by rememberSaveable { mutableStateOf(languageOpen) }
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
            item { TestAudioRow(test, actions.sendTestAudio) }

            item { SectionHeader(stringResource(R.string.section_triggers)) }
            item { Triggers(settings.triggers, actions) }

            item { SectionHeader(stringResource(R.string.section_output)) }
            item { Outputs(settings.output) { o -> actions.update { it.copy(output = o) } } }

            if (Trigger.BUBBLE in settings.triggers) {
                item { SectionHeader(stringResource(R.string.section_shape)) }
                item { ShapePicker(settings.shape) { s -> actions.update { it.copy(shape = s) } } }
            }

            item { SectionHeader(stringResource(R.string.section_language)) }
            item {
                SegmentedListItem(
                    onClick = { picking = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    colors = listColors(),
                    leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.language_hint)) },
                ) { Text(languageName(settings.language)) }
            }

            item { About(version) }
        }
    }
    if (picking) LanguageDialog(
        current = settings.language,
        onPick = { tag -> actions.update { it.copy(language = tag) }; picking = false },
        onDismiss = { picking = false },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Status(access: Access, actions: AccessActions) {
    val ready = access.ready
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
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
                    Text(
                        stringResource(if (ready) R.string.status_ready else R.string.status_setup),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(if (ready) R.string.status_ready_body else R.string.status_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ready) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!ready) {
                Spacer(Modifier.height(8.dp))
                if (!access.microphone) Missing(R.string.onb_mic_title, null, R.string.onb_allow, actions.requestMicrophone)
                if (!access.insertion) {
                    Missing(R.string.onb_insert_title, if (access.restricted) R.string.status_restricted else null, R.string.open_settings, actions.openAccessibility)
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
        FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(action)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TestAudioRow(state: TestAudio.State, onSend: () -> Unit) {
    val sending = state == TestAudio.State.Sending
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedButton(onClick = onSend, enabled = !sending, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            if (sending) {
                LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.sending))
            } else {
                Text(stringResource(R.string.send_test_audio))
            }
        }
        val (text, color) = when (state) {
            TestAudio.State.Idle, TestAudio.State.Sending -> stringResource(R.string.test_hint) to MaterialTheme.colorScheme.onSurfaceVariant
            is TestAudio.State.Heard ->
                (if (state.text.isBlank()) stringResource(R.string.empty_result) else stringResource(R.string.test_result, state.text)) to
                    MaterialTheme.colorScheme.onSurface
            is TestAudio.State.Failed -> stringResource(R.string.test_failed, state.detail.orEmpty()) to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
}

// Segmented items default to the surface colour, which vanishes on a surface background.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun listColors(tintSelected: Boolean = true) = MaterialTheme.colorScheme.let {
    ListItemDefaults.segmentedColors(
        containerColor = it.surfaceContainer,
        disabledContainerColor = it.surfaceContainer,
        selectedContainerColor = if (tintSelected) it.secondaryContainer else it.surfaceContainer,
        selectedContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurface,
        selectedSupportingContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurfaceVariant,
        selectedLeadingContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurfaceVariant,
    )
}

private class TriggerRow(val trigger: Trigger, val icon: ImageVector, val title: Int, val hint: Int, val action: Int? = null, val onAction: () -> Unit = {})

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Triggers(selected: Set<Trigger>, actions: HomeActions, sdk: Int = Build.VERSION.SDK_INT) {
    // Android 13+ can add the tile from here; before, the user adds it from the Quick Settings editor.
    val tileAdds = sdk >= 33
    val rows = listOf(
        TriggerRow(Trigger.BUBBLE, Icons.Outlined.BubbleChart, R.string.trigger_bubble, R.string.trigger_bubble_hint),
        // Whether Android shows the button is the user's accessibility shortcut setting, not the app's.
        TriggerRow(
            Trigger.ACCESSIBILITY_BUTTON, Icons.Outlined.AccessibilityNew, R.string.trigger_a11y, R.string.trigger_a11y_hint,
            R.string.trigger_a11y_action, actions.openShortcutSettings,
        ),
        if (tileAdds) TriggerRow(
            Trigger.QUICK_SETTINGS, Icons.Outlined.GridView, R.string.trigger_tile, R.string.trigger_tile_hint_add,
            R.string.trigger_tile_action, actions.addTile,
        ) else TriggerRow(Trigger.QUICK_SETTINGS, Icons.Outlined.GridView, R.string.trigger_tile, R.string.trigger_tile_hint),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, row ->
            val checked = row.trigger in selected
            // One trigger always stays on, or nothing could start a dictation.
            val last = checked && selected.size == 1
            SegmentedListItem(
                checked = checked,
                onCheckedChange = { on ->
                    if (last) return@SegmentedListItem
                    actions.update { it.copy(triggers = if (on) selected + row.trigger else selected - row.trigger) }
                    if (on && row.trigger == Trigger.QUICK_SETTINGS && tileAdds) actions.addTile()
                },
                shapes = ListItemDefaults.segmentedShapes(i, rows.size),
                // Checkboxes already mark several rows; a tint on each would read as one choice.
                colors = listColors(tintSelected = false),
                leadingContent = { Icon(row.icon, contentDescription = null) },
                trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                supportingContent = {
                    Column {
                        Text(stringResource(row.hint))
                        if (checked && row.action != null) {
                            FilledTonalButton(
                                onClick = row.onAction,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(stringResource(row.action)) }
                        }
                    }
                },
            ) { Text(stringResource(row.title)) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Outputs(selected: Output, onChange: (Output) -> Unit) {
    val rows = listOf(
        Triple(Output.INSERT, Icons.Outlined.Keyboard, R.string.output_insert to R.string.output_insert_hint),
        Triple(Output.HOLD, Icons.Outlined.PushPin, R.string.output_hold to R.string.output_hold_hint),
        Triple(Output.CLIPBOARD, Icons.Outlined.ContentPaste, R.string.output_clipboard to R.string.output_clipboard_hint),
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, (output, icon, text) ->
            SegmentedListItem(
                selected = output == selected,
                onClick = { onChange(output) },
                shapes = ListItemDefaults.segmentedShapes(i, rows.size),
                colors = listColors(),
                leadingContent = { Icon(icon, contentDescription = null) },
                trailingContent = { RadioButton(selected = output == selected, onClick = null) },
                supportingContent = { Text(stringResource(text.second)) },
            ) { Text(stringResource(text.first)) }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapePicker(selected: BubbleShape, onPick: (BubbleShape) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(colors.surfaceContainer, MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BubbleShape.entries.forEach { shape ->
            val on = shape == selected
            val outline = shape.polygon.toShape()
            val name = stringResource(shape.label)
            Box(
                Modifier.size(52.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(shape) })
                    .semantics { contentDescription = name }
                    .then(if (on) Modifier.border(2.dp, colors.primary, outline) else Modifier)
                    .padding(5.dp)
                    .background(if (on) colors.primary else colors.secondaryContainer, outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = if (on) colors.onPrimary else colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                items(Languages) { tag ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp)
                            .selectable(selected = tag == current, role = Role.RadioButton, onClick = { onPick(tag) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(languageName(tag), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun About(version: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)) {
        Text(stringResource(R.string.disclaimer_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
