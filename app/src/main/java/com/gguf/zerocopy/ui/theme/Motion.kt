package com.gguf.zerocopy.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.min

/**
 * Single motion language for ZeroCopy — durations (ms) + signature easings.
 * Part of the one-theme system: every transition reads from here so motion
 * feels coherent across the whole app.
 */
object ZcMotion {
    val xxs = 130
    val xs = 190
    val sm = 250
    val md = 330
    val lg = 460
    val enter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)   // expressive deceleration
    val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f) // Android standard
    val emphasis = CubicBezierEasing(0.2f, 0f, 0f, 1f)   // snappy
}

/**
 * Mount entrance: gentle fade + slide, staggered by [index] for lists/grids.
 * Plays once when first composed.
 */
@Composable
fun ZcEnter(
    index: Int = 0,
    vertical: Boolean = true,
    staggerMs: Int = 55,
    content: @Composable () -> Unit
) {
    val delayMs = min(index, 12) * staggerMs
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(ZcMotion.sm, delayMillis = delayMs, easing = ZcMotion.enter)) +
                if (vertical) expandVertically(tween(ZcMotion.sm, delayMillis = delayMs, easing = ZcMotion.enter))
                else expandHorizontally(tween(ZcMotion.sm, delayMillis = delayMs, easing = ZcMotion.enter)),
        content = { content() }
    )
}

/**
 * Animated brand gradient field — a slow-rotating cyan→purple wash used behind
 * headers / heroes. Oversized + clipped by the caller so rotation never exposes
 * edges. Reads the active palette, so it is correct in both themes.
 */
@Composable
fun ZcGradientField(modifier: Modifier = Modifier, alpha: Float = 0.16f) {
    val transition = rememberInfiniteTransition(label = "zcGradientField")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)))
    val palette = currentPalette()
    Box(
        modifier
            .graphicsLayer { scaleX = 1.6f; scaleY = 1.6f; rotationZ = angle }
            .background(
                Brush.linearGradient(
                    listOf(palette.GradientStart.copy(alpha = alpha), palette.GradientEnd.copy(alpha = alpha))
                )
            )
    ) {}
}
