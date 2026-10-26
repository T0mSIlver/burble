package dev.silver.burble.overlay

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.silver.burble.BuildConfig
import dev.silver.burble.ui.theme.Motion
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * The listening wave: Gboard's Rambler bar, rebuilt for a round bubble. Gboard draws its bar
 * with AGSL shaders; Burble's design notes' research/gboard-wave-renderer.md describes them, and its
 * playground's variant G1 is this wave. The bar's bottom edge, where Gboard's glow rises, is the
 * bubble's centre, and heights above that edge are distances from the centre, dp for dp.
 *
 * Layers, back to front: a glow whose soft edge sits 15 dp from the centre in silence and
 * 35 dp at full voice, wobbling in broad noise humps; two light bands from the centre, drawn
 * with Lighten; and a 45° lattice of 3.75 dp cells holding pixel-stepped diamonds, sized and
 * pushed around by value noise, shown only where the glow is and only while the voice is on.
 */

/** One frame's inputs: from [WaveHost] while listening, from [steady] in screenshot tests. */
@Immutable
internal data class WaveFrame(
    /** The glow's level, 0..1. */
    val glow: Float,
    /** The light bands' level, 0..1. */
    val bands: Float,
    /** The diamonds' opacity, 0..1.5. */
    val dots: Float,
    /** The glow noise's sideways drift, in bar heights. */
    val drift: Float,
    /** The glow noise's morph clock. */
    val morph: Float,
    /** Seconds since listening started: the diamonds' clock. */
    val time: Float,
    /** The bubble opening from its centre, 0..1. */
    val opened: Float,
) {
    companion object {
        /** What a steady [level] settles to after [time] s. */
        fun steady(level: Float, time: Float): WaveFrame {
            val l = gate(level)
            return WaveFrame(l, l, if (l > 0f) DOTS_ON else 0f, -DRIFT * time, MORPH * (1f + 9f * l) * time, time, 1f)
        }
    }
}

/** Under 0.5 the voice counts as silence, as on Gboard. */
internal fun gate(level: Float) = if (level < 0.5f) 0f else level.coerceAtMost(1f)

/**
 * Gboard's host side: turns level updates into the shader's inputs. Each update sets the light
 * bands and the diamonds on straight 200 ms ramps from where they are; the glow chases the
 * level at 5 per second both ways (0 to 1 in 200 ms). On opening, the diamonds show for 166 ms
 * and fade over 333 ms. Times in ms.
 */
internal class WaveHost(now: Double, settled: Boolean = false) {
    private val start = if (settled) now - 333 else now
    private var glow = 0f
    private var target = 0f
    private val bands = Ramp(0f)
    // Settled (a retry, straight into finishing): already open, no opening flash.
    private val dots = Ramp(if (settled) 0f else DOTS_ON)
    private var dotsFadeAt = if (settled) -1.0 else now + 166
    private var drift = 0f
    private var morph = 0f
    private var last = now

    /** A new level, 0..1. */
    fun feed(level: Float, now: Double) {
        val l = gate(level)
        bands.set(l, now, 200.0)
        // Silence leaves the opening flash to finish; the voice takes over the diamonds.
        if (l > 0f || dotsFadeAt < 0) {
            dotsFadeAt = -1.0
            dots.set(if (l > 0f) DOTS_ON else 0f, now, 200.0)
        }
        target = l
    }

    /**
     * Finishing: the diamonds and the light bands leave in 100 ms and the glow chases silence,
     * at most 200 ms from full voice, settling to its resting light.
     */
    fun settle(now: Double) {
        dotsFadeAt = -1.0
        dots.set(0f, now, 100.0, Motion.standard)
        bands.set(0f, now, 100.0, Motion.standard)
        target = 0f
    }

    /** Once per frame. */
    fun step(now: Double) {
        val dt = (now - last).coerceAtLeast(0.0)
        last = now
        if (dotsFadeAt >= 0 && now >= dotsFadeAt) {
            dots.set(0f, now, 333.0)
            dotsFadeAt = -1.0
        }
        val s = (0.005 * dt).toFloat()
        glow += (target - glow).coerceIn(-s, s)
        drift -= DRIFT * (dt / 1000).toFloat()
        morph += MORPH * (1f + 9f * glow) * (dt / 1000).toFloat()
    }

