package com.gotohex.rdp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class SpaceColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundGradient: List<Color>,
    val surface: Color,
    val surfaceElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val border: Color,
    val borderGlow: Color,
    val cardGradient: List<Color>,
    val cursorColor: Color,
    val navBar: Color,
    val hologram: Color,
    val warpBlue: Color,
    val solarPulse: Color,
    val deepVoid: Color,
    val starlight: Color,
    val cardBorder: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val chipBackground: Color,
)

// ── Shared status palette ─────────────────────────────────────────────────────
private val DarkSuccess  = Color(0xFF00FFB3L)
private val DarkWarning  = Color(0xFFFFAB00L)
private val DarkDanger   = Color(0xFFFF2D78L)
private val LightSuccess = Color(0xFF00875AL)
private val LightWarning = Color(0xFFB36200L)
private val LightDanger  = Color(0xFFCF1759L)

// ═════════════════════════════════════════════════════════════════════════════
//  DEEP SPACE  (cyan accent)
// ═════════════════════════════════════════════════════════════════════════════
private val SpaceDark = SpaceColors(
    isDark              = true,
    background          = Color(0xFF020816),
    backgroundGradient  = listOf(Color(0xFF020816), Color(0xFF081428), Color(0xFF0D1B3E), Color(0xFF150B2A)),
    surface             = Color(0xFF0E1A30),
    surfaceElevated     = Color(0xFF172038),
    textPrimary         = Color(0xFFE8EAF6),
    textSecondary       = Color(0xFF8FA3C8),
    accent              = Color(0xFF00E5FF),
    accentSecondary     = Color(0xFF2979FF),
    accentTertiary      = Color(0xFF7C4DFF),
    success             = DarkSuccess,
    warning             = DarkWarning,
    danger              = DarkDanger,
    border              = Color(0xFF1E2D4A),
    borderGlow          = Color(0x4000E5FF),
    cardGradient        = listOf(Color(0xFF0E1A30), Color(0xFF0A1525)),
    cursorColor         = Color(0xFF00E5FF),
    navBar              = Color(0xFF060E1E),
    hologram            = Color(0x2000E5FF),
    warpBlue            = Color(0xFF0D47A1),
    solarPulse          = Color(0xFFFF6B35),
    deepVoid            = Color(0xFF020816),
    starlight           = Color(0xFFF5F7FF),
    cardBorder          = Color(0x6600E5FF),
    inputBackground     = Color(0xFF0A1422),
    inputBorder         = Color(0xFF243554),
    chipBackground      = Color(0xFF0D1A30),
)

private val SpaceLight = SpaceColors(
    isDark              = false,
    // Crisp white → very pale sky-blue gradient — feels "open sky / clean tech"
    background          = Color(0xFFF2F6FF),
    backgroundGradient  = listOf(Color(0xFFF2F6FF), Color(0xFFE8F0FF), Color(0xFFDEEAFF), Color(0xFFEEF3FF)),
    surface             = Color(0xFFFFFFFF),
    surfaceElevated     = Color(0xFFF8FAFF),
    textPrimary         = Color(0xFF0A1628),
    textSecondary       = Color(0xFF3D5478),
    accent              = Color(0xFF0057CC),  // deep royal blue
    accentSecondary     = Color(0xFF0091EA),  // sky blue
    accentTertiary      = Color(0xFF651FFF),  // electric violet
    success             = LightSuccess,
    warning             = LightWarning,
    danger              = LightDanger,
    border              = Color(0xFFD0DCF0),
    borderGlow          = Color(0x300057CC),
    cardGradient        = listOf(Color(0xFFFFFFFF), Color(0xFFF4F8FF)),
    cursorColor         = Color(0xFF0057CC),
    navBar              = Color(0xFFEAF1FF),
    hologram            = Color(0x180057CC),
    warpBlue            = Color(0xFF1565C0),
    solarPulse          = Color(0xFFE65100),
    deepVoid            = Color(0xFFF2F6FF),
    starlight           = Color(0xFF0A1628),
    cardBorder          = Color(0xFF90B8FF),
    inputBackground     = Color(0xFFF5F8FF),
    inputBorder         = Color(0xFFBDD0F5),
    chipBackground      = Color(0xFFEBF2FF),
)

