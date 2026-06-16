package com.gguf.zerocopy.ui.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.theme.currentPalette
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val colors = currentPalette()
  var serverEnabled by remember { mutableStateOf(SettingsManager.serverEnabled) }
  var serverIp by remember { mutableStateOf(SettingsManager.serverIp) }
  var serverPort by remember { mutableStateOf(SettingsManager.serverPort.toString()) }
  var authEnabled by remember { mutableStateOf(SettingsManager.serverAuthEnabled) }
  var authToken by remember { mutableStateOf(SettingsManager.serverAuthToken) }
  var wifiOnly by remember { mutableStateOf(SettingsManager.serverWifiOnly) }
  var localIp by remember { mutableStateOf(getLocalIpAddress()) }
  var copied by remember { mutableStateOf(false) }

  fun save() {
    SettingsManager.serverEnabled = serverEnabled
    SettingsManager.serverIp = serverIp
    SettingsManager.serverPort = serverPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
    SettingsManager.serverAuthEnabled = authEnabled
    SettingsManager.serverAuthToken = authToken
    SettingsManager.serverWifiOnly = wifiOnly
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Remote Server", fontWeight = FontWeight.Bold, color = colors.Text) },
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
            Icon(
              if (serverEnabled) Icons.Filled.Cloud else Icons.Outlined.CloudOff,
              null,
              tint = if (serverEnabled) colors.Accent2 else colors.Text3,
              modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("Server", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.Text)
              Text(if (serverEnabled) "Running" else "Stopped", fontSize = 11.sp, color = if (serverEnabled) colors.Accent2 else colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Switch(
              checked = serverEnabled,
              onCheckedChange = { serverEnabled = it },
              colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
            )
          }
        }
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Connection", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)

          OutlinedTextField(
            value = serverIp, onValueChange = { serverIp = it },
            label = { Text("IP Address", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text, cursorColor = colors.Accent),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
          )

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
            Icon(Icons.Outlined.Wifi, null, modifier = Modifier.size(18.dp), tint = colors.Text2)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
              Text("WiFi Only", fontSize = 13.sp, color = colors.Text)
              Text("Only serve on WiFi networks", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Switch(
              checked = wifiOnly,
              onCheckedChange = { wifiOnly = it },
              colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
            )
          }
        }
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Authentication", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)

          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Require Auth Token", fontSize = 13.sp, color = colors.Text)
              Text("Clients must provide API key", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
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
              shape = RoundedCornerShape(10.dp),
              colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text, cursorColor = colors.Accent),
              textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            )
          }
        }
      }

      Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = colors.Card)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Your Device Info", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text2)
          Text("Local IP: $localIp", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
          val currentPort = serverPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
          Text("API URL: http://$localIp:$currentPort/v1", fontSize = 11.sp, color = if (serverEnabled) colors.Accent2 else colors.Text3, fontFamily = FontFamily.Monospace)
          Text("curl http://$localIp:$currentPort/v1/chat/completions", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)

          Spacer(Modifier.height(4.dp))

          Button(
            onClick = {
              val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clip.setPrimaryClip(ClipData.newPlainText("api_url", "http://$localIp:$currentPort/v1"))
              copied = true
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.Purple),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (copied) "Copied!" else "Copy API URL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }

          Button(
            onClick = {
              localIp = getLocalIpAddress()
              copied = false
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.Accent),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh IP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      Button(
        onClick = { save() },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
      ) {
        Text("Save Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      }

      Spacer(Modifier.height(16.dp))
    }
  }
}

private fun getLocalIpAddress(): String {
  try {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    while (interfaces.hasMoreElements()) {
      val intf = interfaces.nextElement()
      val addrs = intf.inetAddresses
      while (addrs.hasMoreElements()) {
        val addr = addrs.nextElement()
        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
          return addr.hostAddress ?: "127.0.0.1"
        }
      }
    }
  } catch (_: Exception) {}
  return "127.0.0.1"
}
