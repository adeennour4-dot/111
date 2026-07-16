package com.gguf.zerocopy.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.server.ModelServerService
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RepeatPenaltyConfig
import com.gguf.zerocopy.ui.jobs.JobsScreen
import com.gguf.zerocopy.ui.settings.BenchmarkDialog
import kotlinx.coroutines.Dispatchers
import com.gguf.zerocopy.ui.chat.components.getFileName
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val engineManager = app.engineManager
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val colors = currentPalette()
  val focusManager = LocalFocusManager.current

  // ── State ────────────────────────────────────────────────────────────────
  var nCtx by remember { mutableStateOf(SettingsManager.nCtx.toString()) }
  var maxTok by remember { mutableStateOf(SettingsManager.maxTokens.toString()) }
  var batch by remember { mutableStateOf(SettingsManager.nBatch.toString()) }
  var temp by remember { mutableStateOf(SettingsManager.temperature.toString()) }
  var topP by remember { mutableStateOf(SettingsManager.topP.toString()) }
  var minP by remember { mutableStateOf(SettingsManager.minP.toString()) }
  var topK by remember { mutableStateOf(SettingsManager.topK.toString()) }
  var gpu by remember { mutableStateOf(SettingsManager.gpuLayers.toString()) }
  var threads by remember { mutableStateOf(SettingsManager.threads.toString()) }
  var repPen by remember { mutableStateOf(SettingsManager.repeatPenalty.toString()) }
  var freqPen by remember { mutableStateOf(SettingsManager.freqPenalty.toString()) }
  var presPen by remember { mutableStateOf(SettingsManager.presPenalty.toString()) }
  var sysPrompt by remember { mutableStateOf(SettingsManager.systemPrompt) }
  var showBenchmark by remember { mutableStateOf(false) }
  var lowRam by remember { mutableStateOf(SettingsManager.lowRamMode) }
  var isDark by remember { mutableStateOf(SettingsManager.isDarkTheme) }
  var mmprojPath by remember { mutableStateOf(SettingsManager.mmprojPath) }
  var reasoningEnabled by remember { mutableStateOf(SettingsManager.reasoningEnabled) }
  var ragEnabled by remember { mutableStateOf(SettingsManager.ragEnabled) }
  var showResetConfirm by remember { mutableStateOf(false) }
  var serverPort by remember { mutableStateOf(SettingsManager.serverPort.toString()) }
  var serverAuthEnabled by remember { mutableStateOf(SettingsManager.serverAuthEnabled) }
  var serverAuthToken by remember { mutableStateOf(SettingsManager.serverAuthToken) }
  var serverWifiOnly by remember { mutableStateOf(SettingsManager.serverWifiOnly) }
  var showToken by remember { mutableStateOf(false) }
  var showJobs by remember { mutableStateOf(false) }
  var flashAttn by remember { mutableStateOf(SettingsManager.flashAttention) }
  var chatTemplate by remember { mutableStateOf(SettingsManager.chatTemplate) }
  var ragMaxChunksText by remember { mutableStateOf(SettingsManager.ragMaxChunks.toString()) }
  var ragMaxCharsText by remember { mutableStateOf(SettingsManager.ragMaxChars.toString()) }
  var ragMinScoreText by remember { mutableStateOf(SettingsManager.ragMinScore.toString()) }
  // ── Memory pressure (polled from RustCore) ──
  var memUnderPressure by remember { mutableStateOf(false) }
  var memPressurePct by remember { mutableStateOf(0.0) }
  var memAvailableMb by remember { mutableStateOf(0L) }
  LaunchedEffect(Unit) {
    while (isActive) {
      val advice = com.gguf.zerocopy.domain.inference.RustCore.getMemoryAdvice()
      memUnderPressure = advice.underPressure
      memPressurePct = advice.pressurePercent
      memAvailableMb = advice.availableMb
      kotlinx.coroutines.delay(10_000)
    }
  }

  val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        val name = getFileName(context, uri)
        val dir = File(context.filesDir, "mmproj").also { it.mkdirs() }
        val file = File(dir, name)
        try {
          context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
          }
          mmprojPath = file.absolutePath
        } catch (_: Exception) {}
      }
    }
  }

  fun saveSettings() {
    val cfg = InferenceConfig(
      nCtx = nCtx.toIntOrNull()?.coerceIn(512, 32768) ?: 2048,
      maxNewTokens = maxTok.toIntOrNull()?.coerceIn(64, 8192) ?: 2048,
      nBatch = batch.toIntOrNull()?.coerceIn(512, 8192) ?: 2048,
      temperature = temp.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.5f,
      topP = topP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f,
      minP = minP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.1f,
      topK = topK.toIntOrNull()?.coerceIn(1, 200) ?: 40,
      nGpuLayers = gpu.toIntOrNull()?.coerceIn(0, 999) ?: 99,
      nThreads = threads.toIntOrNull()?.coerceIn(0, 16) ?: 0,
      lowRamMode = lowRam,
      flashAttention = flashAttn,
      mmprojPath = mmprojPath
    )
    val rp = RepeatPenaltyConfig(
      repeatPenalty = repPen.toFloatOrNull() ?: 1.1f,
      freqPenalty = freqPen.toFloatOrNull() ?: 0f,
      presPenalty = presPen.toFloatOrNull() ?: 0f
    )
    SettingsManager.save(cfg, rp)
    SettingsManager.systemPrompt = sysPrompt
    SettingsManager.reasoningEnabled = reasoningEnabled
    SettingsManager.ragEnabled = ragEnabled
    SettingsManager.serverPort = serverPort.toIntOrNull() ?: 8080
    SettingsManager.serverAuthEnabled = serverAuthEnabled
    SettingsManager.serverAuthToken = serverAuthToken
    SettingsManager.serverWifiOnly = serverWifiOnly
    SettingsManager.flashAttention = flashAttn
    SettingsManager.chatTemplate = chatTemplate
    SettingsManager.ragMaxChunks = ragMaxChunksText.toIntOrNull()?.coerceIn(1, 20) ?: 5
    SettingsManager.ragMaxChars = ragMaxCharsText.toIntOrNull()?.coerceIn(500, 10000) ?: 3000
    SettingsManager.ragMinScore = ragMinScoreText.toFloatOrNull()?.coerceIn(0.01f, 1f) ?: 0.05f

    val active = engineManager.getActiveEngine()
    active?.let {
      val modelPath = it.loadedModelPath
      it.config = if (modelPath != null) SettingsManager.toConfig(modelPath) else cfg
      it.repeatPenalty = rp
      it.systemPrompt = sysPrompt
    }
  }

  fun saveAndReload() {
    saveSettings()
    val active = engineManager.getActiveEngine()
    if (active?.isModelLoaded == true && active.loadedModelPath != null) {
      val path = active.loadedModelPath!!
      scope.launch(Dispatchers.IO) {
        engineManager.unloadAll()
        val eng = engineManager.selectEngineForFormat(path)
        eng.config = InferenceConfig(
          nCtx = SettingsManager.nCtx,
          nBatch = SettingsManager.nBatch.coerceIn(512, 8192),
          maxNewTokens = SettingsManager.maxTokens,
          temperature = SettingsManager.temperature.coerceIn(0f, 2f),
          topP = SettingsManager.topP.coerceIn(0f, 1f),
          minP = SettingsManager.minP.coerceIn(0f, 1f),
          topK = SettingsManager.topK.coerceIn(1, 200),
          nGpuLayers = SettingsManager.gpuLayers.coerceIn(0, 999),
          nThreads = SettingsManager.threads.coerceIn(0, 16),
          lowRamMode = SettingsManager.lowRamMode,
          flashAttention = SettingsManager.flashAttention,
          mmprojPath = SettingsManager.mmprojPath
        )
        eng.repeatPenalty = SettingsManager.toRepeatPenalty()
        eng.systemPrompt = SettingsManager.systemPrompt
        eng.loadModel(path)
      }
    }
  }

  BackHandler(onBack = {
    saveSettings()
    onBack()
  })

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = {
          IconButton(onClick = {
            saveSettings()
            onBack()
          }) { Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
      )
    },
    containerColor = colors.Bg,
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { pad ->
    Column(
      modifier = Modifier
        .padding(pad)
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // ═══════════════════════════════════════════════════════════════════════
      // INFERENCE
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("Inference", colors)

      // ── Sampling ──
      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, tint = colors.Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Sampling", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
              color = colors.Accent, fontFamily = FontFamily.Monospace)
          }
          Spacer(Modifier.height(8.dp))
          InlineField("Temperature", "0–2", temp, { temp = it }, focusManager)
          InlineField("Top-P", "0–1", topP, { topP = it }, focusManager)
          InlineField("Min-P", "0–1", minP, { minP = it }, focusManager)
          InlineField("Top-K", "1–200", topK, { topK = it }, focusManager)
          InlineField("Repeat Penalty", "≥1.0", repPen, { repPen = it }, focusManager)
          InlineField("Freq Penalty", "0–2", freqPen, { freqPen = it }, focusManager)
          InlineField("Presence Penalty", "0–2", presPen, { presPen = it }, focusManager)
        }
      }

      // ── Generation ──
      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Refresh, null, tint = colors.Accent2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Generation", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
              color = colors.Accent2, fontFamily = FontFamily.Monospace)
          }
          Spacer(Modifier.height(8.dp))
          InlineField("Batch Size", "512–8192", batch, { batch = it }, focusManager)
          InlineField("GPU Layers", "99=GPU, 0=CPU", gpu, { gpu = it }, focusManager)
          InlineField("Threads", "0=auto, 1–16", threads, { threads = it }, focusManager)
          Spacer(Modifier.height(4.dp))
          ToggleRow("Low RAM Mode", "Reduce memory usage", lowRam, { lowRam = it }, colors)
          ToggleRow("Flash Attention", "ARMv8.2+ (SD 888+)", flashAttn, { flashAttn = it }, colors)
          // ── Memory pressure indicator ──
          if (memUnderPressure || memPressurePct > 50.0) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Warning, null, tint = colors.Amber, modifier = Modifier.size(12.dp))
              Spacer(Modifier.width(4.dp))
              Text("Memory pressure ${"%.0f".format(memPressurePct)}%" +
                if (memAvailableMb > 0) " (${memAvailableMb}MB free)" else "",
                fontSize = 9.sp, color = colors.Amber, fontFamily = FontFamily.Monospace)
            }
          }
        }
      }

      // ── Per-Model Config hint ──
      Surface(shape = RoundedCornerShape(10.dp), color = colors.CardLight) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Settings, null, tint = colors.Accent, modifier = Modifier.size(14.dp))
          Spacer(Modifier.width(8.dp))
          Text("Context & max tokens: set per model via Models → ⚙",
            fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
        }
      }

      // ── Chat template ──
      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          Text("Chat Template", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = colors.Accent, fontFamily = FontFamily.Monospace)
          Spacer(Modifier.height(6.dp))
          ChatTemplateSelector(current = chatTemplate, onChange = { chatTemplate = it }, colors = colors)
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // SYSTEM
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("System", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          Text("System Prompt", fontSize = 11.sp, color = colors.Text2)
          Spacer(Modifier.height(4.dp))
          OutlinedTextField(
            value = sysPrompt, onValueChange = { sysPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
              focusedTextColor = colors.Text, unfocusedTextColor = colors.Text,
              cursorColor = colors.Accent
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
          )
        }
      }

      // ── System actions ──
      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          ActionButton("Load Vision mmproj", colors.Purple, colors) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
            mmprojPicker.launch(intent)
          }
          if (mmprojPath.isNotEmpty()) {
            Text("  " + mmprojPath.substringAfterLast('/'), fontSize = 10.sp,
              color = colors.Accent2, fontFamily = FontFamily.Monospace,
              modifier = Modifier.padding(start = 4.dp, top = 2.dp))
          }
          Spacer(Modifier.height(6.dp))
          ActionButton("Reset Context", colors.Amber, colors) { showResetConfirm = true }
          ActionButton("Unload All Models", colors.Red, colors) { engineManager.unloadAll() }
          ActionButton("Apply Device Defaults", colors.Accent2, colors) {
            val info = app.deviceUtils.detect()
            SettingsManager.applyDeviceDefaults(info)
            nCtx = SettingsManager.nCtx.toString()
            maxTok = SettingsManager.maxTokens.toString()
            batch = SettingsManager.nBatch.toString()
            gpu = SettingsManager.gpuLayers.toString()
            threads = SettingsManager.threads.toString()
            val active = engineManager.getActiveEngine()
            active?.let {
              it.config = SettingsManager.toConfig(it.loadedModelPath)
              it.repeatPenalty = SettingsManager.toRepeatPenalty()
            }
            scope.launch { snackbarHostState.showSnackbar("Device defaults applied") }
          }
          ActionButton("Running Jobs (${app.jobManager.activeCount})", colors.Accent2, colors) {
            showJobs = true
          }
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // REASONING
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("Reasoning", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          ToggleRow("Chain-of-Thought", "Let's work step-by-step before answering",
            reasoningEnabled, { reasoningEnabled = it }, colors)
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // RAG & DOCUMENTS
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("RAG & Documents", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          ToggleRow("Retrieval-Augmented Gen", "Inject document context into prompts",
            ragEnabled, { ragEnabled = it }, colors)

          val ragEngine = app.ragEngine
          val ragStats = if (ragEngine.hasDocuments) ragEngine.getStats() else null

          if (ragStats != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            Text("Index Stats", fontSize = 10.sp, color = colors.Accent,
              fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            RagStatRow("Documents", "${ragStats.documentCount}", colors)
            RagStatRow("Chunks", "${ragStats.chunkCount}", colors)
            RagStatRow("Total chars", "${ragStats.totalChars}", colors)
            Spacer(Modifier.height(4.dp))
            ragStats.sources.forEach { name ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, tint = colors.Purple, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(name, fontSize = 9.sp, color = colors.Text3, maxLines = 1,
                  modifier = Modifier.weight(1f))
              }
            }
            TextButton(onClick = { ragEngine.clear() },
              modifier = Modifier.height(32.dp)) {
              Text("Clear all documents", fontSize = 10.sp, color = colors.Red)
            }
          } else {
            Spacer(Modifier.height(4.dp))
            Text("No docs loaded — attach files in chat to build context",
              fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
          }

          Spacer(Modifier.height(8.dp))
          HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
          Spacer(Modifier.height(6.dp))
          Text("Settings", fontSize = 10.sp, color = colors.Accent,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(4.dp))
          InlineField("Max chunks", "1–20", ragMaxChunksText, { ragMaxChunksText = it }, focusManager)
          InlineField("Max chars/query", "500–10000", ragMaxCharsText, { ragMaxCharsText = it }, focusManager)
          InlineField("Min score", "0.01–1.0", ragMinScoreText, { ragMinScoreText = it }, focusManager)
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // APPEARANCE
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("Appearance", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          ToggleRow("Dark Theme", null, isDark, {
            isDark = it; SettingsManager.isDarkTheme = it
          }, colors)
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // SERVER
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("Server", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          ToggleRow("Model Server", "Anyone on WiFi can access the web UI",
            app.modelServer.isRunning, {
              if (it) {
                val engine = app.engineManager.getActiveEngine()
                if (engine?.loadedModelPath != null) {
                  val path = engine.loadedModelPath ?: ""
                  SettingsManager.lastModelPath = path
                  SettingsManager.lastModelName = path.substringAfterLast('/')
                }
                app.modelServer.setAutoModel(SettingsManager.lastModelPath, SettingsManager.lastModelName)
                context.startService(Intent(context, ModelServerService::class.java))
                SettingsManager.serverEnabled = true
              } else {
                context.stopService(Intent(context, ModelServerService::class.java))
                SettingsManager.serverEnabled = false
              }
            }, colors)

          if (app.modelServer.isRunning) {
            Text(app.modelServer.getServerUrl(), fontSize = 10.sp,
              color = colors.Accent2, fontFamily = FontFamily.Monospace)
          }

          Spacer(Modifier.height(6.dp))
          InlineField("Port", "1024–65535", serverPort, { serverPort = it }, focusManager)
          ToggleRow("Auth enabled", null, serverAuthEnabled, { serverAuthEnabled = it }, colors)

          if (serverAuthEnabled) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
              value = serverAuthToken, onValueChange = { serverAuthToken = it },
              modifier = Modifier.fillMaxWidth().height(44.dp),
              label = { Text("Auth Token", fontSize = 11.sp) },
              singleLine = true,
              visualTransformation = if (showToken) VisualTransformation.None
                else PasswordVisualTransformation(),
              trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                  Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (showToken) "Hide" else "Show", tint = colors.Text3)
                }
              },
              shape = RoundedCornerShape(8.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
                focusedTextColor = colors.Text, unfocusedTextColor = colors.Text,
                cursorColor = colors.Accent
              ),
              textStyle = LocalTextStyle.current.copy(fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            )
          }
          ToggleRow("WiFi only", null, serverWifiOnly, { serverWifiOnly = it }, colors)
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // BENCHMARK & SAVE
      // ═══════════════════════════════════════════════════════════════════════
      Surface(shape = RoundedCornerShape(12.dp), color = colors.CardLight) {
        Column(Modifier.padding(14.dp)) {
          ActionButton("Run Benchmark", colors.Accent2, colors) { showBenchmark = true }
          Spacer(Modifier.height(8.dp))
          Button(
            onClick = {
              saveAndReload()
              scope.launch { snackbarHostState.showSnackbar("Settings saved & model reloaded") }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
          ) {
            Icon(Icons.Filled.Save, null, tint = colors.Bg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save Settings", color = colors.Bg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          }
        }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // ABOUT
      // ═══════════════════════════════════════════════════════════════════════
      SectionHeader("About", colors)

      Surface(shape = RoundedCornerShape(12.dp), color = colors.Card,
        border = BorderStroke(1.dp, colors.Border)) {
        Column(Modifier.padding(14.dp)) {
          Text("adeennour4-dot", fontSize = 12.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
          Text("github.com/adeennour4-dot/111", fontSize = 11.sp, color = colors.Accent,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
              try { context.startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/adeennour4-dot/111"))) }
              catch (_: Exception) {}
            })
          Spacer(Modifier.height(6.dp))
          Text("v1.0.2", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
          Text("${Build.MODEL} · ${Build.VERSION.RELEASE}", fontSize = 10.sp,
            color = colors.Text3, fontFamily = FontFamily.Monospace)
          val deviceInfo = remember { app.deviceUtils.detect() }
          Text("RAM: ${deviceInfo.totalRamMB / 1024} GB", fontSize = 10.sp,
            color = colors.Text3, fontFamily = FontFamily.Monospace)
          Spacer(Modifier.height(6.dp))
          ActionButton("Send Logs", colors.Accent2, colors) { sendLogs(context) }
        }
      }

      Spacer(Modifier.height(32.dp))
    }
  }

  // ── Dialogs ─────────────────────────────────────────────────────────────
  if (showResetConfirm) { ResetDialog(colors, engineManager, snackbarHostState, scope) { showResetConfirm = false } }
  if (showBenchmark) {
    BenchmarkDialog(engineManager, app.modelRepository.models.value, onDismiss = { showBenchmark = false })
  }
  if (showJobs) { JobsScreen(onBack = { showJobs = false }) }
}

// ═══════════════════════════════════════════════════════════════════════════
// COMPOSABLE HELPERS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, colors: ZcPalette) {
  Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
    color = colors.Accent.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace,
    letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun InlineField(
  label: String, hint: String, value: String,
  onChange: (String) -> Unit, focusManager: androidx.compose.ui.focus.FocusManager
) {
  val colors = currentPalette()

  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, fontSize = 12.sp, color = colors.Text2)
      Text(hint, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.width(8.dp))
    OutlinedTextField(
      value = value, onValueChange = { v ->
        // Only accept valid decimal input: optional leading "-", digits, and
        // at most one ".".  Reject bare separators so the field never shows
        // just "." or "-" without digits.
        if (v.isEmpty()) return@OutlinedTextField
        if (v == "." || v == "-" || v == "-.") return@OutlinedTextField
        if (v.count { it == '.' } > 1) return@OutlinedTextField
        // Reject non-digit chars (allow digits, '.', and leading '-')
        val clean = v.filterIndexed { i, c -> c.isDigit() || c == '.' || (c == '-' && i == 0) }
        if (clean != v) return@OutlinedTextField
        onChange(v)
      },
      modifier = Modifier.width(90.dp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(
        onDone = { focusManager.clearFocus() }
      ),
      shape = RoundedCornerShape(6.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
        focusedTextColor = colors.Text, unfocusedTextColor = colors.Text,
        cursorColor = colors.Accent
      ),
      textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.Text)
    )
  }
}



@Composable
private fun ToggleRow(
  label: String, subtitle: String?, checked: Boolean,
  onCheckedChange: (Boolean) -> Unit, colors: ZcPalette
) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, fontSize = 13.sp, color = colors.Text2)
      if (subtitle != null) {
        Text(subtitle, fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
      }
    }
    Switch(
      checked = checked, onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
    )
  }
}

