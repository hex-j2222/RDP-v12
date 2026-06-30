package com.gotohex.rdp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.gotohex.rdp.R

// ─────────────────────────────────────────────────────────────────────────────
// Fonts — Space/Sci-Fi themed:
//   • Orbitron      — geometric display font for titles (English/Latin)
//   • Rajdhani      — clean technical body text (English/Latin)
//   • Share Tech Mono — console/HUD monospace for code/labels
//   • Tajawal       — modern geometric Arabic that pairs well with Orbitron
// ─────────────────────────────────────────────────────────────────────────────

private val DisplayFontFamily = FontFamily(
    Font(R.font.orbitron_medium,   weight = FontWeight.Medium),
    Font(R.font.orbitron_semibold, weight = FontWeight.SemiBold),
    Font(R.font.orbitron_bold,     weight = FontWeight.Bold),
    Font(R.font.tajawal_bold,      weight = FontWeight.Bold),
    Font(R.font.tajawal_medium,    weight = FontWeight.Medium),
)

private val BodyFontFamily = FontFamily(
    Font(R.font.rajdhani_regular,  weight = FontWeight.Normal),
    Font(R.font.rajdhani_medium,   weight = FontWeight.Medium),
    Font(R.font.rajdhani_semibold, weight = FontWeight.SemiBold),
    Font(R.font.tajawal_regular,   weight = FontWeight.Normal),
    Font(R.font.tajawal_medium,    weight = FontWeight.Medium),
    Font(R.font.tajawal_bold,      weight = FontWeight.Bold),
)

private val MonoFontFamily = FontFamily(
    Font(R.font.share_tech_mono, weight = FontWeight.Normal),
    Font(R.font.share_tech_mono, weight = FontWeight.Medium),
    Font(R.font.tajawal_regular, weight = FontWeight.Normal),
)

val SpaceTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.15.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp
    ),
)

@Composable
fun HexRDPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeVariant: String = "space",
    content: @Composable () -> Unit
) {
    val spaceColors = spaceColorsFor(themeVariant, darkTheme)
    val colorScheme = materialColorSchemeFor(spaceColors)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // BUG 1 FIX: (view.context as Activity) throws ClassCastException on
            // devices where LocalContext is wrapped (MIUI, OneUI, multi-window,
            // picture-in-picture, or any ContextWrapper chain). Use safe cast
            // and bail out silently — status-bar tint is cosmetic and should
            // never crash the app.
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSpaceColors  provides spaceColors,
        LocalThemeVariant provides themeVariant
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = SpaceTypography,
            content     = content
        )
    }
}
