package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gguf.zerocopy.data.local.SettingsManager

object ThemeState {
  var isDark by mutableStateOf(SettingsManager.isDarkTheme)
  var themeMode by mutableStateOf(SettingsManager.themeMode)
}

// ── Shape system (calculated, geometric) ────────────────────────────────────

/**
 * Symmetric radius scale — every component draws from these tokens so
 * the whole app shares one geometric language. No ad-hoc radius values.
 */
object ZcShape {
  val Xs = RoundedCornerShape(4.dp)   // chips, badges, small overlays
  val Sm = RoundedCornerShape(8.dp)   // inputs, list items, buttons (secondary)
  val Md = RoundedCornerShape(12.dp)  // cards, sheets, dialogs
  val Lg = RoundedCornerShape(16.dp)  // large surfaces, bottom sheets
  val Xl = RoundedCornerShape(24.dp)  // hero sections, full-screen modals
  val Pill = RoundedCornerShape(50)   // fully rounded pills / FABs
  val Circle = RoundedCornerShape(50) // avatars, icon buttons (w/ square size)
}

/**
 * 8dp spacing grid — all padding, margin, gap values are multiples of 4dp,
 * snapped to 8dp where possible. Keeps the UI rhythmically consistent.
 */
object ZcSpace {
  val Xxs = 2.dp   // hairline internal gaps
  val Xs  = 4.dp   // tight internal (icon↔text in button)
  val Sm  = 8.dp   // standard internal (card padding = 16 = 2×Sm)
  val Md  = 12.dp  // medium internal
  val Lg  = 16.dp  // section padding, card inset
  val Xl  = 24.dp  // screen margins, major section gaps
  val Xxl = 32.dp  // large structural gaps
  val Xxxl = 48.dp // hero / full-screen breathing room
}

// ── Color schemes (palette unchanged — sourced from ZcColors) ──────────────

private val DarkScheme =
  darkColorScheme(
    background = ZcColors.Bg,
    surface = ZcColors.Surface,
    surfaceVariant = ZcColors.Card,
    surfaceTint = ZcColors.Accent,
    primary = ZcColors.Accent,
    secondary = ZcColors.Accent2,
    tertiary = ZcColors.Purple,
    onBackground = ZcColors.Text,
    onSurface = ZcColors.Text,
    onSurfaceVariant = ZcColors.Text2,
    onPrimary = ZcColors.Bg,
    onSecondary = ZcColors.Bg,
    outline = ZcColors.Border,
    outlineVariant = ZcColors.Border.copy(alpha = 0.5f),
    error = ZcColors.Red,
    errorContainer = ZcColors.Red.copy(alpha = 0.16f),
    onError = ZcColors.Bg,
    onErrorContainer = ZcColors.Red,
  )

private val LightScheme =
  lightColorScheme(
    background = ZcLightColors.Bg,
    surface = ZcLightColors.Surface,
    surfaceVariant = ZcLightColors.Card,
    surfaceTint = ZcLightColors.Accent,
    primary = ZcLightColors.Accent,
    secondary = ZcLightColors.Accent2,
    tertiary = ZcLightColors.Purple,
    onBackground = ZcLightColors.Text,
    onSurface = ZcLightColors.Text,
    onSurfaceVariant = ZcLightColors.Text2,
    onPrimary = ZcLightColors.Bg,
    onSecondary = ZcLightColors.Bg,
    outline = ZcLightColors.Border,
    outlineVariant = ZcLightColors.Border.copy(alpha = 0.5f),
    error = ZcLightColors.Red,
    errorContainer = ZcLightColors.Red.copy(alpha = 0.16f),
    onError = ZcLightColors.Bg,
    onErrorContainer = ZcLightColors.Red,
  )

// ── Typography (sans-serif body, Orbitron display — one accent hierarchy) ──

/**
 * ZeroCopy typography — single visual hierarchy.
 *  • Display/Headline: Orbitron (brand) — geometric, distinctive
 *  • Body/Label: System sans-serif — readable, neutral, modern
 *  • ONE accent weight (SemiBold) for emphasis; Regular for body.
 *  • Line heights tuned for comfortable reading (1.4–1.5×).
 */
val ZcTypography = Typography(
  // Brand moments — large, geometric, Orbitron
  displayLarge = TextStyle(
    fontFamily = FontFamily.Monospace, // Orbitron not bundled; Monospace proxy
    fontSize = 48.sp, fontWeight = FontWeight.Black, letterSpacing = -0.5.sp
  ),
  displayMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = -0.25.sp
  ),
  displaySmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 28.sp, fontWeight = FontWeight.Bold
  ),

  // Section headers — Orbitron, SemiBold
  headlineLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 24.sp, fontWeight = FontWeight.Bold
  ),
  headlineMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 20.sp, fontWeight = FontWeight.SemiBold
  ),
  headlineSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 18.sp, fontWeight = FontWeight.SemiBold
  ),

  // Titles — Orbitron for UI chrome
  titleLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
  ),
  titleMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
  ),
  titleSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp, fontWeight = FontWeight.Medium
  ),

  // Body — SYSTEM SANS-SERIF (not monospace). Readable, neutral.
  bodyLarge = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal
  ),
  bodyMedium = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal
  ),
  bodySmall = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal
  ),

  // Labels — SansSerif, SemiBold for actionable text
  labelLarge = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
  ),
  labelMedium = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 12.sp, fontWeight = FontWeight.Medium
  ),
  labelSmall = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 10.sp, fontWeight = FontWeight.Medium
  )
)

@Composable
fun ZeroCopyTheme(darkTheme: Boolean = ThemeState.isDark, content: @Composable () -> Unit) {
  SideEffect { ThemeState.isDark = darkTheme }
  val colorScheme = if (darkTheme) DarkScheme else LightScheme
  MaterialTheme(colorScheme = colorScheme, typography = ZcTypography, shapes = Shapes(
    extraSmall = ZcShape.Xs,
    small = ZcShape.Sm,
    medium = ZcShape.Md,
    large = ZcShape.Lg,
    extraLarge = ZcShape.Xl
  )) { content() }
}

@Composable
fun currentPalette(): ZcPalette = if (ThemeState.isDark) ZcColors else ZcLightColors

interface ZcPalette {
  val Bg: Color
  val Surface: Color
  val Card: Color
  val CardLight: Color
  val Border: Color
  val Accent: Color
  val Accent2: Color
  val Cyan: Color
  val Red: Color
  val Amber: Color
  val Purple: Color
  val Text: Color
  val Text2: Color
  val Text3: Color
  val UserBg: Color
  val ThinkBg: Color
  val GradientStart: Color
  val GradientEnd: Color
  val GlowAccent: Color
  val GlowAccent2: Color
}