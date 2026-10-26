package dev.silver.burble.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.silver.burble.R

/**
 * An icon on a MaterialShapes container. With [done] the icon turns into a check mark and the
 * container into the tertiary colour, so a permission granted in Settings shows on return.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeBadge(
    icon: ImageVector,
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    size: Dp = 112.dp,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (done) colors.tertiaryContainer else colors.primaryContainer, label = "container")
    val content by animateColorAsState(if (done) colors.onTertiaryContainer else colors.onPrimaryContainer, label = "content")
    Box(modifier.size(size).background(container, shape.toShape()), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = done,
            transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 400f)) + fadeIn()) togetherWith fadeOut() },
            label = "badge",
        ) { granted ->
            Icon(if (granted) Icons.Rounded.Check else icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.42f))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * How pages under home come and go: in from the end and out to the start going in, the other way
 * back, on the motion scheme's spatial spring with an effects fade.
 */
fun AnimatedContentTransitionScope<Int>.pageTransition(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val spatial = MotionScheme.expressive().defaultSpatialSpec<IntOffset>()
    val effects = MotionScheme.expressive().defaultEffectsSpec<Float>()
    return (slideInHorizontally(spatial) { sign * it / 5 } + fadeIn(effects)) togetherWith
        (slideOutHorizontally(spatial) { -sign * it / 5 } + fadeOut(MotionScheme.expressive().fastEffectsSpec()))
}

/** A screen under home: a large title that collapses as it scrolls, a back arrow, a list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    // Android's predictive back: the page shrinks and moves with the swipe, then goes.
    var back by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler { events ->
        try {
            events.collect { back = it.progress; fromLeft = it.swipeEdge == BackEventCompat.EDGE_LEFT }
            onBack()
        } catch (e: CancellationException) {
            back = 0f
            throw e
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .graphicsLayer {
                val scale = 1f - 0.1f * back
                scaleX = scale
                scaleY = scale
                translationX = (if (fromLeft) 1f else -1f) * back * 8.dp.toPx()
                shape = RoundedCornerShape(32.dp * back)
                clip = back > 0f
            }
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            // Below the app bar rather than under it, so a pinned header sticks beneath it.
            Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            content = content,
        )
    }
}
