package dev.silver.burble.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.silver.burble.BubbleShape
import dev.silver.burble.R
import dev.silver.burble.Settings
import dev.silver.burble.WhenIdle
import dev.silver.burble.overlay.Look
import dev.silver.burble.overlay.OverlayContent
import dev.silver.burble.overlay.polygon
import kotlin.math.roundToInt

const val MIN_SIZE = 40
const val MAX_SIZE = 96

/**
 * The bubble as it will look, drawn by the overlay's own code against the edge of a sketched
 * screen: in use, and after 3 s idle, smaller or a dot docked to the edge, at the idle opacity.
 * Both follow every setting as it changes.
 */
@Composable
fun BubblePreview(settings: Settings) {
    val appearance = stringResource(when (settings.whenIdle) {
        WhenIdle.FULL -> R.string.when_idle_full
        WhenIdle.SHRINK -> R.string.when_idle_shrink
        WhenIdle.DOT -> R.string.when_idle_dot
    })
    // The size eases to the slider's value, as the bubble on screen does.
    val side by animateDpAsState(settings.bubbleSize.dp, MaterialTheme.motionScheme.defaultSpatialSpec())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PreviewHalf(stringResource(R.string.preview_active), stringResource(R.string.preview_active_description)) {
            OverlayContent(Look.Idle, settings.shape, side, alignEnd = true, labelled = false) { _, _ -> }
        }
        PreviewHalf(
            stringResource(R.string.preview_idle),
            stringResource(R.string.preview_idle_description, appearance, settings.idleOpacity),
        ) {
            OverlayContent(
                Look.Idle, settings.shape, side, alignEnd = true, labelled = false,
                rest = true, whenIdle = settings.whenIdle, idleOpacity = settings.idleOpacity / 100f,
            ) { _, _ -> }
        }
    }
}

/** A sketched screen, the bubble at its right edge. A picture: no taps, one TalkBack line. */
@Composable
private fun RowScope.PreviewHalf(label: String, description: String, bubble: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }) {
        Text(label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                // Room for the largest bubble and its shadow.
                .height(MAX_SIZE.dp + 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerLowest)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
                .drawBehind {
                    // Lines of text, so the idle opacity has something to show through.
                    val line = colors.onSurfaceVariant.copy(alpha = 0.18f)
                    val h = 8.dp.toPx()
                    var y = 18.dp.toPx()
                    var i = 0
                    while (y + h < size.height - 12.dp.toPx()) {
                        val w = size.width * listOf(0.82f, 0.9f, 0.64f, 0.86f, 0.74f)[i++ % 5] - 24.dp.toPx()
                        drawRoundRect(line, Offset(12.dp.toPx(), y), Size(w, h), CornerRadius(h / 2))
                        y += 18.dp.toPx()
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) { bubble() }
    }
}

/**
 * Three by two tiles across the card: each shape at one size in the bubble's own colours, so
 * only the outline differs; the selected tile takes a ring and a check. Its fill stays neutral:
 * the secondary container is close to the shape's primary container in many palettes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapePicker(selected: BubbleShape, onPick: (BubbleShape) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BubbleShape.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { shape ->
                    val on = shape == selected
                    val tile = MaterialTheme.shapes.large
                    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
                    val fill by animateColorAsState(if (on) colors.surfaceContainerHighest else colors.surfaceContainerHigh, effects)
                    val ring by animateColorAsState(if (on) colors.primary else colors.primary.copy(alpha = 0f), effects)
                    val label by animateColorAsState(if (on) colors.primary else colors.onSurfaceVariant, effects)
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(tile)
                            .background(fill)
                            .border(2.dp, ring, tile)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(shape) }),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(48.dp).background(colors.primaryContainer, shape.polygon().toShape()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
                            }
                            Text(
                                stringResource(shape.label),
                                Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = label,
                                textAlign = TextAlign.Center,
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = on,
                            modifier = Modifier.align(Alignment.TopEnd),
                            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                        ) {
                            Box(
                                Modifier.padding(6.dp).size(20.dp).background(colors.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val BubbleShape.label
    get() = when (this) {
        BubbleShape.COOKIE_9 -> R.string.shape_cookie
        BubbleShape.SUNNY -> R.string.shape_sunny
        BubbleShape.CLOVER_4 -> R.string.shape_clover
        BubbleShape.CIRCLE -> R.string.shape_circle
        BubbleShape.OVAL -> R.string.shape_oval
        BubbleShape.SOFT_BURST -> R.string.shape_burst
    }

/** One choice of a few, as a connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> Choices(choices: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        choices.forEachIndexed { i, (choice, label) ->
            ToggleButton(
                checked = choice == selected,
                onCheckedChange = { onPick(choice) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // The default unchecked colour is the card's own.
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(stringResource(label), textAlign = TextAlign.Center) }
        }
    }
}

/** A slider over [range] in steps of [step], with its value written beside it. */
@Composable
fun Stepped(value: Int, range: IntRange, step: Int, label: String?, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            modifier = Modifier.weight(1f),
        )
        if (label != null) Text(
            label,
            Modifier.padding(start = 16.dp).widthIn(min = 52.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
        )
    }
}
