package dev.silver.burble.overlay

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.MutableTransitionState
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import dev.silver.burble.BubbleShape
import dev.silver.burble.WhenIdle
import dev.silver.burble.Dictation
import dev.silver.burble.MainActivity
import dev.silver.burble.Output
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
    /** No field took the words: they're on the clipboard. */
    data object Copied : Look
    /** [waits]: the audio is kept; a tap retries it. */
    data class Failed(val kind: ErrorKind, val waits: Boolean = false) : Look
}

internal fun Dictation.State.look(): Look = when (this) {
    Dictation.State.Idle -> Look.Idle
    Dictation.State.Listening -> Look.Listening
    Dictation.State.Finishing -> Look.Finishing
    is Dictation.State.Done -> if (output == Output.CLIPBOARD) Look.Copied else Look.Done
    is Dictation.State.Error -> Look.Failed(kind, waits)
}

/** What the overlay's windows share: [Overlay] sets it, the composables in them read it. */
internal class OverlayUi {
    val screen = mutableStateOf(Screen(1f, 1f, Box(0f, 0f, 1f, 1f), 1f))
    val anchor = mutableStateOf(Anchor(Edge.RIGHT, 0.4f))
    /** How far a finger has dragged the bubble from its anchor, or the snap back's remainder. */
    val drag = mutableStateOf(0f to 0f)
    val locked = mutableStateOf(false)
    val away = mutableStateOf(false)
    /** Use Burble went off: the bubble fades out, then its windows go. */
    val closing = mutableStateOf(false)
    /** A finger is on the bubble, dragging or not. */
    val touching = mutableStateOf(false)
    /** A finger is on the bubble and hasn't started a drag: it's pressed. */
    val pressed = mutableStateOf(false)
    val dragging = mutableStateOf(false)
    /** Springing back to its edge after a drag. */
    val moving = mutableStateOf(false)
    /** An error's label shows. */
    val labelled = mutableStateOf(false)
    /** Docked at rest: the touch window narrows to the edge. */
    val docked = mutableStateOf(false)
    /** Idle and untouched: it may rest. */
    val idle = mutableStateOf(false)
    /** At rest, or on its way there: the idle look. */
    val rest = mutableStateOf(false)
    /** When a finger last left the bubble, in [android.os.SystemClock.uptimeMillis]. */
    var releasedAt = 0L
}

/**
 * Whether a touch or a scroll in the app underneath, at [now], sends the bubble to rest at once
 * rather than after [REST_MS]: only [idle] (no dictation, no result, no error showing), with no
 * finger on it, and not within [OWN_TOUCH_MS] of a finger leaving it, which the same gesture's
 * own events would otherwise do.
 */
internal fun restsEarly(idle: Boolean, touching: Boolean, now: Long, releasedAt: Long) =
    idle && !touching && now - releasedAt >= OWN_TOUCH_MS

internal const val OWN_TOUCH_MS = 500L

/**
 * The bubble in its own window, wired to [Dictation] and [Prefs]: results time out, haptics mark
 * changes, 3 s untouched in Idle bring the idle look. Its place comes from [Geometry] only.
 * [onGone] runs once it has faded out for good.
 */
