package com.gguf.zerocopy.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(onLoadModel: (String, String) -> Unit, onDownload: () -> Unit) {
  val alpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    alpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    delay(600)
  }

  Box(
    modifier = Modifier.fillMaxSize().background(ZcColors.Bg),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.alpha(alpha.value),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        modifier = Modifier
          .size(100.dp)
          .clip(RoundedCornerShape(28.dp))
          .background(
            Brush.linearGradient(listOf(ZcColors.GradientStart, ZcColors.GradientEnd))
          ),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "ZC",
          fontSize = 36.sp,
          fontWeight = FontWeight.Black,
          color = Color.White,
          fontFamily = FontFamily.Monospace
        )
      }
    }
  }
}
