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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.gguf.zerocopy.ui.invent.InventProjectScreen
import com.gguf.zerocopy.ui.invent.InventScreen
import com.gguf.zerocopy.ui.invent.InventSetupScreen
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
  var inventStarted by rememberSaveable { mutableStateOf(false) }
  var inventProjectSelected by rememberSaveable { mutableStateOf(false) }
  var inventProjectIndex by rememberSaveable { mutableIntStateOf(0) }
  var completedInventProjects by rememberSaveable { mutableStateOf<Set<Int>>(emptySet()) }
  var inventModel1Path by rememberSaveable { mutableStateOf("") }
  var inventModel1Name by rememberSaveable { mutableStateOf("") }
  var inventModel2Path by rememberSaveable { mutableStateOf("") }
  var inventModel2Name by rememberSaveable { mutableStateOf("") }
  var inventResPath by rememberSaveable { mutableStateOf("") }
  var inventResName by rememberSaveable { mutableStateOf("") }
  var inventOffline by rememberSaveable { mutableStateOf(false) }
  var inventSameModel by rememberSaveable { mutableStateOf(false) }
  var inventReasoningEnabled by rememberSaveable { mutableStateOf(true) }

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
                  color = if (isSelected) navColors.Accent else navColors.Text3
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

      // Tab 4: Invent (setup → project selection → main screen)
      Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = if (selectedTab == 4) 1f else 0f
          translationX = slide4 * size.width * 0.10f
          if (selectedTab != 4) { scaleX = 0.001f; scaleY = 0.001f }
        }
      ) {
        if (!inventStarted) {
          InventSetupScreen(
            onStart = { m1p, m1n, m2p, m2n, rp, rn, offline, sameModel, reasoning ->
              inventModel1Path = m1p; inventModel1Name = m1n
              inventModel2Path = m2p; inventModel2Name = m2n
              inventResPath = rp; inventResName = rn
              inventOffline = offline
              inventSameModel = sameModel
              inventReasoningEnabled = reasoning
              inventStarted = true
              inventProjectSelected = false
            },
            onBack = { selectedTab = 0 }
          )
        } else if (!inventProjectSelected) {
          InventProjectScreen(
            model1Path = inventModel1Path, model1Name = inventModel1Name,
            model2Path = inventModel2Path, model2Name = inventModel2Name,
            researcherPath = inventResPath, researcherName = inventResName,
            offlineMode = inventOffline,
            sameModelMode = inventSameModel,
            reasoningEnabled = inventReasoningEnabled,
            completedProjects = completedInventProjects,
            onStartProject = { idx ->
              inventProjectIndex = idx
              inventProjectSelected = true
            },
            onBack = {
              inventStarted = false
            },
            onSettings = { role, modelPath, modelName ->
              // Open settings dialog for the model
            },
            onPickModel = { role ->
              selectedTab = 1
            }
          )
        } else {
          InventScreen(
              model1Path = inventModel1Path, model1Name = inventModel1Name,
              model2Path = inventModel2Path, model2Name = inventModel2Name,
              researcherPath = inventResPath, researcherName = inventResName,
              offlineMode = inventOffline,
              sameModelMode = inventSameModel,
              reasoningEnabled = inventReasoningEnabled,
              onNewSession = {
                completedInventProjects = completedInventProjects + inventProjectIndex
                inventStarted = false
                inventModel1Path = ""; inventModel1Name = ""
                inventModel2Path = ""; inventModel2Name = ""
                inventResPath = ""; inventResName = ""
              },
              onBack = { selectedTab = 0 },
              onModelsClick = { selectedTab = 1 }
          )
        }
      }
    }
  }
}

/** Animated bottom-bar sprite: outlined when idle, filled + glowing when active,
 *  with a pop-in scale and — for Invent's lightbulb — a warm "turned on" pulse. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun NavSprite(item: NavItem, isSelected: Boolean, colors: ZcPalette) {
  val isBulb = item.label == "Invent"
  // Each tab gets its own accent while active; Invent keeps its warm bulb amber
  val activeTint = when (item.label) {
    "Chat" -> colors.Accent2
    "Models" -> colors.Accent
    "Server" -> colors.Amber
    "Settings" -> colors.Purple
    else -> Color(0xFFFFD166)
  }
  val scale = remember { Animatable(if (isSelected) 1f else 0.9f) }
  val halo = remember { Animatable(if (isSelected) 1f else 0f) }

  LaunchedEffect(isSelected) {
    if (isSelected) {
      // Turn on: pop-in (small → overshoot → settle) + halo glow
      scale.snapTo(0.55f)
      scale.animateTo(1.18f, tween(240, easing = FastOutSlowInEasing))
      scale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy))
      halo.animateTo(1f, tween(350))
    } else {
      // Turn off: glow fades, sprite shrinks back (reverse)
      halo.animateTo(0f, tween(200))
      scale.animateTo(0.9f, tween(220))
    }
  }

  // Every selected sprite breathes gently while its tab is open
  // (the Invent bulb keeps its original stronger pulse)
  val pulse = if (isSelected) {
    val t = rememberInfiniteTransition(label = "spritePulse")
    if (isBulb) {
      t.animateFloat(0.88f, 1.12f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bulbPulseVal")
    } else {
      t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spritePulseVal")
    }
  } else null

  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
    // Glow halo behind the active sprite (small, tight)
    Box(
      Modifier.size(20.dp).clip(CircleShape)
        .background(activeTint.copy(alpha = (0.16f * halo.value).coerceIn(0f, 1f)))
    )
    // Ray burst — lightbulb "turned on" rays (Invent tab only, short rays)
    if (isBulb && isSelected) {
      Canvas(Modifier.size(28.dp)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseR = 8.dp.toPx()
        val rayLen = (2f + (pulse?.value ?: 1f) * 1.2f).dp.toPx()
        for (i in 0 until 8) {
          val a = i * (Math.PI / 4.0)
          val dx = Math.cos(a)
          val dy = Math.sin(a)
          drawLine(
            color = activeTint.copy(alpha = 0.5f),
            start = Offset(c.x + (dx * baseR).toFloat(), c.y + (dy * baseR).toFloat()),
            end = Offset(c.x + (dx * (baseR + rayLen)).toFloat(), c.y + (dy * (baseR + rayLen)).toFloat()),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round
          )
        }
      }
    }
    AnimatedContent(
      targetState = isSelected,
      transitionSpec = {
        (fadeIn(tween(160)) + scaleIn(initialScale = 0.5f)) togetherWith
          (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f))
      },
      label = "sprite"
    ) { selected ->
      Icon(
        if (selected) item.activeIcon else item.icon,
        item.label,
        tint = if (selected) activeTint else colors.Text3,
        modifier = Modifier.size(22.dp).scale(scale.value * (pulse?.value ?: 1f))
      )
    }
  }
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