    fun frame(now: Double) = WaveFrame(
        glow, bands.at(now), dots.at(now), drift, morph,
        ((now - start) / 1000).toFloat(), ((now - start) / 333).toFloat().coerceIn(0f, 1f),
    )

    /** Gboard's straight ramps; [easing] curves one (the bubble's settle). */
    private class Ramp(v: Float) {
        private var from = v
        private var to = v
        private var start = 0.0
        private var ms = 1.0
        private var easing: Easing? = null
        fun set(target: Float, now: Double, duration: Double, easing: Easing? = null) {
            from = at(now); to = target; start = now; ms = duration; this.easing = easing
        }
        fun at(now: Double): Float {
            val k = ((now - start) / ms).coerceIn(0.0, 1.0).toFloat()
            return from + (to - from) * (easing?.transform(k) ?: k)
        }
    }
}

private const val DOTS_ON = 1.5f
private const val DRIFT = 0.21f
private const val MORPH = 0.95f

/**
 * The wave's geometry. The defaults are the shipped radial wave.
 *
 * TODO(before v0.1.0): remove the debug "Wave shape (temporary)" row (src/debug,
 * ui/WaveShapeDebug.kt) and this switchboard; keep only the shape Tom picks, as constants.
 */
@Immutable
internal data class WaveShape(
    /** Scales the glow's distances: 1.25 lights a quarter more radius. Tom's pick on the phone. */
    val cover: Float = 1.5f,
    /** 0 a disc; toward 1 a wide horizontal ellipse. */
    val blend: Float = 0f,
    /** The glow's source below the centre, share of the radius. */
    val lift: Float = 0f,
    /** The glow's edge in silence, dp from the source. */
    val edgeRest: Float = 15f,
    /** How far full voice pushes it, dp. */
    val edgeGain: Float = 20f,
) {
    companion object {
        /** The shape every wave draws. Only debug builds change it. */
        var current by mutableStateOf(WaveShape())

        private const val PREFS = "debug_wave_shape"

        /** Debug builds: the shape chosen in Advanced, kept across restarts. */
        fun load(context: Context) {
            if (!BuildConfig.DEBUG) return
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = WaveShape()
            current = WaveShape(
                p.getFloat("cover", d.cover), p.getFloat("blend", d.blend), p.getFloat("lift", d.lift),
                p.getFloat("edgeRest", d.edgeRest), p.getFloat("edgeGain", d.edgeGain),
            )
        }

        fun save(context: Context, shape: WaveShape) {
            if (!BuildConfig.DEBUG) return
            current = shape
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("cover", shape.cover).putFloat("blend", shape.blend).putFloat("lift", shape.lift)
                .putFloat("edgeRest", shape.edgeRest).putFloat("edgeGain", shape.edgeGain).apply()
        }
    }
}

/**
 * The wave for [frame]. [accent] sets the hue (Gboard derives its glow from the system accent);
 * the background is the listening bubble's surface.
 */
@Composable
internal fun Wave(
    frame: () -> WaveFrame,
    accent: Color,
    modifier: Modifier = Modifier,
    shader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    // The bubble's one background, in every state.
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val dark = surface.luminance() < 0.5f
    val colors = remember(accent, dark) { waveColors(accent, dark) }
    val dp = LocalDensity.current.density
    if (shader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderWave(frame, colors, surface, dp, modifier)
    } else {
        CanvasWave(frame, colors, surface, dp, modifier)
    }
}

