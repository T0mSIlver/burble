package dev.silver.burble.overlay

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
import kotlin.math.sin

/**
 * The listening wave, after Gboard's voice bar: a soft glow rising from the bottom in drifting
 * humps, a pale haze above it, and a fine tilted lattice of diamonds lighter than the glow,
 * cut out of it where it is strong. In silence only a thin glow line stays along the bottom.
 * One hue, [color] (the theme's tertiary, like Gboard), at varying alpha; the diamonds are white.
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
    val pitch = with(LocalDensity.current) { PITCH_DP.dp.toPx() }
    if (shader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderWave(level, time, color, pitch, modifier)
    } else {
        CanvasWave(level, time, color, pitch, modifier)
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
            // As in Gboard: the humps rise within about 100 ms of the voice and sink over most of
            // a second.
            val target = level.value
            val rate = if (target > smooth.floatValue) ATTACK else RELEASE
            smooth.floatValue += (target - smooth.floatValue) * (1f - exp(-rate * dt))
        }
    }
    Wave({ smooth.floatValue }, { time.floatValue }, color, modifier)
}

private const val ATTACK = 30f
private const val RELEASE = 3f

/**
 * Glow height at [x] (0..1 across) as a fraction of the height, from the bottom: a thin line in
 * silence, three drifting humps that grow with [level].
 */
internal fun humps(x: Float, t: Float, level: Float): Float {
    fun hump(centre: Float, amp: Float): Float {
        val d = (x - centre) / 0.24f
        return amp * exp(-d * d)
    }
    val sum = hump(0.3f + 0.22f * sin(0.35f * t), 0.75f + 0.25f * sin(1.3f * t)) +
        hump(0.72f + 0.2f * sin(0.5f * t + 2f), 0.75f + 0.25f * sin(1.1f * t + 1f)) +
        hump(0.5f + 0.32f * sin(0.27f * t + 4f), 0.6f + 0.3f * sin(0.9f * t + 2f))
    return 0.07f + level * 0.95f * sum.coerceAtMost(1.2f)
}

private fun smoothstep(a: Float, b: Float, v: Float): Float {
    val k = ((v - a) / (b - a)).coerceIn(0f, 1f)
    return k * k * (3f - 2f * k)
}

/** The glow's opacity at height [y] under a hump of height [h]: strong low, soft at the top. */
internal fun glow(y: Float, h: Float) = 0.9f * (1f - smoothstep(0.2f, 1.3f, y / h))

/** The pale haze above the glow. */
internal fun haze(y: Float, h: Float) = 0.3f * (1f - smoothstep(0.8f, 3f, y / h))

/** Diamond size, 0..1 of its largest, where the glow is strong and the hump has risen. */
internal fun diamond(y: Float, h: Float) = smoothstep(0.35f, 0.8f, glow(y, h)) * smoothstep(0.16f, 0.4f, h)

/** Diamond pitch: very fine, as in Gboard. */
private const val PITCH_DP = 3.5f
private const val DIAMOND = 0.44f
private const val DIAMOND_ALPHA = 0.85f

