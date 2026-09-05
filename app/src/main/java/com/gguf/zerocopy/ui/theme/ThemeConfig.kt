package com.gguf.zerocopy.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

data class CustomColors(
    val primary: Color = Color(0xFF6750A4),
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFEADDFF),
    val onPrimaryContainer: Color = Color(0xFF21005D),
    val secondary: Color = Color(0xFF625B71),
    val onSecondary: Color = Color.White,
    val secondaryContainer: Color = Color(0xFFE8DEF8),
    val onSecondaryContainer: Color = Color(0xFF1D192B),
    val tertiary: Color = Color(0xFF7D5260),
    val onTertiary: Color = Color.White,
    val tertiaryContainer: Color = Color(0xFFFFD8E4),
    val onTertiaryContainer: Color = Color(0xFF31111D),
    val error: Color = Color(0xFFBA1A1A),
    val onError: Color = Color.White,
    val errorContainer: Color = Color(0xFFFFDAD6),
    val onErrorContainer: Color = Color(0xFF410002),
    val background: Color = Color(0xFF1C1B1F),
    val onBackground: Color = Color(0xFFE6E1E5),
    val surface: Color = Color(0xFF1C1B1F),
    val onSurface: Color = Color(0xFFE6E1E5),
    val surfaceVariant: Color = Color(0xFF49454F),
    val onSurfaceVariant: Color = Color(0xFFCAC4D0),
    val outline: Color = Color(0xFF938F99),
    val outlineVariant: Color = Color(0xFF49454F),
    val shadow: Color = Color.Black,
    val scrim: Color = Color.Black,
    val inverseSurface: Color = Color(0xFFE6E1E5),
    val inverseOnSurface: Color = Color(0xFF313033),
    val inversePrimary: Color = Color(0xFF6750A4),
    val surfaceTint: Color = Color(0xFFD0BCFF),
    val surfaceBright: Color = Color(0xFF49454F),
    val surfaceDim: Color = Color(0xFF1C1B1F),
    val primaryFixed: Color = Color(0xFFEADDFF),
    val onPrimaryFixed: Color = Color(0xFF21005D),
    val primaryFixedDim: Color = Color(0xFFD0BCFF),
    val onPrimaryFixedVariant: Color = Color(0xFF4F378B),
    val secondaryFixed: Color = Color(0xFFE8DEF8),
    val onSecondaryFixed: Color = Color(0xFF1D192B),
    val secondaryFixedDim: Color = Color(0xFFCCC2DC),
    val onSecondaryFixedVariant: Color = Color(0xFF4A4458),
    val tertiaryFixed: Color = Color(0xFFFFD8E4),
    val onTertiaryFixed: Color = Color(0xFF31111D),
    val tertiaryFixedDim: Color = Color(0xFFEFB8C8),
    val onTertiaryFixedVariant: Color = Color(0xFF633B48),
)

data class ShapeTokens(
    val extraSmall: Float = 4.dp.value,
    val small: Float = 8.dp.value,
    val medium: Float = 12.dp.value,
    val large: Float = 16.dp.value,
    val extraLarge: Float = 24.dp.value,
    val pill: Float = 100.dp.value,
    val circle: Float = 50.dp.value,
)

data class SpacingTokens(
    val xs: Float = 4.dp.value,
    val sm: Float = 8.dp.value,
    val md: Float = 16.dp.value,
    val lg: Float = 24.dp.value,
    val xl: Float = 32.dp.value,
    val xxl: Float = 48.dp.value,
)

enum class DensityLevel(val factor: Float, val label: String) {
    COMPACT(0.85f, "Compact"),
    COMFORTABLE(1.0f, "Comfortable"),
    SPACIOUS(1.15f, "Spacious"),
}

enum class AnimationIntensity(val factor: Float, val label: String) {
    NONE(0f, "None"),
    SUBTLE(0.5f, "Subtle"),
    NORMAL(1.0f, "Normal"),
    PLAYFUL(1.5f, "Playful"),
}

