package dev.silver.burble.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import dev.silver.burble.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.silver.burble.R
import dev.silver.burble.Report

/**
 * What a bug report holds, shown before it leaves the phone: first the connection test, run as
 * the report opens, then the rest. The user reads it, keeps app names or not, and copies it or
 * opens the GitHub issue with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportScreen(draft: Report.Draft, test: TestAudio.State, onCopy: (String) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var keepApps by rememberSaveable { mutableStateOf(false) }
    val connection = when (test) {
        TestAudio.State.Idle, TestAudio.State.Sending -> null
        is TestAudio.State.Heard -> stringResource(R.string.report_test_heard, test.text)
        is TestAudio.State.Failed -> stringResource(R.string.report_test_failed, test.detail.orEmpty())
    }
    val body = draft.body(keepApps, connection)
    val colors = MaterialTheme.colorScheme
    SubScreen(stringResource(R.string.report_problem), onBack) {
        item {
            // The connection test, so a report says whether Google answered.
            val failed = test is TestAudio.State.Failed
            val container by animateColorAsState(if (failed) colors.errorContainer else colors.surfaceContainerHigh, Motion.effects())
            val content by animateColorAsState(if (failed) colors.onErrorContainer else colors.onSurface, Motion.effects())
            Surface(
                color = container,
                contentColor = content,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                // The result fades in over the spinner as the card grows to fit it.
                AnimatedContent(
                    connection,
                    transitionSpec = {
                        fadeIn(Motion.effects()) togetherWith fadeOut(Motion.fastEffects()) using
                            SizeTransform(clip = false) { _, _ -> Motion.spatial() }
                    },
                    label = "test",
                ) { shown ->
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (shown == null) {
                            LoadingIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(shown ?: stringResource(R.string.report_test_sending), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.report_intro),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Group(listOf(switchRow(Icons.Outlined.Apps, stringResource(R.string.report_keep_apps),
                stringResource(R.string.report_keep_apps_hint), keepApps) { keepApps = it }))
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                SelectionContainer {
                    Text(body, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { onCopy(body) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.report_copy)) }
                Button(onClick = { onOpen(body) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.report_open)) }
            }
        }
    }
}
