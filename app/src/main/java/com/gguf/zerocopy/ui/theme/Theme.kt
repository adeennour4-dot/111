package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gguf.zerocopy.data.local.SettingsManager

object ThemeState {
  var isDark by mutableStateOf(SettingsManager.isDarkTheme)
}

private val DarkScheme =
  darkColorScheme(
    background = ZcColors.Bg,
    surface = ZcColors.Surface,
    surfaceVariant = ZcColors.Card,
    primary = ZcColors.Accent,
    secondary = ZcColors.Accent2,
    tertiary = ZcColors.Purple,
    onBackground = ZcColors.Text,
    onSurface = ZcColors.Text,
    onPrimary = ZcColors.Bg,
    outline = ZcColors.Border
  )

private val LightScheme =
  lightColorScheme(
    background = ZcLightColors.Bg,
    surface = ZcLightColors.Surface,
    surfaceVariant = ZcLightColors.Card,
    primary = ZcLightColors.Accent,
    secondary = ZcLightColors.Accent2,
    tertiary = ZcLightColors.Purple,
    onBackground = ZcLightColors.Text,
    onSurface = ZcLightColors.Text,
    onPrimary = ZcLightColors.Bg,
    outline = ZcLightColors.Border
  )

@Composable
fun ZeroCopyTheme(content: @Composable () -> Unit) {
  val colorScheme = if (ThemeState.isDark) DarkScheme else LightScheme
  MaterialTheme(colorScheme = colorScheme) { content() }
}