enum class ShapeStyle(val label: String, val tokens: ShapeTokens) {
    ROUNDED("Rounded", ShapeTokens(extraSmall = 4f, small = 8f, medium = 16f, large = 24f, extraLarge = 32f)),
    SHARP("Sharp", ShapeTokens(extraSmall = 0f, small = 0f, medium = 0f, large = 4f, extraLarge = 8f)),
    PILLED("Pilled", ShapeTokens(extraSmall = 100f, small = 100f, medium = 100f, large = 100f, extraLarge = 100f)),
    SOFT("Soft", ShapeTokens(extraSmall = 6f, small = 12f, medium = 20f, large = 28f, extraLarge = 36f)),
    CUSTOM("Custom", ShapeTokens()),
}

data class TypographyScale(
    val displayLarge: Float = 57.sp.value,
    val displayMedium: Float = 45.sp.value,
    val displaySmall: Float = 36.sp.value,
    val headlineLarge: Float = 32.sp.value,
    val headlineMedium: Float = 28.sp.value,
    val headlineSmall: Float = 24.sp.value,
    val titleLarge: Float = 22.sp.value,
    val titleMedium: Float = 16.sp.value,
    val titleSmall: Float = 14.sp.value,
    val bodyLarge: Float = 16.sp.value,
    val bodyMedium: Float = 14.sp.value,
    val bodySmall: Float = 12.sp.value,
    val labelLarge: Float = 14.sp.value,
    val labelMedium: Float = 12.sp.value,
    val labelSmall: Float = 11.sp.value,
)

data class ThemeConfig(
    val isDark: Boolean = true,
    val density: DensityLevel = DensityLevel.COMFORTABLE,
    val animationIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
    val shapeStyle: ShapeStyle = ShapeStyle.ROUNDED,
    val customShapeTokens: ShapeTokens = ShapeTokens(),
    val customColors: CustomColors? = null,
    val useCustomColors: Boolean = false,
    val presetIndex: Int = 0,
    val typographyScale: Float = 1.0f,
    val accentColorIndex: Int = 0,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val dynamicColor: Boolean = false,
)

