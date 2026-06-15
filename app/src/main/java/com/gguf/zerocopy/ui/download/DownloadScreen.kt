package com.gguf.zerocopy.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.DownloadableModel
import com.gguf.zerocopy.data.repository.ModelDownloads
import com.gguf.zerocopy.data.repository.ModelRepository
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(onModelSelected: (String, String) -> Unit, onBack: () -> Unit) {
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val deviceInfo = remember { app.deviceUtils.detect() }
  val recommended = remember { ModelDownloads.recommendForRam(deviceInfo.availableRamMB) }

  var downloadTask by remember { mutableStateOf<ModelRepository.DownloadTask?>(null) }
  var downloadProgress by remember { mutableFloatStateOf(0f) }
  var statusText by remember { mutableStateOf("") }
  var cancelled by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Download Models", fontWeight = FontWeight.Bold, color = ZcColors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = ZcColors.Text2)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ZcColors.Bg)
      )
    },
    containerColor = ZcColors.Bg
  ) { pad ->
    Column(modifier = Modifier.padding(pad).fillMaxSize()) {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = ZcColors.CardLight
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            "Device: ${deviceInfo.socModel.ifEmpty { "Unknown" }}",
            fontSize = 11.sp,
            color = ZcColors.Text2,
            fontFamily = FontFamily.Monospace
          )
          Text(
            "RAM: ${deviceInfo.availableRamMB}MB available / ${deviceInfo.totalRamMB}MB total",
            fontSize = 11.sp,
            color = ZcColors.Text2,
            fontFamily = FontFamily.Monospace
          )
          Text(
            "Recommended models shown below",
            fontSize = 11.sp,
            color = ZcColors.Accent2,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        items(recommended, key = { it.id }) { model ->
          DownloadCard(
            model = model,
            isDownloading = downloadTask?.isRunning == true,
            progress = downloadProgress,
            onClick = {
              if (downloadTask?.isRunning == true) return@DownloadCard
              statusText = "Downloading ${model.name}..."
              cancelled = false
              val task = app.modelRepository.downloadFromHf(
                repo = model.hfRepo,
                filename = model.hfFile,
                onProgress = { progress -> downloadProgress = progress },
                onCancel = { cancelled }
              )
              downloadTask = task
              scope.launch(Dispatchers.IO) {
                while (task.isRunning) {
                  kotlinx.coroutines.delay(200)
                }
                val result = task.result
                if (result?.isSuccess == true) {
                  val localModel = result.getOrThrow()
                  val engine = app.engineManager.selectEngineForFormat(localModel.path)
                  engine.config = SettingsManager.toConfig()
                  engine.repeatPenalty = SettingsManager.toRepeatPenalty()
                  engine.systemPrompt = SettingsManager.systemPrompt
                  val loadResult = engine.loadModel(localModel.path)
                  if (loadResult.isSuccess) {
                    app.modelRepository.markUsed(localModel.id)
                    onModelSelected(localModel.path, localModel.name)
                  } else {
                    statusText = "Load failed: ${loadResult.exceptionOrNull()?.message}"
                  }
                } else {
                  statusText = if (task.isRunning) {
                    "Download failed: ${result?.exceptionOrNull()?.message}"
                  } else {
                    "Download cancelled"
                  }
                }
                downloadTask = null
              }
            },
            onCancel = {
              cancelled = true
              downloadTask?.cancel()
            }
          )
        }
      }

      if (statusText.isNotEmpty() && downloadTask?.isRunning != true) {
        Surface(modifier = Modifier.fillMaxWidth(), color = ZcColors.Card) {
          Text(
            statusText,
            modifier = Modifier.padding(16.dp),
            fontSize = 11.sp,
            color = ZcColors.Text3,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}

@Composable
fun DownloadCard(
  model: DownloadableModel,
  isDownloading: Boolean,
  progress: Float,
  onClick: () -> Unit,
  onCancel: () -> Unit = {}
) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(enabled = !isDownloading, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = ZcColors.CardLight
  ) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(
        if (isDownloading) Icons.Outlined.Downloading else Icons.Filled.CloudDownload,
        null,
        modifier = Modifier.size(32.dp),
        tint = if (isDownloading) ZcColors.Accent2 else ZcColors.Accent
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(model.name, color = ZcColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(model.description, fontSize = 11.sp, color = ZcColors.Text2, maxLines = 2)
        Row {
          Text(
            model.format.uppercase(),
            fontSize = 10.sp,
            color = ZcColors.Accent,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            formatSize(model.sizeBytes),
            fontSize = 10.sp,
            color = ZcColors.Text3,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            model.engine.id,
            fontSize = 10.sp,
            color = ZcColors.Accent2,
            fontFamily = FontFamily.Monospace
          )
        }
        if (isDownloading) {
          Spacer(Modifier.height(4.dp))
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = ZcColors.Accent2,
            trackColor = ZcColors.Card
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "%.0f%%".format(progress * 100),
              fontSize = 10.sp,
              color = ZcColors.Accent2,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
              Icon(
                Icons.Filled.Close,
                "Cancel",
                tint = ZcColors.Red,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }
    }
  }
}

private fun formatSize(bytes: Long): String = when {
  bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
  bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
  bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
  else -> "$bytes B"
}