/** [Wave] driven by the live level, [Dictation.level]'s scale, and the frame clock. */
@Composable
fun LiveWave(
    level: StateFlow<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    finishing: () -> Boolean = { false },
    reducedMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    val state = remember { mutableStateOf(WaveFrame.steady(0f, 0f).copy(dots = DOTS_ON, opened = 0f)) }
    LaunchedEffect(level) {
        var host: WaveHost? = null
        // The flow starts at 0 before the first buffer. It drops repeats, where Gboard would
        // restart the same ramps, which changes nothing.
        var fed = 0f
        var settled = false
        while (true) withInfiniteAnimationFrameNanos { nanos ->
            val now = nanos / 1e6
            val h = host ?: WaveHost(now, settled = finishing()).also { host = it }
            if (finishing()) {
                if (!settled) { h.settle(now); settled = true }
            } else {
                // Listening again in the same wave (a tap within its fade-out): the next finish
                // settles it again.
                settled = false
                val heard = level.value
                if (heard != fed) {
                    h.feed(heard, now)
                    fed = heard
                }
            }
            h.step(now)
            // Removed animations: the glow still follows the voice; its noise and diamonds stay still.
            state.value = h.frame(now).let { if (reducedMotion) it.copy(drift = 0f, morph = 0f, time = 0f) else it }
        }
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) { WaveShape.load(context) }
    Wave({ state.value }, accent, modifier)
}

/** The wave's colours: the glow, and the diamonds. */
@Immutable
internal data class WaveColors(val glow: Color, val dots: Color)

/**
 * Gboard's glow, measured on its bar under pink and blue palettes: its hue lies halfway between
 * the accent's and 302° (in OKLCH), and it is more saturated than any M3 role, at lightness 0.78
 * (0.67 on a dark surface). Diamonds are white on light surfaces, a pale tint of the glow on
 * dark ones.
 */
internal fun waveColors(accent: Color, dark: Boolean): WaveColors {
    val h = accentHue(accent)
    val d = ((302.0 - h + 540.0) % 360.0) - 180.0
    val hue = h + d / 2
    return if (dark) WaveColors(oklch(0.67, 0.10, hue), oklch(0.86, 0.07, hue))
    else WaveColors(oklch(0.78, 0.14, hue), Color.White)
}

/**
 * The bubble's glyphs (the mic, the refresh arrow): the glow's hue, deep enough to read on the
 * bubble's tonal background (OKLCH lightness 0.5, 0.86 on a dark surface), at 1.3 times the
 * glow's chroma up to 0.16.
 */
internal fun waveInk(accent: Color, dark: Boolean): Color {
    val glow = waveColors(accent, dark).glow
    val (a, b) = oklabAb(glow)
    val hue = (Math.toDegrees(atan2(b, a)) + 360) % 360
    return oklch(if (dark) 0.86 else 0.5, minOf(0.16, 1.3 * hypot(a, b)), hue)
}

private fun lin(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun gam(c: Double): Double = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

/** OKLab's a and b for [c]. */
private fun oklabAb(c: Color): Pair<Double, Double> {
    val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s) to (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
}

private fun accentHue(c: Color): Double {
    val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return (Math.toDegrees(atan2(bb, a)) + 360) % 360
}

/** An OKLCH colour, its chroma reduced until it fits sRGB. */
private fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    var c = chroma
    while (true) {
        val a = c * cos(Math.toRadians(hue))
        val b = c * sin(Math.toRadians(hue))
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        if ((r in 0.0..1.0 && g in 0.0..1.0 && bl in 0.0..1.0) || c <= 0.0) {
            return Color(gam(r.coerceIn(0.0, 1.0)).toFloat(), gam(g.coerceIn(0.0, 1.0)).toFloat(), gam(bl.coerceIn(0.0, 1.0)).toFloat())
        }
        c -= 0.005
    }
}

/** The largest bubble's radius, dp: the glow's geometry is drawn to it and scaled to the bubble. */
private const val REF_R = 48f

/** Gboard's bar height, dp: it sets the light bands, the fade and the diamonds' reach. */
private const val BAR_DP = 51f

