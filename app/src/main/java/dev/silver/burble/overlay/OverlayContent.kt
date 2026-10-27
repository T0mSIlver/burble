package dev.silver.burble.overlay

import androidx.compose.ui.graphics.compositeOver
import android.content.Intent
import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.exp
import dev.silver.burble.Haptic
import dev.silver.burble.Haptics
import android.view.View
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.MutableTransitionState
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
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
import dev.silver.burble.ui.theme.Motion
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
    /** Out of a keyboard's mic key or back into it ([Overlay.emergeFrom]). */
    val flight = mutableStateOf<Flight?>(null)
    /** The next rest comes at once rather than after [REST_MS]: no keyboard came back to go into. */
    var restNow = false
    /** The keyboard's mic key as its hello last said, as a flight at the key, all but transparent. */
    val keyCopy = mutableStateOf<Flight?>(null)
    /** The key's copy draws the listening light once, unseen, to have its first frame done. */
    val warming = mutableStateOf(false)
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
 * changes, 2 s untouched in Idle bring the idle look. Its place comes from [Geometry] only.
 * [onGone] runs once it has faded out for good.
 */
@Composable
internal fun LiveBubble(ui: OverlayUi, onGone: () -> Unit) {
    val state by Dictation.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val look = state.look()
    val view = LocalView.current
    var last by remember { mutableStateOf(look) }
    // Inserted and Copied are the idle look at once, with a haptic and TalkBack saying which. An
    // error's words stay beside the bubble: until it is resolved while its audio waits, 4 s when
    // it doesn't, 2 s for nothing heard.
    val inserted = stringResource(R.string.overlay_inserted)
    val copied = stringResource(R.string.overlay_copied)
    LaunchedEffect(look) {
        if (look::class != last::class) haptic(view, look)
        last = look
        ui.labelled.value = look is Look.Failed
        when (look) {
            Look.Done, Look.Copied -> {
                @Suppress("DEPRECATION") view.announceForAccessibility(if (look == Look.Done) inserted else copied)
                // The bubble already shows the idle look; the state stays Done a moment, so what
                // watches it (onboarding's Try it) sees the words land.
                delay(DONE_HOLD_MS)
                Dictation.acknowledge()
            }
            is Look.Failed -> if (!look.waits) {
                delay(if (look.kind == ErrorKind.NOTHING_HEARD) NOTHING_HEARD_WORDS_MS else ERROR_WORDS_MS)
                ui.labelled.value = false
                Dictation.acknowledge()
            }
            else -> Unit
        }
    }
    val shown = look.shown()
    // 2 s untouched in Idle, counted again after every touch and every dictation, and only once
    // an error's words have gone, so they stay beside the bubble; sooner when the user touches or
    // scrolls the app underneath ([Overlay.elsewhere]).
    // Nor while a keyboard's key started it: it waits full size for the keyboard to come back.
    val idle = shown is Look.Idle && !ui.touching.value && !ui.labelled.value && ui.flight.value == null
    LaunchedEffect(idle) {
        ui.idle.value = idle
        ui.rest.value = false
        if (idle) {
            if (!ui.restNow) delay(REST_MS)
            ui.restNow = false
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
    // Out of a keyboard's mic key: the flight is its entrance, and it starts full size.
    val flight = ui.flight.value
    LaunchedEffect(flight) { if (flight != null) progress.snapTo(0f) }
    // It shows and hides from its centre, full or at rest.
    val origin = placement(layout(if (atRest) 1f else 0f), sizePx, settings.whenIdle, 0f).centre
        .let { TransformOrigin(it.x / side, it.y / side) }
    Box(Modifier.size(with(density) { side.toDp() })) {
        AnimatedVisibility(
            visible,
            enter = if (flight != null) EnterTransition.None
                else fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f, transformOrigin = origin),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f, transformOrigin = origin),
        ) {
            // On its way back into the keyboard's key, its light goes out with no mic showing.
            val drawn = if (flight?.returning == true && flight.handoverAt == 0L && shown == Look.Idle) Look.Finishing else shown
            BubbleBody(
                drawn, settings.shape, settings.bubbleSize.dp,
                place = {
                    val at = placement(layout(progress.value), sizePx, settings.whenIdle, progress.value)
                    ui.flight.value?.let { it.place(at, sizePx, shown = !it.inWindow) } ?: at
                },
                pressed = ui.pressed.value,
            ) { color, modifier ->
                LiveWave(Dictation.level, color, modifier, finishing = { Dictation.state.value == Dictation.State.Finishing })
            }
        }
    }
}