@Composable
private fun ActionButton(text: String, color: androidx.compose.ui.graphics.Color, colors: ZcPalette,
  onClick: () -> Unit) {
  OutlinedButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(38.dp),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
  ) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
  }
  Spacer(Modifier.height(4.dp))
}

@Composable
private fun ResetDialog(
  colors: ZcPalette,
  engineManager: com.gguf.zerocopy.domain.inference.EngineManager,
  snackbarHostState: SnackbarHostState,
  scope: kotlinx.coroutines.CoroutineScope,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Card,
    title = { Text("Reset Context?", color = colors.Text) },
    text = { Text("Clear the context window and conversation history. Model stays loaded.",
      color = colors.Text2) },
    confirmButton = {
      TextButton(onClick = {
        engineManager.getActiveEngine()?.resetContext()
        onDismiss()
        scope.launch { snackbarHostState.showSnackbar("Context reset") }
      }) { Text("Reset", color = colors.Red) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.Text2) } }
  )
}

@Composable
fun SettingField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
  val colors = currentPalette()
  Column {
    Text(label, fontSize = 11.sp, color = colors.Text2)
    Text(hint, fontSize = 9.sp, color = colors.Text3)
    OutlinedTextField(
      value = value, onValueChange = onChange,
      modifier = Modifier.fillMaxWidth().height(42.dp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      shape = RoundedCornerShape(8.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
        focusedTextColor = colors.Accent, unfocusedTextColor = colors.Accent.copy(alpha = 0.7f),
        cursorColor = colors.Accent
      ),
      textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    )
  }
}