// ═════════════════════════════════════════════════════════════════════════════
//  NEBULA  (violet / magenta accent)
// ═════════════════════════════════════════════════════════════════════════════
private val NebulaDark = SpaceColors(
    isDark              = true,
    background          = Color(0xFF080512),
    backgroundGradient  = listOf(Color(0xFF080512), Color(0xFF140B28), Color(0xFF1E0E3A), Color(0xFF280B30)),
    surface             = Color(0xFF130B24),
    surfaceElevated     = Color(0xFF1E1238),
    textPrimary         = Color(0xFFF3EDFF),
    textSecondary       = Color(0xFFA08CC8),
    accent              = Color(0xFFBB86FC),
    accentSecondary     = Color(0xFFFF4FA8),
    accentTertiary      = Color(0xFF03DAC6),
    success             = DarkSuccess,
    warning             = DarkWarning,
    danger              = Color(0xFFFF4D6D),
    border              = Color(0xFF2A1A4A),
    borderGlow          = Color(0x40BB86FC),
    cardGradient        = listOf(Color(0xFF130B24), Color(0xFF100920)),
    cursorColor         = Color(0xFFBB86FC),
    navBar              = Color(0xFF06030E),
    hologram            = Color(0x20BB86FC),
    warpBlue            = Color(0xFF4527A0),
    solarPulse          = Color(0xFFFF6B6B),
    deepVoid            = Color(0xFF080512),
    starlight           = Color(0xFFF3EDFF),
    cardBorder          = Color(0x60BB86FC),
    inputBackground     = Color(0xFF0D0818),
    inputBorder         = Color(0xFF331B4F),
    chipBackground      = Color(0xFF180D2A),
)

private val NebulaLight = SpaceColors(
    isDark              = false,
    // Warm lavender-white — feels "cosmic soft light"
    background          = Color(0xFFF8F4FF),
    backgroundGradient  = listOf(Color(0xFFF8F4FF), Color(0xFFF2EAFF), Color(0xFFECE0FF), Color(0xFFF5F0FF)),
    surface             = Color(0xFFFFFFFF),
    surfaceElevated     = Color(0xFFFBF8FF),
    textPrimary         = Color(0xFF1A0B30),
    textSecondary       = Color(0xFF5C4080),
    accent              = Color(0xFF7B2FBE),   // rich purple
    accentSecondary     = Color(0xFFD81B60),   // deep pink
    accentTertiary      = Color(0xFF00897B),   // teal complement
    success             = LightSuccess,
    warning             = LightWarning,
    danger              = LightDanger,
    border              = Color(0xFFDDD0F5),
    borderGlow          = Color(0x307B2FBE),
    cardGradient        = listOf(Color(0xFFFFFFFF), Color(0xFFFAF5FF)),
    cursorColor         = Color(0xFF7B2FBE),
    navBar              = Color(0xFFF0E8FF),
    hologram            = Color(0x187B2FBE),
    warpBlue            = Color(0xFF4527A0),
    solarPulse          = Color(0xFFBF360C),
    deepVoid            = Color(0xFFF8F4FF),
    starlight           = Color(0xFF1A0B30),
    cardBorder          = Color(0xFFBB90E8),
    inputBackground     = Color(0xFFF5F0FF),
    inputBorder         = Color(0xFFCBB8EE),
    chipBackground      = Color(0xFFEFE5FF),
)

