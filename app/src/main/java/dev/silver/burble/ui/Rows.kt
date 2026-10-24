package dev.silver.burble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One row of a settings card; [Group] gives it the shape of its place in the card. */
typealias SettingRow = @Composable (ListItemShapes) -> Unit

/** Rows joined into one card, as Android's own Settings groups them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Group(rows: List<SettingRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, row -> row(ListItemDefaults.segmentedShapes(i, rows.size)) }
    }
}

// Segmented items default to the surface colour, which vanishes on a surface background.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun listColors(tintSelected: Boolean = true) = MaterialTheme.colorScheme.let {
    ListItemDefaults.segmentedColors(
        containerColor = it.surfaceContainer,
        disabledContainerColor = it.surfaceContainer,
        selectedContainerColor = if (tintSelected) it.secondaryContainer else it.surfaceContainer,
        selectedContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurface,
        selectedSupportingContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurfaceVariant,
        selectedLeadingContentColor = if (tintSelected) it.onSecondaryContainer else it.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun switchRow(icon: ImageVector, title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        colors = listColors(tintSelected = false),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = hint?.let { { Text(it) } },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun linkRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = listColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Text(value) },
    ) { Text(title) }
}

/** A row whose body is arbitrary content under its title, such as a picker. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun contentRow(icon: ImageVector, title: String, body: @Composable () -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        shapes = shapes,
        colors = listColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Column(Modifier.padding(top = 12.dp)) { body() } },
    ) { Text(title) }
}
