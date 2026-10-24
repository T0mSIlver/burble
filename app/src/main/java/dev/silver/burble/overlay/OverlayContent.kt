package dev.silver.burble.overlay

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.silver.burble.BubbleShape
import dev.silver.burble.BubbleSize
import dev.silver.burble.Dictation
import dev.silver.burble.Dictation.ErrorKind
import dev.silver.burble.Prefs
import dev.silver.burble.R
import dev.silver.burble.ui.theme.LocalWallpaperColors
import kotlinx.coroutines.delay

/** What the bubble draws; several [Dictation.State]s share one. */
internal sealed interface Look {
    data object Idle : Look
    data object Listening : Look
    data object Finishing : Look
    data object Done : Look
    data object Ready : Look
    /** [waits]: the audio is kept; a tap retries it. */
    data class Failed(val kind: ErrorKind, val waits: Boolean = false) : Look
}

internal fun Dictation.State.look(): Look = when (this) {
    Dictation.State.Idle -> Look.Idle
    Dictation.State.Listening -> Look.Listening
    Dictation.State.Finishing -> Look.Finishing
    is Dictation.State.Done -> Look.Done
    is Dictation.State.Ready -> Look.Ready
    is Dictation.State.Error -> Look.Failed(kind, waits)
}

internal fun BubbleSize.dp(): Dp = when (this) {
    BubbleSize.SMALL -> 52.dp
    BubbleSize.MEDIUM -> 64.dp
    BubbleSize.LARGE -> 76.dp
}

/** The bubble wired to [Dictation] and [Prefs]: results time out, haptics mark changes. */
@Composable
internal fun LiveOverlay(alignEnd: State<Boolean>, locked: State<Boolean>) {
    val context = LocalContext.current
    val state by Dictation.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val look = state.look()
    val view = LocalView.current
    var last by remember { mutableStateOf(look) }
    var labelled by remember { mutableStateOf(false) }
    LaunchedEffect(look) {
        if (look::class != last::class) haptic(view, look)
        last = look
        labelled = look is Look.Failed
        val hold = when (look) {
            Look.Done -> 700L
            is Look.Failed -> 4000L
            else -> return@LaunchedEffect
        }
        delay(hold)
        labelled = false
        // A waiting error stays: error colour and retry glyph, until a tap or ✕.
        Dictation.acknowledge()
    }
    if (locked.value) return
    OverlayContent(look, settings.shape, settings.bubbleSize, alignEnd.value, labelled, onTap = {
        when (look) {
            // Ready: the waiting text stays on the clipboard. Failed: retries the kept audio,
            // or starts over when there is none.
            Look.Idle, Look.Ready, Look.Listening, is Look.Failed -> Dictation.toggle(context)
            Look.Finishing, Look.Done -> Unit
        }
    }) { color, modifier -> LiveWave(Dictation.level, color, modifier) }
}

private fun haptic(view: View, look: Look) {
    val effect = when (look) {
        Look.Listening -> HapticFeedbackConstants.GESTURE_START
        Look.Finishing -> HapticFeedbackConstants.GESTURE_END
        Look.Done, Look.Ready -> HapticFeedbackConstants.CONFIRM
        is Look.Failed -> HapticFeedbackConstants.REJECT
        Look.Idle -> return
    }
    view.performHapticFeedback(effect)
}

/**
 * The wave's hue, at a light tone: the wallpaper's tertiary, as Gboard's bar uses; under the
 * Google blue fallback, whose tertiary is a green that would look accidental, the primary blue.
 * Light themes' accents are dark (tone 40), so they are lifted towards their container; dark
 * themes' are already light (tone 80).
 */
internal fun ColorScheme.waveColor(wallpaper: Boolean): Color {
    val (accent, container) = if (wallpaper) tertiary to tertiaryContainer else primary to primaryContainer
    return if (surface.luminance() < 0.5f) accent else lerp(accent, container, 0.6f)
}