@Composable
internal fun LiveBubble(ui: OverlayUi, onGone: () -> Unit) {
    val state by Dictation.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val look = state.look()
    val view = LocalView.current
    var last by remember { mutableStateOf(look) }
    // Results are brief: the check 0.7 s, Copied 1 s. An error shows its colour 1.5 s, then the
    // bubble's own look (with a retry badge while its audio waits); its label stays 4 s.
    var quiet by remember { mutableStateOf(false) }
    val inserted = stringResource(R.string.overlay_inserted)
    val copied = stringResource(R.string.overlay_copied)
    LaunchedEffect(look) {
        if (look::class != last::class) haptic(view, look)
        last = look
        quiet = false
        ui.labelled.value = look is Look.Failed
        when (look) {
            Look.Done, Look.Copied -> {
                @Suppress("DEPRECATION") view.announceForAccessibility(if (look == Look.Done) inserted else copied)
                delay(if (look == Look.Done) 700L else 1000L)
                Dictation.acknowledge()
            }
            is Look.Failed -> {
                delay(1500L)
                quiet = true
                delay(2500L)
                ui.labelled.value = false
                // A waiting error stays, as the bubble's own look with a retry badge, until a tap
                // or ✕.
                Dictation.acknowledge()
            }
            else -> Unit
        }
    }
    // A quiet error looks, and rests, like Idle.
    val shown = if (look is Look.Failed && quiet) Look.Idle else look
    val badge = look is Look.Failed && quiet && look.waits
    // 3 s untouched in Idle, counted again after every touch and every dictation; sooner when
    // the user touches or scrolls the app underneath ([Overlay.elsewhere]).
    val idle = shown is Look.Idle && !ui.touching.value
    LaunchedEffect(idle) {
        ui.idle.value = idle
        ui.rest.value = false
        if (idle) {
            delay(REST_MS)
            ui.rest.value = true
        }
    }
    val atRest = idle && ui.rest.value
    // One spring from full to the idle look and back. Read only while drawing: a frame of the
    // motion redraws the bubble without recomposing it.
    val progress = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(atRest) {
        settled = false
        progress.animateTo(if (atRest) 1f else 0f, RestSpec)
        settled = true
    }
    val docked = atRest && settled && settings.whenIdle != WhenIdle.FULL
    LaunchedEffect(docked) { ui.docked.value = docked }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = !(ui.locked.value || ui.away.value || ui.closing.value)
    LaunchedEffect(visible.isIdle, visible.currentState, ui.closing.value) {
        if (ui.closing.value && visible.isIdle && !visible.currentState) onGone()
    }
    val density = LocalDensity.current
    val side = Geometry.windowSide(density.density)
    val sizePx = with(density) { settings.bubbleSize.dp.toPx() }
    val layout = { p: Float ->
        Geometry.layout(ui.screen.value, ui.anchor.value, settings.bubbleSize, settings.whenIdle, p,
            settings.idleOpacity / 100f, ui.drag.value)
    }
    val motion = MaterialTheme.motionScheme
    // It shows and hides from its centre, full or at rest.
    val origin = placement(layout(if (atRest) 1f else 0f), sizePx, settings.whenIdle, 0f).centre
        .let { TransformOrigin(it.x / side, it.y / side) }
    Box(Modifier.size(with(density) { side.toDp() })) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f, transformOrigin = origin),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f, transformOrigin = origin),
        ) {
            BubbleBody(
                shown, settings.shape, settings.bubbleSize.dp,
                place = { placement(layout(progress.value), sizePx, settings.whenIdle, progress.value) },
                badge = badge,
                pressed = ui.pressed.value,
            ) { color, modifier -> LiveWave(Dictation.level, color, modifier) }
        }
    }
}

/**
 * Where the bubble is drawn in its window this frame, and how: [round] morphs its shape towards
 * a circle, [glyphs] fades what it shows. Read while drawing, so a motion redraws the bubble
 * without recomposing it.
 */
internal class Placement(
    val centre: Offset,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val glyphs: Float = 1f,
    val round: Float = 0f,
)

/**
 * [l]'s bubble, drawn against its window's rounded origin as Overlay places it, at [p] between
 * full and at rest. The dot is a circle: a 12 dp cookie reads as a blur. The glyph is gone
 * halfway there, where the bubble gets too small for it.
 */
internal fun placement(l: BubbleLayout, sizePx: Float, whenIdle: WhenIdle, p: Float): Placement {
    val dot = whenIdle == WhenIdle.DOT
    return Placement(
        centre = Offset(l.bubble.centerX - l.window.left.roundToInt(), l.bubble.centerY - l.window.top.roundToInt()),
        scale = l.bubble.width / sizePx,
        alpha = l.alpha,
        glyphs = if (dot) (1f - 2f * p).coerceIn(0f, 1f) else 1f,
        round = if (dot) p.coerceIn(0f, 1f) else 0f,
    )
}

/** The error's label in its own window beside the bubble, on the side facing the screen. */
@Composable
internal fun LivePill(ui: OverlayUi) {
    val state by Dictation.state.collectAsState()
    val context = LocalContext.current
    val failed = (state.look() as? Look.Failed)?.takeIf {
        ui.labelled.value && !ui.dragging.value && !ui.moving.value &&
            !ui.locked.value && !ui.away.value && !ui.closing.value
    }
    val right = ui.anchor.value.edge == Edge.RIGHT
    Box(Modifier.fillMaxSize(), contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
        ErrorPill(failed, towardsStart = right) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** The ✕ target in its window: grows in, shrinks out; [onGone] once it has. */
@Composable
internal fun LiveTarget(shown: androidx.compose.runtime.State<Boolean>, armed: androidx.compose.runtime.State<Boolean>, onGone: () -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = shown.value
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (!shown.value && visible.isIdle && !visible.currentState) onGone()
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f),
        exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f),
    ) { DismissTarget(armed.value) }
}

/** A tap on the bubble, whatever it shows. */
internal fun tapBubble(context: Context) {
    when (Dictation.state.value.look()) {
        // Failed: retries the kept audio, or starts over when there is none.
        Look.Idle, Look.Listening, is Look.Failed -> Dictation.toggle(context)
        Look.Finishing, Look.Done, Look.Copied -> Unit
    }
}

