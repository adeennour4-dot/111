package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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

@Composable
fun ZeroCopyTheme(content: @Composable () -> Unit) {
  val colorScheme = if (ThemeState.isDark) DarkScheme else LightScheme
  MaterialTheme(colorScheme = colorScheme) { content() }
}

val ZcColors.current: ZcPalette
  @Composable get() = if (ThemeState.isDark) ZcColors else ZcLightColors

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