/** Draws the wave; the live overlay passes the mic's, screenshot tests a frozen one. */
internal typealias WaveSlot = @Composable (color: Color, modifier: Modifier) -> Unit

/**
 * The bubble, the only thing Burble draws over other apps. It keeps its shape and size in
 * every state, so nothing it covers changes while you talk: the wave, the loader and the check
 * all play inside it. An error tints it and puts a one-line reason beside it, on the side
 * facing the middle of the screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverlayContent(
    look: Look,
    shape: BubbleShape,
    size: BubbleSize,
    /** Pinned to the right edge: the label goes on the left. */
    alignEnd: Boolean,
    /** An error's reason shows beside the bubble; it goes after 4 s, the error colour stays. */
    labelled: Boolean,
    onTap: () -> Unit,
    wave: WaveSlot,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (look) {
        Look.Done -> colors.primary to colors.onPrimary
        // Tertiary is the wallpaper's accent; the Google blue fallback's is an accidental green.
        Look.Ready -> if (LocalWallpaperColors.current) colors.tertiaryContainer to colors.onTertiaryContainer
            else colors.secondaryContainer to colors.onSecondaryContainer
        is Look.Failed -> colors.errorContainer to colors.onErrorContainer
        // Gboard's bar: the glow on the plain surface.
        Look.Listening -> colors.surfaceContainerLowest to colors.onSurface
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val bg by animateColorAsState(container, effects)
    val fg by animateColorAsState(content, effects)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    val polygon = shape.polygon()
    val outline = polygon.toShape()
    val shadow = remember(polygon) { convexHull(polygon) }
    val side = size.dp()
    val label = (look as? Look.Failed)?.takeIf { labelled }?.let { stringResource(it.kind.message()) }

    // Room for the shadow inside the window.
    Row(
        Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (alignEnd) Label(label)
        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 6.dp.toPx()
                    // Skia's shadow for a concave path can hang the render thread (SoftBurst did,
                    // on every launch): the shadow takes the shape's convex hull instead.
                    this.shape = shadow
                }
                // Drawn offscreen, then everything outside the shape cleared: a mask, not a clip.
                // Android clips children to a non-convex outline only from 13 on, and a child
                // fading in at partial alpha showed its square past the shape.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val outside = Path().apply {
                        addRect(Rect(Offset.Zero, this@drawWithCache.size))
                        addOutline(outline.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache))
                        fillType = PathFillType.EvenOdd
                    }
                    onDrawWithContent {
                        drawRect(bg)
                        drawContent()
                        drawPath(outside, Color.Black, blendMode = BlendMode.Clear)
                    }
                }
                .clickable(press, null, onClickLabel = stringResource(look.action())) { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = look,
                contentKey = { it::class },
                transitionSpec = {
                    // Every look fills the bubble, so no size transform clips one to a growing box.
                    fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec) using null
                },
                contentAlignment = Alignment.Center,
            ) { s ->
                val glyph = Modifier.size(side * 0.42f)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { when (s) {
                    Look.Idle -> Icon(Icons.Rounded.Mic, stringResource(R.string.overlay_start), glyph, fg)
                    Look.Listening -> wave(colors.waveColor(LocalWallpaperColors.current), Modifier.fillMaxSize())
                    Look.Finishing -> LoadingIndicator(Modifier.size(side * 0.72f), color = colors.primary)
                    Look.Done -> Icon(Icons.Rounded.Check, stringResource(R.string.overlay_inserted), glyph, fg)
                    Look.Ready -> Icon(Icons.Rounded.ContentPaste, stringResource(R.string.overlay_ready), glyph, fg)
                    is Look.Failed -> Icon(if (s.waits) Icons.Rounded.Refresh else s.kind.icon(),
                        stringResource(s.kind.message()), glyph, fg)
                } }
            }
        }
        if (!alignEnd) Label(label)
    }
}

