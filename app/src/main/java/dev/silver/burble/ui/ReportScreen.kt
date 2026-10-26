package dev.silver.burble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Button
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
 * What a bug report holds, shown before it leaves the phone: the user reads it, keeps app names
 * or not, and copies it or opens the GitHub issue with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportScreen(draft: Report.Draft, onCopy: (String) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var keepApps by rememberSaveable { mutableStateOf(false) }
    val body = draft.body(keepApps)
    SubScreen(stringResource(R.string.report_problem), onBack) {
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
