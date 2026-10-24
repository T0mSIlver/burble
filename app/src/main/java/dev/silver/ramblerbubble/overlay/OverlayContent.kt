package dev.silver.ramblerbubble.overlay

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.silver.ramblerbubble.BubbleShape
import dev.silver.ramblerbubble.Dictation
import dev.silver.ramblerbubble.Dictation.ErrorKind
import dev.silver.ramblerbubble.Output
import dev.silver.ramblerbubble.Prefs
import dev.silver.ramblerbubble.R
import kotlinx.coroutines.delay
import kotlin.math.max

/** What the overlay draws; several [Dictation.State]s share one. */
internal sealed interface Look {
    data object Idle : Look
    data object Listening : Look
    data object Finishing : Look
    data object Inserted : Look
    data object Copied : Look
    data class Held(val text: String) : Look
    data class Failed(val kind: ErrorKind) : Look
}

internal fun Dictation.State.look(): Look = when (this) {
    Dictation.State.Idle -> Look.Idle
    Dictation.State.Listening -> Look.Listening
    Dictation.State.Finishing -> Look.Finishing
    is Dictation.State.Done -> when (output) {
        Output.INSERT -> Look.Inserted
        Output.CLIPBOARD -> Look.Copied
        Output.HOLD -> Look.Held(text)
    }
    is Dictation.State.Error -> Look.Failed(kind)
}

internal class OverlayActions(
    val start: () -> Unit,
    val cancel: () -> Unit,
    val stop: () -> Unit,
    val insert: (String) -> Unit,
    val copy: (String) -> Unit,
    val dismiss: () -> Unit,
) {
    companion object {
        val None = OverlayActions({}, {}, {}, {}, {}, {})
    }
}

/** The overlay wired to [Dictation] and [Prefs]: confirmations time out, haptics mark changes. */
@Composable
internal fun LiveOverlay(idleBubble: State<Boolean>, alignEnd: State<Boolean>) {
    val context = LocalContext.current
    val state by Dictation.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val look = state.look()
    val view = LocalView.current
    var last by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        if (look::class != last::class) haptic(view, look)
        last = look
        val hold = when (look) {
            Look.Inserted -> 900L
            Look.Copied -> 1500L
            is Look.Failed -> if (look.kind == ErrorKind.QUOTA || look.kind == ErrorKind.REJECTED) 5000L else 3000L
            else -> return@LaunchedEffect
        }
        delay(hold)
        Dictation.acknowledge()
    }
    if (look == Look.Idle && !idleBubble.value) return
    val actions = remember(context) {
        OverlayActions(
            start = { Dictation.start(context) },
            cancel = { Dictation.cancel() },
            stop = { Dictation.stop() },
            insert = { Dictation.insert(context, it); Dictation.acknowledge() },
            copy = { Dictation.copy(context, it); Dictation.acknowledge() },
            dismiss = { Dictation.acknowledge() },
        )
    }
    OverlayContent(look, settings.shape, actions, alignEnd.value) { start, end, modifier ->
        LiveWave(Dictation.level, start, end, modifier)
    }
}

private fun haptic(view: View, look: Look) {
    val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val effect = when (look) {
        Look.Listening -> HapticFeedbackConstants.VIRTUAL_KEY
        Look.Finishing -> HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
        Look.Inserted, Look.Copied, is Look.Held ->
            if (r) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        is Look.Failed -> if (r) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Look.Idle -> return
    }
    view.performHapticFeedback(effect)
}

/** Draws the wave band; the host passes the live one, screenshot tests a frozen one. */
internal typealias WaveSlot = @Composable (start: Color, end: Color, modifier: Modifier) -> Unit

private val Corner = 28.dp
private val Bubble = 56.dp