// ═════════════════════════════════════════════════════════════════════════════
//  AURORA  (green / teal accent)
// ═════════════════════════════════════════════════════════════════════════════
private val AuroraDark = SpaceColors(
    isDark              = true,
    background          = Color(0xFF030F0C),
    backgroundGradient  = listOf(Color(0xFF030F0C), Color(0xFF081F18), Color(0xFF0C2E22), Color(0xFF062030)),
    surface             = Color(0xFF091E18),
    surfaceElevated     = Color(0xFF0F2920),
    textPrimary         = Color(0xFFE0FFF5),
    textSecondary       = Color(0xFF7BBCA8),
    accent              = Color(0xFF00F5A0),
    accentSecondary     = Color(0xFF00D4C8),
    accentTertiary      = Color(0xFF5CE1E6),
    success             = Color(0xFF00F5A0),
    warning             = DarkWarning,
    danger              = Color(0xFFFF5C7C),
    border              = Color(0xFF1A3D30),
    borderGlow          = Color(0x4000F5A0),
    cardGradient        = listOf(Color(0xFF091E18), Color(0xFF071A15)),
    cursorColor         = Color(0xFF00F5A0),
    navBar              = Color(0xFF020A08),
    hologram            = Color(0x2000F5A0),
    warpBlue            = Color(0xFF006064),
    solarPulse          = Color(0xFFFF8A50),
    deepVoid            = Color(0xFF030F0C),
    starlight           = Color(0xFFE0FFF5),
    cardBorder          = Color(0x6000F5A0),
    inputBackground     = Color(0xFF061510),
    inputBorder         = Color(0xFF1D4035),
    chipBackground      = Color(0xFF0A1E18),
)

private val AuroraLight = SpaceColors(
    isDark              = false,
    // Mint-white with teal depth — fresh and clear
    background          = Color(0xFFEFF8F5),
    backgroundGradient  = listOf(Color(0xFFEFF8F5), Color(0xFFE2F5EE), Color(0xFFD6F0E7), Color(0xFFEBF7F2)),
    surface             = Color(0xFFFFFFFF),
    surfaceElevated     = Color(0xFFF5FBF8),
    textPrimary         = Color(0xFF062018),
    textSecondary       = Color(0xFF2D6655),
    accent              = Color(0xFF00796B),   // forest teal
    accentSecondary     = Color(0xFF0097A7),   // ocean blue
    accentTertiary      = Color(0xFF2E7D32),   // forest green
    success             = LightSuccess,
    warning             = LightWarning,
    danger              = LightDanger,
    border              = Color(0xFFC0E0D8),
    borderGlow          = Color(0x3000796B),
    cardGradient        = listOf(Color(0xFFFFFFFF), Color(0xFFF2FAF7)),
    cursorColor         = Color(0xFF00796B),
    navBar              = Color(0xFFDFF2EC),
    hologram            = Color(0x1800796B),
    warpBlue            = Color(0xFF006064),
    solarPulse          = Color(0xFFBF360C),
    deepVoid            = Color(0xFFEFF8F5),
    starlight           = Color(0xFF062018),
    cardBorder          = Color(0xFF80C4B8),
    inputBackground     = Color(0xFFF0FAF7),
    inputBorder         = Color(0xFFB0D8D0),
    chipBackground      = Color(0xFFE5F5F0),
)

// ── Theme selector ────────────────────────────────────────────────────────────
fun spaceColorsFor(themeVariant: String, darkTheme: Boolean): SpaceColors = when (themeVariant) {
    "nebula" -> if (darkTheme) NebulaDark  else NebulaLight
    "aurora" -> if (darkTheme) AuroraDark  else AuroraLight
    else     -> if (darkTheme) SpaceDark   else SpaceLight
}

val LocalSpaceColors = staticCompositionLocalOf { SpaceDark }

/** Exposes the active theme variant name ("space" | "nebula" | "aurora")
 *  so composables can resolve SpaceIcons without needing it passed
 *  explicitly as a parameter. Provided by HexRDPTheme. */
val LocalThemeVariant = staticCompositionLocalOf { "space" }

