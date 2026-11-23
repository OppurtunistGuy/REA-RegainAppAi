package app.steady.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Design tokens from prototype/steady-prototype.html (`:root` and dark overrides). */
@Immutable
data class SteadyColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val urge: Color,
    val urgeSoft: Color,
    val calm: Color,
    val calmSoft: Color,
    val screen: Color,
    val isDark: Boolean,
)

val LightSteadyColors = SteadyColors(
    bg = Color(0xFFEEF1F5),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF152230),
    muted = Color(0xFF5A6878),
    line = Color(0xFFD5DCE5),
    accent = Color(0xFF3550D4),
    accentSoft = Color(0xFFE3E8FB),
    onAccent = Color(0xFFFFFFFF),
    urge = Color(0xFFD9573B),
    urgeSoft = Color(0xFFFBE6E0),
    calm = Color(0xFF2F8A6A),
    calmSoft = Color(0xFFDDF1E9),
    screen = Color(0xFFF7F9FB),
    isDark = false,
)

val DarkSteadyColors = SteadyColors(
    bg = Color(0xFF0F151C),
    surface = Color(0xFF18212B),
    ink = Color(0xFFE6ECF2),
    muted = Color(0xFF93A1B0),
    line = Color(0xFF2A3542),
    accent = Color(0xFF8197FF),
    accentSoft = Color(0xFF222C4F),
    onAccent = Color(0xFF0B1020),
    urge = Color(0xFFF0806A),
    urgeSoft = Color(0xFF3A2420),
    calm = Color(0xFF5CC9A0),
    calmSoft = Color(0xFF173329),
    screen = Color(0xFF141C25),
    isDark = true,
)

val LocalSteadyColors = staticCompositionLocalOf { LightSteadyColors }
