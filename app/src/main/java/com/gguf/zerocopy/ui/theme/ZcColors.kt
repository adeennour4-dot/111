package com.gguf.zerocopy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * ZeroCopy "Aurora" palette v2 — evolved from the launcher-icon identity
 * (purple + green + cyan). Surfaces shift from blue-black to neutral
 * indigo-slate; accents are lifted for contrast on the lighter base;
 * danger stays a hot violet (no reds/ambers/yellows by design).
 *
 * Icon purple #8A70FF · icon green #2BE4A4 · sky cyan #46D6FF
 */
object ZcColors : ZcPalette {
  override val Bg = Color(0xFF090B12)
  override val Surface = Color(0xFF0F1219)
  override val Card = Color(0xFF141826)
  override val CardLight = Color(0xFF1A1F31)
  override val Border = Color(0xFF252C42)
  override val Accent = Color(0xFF8A70FF)      // electric violet — PRIMARY ACCENT
  override val Accent2 = Color(0xFF2BE4A4)     // spring green — SUCCESS ONLY
  override val Cyan = Color(0xFF46D6FF)
  override val Red = Color(0xFFE055FF)         // hot violet — DESTRUCTIVE/ERROR
  override val Amber = Color(0xFF46D6FF)       // cyan — ADDITIVE ACCENT
  override val Purple = Color(0xFFB06CFF)
  override val Text = Color(0xFFF3F4FB)
  override val Text2 = Color(0xFF9FA6C4)
  override val Text3 = Color(0xFF5F6683)
  override val UserBg = Color(0xFF191430)
  override val ThinkBg = Color(0xFF151827)
  override val GradientStart = Color(0xFF46D6FF)   // sky → violet brand gradient
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

/** Semantic color aliases — use these in components, NOT raw palette values. */
object ZcSemantic : ZcSemanticColors {
  // Primary actions, selection, focus �� THE ONE ACCENT (violet)
  override val Primary = ZcColors.Accent
  override val PrimaryContainer = ZcColors.Accent.copy(alpha = 0.14f)
  override val OnPrimary = ZcColors.Bg

  // Success states only — green
  override val Success = ZcColors.Accent2
  override val SuccessContainer = ZcColors.Accent2.copy(alpha = 0.14f)
  override val OnSuccess = ZcColors.Bg

  // Destructive / error — hot violet
  override val Error = ZcColors.Red
  override val ErrorContainer = ZcColors.Red.copy(alpha = 0.14f)
  override val OnError = ZcColors.Bg

  // AI/tech indicator — sky cyan
  override val AiAccent = ZcColors.Cyan
  override val AiAccentContainer = ZcColors.Cyan.copy(alpha = 0.14f)

  // Surfaces
  override val Surface = ZcColors.Surface
  override val SurfaceVariant = ZcColors.Card
  override val SurfaceBright = ZcColors.CardLight
  override val Border = ZcColors.Border
  override val BorderSubtle = ZcColors.Border.copy(alpha = 0.5f)

  // Text hierarchy
  override val OnSurface = ZcColors.Text
  override val OnSurfaceVariant = ZcColors.Text2
  override val OnSurfaceMuted = ZcColors.Text3

  // Chat-specific
  override val UserBubble = ZcColors.UserBg
  override val ThinkBubble = ZcColors.ThinkBg

  // Brand gradient (cyan → violet) — active states only
  override val GradientStart = ZcColors.GradientStart
  override val GradientEnd = ZcColors.GradientEnd
}

object ZcLightSemantic : ZcSemanticColors {
  override val Primary = ZcLightColors.Accent
  override val PrimaryContainer = ZcLightColors.Accent.copy(alpha = 0.14f)
  override val OnPrimary = ZcLightColors.Bg

  override val Success = ZcLightColors.Accent2
  override val SuccessContainer = ZcLightColors.Accent2.copy(alpha = 0.14f)
  override val OnSuccess = ZcLightColors.Bg

  override val Error = ZcLightColors.Red
  override val ErrorContainer = ZcLightColors.Red.copy(alpha = 0.14f)
  override val OnError = ZcLightColors.Bg

  override val AiAccent = ZcLightColors.Cyan
  override val AiAccentContainer = ZcLightColors.Cyan.copy(alpha = 0.14f)

  override val Surface = ZcLightColors.Surface
  override val SurfaceVariant = ZcLightColors.Card
  override val SurfaceBright = ZcLightColors.CardLight
  override val Border = ZcLightColors.Border
  override val BorderSubtle = ZcLightColors.Border.copy(alpha = 0.5f)

  override val OnSurface = ZcLightColors.Text
  override val OnSurfaceVariant = ZcLightColors.Text2
  override val OnSurfaceMuted = ZcLightColors.Text3

  override val UserBubble = ZcLightColors.UserBg
  override val ThinkBubble = ZcLightColors.ThinkBg

  override val GradientStart = ZcLightColors.GradientStart
  override val GradientEnd = ZcLightColors.GradientEnd
}

/**
 * Single brand gradient for the whole app (sky → violet), derived from the
 * active palette so dark and light themes stay coherent. Use for ACTIVE states
 * only (generating, loading, focus) — no glow at rest.
 */
fun ZcPalette.gradient(vertical: Boolean = false): Brush =
  if (vertical) Brush.verticalGradient(listOf(GradientStart, GradientEnd))
  else Brush.linearGradient(listOf(GradientStart, GradientEnd))

/** Semantic colors resolved for the current theme (dark or light). */
@Composable
fun currentSemantic(): ZcSemanticColors =
  if (ThemeState.isDark) ZcSemantic else ZcLightSemantic

interface ZcSemanticColors {
  // Primary actions, selection, focus — THE ONE ACCENT (violet)
  val Primary: Color
  val PrimaryContainer: Color
  val OnPrimary: Color

  // Success states only — green
  val Success: Color
  val SuccessContainer: Color
  val OnSuccess: Color

  // Destructive / error — hot violet
  val Error: Color
  val ErrorContainer: Color
  val OnError: Color

  // AI/tech indicator — sky cyan
  val AiAccent: Color
  val AiAccentContainer: Color

  // Surfaces
  val Surface: Color
  val SurfaceVariant: Color
  val SurfaceBright: Color
  val Border: Color
  val BorderSubtle: Color

  // Text hierarchy
  val OnSurface: Color
  val OnSurfaceVariant: Color
  val OnSurfaceMuted: Color

  // Chat-specific
  val UserBubble: Color
  val ThinkBubble: Color

  // Brand gradient (cyan → violet) — active states only
  val GradientStart: Color
  val GradientEnd: Color
}
