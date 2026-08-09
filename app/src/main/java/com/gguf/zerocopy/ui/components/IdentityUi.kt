package com.gguf.zerocopy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Identity colors — the launcher-icon palette (purple + green) plus cyan. */
val IdentityCyan = Color(0xFF00E5F0)
val IdentityGreen = Color(0xFF00E5A0)
val IdentityPurple = Color(0xFF7C5CFF)

val IdentityGradient = listOf(IdentityCyan, IdentityGreen, IdentityPurple)
val IdentitySweepBrush = Brush.sweepGradient(listOf(IdentityCyan, IdentityGreen, IdentityPurple, IdentityCyan))
val IdentityBorderBrush = Brush.linearGradient(IdentityGradient)

/**
 * Black/white chat bubble wrapped in the app's cyan→green→purple gradient
 * ring. While [circulating] (a response is being generated) the gradient
 * sweeps around the outline; otherwise it sits as a static gradient border.
 * The bubble fill is [bubbleColor] — near-black in dark mode, white in light.
 */
@Composable
fun GradientBubbleBox(
    circulating: Boolean,
    bubbleColor: Color,
    shape: Shape,
    borderWidth: androidx.compose.ui.unit.Dp = 1.5.dp,
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
