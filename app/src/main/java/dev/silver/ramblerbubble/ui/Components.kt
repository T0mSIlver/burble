package dev.silver.ramblerbubble.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.silver.ramblerbubble.BubbleShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val BubbleShape.polygon: RoundedPolygon
    get() = when (this) {
        BubbleShape.COOKIE_9 -> MaterialShapes.Cookie9Sided
        BubbleShape.SUNNY -> MaterialShapes.Sunny
        BubbleShape.CLOVER_4 -> MaterialShapes.Clover4Leaf
        BubbleShape.CIRCLE -> MaterialShapes.Circle
        BubbleShape.PILL -> MaterialShapes.Pill
        BubbleShape.SOFT_BURST -> MaterialShapes.SoftBurst
    }

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