// Android 12: the glow and the white core as radial gradients, without the lattice.
@Composable
private fun CanvasWave(frame: () -> WaveFrame, colors: WaveColors, surface: Color, dp: Float, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val c = Offset(size.width / 2, size.height / 2)
        val r = hypot(size.width, size.height) / 2
        // The glow follows the bubble's size, as in the shader.
        val ref = REF_R / (minOf(size.width, size.height) / 2 / dp)
        val n = 64 // about 0.7 dp a stop, fine enough for the crisp silent edge
        onDrawBehind {
            val f = frame()
            // The humps' rise and fall, without their shape round the edge.
            val shape = WaveShape.current // blend and lift are the shader's only
            val edge = shape.edgeRest + shape.edgeGain * f.glow + 0.25f * (13f + 25f * f.glow) * sin(1.1f * f.morph)
            val e0 = shape.edgeRest + shape.edgeGain * f.glow
            val band = minOf(BAR_DP / 6 * (1 + f.bands), 0.45f * e0)
            val soft = 6f * (SILENT_SOFT + (1 - SILENT_SOFT) * f.glow)
            val glow = Array(n + 1) { i ->
                val u = i * r / n / dp * ref / shape.cover
                i / n.toFloat() to colors.glow.copy(alpha = logistic((edge - u) / soft) * reveal(u))
            }
            val core = Array(n + 1) { i ->
                val u = i * r / n / dp * ref / shape.cover
                val t = ((band - u) / band).coerceIn(0f, 1f)
                i / n.toFloat() to Color.White.copy(alpha = CORE * t * t * (3 - 2 * t))
            }
            drawRect(surface)
            drawRect(Brush.radialGradient(*glow, center = c, radius = r))
            drawRect(Brush.radialGradient(*core, center = c, radius = r))
        }
    })
}

private fun logistic(x: Float) = 1f / (1f + exp(-x))
private fun reveal(u: Float) = 1f / (1f + exp(6f * (u - 2f * BAR_DP / 3f) / BAR_DP))
private const val CORE = 0.2f
private const val SILENT_SOFT = 0.2f

