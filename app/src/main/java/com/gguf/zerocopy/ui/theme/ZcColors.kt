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
object ZcSemantic {
  // Primary actions, selection, focus — THE ONE ACCENT (violet)
  val Primary = ZcColors.Accent
  val PrimaryContainer = ZcColors.Accent.copy(alpha = 0.14f)
  val OnPrimary = ZcColors.Bg

  // Success states only — green
  val Success = ZcColors.Accent2
  val SuccessContainer = ZcColors.Accent2.copy(alpha = 0.14f)
  val OnSuccess = ZcColors.Bg

  // Destructive / error — hot violet
  val Error = ZcColors.Red
  val ErrorContainer = ZcColors.Red.copy(alpha = 0.14f)
  val OnError = ZcColors.Bg

  // AI/tech indicator — sky cyan
  val AiAccent = ZcColors.Cyan
  val AiAccentContainer = ZcColors.Cyan.copy(alpha = 0.14f)

  // Surfaces
  val Surface = ZcColors.Surface
  val SurfaceVariant = ZcColors.Card
  val SurfaceBright = ZcColors.CardLight
  val Border = ZcColors.Border
  val BorderSubtle = ZcColors.Border.copy(alpha = 0.5f)

  // Text hierarchy
  val OnSurface = ZcColors.Text
  val OnSurfaceVariant = ZcColors.Text2
  val OnSurfaceMuted = ZcColors.Text3

  // Chat-specific
  val UserBubble = ZcColors.UserBg
  val ThinkBubble = ZcColors.ThinkBg

  // Brand gradient (cyan → violet) — active states only
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

/**
 * Single brand gradient for the whole app (sky → violet), derived from the
 * active palette so dark and light themes stay coherent. Use for ACTIVE states
 * only (generating, loading, focus) — no glow at rest.
 */
fun ZcPalette.gradient(vertical: Boolean = false): Brush =
  if (vertical) Brush.verticalGradient(listOf(GradientStart, GradientEnd))
  else Brush.linearGradient(listOf(GradientStart, GradientEnd))
