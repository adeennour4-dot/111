package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
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

@Composable
fun ZeroCopyTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = DarkScheme) { content() }
}







