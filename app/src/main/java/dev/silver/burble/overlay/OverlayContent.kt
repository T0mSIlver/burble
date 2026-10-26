package dev.silver.burble.overlay

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CloudUpload
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
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.silver.burble.BubbleShape
import dev.silver.burble.WhenIdle
import dev.silver.burble.Dictation
import dev.silver.burble.MainActivity
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

/** The bubble wired to [Dictation] and [Prefs]: results time out, haptics mark changes. */
@Composable
internal fun LiveOverlay(
    alignEnd: State<Boolean>,
    locked: State<Boolean>,
    away: State<Boolean>,
    touching: State<Boolean>,
    windowShift: State<Float>,
    onDock: (Float) -> Unit,
) {
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
    // 3 s untouched in Idle, counted again after every touch and every dictation.
    var rest by remember { mutableStateOf(false) }
    val idle = look is Look.Idle && !touching.value
    LaunchedEffect(idle) {
        rest = false
        if (idle) {
            delay(REST_MS)
            rest = true
        }
    }
    if (locked.value) return
    AnimatedVisibility(!away.value, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
        OverlayContent(look, settings.shape, settings.bubbleSize.dp, alignEnd.value, labelled,
            rest = rest, whenIdle = settings.whenIdle, idleOpacity = settings.idleOpacity / 100f,
            touching = touching.value,
            windowShift = { windowShift.value },
            onDock = onDock,
            onOpenApp = {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onTap = {
            when (look) {
                // Ready: the waiting text stays on the clipboard. Failed: retries the kept audio,
                // or starts over when there is none.
                Look.Idle, Look.Ready, Look.Listening, is Look.Failed -> Dictation.toggle(context)
                Look.Finishing, Look.Done -> Unit
            }
        }) { color, modifier -> LiveWave(Dictation.level, color, modifier) }
    }
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
 * The wave's hue source: the primary, as Gboard takes its glow from the system accent. [Wave]
 * turns it into the glow's own lighter, more saturated colour.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ColorScheme.waveColor(wallpaper: Boolean): Color = primary

/** Draws the wave; the live overlay passes the mic's, screenshot tests a frozen one. */
internal typealias WaveSlot = @Composable (color: Color, modifier: Modifier) -> Unit

private const val REST_MS = 3000L

/**
 * Between full (0) and at rest (1). Critically damped: no overshoot, so size, dock and opacity
 * each move one way from one look to the other.
 */
internal val RestSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The bubble's scale, outward offset in pixels and alpha at [progress] towards rest. */
internal data class RestLook(val scale: Float, val offset: Float, val alpha: Float)

internal fun restLook(progress: Float, idleScale: Float, idleOpacity: Float, dock: Float) = RestLook(
    scale = 1f + (idleScale - 1f) * progress,
    offset = dock * progress,
    alpha = 1f + (idleOpacity - 1f) * progress,
)

/** "Shrink" draws the idle bubble at this share of its size. */
private const val SHRUNK = 0.6f
private val Dot = 12.dp
/** A docked bubble's gap to the screen edge. */
private val DockInset = 4.dp
/** The least of a docked bubble's touch area left on screen. */
private val MinTouch = 48.dp
/** The window's padding around the bubble, room for its shadow. */
private val Padding = 8.dp

/**
 * The bubble, the only thing Burble draws over other apps. It keeps its shape and size in
 * every state, so nothing it covers changes while you talk: the wave, the loader and the check
 * all play inside it. An error tints it and puts a one-line reason beside it, on the side
 * facing the middle of the screen. Only Idle fades and, once at rest, shrinks to the nearest
 * side edge: smaller or a dot, docked [DockInset] from it. The layout keeps the full size; the
 * window follows the docked bubble out past the edge, by the shift [onDock] asks for, so the touch
 * area left on screen hugs the edge. [windowShift] is how far out the window is now: the bubble
 * is drawn against it, so it moves smoothly whatever the window does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverlayContent(
    look: Look,
    shape: BubbleShape,
    side: Dp,
    /** Pinned to the right edge: the label goes on the left. */
    alignEnd: Boolean,
    /** An error's reason shows beside the bubble; it goes after 4 s, the error colour stays. */
    labelled: Boolean,
    /** Idle and untouched for 3 s: [whenIdle] applies. */
    rest: Boolean = false,
    whenIdle: WhenIdle = WhenIdle.SHRINK,
    /** Idle's opacity, 0.2 to 1. */
    idleOpacity: Float = 1f,
    /** A finger is on the bubble, dragging or not: full size and opacity. */
    touching: Boolean = false,
    /** How far past its edge position the window sits, outward, in pixels. */
    windowShift: () -> Float = { 0f },
    /** Asks for the window's outward shift while docked; 0 to undock. */
    onDock: (Float) -> Unit = {},
    /** The error label's action, for errors only the app can fix. */
    onOpenApp: () -> Unit = {},
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
    // Two looks only: full, or at rest (3 s untouched in Idle) with the idle size, dock and
    // opacity together. One progress drives them all, so nothing changes on its own.
    val atRest = look == Look.Idle && rest && !touching && !pressed
    val progress by animateFloatAsState(if (atRest) 1f else 0f, RestSpec)
    val idleScale = when (whenIdle) {
        WhenIdle.FULL -> 1f
        WhenIdle.SHRINK -> SHRUNK
        WhenIdle.DOT -> Dot / side
    }
    // Docked: the bubble's centre moves out until its edge is DockInset from the screen's.
    val density = LocalDensity.current
    val window = with(density) { (side + Padding * 2).toPx() }
    val dock = if (whenIdle == WhenIdle.FULL) 0f
        else with(density) { window / 2 - DockInset.toPx() - (side * idleScale).toPx() / 2 }
    val outward = if (alignEnd) 1f else -1f
    LaunchedEffect(atRest, dock) {
        onDock(if (atRest) dock.coerceAtMost(window - with(density) { MinTouch.toPx() }).coerceAtLeast(0f) else 0f)
    }
    val rested = restLook(progress, idleScale, idleOpacity, dock)
    val polygon = shape.polygon()
    val outline = polygon.toShape()
    val keyShadow = remember(outline) { DropShadowPainter(outline, KeyShadow) }
    val ambientShadow = remember(outline) { DropShadowPainter(outline, AmbientShadow) }
    val failed = (look as? Look.Failed)?.takeIf { labelled }

    // Room for the shadow inside the window. The whole window takes the tap: hit tests follow the
    // bubble's scale, so a docked dot alone would be a 12 dp target.
    Row(
        Modifier
            .clickable(press, null, onClickLabel = stringResource(look.action())) { onTap() }
            .padding(Padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (alignEnd) Label(failed, onOpenApp)
        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    scaleX = scale * rested.scale
                    scaleY = scale * rested.scale
                    translationX = outward * (rested.offset - windowShift())
                    alpha = rested.alpha
                    // Offscreen, the fade would crop the shadow to the bubble's square.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                // M3's level-2 elevation, two layers, following the real outline. A blurred copy
                // of the shape, not shadowElevation: Skia's shadow for a concave path hung the
                // render thread on SoftBurst at every launch. Only outside the shape, so a faded
                // bubble shows what is under it, not its shadow.
                .drawWithCache {
                    val inside = Path().apply {
                        addOutline(outline.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache))
                    }
                    onDrawBehind {
                        clipPath(inside, ClipOp.Difference) {
                            with(keyShadow) { draw(this@onDrawBehind.size) }
                            with(ambientShadow) { draw(this@onDrawBehind.size) }
                        }
                    }
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
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = look,
                // A 12 dp dot has no room for the glyph.
                modifier = Modifier.graphicsLayer { alpha = if (whenIdle == WhenIdle.DOT) 1f - progress else 1f },
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
        if (!alignEnd) Label(failed, onOpenApp)
    }
}

private val KeyShadow = Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), alpha = 0.3f)
private val AmbientShadow = Shadow(radius = 6.dp, spread = 2.dp, offset = DpOffset(0.dp, 2.dp), alpha = 0.15f)

