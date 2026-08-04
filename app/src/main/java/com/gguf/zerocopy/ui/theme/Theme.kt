package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager

object ThemeState {
  var isDark by mutableStateOf(SettingsManager.isDarkTheme)
}

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
    outlineVariant = ZcColors.Border.copy(alpha = 0.5f)
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
    outlineVariant = ZcLightColors.Border.copy(alpha = 0.5f)
  )

/**
 * ZeroCopy terminal-style typography.
 * Monospace everywhere keeps the identity, but with legible sizes:
 * body 12.5–14sp, labels ≥9sp (the app previously used 7–11sp everywhere).
 */
val ZcTypography = Typography(
  displaySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp),
  headlineMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold),
  titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold),
  titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
  titleSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
  bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
  bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
  bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
  labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
  labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
  labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
)

@Composable
fun ZeroCopyTheme(content: @Composable () -> Unit) {
  val colorScheme = if (ThemeState.isDark) DarkScheme else LightScheme
  MaterialTheme(colorScheme = colorScheme, typography = ZcTypography) { content() }
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