/**
 * The bubble on its way out of a keyboard's mic key and back, in the flight's own window, which
 * covers the screen: as [LiveBubble] draws it, placed by [OverlayUi.flight]. Kept composed and
 * unseen while a keyboard that hands off is up, so its first frame is the next one.
 */
@Composable
internal fun FlightBubble(ui: OverlayUi) {
    val state by Dictation.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val flight = ui.flight.value
    val shown = state.look().shown()
    // Out of the key it listens from the first frame, though the dictation starts a frame later;
    // back into it, its light goes out with no mic. Never the idle mic.
    val drawn = when {
        flight == null && ui.warming.value -> Look.Listening
        flight == null || shown != Look.Idle -> shown
        flight.returning -> Look.Finishing
        else -> Look.Listening
    }
    val sizePx = with(LocalDensity.current) { settings.bubbleSize.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        BubbleBody(
            drawn, settings.shape, settings.bubbleSize.dp,
            place = {
                // No flight: the key's copy, drawn where the key is but unseen, so the first
                // frame of a flight only changes its opacity.
                val f = ui.flight.value ?: ui.keyCopy.value
                if (f == null) Placement(Offset.Zero, alpha = 0f)
                else {
                    val (x, y) = f.centre()
                    f.place(Placement(Offset.Zero), sizePx, Offset(x, y), shown = f.inFlightWindow)
                }
            },
        ) { color, modifier ->
            LiveWave(Dictation.level, color, modifier, finishing = { Dictation.state.value == Dictation.State.Finishing })
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
    /** Its height over its width, stretched to a keyboard key's ([Flight]). */
    val squash: Float = 1f,
    /** How far its shape has morphed into a keyboard key's disc, a circle. */
    val key: Float = 0f,
    /** How far it has taken the key's [fill] and lost its shadow. */
    val skin: Float = 0f,
    val fill: Color = Color.Unspecified,
    /** The key's glyph, drawn unscaled at its centre, at [iconAlpha]. */
    val icon: ImageBitmap? = null,
    val iconAlpha: Float = 0f,
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
        ErrorPanel(failed, towardsStart = right) {
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
        // Failed: retries the kept audio, or starts over when there is none. Inserted and Copied
        // look idle, so they start listening too.
        Look.Idle, Look.Listening, is Look.Failed, Look.Done, Look.Copied -> Dictation.toggle(context)
        Look.Finishing -> Unit
    }
}

private fun haptic(view: View, look: Look) {
    val event = when (look) {
        Look.Listening -> Haptic.START
        Look.Finishing -> Haptic.STOP
        Look.Done -> Haptic.INSERTED
        Look.Copied -> Haptic.COPIED
        is Look.Failed -> if (look.kind == ErrorKind.NOTHING_HEARD) Haptic.NOTHING_HEARD else Haptic.ERROR
        Look.Idle -> return
    }
    Haptics.play(view, event)
}

/**
 * The wave's hue source: the primary, as Gboard takes its glow from the system accent. [Wave]
 * turns it into the glow's own lighter, more saturated colour.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ColorScheme.waveColor(wallpaper: Boolean): Color = primary

/** Draws the wave; the live overlay passes the mic's, screenshot tests a frozen one. */
internal typealias WaveSlot = @Composable (color: Color, modifier: Modifier) -> Unit

internal const val REST_MS = 2000L

/** Between full (0) and at rest (1): [Motion.rest], moving size, dock, shape and opacity as one. */
internal val RestSpec: FiniteAnimationSpec<Float> get() = Motion.rest

/** How long Inserted and Copied stay as states behind the idle look. */
private const val DONE_HOLD_MS = 200L

/** An error's words, when its audio doesn't wait for a retry. */
private const val ERROR_WORDS_MS = 4000L
private const val NOTHING_HEARD_WORDS_MS = 2000L

/**
 * What the bubble draws for [this]: Inserted, Copied and an error without kept audio show the
 * idle look (their words, haptic and TalkBack say the rest), and rest like it.
 */
internal fun Look.shown(): Look = when (this) {
    Look.Done, Look.Copied -> Look.Idle
    is Look.Failed -> if (waits) this else Look.Idle
    else -> this
}

/** The idle mic: drawn for the bubble (mic A); bubble_mic_b is the other drawing. */
private val MicGlyph = R.drawable.bubble_mic_a

/** The bubble's glyphs: 24 dp at the default 64 dp size. */
private const val GLYPH = 24f / 64f

/** The sweep fades in over this, ms, once it starts. */
private const val SWEEP_IN_MS = 120f

/** Reduced motion: finishing is the settled light, dimmed to this, with no sweep. */
private const val REDUCED_FINISHING = 0.7f

/** Finds the bubble in tests. */
internal const val BUBBLE_TAG = "bubble"

/**
 * The bubble, the only thing Burble draws over other apps. It keeps its shape, size and tonal
 * background in every state, so nothing it covers changes while you talk; what it shows
 * cross-fades inside: the mic at idle, the wave while listening, the same light settled while
 * finishing (Gboard's sweep from [SWEEP_AFTER_MS] on), and the refresh arrow while an error's
 * audio waits. Drawn where [place] says in its parent; [pressed] shrinks it a little.
 * [finishingMs] freezes finishing's clock for screenshots.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BubbleBody(
    look: Look,
    shape: BubbleShape,
    side: Dp,
    place: () -> Placement,
    pressed: Boolean = false,
    finishingMs: Long? = null,
    reducedMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    wave: WaveSlot,
) {
    val colors = MaterialTheme.colorScheme
    val bg = colors.surfaceContainerHigh
    val accent = colors.waveColor(LocalWallpaperColors.current)
    val ink = remember(accent, bg) { waveInk(accent, bg.luminance() < 0.5f) }
    // Each layer cross-fades on the fast effects spring (settled in about 110 ms); back to idle
    // from finishing (inserted, copied, nothing heard) on the default one (about 165 ms, Gboard's
    // result fade).
    val previous = remember { mutableStateOf(look) }
    val from = remember(look::class) { previous.value }
    SideEffect { previous.value = look }
    val toIdle = from == Look.Finishing && look == Look.Idle
    val fade = if (toIdle) Motion.effects<Float>() else Motion.fastEffects()
    val lit = look == Look.Listening || look == Look.Finishing
    val mic by animateFloatAsState(if (look == Look.Idle) 1f else 0f, fade)
    val light by animateFloatAsState(
        when {
            look == Look.Finishing && reducedMotion -> REDUCED_FINISHING
            lit -> 1f
            else -> 0f
        },
        fade,
    )
    val retry by animateFloatAsState(if (look is Look.Failed) 1f else 0f, fade)
    // Finishing's clock, read while drawing: the sweep starts 700 ms in, so a quick finish never
    // shows it.
    val finishing = remember { mutableFloatStateOf(0f) }
    // Leaving finishing, the sweep holds where it was while the light fades; back to 0 once the
    // light is gone or listening starts again, so no later light carries the last one's sweep.
    SideEffect {
        if (look == Look.Listening || (look != Look.Finishing && light == 0f)) finishing.floatValue = 0f
    }
    LaunchedEffect(look == Look.Finishing, finishingMs, reducedMotion) {
        if (look != Look.Finishing || reducedMotion) return@LaunchedEffect
        if (finishingMs != null) { finishing.floatValue = finishingMs.toFloat(); return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) withInfiniteAnimationFrameMillis { finishing.floatValue = (it - start).toFloat() }
    }
    val sweeping = (look == Look.Finishing || (light > 0f && look != Look.Listening)) && !reducedMotion
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
    val glyph = Modifier.size(side * GLYPH)
    // The bubble's place, size and opacity.
    Box(
        Modifier
            .testTag(BUBBLE_TAG)
            .size(side)
            .graphicsLayer {
                val at = place()
                translationX = at.centre.x - half
                translationY = at.centre.y - half
                scaleX = at.scale * press.value
                scaleY = at.scale * at.squash * press.value
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
                        val at = place()
                        // Out of a keyboard's mic key, a disc, the shape morphs from a circle.
                        val round = maxOf(at.round, at.key)
                        // A key casts no shadow: the bubble's comes as it takes its own colour.
                        val lift = 1f - at.skin
                        if (lift <= 0f) return@onDrawBehind
                        val clip = if (round == 0f) inside else rounding.path(round, size)
                        clipPath(clip, ClipOp.Difference) {
                            if (round < 1f) {
                                with(keyShadow) { draw(this@onDrawBehind.size, alpha = (1f - round) * lift) }
                                with(ambientShadow) { draw(this@onDrawBehind.size, alpha = (1f - round) * lift) }
                            }
                            if (round > 0f) {
                                with(roundKeyShadow) { draw(this@onDrawBehind.size, alpha = round * lift) }
                                with(roundAmbientShadow) { draw(this@onDrawBehind.size, alpha = round * lift) }
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
                        val at = place()
                        drawRect(if (at.skin > 0f && at.fill.isSpecified) lerp(bg, at.fill, at.skin) else bg)
                        drawContent()
                        // The key's own glyph, at its own size: the bubble around it is scaled.
                        val icon = at.icon
                        if (icon != null && at.iconAlpha > 0f) {
                            val sx = at.scale * press.value
                            scale(1f / sx, 1f / (sx * at.squash)) {
                                drawImage(icon, Offset((size.width - icon.width) / 2, (size.height - icon.height) / 2), alpha = at.iconAlpha)
                            }
                        }
                        val round = maxOf(at.round, at.key)
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
            // A 12 dp dot has no room for the glyph. Faded in place: a layer that turned
            // offscreen as it left full opacity dropped the glyph for a frame.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    this.alpha = place().glyphs
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                contentAlignment = Alignment.Center,
            ) {
                // The wave stays composed from listening through finishing, so its light settles
                // rather than restarting; the sweep masks it, showing the bubble's background,
                // which is the wave's own surface.
                if (lit || light > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = light
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                if (sweeping) sweepMask(finishing.floatValue, density.density)?.let {
                                    drawRect(it, blendMode = BlendMode.DstIn)
                                }
                            },
                    ) { wave(accent, Modifier.fillMaxSize()) }
                }
                if (mic > 0f) {
                    Icon(painterResource(MicGlyph), stringResource(R.string.overlay_start),
                        glyph.graphicsLayer { alpha = mic }, ink)
                }
                if (retry > 0f) {
                    val failed = look as? Look.Failed
                    Icon(painterResource(R.drawable.bubble_refresh),
                        failed?.let { stringResource(it.kind.panel()) } ?: stringResource(R.string.overlay_retry),
                        glyph.graphicsLayer { alpha = retry }, ink)
                }
            }
        }
    }
}

/** Finishing's sweep waits this long: a quick finish shows only the settled light. */
internal const val SWEEP_AFTER_MS = 700f

/**
 * Gboard's processing sweep (fkm, research/bubble-states-research.md) as a mask over the wave, at
 * [ms] into finishing: a half-width window of the glow, feathered, that narrows to the middle
 * half, swings to the right half, then left and right on a 3 s loop. Widths are the silent
 * glow's (its 15 dp edge and half its 13 dp humps on each side); null before it starts.
 */
internal fun DrawScope.sweepMask(ms: Float, dp: Float): Brush? {
    val t = ms - SWEEP_AFTER_MS
    if (t < 0f) return null
    val strength = Motion.emphasizedDecelerate.transform((t / SWEEP_IN_MS).coerceIn(0f, 1f))
    val (l, r) = sweepWindow(t)
    val gw = 2f * (15f + 6.5f) * dp
    val feather = 0.12f * gw
    val cx = size.width / 2
    val n = 48
    val stops = Array(n + 1) { i ->
        val x = size.width * i / n
        val u = (x - cx) / gw + 0.5f
        val d = (if (u < l) l - u else if (u > r) u - r else -minOf(u - l, r - u)) * gw
        val a = 1f / (1f + exp(2f * (d - feather) / feather))
        i / n.toFloat() to Color.Black.copy(alpha = 1f - strength * (1f - a))
    }
    return Brush.horizontalGradient(*stops)
}

/** The sweep window's left and right edges, as shares of the glow's width, [ms] after it starts. */
internal fun sweepWindow(ms: Float): Pair<Float, Float> {
    var t = ms
    while (t >= SWEEP_T[4]) t -= 3000f
    var i = 1
    while (t > SWEEP_T[i]) i++
    val k = SWEEP_EASE[i - 1].transform(((t - SWEEP_T[i - 1]) / (SWEEP_T[i] - SWEEP_T[i - 1])).coerceIn(0f, 1f))
    val left = SWEEP_L[i - 1] + (SWEEP_L[i] - SWEEP_L[i - 1]) * k
    return left to left + if (i == 1) 1f - 0.5f * k else 0.5f
}

private val SWEEP_T = floatArrayOf(0f, 166f, 1166f, 2166f, 4166f)
private val SWEEP_L = floatArrayOf(0f, 0.25f, 0.52f, -0.02f, 0.52f)
// Gboard's: emphasized, then (0.33, 0, 0.83, 1); its first, linear narrowing on M3's standard curve.
private val SWEEP_EASE = arrayOf(Motion.standard, Motion.standard, Motion.standard, CubicBezierEasing(0.33f, 0f, 0.83f, 1f))

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
    finishingMs: Long? = null,
    reducedMotion: Boolean = false,
    wave: WaveSlot,
) {
    val density = LocalDensity.current
    val w = Geometry.windowSide(density.density)
    val shown = look.shown()
    val sizeDp = side.value.roundToInt()
    val sizePx = with(density) { side.toPx() }
    val progress = remember { Animatable(if (rest && shown == Look.Idle) 1f else 0f) }
    LaunchedEffect(rest && shown == Look.Idle) { progress.animateTo(if (rest && shown == Look.Idle) 1f else 0f, RestSpec) }
    val screen = Screen(w, w, Box(0f, 0f, w, w), density.density)
    val anchor = Anchor(if (alignEnd) Edge.RIGHT else Edge.LEFT, 0.5f)
    val failed = (look as? Look.Failed)?.takeIf { labelled }
    // The panel 8 dp from the full-size cookie, as Geometry places its window on screen, not from
    // this window's edge.
    val full = Geometry.layout(screen, anchor, sizeDp, whenIdle, 0f, idleOpacity).bubble
    val gap = Geometry.PILL_GAP_DP * density.density
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) Box(Modifier.offset { IntOffset((full.left - gap).roundToInt(), 0) }) {
            ErrorPanel(failed, towardsStart = true) {}
        }
        Box(Modifier.size(with(density) { w.toDp() })) {
            BubbleBody(
                shown, shape, side,
                place = {
                    val p = progress.value
                    placement(Geometry.layout(screen, anchor, sizeDp, whenIdle, p, idleOpacity), sizePx, whenIdle, p)
                },
                finishingMs = finishingMs,
                reducedMotion = reducedMotion,
                wave = wave,
            )
        }
        if (!alignEnd) Box(Modifier.offset { IntOffset(-(w - full.right - gap).roundToInt(), 0) }) {
            ErrorPanel(failed, towardsStart = false) {}
        }
    }
}

