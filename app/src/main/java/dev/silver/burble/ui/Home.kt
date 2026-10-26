package dev.silver.burble.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.silver.burble.BubbleShow
import dev.silver.burble.R
import dev.silver.burble.Settings
import dev.silver.burble.WhenIdle
import dev.silver.burble.updater.Release
import dev.silver.burble.updater.Updater

class HomeActions(
    val access: AccessActions,
    val update: ((Settings) -> Settings) -> Unit,
    val checkForUpdate: () -> Unit,
    val installUpdate: (Release) -> Unit,
    val openUrl: (String) -> Unit,
    val reportProblem: () -> Unit,
) {
    companion object {
        val None = HomeActions(AccessActions.None, {}, {}, {}, {}, {})
    }
}

internal const val SOURCE = "https://github.com/T0mSIlver/burble"

/**
 * One screen, laid out as Android's own settings: what's missing first, then the main switch,
 * when the bubble shows, a row to its look, a row to [Advanced], and About. [keyboard] is the
 * keyboard's languages, which dictation follows by default. The last parameters open pages for
 * screenshots.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Home(
    settings: Settings,
    access: Access,
    update: Updater.State,
    actions: HomeActions,
    version: String,
    keyboard: List<String> = emptyList(),
    advancedOpen: Boolean = false,
    languagesOpen: Boolean = false,
    licencesOpen: Boolean = false,
    lookOpen: Boolean = false,
    aboutOpen: Boolean = false,
) {
    var advanced by rememberSaveable { mutableStateOf(advancedOpen) }
    var licences by rememberSaveable { mutableStateOf(licencesOpen) }
    var look by rememberSaveable { mutableStateOf(lookOpen) }
    var about by rememberSaveable { mutableStateOf(aboutOpen || licencesOpen) }
    val set = actions.update
    @Composable fun HomeMain() {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
                // Until setup is done the switch would switch nothing.
                if (!access.ready) item { Setup(access, actions.access) }
                else item { MainSwitch(settings.bubbleOn) { on -> set { it.copy(bubbleOn = on) } } }
                // An update shows here too, so it isn't hidden behind About.
                if (update.hasRelease) item { UpdateCard(update, actions) }

                item { SectionHeader(stringResource(R.string.section_bubble)) }
                item {
                    Group(listOf(
                        contentRow(null, stringResource(R.string.bubble_when)) {
                            Choices(listOf(BubbleShow.ALWAYS to R.string.bubble_when_always, BubbleShow.TYPING to R.string.bubble_when_typing),
                                settings.bubbleShow) { w -> set { it.copy(bubbleShow = w) } }
                            Text(
                                stringResource(if (settings.bubbleShow == BubbleShow.ALWAYS) R.string.snooze_always else R.string.snooze_typing),
                                Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        lookRow(settings) { look = true },
                    ))
                }

                item { Spacer(Modifier.size(24.dp)) }
                item {
                    Group(listOf(
                        linkRow(Icons.Outlined.Tune, stringResource(R.string.advanced), stringResource(R.string.advanced_hint)) { advanced = true },
                        linkRow(Icons.Outlined.Info, stringResource(R.string.section_about), stringResource(R.string.version, version)) { about = true },
                    ))
                }
            }
        }
    }
    // Pages under home slide in from the end, on the motion scheme's springs, and back.
    // Ordered by depth, so Licences slides in over About and back out to it.
    val page = when { advanced -> 1; look -> 2; about && licences -> 4; about -> 3; else -> 0 }
    AnimatedContent(page, transitionSpec = { pageTransition(forward = targetState > initialState) }, label = "page") { p ->
        when (p) {
            1 -> Advanced(settings, keyboard, actions, onBack = { advanced = false }, languagesOpen)
            2 -> BubbleLook(settings, set, onBack = { look = false })
            3 -> About(settings, update, version, actions, onLicences = { licences = true }, onBack = { about = false })
            4 -> Licences(onBack = { licences = false })
            else -> HomeMain()
        }
    }
}

/**
 * Android 16's main switch: a large card in the primary container while on. Off, the bubble is
 * gone until it is turned back on, here or from the Quick Settings tile.
 */
@Composable
private fun MainSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (on) colors.primaryContainer else colors.surfaceContainerHighest,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            Modifier
                .toggleable(on, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = 80.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.use_burble), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(16.dp))
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

/**
 * What stands between the user and a dictation. A stopped service gets one way back, with the
 * battery exemption second, since a battery saver is the usual reason Android stops it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Setup(access: Access, actions: AccessActions) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        shape = segmentShape(0, 1),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            if (access.microphone && access.insertion && access.stopped) {
                Text(stringResource(R.string.status_stopped), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.status_stopped_hint),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (!access.battery) TextButton(onClick = actions.openBattery) { Text(stringResource(R.string.status_battery)) }
                    Button(onClick = actions.openAccessibility, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.open_settings)) }
                }
                return@Column
            }
            Text(stringResource(R.string.status_setup), style = MaterialTheme.typography.titleLarge)
            if (!access.microphone) Missing(null, null, R.string.onb_allow_mic, actions.requestMicrophone)
            if (!access.insertion) {
                Missing(R.string.onb_insert_title, if (access.restricted) R.string.status_restricted else R.string.status_insert_hint,
                    R.string.open_settings, actions.openAccessibility)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Missing(label: Int?, detail: Int?, action: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        if (label != null) Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(action))
        }
    }
}

/**
 * One tap from home: the version with the automatic update check, the update's details while
 * there is one, report a problem, privacy, licences, source and the disclaimer.
 */
@Composable
private fun About(settings: Settings, update: Updater.State, version: String, actions: HomeActions, onLicences: () -> Unit, onBack: () -> Unit) {
    val rows = listOfNotNull(
        updateSwitch(settings.checkUpdates, update, version, actions),
        releaseRow(update, actions),
        linkRow(Icons.Outlined.BugReport, stringResource(R.string.report_problem), stringResource(R.string.report_problem_hint)) {
            actions.reportProblem()
        },
        linkRow(Icons.Outlined.PrivacyTip, stringResource(R.string.privacy), stringResource(R.string.privacy_hint)) {
            actions.openUrl("$SOURCE#what-leaves-your-phone")
        },
        linkRow(Icons.Outlined.Gavel, stringResource(R.string.licence), null, onLicences),
        linkRow(Icons.Outlined.Code, stringResource(R.string.source), SOURCE.removePrefix("https://")) {
            actions.openUrl(SOURCE)
        },
    )
    SubScreen(stringResource(R.string.section_about), onBack) {
        item { Spacer(Modifier.size(8.dp)) }
        item { Group(rows) }
        item {
            Text(
                stringResource(R.string.about_disclaimer),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