/** The reason for an error, beside the bubble: one line, on the inverse surface like a snackbar. */
@Composable
private fun Label(text: String?) {
    AnimatedVisibility(text != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        Text(
            text.orEmpty(),
            Modifier
                .widthIn(max = 260.dp)
                .graphicsLayer { shadowElevation = 3.dp.toPx(); shape = CircleShape; clip = true }
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The drop target at the bottom of the screen while the bubble is dragged. [discards]: a
 * dictation is running or its text waits for a field, so dropping drops it; otherwise dropping
 * turns the bubble off.
 */
@Composable
internal fun DismissTarget(armed: Boolean, discards: Boolean) {
    val colors = MaterialTheme.colorScheme
    val grow by animateFloatAsState(if (armed) 1.2f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    val bg by animateColorAsState(if (armed) colors.error else colors.inverseSurface)
    val fg by animateColorAsState(if (armed) colors.onError else colors.inverseOnSurface)
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer { scaleX = grow; scaleY = grow; shadowElevation = 6.dp.toPx(); shape = CircleShape; clip = true }
                .drawBehind { drawRect(bg) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, null, Modifier.size(28.dp), fg)
        }
        Text(
            stringResource(if (discards) R.string.overlay_drop_cancel else R.string.overlay_drop_off),
            Modifier
                .padding(top = 12.dp)
                .background(colors.inverseSurface, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.inverseOnSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun Look.action() = when (this) {
    Look.Listening -> R.string.overlay_stop
    Look.Ready -> R.string.overlay_start
    is Look.Failed -> R.string.overlay_retry
    else -> R.string.overlay_start
}

private fun ErrorKind.message() = when (this) {
    ErrorKind.NO_MIC -> R.string.overlay_error_no_mic
    ErrorKind.NOTHING_HEARD -> R.string.overlay_error_nothing_heard
    ErrorKind.QUOTA -> R.string.overlay_error_quota
    ErrorKind.REJECTED -> R.string.overlay_error_rejected
    ErrorKind.NETWORK -> R.string.overlay_error_network
    ErrorKind.OTHER -> R.string.overlay_error_other
}

private fun ErrorKind.icon(): ImageVector = when (this) {
    ErrorKind.NO_MIC -> Icons.Rounded.MicOff
    ErrorKind.NOTHING_HEARD -> Icons.Rounded.VoiceOverOff
    ErrorKind.QUOTA -> Icons.Rounded.HourglassEmpty
    ErrorKind.REJECTED -> Icons.Rounded.Block
    ErrorKind.NETWORK -> Icons.Rounded.CloudOff
    ErrorKind.OTHER -> Icons.Rounded.ErrorOutline
}

/**
 * The convex hull of [polygon]'s control points, in its unit square scaled to the size: it holds
 * the whole shape, and a convex outline keeps Skia's shadow on its fast path.
 */
internal fun convexHull(polygon: RoundedPolygon): Shape {
    // Rounded, so float noise doesn't leave near-duplicates that bend the hull inwards.
    fun round(v: Float) = kotlin.math.round(v * 10_000f) / 10_000f
    val points = polygon.cubics.flatMap {
        listOf(it.anchor0X to it.anchor0Y, it.control0X to it.control0Y,
            it.control1X to it.control1Y, it.anchor1X to it.anchor1Y)
    }.map { (x, y) -> round(x) to round(y) }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
        (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
    // Andrew's monotone chain.
    val hull = mutableListOf<Pair<Float, Float>>()
    for (pass in listOf(points, points.reversed())) {
        val start = hull.size
        for (p in pass) {
            while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull.last(), p) <= 1e-6f) hull.removeAt(hull.lastIndex)
            hull.add(p)
        }
        hull.removeAt(hull.lastIndex)
    }
    return GenericShape { size, _ ->
        hull.forEachIndexed { i, (x, y) ->
            if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height)
        }
        close()
    }
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
