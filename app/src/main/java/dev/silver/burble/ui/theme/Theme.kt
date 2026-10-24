package dev.silver.burble.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.silver.burble.R

// Without dynamic colour (Android 10–11, some OEM skins) the app falls back to the blue
// Google's own apps use, not Material's baseline purple.
private val GoogleLight = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF), onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF146C2E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EED0), onTertiaryContainer = Color(0xFF072711),
    background = Color(0xFFF8FAFD), surface = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF0F4F9), surfaceContainerHigh = Color(0xFFE9EEF6),
    onSurface = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF444746),
)

private val GoogleDark = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF), onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004A77), onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C), onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF0F5223), onTertiaryContainer = Color(0xFFC4EED0),
    background = Color(0xFF131314), surface = Color(0xFF131314),
    surfaceContainer = Color(0xFF1E1F20), surfaceContainerHigh = Color(0xFF282A2C),
    onSurface = Color(0xFFE3E3E3), onSurfaceVariant = Color(0xFFC4C7C5),
)

/**
 * Google Sans Flex is variable in weight and optical size. Android applies neither axis by
 * itself, so each style gets a family whose fonts set `wght` to the weight asked for and `opsz`
 * to the style's size, as Google's apps do: tighter at headline sizes, looser at body sizes.
 */
private fun flex(style: TextStyle): TextStyle {
    val size = style.fontSize.value
    val weights = listOf(400, 500, 600, 700) + (style.fontWeight?.weight ?: 400)
    val family = FontFamily(
        weights.distinct().map {
            Font(
                R.font.google_sans_flex,
                FontWeight(it),
                variationSettings = FontVariation.Settings(FontVariation.weight(it), FontVariation.Setting("opsz", size)),
            )
        },
    )
    return style.copy(fontFamily = family)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val typography = Typography().let { m ->
    Typography(
        displayLarge = flex(m.displayLarge),
        displayMedium = flex(m.displayMedium),
        displaySmall = flex(m.displaySmall),
        headlineLarge = flex(m.headlineLarge),
        headlineMedium = flex(m.headlineMedium),
        headlineSmall = flex(m.headlineSmall),
        titleLarge = flex(m.titleLarge),
        titleMedium = flex(m.titleMedium),
        titleSmall = flex(m.titleSmall),
        bodyLarge = flex(m.bodyLarge),
        bodyMedium = flex(m.bodyMedium),
        bodySmall = flex(m.bodySmall),
        labelLarge = flex(m.labelLarge),
        labelMedium = flex(m.labelMedium),
        labelSmall = flex(m.labelSmall),
        displayLargeEmphasized = flex(m.displayLargeEmphasized),
        displayMediumEmphasized = flex(m.displayMediumEmphasized),
        displaySmallEmphasized = flex(m.displaySmallEmphasized),
        headlineLargeEmphasized = flex(m.headlineLargeEmphasized),
        headlineMediumEmphasized = flex(m.headlineMediumEmphasized),
        headlineSmallEmphasized = flex(m.headlineSmallEmphasized),
        titleLargeEmphasized = flex(m.titleLargeEmphasized),
        titleMediumEmphasized = flex(m.titleMediumEmphasized),
        titleSmallEmphasized = flex(m.titleSmallEmphasized),
        bodyLargeEmphasized = flex(m.bodyLargeEmphasized),
        bodyMediumEmphasized = flex(m.bodyMediumEmphasized),
        bodySmallEmphasized = flex(m.bodySmallEmphasized),
        labelLargeEmphasized = flex(m.labelLargeEmphasized),
        labelMediumEmphasized = flex(m.labelMediumEmphasized),
        labelSmallEmphasized = flex(m.labelSmallEmphasized),
    )
}

/**
 * Material 3 Expressive with the wallpaper's colours where Android offers them. Screenshot tests
 * pass [dynamic] false for the Google blue fallback, or a wallpaper scheme as [colors].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    colors: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        colors != null -> colors
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> GoogleDark
        else -> GoogleLight
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        content = content,
    )
}
