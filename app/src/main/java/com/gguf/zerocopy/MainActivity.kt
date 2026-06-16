package com.gguf.zerocopy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.chat.ChatScreen
import com.gguf.zerocopy.ui.cloud.CloudScreen
import com.gguf.zerocopy.ui.download.DownloadScreen
import com.gguf.zerocopy.ui.models.ModelListScreen
import com.gguf.zerocopy.ui.settings.SettingsScreen
import com.gguf.zerocopy.ui.theme.ZeroCopyTheme
import com.gguf.zerocopy.ui.welcome.WelcomeScreen

enum class AppScreen { WELCOME, CHAT, MODELS, DOWNLOAD, SETTINGS, CLOUD }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var isDark by remember { mutableStateOf(SettingsManager.isDarkTheme) }
            ZeroCopyTheme(darkTheme = isDark) {
                AppRoot(
                    onThemeToggle = {
                        isDark = !isDark
                        SettingsManager.isDarkTheme = isDark
                    }
                )
            }
        }
    }
}

@Composable
fun AppRoot(onThemeToggle: () -> Unit) {
    val app = ZeroCopyApp.instance
    var screen by remember { mutableStateOf(AppScreen.WELCOME) }
    var loadedModelPath by remember { mutableStateOf("") }
    var loadedModelName by remember { mutableStateOf("") }
    var currentSessionId by remember { mutableStateOf<String?>(null) }

    when (screen) {
        AppScreen.WELCOME -> WelcomeScreen(
            onLoadModel = { path, name ->
                loadedModelPath = path
                loadedModelName = name
                currentSessionId = null
                screen = AppScreen.CHAT
            },
            onDownload = { screen = AppScreen.DOWNLOAD }
        )
        AppScreen.CHAT -> ChatScreen(
            modelPath = loadedModelPath,
            modelName = loadedModelName,
            sessionId = currentSessionId,
            onBack = {
                if (app.engineManager.getActiveEngine()?.isModelLoaded == true) {
                    screen = AppScreen.CHAT
                } else {
                    screen = AppScreen.WELCOME
                }
            },
            onSettings = { screen = AppScreen.SETTINGS },
            onModels = { screen = AppScreen.MODELS },
            onCloud = { screen = AppScreen.CLOUD }
        )
        AppScreen.MODELS -> ModelListScreen(
            onModelSelected = { path, name ->
                loadedModelPath = path
                loadedModelName = name
                currentSessionId = null
                screen = AppScreen.CHAT
            },
            onBack = { screen = AppScreen.CHAT }
        )
        AppScreen.DOWNLOAD -> DownloadScreen(
            onModelSelected = { path, name ->
                loadedModelPath = path
                loadedModelName = name
                currentSessionId = null
                screen = AppScreen.CHAT
            },
            onBack = { screen = AppScreen.WELCOME }
        )
        AppScreen.SETTINGS -> SettingsScreen(
            onBack = { screen = AppScreen.CHAT },
            onThemeToggle = onThemeToggle
        )
        AppScreen.CLOUD -> CloudScreen(
            onBack = { screen = AppScreen.CHAT }
        )
    }
}
