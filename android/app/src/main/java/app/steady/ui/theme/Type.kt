package app.steady.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.steady.R

/**
 * Downloadable Google Fonts. If the provider is missing (no Play services) or the download fails,
 * Compose falls back to the platform default sans-serif automatically.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val atkinson = GoogleFont("Atkinson Hyperlegible")
private val bricolage = GoogleFont("Bricolage Grotesque")
private val jetbrainsMono = GoogleFont("JetBrains Mono")

val BodyFont = FontFamily(
    Font(googleFont = atkinson, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = atkinson, fontProvider = provider, weight = FontWeight.Bold),
)

val DisplayFont = FontFamily(
    Font(googleFont = bricolage, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = bricolage, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = bricolage, fontProvider = provider, weight = FontWeight.ExtraBold),
)

val MonoFont = FontFamily(
    Font(googleFont = jetbrainsMono, fontProvider = provider, weight = FontWeight.Medium),
)

val SteadyTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 29.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp),
    // Prototype `.p-label`: mono, uppercase, tracked.
    labelSmall = TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.08.em),
)
