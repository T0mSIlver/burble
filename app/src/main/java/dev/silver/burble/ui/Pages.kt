package dev.silver.burble.ui

import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sign

private enum class Kind { PUSH, POP, PREVIEW }

/** One page giving way to another; [leftEdge] and [startY] are where a back swipe began. */
private class Motion<T>(val from: T, val to: T, val kind: Kind, val leftEdge: Boolean = true, val startY: Float = 0f)

// Material 3's predictive back for a full-screen page.
private const val MinScale = 0.9f
private val EdgeGap = 8.dp
private val MaxShiftY = 24.dp
// Android's own preview dims the page behind more in dark, where a lighter scrim wouldn't show.
private const val MaxScrimLight = 0.16f
private const val MaxScrimDark = 0.6f

/**
 * Pages over a root, the last of [stack] on screen, each keeping its state (scroll, a draft)
 * while pages above it are open. A page added slides in from the end over the one it leaves, on
 * the motion scheme's springs; one removed slides back out. Android's back gesture previews going
 * back as Material 3 specifies for a full-screen page: the page shrinks, rounds its corners to the
 * screen's and leans with the finger, over the page it returns to, dimmed. Let go past Android's
 * threshold and it fades into that page; short of it, it springs back. [onBack] removes the last
 * page; from the root, back is Android's, which previews going home.
 */
@Composable
fun <T : Any> PageStack(stack: List<T>, onBack: () -> Unit, modifier: Modifier = Modifier, page: @Composable (T) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.motionScheme
    val current by rememberUpdatedState(stack)
    val back by rememberUpdatedState(onBack)
    var shown by remember { mutableStateOf(stack.last()) }
    var before by remember { mutableStateOf(stack) }
    var motion by remember { mutableStateOf<Motion<T>?>(null) }
    // Push and pop: the slide, and the fades in and out, each 0 to 1.
    val slide = remember { Animatable(1f) }
    val enter = remember { Animatable(1f) }
    val exit = remember { Animatable(1f) }
    // The back gesture's progress, then the fade once it's committed.
    val gesture = remember { Animatable(0f) }
    val commit = remember { Animatable(0f) }
    var touchY by remember { mutableFloatStateOf(0f) }

    val top = stack.last()
    LaunchedEffect(top) {
        val was = before
        before = stack
        // A page closed starts afresh when it's opened again.
        was.filterNot { it in stack }.forEach { holder.removeState(it) }
        // A committed back gesture has shown the page already.
        if (top == shown) return@LaunchedEffect
        val m = Motion(shown, top, if (top in was) Kind.POP else Kind.PUSH)
        shown = top
        motion = m
        slide.snapTo(0f)
        enter.snapTo(0f)
        exit.snapTo(0f)
        coroutineScope {
            launch { slide.animateTo(1f, scheme.defaultSpatialSpec()) }
            launch { enter.animateTo(1f, scheme.defaultEffectsSpec()) }
            launch { exit.animateTo(1f, scheme.fastEffectsSpec()) }
        }
        if (motion === m) motion = null
    }

    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        val pages = current
        var preview: Motion<T>? = null
        try {
            events.collect { e ->
                if (preview == null) {
                    preview = Motion(pages.last(), pages[pages.size - 2], Kind.PREVIEW, e.swipeEdge != BackEventCompat.EDGE_RIGHT, e.touchY)
                    commit.snapTo(0f)
                    gesture.snapTo(0f)
                    motion = preview
                }
                touchY = e.touchY
                if (motion === preview) gesture.snapTo(e.progress)
            }
        } catch (e: CancellationException) {
            preview?.let { m -> scope.launch { gesture.animateTo(0f, scheme.defaultSpatialSpec()); if (motion === m) motion = null } }
            throw e
        }
        val m = preview
        // A page opened mid-gesture, a report or the next onboarding step, stays: the swipe was
        // about the page it covered.
        if (m != null && motion !== m) return@PredictiveBackHandler
        // The back button: back slides the page out.
        if (m == null || gesture.value < 0.01f) {
            if (m != null) motion = null
            back()
            return@PredictiveBackHandler
        }
        shown = m.to
        back()
        scope.launch { commit.animateTo(1f, scheme.slowEffectsSpec()); if (motion === m) motion = null }
    }

    val m = motion
    val layers = when {
        m == null -> listOf(shown)
        m.kind == Kind.PUSH -> listOf(m.from, m.to)
        // The page going away stays on top.
        else -> listOf(m.to, m.from)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val view = LocalView.current
    val surface = MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim
    val maxScrim = if (surface.luminance() < 0.5f) MaxScrimDark else MaxScrimLight
    Box(modifier) {
        for (k in layers) key(k) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val m = motion ?: return@graphicsLayer
                        if (m.kind != Kind.PREVIEW) {
                            val sign = if ((m.kind == Kind.PUSH) != rtl) 1f else -1f
                            if (k == m.to) {
                                translationX = sign * size.width / 5 * (1 - slide.value)
                                alpha = enter.value
                            } else {
                                translationX = -sign * size.width / 5 * slide.value
                                alpha = 1 - exit.value
                            }
                            return@graphicsLayer
                        }
                        if (k != m.from) return@graphicsLayer
                        val p = gesture.value.coerceIn(0f, 1f)
                        val c = commit.value.coerceIn(0f, 1f)
                        val scale = lerp(1f, MinScale, p)
                        val gap = EdgeGap.toPx()
                        val spaceX = ((size.width - size.width * scale) / 2 - gap).coerceAtLeast(0f)
                        val spaceY = ((size.height - size.height * scale) / 2 - gap).coerceAtLeast(0f).coerceAtMost(MaxShiftY.toPx())
                        val dy = touchY - m.startY
                        scaleX = scale * lerp(1f, MinScale, c)
                        scaleY = scaleX
                        translationX = lerp(0f, spaceX, p) * if (m.leftEdge) 1f else -1f
                        translationY = lerp(0f, spaceY, (abs(dy) / size.height).coerceAtMost(1f)) * sign(dy)
                        alpha = 1 - c
                        // The screen's own corners, or Material's extra-large shape where it has none.
                        val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
                            ?.takeIf { it > 0 }?.toFloat() ?: 28.dp.toPx()
                        shape = RoundedCornerShape(corner * p)
                        clip = true
                    }
                    .drawWithContent {
                        drawContent()
                        val m = motion
                        if (m?.kind == Kind.PREVIEW && k == m.to) {
                            drawRect(scrim, alpha = maxScrim * gesture.value.coerceIn(0f, 1f) * (1 - commit.value.coerceIn(0f, 1f)))
                        }
                    }
                    .background(surface),
            ) {
                holder.SaveableStateProvider(k) { page(k) }
            }
        }
    }
}
