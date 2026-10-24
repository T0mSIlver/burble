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
 * The listening wave, after Gboard's voice bar: a soft glow under a crest, overlaid with a dense
 * lattice of diamonds that are largest and strongest at the bottom and fade towards the crest.
 * The crest breathes slowly in silence and rises with the voice. One hue, [color], at varying
 * alpha.
 *
 * [level] and [time] are read at draw time only, so a new frame redraws without recomposing.
 */
@Composable
fun Wave(
    level: () -> Float,
    time: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    shader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    val spacing = with(LocalDensity.current) { SPACING_DP.dp.toPx() }
    if (shader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderWave(level, time, color, spacing, modifier)
    } else {
        CanvasWave(level, time, color, spacing, modifier)
    }
}

/** [Wave] driven by the live microphone level and the frame clock. */
@Composable
fun LiveWave(level: StateFlow<Float>, color: Color, modifier: Modifier = Modifier) {
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
    Wave({ smooth.floatValue }, { time.floatValue }, color, modifier)
}

/** Crest height at [x] (0..1 across), as a fraction of the band's height from the bottom. */
internal fun crest(x: Float, t: Float, level: Float): Float {
    val tau = 2f * PI.toFloat()
    val envelope = 0.35f + 0.65f * sin(PI.toFloat() * x).pow(0.8f)
    // Silence still breathes: a slow swell and a drifting ripple.
    val idle = 0.32f + 0.1f * (0.5f + 0.5f * sin(tau * 0.3f * t)) + 0.05f * sin(tau * (1.3f * x - 0.4f * t))
    val swell = 0.6f + 0.25f * sin(tau * (1.1f * x - 0.45f * t)) + 0.15f * sin(tau * (2.3f * x + 0.6f * t) + 1.7f)
    return idle + level * 0.9f * envelope * swell
}

/** How far below the crest [c] a point at height [y] sits, 0 at the crest to 1 well under it. */
internal fun depth(y: Float, c: Float): Float {
    val v = ((c - y) / 0.25f).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/** Diamond half-diagonal as a fraction of the lattice pitch: bigger at the bottom. */
internal fun dotSize(y: Float, c: Float) = depth(y, c) * (1f - 0.45f * y) * 0.4f

/** Diamond opacity by height. */
internal fun dotAlpha(y: Float) = 0.3f + 0.5f * (1f - y)

private const val SPACING_DP = 5f
private const val HAZE = 0.32f

// The lattice: rows every half pitch, alternate rows shifted by half a pitch, so neighbouring
// diamonds sit on the diagonals and the biggest ones nearly tile.
@Composable
private fun CanvasWave(level: () -> Float, time: () -> Float, color: Color, spacing: Float, modifier: Modifier) {
    val dots = remember { Path() }
    val glow = remember { Path() }
    Spacer(modifier.drawWithCache {
        // Under the crest the glow fades upwards as (1 - y)^1.5, as in the shader.
        val haze = Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            0.25f to color.copy(alpha = HAZE * 0.125f),
            0.5f to color.copy(alpha = HAZE * 0.35f),
            0.75f to color.copy(alpha = HAZE * 0.65f),
            1f to color.copy(alpha = HAZE),
        )
        val rows = (size.height / (spacing / 2f)).toInt()
        val cols = (size.width / spacing).toInt() + 2
        onDrawBehind {
            val l = level()
            val t = time()
            val w = size.width
            val h = size.height
            // Stacked layers under 100 %, 80 % and 60 % of the crest feather its edge.
            for (k in GLOW_LAYERS) {
                glow.rewind()
                glow.moveTo(0f, h)
                for (i in 0..SAMPLES) {
                    val x = i / SAMPLES.toFloat()
                    glow.lineTo(x * w, h - (crest(x, t, l) * k).coerceAtMost(1f) * h)
                }
                glow.lineTo(w, h)
                glow.close()
                drawPath(glow, haze, alpha = 1f / GLOW_LAYERS.size)
            }
            for (row in 0 until rows) {
                val cy = h - row * spacing / 2f
                val y = row * spacing / 2f / h
                val shift = if (row % 2 == 1) spacing / 2f else 0f
                dots.rewind()
                for (col in 0 until cols) {
                    val cx = col * spacing + shift - spacing / 2f
                    val r = dotSize(y, crest((cx / w).coerceIn(0f, 1f), t, l)) * spacing
                    if (r < 0.3f) continue
                    dots.moveTo(cx, cy - r)
                    dots.lineTo(cx + r, cy)
                    dots.lineTo(cx, cy + r)
                    dots.lineTo(cx - r, cy)
                    dots.close()
                }
                drawPath(dots, color, alpha = dotAlpha(y))
            }
        }
    })
}

private const val SAMPLES = 48
private val GLOW_LAYERS = floatArrayOf(1f, 0.8f, 0.6f)

// The same glow and lattice as CanvasWave, per pixel. In lattice coordinates m = (X + Y) / s,
// n = (X - Y) / s the diamonds are squares, so each pixel only tests its nearest lattice point.
private const val AGSL = """
uniform float2 size;
uniform float spacing;
uniform float time;
uniform float level;
layout(color) uniform half4 color;

const float PI = 3.14159265;

float crest(float x) {
    float tau = 2.0 * PI;
    float envelope = 0.35 + 0.65 * pow(sin(PI * x), 0.8);
    float idle = 0.32 + 0.1 * (0.5 + 0.5 * sin(tau * 0.3 * time)) + 0.05 * sin(tau * (1.3 * x - 0.4 * time));
    float swell = 0.6 + 0.25 * sin(tau * (1.1 * x - 0.45 * time)) + 0.15 * sin(tau * (2.3 * x + 0.6 * time) + 1.7);
    return idle + level * 0.9 * envelope * swell;
}

float depth(float y, float c) {
    float v = clamp((c - y) / 0.25, 0.0, 1.0);
    return v * v * (3.0 - 2.0 * v);
}

half4 main(float2 p) {
    float X = p.x + spacing / 2.0;
    float Y = size.y - p.y;
    float y = Y / size.y;
    float c = crest(clamp(p.x / size.x, 0.0, 1.0));
    float layers = step(Y, min(c, 1.0) * size.y) + step(Y, min(c * 0.8, 1.0) * size.y) +
        step(Y, min(c * 0.6, 1.0) * size.y);
    float haze = 0.32 * pow(1.0 - y, 1.5) * layers / 3.0;

    float m = floor((X + Y) / spacing + 0.5);
    float n = floor((X - Y) / spacing + 0.5);
    float cx = (m + n) * spacing / 2.0 - spacing / 2.0;
    float cY = (m - n) * spacing / 2.0;
    float dot = 0.0;
    if (cY >= 0.0) {
        float cyN = cY / size.y;
        float r = depth(cyN, crest(clamp(cx / size.x, 0.0, 1.0))) * (1.0 - 0.45 * cyN) * 0.4 * spacing;
        if (r >= 0.3) {
            float d = abs(p.x - cx) + abs(Y - cY);
            dot = clamp(r - d + 0.5, 0.0, 1.0) * (0.3 + 0.5 * (1.0 - cyN));
        }
    }
    float a = dot + haze * (1.0 - dot);
    return color * half(a);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderWave(level: () -> Float, time: () -> Float, color: Color, spacing: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(modifier.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("spacing", spacing)
        shader.setColorUniform("color", color.toArgb())
        onDrawBehind {
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("level", level())
            drawRect(brush, Offset.Zero, size)
        }
    })
}
