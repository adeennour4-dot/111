package com.gguf.zerocopy.ui.models

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListScreen(onModelSelected: (String, String) -> Unit, onBack: () -> Unit) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val colors = currentPalette()
  val models by app.modelRepository.models.collectAsState(initial = emptyList())
  var loading by remember { mutableStateOf(false) }
  var infoModel by remember { mutableStateOf<com.gguf.zerocopy.data.repository.LocalModel?>(null) }
  var unloadKey by remember { mutableStateOf(0) }
  val loadedPath by remember {
    derivedStateOf {
      unloadKey
      val e = app.engineManager.getActiveEngine()
      if (e?.isModelLoaded == true) e.loadedModelPath else null
    }
  }

  val filePicker =
    rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val name = getFileName(context, uri)
          loading = true
          scope.launch {
            val result = app.modelRepository.importUri(uri, name)
            if (result.isSuccess) {
              val model = result.getOrThrow()
              val engine = app.engineManager.selectEngineForFormat(model.path)
              engine.config = SettingsManager.toConfig()
              engine.repeatPenalty = SettingsManager.toRepeatPenalty()
              engine.systemPrompt = SettingsManager.systemPrompt
              val loadResult = engine.loadModel(model.path)
              withContext(Dispatchers.Main) {
                loading = false
                if (loadResult.isSuccess) {
                  app.modelRepository.markUsed(model.id)
                  onModelSelected(model.path, model.name)
                }
              }
            } else {
              loading = false
            }
          }
        }
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Your Models", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
          }
        },
        actions = {
          IconButton(onClick = {
            val intent =
              Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
              }
            filePicker.launch(intent)
          }) {
            Icon(Icons.Filled.Add, "Import", tint = colors.Accent)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
      )
    },
    containerColor = colors.Bg
  ) { pad ->
    Box(modifier = Modifier.padding(pad).fillMaxSize()) {
      if (models.isEmpty() && !loading) {
        Column(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            Icons.Outlined.SmartToy,
            null,
            modifier = Modifier.size(48.dp),
            tint = colors.Text3
          )
          Spacer(Modifier.height(16.dp))
          Text("No models imported", color = colors.Text3, fontSize = 16.sp)
          Text("Tap + to add a model file", color = colors.Text3, fontSize = 13.sp)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(models, key = { it.id }) { model ->
            ModelCard(
              model = model,
              isLoaded = loadedPath == model.path,
              onClick = {
                scope.launch {
                  val engine = app.engineManager.selectEngineForFormat(model.path)
                  engine.config = SettingsManager.toConfig()
                  engine.repeatPenalty = SettingsManager.toRepeatPenalty()
                  engine.systemPrompt = SettingsManager.systemPrompt
                  engine.mmprojPath = SettingsManager.mmprojPath
                  val loadResult = engine.loadModel(model.path)
                  if (loadResult.isSuccess) {
                    app.modelRepository.markUsed(model.id)
                    unloadKey++
                    onModelSelected(model.path, model.name)
                  }
                }
              },
              onInfo = { infoModel = model },
              onDelete = {
                val ok = app.modelRepository.deleteModel(model.id)
                if (!ok) {
                  Toast.makeText(
                    context,
                    "Failed to delete ${model.name}",
                    Toast.LENGTH_SHORT
                  ).show()
                }
              },
              onUnload = {
                app.engineManager.getActiveEngine()?.unloadModel()
                unloadKey++
              }
            )
          }
        }
      }

      infoModel?.let { model ->
        AlertDialog(
          onDismissRequest = { infoModel = null },
          containerColor = colors.Card,
          title = { Text("Model Info", color = colors.Text, fontWeight = FontWeight.Bold) },
          text = {
            Column {
              DetailRow("Name", model.name)
              DetailRow("Format", model.format.uppercase())
              DetailRow("Engine", model.engine.id)
              DetailRow("Size", model.sizeFormatted)
              DetailRow(
                "Added",
                java.text.SimpleDateFormat(
                  "MMM d, HH:mm",
                  java.util.Locale.getDefault()
                ).format(java.util.Date(model.addedAt))
              )
              if (model.lastUsed > 0) {
                DetailRow(
                  "Last used",
                  java.text.SimpleDateFormat(
                    "MMM d, HH:mm",
                    java.util.Locale.getDefault()
                  ).format(java.util.Date(model.lastUsed))
                )
              }
              DetailRow("Path", model.path)
            }
          },
          confirmButton = {
            TextButton(onClick = {
              infoModel = null
              scope.launch {
                val engine = app.engineManager.selectEngineForFormat(model.path)
                engine.config = SettingsManager.toConfig()
                engine.repeatPenalty = SettingsManager.toRepeatPenalty()
                engine.systemPrompt = SettingsManager.systemPrompt
                engine.mmprojPath = SettingsManager.mmprojPath
                val loadResult = engine.loadModel(model.path)
                if (loadResult.isSuccess) {
                  app.modelRepository.markUsed(model.id)
                  onModelSelected(model.path, model.name)
                }
              }
            }) { Text("Load", color = colors.Accent) }
          },
          dismissButton = {
            TextButton(onClick = { infoModel = null }) { Text("Close", color = colors.Text2) }
          }
        )
      }

      if (loading) {
        CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
          color = colors.Accent
        )
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  val colors = currentPalette()
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    Text("$label: ", fontSize = 12.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
    Text(value, fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelCard(
  model: com.gguf.zerocopy.data.repository.LocalModel,
  isLoaded: Boolean = false,
  onClick: () -> Unit,
  onInfo: () -> Unit = {},
  onDelete: () -> Unit,
  onUnload: () -> Unit = {}
) {
  val colors = currentPalette()
  var showDeleteConfirm by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = colors.CardLight
  ) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(model.name, color = colors.Text, fontSize = 14.sp, maxLines = 1)
        Row {
          Text(
            model.format.uppercase(),
            fontSize = 10.sp,
            color = colors.Accent,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            model.sizeFormatted,
            fontSize = 10.sp,
            color = colors.Text3,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            model.engine.id,
            fontSize = 10.sp,
            color = colors.Accent2,
            fontFamily = FontFamily.Monospace
          )
        }
      }
      if (isLoaded) {
        IconButton(onClick = onUnload, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Filled.Stop, "Unload", tint = colors.Red, modifier = Modifier.size(18.dp))
        }
      } else {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
          Icon(
            Icons.Filled.PlayArrow,
            "Load",
            tint = colors.Accent,
            modifier = Modifier.size(18.dp)
          )
        }
      }
      IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Info, "Info", tint = colors.Accent2, modifier = Modifier.size(18.dp))
      }
      IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Delete, "Delete", tint = colors.Red, modifier = Modifier.size(18.dp))
      }
    }
  }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      containerColor = colors.Card,
      title = { Text("Delete Model?", color = colors.Text, fontSize = 16.sp) },
      text = {
        Text("Remove ${model.name} from device?", color = colors.Text2, fontSize = 14.sp)
      },
      confirmButton = {
        TextButton(onClick = {
          showDeleteConfirm = false
          onDelete()
        }) { Text("Delete", color = colors.Red) }
      },
      dismissButton = {
        TextButton(onClick = {
          showDeleteConfirm = false
        }) { Text("Cancel", color = colors.Text2) }
      }
    )
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
