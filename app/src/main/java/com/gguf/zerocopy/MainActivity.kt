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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.chat.ChatScreen
import com.gguf.zerocopy.ui.cloud.CloudScreen
import com.gguf.zerocopy.ui.models.ModelListScreen
import com.gguf.zerocopy.ui.sessions.SessionListScreen
import com.gguf.zerocopy.ui.settings.SettingsScreen
import com.gguf.zerocopy.ui.theme.ZeroCopyTheme
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import com.gguf.zerocopy.data.invent.InventProjectStore
import com.gguf.zerocopy.ui.invent.InventDashboardScreen
import com.gguf.zerocopy.ui.invent.InventScreen
import kotlinx.coroutines.delay

data class NavItem(val label: String, val icon: ImageVector, val activeIcon: ImageVector)

private val navItems = listOf(
  NavItem("Chat", Icons.Outlined.Chat, Icons.Filled.Chat),
  NavItem("Models", Icons.Outlined.SmartToy, Icons.Filled.SmartToy),
  NavItem("Server", Icons.Outlined.Dns, Icons.Filled.Dns),
  NavItem("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
  NavItem("Invent", Icons.Outlined.Lightbulb, Icons.Outlined.Lightbulb)
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
  var loadedModelPath by remember { mutableStateOf("") }
  var loadedModelName by remember { mutableStateOf("") }
  var currentSessionId by remember { mutableStateOf<String?>(null) }  // start with new session
  LaunchedEffect(Unit) {
    SettingsManager.currentSessionId = ""
    // Onboarding screen removed — mark first-run done so device defaults are
    // applied exactly once (in ZeroCopyApp.onCreate), never on later launches.
    SettingsManager.welcomeDone = true
  }
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  var showSessionList by remember { mutableStateOf(false) }
  // Invent navigation: "dashboard" (the 4 squares) | "chat" (session screen)
  var inventScreen by rememberSaveable { mutableStateOf("dashboard") }
  var inventProjectId by rememberSaveable { mutableStateOf("") }
  var inventSessionId by rememberSaveable { mutableStateOf("") } // "" = start a fresh session

  val inventContext = LocalContext.current
  var inventProjects by remember { mutableStateOf(InventProjectStore.listProjects(inventContext)) }
  val inventModels by app.modelRepository.models.collectAsState()
  // Force a refresh of the project list after returning from a chat session
  var inventProjectRefresh by remember { mutableIntStateOf(0) }
  LaunchedEffect(inventProjectRefresh) { inventProjects = InventProjectStore.listProjects(inventContext) }
  // First run: seed the 4 squares so each one exists with its + ready
  LaunchedEffect(Unit) {
    if (InventProjectStore.listProjects(inventContext).isEmpty()) {
      repeat(4) { i -> InventProjectStore.createProject(inventContext, "Project ${i + 1}") }
      inventProjects = InventProjectStore.listProjects(inventContext)
    }
  }

  if (showSplash) {
    SplashScreen(onDone = { showSplash = false })
    return
  }

  Scaffold(
    bottomBar = {
      val navColors = currentPalette()
      Column {
        HorizontalDivider(color = navColors.Border.copy(alpha = 0.4f), thickness = 0.5.dp)
        NavigationBar(
          containerColor = navColors.Surface,
          tonalElevation = 0.dp
        ) {
          navItems.forEachIndexed { idx, item ->
            val isSelected = selectedTab == idx
            NavigationBarItem(
              selected = isSelected,
              onClick = { selectedTab = idx },
              icon = { NavSprite(item, isSelected, navColors) },
              label = {
                Text(
                  item.label,
                  fontSize = 10.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSelected) navTabColor(item.label, navColors)
                          else navTabColor(item.label, navColors).copy(alpha = 0.45f)
                )
              },
              colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.Transparent,
                unselectedIconColor = Color.Transparent,
                indicatorColor = Color.Transparent,
                selectedTextColor = navColors.Accent,
                unselectedTextColor = navColors.Text3
              )
            )
          }
        }
      }
    }
  ) { innerPad ->
    // All screens are always composed but only the selected one is visible.
    // This preserves inference state when switching tabs (e.g., chat keeps running
    // while you check Settings or Models).
    Box(modifier = Modifier.padding(innerPad).fillMaxSize()) {
      // Tab slide offsets — scroll-like feel when switching tabs
      val slide0 by animateFloatAsState(
        targetValue = when { selectedTab == 0 -> 0f; selectedTab > 0 -> -1f; else -> 1f },
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "slide0"
      )
      val slide1 by animateFloatAsState(
        targetValue = when { selectedTab == 1 -> 0f; selectedTab > 1 -> -1f; else -> 1f },
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "slide1"
      )
      val slide2 by animateFloatAsState(
        targetValue = when { selectedTab == 2 -> 0f; selectedTab > 2 -> -1f; else -> 1f },
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "slide2"
      )
      val slide3 by animateFloatAsState(
        targetValue = when { selectedTab == 3 -> 0f; selectedTab > 3 -> -1f; else -> 1f },
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "slide3"
      )
      val slide4 by animateFloatAsState(
        targetValue = when { selectedTab == 4 -> 0f; selectedTab > 4 -> -1f; else -> 1f },
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "slide4"
      )
      // Tab 0: Chat (always composed so inference continues)
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 0) 1f else 0f
          translationX = slide0 * size.width * 0.10f
          if (selectedTab != 0) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        if (showSessionList) {
          SessionListScreen(
            onSessionSelected = { session ->
              app.engineManager.getActiveEngine()?.resetContext()
              app.ragEngine.clear()
              currentSessionId = session.id
              SettingsManager.currentSessionId = session.id
              if (session.modelPath.isNotEmpty()) { loadedModelPath = session.modelPath; loadedModelName = session.modelName }
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
                val existing = app.chatRepository.sessions.value.find { it.id == currentSessionId }
                if (existing != null) app.chatRepository.renameSession(currentSessionId!!, "Chat - $name")
                else currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
              } else currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
            },
            onSessions = { app.chatRepository.refreshSessions(); showSessionList = true },
            onCloud = { selectedTab = 2 }
          )
        }
      }

      // Tab 1: Models
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 1) 1f else 0f
          translationX = slide1 * size.width * 0.10f
          if (selectedTab != 1) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        ModelListScreen(
          onModelSelected = { path, name ->
            loadedModelPath = path; loadedModelName = name
            currentSessionId = app.chatRepository.createSession("Chat - $name", path, name).id
            selectedTab = 0
          },
          onBack = { selectedTab = 0 }
        )
      }

      // Tab 2: Cloud/Server
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 2) 1f else 0f
          translationX = slide2 * size.width * 0.10f
          if (selectedTab != 2) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        CloudScreen(onBack = { selectedTab = 0 })
      }

      // Tab 3: Settings
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 3) 1f else 0f
          translationX = slide3 * size.width * 0.10f
          if (selectedTab != 3) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        SettingsScreen(onBack = { selectedTab = 0 })
      }

      // Tab 4: Invent (dashboard of 4 squares → session chat)
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 4) 1f else 0f
          translationX = slide4 * size.width * 0.10f
          if (selectedTab != 4) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        when (inventScreen) {
          "chat" -> {
            val project = inventProjects.find { it.id == inventProjectId }
            if (project != null) {
              val planner = project.roles.find { it.isPlanner }
              val coder = project.roles.find { it.isCoder }
              val debugger = project.roles.find { it.isDebugger }
              InventScreen(
                  model1Path = planner?.modelPath ?: "", model1Name = planner?.modelName ?: "",
                  model2Path = coder?.modelPath ?: "", model2Name = coder?.modelName ?: "",
                  debuggerPath = debugger?.modelPath ?: "", debuggerName = debugger?.modelName ?: "",
                  offlineMode = false,
                  sameModelMode = planner?.modelPath != null && planner.modelPath.isNotEmpty() && planner.modelPath == coder?.modelPath,
                  reasoningEnabled = planner?.thinkingEnabled ?: true,
                  onBack = {
                    inventScreen = "dashboard"
                    inventProjectRefresh++
                  },
                  onModelsClick = { selectedTab = 1 },
                  onNewSession = {
                    inventScreen = "dashboard"
                    inventProjectRefresh++
                  },
                  startFresh = inventSessionId.isEmpty(),
                  sessionToOpen = inventSessionId.ifEmpty { null },
                  onSessionCreated = { newSid ->
                    val p = inventProjects.find { it.id == inventProjectId }
                    if (p != null && newSid.isNotEmpty() && !p.sessionIds.contains(newSid)) {
                      InventProjectStore.saveProject(inventContext, p.withSessionIds(p.sessionIds + newSid))
                      inventProjects = InventProjectStore.listProjects(inventContext)
                    }
                    // Remember the live session so a process-death restore reopens it.
                    if (newSid.isNotEmpty()) inventSessionId = newSid
                  }
              )
            } else {
              // Project missing (cleared) — fall back to the dashboard.
              LaunchedEffect(Unit) { inventScreen = "dashboard" }
            }
          }
          else -> {
            // The dashboard: one big square holding 4 small squares (2×2)
            InventDashboardScreen(
                projects = inventProjects,
                models = inventModels,
                onSaveProject = { p ->
                  InventProjectStore.saveProject(inventContext, p)
                  inventProjects = InventProjectStore.listProjects(inventContext)
                },
                onClearProject = { id ->
                  InventProjectStore.clearProjectContents(inventContext, id)
                  inventProjects = InventProjectStore.listProjects(inventContext)
                },
                onStartSession = { project ->
                  inventProjectId = project.id
                  inventSessionId = ""
                  inventScreen = "chat"
                },
                onOpenSession = { project, sid ->
                  inventProjectId = project.id
                  inventSessionId = sid
                  inventScreen = "chat"
                },
                onBack = { selectedTab = 0 }
            )
          }
        }
      }
    }
  }
}

