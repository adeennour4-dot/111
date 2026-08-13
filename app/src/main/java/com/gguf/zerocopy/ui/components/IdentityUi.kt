package com.gguf.zerocopy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily

/** Identity colors — the launcher-icon palette (purple + green) plus cyan. */
val IdentityCyan = Color(0xFF00E5F0)
val IdentityGreen = Color(0xFF00E5A0)
val IdentityPurple = Color(0xFF7C5CFF)

val IdentityGradient = listOf(IdentityCyan, IdentityPurple)
val IdentitySweepBrush = Brush.sweepGradient(listOf(IdentityCyan, IdentityPurple, IdentityCyan))
val IdentityBorderBrush = Brush.linearGradient(IdentityGradient)

/** Futuristic display font (Orbitron) for headers, names and labels. */
val FuturisticFont = FontFamily(Font(R.font.orbitron))

/**
 * Black/white chat bubble wrapped in the app's cyan→purple gradient
 * ring. While [circulating] (a response is being generated) the gradient
 * sweeps around the outline; otherwise it sits as a static gradient border.
 * The bubble fill is [bubbleColor] — near-black in dark mode, white in light.
 */
@Composable
fun GradientBubbleBox(
    circulating: Boolean,
    bubbleColor: Color,
    shape: Shape,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val angle = rememberCirculatingAngle(circulating)
    if (circulating) {
        Box(Modifier.clip(shape)) {
            // Rotating gradient field, oversized so rotation never exposes corners.
            Box(
                Modifier.matchParentSize().scale(1.5f)
                    .graphicsLayer { rotationZ = angle }
                    .background(IdentitySweepBrush)
            )
            // Content inset by borderWidth → the exposed edge is the moving ring.
            Box(
                Modifier.padding(borderWidth)
                    .background(bubbleColor, shape)
                    .clip(shape)
            ) { content() }
        }
    } else {
        Box(
            Modifier
                .border(borderWidth, IdentityBorderBrush, shape)
                .background(bubbleColor, shape)
                .clip(shape)
        ) { content() }
    }
}

@Composable
private fun rememberCirculatingAngle(enabled: Boolean): Float {
    if (!enabled) return 0f
    val transition = rememberInfiniteTransition(label = "circulatingGradient")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    return angle
}

/**
 * Typing/"thinking" indicator: three pulsing dots in the identity gradient
 * (cyan → green → purple).
 */
@Composable
fun GradientThinkingDots(
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 7.dp
) {
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    Row(modifier = modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IdentityGradient.forEachIndexed { index, color ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 130),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Circular "thinking" indicator — the gradient ring sweeps around the circle
 * while the center dot breathes. Used in chat bubbles while a reply streams.
 */
@Composable
fun GradientThinkingCircle(size: androidx.compose.ui.unit.Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "thinkingCircle")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "thinkRing"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "thinkDot"
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .border(0.2.dp, IdentityBorderBrush, CircleShape)
        )
        Box(
            Modifier
                .size(size * 0.4f)
                .background(IdentityGreen.copy(alpha = pulse), CircleShape)
        )
    }
}

/**
 * Circular "searching" indicator — gradient ring sweeps around a search glyph.
 * Used while the app researches / searches (Invent libraries, web search).
 */
@Composable
fun GradientSearchingCircle(size: androidx.compose.ui.unit.Dp = 26.dp) {
    val transition = rememberInfiniteTransition(label = "searchingCircle")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "searchRing"
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .border(0.2.dp, IdentityBorderBrush, CircleShape)
        )
        Icon(
            Icons.Outlined.Search,
            contentDescription = "Searching",
            tint = IdentityCyan,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/**
 * Circular attach (paperclip) button with the identity gradient ring.
 * Lights up with a full gradient fill when [active] (an attachment is set).
 */
@Composable
fun ClipCircleIcon(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    fill: Color = Color(0xFF0B0E13),
    active: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(0.2.dp, if (active) IdentitySweepBrush else IdentityBorderBrush, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.AttachFile,
            contentDescription = "Attach",
            tint = if (active) IdentityGreen else IdentityCyan,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * Polished, on-brand pill button. Fully circular, soft tinted fill with a
 * hairline gradient-ring border, and the standard Material press ripple.
 * Use [ghost] = true for secondary / paired actions (transparent fill).
 */
@Composable
fun ZcPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = IdentityCyan,
    ghost: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (ghost) Color.Transparent else tint.copy(alpha = 0.14f),
        border = BorderStroke(0.2.dp, tint.copy(alpha = 0.5f)),
    ) {
        if (label != null) {
            Text(
                label,
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FuturisticFont,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}
