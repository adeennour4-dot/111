package com.gguf.zerocopy.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ZeroCopy identity palette — ONLY the launcher-icon colors (purple + green),
 * plus cyan, and white/black. No reds, ambers or yellows: danger/warnings use
 * a hot purple, "additive" accents use cyan.
 *
 * Icon purple #7C5CFF · icon green #00E5A0 · cyan #00E5F0
 */
object ZcColors : ZcPalette {
  override val Bg = Color(0xFF05050F)
  override val Surface = Color(0xFF0D0D1A)
  override val Card = Color(0xFF101018)
  override val CardLight = Color(0xFF141422)
  override val Border = Color(0xFF262640)
  override val Accent = Color(0xFF7C5CFF)      // icon purple
  override val Accent2 = Color(0xFF00E5A0)     // icon green
  override val Cyan = Color(0xFF00E5F0)
  override val Red = Color(0xFFC44DFF)         // hot purple (danger/stop)
  override val Amber = Color(0xFF00E5F0)       // cyan (additive accent)
  override val Purple = Color(0xFFA855F7)
  override val Text = Color(0xFFF0F0FF)
  override val Text2 = Color(0xFF9AA3C8)
  override val Text3 = Color(0xFF56566E)
  override val UserBg = Color(0xFF1A1030)
  override val ThinkBg = Color(0xFF141428)
  override val GradientStart = Color(0xFF7C5CFF)
  override val GradientEnd = Color(0xFF00E5A0)
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
  override val Red = Color(0xFFA82FE0)         // hot purple (danger/stop)
  override val Amber = Color(0xFF00AEC8)       // cyan (additive accent)
  override val Purple = Color(0xFF9333EA)
  override val Text = Color(0xFF101018)
  override val Text2 = Color(0xFF5A5A80)
  override val Text3 = Color(0xFF8E8EAC)
  override val UserBg = Color(0xFFEFEAFF)
  override val ThinkBg = Color(0xFFF2EFFC)
  override val GradientStart = Color(0xFF6B4EE6)
  override val GradientEnd = Color(0xFF00C890)
  override val GlowAccent = Color(0x306B4EE6)
  override val GlowAccent2 = Color(0x3000C890)
}