object PresetThemes {
    val presets = listOf(
        ThemePreset(
            "Midnight",
            CustomColors(
                primary = Color(0xFF8A70FF),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF2D2558),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFF2BE4A4),
                onSecondary = Color.Black,
                secondaryContainer = Color(0xFF1A3A2A),
                onSecondaryContainer = Color.White,
                tertiary = Color(0xFF46D6FF),
                onTertiary = Color.Black,
                tertiaryContainer = Color(0xFF1A3A4A),
                onTertiaryContainer = Color.White,
                error = Color(0xFFE055FF),
                onError = Color.White,
                errorContainer = Color(0xFF4A1A2A),
                onErrorContainer = Color.White,
                background = Color(0xFF090B12),
                onBackground = Color(0xFFF3F4FB),
                surface = Color(0xFF0F1219),
                onSurface = Color(0xFFF3F4FB),
                surfaceVariant = Color(0xFF1A1F31),
                onSurfaceVariant = Color(0xFF9FA6C4),
                outline = Color(0xFF252C42),
                outlineVariant = Color(0xFF252C42).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFF3F4FB),
                inverseOnSurface = Color(0xFF090B12),
                inversePrimary = Color(0xFF8A70FF),
                surfaceTint = Color(0xFF8A70FF),
                surfaceBright = Color(0xFF1A1F31),
                surfaceDim = Color(0xFF090B12),
            ),
            ShapeStyle.ROUNDED
        ),
        ThemePreset(
            "Dawn",
            CustomColors(
                primary = Color(0xFF6750A4),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFEADDFF),
                onPrimaryContainer = Color(0xFF21005D),
                secondary = Color(0xFF625B71),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE8DEF8),
                onSecondaryContainer = Color(0xFF1D192B),
                tertiary = Color(0xFF7D5260),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFD8E4),
                onTertiaryContainer = Color(0xFF31111D),
                error = Color(0xFFBA1A1A),
                onError = Color.White,
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002),
                background = Color(0xFFF7F8FC),
                onBackground = Color(0xFF15171F),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF15171F),
                surfaceVariant = Color(0xFFEEF0F8),
                onSurfaceVariant = Color(0xFF555C74),
                outline = Color(0xFFE1E4F0),
                outlineVariant = Color(0xFFE1E4F0).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFF313033),
                inverseOnSurface = Color(0xFFF7F8FC),
                inversePrimary = Color(0xFF6750A4),
                surfaceTint = Color(0xFF6750A4),
                surfaceBright = Color(0xFFEEF0F8),
                surfaceDim = Color(0xFFF7F8FC),
            ),
            ShapeStyle.SOFT
        ),
        ThemePreset(
            "Forest",
            CustomColors(
                primary = Color(0xFF2E7D32),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFA5D6A7),
                onPrimaryContainer = Color(0xFF1B5E20),
                secondary = Color(0xFF388E3C),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFC8E6C9),
                onSecondaryContainer = Color(0xFF1B5E20),
                tertiary = Color(0xFF558B2F),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFDCEDC8),
                onTertiaryContainer = Color(0xFF33691E),
                error = Color(0xFFC62828),
                onError = Color.White,
                errorContainer = Color(0xFFFFCDD2),
                onErrorContainer = Color(0xFFB71C1C),
                background = Color(0xFF0D1B0D),
                onBackground = Color(0xFFE8F5E9),
                surface = Color(0xFF142814),
                onSurface = Color(0xFFE8F5E9),
                surfaceVariant = Color(0xFF1E3A1E),
                onSurfaceVariant = Color(0xFFA5D6A7),
                outline = Color(0xFF2E5D2E),
                outlineVariant = Color(0xFF2E5D2E).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFE8F5E9),
                inverseOnSurface = Color(0xFF0D1B0D),
                inversePrimary = Color(0xFF81C784),
                surfaceTint = Color(0xFF4CAF50),
                surfaceBright = Color(0xFF1E3A1E),
                surfaceDim = Color(0xFF0D1B0D),
            ),
            ShapeStyle.SOFT
        ),
        ThemePreset(
            "Ocean",
            CustomColors(
                primary = Color(0xFF0288D1),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFB3E5FC),
                onPrimaryContainer = Color(0xFF01579B),
                secondary = Color(0xFF0277BD),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFB3E5FC),
                onSecondaryContainer = Color(0xFF01579B),
                tertiary = Color(0xFF0097A7),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFB2EBF2),
                onTertiaryContainer = Color(0xFF006064),
                error = Color(0xFFEF5350),
                onError = Color.White,
                errorContainer = Color(0xFFFFCDD2),
                onErrorContainer = Color(0xFFC62828),
                background = Color(0xFF0A1A2A),
                onBackground = Color(0xFFE1F5FE),
                surface = Color(0xFF0F2434),
                onSurface = Color(0xFFE1F5FE),
                surfaceVariant = Color(0xFF163042),
                onSurfaceVariant = Color(0xFFB3E5FC),
                outline = Color(0xFF1E3F56),
                outlineVariant = Color(0xFF1E3F56).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFE1F5FE),
                inverseOnSurface = Color(0xFF0A1A2A),
                inversePrimary = Color(0xFF4FC3F7),
                surfaceTint = Color(0xFF29B6F6),
                surfaceBright = Color(0xFF163042),
                surfaceDim = Color(0xFF0A1A2A),
            ),
            ShapeStyle.ROUNDED
        ),
        ThemePreset(
            "Sunset",
            CustomColors(
                primary = Color(0xFFF57C00),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFCC80),
                onPrimaryContainer = Color(0xFFE65100),
                secondary = Color(0xFFEF6C00),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFFFCC80),
                onSecondaryContainer = Color(0xFFE65100),
                tertiary = Color(0xFFE65100),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFE0B2),
                onTertiaryContainer = Color(0xFFBF360C),
                error = Color(0xFFEF5350),
                onError = Color.White,
                errorContainer = Color(0xFFFFCDD2),
                onErrorContainer = Color(0xFFC62828),
                background = Color(0xFF2A1800),
                onBackground = Color(0xFFFFF3E0),
                surface = Color(0xFF3E2700),
                onSurface = Color(0xFFFFF3E0),
                surfaceVariant = Color(0xFF4E3400),
                onSurfaceVariant = Color(0xFFFFCC80),
                outline = Color(0xFF6B4700),
                outlineVariant = Color(0xFF6B4700).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFFFF3E0),
                inverseOnSurface = Color(0xFF2A1800),
                inversePrimary = Color(0xFFFFB74D),
                surfaceTint = Color(0xFFFF9800),
                surfaceBright = Color(0xFF4E3400),
                surfaceDim = Color(0xFF2A1800),
            ),
            ShapeStyle.PILLED
        ),
        ThemePreset(
            "Rose",
            CustomColors(
                primary = Color(0xFFE91E63),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFF8BBD0),
                onPrimaryContainer = Color(0xFF880E4F),
                secondary = Color(0xFFEC407A),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFF8BBD0),
                onSecondaryContainer = Color(0xFF880E4F),
                tertiary = Color(0xFFD81B60),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFCE4EC),
                onTertiaryContainer = Color(0xFF880E4F),
                error = Color(0xFFEF5350),
                onError = Color.White,
                errorContainer = Color(0xFFFFCDD2),
                onErrorContainer = Color(0xFFC62828),
                background = Color(0xFF2A0012),
                onBackground = Color(0xFFFCE4EC),
                surface = Color(0xFF3D0018),
                onSurface = Color(0xFFFCE4EC),
                surfaceVariant = Color(0xFF4E0020),
                onSurfaceVariant = Color(0xFFF8BBD0),
                outline = Color(0xFF6B002C),
                outlineVariant = Color(0xFF6B002C).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFFCE4EC),
                inverseOnSurface = Color(0xFF2A0012),
                inversePrimary = Color(0xFFF48FB1),
                surfaceTint = Color(0xFFF06292),
                surfaceBright = Color(0xFF4E0020),
                surfaceDim = Color(0xFF2A0012),
            ),
            ShapeStyle.SOFT
        ),
        ThemePreset(
            "Monochrome",
            CustomColors(
                primary = Color(0xFF757575),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFBDBDBD),
                onPrimaryContainer = Color(0xFF212121),
                secondary = Color(0xFF9E9E9E),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE0E0E0),
                onSecondaryContainer = Color(0xFF212121),
                tertiary = Color(0xFFBDBDBD),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFEEEEEE),
                onTertiaryContainer = Color(0xFF212121),
                error = Color(0xFF9E9E9E),
                onError = Color.White,
                errorContainer = Color(0xFFE0E0E0),
                onErrorContainer = Color(0xFF212121),
                background = Color(0xFF121212),
                onBackground = Color(0xFFFFFFFF),
                surface = Color(0xFF1E1E1E),
                onSurface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFF2D2D2D),
                onSurfaceVariant = Color(0xFFBDBDBD),
                outline = Color(0xFF424242),
                outlineVariant = Color(0xFF424242).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFFFFFFF),
                inverseOnSurface = Color(0xFF121212),
                inversePrimary = Color(0xFF757575),
                surfaceTint = Color(0xFF757575),
                surfaceBright = Color(0xFF2D2D2D),
                surfaceDim = Color(0xFF121212),
            ),
            ShapeStyle.SHARP
        ),
        ThemePreset(
            "Nord",
            CustomColors(
                primary = Color(0xFF88C0D0),
                onPrimary = Color(0xFF2E3440),
                primaryContainer = Color(0xFF434C5E),
                onPrimaryContainer = Color(0xFFD8DEE9),
                secondary = Color(0xFF81A1C1),
                onSecondary = Color(0xFF2E3440),
                secondaryContainer = Color(0xFF3B4252),
                onSecondaryContainer = Color(0xFFD8DEE9),
                tertiary = Color(0xFFB48EAD),
                onTertiary = Color(0xFF2E3440),
                tertiaryContainer = Color(0xFF434C5E),
                onTertiaryContainer = Color(0xFFD8DEE9),
                error = Color(0xFFBF616A),
                onError = Color(0xFF2E3440),
                errorContainer = Color(0xFF3B4252),
                onErrorContainer = Color(0xFFD8DEE9),
                background = Color(0xFF2E3440),
                onBackground = Color(0xFFD8DEE9),
                surface = Color(0xFF3B4252),
                onSurface = Color(0xFFD8DEE9),
                surfaceVariant = Color(0xFF434C5E),
                onSurfaceVariant = Color(0xFFD8DEE9),
                outline = Color(0xFF4C566A),
                outlineVariant = Color(0xFF4C566A).copy(alpha = 0.5f),
                shadow = Color.Black,
                scrim = Color.Black,
                inverseSurface = Color(0xFFD8DEE9),
                inverseOnSurface = Color(0xFF2E3440),
                inversePrimary = Color(0xFF88C0D0),
                surfaceTint = Color(0xFF88C0D0),
                surfaceBright = Color(0xFF434C5E),
                surfaceDim = Color(0xFF2E3440),
            ),
            ShapeStyle.ROUNDED
        ),
    )

    data class ThemePreset(
        val name: String,
        val colors: CustomColors,
        val shapeStyle: ShapeStyle
    )
}

