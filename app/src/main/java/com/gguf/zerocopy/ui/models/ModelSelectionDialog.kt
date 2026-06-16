package com.gguf.zerocopy.ui.models

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionSheet(onDismiss: () -> Unit, onModelSelected: (String, String) -> Unit) {
  val colors = currentPalette()
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val models by app.modelRepository.models.collectAsState(initial = emptyList())
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var loading by remember { mutableStateOf(false) }

  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val name = getFileName(context, uri)
          loading = true
          scope.launch(Dispatchers.IO) {
            val result = app.modelRepository.importUri(uri, name)
            if (result.isSuccess) {
              val model = result.getOrThrow()
              val engine = app.engineManager.selectEngineForFormat(model.path)
              engine.config = SettingsManager.toConfig()
              engine.repeatPenalty = SettingsManager.toRepeatPenalty()
              engine.systemPrompt = SettingsManager.systemPrompt
              val loadResult = engine.loadModel(model.path)
              if (loadResult.isSuccess) {
                app.modelRepository.markUsed(model.id)
                onModelSelected(model.path, model.name)
              }
            }
            loading = false
          }
        }
      }
    }

  var unloadKey by remember { mutableStateOf(0) }
  val loadedPath by remember {
    derivedStateOf {
      unloadKey
      val e = app.engineManager.getActiveEngine()
      if (e?.isModelLoaded == true) e.loadedModelPath else null
    }
  }
  var deleteModel by remember { mutableStateOf<LocalModel?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Select Model",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = colors.Text,
          modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
          }
          importLauncher.launch(intent)
        }) {
          Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp), tint = colors.Accent)
          Spacer(Modifier.width(4.dp))
          Text("Import", color = colors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
      }

      Spacer(Modifier.height(12.dp))

      if (loading) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
          contentAlignment = Alignment.Center
        ) {
          androidx.compose.material3.CircularProgressIndicator(color = colors.Accent)
        }
      } else if (models.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(Icons.Outlined.SmartToy, null, modifier = Modifier.size(48.dp), tint = colors.Text3)
          Spacer(Modifier.height(12.dp))
          Text("No models yet", color = colors.Text3, fontSize = 15.sp)
          Text("Import a model to get started", color = colors.Text3, fontSize = 12.sp)
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.height(300.dp)
        ) {
          items(models, key = { it.id }) { model ->
            val isLoaded = loadedPath == model.path
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  scope.launch(Dispatchers.IO) {
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
              shape = RoundedCornerShape(14.dp),
              color = colors.Card
            ) {
              Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                  modifier = Modifier
                    .size(44.dp)
                    .padding(end = 12.dp)
                ) {
                  when (model.format.lowercase()) {
                    "gguf" -> Text(
                      "G",
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Black,
                      color = colors.Accent,
                      fontFamily = FontFamily.Monospace
                    )
                    "mnn" -> Text(
                      "M",
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Black,
                      color = colors.Accent2,
                      fontFamily = FontFamily.Monospace
                    )
                    else -> Text(
                      "L",
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Black,
                      color = colors.Purple,
                      fontFamily = FontFamily.Monospace
                    )
                  }
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    model.name,
                    color = colors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
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
                  IconButton(onClick = {
                    app.engineManager.getActiveEngine()?.unloadModel()
                    unloadKey++
                  }, modifier = Modifier.size(32.dp)) {
                    Icon(
                      Icons.Filled.Stop,
                      "Unload",
                      tint = colors.Red,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                } else {
                  IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
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
                  }, modifier = Modifier.size(32.dp)) {
                    Icon(
                      Icons.Filled.PlayArrow,
                      "Load",
                      tint = colors.Accent,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }
                IconButton(onClick = { deleteModel = model }, modifier = Modifier.size(32.dp)) {
                  Icon(
                    Icons.Filled.Delete,
                    "Delete",
                    tint = colors.Red.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
    }
  }

  deleteModel?.let { model ->
    AlertDialog(
      onDismissRequest = { deleteModel = null },
      containerColor = colors.Card,
      title = { Text("Delete Model?", color = colors.Text, fontSize = 16.sp) },
      text = { Text("Remove ${model.name} from device?", color = colors.Text2, fontSize = 14.sp) },
      confirmButton = {
        TextButton(onClick = {
          val ok = app.modelRepository.deleteModel(model.id)
          if (!ok) {
            Toast.makeText(
              context,
              "Failed to delete ${model.name}",
              Toast.LENGTH_SHORT
            ).show()
          }
          deleteModel = null
        }) { Text("Delete", color = colors.Red) }
      },
      dismissButton = {
        TextButton(onClick = { deleteModel = null }) { Text("Cancel", color = colors.Text2) }
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
