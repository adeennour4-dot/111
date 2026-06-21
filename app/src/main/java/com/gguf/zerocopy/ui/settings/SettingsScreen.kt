package com.gguf.zerocopy.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.local.InferenceConfig
import com.gguf.zerocopy.data.local.RepeatPenaltyConfig
import com.gguf.zerocopy.ui.theme.currentPalette
import com.gguf.zerocopy.ui.theme.ZcPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val engine = app.activeEngine
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val colors = currentPalette()

  var nCtx by remember { mutableStateOf(SettingsManager.nCtx.toString()) }
  var maxTok by remember { mutableStateOf(SettingsManager.maxTokens.toString()) }
  var batch by remember { mutableStateOf(SettingsManager.nBatch.toString()) }
  var temp by remember { mutableStateOf(SettingsManager.temperature.toString()) }
  var topP by remember { mutableStateOf(SettingsManager.topP.toString()) }
  var minP by remember { mutableStateOf(SettingsManager.minP.toString()) }
  var gpu by remember { mutableStateOf(SettingsManager.gpuLayers.toString()) }
  var threads by remember { mutableStateOf(SettingsManager.threads.toString()) }
  var repPen by remember { mutableStateOf(SettingsManager.repeatPenalty.toString()) }
  var freqPen by remember { mutableStateOf(SettingsManager.freqPenalty.toString()) }
  var presPen by remember { mutableStateOf(SettingsManager.presPenalty.toString()) }
  var sysPrompt by remember { mutableStateOf(SettingsManager.systemPrompt) }
  var lowRam by remember { mutableStateOf(SettingsManager.lowRamMode) }
  var isDark by remember { mutableStateOf(SettingsManager.isDarkTheme) }
  var reasoningEnabled by remember { mutableStateOf(SettingsManager.reasoningEnabled) }
  var showResetConfirm by remember { mutableStateOf(false) }
  var sinkTokens by remember { mutableStateOf(SettingsManager.kvSinkTokens.toString()) }
  var recentTokens by remember { mutableStateOf(SettingsManager.kvRecentTokens.toString()) }
  var evictThreshold by remember { mutableStateOf(SettingsManager.kvEvictThreshold.toString()) }

  fun parseFloat(s: String) = s.replace(",", ".").toFloatOrNull()
  fun parseInt(s: String) = s.replace(",", ".").toIntOrNull()

  fun saveSettings() {
    val cfg = InferenceConfig(
      nCtx = parseInt(nCtx)?.coerceIn(512, 32768) ?: 2048,
      maxNewTokens = parseInt(maxTok)?.coerceIn(64, 8192) ?: 2048,
      nBatch = parseInt(batch)?.coerceIn(512, 8192) ?: 2048,
      temperature = parseFloat(temp)?.coerceIn(0f, 2f) ?: 0.5f,
      topP = parseFloat(topP)?.coerceIn(0f, 1f) ?: 0.85f,
      minP = parseFloat(minP)?.coerceIn(0f, 1f) ?: 0.1f,
      nGpuLayers = parseInt(gpu)?.coerceIn(0, 999) ?: 99,
      nThreads = parseInt(threads)?.coerceIn(0, 16) ?: 0,
      lowRamMode = lowRam
    )
    val rp = RepeatPenaltyConfig(
      repeatPenalty = parseFloat(repPen) ?: 1.1f,
      freqPenalty = parseFloat(freqPen) ?: 0f,
      presPenalty = parseFloat(presPen) ?: 0f
    )
    SettingsManager.save(cfg, rp)
    SettingsManager.systemPrompt = sysPrompt
    SettingsManager.reasoningEnabled = reasoningEnabled
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
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      var expandedSampling by remember { mutableStateOf(true) }
      var expandedAdvanced by remember { mutableStateOf(false) }
      var expandedAbout by remember { mutableStateOf(false) }

      CollapsibleCard("Sampling & Generation", expandedSampling, { expandedSampling = !expandedSampling }, colors) {
        SettingField("Temperature", "0-2 (lower = more focused)", temp, { temp = it })
        SettingField("Top-P", "0-1 (nucleus sampling)", topP, { topP = it })
        SettingField("Min-P", "0-1 (filter unlikely tokens)", minP, { minP = it })
        SettingField("Repeat Penalty", "1.0=off, >1 reduces repeats", repPen, { repPen = it })
        SettingField("Freq Penalty", "0=off, penalizes frequent tokens", freqPen, { freqPen = it })
        SettingField("Presence Penalty", "0=off, penalizes seen tokens", presPen, { presPen = it })
        SettingField("Context Window", "512-32768", nCtx, { nCtx = it })
        SettingField("Max Tokens", "64-8192", maxTok, { maxTok = it })
        SettingField("Batch Size", "512-8192", batch, { batch = it })
        SettingField("GPU Layers", "99=GPU, 0=CPU", gpu, { gpu = it })
        SettingField("Threads", "0=auto, 1-16", threads, { threads = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Low RAM Mode", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
          Switch(
            checked = lowRam,
            onCheckedChange = { lowRam = it },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
          )
        }
      }

      CollapsibleCard("System & Advanced", expandedAdvanced, { expandedAdvanced = !expandedAdvanced }, colors) {
        OutlinedTextField(
          value = sysPrompt,
          onValueChange = { sysPrompt = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("System Prompt", fontSize = 12.sp) },
          maxLines = 4,
          shape = RoundedCornerShape(10.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.Accent,
            unfocusedBorderColor = colors.Border,
            focusedTextColor = colors.Text,
            unfocusedTextColor = colors.Text,
            cursorColor = colors.Accent
          ),
          textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        )
        Text(
          "Context/GPU changes need model reload.",
          fontSize = 10.sp, color = colors.Amber, fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Enable reasoning", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
          Switch(
            checked = reasoningEnabled,
            onCheckedChange = { reasoningEnabled = it },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Dark Theme", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
          Switch(
            checked = isDark,
            onCheckedChange = {
              isDark = it
              SettingsManager.isDarkTheme = it
            },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
          )
        }
        Text("Prompt Cache", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.Accent2)
        SettingField("Sink Tokens", "1-32 (first tokens to keep)", sinkTokens, { sinkTokens = it })
        SettingField("Recent Tokens", "64-2048 (last tokens to keep)", recentTokens, { recentTokens = it })
        SettingField("Evict Threshold", "0.5-0.99 (% full before eviction)", evictThreshold, { evictThreshold = it })
        OutlinedButton(
          onClick = {
            SettingsManager.kvSinkTokens = sinkTokens.toIntOrNull()?.coerceIn(1, 32) ?: 4
            SettingsManager.kvRecentTokens = recentTokens.toIntOrNull()?.coerceIn(64, 2048) ?: 512
            SettingsManager.kvEvictThreshold = evictThreshold.toFloatOrNull()?.coerceIn(0.5f, 0.99f) ?: 0.85f
            scope.launch { snackbarHostState.showSnackbar("StreamingLLM saved") }
          },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Accent2)
        ) { Text("Save StreamingLLM", fontSize = 12.sp) }
        OutlinedButton(
          onClick = {
            engine?.clearCache()
            scope.launch { snackbarHostState.showSnackbar("Prompt cache cleared") }
          },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Amber)
        ) { Text("Clear Prompt Cache", fontSize = 12.sp) }
        OutlinedButton(
          onClick = { showResetConfirm = true },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Amber)
        ) { Text("Reset Context", fontSize = 12.sp) }
        OutlinedButton(
          onClick = { app.activeEngine.let { e ->
            if (e.isLoaded) scope.launch { e.unload() }
          } },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Red)
        ) { Text("Unload Model", fontSize = 12.sp) }
        OutlinedButton(
          onClick = {
            val info = app.deviceUtils.detect()
            SettingsManager.applyDeviceDefaults(info)
            nCtx = SettingsManager.nCtx.toString()
            maxTok = SettingsManager.maxTokens.toString()
            batch = SettingsManager.nBatch.toString()
            gpu = SettingsManager.gpuLayers.toString()
            threads = SettingsManager.threads.toString()
            scope.launch { snackbarHostState.showSnackbar("Device defaults applied") }
          },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Accent2)
        ) { Text("Apply Device Defaults", fontSize = 12.sp) }
      }

      CollapsibleCard("About", expandedAbout, { expandedAbout = !expandedAbout }, colors) {
        Text("Developer: adeennour4-dot", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Text("GitHub: github.com/adeennour4-dot/111", fontSize = 11.sp, color = colors.Accent,
          fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
          try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/adeennour4-dot/111")))
          } catch (_: Exception) {}
        })
        Text("App Version: 1.0.2", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        val deviceInfo = remember { app.deviceUtils.detect() }
        Text("RAM: ${deviceInfo.totalRamMB / 1024} GB total", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Text("Device: ${Build.MODEL}", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
      }

      Button(
        onClick = {
          saveSettings()
          scope.launch { snackbarHostState.showSnackbar("Settings saved") }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
      ) {
        Icon(Icons.Filled.Save, null, tint = colors.Bg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Save Settings", color = colors.Bg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      }

      Spacer(Modifier.height(32.dp))
    }
  }

  if (showResetConfirm) {
    AlertDialog(
      onDismissRequest = { showResetConfirm = false },
      containerColor = colors.Card,
      title = { Text("Reset Context?", color = colors.Text) },
      text = { Text("This will clear the model's context window. The model will remain loaded.", color = colors.Text2) },
      confirmButton = {
        TextButton(onClick = {
          scope.launch { engine.unload(); engine.load(SettingsManager.lastModelPath, SettingsManager.toEngineConfig()) }
          showResetConfirm = false
          scope.launch { snackbarHostState.showSnackbar("Model reloaded") }
        }) { Text("Reset", color = colors.Red) }
      },
      dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", color = colors.Text2) } }
    )
  }
}

@Composable
fun CollapsibleCard(title: String, expanded: Boolean, onToggle: () -> Unit, colors: ZcPalette, content: @Composable () -> Unit) {
  Column {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.Accent,
        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
      Icon(
        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        null, tint = colors.Text3, modifier = Modifier.size(20.dp)
      )
    }
    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically(),
      exit = shrinkVertically()
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
      }
    }
  }
}

@Composable
fun SettingField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
  val colors = currentPalette()
  Column {
    Text(label, fontSize = 11.sp, color = colors.Text2)
    Text(hint, fontSize = 9.sp, color = colors.Text3)
    OutlinedTextField(
      value = value,
      onValueChange = onChange,
      modifier = Modifier.fillMaxWidth().height(52.dp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      shape = RoundedCornerShape(10.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
        focusedTextColor = colors.Text, unfocusedTextColor = colors.Text, cursorColor = colors.Accent
      ),
      textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    )
  }
}