class ThemeManager {
    private val _config = MutableStateFlow(ThemeConfig())
    val config = _config.asStateFlow()

    var onConfigChange: ((ThemeConfig) -> Unit)? = null

    init {
      // Observe config changes and persist to SettingsManager
      config.onEach { cfg ->
        com.gguf.zerocopy.data.local.SettingsManager.density = cfg.density.name
        com.gguf.zerocopy.data.local.SettingsManager.animationIntensity = cfg.animationIntensity.name
        com.gguf.zerocopy.data.local.SettingsManager.shapeStyle = cfg.shapeStyle.name
        com.gguf.zerocopy.data.local.SettingsManager.typographyScale = cfg.typographyScale
        com.gguf.zerocopy.data.local.SettingsManager.reducedMotion = cfg.reducedMotion
        com.gguf.zerocopy.data.local.SettingsManager.highContrast = cfg.highContrast
        com.gguf.zerocopy.data.local.SettingsManager.dynamicColor = cfg.dynamicColor
        com.gguf.zerocopy.data.local.SettingsManager.accentColorIndex = cfg.accentColorIndex
        com.gguf.zerocopy.data.local.SettingsManager.themePresetIndex = cfg.presetIndex
        com.gguf.zerocopy.data.local.SettingsManager.useCustomColors = cfg.useCustomColors
      }.launchIn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
    }

