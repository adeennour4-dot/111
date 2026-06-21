package com.gguf.zerocopy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.chat.ChatScreen
import com.gguf.zerocopy.ui.models.ModelListScreen
import com.gguf.zerocopy.ui.sessions.SessionListScreen
import com.gguf.zerocopy.ui.rag.RagScreen
import com.gguf.zerocopy.ui.settings.SettingsScreen
import com.gguf.zerocopy.ui.theme.ZeroCopyTheme
import com.gguf.zerocopy.ui.theme.currentPalette
import com.gguf.zerocopy.ui.welcome.WelcomeScreen
import kotlinx.coroutines.delay

data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
  NavItem("Chat", Icons.Outlined.Chat),
  NavItem("Models", Icons.Outlined.SmartToy),
  NavItem("RAG", Icons.Outlined.LibraryBooks),
  NavItem("Settings", Icons.Filled.Settings)
)

class MainActivity : ComponentActivity() {
  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { _ -> }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    requestNotificationPermission()
    setContent { ZeroCopyTheme { AppRoot() } }
  }

  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }
}

@Composable
fun AppRoot() {
  val app = ZeroCopyApp.instance
  var showSplash by remember { mutableStateOf(true) }
  var showWelcome by rememberSaveable { mutableStateOf(!SettingsManager.welcomeDone) }
  var loadedModelPath by remember { mutableStateOf(SettingsManager.lastModelPath) }
  var loadedModelName by remember { mutableStateOf(SettingsManager.lastModelName) }
  var currentSessionId by remember { mutableStateOf<String?>(SettingsManager.currentSessionId.ifEmpty { null }) }
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  var showSessionList by remember { mutableStateOf(false) }
  var pendingModelSwitch by remember { mutableStateOf<Pair<String, String>?>(null) }

  // Restore last session on startup
  LaunchedEffect(Unit) {
    val lastSessionId = SettingsManager.currentSessionId
    if (lastSessionId.isNotEmpty() && app.chatRepository.sessionExists(lastSessionId)) {
      currentSessionId = lastSessionId
      app.chatRepository.selectSession(lastSessionId)
    }
  }

  if (showSplash) {
    SplashScreen(onDone = { showSplash = false })
    return
  }

  if (showWelcome) {
    WelcomeScreen(onDone = {
      SettingsManager.welcomeDone = true
      showWelcome = false
    })
    return
  }

  Scaffold(
    bottomBar = {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
      ) {
        navItems.forEachIndexed { idx, item ->
          NavigationBarItem(
            selected = selectedTab == idx,
            onClick = { selectedTab = idx },
            icon = { Icon(item.icon, item.label) },
            label = { Text(item.label, fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MaterialTheme.colorScheme.primary,
              unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
          )
        }
      }
    }
  ) { innerPad ->
    Box(modifier = Modifier.padding(innerPad).fillMaxSize()) {
      when (selectedTab) {
        0 -> {
          if (showSessionList) {
            SessionListScreen(
              onSessionSelected = { session ->
                currentSessionId = session.id
                SettingsManager.currentSessionId = session.id
                if (session.modelPath.isNotEmpty()) { loadedModelPath = session.modelPath; loadedModelName = session.modelName }
                app.chatRepository.selectSession(session.id)
                showSessionList = false
              },
              onBack = { showSessionList = false }
            )
          } else {
            ChatScreen(
              modelPath = loadedModelPath, modelName = loadedModelName,
              sessionId = currentSessionId,
              onModelSelected = { path, name ->
                loadedModelPath = path; loadedModelName = name
                SettingsManager.lastModelPath = path; SettingsManager.lastModelName = name
                if (currentSessionId != null && app.chatRepository.sessionExists(currentSessionId!!)) {
                  app.chatRepository.updateSessionModel(currentSessionId!!, path, name)
                  val existing = app.chatRepository.sessions.value.find { it.id == currentSessionId }
                  if (existing != null) app.chatRepository.renameSession(currentSessionId!!, "Chat - $name")
                  else currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
                } else currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
              },
              onSettings = { selectedTab = 3 },
              onSessions = { showSessionList = true },
              onRag = { selectedTab = 2 }
            )
          }
        }
        1 -> ModelListScreen(
          onModelSelected = { path, name ->
            if (currentSessionId != null && app.chatRepository.sessionExists(currentSessionId!!)) {
              val msgs = app.chatRepository.getMessages(currentSessionId!!)
              if (msgs.isNotEmpty()) {
                pendingModelSwitch = path to name
                return@ModelListScreen
              }
            }
            loadedModelPath = path; loadedModelName = name
            currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
            selectedTab = 0
          },
          onBack = { selectedTab = 0 }
        )
         2 -> RagScreen(onBack = { selectedTab = 0 })
          3 -> SettingsScreen(onBack = { selectedTab = 0 })
      }
    }
  }

  pendingModelSwitch?.let { (path, name) ->
    AlertDialog(
      onDismissRequest = { pendingModelSwitch = null },
      containerColor = currentPalette().Card,
      title = { Text("$name", color = currentPalette().Text, fontWeight = FontWeight.SemiBold) },
      text = {
        Text(
          "Continue the current chat with this model, or start a new chat?",
          color = currentPalette().Text2
        )
      },
      confirmButton = {
        TextButton(onClick = {
          loadedModelPath = path; loadedModelName = name
          SettingsManager.lastModelPath = path; SettingsManager.lastModelName = name
          app.chatRepository.updateSessionModel(currentSessionId!!, path, name)
          pendingModelSwitch = null
          selectedTab = 0
        }) {
          Text("Continue Current Chat", color = currentPalette().Accent)
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = {
            loadedModelPath = path; loadedModelName = name
            SettingsManager.lastModelPath = path; SettingsManager.lastModelName = name
            currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
            pendingModelSwitch = null
            selectedTab = 0
          }) {
            Text("New Chat", color = currentPalette().Text2)
          }
          Spacer(Modifier.width(4.dp))
          TextButton(onClick = { pendingModelSwitch = null }) {
            Text("Cancel", color = currentPalette().Text2)
          }
        }
      }
    )
  }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
  val colors = currentPalette()
  val alpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    alpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    delay(400)
    alpha.animateTo(0f, animationSpec = tween(400))
    onDone()
  }

  Box(
    modifier = Modifier.fillMaxSize().background(colors.Bg),
    contentAlignment = Alignment.Center
  ) {
    Column(modifier = Modifier.alpha(alpha.value), horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
        modifier = Modifier.size(100.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))),
        contentAlignment = Alignment.Center
      ) { Text("ZC", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace) }
      Spacer(Modifier.height(20.dp))
      Text("ZeroCopy", fontSize = 28.sp, fontWeight = FontWeight.Light, color = colors.Text2, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
      Spacer(Modifier.height(8.dp))
      Text("by adeennour4-dot", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = colors.Text3, fontFamily = FontFamily.Monospace)
    }
  }
}