// Canvas version for Android 12: the glow as stacked translucent layers under the hump curve,
// with a vertical fade; the diamonds row by row. Close to the shader at a glance.
@Composable
private fun CanvasWave(level: () -> Float, time: () -> Float, color: Color, pitch: Float, modifier: Modifier) {
    val dots = remember { Path() }
    val area = remember { Path() }
    Spacer(modifier.drawWithCache {
        val fade = Brush.verticalGradient(
            0f to color.copy(alpha = 0f), 0.5f to color.copy(alpha = 0.35f), 1f to color.copy(alpha = 0.9f),
        )
        val rows = (size.height / (pitch / 2f)).toInt() + 1
        val cols = (size.width / pitch).toInt() + 2
        onDrawBehind {
            val l = level()
            val t = time()
            val w = size.width
            val h = size.height
            for (k in LAYERS) {
                area.rewind()
                area.moveTo(0f, h)
                for (i in 0..SAMPLES) {
                    val x = i / SAMPLES.toFloat()
                    area.lineTo(x * w, h - (humps(x, t, l) * k).coerceAtMost(1.5f) * h)
                }
                area.lineTo(w, h)
                area.close()
                drawPath(area, fade, alpha = 1f / LAYERS.size)
            }
            for (row in 0 until rows) {
                val cy = h - row * pitch / 2f
                val y = row * pitch / 2f / h
                val shift = if (row % 2 == 1) pitch / 2f else 0f
                dots.rewind()
                for (col in 0 until cols) {
                    val cx = col * pitch + shift - pitch / 2f
                    val r = diamond(y, humps((cx / w).coerceIn(0f, 1f), t, l)) * DIAMOND * pitch
                    if (r < 0.3f) continue
                    dots.moveTo(cx, cy - r)
                    dots.lineTo(cx + r, cy)
                    dots.lineTo(cx, cy + r)
                    dots.lineTo(cx - r, cy)
                    dots.close()
                }
                drawPath(dots, Color.White, alpha = DIAMOND_ALPHA)
            }
        }
    })
}

private const val SAMPLES = 32
private val LAYERS = floatArrayOf(2.6f, 1.3f, 1f, 0.7f, 0.45f)

// The same humps, glow, haze and diamonds as the Kotlin functions above, per pixel. In lattice
// coordinates m = (X + Y) / s, n = (X - Y) / s the diamonds are squares, so each pixel only
// tests its nearest lattice point.
private const val AGSL = """
uniform float2 size;
uniform float pitch;
uniform float time;
uniform float level;
layout(color) uniform half4 color;

float hump(float x, float centre, float amp) {
    float d = (x - centre) / 0.24;
    return amp * exp(-d * d);
}

float humps(float x) {
    float t = time;
    float sum = hump(x, 0.3 + 0.22 * sin(0.35 * t), 0.75 + 0.25 * sin(1.3 * t)) +
        hump(x, 0.72 + 0.2 * sin(0.5 * t + 2.0), 0.75 + 0.25 * sin(1.1 * t + 1.0)) +
        hump(x, 0.5 + 0.32 * sin(0.27 * t + 4.0), 0.6 + 0.3 * sin(0.9 * t + 2.0));
    return 0.07 + level * 0.95 * min(sum, 1.2);
}

float glow(float y, float h) { return 0.9 * (1.0 - smoothstep(0.2, 1.3, y / h)); }
float haze(float y, float h) { return 0.3 * (1.0 - smoothstep(0.8, 3.0, y / h)); }
float diamond(float y, float h) { return smoothstep(0.35, 0.8, glow(y, h)) * smoothstep(0.16, 0.4, h); }

half4 main(float2 p) {
    float Y = size.y - p.y;
    float y = Y / size.y;
    float h = humps(clamp(p.x / size.x, 0.0, 1.0));
    float a = max(glow(y, h), haze(y, h));

    float X = p.x + pitch / 2.0;
    float m = floor((X + Y) / pitch + 0.5);
    float n = floor((X - Y) / pitch + 0.5);
    float cx = (m + n) * pitch / 2.0 - pitch / 2.0;
    float cY = (m - n) * pitch / 2.0;
    float d = 0.0;
    if (cY >= 0.0) {
        float cy = cY / size.y;
        float r = diamond(cy, humps(clamp(cx / size.x, 0.0, 1.0))) * 0.44 * pitch;
        if (r >= 0.3) d = clamp(r - (abs(p.x - cx) + abs(Y - cY)) + 0.5, 0.0, 1.0) * 0.85;
    }
    // White diamonds over the tinted glow, premultiplied.
    half3 rgb = color.rgb * half(a * (1.0 - d)) + half3(d);
    return half4(rgb, half(a * (1.0 - d) + d));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderWave(level: () -> Float, time: () -> Float, color: Color, pitch: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(modifier.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("pitch", pitch)
        shader.setColorUniform("color", color.toArgb())
        onDrawBehind {
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("level", level())
            drawRect(brush, Offset.Zero, size)
        }
    })
}