    fun updateConfig(block: ThemeConfig.() -> ThemeConfig) {
        val newConfig = _config.value.block()
        _config.value = newConfig
        onConfigChange?.invoke(newConfig)
    }

    fun setPreset(index: Int) {
        updateConfig {
            this.copy(presetIndex = index, useCustomColors = false)
        }
    }

    fun setCustomColors(colors: CustomColors) {
        updateConfig {
            this.copy(useCustomColors = true, customColors = colors)
        }
    }

    fun toggleDark() {
        updateConfig { this.copy(isDark = !isDark) }
    }

    fun setDensity(level: DensityLevel) {
        updateConfig { this.copy(density = level) }
    }

    fun setAnimationIntensity(level: AnimationIntensity) {
        updateConfig { this.copy(animationIntensity = level) }
    }

    fun setShapeStyle(style: ShapeStyle) {
        updateConfig { this.copy(shapeStyle = style) }
    }

    fun setCustomShapeTokens(tokens: ShapeTokens) {
        updateConfig { this.copy(shapeStyle = ShapeStyle.CUSTOM, customShapeTokens = tokens) }
    }

    fun setTypographyScale(scale: Float) {
        updateConfig { this.copy(typographyScale = scale.coerceIn(0.8f, 1.5f)) }
    }

    fun setReducedMotion(enabled: Boolean) {
        updateConfig { this.copy(reducedMotion = enabled) }
    }

    fun setHighContrast(enabled: Boolean) {
        updateConfig { this.copy(highContrast = enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        updateConfig { this.copy(dynamicColor = enabled) }
    }

    fun setAccentColorIndex(index: Int) {
        updateConfig { this.copy(accentColorIndex = index) }
    }
}

object ThemeManagerInstance {
    val instance = ThemeManager()
}