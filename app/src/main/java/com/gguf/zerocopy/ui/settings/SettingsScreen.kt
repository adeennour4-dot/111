package com.gguf.zerocopy.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RepeatPenaltyConfig
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val app = ZeroCopyApp.instance
  val engineManager = app.engineManager
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

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

  fun saveSettings() {
    val cfg = InferenceConfig(
      nCtx = nCtx.toIntOrNull()?.coerceIn(512, 32768) ?: 2048,
      maxNewTokens = maxTok.toIntOrNull()?.coerceIn(64, 8192) ?: 2048,
      nBatch = batch.toIntOrNull()?.coerceIn(512, 8192) ?: 2048,
      temperature = temp.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.5f,
      topP = topP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f,
      minP = minP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.1f,
      nGpuLayers = gpu.toIntOrNull()?.coerceIn(0, 999) ?: 99,
      nThreads = threads.toIntOrNull()?.coerceIn(0, 16) ?: 0,
      lowRamMode = lowRam
    )
    val rp = RepeatPenaltyConfig(
      repeatPenalty = repPen.toFloatOrNull() ?: 1.1f,
      freqPenalty = freqPen.toFloatOrNull() ?: 0f,
      presPenalty = presPen.toFloatOrNull() ?: 0f
    )
    SettingsManager.save(cfg, rp)
    SettingsManager.systemPrompt = sysPrompt

    val active = engineManager.getActiveEngine()
    active?.let {
      it.config = cfg
      it.repeatPenalty = rp
      it.systemPrompt = sysPrompt
    }
  }

  BackHandler(onBack = {
    saveSettings()
    onBack()
  })

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings", fontWeight = FontWeight.Bold, color = ZcColors.Text) },
        navigationIcon = {
          IconButton(onClick = {
            saveSettings()
            onBack()
          }) { Icon(Icons.Filled.ArrowBack, "Back", tint = ZcColors.Text2) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ZcColors.Bg)
      )
    },
    containerColor = ZcColors.Bg,
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { pad ->
    Column(
      modifier = Modifier
        .padding(pad)
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text(
        "Sampling",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = ZcColors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      SettingField("Temperature", "0-2 (lower = more focused)", temp, { temp = it })
      SettingField("Top-P", "0-1 (nucleus sampling)", topP, { topP = it })
      SettingField("Min-P", "0-1 (filter unlikely tokens)", minP, { minP = it })
      SettingField("Repeat Penalty", "1.0=off, >1 reduces repeats", repPen, { repPen = it })
      SettingField("Freq Penalty", "0=off, penalizes frequent tokens", freqPen, { freqPen = it })
      SettingField("Presence Penalty", "0=off, penalizes seen tokens", presPen, { presPen = it })

      HorizontalDivider(color = ZcColors.Border, thickness = 1.dp)

      Text(
        "Generation",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = ZcColors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      SettingField("Context Window", "512-32768", nCtx, { nCtx = it })
      SettingField("Max Tokens", "64-8192", maxTok, { maxTok = it })
      SettingField("Batch Size", "512-8192", batch, { batch = it })
      SettingField("GPU Layers", "99=GPU, 0=CPU", gpu, { gpu = it })
      SettingField("Threads", "0=auto, 1-16", threads, { threads = it })

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Low RAM Mode", fontSize = 13.sp, color = ZcColors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = lowRam,
          onCheckedChange = { lowRam = it },
          colors = SwitchDefaults.colors(checkedTrackColor = ZcColors.Accent, checkedThumbColor = ZcColors.Bg)
        )
      }

      HorizontalDivider(color = ZcColors.Border, thickness = 1.dp)

      Text(
        "Appearance",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = ZcColors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dark Theme", fontSize = 13.sp, color = ZcColors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = isDark,
          onCheckedChange = {
            isDark = it
            SettingsManager.isDarkTheme = it
          },
          colors = SwitchDefaults.colors(checkedTrackColor = ZcColors.Accent, checkedThumbColor = ZcColors.Bg)
        )
      }

      HorizontalDivider(color = ZcColors.Border, thickness = 1.dp)

      Text(
        "System",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = ZcColors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      OutlinedTextField(
        value = sysPrompt,
        onValueChange = { sysPrompt = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("System Prompt", fontSize = 12.sp) },
        maxLines = 4,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ZcColors.Accent,
          unfocusedBorderColor = ZcColors.Border,
          focusedTextColor = ZcColors.Text,
          unfocusedTextColor = ZcColors.Text,
          cursorColor = ZcColors.Accent
        ),
        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
      )

      Text(
        "Context/GPU changes need model reload.",
        fontSize = 10.sp,
        color = ZcColors.Amber,
        fontFamily = FontFamily.Monospace
      )

      Button(
        onClick = {
          saveSettings()
          scope.launch { snackbarHostState.showSnackbar("Settings saved") }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ZcColors.Accent)
      ) {
        Box(
          modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(ZcColors.GradientStart, ZcColors.GradientEnd))),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Filled.Save, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Save Settings", color = ZcColors.Bg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      }

      OutlinedButton(
        onClick = {
          val active = engineManager.getActiveEngine()
          if (active != null) active.resetContext()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZcColors.Amber)
      ) {
        Text("Reset Context", fontSize = 12.sp)
      }

      OutlinedButton(
        onClick = { engineManager.unloadAll() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZcColors.Red)
      ) {
        Text("Unload All Models", fontSize = 12.sp)
      }

      Spacer(Modifier.height(32.dp))
    }
  }
}

@Composable
fun SettingField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
  Column {
    Text(label, fontSize = 11.sp, color = ZcColors.Text2)
    Text(hint, fontSize = 9.sp, color = ZcColors.Text3)
    OutlinedTextField(
      value = value,
      onValueChange = onChange,
      modifier = Modifier.fillMaxWidth().height(52.dp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      shape = RoundedCornerShape(10.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ZcColors.Accent,
        unfocusedBorderColor = ZcColors.Border,
        focusedTextColor = ZcColors.Text,
        unfocusedTextColor = ZcColors.Text,
        cursorColor = ZcColors.Accent
      ),
      textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    )
  }
}
