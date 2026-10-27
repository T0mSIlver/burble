package dev.silver.burble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.silver.burble.Haptic
import dev.silver.burble.Haptics

/**
 * Debug builds only, under Advanced: a button per haptic event, to feel each on the phone.
 *
 * TODO(before v0.1.0): remove this file and its row in [debugRows]. English only on purpose:
 * never in a release.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HapticsDebug() {
    val view = LocalView.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Debug build only. Each plays as the bubble does; silent with touch feedback off.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (event in Haptic.entries) {
                    FilledTonalButton(onClick = { Haptics.play(view, event, listening = false) }) {
                        Text(event.name.lowercase().replace('_', ' '))
                    }
                }
            }
        }
    }
}
