package com.gguf.zerocopy.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
  val colors = currentPalette()
  val titleAlpha = remember { Animatable(0f) }
  val taglineAlpha = remember { Animatable(0f) }
  val featuresAlpha = remember { Animatable(0f) }
  val buttonAlpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    titleAlpha.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    delay(200)
    taglineAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    delay(200)
    featuresAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    delay(300)
    buttonAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
  }

  Box(
    modifier = Modifier.fillMaxSize().background(colors.Bg),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Spacer(Modifier.weight(1f))

      Box(modifier = Modifier.alpha(titleAlpha.value)) {
        Text(
          "ZeroCopy",
          fontSize = 42.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace,
          style = TextStyle(brush = Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd)))
        )
      }

      Spacer(Modifier.height(8.dp))

      Box(modifier = Modifier.alpha(taglineAlpha.value)) {
        Text(
          "Private AI. On Your Device.",
          fontSize = 15.sp,
          color = colors.Text2,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Light,
          letterSpacing = 1.sp
        )
      }

      Spacer(Modifier.height(48.dp))

      Box(modifier = Modifier.alpha(featuresAlpha.value)) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
          FeatureItem(
            icon = Icons.Outlined.Memory,
            title = "Local Inference",
            subtitle = "Runs entirely on-device"
          )
          FeatureItem(
            icon = Icons.Outlined.Extension,
            title = "Multiple Engines",
            subtitle = "llama.cpp, MNN, LiteRT"
          )
          FeatureItem(
            icon = Icons.Outlined.Hub,
            title = "Chat & Server",
            subtitle = "Chat interface or API server"
          )
          FeatureItem(
            icon = Icons.Outlined.Lock,
            title = "Privacy First",
            subtitle = "Your data never leaves"
          )
        }
      }

      Spacer(Modifier.weight(1f))

      Box(modifier = Modifier.alpha(buttonAlpha.value).padding(bottom = 48.dp)) {
        Button(
          onClick = onDone,
          modifier = Modifier.fillMaxWidth().height(52.dp),
          shape = RoundedCornerShape(16.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = colors.Accent
          )
        ) {
          Text(
            "Get Started",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}

@Composable
private fun FeatureItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String
) {
  val colors = currentPalette()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    Box(
      modifier = Modifier.size(44.dp).background(
        Brush.linearGradient(listOf(colors.GradientStart.copy(alpha = 0.15f), colors.GradientEnd.copy(alpha = 0.15f))),
        RoundedCornerShape(12.dp)
      ),
      contentAlignment = Alignment.Center
    ) {
      Icon(icon, null, tint = colors.Accent, modifier = Modifier.size(24.dp))
    }
    Spacer(Modifier.width(16.dp))
    Column {
      Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.Text,
        fontFamily = FontFamily.Monospace
      )
      Text(
        subtitle,
        fontSize = 12.sp,
        color = colors.Text3,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}