/** Animated bottom-bar sprite: outlined when idle, filled + glowing when active,
 *  with a pop-in scale and — for Invent's lightbulb — a warm "turned on" pulse. */
/** Animated bottom-bar sprite: outlined when idle, filled when active, with a
 *  3D flip switching effect (rotationY + crossfade), a soft radial glow halo,
 *  and — for Invent's lightbulb — warm amber rays. Every icon has its own color. */
@Composable
private fun NavSprite(item: NavItem, isSelected: Boolean, colors: ZcPalette) {
  val isBulb = item.label == "Invent"
  // Every icon has its own color — dim when idle, full + glow when active
  val tabColor = navTabColor(item.label, colors)
  val idleTint = tabColor.copy(alpha = 0.45f)
  val halo = remember { Animatable(if (isSelected) 1f else 0f) }
  // Icon flip progress: 0 = idle icon, 1 = active icon (3D switching effect)
  val flip by animateFloatAsState(
    targetValue = if (isSelected) 1f else 0f,
    animationSpec = tween(320, easing = FastOutSlowInEasing),
    label = "iconFlip"
  )

  LaunchedEffect(isSelected) {
    if (isSelected) halo.animateTo(1f, tween(350))
    else halo.animateTo(0f, tween(200))
  }

  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
    // Soft radial glow — fades smoothly from center to edge
    Box(
      Modifier.size(34.dp).background(
        Brush.radialGradient(
          listOf(tabColor.copy(alpha = (0.38f * halo.value).coerceIn(0f, 1f)), tabColor.copy(alpha = 0f))
        ),
        CircleShape
      )
    )
    // Ray burst — lightbulb "turned on" rays (Invent tab only, short static rays)
    if (isBulb && isSelected) {
      Canvas(Modifier.size(28.dp)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseR = 8.dp.toPx()
        val rayLen = 3.2f.dp.toPx()
        for (i in 0 until 8) {
          val a = i * (Math.PI / 4.0)
          val dx = Math.cos(a)
          val dy = Math.sin(a)
          drawLine(
            color = tabColor.copy(alpha = 0.55f),
            start = Offset(c.x + (dx * baseR).toFloat(), c.y + (dy * baseR).toFloat()),
            end = Offset(c.x + (dx * (baseR + rayLen)).toFloat(), c.y + (dy * (baseR + rayLen)).toFloat()),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round
          )
        }
      }
    }
    // Switching effect: outlined ↔ filled via 3D flip + crossfade + pop-in
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
      Icon(
        item.icon,
        item.label,
        tint = idleTint,
        modifier = Modifier.size(22.dp).graphicsLayer {
          alpha = 1f - flip
          rotationY = -180f * flip
        }
      )
      Icon(
        item.activeIcon,
        item.label,
        tint = tabColor,
        modifier = Modifier.size(22.dp).graphicsLayer {
          alpha = flip
          rotationY = -180f * (1f - flip)
          scaleX = 0.8f + 0.2f * flip
          scaleY = 0.8f + 0.2f * flip
        }
      )
    }
  }
}

