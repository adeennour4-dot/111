package com.gguf.zerocopy.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.server.ModelServerService
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RepeatPenaltyConfig
import com.gguf.zerocopy.ui.chat.components.getFileName
import com.gguf.zerocopy.ui.theme.currentPalette
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
  var mmprojPath by remember { mutableStateOf(SettingsManager.mmprojPath) }
  var reasoningEnabled by remember { mutableStateOf(SettingsManager.reasoningEnabled) }
  var ragEnabled by remember { mutableStateOf(SettingsManager.ragEnabled) }
  var showResetConfirm by remember { mutableStateOf(false) }
  var serverPort by remember { mutableStateOf(SettingsManager.serverPort.toString()) }
  var serverAuthEnabled by remember { mutableStateOf(SettingsManager.serverAuthEnabled) }
  var serverAuthToken by remember { mutableStateOf(SettingsManager.serverAuthToken) }
  var serverWifiOnly by remember { mutableStateOf(SettingsManager.serverWifiOnly) }
  var showToken by remember { mutableStateOf(false) }

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
      nGpuLayers = gpu.toIntOrNull()?.coerceIn(0, 999) ?: 99,
      nThreads = threads.toIntOrNull()?.coerceIn(0, 16) ?: 0,
      lowRamMode = lowRam,
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
      Text(
        "Sampling",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      SettingField("Temperature", "0-2 (lower = more focused)", temp, { temp = it })
      SettingField("Top-P", "0-1 (nucleus sampling)", topP, { topP = it })
      SettingField("Min-P", "0-1 (filter unlikely tokens)", minP, { minP = it })
      SettingField("Repeat Penalty", "1.0=off, >1 reduces repeats", repPen, { repPen = it })
      SettingField("Freq Penalty", "0=off, penalizes frequent tokens", freqPen, { freqPen = it })
      SettingField("Presence Penalty", "0=off, penalizes seen tokens", presPen, { presPen = it })

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "Generation",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

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

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "System",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
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
        fontSize = 10.sp,
        color = colors.Amber,
        fontFamily = FontFamily.Monospace
      )

      OutlinedButton(
        onClick = {
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
          }
          mmprojPicker.launch(intent)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Purple)
      ) {
        Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp), tint = colors.Purple)
        Spacer(Modifier.width(6.dp))
        Text(
          if (mmprojPath.isEmpty()) "Load Vision mmproj" else "mmproj: ${mmprojPath.substringAfterLast('/')}",
          fontSize = 11.sp,
          maxLines = 1
        )
      }

      OutlinedButton(
        onClick = { showResetConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Amber)
      ) {
        Text("Reset Context", fontSize = 12.sp)
      }

      OutlinedButton(
        onClick = { engineManager.unloadAll() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Red)
      ) {
        Text("Unload All Models", fontSize = 12.sp)
      }

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
      ) {
        Text("Apply Device Defaults", fontSize = 12.sp)
      }

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "Reasoning",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Enable reasoning", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = reasoningEnabled,
          onCheckedChange = { reasoningEnabled = it },
          colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
        )
      }

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "RAG & Documents",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Retrieval-Augmented Generation", fontSize = 13.sp, color = colors.Text2)
          Text("Inject document context into prompts", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        }
        Switch(
          checked = ragEnabled,
          onCheckedChange = { ragEnabled = it },
          colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
        )
      }

      val ragEngine = app.ragEngine
      if (ragEngine.hasDocuments) {
        Spacer(Modifier.height(6.dp))
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = colors.CardLight
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text("Loaded documents (${ragEngine.documentNames.size})", fontSize = 11.sp, color = colors.Text2)
            ragEngine.documentNames.forEach { name ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, tint = colors.Purple, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(name, fontSize = 10.sp, color = colors.Text3, maxLines = 1, modifier = Modifier.weight(1f))
              }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { ragEngine.clear() }) {
              Text("Clear all documents", fontSize = 10.sp, color = colors.Red)
            }
          }
        }
      } else {
        Spacer(Modifier.height(6.dp))
        Text("No documents loaded — attach files in chat to build context", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
      }

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "Appearance",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

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

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "Server",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model Server", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = app.modelServer.isRunning,
          onCheckedChange = {
            if (it) {
              val engine = app.engineManager.getActiveEngine()
              if (engine?.loadedModelPath != null) {
                val path = engine.loadedModelPath ?: ""
                val name = path.substringAfterLast('/')
                SettingsManager.lastModelPath = path
                SettingsManager.lastModelName = name
              }
              app.modelServer.setAutoModel(SettingsManager.lastModelPath, SettingsManager.lastModelName)
              context.startService(Intent(context, ModelServerService::class.java))
              SettingsManager.serverEnabled = true
            } else {
              context.stopService(Intent(context, ModelServerService::class.java))
              SettingsManager.serverEnabled = false
            }
          },
          colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent2, checkedThumbColor = colors.Bg)
        )
      }

      if (app.modelServer.isRunning) {
        Text(
          "Server: ${app.modelServer.getServerUrl()}",
          fontSize = 10.sp,
          color = colors.Accent2,
          fontFamily = FontFamily.Monospace
        )
        Text(
          "Anyone on your WiFi can access the web UI",
          fontSize = 10.sp,
          color = colors.Text3,
          fontFamily = FontFamily.Monospace
        )
      }

      SettingField("Port", "1024-65535", serverPort, { serverPort = it })

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auth enabled", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = serverAuthEnabled,
          onCheckedChange = { serverAuthEnabled = it },
          colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
        )
      }

      if (serverAuthEnabled) {
        OutlinedTextField(
          value = serverAuthToken,
          onValueChange = { serverAuthToken = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Auth Token", fontSize = 12.sp) },
          singleLine = true,
          visualTransformation = if (showToken) VisualTransformation.None
            else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showToken = !showToken }) {
              Icon(
                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                if (showToken) "Hide token" else "Show token",
                tint = colors.Text3
              )
            }
          },
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
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("WiFi only", fontSize = 13.sp, color = colors.Text2, modifier = Modifier.weight(1f))
        Switch(
          checked = serverWifiOnly,
          onCheckedChange = { serverWifiOnly = it },
          colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
        )
      }

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

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

      HorizontalDivider(color = colors.Border, thickness = 1.dp)

      Text(
        "About",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
      )

      Text(
        "Developer: adeennour4-dot",
        fontSize = 11.sp,
        color = colors.Text3,
        fontFamily = FontFamily.Monospace
      )

      Text(
        "GitHub: github.com/adeennour4-dot/111",
        fontSize = 11.sp,
        color = colors.Accent,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable {
          try {
            context.startActivity(
              android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/adeennour4-dot/111")
              )
            )
          } catch (_: Exception) {}
        }
      )

      Text(
        "App Version: 1.0.2",
        fontSize = 10.sp,
        color = colors.Text3,
        fontFamily = FontFamily.Monospace
      )

      val deviceInfo = remember { app.deviceUtils.detect() }
      Text(
        "RAM: ${deviceInfo.totalRamMB / 1024} GB total",
        fontSize = 10.sp,
        color = colors.Text3,
        fontFamily = FontFamily.Monospace
      )
      Text(
        "Device: ${Build.MODEL}",
        fontSize = 10.sp,
        color = colors.Text3,
        fontFamily = FontFamily.Monospace
      )

      Spacer(Modifier.height(32.dp))
    }
  }

  if (showResetConfirm) {
    AlertDialog(
      onDismissRequest = { showResetConfirm = false },
      containerColor = colors.Card,
      title = { Text("Reset Context?", color = colors.Text) },
      text = {
        Text(
          "This will clear the model's context window and conversation history. The model will remain loaded.",
          color = colors.Text2
        )
      },
      confirmButton = {
        TextButton(onClick = {
          val active = engineManager.getActiveEngine()
          if (active != null) active.resetContext()
          showResetConfirm = false
          scope.launch { snackbarHostState.showSnackbar("Context reset") }
        }) { Text("Reset", color = colors.Red) }
      },
      dismissButton = {
        TextButton(onClick = { showResetConfirm = false }) {
          Text("Cancel", color = colors.Text2)
        }
      }
    )
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
        focusedBorderColor = colors.Accent,
        unfocusedBorderColor = colors.Border,
        focusedTextColor = colors.Text,
        unfocusedTextColor = colors.Text,
        cursorColor = colors.Accent
      ),
      textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    )
  }
}