private val KeyShadow = Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), alpha = 0.3f)
private val AmbientShadow = Shadow(radius = 6.dp, spread = 2.dp, offset = DpOffset(0.dp, 2.dp), alpha = 0.15f)

/**
 * An error's words beside the bubble, 8 dp from it on the side facing the screen: a separate
 * panel in the bubble's tonal colour, 12 dp corners and padding, 14 sp, wrapped into balanced
 * lines and never cut short. It comes and goes in 120 ms, in place; TalkBack reads it. Errors
 * the app must fix (the microphone, a refusal) open it when tapped.
 */
@Composable
internal fun ErrorPanel(failed: Look.Failed?, towardsStart: Boolean, onOpenApp: () -> Unit) {
    AnimatedVisibility(
        failed != null,
        enter = fadeIn(Motion.fastEffects()),
        exit = fadeOut(Motion.fastEffects()),
    ) {
        // Kept through the exit, when [failed] is already null.
        val shown = remember { mutableStateOf(failed) }
        if (failed != null) shown.value = failed
        val f = shown.value ?: return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        val opens = f.kind == ErrorKind.NO_MIC || f.kind == ErrorKind.REJECTED
        val panel = RoundedCornerShape(12.dp)
        val keyShadow = remember { DropShadowPainter(panel, KeyShadow) }
        val ambientShadow = remember { DropShadowPainter(panel, AmbientShadow) }
        Box(
            Modifier
                .widthIn(max = Geometry.PILL_WIDTH_DP.dp)
                // The bubble's elevation, drawn only outside the panel: an elevation shadow under
                // it showed through as it faded, darkening it.
                .drawWithCache {
                    val inside = Path().apply { addOutline(panel.createOutline(size, layoutDirection, this@drawWithCache)) }
                    onDrawBehind {
                        clipPath(inside, ClipOp.Difference) {
                            with(keyShadow) { draw(this@onDrawBehind.size) }
                            with(ambientShadow) { draw(this@onDrawBehind.size) }
                        }
                    }
                }
                .clip(panel)
                .background(colors.surfaceContainerHigh)
                .then(if (opens) Modifier.clickable(onClick = onOpenApp) else Modifier)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(12.dp),
        ) {
            Text(
                stringResource(f.kind.panel()),
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                textAlign = if (towardsStart) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

/**
 * The ✕ at the bottom of the screen while the bubble is dragged: a quiet tonal circle. With the
 * bubble over it, it grows a little and deepens by the pressed state layer, as M3 marks a
 * target under a finger; never red, since hiding the bubble for now is no danger. Dropping there
 * hides the bubble for now.
 */
@Composable
internal fun DismissTarget(armed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val grow by animateFloatAsState(if (armed) 1.15f else 1f, Motion.fastSpatial())
    val deep = colors.onSurface.copy(alpha = 0.12f).compositeOver(colors.surfaceContainerHighest)
    val bg by animateColorAsState(if (armed) deep else colors.surfaceContainerHighest, Motion.effects())
    val fg by animateColorAsState(if (armed) colors.onSurface else colors.onSurfaceVariant, Motion.effects())
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

/** An error's words in its panel: what happened, then what to do. */
private fun ErrorKind.panel() = when (this) {
    ErrorKind.NO_MIC -> R.string.overlay_panel_no_mic
    ErrorKind.NOTHING_HEARD -> R.string.overlay_panel_nothing_heard
    ErrorKind.QUOTA -> R.string.overlay_panel_quota
    ErrorKind.REJECTED -> R.string.overlay_panel_rejected
    ErrorKind.NETWORK -> R.string.overlay_panel_network
    ErrorKind.OTHER -> R.string.overlay_panel_other
    ErrorKind.UNSENT -> R.string.overlay_panel_unsent
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