/**
 * The bubble and the shapes it turns into. Leaving idle, the bubble's shape first morphs to a
 * circle, then the circle stretches into the pill; coming back, the reverse. Every wider look is
 * a rounded rectangle with the circle's corner radius, so the stretch has no seam.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverlayContent(
    look: Look,
    shape: BubbleShape,
    actions: OverlayActions,
    /** Pinned to the right edge: grow leftwards. */
    alignEnd: Boolean,
    wave: WaveSlot,
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fast = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val morph = remember(shape) { Morph(shape.polygon(), MaterialShapes.Circle) }
    val progress = remember { Animatable(if (look == Look.Idle) 0f else 1f) }
    var shown by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        when {
            shown == Look.Idle && look != Look.Idle -> {
                progress.animateTo(1f, fast)
                shown = look
            }
            look == Look.Idle && shown != Look.Idle -> {
                shown = look
                delay(250) // let the pill shrink back to a circle first
                progress.animateTo(0f, spatial)
            }
            else -> shown = look
        }
    }

    val colors = MaterialTheme.colorScheme
    val (container, content) = when (shown) {
        Look.Idle, Look.Finishing -> colors.primaryContainer to colors.onPrimaryContainer
        Look.Inserted -> colors.primary to colors.onPrimary
        is Look.Failed -> if ((shown as Look.Failed).kind == ErrorKind.NOTHING_HEARD) {
            colors.surfaceContainerHigh to colors.onSurface
        } else {
            colors.errorContainer to colors.onErrorContainer
        }
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val bg by animateColorAsState(container, effects)
    val fg by animateColorAsState(content, effects)

    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, fast)
    val rounded = remember { RoundedCornerShape(Corner) }

    // Resizing an overlay window is a round trip to the system, too slow to do every frame.
    // During a change of look the window takes both looks' bounds at once, and the container
    // animates inside it.
    val transition = updateTransition(shown, label = "look")
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .padding(8.dp) // room for the shadow
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val hold = transition.isRunning || transition.currentState != transition.targetState
                val w = if (hold) max(p.width, bounds.width) else p.width
                val h = if (hold) max(p.height, bounds.height) else p.height
                layout(w, h) { p.place(if (alignEnd) w - p.width else 0, 0) }
            },
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 6.dp.toPx()
                    this.shape = progress.value.let { if (it >= 1f) rounded else MorphShape(morph, it, rounded) }
                    clip = true
                }
                .drawBehind { drawRect(bg) },
        ) {
            transition.AnimatedContent(
                contentKey = { it::class },
                contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
                transitionSpec = {
                    fadeIn(fadeSpec) togetherWith fadeOut(fadeOutSpec) using
                        SizeTransform(clip = false) { from, to ->
                            bounds = IntSize(max(from.width, to.width), max(from.height, to.height))
                            sizeSpec
                        }
                },
            ) { s ->
                when (s) {
                    Look.Idle -> Box(
                        Modifier.size(Bubble).clickable(press, null, onClickLabel = null) { actions.start() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Mic, stringResource(R.string.overlay_start), Modifier.size(26.dp), fg)
                    }
                    Look.Listening -> Row(
                        Modifier.width(264.dp).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundButton(Icons.Rounded.Close, stringResource(R.string.overlay_cancel),
                            colors.surfaceContainerHighest, colors.onSurfaceVariant, actions.cancel)
                        wave(colors.tertiary, colors.primary,
                            Modifier.weight(1f).heightIn(40.dp, 40.dp).padding(horizontal = 6.dp))
                        RoundButton(Icons.Rounded.Check, stringResource(R.string.overlay_stop),
                            colors.primary, colors.onPrimary, actions.stop)
                    }
                    Look.Finishing -> Box(Modifier.size(Bubble), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(44.dp), color = fg)
                    }
                    Look.Inserted -> Box(Modifier.size(Bubble), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, stringResource(R.string.overlay_inserted), Modifier.size(28.dp), fg)
                    }
                    Look.Copied -> Note(Icons.Rounded.ContentCopy, stringResource(R.string.overlay_copied), fg,
                        colors.primary, actions.dismiss)
                    is Look.Held -> Held(s.text, fg, actions)
                    is Look.Failed -> Note(s.kind.icon(), stringResource(s.kind.message()), fg, fg, actions.dismiss)
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).background(container, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), content)
    }
}

/** One line or two, with an icon: confirmations and errors. Tap to dismiss. */
@Composable
private fun Note(icon: ImageVector, text: String, fg: Color, iconColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 296.dp).heightIn(min = Bubble).clickable(onClick = onClick)
            .padding(start = 18.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), iconColor)
        Text(text, color = fg, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Held(text: String, fg: Color, actions: OverlayActions) {
    Column(Modifier.width(296.dp).padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
        Text(
            text,
            Modifier.heightIn(max = 168.dp).padding(end = 8.dp).verticalScroll(rememberScrollState()),
            color = fg,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(actions.dismiss) {
                Icon(Icons.Rounded.Close, stringResource(R.string.overlay_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton({ actions.copy(text) }) { Text(stringResource(R.string.overlay_copy)) }
            Spacer(Modifier.width(8.dp))
            Button({ actions.insert(text) }) { Text(stringResource(R.string.overlay_insert)) }
        }
    }
}

private fun ErrorKind.message() = when (this) {
    ErrorKind.NO_MIC -> R.string.overlay_error_no_mic
    ErrorKind.NOTHING_HEARD -> R.string.overlay_error_nothing_heard
    ErrorKind.QUOTA -> R.string.overlay_error_quota
    ErrorKind.REJECTED -> R.string.overlay_error_rejected
    ErrorKind.NETWORK -> R.string.overlay_error_network
    ErrorKind.OTHER -> R.string.overlay_error_other
}

private fun ErrorKind.icon() = when (this) {
    ErrorKind.NO_MIC -> Icons.Rounded.MicOff
    ErrorKind.NOTHING_HEARD -> Icons.Rounded.VoiceOverOff
    ErrorKind.QUOTA -> Icons.Rounded.HourglassEmpty
    ErrorKind.REJECTED -> Icons.Rounded.Block
    ErrorKind.NETWORK -> Icons.Rounded.CloudOff
    ErrorKind.OTHER -> Icons.Rounded.ErrorOutline
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun BubbleShape.polygon(): RoundedPolygon = when (this) {
    BubbleShape.COOKIE_9 -> MaterialShapes.Cookie9Sided
    BubbleShape.SUNNY -> MaterialShapes.Sunny
    BubbleShape.CLOVER_4 -> MaterialShapes.Clover4Leaf
    BubbleShape.CIRCLE -> MaterialShapes.Circle
    BubbleShape.PILL -> MaterialShapes.Pill
    BubbleShape.SOFT_BURST -> MaterialShapes.SoftBurst
}

/** The bubble's shape [progress] of the way to a circle; [wide] once it is no longer square. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class MorphShape(private val morph: Morph, private val progress: Float, private val wide: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (kotlin.math.abs(size.width - size.height) > 0.5f) return wide.createOutline(size, layoutDirection, density)
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
