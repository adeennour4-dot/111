package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

private val LightScheme = lightColorScheme(
    background = ZcColors.LightBg,
    surface = ZcColors.LightSurface,
    surfaceVariant = ZcColors.LightCardLight,
    primary = ZcColors.Accent,
    secondary = ZcColors.Accent2,
    tertiary = ZcColors.Purple,
    onBackground = ZcColors.LightText,
    onSurface = ZcColors.LightText,
    onPrimary = ZcColors.Bg,
    outline = ZcColors.LightBorder
)

@Composable
fun ZeroCopyTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme) { content() }
}
