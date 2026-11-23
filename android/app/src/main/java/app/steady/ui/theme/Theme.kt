package app.steady.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private fun SteadyColors.toMaterial() = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = ink,
        secondary = calm, onSecondary = onAccent, secondaryContainer = calmSoft, onSecondaryContainer = ink,
        tertiary = urge, tertiaryContainer = urgeSoft, onTertiaryContainer = ink,
        background = screen, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = bg, onSurfaceVariant = muted, outline = line, outlineVariant = line,
        error = urge, errorContainer = urgeSoft,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = ink,
        secondary = calm, onSecondary = onAccent, secondaryContainer = calmSoft, onSecondaryContainer = ink,
        tertiary = urge, tertiaryContainer = urgeSoft, onTertiaryContainer = ink,
        background = screen, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = bg, onSurfaceVariant = muted, outline = line, outlineVariant = line,
        error = urge, errorContainer = urgeSoft,
    )
}

val SteadyShapes = Shapes(
    small = RoundedCornerShape(12.dp),   // .opt
    medium = RoundedCornerShape(14.dp),  // .cta / .ghost
    large = RoundedCornerShape(16.dp),   // .card
)

@Composable
fun SteadyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkSteadyColors else LightSteadyColors
    CompositionLocalProvider(LocalSteadyColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = SteadyTypography,
            shapes = SteadyShapes,
            content = content,
        )
    }
}

/** Access prototype tokens not covered by Material: `Steady.colors.urge`, etc. */
object Steady {
    val colors: SteadyColors
        @Composable @ReadOnlyComposable get() = LocalSteadyColors.current
}
