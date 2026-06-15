package com.gguf.zerocopy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.gguf.zerocopy.ui.chat.ChatScreen
import com.gguf.zerocopy.ui.download.DownloadScreen
import com.gguf.zerocopy.ui.models.ModelListScreen
import com.gguf.zerocopy.ui.sessions.SessionListScreen
import com.gguf.zerocopy.ui.settings.SettingsScreen
import com.gguf.zerocopy.ui.theme.ZcColors
import com.gguf.zerocopy.ui.theme.ZeroCopyTheme
import kotlinx.coroutines.delay

enum class AppScreen { SPLASH, CHAT, SESSIONS, MODELS, DOWNLOAD, SETTINGS }

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      ZeroCopyTheme { AppRoot() }
    }
  }
}

@Composable
fun AppRoot() {
  val app = ZeroCopyApp.instance
  var screen by remember { mutableStateOf(AppScreen.SPLASH) }
  var loadedModelPath by remember { mutableStateOf("") }
  var loadedModelName by remember { mutableStateOf("") }
  var currentSessionId by remember { mutableStateOf<String?>(null) }

  when (screen) {
    AppScreen.SPLASH -> SplashScreen(onDone = { screen = AppScreen.CHAT })

    AppScreen.CHAT ->
      ChatScreen(
        modelPath = loadedModelPath,
        modelName = loadedModelName,
        sessionId = currentSessionId,
        onModelSelected = { path, name ->
          loadedModelPath = path
          loadedModelName = name
          currentSessionId = app.chatRepository.createSession("Chat - $name").id
        },
        onSettings = { screen = AppScreen.SETTINGS },
        onSessions = { screen = AppScreen.SESSIONS },
        onStore = { screen = AppScreen.DOWNLOAD }
      )

    AppScreen.SESSIONS ->
      SessionListScreen(
        onSessionSelected = { id ->
          currentSessionId = id
          screen = AppScreen.CHAT
        },
        onBack = { screen = AppScreen.CHAT }
      )

    AppScreen.MODELS ->
      ModelListScreen(
        onModelSelected = { path, name ->
          loadedModelPath = path
          loadedModelName = name
          currentSessionId = app.chatRepository.createSession("Chat - $name").id
          screen = AppScreen.CHAT
        },
        onBack = { screen = AppScreen.CHAT }
      )

    AppScreen.DOWNLOAD ->
      DownloadScreen(
        onModelSelected = { path, name ->
          loadedModelPath = path
          loadedModelName = name
          currentSessionId = app.chatRepository.createSession("Chat - $name").id
          screen = AppScreen.CHAT
        },
        onBack = { screen = AppScreen.CHAT }
      )

    AppScreen.SETTINGS ->
      SettingsScreen(
        onBack = { screen = AppScreen.CHAT }
      )
  }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
  val alpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    alpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    delay(400)
    alpha.animateTo(0f, animationSpec = tween(400))
    onDone()
  }

  Box(
    modifier = Modifier.fillMaxSize().background(ZcColors.Bg),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.alpha(alpha.value),
      horizontalAlignment = Alignment.CenterHorizontally
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
      Spacer(Modifier.height(20.dp))
      Text(
        "hello",
        fontSize = 28.sp,
        fontWeight = FontWeight.Light,
        color = ZcColors.Text2,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 4.sp
      )
    }
  }
}