fun materialColorSchemeFor(colors: SpaceColors) = if (colors.isDark) {
    darkColorScheme(
        primary            = colors.accent,
        onPrimary          = colors.background,
        primaryContainer   = colors.surface,
        onPrimaryContainer = colors.textPrimary,
        secondary          = colors.accentSecondary,
        onSecondary        = colors.textPrimary,
        background         = colors.background,
        onBackground       = colors.textPrimary,
        surface            = colors.surfaceElevated,
        onSurface          = colors.textPrimary,
        surfaceVariant     = colors.surface,
        onSurfaceVariant   = colors.textSecondary,
        error              = colors.danger,
        onError            = colors.background,
        outline            = colors.border,
        outlineVariant     = colors.inputBorder,
        scrim              = Color(0xBB000000),
    )
} else {
    lightColorScheme(
        primary            = colors.accent,
        onPrimary          = Color.White,
        primaryContainer   = colors.chipBackground,
        onPrimaryContainer = colors.accent,
        secondary          = colors.accentSecondary,
        onSecondary        = Color.White,
        background         = colors.background,
        onBackground       = colors.textPrimary,
        surface            = colors.surfaceElevated,
        onSurface          = colors.textPrimary,
        surfaceVariant     = colors.surface,
        onSurfaceVariant   = colors.textSecondary,
        error              = colors.danger,
        onError            = Color.White,
        outline            = colors.border,
        outlineVariant     = colors.inputBorder,
        scrim              = Color(0x55000000),
    )
}

// ── Semantic color aliases (theme-aware, composable only) ─────────────────────
val StarDust: Color          @Composable get() = LocalSpaceColors.current.textPrimary
val CometTail: Color         @Composable get() = LocalSpaceColors.current.textSecondary
val PulsarCyan: Color        @Composable get() = LocalSpaceColors.current.accent
val QuantumBlue: Color       @Composable get() = LocalSpaceColors.current.accentSecondary
val VoidPurple: Color        @Composable get() = LocalSpaceColors.current.accentTertiary
val PlasmaGreen: Color       @Composable get() = LocalSpaceColors.current.success
val NovaPink: Color          @Composable get() = LocalSpaceColors.current.danger
val SolarFlare: Color        @Composable get() = LocalSpaceColors.current.warning
val HorizonGray: Color       @Composable get() = LocalSpaceColors.current.border
val GlowBorder: Color        @Composable get() = LocalSpaceColors.current.borderGlow
val DeepSpace: Color         @Composable get() = LocalSpaceColors.current.background
val NebulaSurface: Color     @Composable get() = LocalSpaceColors.current.surface
val StarfieldSurface: Color  @Composable get() = LocalSpaceColors.current.surfaceElevated
val GradientCardStart: Color @Composable get() = LocalSpaceColors.current.cardGradient[0]
val GradientCardEnd: Color   @Composable get() = LocalSpaceColors.current.cardGradient[1]
val ConnectedGreen: Color    @Composable get() = LocalSpaceColors.current.success
val ConnectingAmber: Color   @Composable get() = LocalSpaceColors.current.warning
val DisconnectedGray: Color  @Composable get() = LocalSpaceColors.current.textSecondary
val ErrorRed: Color          @Composable get() = LocalSpaceColors.current.danger
val NavBarColor: Color       @Composable get() = LocalSpaceColors.current.navBar
val HologramColor: Color     @Composable get() = LocalSpaceColors.current.hologram
val WarpBlue: Color          @Composable get() = LocalSpaceColors.current.warpBlue
val SolarPulse: Color        @Composable get() = LocalSpaceColors.current.solarPulse
val CardBorderColor: Color   @Composable get() = LocalSpaceColors.current.cardBorder
val InputBg: Color           @Composable get() = LocalSpaceColors.current.inputBackground
val InputBorder: Color       @Composable get() = LocalSpaceColors.current.inputBorder
val ChipBg: Color            @Composable get() = LocalSpaceColors.current.chipBackground