/**
 * An error beside the bubble, never cut short: what happened, then what to do. Errors the app
 * must fix (the microphone, a refusal) open it when tapped.
 */
@Composable
private fun Label(failed: Look.Failed?, onOpenApp: () -> Unit) {
    AnimatedVisibility(failed != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        // Kept through the exit, when [failed] is already null.
        val shown = remember { mutableStateOf(failed) }
        if (failed != null) shown.value = failed
        val f = shown.value ?: return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        val opens = f.kind == ErrorKind.NO_MIC || f.kind == ErrorKind.REJECTED
        Column(
            Modifier
                .widthIn(max = 240.dp)
                .graphicsLayer { shadowElevation = 3.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                .background(colors.errorContainer)
                .then(if (opens) Modifier.clickable(onClick = onOpenApp) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(f.kind.message()), color = colors.onErrorContainer, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(f.action()), color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Look.Failed.action() = when (kind) {
    ErrorKind.NO_MIC -> R.string.overlay_action_allow_mic
    ErrorKind.REJECTED -> R.string.overlay_action_update
    ErrorKind.QUOTA -> R.string.overlay_action_later
    ErrorKind.NOTHING_HEARD -> R.string.overlay_action_speak
    ErrorKind.NETWORK, ErrorKind.OTHER -> R.string.overlay_action_retry
    ErrorKind.UNSENT -> R.string.overlay_action_retry_unsent
}

/**
 * The ✕ at the bottom of the screen while the bubble is dragged: a surface-container circle that
 * grows and turns the error container colour when the bubble is over it. Dropping there hides
 * the bubble for now.
 */
@Composable
internal fun DismissTarget(armed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val grow by animateFloatAsState(if (armed) 1.2f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    val bg by animateColorAsState(if (armed) colors.errorContainer else colors.surfaceContainerHighest)
    val fg by animateColorAsState(if (armed) colors.onErrorContainer else colors.onSurface)
    Box(
        Modifier
            .padding(12.dp)
            .size(64.dp)
            .graphicsLayer { scaleX = grow; scaleY = grow; shadowElevation = 6.dp.toPx(); shape = CircleShape; clip = true }
            .drawBehind { drawRect(bg) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, stringResource(R.string.overlay_close), Modifier.size(28.dp), fg)
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
    ErrorKind.UNSENT -> R.string.overlay_error_unsent
}

private fun ErrorKind.icon(): ImageVector = when (this) {
    ErrorKind.NO_MIC -> Icons.Rounded.MicOff
    ErrorKind.NOTHING_HEARD -> Icons.Rounded.VoiceOverOff
    ErrorKind.QUOTA -> Icons.Rounded.HourglassEmpty
    ErrorKind.REJECTED -> Icons.Rounded.Block
    ErrorKind.NETWORK -> Icons.Rounded.CloudOff
    ErrorKind.OTHER -> Icons.Rounded.ErrorOutline
    ErrorKind.UNSENT -> Icons.Rounded.CloudUpload
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun BubbleShape.polygon(): RoundedPolygon = when (this) {
    BubbleShape.COOKIE_9 -> MaterialShapes.Cookie9Sided
    BubbleShape.SUNNY -> MaterialShapes.Sunny
    BubbleShape.CLOVER_4 -> MaterialShapes.Clover4Leaf
    BubbleShape.CIRCLE -> MaterialShapes.Circle
    BubbleShape.OVAL -> MaterialShapes.Oval
    BubbleShape.SOFT_BURST -> MaterialShapes.SoftBurst
}