@Composable
private fun ChatTemplateSelector(
  current: String, onChange: (String) -> Unit, colors: ZcPalette
) {
  val options = listOf(
    "auto" to "Auto-detect", "chatml" to "ChatML",
    "gemma" to "Gemma", "llama3" to "Llama 3",
    "deepseek" to "DeepSeek", "qwen" to "Qwen",
    "phi" to "Phi-3/4", "mistral" to "Mistral",
    "command" to "Command-R"
  )
  val expanded = remember { mutableStateOf(false) }
  val selectedLabel = options.find { it.first == current }?.second ?: "Auto-detect"

  Box {
    OutlinedButton(
      onClick = { expanded.value = true },
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Accent)
    ) {
      Text(selectedLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(1f))
      Text("▾", fontSize = 11.sp, color = colors.Text3)
    }
    DropdownMenu(
      expanded = expanded.value, onDismissRequest = { expanded.value = false },
      containerColor = colors.Card
    ) {
      options.forEach { (value, label) ->
        DropdownMenuItem(
          text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (value == current) {
                Text("✓ ", fontSize = 12.sp, color = colors.Accent, fontFamily = FontFamily.Monospace)
              } else Spacer(Modifier.width(14.dp))
              Text(label, fontSize = 12.sp,
                color = if (value == current) colors.Accent else colors.Text,
                fontFamily = FontFamily.Monospace)
            }
          },
          onClick = { onChange(value); expanded.value = false }
        )
      }
    }
  }
}

