package dev.silver.ramblerbubble.overlay

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The listening wave: a lattice of small diamonds that fills from the bottom up to a soft
 * crest, which rises with the voice and drifts sideways. Diamonds shrink to nothing near the
 * crest. Colour runs from [start] on the left to [end] on the right.
 *
 * [level] and [time] are read at draw time only, so a new frame redraws without recomposing.
 */
@Composable
fun Wave(
    level: () -> Float,
    time: () -> Float,
    start: Color,
    end: Color,
    modifier: Modifier = Modifier,
    shader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    val spacing = with(LocalDensity.current) { SPACING_DP.dp.toPx() }
    if (shader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderWave(level, time, start, end, spacing, modifier)
    } else {
        CanvasWave(level, time, start, end, spacing, modifier)
    }
}

/** [Wave] driven by the live microphone level and the frame clock. */
@Composable
fun LiveWave(level: StateFlow<Float>, start: Color, end: Color, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    val smooth = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        var t0 = -1L
        var last = 0L
        while (true) withInfiniteAnimationFrameNanos { now ->
            if (t0 < 0) { t0 = now; last = now }
            val dt = (now - last) / 1e9f
            last = now
            time.floatValue = (now - t0) / 1e9f
            // Levels come ten times a second: rise quickly with the voice, settle slowly.
            val target = level.value
            val rate = if (target > smooth.floatValue) 18f else 4f
            smooth.floatValue += (target - smooth.floatValue) * (1f - exp(-rate * dt))
        }
    }
    Wave({ smooth.floatValue }, { time.floatValue }, start, end, modifier)
}

/** Crest height at [x] (0..1 across), as a fraction of the band's height from the bottom. */
internal fun crest(x: Float, t: Float, level: Float): Float {
    val envelope = sin(PI.toFloat() * x).pow(0.8f)
    val swell = 0.55f + 0.25f * sin(2f * PI.toFloat() * (1.3f * x - 0.35f * t)) +
        0.2f * sin(2f * PI.toFloat() * (2.7f * x + 0.5f * t) + 1.7f)
    // Silence keeps a low ripple so the band never looks frozen.
    val idle = 0.24f + 0.06f * sin(2f * PI.toFloat() * (x * 1.7f - 0.6f * t))
    return idle + level * 0.95f * envelope * swell
}

/** Diamond size, 0..1, for a dot at [y] (0 at the bottom) under a crest at [c]. */
internal fun dotScale(y: Float, c: Float): Float {
    val edge = 0.3f
    val v = ((c - y) / edge).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

private const val SPACING_DP = 5f
private const val DOT = 0.76f

@Composable
private fun CanvasWave(
    level: () -> Float, time: () -> Float, start: Color, end: Color, spacing: Float, modifier: Modifier,
) {
    val path = remember { Path() }
    Spacer(modifier.drawWithCache {
        val brush = Brush.horizontalGradient(listOf(start, end))
        val cols = (size.width / spacing).toInt()
        val rows = (size.height / spacing).toInt()
        val ox = (size.width - (cols - 1) * spacing) / 2f
        onDrawBehind {
            val l = level()
            val t = time()
            path.rewind()
            for (row in 0 until rows) {
                val cy = size.height - spacing / 2f - row * spacing
                val y = 1f - cy / size.height
                val shift = if (row % 2 == 1) spacing / 2f else 0f
                for (col in 0 until cols) {
                    val cx = ox + col * spacing + shift
                    if (cx > size.width) continue
                    val r = dotScale(y, crest(cx / size.width, t, l)) * DOT * spacing / 2f
                    if (r < 0.3f) continue
                    path.moveTo(cx, cy - r)
                    path.lineTo(cx + r, cy)
                    path.lineTo(cx, cy + r)
                    path.lineTo(cx - r, cy)
                    path.close()
                }
            }
            drawPath(path, brush)
        }
    })
}

// The same lattice and crest as CanvasWave, per pixel. Each pixel tests only its own cell's
// diamond: diamonds never reach past their cell.
private const val AGSL = """
uniform float2 size;
uniform float spacing;
uniform float time;
uniform float level;
layout(color) uniform half4 startColor;
layout(color) uniform half4 endColor;

const float PI = 3.14159265;

float crest(float x) {
    float envelope = pow(sin(PI * x), 0.8);
    float swell = 0.55 + 0.25 * sin(2.0 * PI * (1.3 * x - 0.35 * time)) +
        0.2 * sin(2.0 * PI * (2.7 * x + 0.5 * time) + 1.7);
    float idle = 0.24 + 0.06 * sin(2.0 * PI * (x * 1.7 - 0.6 * time));
    return idle + level * 0.95 * envelope * swell;
}

half4 main(float2 p) {
    float cols = floor(size.x / spacing);
    float rows = floor(size.y / spacing);
    float ox = (size.x - (cols - 1.0) * spacing) / 2.0;
    float row = floor((size.y - p.y) / spacing);
    if (row >= rows) return half4(0);
    float cy = size.y - spacing / 2.0 - row * spacing;
    float shift = mod(row, 2.0) * spacing / 2.0;
    float col = floor((p.x - ox - shift) / spacing + 0.5);
    if (col < 0.0 || col >= cols) return half4(0);
    float cx = ox + col * spacing + shift;
    if (cx > size.x) return half4(0);
    float y = 1.0 - cy / size.y;
    float v = clamp((crest(cx / size.x) - y) / 0.3, 0.0, 1.0);
    float r = v * v * (3.0 - 2.0 * v) * 0.76 * spacing / 2.0;
    if (r < 0.3) return half4(0);
    float d = abs(p.x - cx) + abs(p.y - cy);
    float a = clamp(r - d + 0.5, 0.0, 1.0);
    return mix(startColor, endColor, half(clamp(p.x / size.x, 0.0, 1.0))) * half(a);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderWave(
    level: () -> Float, time: () -> Float, start: Color, end: Color, spacing: Float, modifier: Modifier,
) {
    val shader = remember { RuntimeShader(AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(modifier.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("spacing", spacing)
        shader.setColorUniform("startColor", start.toArgb())
        shader.setColorUniform("endColor", end.toArgb())
        onDrawBehind {
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("level", level())
            drawRect(brush, Offset.Zero, size)
        }
    })
}
