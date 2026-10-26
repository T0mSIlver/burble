package dev.silver.burble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One row of a settings group; [Group] gives it the shape of its place in the group. */
typealias SettingRow = @Composable (ListItemShapes) -> Unit

private val Outer = 24.dp
private val Inner = 4.dp

/**
 * The corners of item [index] of [count] in a group, as Android 16's Settings draws them: large
 * at the group's outer edges, small between items, large all round for a lone item. Every card
 * and group in the app takes its shape from here.
 */
fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) Outer else Inner
    val bottom = if (index == count - 1) Outer else Inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The gap between the separate surfaces of one group. */
val GroupGap = 2.dp

/** Rows joined into one group, as Android's own Settings groups them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Group(rows: List<SettingRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        rows.forEachIndexed { i, row ->
            val shape = segmentShape(i, rows.size)
            row(ListItemShapes(shape, shape, shape, shape, shape, shape))
        }
    }
}

/** Row [index] of a group of [count], drawn on its own, for groups split across list items. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupRow(row: SettingRow, index: Int, count: Int, modifier: Modifier = Modifier) {
    val shape = segmentShape(index, count)
    Column(modifier.padding(top = if (index > 0) GroupGap else 0.dp)) {
        row(ListItemShapes(shape, shape, shape, shape, shape, shape))
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

/** One of a few rows to choose between, as Android's settings mark them: a radio button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun radioRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        colors = listColors(tintSelected = false),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = summary?.let { { Text(it) } },
    ) { Text(title) }
}

/**
 * A row whose body is arbitrary content under its title, such as a picker. Without an icon the
 * body spans the card, as a picker needs; without a title it is the whole row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun contentRow(icon: ImageVector?, title: String?, body: @Composable () -> Unit): SettingRow = { shapes ->
    SegmentedListItem(
        shapes = shapes,
        colors = listColors(),
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = title?.let { { Column(Modifier.padding(top = 12.dp)) { body() } } },
    ) { if (title != null) Text(title) else Column(Modifier.padding(vertical = 4.dp)) { body() } }
}
