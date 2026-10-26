package dev.silver.burble.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.silver.burble.overlay.WaveShape

/**
 * Debug builds only (src/debug; src/release has an empty [debugRows]): the listening wave's
 * geometry, to compare the playground's coverage options on the phone. Applies live to every
 * wave.
 *
 * TODO(before v0.1.0): remove this file, its release stub, the debugRows() call in Advanced and
 * WaveShape's switchboard; keep the chosen shape. English only on purpose: never in a release.
 */
fun LazyListScope.debugRows() {
    // Screenshot tests (Robolectric) run the debug variant; keep the temporary row out of the
    // goldens so they show the screen as released.
    if (Build.FINGERPRINT == "robolectric") return
    item { SectionHeader("Wave shape (temporary)") }
    item { WaveShapeDebug() }
}

/**
 * The playground's coverage options (Burble's design notes, material/images/wave-coverage), now drawn
 * to the 96 dp bubble and scaled to the size set; "Tom 1.50" is the default.
 */
private val presets = listOf(
    "Tom 1.50" to WaveShape(),
    "Radial today" to WaveShape(cover = 1f),
    "A bigger radial" to WaveShape(cover = 1.1f, edgeRest = 17f, edgeGain = 17f),
    "B ellipse 0.4" to WaveShape(cover = 1f, blend = 0.4f),
    "C ellipse 0.6" to WaveShape(cover = 1f, blend = 0.6f),
    "D low source" to WaveShape(cover = 1.3f, lift = 0.3f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaveShapeDebug() {
    val context = LocalContext.current
    val shape = WaveShape.current
    val set = { s: WaveShape -> WaveShape.save(context, s) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Debug build only. Listen to see it; it applies to every wave.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((name, preset) in presets) {
                    FilterChip(selected = shape == preset, onClick = { set(preset) }, label = { Text(name) })
                }
            }
            Tune("Coverage", shape.cover, 0.6f..1.8f) { set(shape.copy(cover = it)) }
            Tune("Radial to midline", shape.blend, 0f..0.95f) { set(shape.copy(blend = it)) }
            Tune("Source below centre", shape.lift, 0f..0.8f) { set(shape.copy(lift = it)) }
            Text("Edge: %.1f dp in silence, + %.1f dp at full voice".format(shape.edgeRest, shape.edgeGain),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tune(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value, { onChange((it * 100).toInt() / 100f) }, Modifier.weight(1f), valueRange = range)
            Text("%.2f".format(value), Modifier.padding(start = 12.dp).widthIn(min = 44.dp),
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
        }
    }
}
