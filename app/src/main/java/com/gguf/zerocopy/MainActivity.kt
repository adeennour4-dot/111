package com.gguf.zerocopy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gguf.zerocopy.ui.chat.ChatScreen
import com.gguf.zerocopy.ui.download.DownloadScreen
import com.gguf.zerocopy.ui.models.ModelListScreen
import com.gguf.zerocopy.ui.sessions.SessionListScreen
import com.gguf.zerocopy.ui.settings.SettingsScreen
import com.gguf.zerocopy.ui.theme.ZeroCopyTheme
import com.gguf.zerocopy.ui.welcome.WelcomeScreen

enum class AppScreen { WELCOME, CHAT, SESSIONS, MODELS, DOWNLOAD, SETTINGS }

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
  var screen by remember { mutableStateOf(AppScreen.WELCOME) }
  var loadedModelPath by remember { mutableStateOf("") }
  var loadedModelName by remember { mutableStateOf("") }
  var currentSessionId by remember { mutableStateOf<String?>(null) }

  when (screen) {
    AppScreen.WELCOME ->
      WelcomeScreen(
        onLoadModel = { path, name ->
          loadedModelPath = path
          loadedModelName = name
          currentSessionId = app.chatRepository.createSession("Chat - $name").id
          screen = AppScreen.CHAT
        },
        onDownload = { screen = AppScreen.DOWNLOAD }
      )
    AppScreen.CHAT ->
      ChatScreen(
        modelPath = loadedModelPath,
        modelName = loadedModelName,
        sessionId = currentSessionId,
        onBack = { screen = AppScreen.WELCOME },
        onSettings = { screen = AppScreen.SETTINGS },
        onModels = { screen = AppScreen.MODELS },
        onSessions = { screen = AppScreen.SESSIONS }
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
        onBack = { screen = AppScreen.WELCOME }
      )
    AppScreen.SETTINGS ->
      SettingsScreen(
        onBack = { screen = AppScreen.CHAT }
      )
  }
}
