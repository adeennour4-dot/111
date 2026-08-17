package com.gguf.zerocopy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * ZeroCopy identity palette — ONLY the launcher-icon colors (purple + green),
 * plus cyan, and white/black. No reds, ambers or yellows: danger/warnings use
 * a hot purple, "additive" accents use cyan.
 *
 * Palette is FROZEN — the redesign keeps these exact values. All semantic
 * mappings (primary, error, success, etc.) derive from this set.
 *
 * Icon purple #7C5CFF · icon green #00E5A0 · cyan #00E5F0
 */
object ZcColors : ZcPalette {
  override val Bg = Color(0xFF05050F)
  override val Surface = Color(0xFF0D0D1A)
  override val Card = Color(0xFF101018)
  override val CardLight = Color(0xFF141422)
  override val Border = Color(0xFF262640)
  override val Accent = Color(0xFF7C5CFF)      // icon purple — PRIMARY ACCENT
  override val Accent2 = Color(0xFF00E5A0)     // icon green — SUCCESS ONLY
  override val Cyan = Color(0xFF00E5F0)
  override val Red = Color(0xFFC44DFF)         // hot purple — DESTRUCTIVE/ERROR
  override val Amber = Color(0xFF00E5F0)       // cyan — ADDITIVE ACCENT
  override val Purple = Color(0xFFA855F7)
  override val Text = Color(0xFFF0F0FF)
  override val Text2 = Color(0xFF9AA3C8)
  override val Text3 = Color(0xFF56566E)
  override val UserBg = Color(0xFF1A1030)
  override val ThinkBg = Color(0xFF141428)
  override val GradientStart = Color(0xFF00E5F0)   // cyan → purple brand gradient
  override val GradientEnd = Color(0xFF7C5CFF)
  override val GlowAccent = Color(0x507C5CFF)
  override val GlowAccent2 = Color(0x5000E5A0)
}

object ZcLightColors : ZcPalette {
  override val Bg = Color(0xFFF8F8FE)
  override val Surface = Color(0xFFFFFFFF)
  override val Card = Color(0xFFFFFFFF)
  override val CardLight = Color(0xFFEFEFFA)
  override val Border = Color(0xFFD5D5EC)
  override val Accent = Color(0xFF6B4EE6)
  override val Accent2 = Color(0xFF00C890)
  override val Cyan = Color(0xFF00AEC8)
  override val Red = Color(0xFFA82FE0)
  override val Amber = Color(0xFF00AEC8)
  override val Purple = Color(0xFF9333EA)
  override val Text = Color(0xFF101018)
  override val Text2 = Color(0xFF5A5A80)
  override val Text3 = Color(0xFF8E8EAC)
  override val UserBg = Color(0xFFEFEAFF)
  override val ThinkBg = Color(0xFFF2EFFC)
  override val GradientStart = Color(0xFF00AEC8)
  override val GradientEnd = Color(0xFF6B4EE6)
  override val GlowAccent = Color(0x306B4EE6)
  override val GlowAccent2 = Color(0x3000C890)
}

/** Semantic color aliases — use these in components, NOT raw palette values. */
object ZcSemantic {
  // Primary actions, selection, focus — THE ONE ACCENT (purple)
  val Primary = ZcColors.Accent
  val PrimaryContainer = ZcColors.Accent.copy(alpha = 0.14f)
  val OnPrimary = ZcColors.Bg

  // Success states only — green
  val Success = ZcColors.Accent2
  val SuccessContainer = ZcColors.Accent2.copy(alpha = 0.14f)
  val OnSuccess = ZcColors.Bg

  // Destructive / error — hot purple
  val Error = ZcColors.Red
  val ErrorContainer = ZcColors.Red.copy(alpha = 0.14f)
  val OnError = ZcColors.Bg

  // AI/tech indicator — cyan
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

  // Brand gradient (cyan → purple) — active states only
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
 * Single brand gradient for the whole app (cyan → purple), derived from the
 * active palette so dark and light themes stay coherent. Use for ACTIVE states
 * only (generating, loading, focus) — no glow at rest.
 */
fun ZcPalette.gradient(vertical: Boolean = false): Brush =
  if (vertical) Brush.verticalGradient(listOf(GradientStart, GradientEnd))
  else Brush.linearGradient(listOf(GradientStart, GradientEnd))