// The shader. p is in px from the top-left; u is the distance from the centre in dp, the
// height above Gboard's bar edge.
private const val AGSL = """
uniform float2 size;
uniform float dp;
uniform float glowLvl;
uniform float bandsLvl;
uniform float dotsAlpha;
uniform float drift;
uniform float morph;
uniform float time;
uniform float opened;
uniform float cover;
uniform float blend;
uniform float lift;
uniform float edgeRest;
uniform float edgeGain;
layout(color) uniform half4 glowColor;
layout(color) uniform half4 dotColor;
layout(color) uniform half4 surface;

const float PI = 3.14159265;
const float BAR = 51.0;     // Gboard's bar height, dp
const float CELL = 3.75;   // lattice cell, dp
const float DOT_MAX = 0.37;
const float DOT_MIN = -0.07;
const float CORE = 0.2;
const float SILENT_SOFT = 0.2;
const float REF_R = 48.0;  // the largest bubble's radius, dp: the glow's geometry is drawn to it

// Rotate v by -a, as Gboard's 2D rotation does.
float2 turn(float2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * v.x + s * v.y, -s * v.x + c * v.y);
}

float vhash(float2 c) { return fract(sin(dot(c, float2(12.9898, 78.233))) * 43758.5453); }

// Value noise and its gradient: x = 0..1, yz = d/dx, d/dy.
float3 vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float2 du = 6.0 * f * (1.0 - f);
    float a = vhash(i);
    float k1 = vhash(i + float2(1.0, 0.0)) - a;
    float k2 = vhash(i + float2(0.0, 1.0)) - a;
    float k3 = vhash(i + float2(1.0, 1.0)) - a - k1 - k2;
    return float3(a + k1 * u.x + k2 * u.y + k3 * u.x * u.y, du * float2(k1 + k3 * u.y, k2 + k3 * u.x));
}

float3 ghash(float3 c) {
    return fract(sin(float3(dot(c, float3(127.1, 311.7, 74.7)), dot(c, float3(269.5, 183.3, 246.1)),
        dot(c, float3(113.5, 271.9, 124.6)))) * 43758.5453) * 2.0 - 1.0;
}

float corner(float3 i, float3 f, float3 o) { return dot(ghash(i + o), f - o); }

// Gradient noise in 3D, about -1..1.
float gnoise(float3 p) {
    float3 i = floor(p);
    float3 f = fract(p);
    float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float x00 = mix(corner(i, f, float3(0.0, 0.0, 0.0)), corner(i, f, float3(1.0, 0.0, 0.0)), u.x);
    float x10 = mix(corner(i, f, float3(0.0, 1.0, 0.0)), corner(i, f, float3(1.0, 1.0, 0.0)), u.x);
    float x01 = mix(corner(i, f, float3(0.0, 0.0, 1.0)), corner(i, f, float3(1.0, 0.0, 1.0)), u.x);
    float x11 = mix(corner(i, f, float3(0.0, 1.0, 1.0)), corner(i, f, float3(1.0, 1.0, 1.0)), u.x);
    return 1.6 * mix(mix(x00, x10, u.y), mix(x01, x11, u.y), u.z);
}

// The glow's noise at q (dp), 0..1: cells 1 / 0.27 bar heights across.
float glowNoise(float2 q) {
    return clamp(0.5 + 0.5 * gnoise(float3(q / BAR, morph) * 0.27), 0.0, 1.0);
}

// The glow's opacity at height u: a soft step at 15 + 20 * level dp, moved by the humps (noise
// taken at hq, round the centre), its softness varying with the noise at q. Gboard's 6 dp edge
// reads as a glow on its 51 dp bar but as fog across the bubble, so silence draws it at a fifth
// of that and the voice brings Gboard's softness back.
float glowAlpha(float u, float2 q, float2 hq) {
    float e = edgeRest + edgeGain * glowLvl + (glowNoise(hq) - 0.5) * (13.0 + 25.0 * glowLvl);
    float x = (e - u) / (6.0 * mix(SILENT_SOFT, 1.0, glowLvl));
    return 1.0 / (1.0 + exp(-x / (0.5 * (1.0 + 2.0 * glowNoise(q)))));
}

// A light band from the source to height f (px): inner colour at the source, outer at the top,
// premultiplied. Half strength right at the source, where Gboard's band meets the bar's edge.
half4 band(float u, float f, half3 inner, half3 outer) {
    float t = clamp((f + 1.0 - u) / (f + 1.0), 0.0, 1.0);
    float m = t * t * (3.0 - 2.0 * t);
    float b = 0.5 * f * clamp(1.0 - u / f, 0.0, 1.0);
    float sdf = -min(u, f - u);
    float k = m * (1.0 - smoothstep(-1.0 - b, 1.0 + b, sdf)) * (1.0 - smoothstep(-1.0, 1.0, sdf));
    return half4(mix(outer, inner, half(m)) * half(k), half(k));
}

half3 lighten(half3 d, half4 s) { return s.rgb + d - min(s.rgb, d * s.a); }

// The diamonds' noise at a cell centre (dp, lattice axes), swinging round the centre and
// drifting diagonally.
float3 field(float2 c) {
    float2 b = c / 35.0;
    return vnoise(turn(b, PI * sin(time * 0.1)) + time * float2(0.6, 0.9) + float2(37.2, 11.7));
}

float2 neighbour(int k) {
    if (k == 1) return float2(1.0, 0.0);
    if (k == 2) return float2(-1.0, 0.0);
    if (k == 3) return float2(0.0, 1.0);
    if (k == 4) return float2(0.0, -1.0);
    return float2(0.0);
}

// 1 where p (px) is inside a diamond: each cell's diamond is sized by the noise and pushed off
// its site along the noise's gradient, turned by an angle that cycles; the push is capped. A
// pixel checks its own cell and the four sharing its edges. Hard edges, pixel-stepped.
float diamonds(float2 p, float2 c) {
    float2 d = p - c;
    float2 g = 0.70710678 * float2(d.x - d.y, d.x + d.y) / (CELL * dp);
    float2 i = floor(g);
    float2 f = fract(g);
    float cover = 0.0;
    for (int k = 0; k < 5; k++) {
        float2 o = neighbour(k);
        float3 n = field((i + o + 0.5) * CELL);
        float2 push = turn(2.0 * n.yz, 2.0 * PI * (n.x + 0.2 * time) * -1.79);
        push /= 1.0 + (abs(push.x) + abs(push.y)) / 0.35;
        float2 rel = f - (0.5 + o + push * 2.0);
        cover = max(cover, step(max(abs(rel.x), abs(rel.y)), mix(DOT_MIN, DOT_MAX, n.x)));
    }
    return cover;
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    // The glow follows the bubble's size: its distances are in dp of the largest bubble (96 dp,
    // radius REF_R), so a coverage looks the same at every size. The diamonds stay in real dp.
    float R = min(size.x, size.y) * 0.5 / dp;
    float2 q = (p - c) / dp * (REF_R / R);
    // The source can sit below the centre (lift) and the disc widen into a horizontal ellipse
    // (blend); cover scales every distance.
    float2 qs = float2(q.x * (1.0 - min(blend, 0.95)), q.y - lift * REF_R);
    float u = length(qs) / cover;
    // The humps travel round the source at the bar's sideways speed, on a 25 dp circle.
    float rw = 25.0;
    float tw = atan(qs.y, qs.x) + drift * BAR / rw;
    float2 hq = rw * float2(cos(tw), sin(tw));
    float reveal = 1.0 / (1.0 + exp(6.0 * (u - 2.0 * BAR / 3.0) / BAR));
    float open = 1.0 / (1.0 + exp(6.0 * (u - 2.0 * REF_R * opened - BAR / 3.0) / BAR));
    float shown = min(reveal, open);
    float a = glowAlpha(u, q + float2(-drift * BAR, 0.0), hq) * shown;
    float e0 = edgeRest + edgeGain * glowLvl;
    // In silence the disc is saturated mid-way and paler toward its rim, as Gboard's band pales
    // above its saturated part.
    float pale = 0.6 * smoothstep(0.35 * e0, e0, u) * (1.0 - glowLvl);
    half3 tone = mix(glowColor.rgb, mix(glowColor.rgb, half3(1.0), 0.35), half(pale));
    half3 col = mix(surface.rgb, tone, half(a));
    // The light bands stop inside the rim, so nothing hazes past it.
    float f = min(BAR / 3.0 * (1.0 + bandsLvl), 0.9 * e0) * dp;
    col = lighten(col, band(u * dp, f, glowColor.rgb, glowColor.rgb) * half(0.9 * shown));
    col = lighten(col, band(u * dp, 0.5 * f, half3(1.0), glowColor.rgb) * half(CORE * shown));
    float area = max(0.0, 1.0 - u / BAR) * smoothstep(-1.32 * BAR, 0.0, -min(u, BAR - u));
    float dots = clamp(dotsAlpha * area * diamonds(p, c) * a, 0.0, 1.0);
    return half4(mix(col, dotColor.rgb, half(dots)), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderWave(frame: () -> WaveFrame, colors: WaveColors, surface: Color, dp: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(modifier.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("dp", dp)
        shader.setColorUniform("glowColor", colors.glow.toArgb())
        shader.setColorUniform("dotColor", colors.dots.toArgb())
        shader.setColorUniform("surface", surface.toArgb())
        onDrawBehind {
            val f = frame()
            shader.setFloatUniform("glowLvl", f.glow)
            shader.setFloatUniform("bandsLvl", f.bands)
            shader.setFloatUniform("dotsAlpha", f.dots)
            shader.setFloatUniform("drift", f.drift)
            shader.setFloatUniform("morph", f.morph)
            shader.setFloatUniform("time", f.time)
            shader.setFloatUniform("opened", f.opened)
            val shape = WaveShape.current
            shader.setFloatUniform("cover", shape.cover)
            shader.setFloatUniform("blend", shape.blend)
            shader.setFloatUniform("lift", shape.lift)
            shader.setFloatUniform("edgeRest", shape.edgeRest)
            shader.setFloatUniform("edgeGain", shape.edgeGain)
            drawRect(brush, Offset.Zero, size)
        }
    })
}
