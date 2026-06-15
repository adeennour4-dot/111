package com.gguf.zerocopy.ui.welcome

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WelcomeScreen(onLoadModel: (String, String) -> Unit, onDownload: () -> Unit) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }

  val filePicker =
    rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val name = getFileName(context, uri)
          loading = true
          status = "Importing..."
          scope.launch {
            val result = app.modelRepository.importUri(uri, name)
            if (result.isSuccess) {
              val model = result.getOrThrow()
              status = "Loading..."
              val engine = app.engineManager.selectEngineForFormat(model.path)
              engine.config = SettingsManager.toConfig()
              engine.repeatPenalty = SettingsManager.toRepeatPenalty()
              engine.systemPrompt = SettingsManager.systemPrompt
              val loadResult = engine.loadModel(model.path)
              withContext(Dispatchers.Main) {
                loading = false
                if (loadResult.isSuccess) {
                  app.modelRepository.markUsed(model.id)
                  onLoadModel(model.path, model.name)
                } else {
                  status = "Failed: ${loadResult.exceptionOrNull()?.message}"
                }
              }
            } else {
              loading = false
              status = "Import failed"
            }
          }
        }
      }
    }

  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier =
      Modifier
        .size(80.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(ZcColors.GradientStart, ZcColors.GradientEnd))),
      contentAlignment = Alignment.Center
    ) {
      Text(
        "ZC",
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        color = Color.White,
        fontFamily = FontFamily.Monospace
      )
    }
    Spacer(Modifier.height(24.dp))
    Text(
      "ZeroCopy",
      fontSize = 22.sp,
      fontWeight = FontWeight.Bold,
      color = ZcColors.Text,
      fontFamily = FontFamily.Monospace
    )
    Text("Private on-device LLM", fontSize = 13.sp, color = ZcColors.Text2)
    Spacer(Modifier.height(8.dp))
    Text(
      "llama.cpp · MNN · LiteRT-LM",
      fontSize = 11.sp,
      color = ZcColors.Text3,
      fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(40.dp))

    Button(
      onClick = {
        val intent =
          Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
          }
        filePicker.launch(intent)
      },
      modifier = Modifier.fillMaxWidth().height(52.dp),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(containerColor = ZcColors.Accent)
    ) {
      Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text("Load Model", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
    }

    Spacer(Modifier.height(12.dp))

    OutlinedButton(
      onClick = onDownload,
      modifier = Modifier.fillMaxWidth().height(52.dp),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = ZcColors.Accent2)
    ) {
      Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        "Download Model",
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = ZcColors.Accent2
      )
    }

    if (loading) {
      Spacer(Modifier.height(16.dp))
      CircularProgressIndicator(
        color = ZcColors.Accent,
        modifier = Modifier.size(24.dp),
        strokeWidth = 2.dp
      )
      Spacer(Modifier.height(8.dp))
      Text(status, fontSize = 11.sp, color = ZcColors.Text3, fontFamily = FontFamily.Monospace)
    }
  }
}

private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
  var name = "model.gguf"
  context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) cursor.getString(idx)?.let { if (it.isNotEmpty()) name = it }
    }
  }
  if ('.' !in name) {
    val mime = context.contentResolver.getType(uri)
    name += when {
      mime?.contains("gguf") == true || mime == "application/octet-stream" -> ".gguf"
      mime?.contains("tensorflow") == true || mime?.contains("tflite") == true -> ".tflite"
      mime?.contains("litert") == true -> ".litertlm"
      else -> ".gguf"
    }
  }
  return name
}
