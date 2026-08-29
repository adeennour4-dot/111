package com.gguf.zerocopy.ui.theme

import androidx.compose.material3.ColorScheme
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

object ZcShape {
    val Xs = RoundedCornerShape(6.dp)
    val Sm = RoundedCornerShape(12.dp)
    val Md = RoundedCornerShape(18.dp)
    val Lg = RoundedCornerShape(24.dp)
    val Xl = RoundedCornerShape(32.dp)
    val Pill = RoundedCornerShape(50)
    val Circle = RoundedCornerShape(50)
}

object ZcSpace {
    val Xxs = 2.dp
    val Xs  = 4.dp
    val Sm  = 8.dp
    val Md  = 12.dp
    val Lg  = 16.dp
    val Xl  = 24.dp
    val Xxl = 32.dp
    val Xxxl = 48.dp
}

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

object ZcColors : ZcPalette {
    override val Bg = Color(0xFF090B12)
    override val Surface = Color(0xFF0F1219)
    override val Card = Color(0xFF141826)
    override val CardLight = Color(0xFF1A1F31)
    override val Border = Color(0xFF252C42)
    override val Accent = Color(0xFF8A70FF)
    override val Accent2 = Color(0xFF2BE4A4)
    override val Cyan = Color(0xFF46D6FF)
    override val Red = Color(0xFFE055FF)
    override val Amber = Color(0xFF46D6FF)
    override val Purple = Color(0xFFB06CFF)
    override val Text = Color(0xFFF3F4FB)
    override val Text2 = Color(0xFF9FA6C4)
    override val Text3 = Color(0xFF5F6683)
    override val UserBg = Color(0xFF191430)
    override val ThinkBg = Color(0xFF151827)
    override val GradientStart = Color(0xFF46D6FF)
    override val GradientEnd = Color(0xFF8A70FF)
    override val GlowAccent = Color(0x558A70FF)
    override val GlowAccent2 = Color(0x552BE4A4)
}

object ZcLightColors : ZcPalette {
    override val Bg = Color(0xFFF7F8FC)
    override val Surface = Color(0xFFFFFFFF)
    override val Card = Color(0xFFFFFFFF)
    override val CardLight = Color(0xFFEEF0F8)
    override val Border = Color(0xFFE1E4F0)
    override val Accent = Color(0xFF6A50E8)
    override val Accent2 = Color(0xFF00B384)
    override val Cyan = Color(0xFF0899C8)
    override val Red = Color(0xFFB23BD9)
    override val Amber = Color(0xFF0899C8)
    override val Purple = Color(0xFF8B3FE8)
    override val Text = Color(0xFF15171F)
    override val Text2 = Color(0xFF555C74)
    override val Text3 = Color(0xFF8A90A6)
    override val UserBg = Color(0xFFEBE8FD)
    override val ThinkBg = Color(0xFFF0F1F7)
    override val GradientStart = Color(0xFF0899C8)
    override val GradientEnd = Color(0xFF6A50E8)
    override val GlowAccent = Color(0x356A50E8)
    override val GlowAccent2 = Color(0x3500B384)
}

object ZcSemantic {
    val Primary = ZcColors.Accent
    val PrimaryContainer = ZcColors.Accent.copy(alpha = 0.14f)
    val OnPrimary = ZcColors.Bg
    val Success = ZcColors.Accent2
    val SuccessContainer = ZcColors.Accent2.copy(alpha = 0.14f)
    val OnSuccess = ZcColors.Bg
    val Error = ZcColors.Red
    val ErrorContainer = ZcColors.Red.copy(alpha = 0.14f)
    val OnError = ZcColors.Bg
    val AiAccent = ZcColors.Cyan
    val AiAccentContainer = ZcColors.Cyan.copy(alpha = 0.14f)
    val Surface = ZcColors.Surface
    val SurfaceVariant = ZcColors.Card
    val SurfaceBright = ZcColors.CardLight
    val Border = ZcColors.Border
    val BorderSubtle = ZcColors.Border.copy(alpha = 0.5f)
    val OnSurface = ZcColors.Text
    val OnSurfaceVariant = ZcColors.Text2
    val OnSurfaceMuted = ZcColors.Text3
    val UserBubble = ZcColors.UserBg
    val ThinkBubble = ZcColors.ThinkBg
    val GradientStart = ZcColors.GradientStart
    val GradientEnd = ZcColors.GradientEnd
}

object ZcLightSemantic {
    val Primary = ZcLightColors.Accent
    val PrimaryContainer = ZcLightColors.Accent.copy(alpha = 0.14f)
    val OnPrimary = ZcLightColors.Bg
    val Success = ZcLightColors.Accent2
    val SuccessContainer = ZcLightColors.Accent2.copy(alpha = 0.14f)
    val OnSuccess = ZcLightColors.Bg
    val Error = ZcLightColors.Red
    val ErrorContainer = ZcLightColors.Red.copy(alpha = 0.14f)
    val OnError = ZcLightColors.Bg
    val AiAccent = ZcLightColors.Cyan
    val AiAccentContainer = ZcLightColors.Cyan.copy(alpha = 0.14f)
    val Surface = ZcLightColors.Surface
    val SurfaceVariant = ZcLightColors.Card
    val SurfaceBright = ZcLightColors.CardLight
    val Border = ZcLightColors.Border
    val BorderSubtle = ZcLightColors.Border.copy(alpha = 0.5f)
    val OnSurface = ZcLightColors.Text
    val OnSurfaceVariant = ZcLightColors.Text2
    val OnSurfaceMuted = ZcLightColors.Text3
    val UserBubble = ZcLightColors.UserBg
    val ThinkBubble = ZcLightColors.ThinkBg
    val GradientStart = ZcLightColors.GradientStart
    val GradientEnd = ZcLightColors.GradientEnd
}

fun ZcPalette.gradient(vertical: Boolean = false) =
    if (vertical) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(GradientStart, GradientEnd))
    else androidx.compose.ui.graphics.Brush.linearGradient(listOf(GradientStart, GradientEnd))

@Composable
fun ZeroCopyTheme(
    darkTheme: Boolean = ThemeState.isDark,
    content: @Composable () -> Unit
) {
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

private val DarkScheme = darkColorScheme(
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

private val LightScheme = lightColorScheme(
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

val ZcTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun currentPalette(): ZcPalette = if (ThemeState.isDark) ZcColors else ZcLightColors