private fun haptic(view: View, look: Look) {
    val effect = when (look) {
        Look.Listening -> HapticFeedbackConstants.GESTURE_START
        Look.Finishing -> HapticFeedbackConstants.GESTURE_END
        Look.Done, Look.Copied -> HapticFeedbackConstants.CONFIRM
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

internal const val REST_MS = 3000L

/**
 * Between full (0) and at rest (1): the expressive scheme's spatial spring, moving size, dock,
 * shape and opacity as one. It overshoots by about 2 %, which [Geometry] keeps on screen. It
 * runs until under a pixel from the end: the default stop, at 1 %, snapped a 96 dp bubble by
 * 2 px in its last frame.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val RestSpec: FiniteAnimationSpec<Float> =
    (MotionScheme.expressive().defaultSpatialSpec<Float>() as SpringSpec<Float>)
        .let { spring(it.dampingRatio, it.stiffness, visibilityThreshold = 0.002f) }

/** Finds the bubble in tests. */
internal const val BUBBLE_TAG = "bubble"

/**
 * The bubble, the only thing Burble draws over other apps. It keeps its shape and size in
 * every state, so nothing it covers changes while you talk: the wave, the loader and the check
 * all play inside it, cross-fading on the motion scheme's springs. Drawn where [place] says in
 * its parent; [pressed] shrinks it a little.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BubbleBody(
    look: Look,
    shape: BubbleShape,
    side: Dp,
    place: () -> Placement,
    pressed: Boolean = false,
    /** A failed dictation's audio waits: a small retry badge on the bubble's own look. */
    badge: Boolean = false,
    wave: WaveSlot,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (look) {
        Look.Done, Look.Copied -> colors.primary to colors.onPrimary
        // Tertiary is the wallpaper's accent; the Google blue fallback's is an accidental green.
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
    val press = animateFloatAsState(if (pressed) 0.9f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    val outline = shape.polygon().toShape()
    val keyShadow = remember(outline) { DropShadowPainter(outline, KeyShadow) }
    val ambientShadow = remember(outline) { DropShadowPainter(outline, AmbientShadow) }
    // Towards the dot: the shape morphs into a circle, its shadow fades into a circle's.
    val rounding = remember(shape) { Rounding(shape.polygon()) }
    val roundKeyShadow = remember { DropShadowPainter(CircleShape, KeyShadow) }
    val roundAmbientShadow = remember { DropShadowPainter(CircleShape, AmbientShadow) }
    val density = LocalDensity.current
    val half = with(density) { side.toPx() } / 2
    // The bubble's place, size and opacity; the shape's mask is inside, so the badge isn't cut.
    Box(
        Modifier
            .testTag(BUBBLE_TAG)
            .size(side)
            .graphicsLayer {
                val at = place()
                translationX = at.centre.x - half
                translationY = at.centre.y - half
                scaleX = at.scale * press.value
                scaleY = at.scale * press.value
                this.alpha = at.alpha
                // Offscreen, the fade would crop the shadow to the bubble's square.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
    Box(
        Modifier
            .fillMaxSize()
                // M3's level-2 elevation, two layers, following the real outline. A blurred copy
                // of the shape, not shadowElevation: Skia's shadow for a concave path hung the
                // render thread on SoftBurst at every launch. Only outside the shape, so a faded
                // bubble shows what is under it, not its shadow.
                .drawWithCache {
                    val inside = Path().apply {
                        addOutline(outline.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache))
                    }
                    onDrawBehind {
                        val round = place().round
                        clipPath(if (round == 0f) inside else rounding.path(round, size), ClipOp.Difference) {
                            if (round < 1f) {
                                with(keyShadow) { draw(this@onDrawBehind.size, alpha = 1f - round) }
                                with(ambientShadow) { draw(this@onDrawBehind.size, alpha = 1f - round) }
                            }
                            if (round > 0f) {
                                with(roundKeyShadow) { draw(this@onDrawBehind.size, alpha = round) }
                                with(roundAmbientShadow) { draw(this@onDrawBehind.size, alpha = round) }
                            }
                        }
                    }
                }
                // Drawn offscreen, then everything outside the shape cleared: a mask, not a clip.
                // Android clips children to a non-convex outline only from 13 on, and a child
                // fading in at partial alpha showed its square past the shape.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val square = Rect(Offset.Zero, size)
                    val outside = Path().apply {
                        addRect(square)
                        addOutline(outline.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache))
                        fillType = PathFillType.EvenOdd
                    }
                    val morphing = Path().apply { fillType = PathFillType.EvenOdd }
                    onDrawWithContent {
                        drawRect(bg)
                        drawContent()
                        val round = place().round
                        val mask = if (round == 0f) outside else morphing.apply {
                            rewind()
                            addRect(square)
                            addPath(rounding.path(round, size))
                        }
                        drawPath(mask, Color.Black, blendMode = BlendMode.Clear)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = look,
                // A 12 dp dot has no room for the glyph. Faded in place: a layer that turned
                // offscreen as it left full opacity dropped the glyph for a frame.
                modifier = Modifier.graphicsLayer {
                    this.alpha = place().glyphs
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
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
                    Look.Copied -> Icon(Icons.Rounded.Check, stringResource(R.string.overlay_copied), glyph, fg)
                    is Look.Failed -> Icon(if (s.waits) Icons.Rounded.Refresh else s.kind.icon(),
                        stringResource(s.kind.message()), glyph, fg)
                } }
            }
        }
        AnimatedVisibility(
            badge,
            Modifier.align(Alignment.TopEnd),
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(fadeInSpec),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(fadeOutSpec),
        ) {
            Box(
                Modifier.size(side * 0.38f).background(colors.errorContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(side * 0.26f))
            }
        }
    }
}

/** The bubble at rest in its parent, centred on [centre]: the settings' shape tiles. */
@Composable
internal fun BubbleBody(look: Look, shape: BubbleShape, side: Dp, centre: Offset, wave: WaveSlot) =
    BubbleBody(look, shape, side, place = { Placement(centre) }, wave = wave)

/** [polygon] part of the way to a circle, as a path for a [Size] box. */
private class Rounding(polygon: RoundedPolygon) {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private val morph = Morph(polygon, MaterialShapes.Circle)
    private val path = android.graphics.Path()
    private val matrix = android.graphics.Matrix()

    fun path(round: Float, size: Size): Path {
        path.rewind()
        morph.toPath(round, path)
        // The shapes span the unit square.
        matrix.setScale(size.width, size.height)
        path.transform(matrix)
        return path.asComposePath()
    }
}

/**
 * The bubble as the overlay draws it, for screenshots and the settings preview: in a window's
 * square, against the edge [alignEnd] picks, placed by [Geometry] as on screen, with an error's
 * label beside it while [labelled].
 */
@Composable
internal fun OverlayContent(
    look: Look,
    shape: BubbleShape,
    side: Dp,
    alignEnd: Boolean,
    labelled: Boolean,
    rest: Boolean = false,
    whenIdle: WhenIdle = WhenIdle.SHRINK,
    idleOpacity: Float = 1f,
    wave: WaveSlot,
) {
    val density = LocalDensity.current
    val w = Geometry.windowSide(density.density)
    val sizeDp = side.value.roundToInt()
    val sizePx = with(density) { side.toPx() }
    val progress = remember { Animatable(if (rest && look == Look.Idle) 1f else 0f) }
    LaunchedEffect(rest && look == Look.Idle) { progress.animateTo(if (rest && look == Look.Idle) 1f else 0f, RestSpec) }
    val screen = Screen(w, w, Box(0f, 0f, w, w), density.density)
    val anchor = Anchor(if (alignEnd) Edge.RIGHT else Edge.LEFT, 0.5f)
    val failed = (look as? Look.Failed)?.takeIf { labelled }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) ErrorPill(failed, towardsStart = true) {}
        Box(Modifier.size(with(density) { w.toDp() })) {
            BubbleBody(
                look, shape, side,
                place = {
                    val p = progress.value
                    placement(Geometry.layout(screen, anchor, sizeDp, whenIdle, p, idleOpacity), sizePx, whenIdle, p)
                },
                wave = wave,
            )
        }
        if (!alignEnd) ErrorPill(failed, towardsStart = false) {}
    }
}

private val KeyShadow = Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), alpha = 0.3f)
private val AmbientShadow = Shadow(radius = 6.dp, spread = 2.dp, offset = DpOffset(0.dp, 2.dp), alpha = 0.15f)

/**
 * An error beside the bubble, never cut short: what happened, then what to do. Errors the app
 * must fix (the microphone, a refusal) open it when tapped.
 */
@Composable
internal fun ErrorPill(failed: Look.Failed?, towardsStart: Boolean, onOpenApp: () -> Unit) {
    // Out from behind the bubble and back, on the motion scheme's springs; no scale, no pop.
    val motion = MaterialTheme.motionScheme
    val fromBubble = { width: Int -> if (towardsStart) width / 4 else -width / 4 }
    AnimatedVisibility(
        failed != null,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec(), fromBubble),
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec(), fromBubble),
    ) {
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
    ErrorKind.UNSENT -> R.string.overlay_action_retry
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
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val bg by animateColorAsState(if (armed) colors.errorContainer else colors.surfaceContainerHighest, effects)
    val fg by animateColorAsState(if (armed) colors.onErrorContainer else colors.onSurface, effects)
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

internal fun Look.action() = when (this) {
    Look.Listening -> R.string.overlay_stop
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