@Composable
private fun RagStatRow(label: String, value: String, colors: ZcPalette) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
    Text(value, fontSize = 9.sp, color = colors.Text2, fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.SemiBold)
  }
}

/** Collect logs + model metadata and share via email/intent. */
private fun sendLogs(context: android.content.Context) {
  try {
    val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
    val logFile = File(logsDir, "zerocopy_logs_${System.currentTimeMillis()}.txt")
    val sb = StringBuilder()
    sb.appendLine("=== ZeroCopy Debug Logs ===")
    sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
    sb.appendLine("Device: ${Build.MODEL} (${Build.MANUFACTURER})")
    sb.appendLine("Android: ${Build.VERSION.SDK_INT}")
    sb.appendLine("RAM: ${Runtime.getRuntime().totalMemory() / (1024*1024)} MB")
    sb.appendLine()
    sb.appendLine("--- Model Info ---")
    try {
      val app = ZeroCopyApp.instance
      val engine = app.engineManager.getActiveEngine()
      if (engine != null) {
        sb.appendLine("Engine: ${engine::class.simpleName}")
        sb.appendLine("Model loaded: ${engine.isModelLoaded}")
        sb.appendLine("Model path: ${engine.loadedModelPath}")
        sb.appendLine("Context: ${engine.config.nCtx}")
      }
    } catch (_: Exception) {}
    sb.appendLine()
    sb.appendLine("--- Logcat (last 200 lines) ---")
    try {
      val process = Runtime.getRuntime().exec("logcat -d -t 200")
      val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
      reader.use { r -> r.lines().forEach { sb.appendLine(it) } }
    } catch (_: Exception) { sb.appendLine("(logcat not available)") }
    logFile.writeText(sb.toString())
    val uri = androidx.core.content.FileProvider.getUriForFile(context,
      "${context.packageName}.fileprovider", logFile)
    context.startActivity(
      Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_STREAM, uri)
          putExtra(Intent.EXTRA_SUBJECT, "ZeroCopy Debug Logs")
          putExtra(Intent.EXTRA_TEXT, "Attached: device info, model config, and logcat output")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Send Logs"
      )
    )
  } catch (e: Exception) {
    android.util.Log.e("SettingsScreen", "sendLogs failed", e)
  }
}