private fun navTabColor(label: String, colors: ZcPalette): Color = when (label) {
  "Chat" -> colors.Accent2
  "Models" -> colors.Accent
  "Server" -> colors.Amber
  "Settings" -> colors.Purple
  else -> Color(0xFFFFD166) // Invent bulb amber
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
  val colors = currentPalette()
  val splashAlpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    splashAlpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    delay(600)
    splashAlpha.animateTo(0f, animationSpec = tween(500))
    onDone()
  }

  val glow1 = colors.GlowAccent
  val glow2 = colors.GlowAccent2

  Box(
    modifier = Modifier.fillMaxSize().background(colors.Bg),
    contentAlignment = Alignment.Center
  ) {
    Column(modifier = Modifier.graphicsLayer { alpha = splashAlpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
      // ---- Glowing layered logo ----
      Box(contentAlignment = Alignment.Center) {
        // Layer 1 (back): large square glow
        Box(
          modifier = Modifier.size(140.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(glow1)
        )
        // Layer 2 (middle): circle glow
        Box(
          modifier = Modifier.size(120.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(glow2)
        )
        // Layer 3 (front): main rounded square with ZC
        Box(
          modifier = Modifier.size(100.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))),
          contentAlignment = Alignment.Center
        ) {
          Text("ZC", fontSize = 36.sp, fontWeight = FontWeight.Black,
            color = Color.White, fontFamily = FontFamily.Monospace)
        }
      }
      Spacer(Modifier.height(20.dp))
      Text("ZeroCopy", fontSize = 28.sp, fontWeight = FontWeight.Light,
        color = colors.Text2, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
      Spacer(Modifier.height(8.dp))
      Text("by adeennour4-dot", fontSize = 12.sp, fontWeight = FontWeight.Normal,
        color = colors.Text3, fontFamily = FontFamily.Monospace)
    }
  }
}

