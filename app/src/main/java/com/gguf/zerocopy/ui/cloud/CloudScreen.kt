package com.gguf.zerocopy.ui.cloud

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
import com.gguf.zerocopy.ui.models.ModelSelectionDialog
import com.gguf.zerocopy.ui.theme.currentPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(onBack: () -> Unit) {
  val app = ZeroCopyApp.instance
  val colors = currentPalette()
  val models by app.modelRepository.models.collectAsState(initial = emptyList())

  var serverPort by remember { mutableStateOf(SettingsManager.serverPort.toString()) }
  var authEnabled by remember { mutableStateOf(SettingsManager.serverAuthEnabled) }
  var authToken by remember { mutableStateOf(SettingsManager.serverAuthToken) }
  var wifiOnly by remember { mutableStateOf(SettingsManager.serverWifiOnly) }
  var autoStartBoot by remember { mutableStateOf(SettingsManager.serverEnabled) }
  var showToken by remember { mutableStateOf(false) }
  var showModelDialog by remember { mutableStateOf(false) }
  var serverModelPath by remember { mutableStateOf(SettingsManager.serverModelPath) }
  var serverModelName by remember { mutableStateOf(SettingsManager.serverModelName) }

  val context = LocalContext.current
  val isRunning = app.modelServer.isRunning
  val localIp = app.modelServer.getLocalIp()
  val serverUrl = app.modelServer.getServerUrl()

  fun saveSettings() {
    SettingsManager.serverPort = serverPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
    SettingsManager.serverAuthEnabled = authEnabled
    SettingsManager.serverAuthToken = authToken
    SettingsManager.serverWifiOnly = wifiOnly
    SettingsManager.serverEnabled = autoStartBoot
    SettingsManager.serverModelPath = serverModelPath
    SettingsManager.serverModelName = serverModelName
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Server", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
      )
    },
    containerColor = colors.Bg
  ) { pad ->
    Column(
      modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier.size(12.dp).clip(CircleShape).background(if (isRunning) Color(0xFF00E6A8) else Color(0xFF5A5A78))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
              Text("Server Status", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text)
              Text(if (isRunning) "Running on $localIp" else "Stopped", fontSize = 11.sp, color = if (isRunning) colors.Accent2 else colors.Text3, fontFamily = FontFamily.Monospace)
            }
          }
          if (isRunning) {
            Text(serverUrl, fontSize = 11.sp, color = colors.Accent, fontFamily = FontFamily.Monospace)
          }
        }
      }

      Button(
        onClick = {
          if (isRunning) {
            context.stopService(Intent(context, ModelServerService::class.java))
          } else {
            saveSettings()
            app.modelServer.setAutoModel(serverModelPath.ifEmpty { SettingsManager.lastModelPath }, serverModelName.ifEmpty { SettingsManager.lastModelName })
            context.startService(Intent(context, ModelServerService::class.java))
          }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) colors.Red else colors.Accent2)
      ) {
        Icon(
          if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
          null,
          modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(if (isRunning) "Stop Server" else "Start Server", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Connected Clients", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)
          Text("No active connections", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        }
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Server Configuration", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)

          OutlinedTextField(
            value = serverPort, onValueChange = { serverPort = it },
            label = { Text("Port", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text, cursorColor = colors.Accent),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
          )

          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Authentication", fontSize = 13.sp, color = colors.Text)
              Text("Require API key from clients", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Switch(
              checked = authEnabled,
              onCheckedChange = { authEnabled = it },
              colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
            )
          }

          if (authEnabled) {
            OutlinedTextField(
              value = authToken, onValueChange = { authToken = it },
              label = { Text("Auth Token", fontSize = 12.sp) },
              modifier = Modifier.fillMaxWidth(), singleLine = true,
              visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
              trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                  Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = colors.Text3)
                }
              },
              shape = RoundedCornerShape(10.dp),
              colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text, cursorColor = colors.Accent),
              textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            )
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Wi-Fi Only", fontSize = 13.sp, color = colors.Text)
              Text("Only serve on Wi-Fi networks", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Switch(
              checked = wifiOnly,
              onCheckedChange = { wifiOnly = it },
              colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
            )
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Auto-start on Boot", fontSize = 13.sp, color = colors.Text)
              Text("Start server when app launches", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Switch(
              checked = autoStartBoot,
              onCheckedChange = { autoStartBoot = it },
              colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
            )
          }
        }
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Server Model", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)
          Text(
            if (serverModelName.isNotEmpty()) serverModelName else if (SettingsManager.lastModelName.isNotEmpty()) SettingsManager.lastModelName else "Use current engine model",
            fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace
          )
          Button(
            onClick = { showModelDialog = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.Accent),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Select Model for Server", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
          }
          if (serverModelPath.isNotEmpty()) {
            TextButton(onClick = {
              serverModelPath = ""
              serverModelName = ""
            }) {
              Text("Use app's current model instead", color = colors.Text3, fontSize = 11.sp)
            }
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      Button(
        onClick = { saveSettings() },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
      ) {
        Text("Save Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      }

      Spacer(Modifier.height(16.dp))
    }
  }

  if (showModelDialog) {
    ModelSelectionDialog(
      models = models,
      onSelect = { model ->
        serverModelPath = model.path
        serverModelName = model.name
        app.engineManager.selectEngineForFormat(model.path)
        showModelDialog = false
      },
      onDismiss = { showModelDialog = false }
    )
  }
}
