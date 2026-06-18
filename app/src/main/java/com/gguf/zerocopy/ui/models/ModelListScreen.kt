package com.gguf.zerocopy.ui.models

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.gguf.zerocopy.domain.inference.EngineType
import com.gguf.zerocopy.ui.theme.currentPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelListScreen(
  onModelSelected: (path: String, name: String) -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val colors = currentPalette()
  val models by app.modelRepository.models.collectAsState(initial = emptyList())
  var loading by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var modelToDelete by remember { mutableStateOf<LocalModel?>(null) }
  var modelToDetail by remember { mutableStateOf<LocalModel?>(null) }
  var engineSwitchWarningModel by remember { mutableStateOf<LocalModel?>(null) }
  var longPressModel by remember { mutableStateOf<LocalModel?>(null) }

  val activeEngine = app.engineManager.getActiveEngine()
  val isModelLoaded = activeEngine?.isModelLoaded == true
  val loadedModelPath = if (isModelLoaded) activeEngine?.loadedModelPath else null

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        val name = getFileName(context, uri)
        loading = true
        scope.launch {
          app.modelRepository.importUri(uri, name)
            .onSuccess { model ->
              loading = false
              isLoading = true
              loadModel(model, onModelSelected)
              isLoading = false
            }
            .onFailure { loading = false }
        }
      }
    }
  }

  fun handleModelTap(model: LocalModel) {
    if (loadedModelPath == model.path && activeEngine != null) {
      activeEngine.unloadModel()
      return
    }
    val targetEngine = app.engineManager.selectEngineForFormat(model.path)
    if (isModelLoaded && activeEngine != targetEngine) {
      engineSwitchWarningModel = model
      return
    }
    isLoading = true
    scope.launch {
      loadModel(model, onModelSelected)
      isLoading = false
    }
  }

  fun confirmEngineSwitch(model: LocalModel) {
    engineSwitchWarningModel = null
    isLoading = true
    scope.launch {
      app.engineManager.unloadAll()
      loadModel(model, onModelSelected)
      isLoading = false
    }
  }

  fun confirmDelete(model: LocalModel) {
    if (loadedModelPath == model.path) {
      app.engineManager.getActiveEngine()?.unloadModel()
    }
    app.modelRepository.deleteModel(model.id)
    modelToDelete = null
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Models", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
          }
        },
        actions = {
          IconButton(onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "*/*"
              putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            }
            filePicker.launch(intent)
          }) {
            Icon(Icons.Filled.Add, "Import", tint = colors.Accent)
          }
          IconButton(onClick = { app.modelRepository.scanModels() }) {
            Icon(Icons.Filled.Refresh, "Scan", tint = colors.Accent2)
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
          Text("No models found. Import a model file to get started.", color = colors.Text3, fontSize = 14.sp)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(models, key = { it.id }) { model ->
            val isThisLoaded = loadedModelPath == model.path
            ModelCard(
              model = model,
              isLoaded = isThisLoaded,
              onClick = { handleModelTap(model) },
              onLongClick = { longPressModel = model }
            )
          }
        }
      }

      if (loading || isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
          color = colors.Accent
        )
      }

      longPressModel?.let { model ->
        DropdownMenu(
          expanded = true,
          onDismissRequest = { longPressModel = null },
          modifier = Modifier.background(colors.Card)
        ) {
          DropdownMenuItem(
            text = { Text("Details", color = colors.Text, fontSize = 14.sp) },
            onClick = {
              longPressModel = null
              modelToDetail = model
            },
            leadingIcon = {
              Icon(Icons.Filled.Info, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
            }
          )
          HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
          DropdownMenuItem(
            text = { Text("Delete", color = colors.Red, fontSize = 14.sp) },
            onClick = {
              longPressModel = null
              modelToDelete = model
            },
            leadingIcon = {
              Icon(Icons.Filled.Delete, null, tint = colors.Red, modifier = Modifier.size(18.dp))
            }
          )
        }
      }

      modelToDelete?.let { model ->
        AlertDialog(
          onDismissRequest = { modelToDelete = null },
          containerColor = colors.Card,
          title = { Text("Delete Model?", color = colors.Text, fontSize = 16.sp) },
          text = {
            Text("Remove ${model.name} from device? This cannot be undone.", color = colors.Text2, fontSize = 14.sp)
          },
          confirmButton = {
            TextButton(onClick = { confirmDelete(model) }) {
              Text("Delete", color = colors.Red)
            }
          },
          dismissButton = {
            TextButton(onClick = { modelToDelete = null }) {
              Text("Cancel", color = colors.Text2)
            }
          }
        )
      }

      modelToDetail?.let { model ->
        AlertDialog(
          onDismissRequest = { modelToDetail = null },
          containerColor = colors.Card,
          title = { Text("Model Details", color = colors.Text, fontWeight = FontWeight.Bold) },
          text = {
            Column {
              DetailRow("Name", model.name)
              DetailRow("Format", model.format.uppercase())
              DetailRow("Engine", model.engine.id)
              DetailRow("Size", model.sizeFormatted)
              DetailRow(
                "Added",
                SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(model.addedAt))
              )
              if (model.lastUsed > 0) {
                DetailRow(
                  "Last used",
                  SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(model.lastUsed))
                )
              }
              DetailRow("Path", model.path)
            }
          },
          confirmButton = {
            TextButton(onClick = {
              modelToDetail = null
              handleModelTap(model)
            }) {
              Text(if (loadedModelPath == model.path) "Unload" else "Load", color = colors.Accent)
            }
          },
          dismissButton = {
            TextButton(onClick = { modelToDetail = null }) {
              Text("Close", color = colors.Text2)
            }
          }
        )
      }

      engineSwitchWarningModel?.let { model ->
        AlertDialog(
          onDismissRequest = { engineSwitchWarningModel = null },
          containerColor = colors.Card,
          title = { Text("Switch Engine?", color = colors.Amber, fontSize = 16.sp) },
          text = {
            Column {
              Text(
                "Another model is currently loaded by a different engine. Loading ${model.name} will unload the current model.",
                color = colors.Text2,
                fontSize = 14.sp
              )
            }
          },
          confirmButton = {
            TextButton(onClick = { confirmEngineSwitch(model) }) {
              Text("Switch", color = colors.Accent)
            }
          },
          dismissButton = {
            TextButton(onClick = { engineSwitchWarningModel = null }) {
              Text("Cancel", color = colors.Text2)
            }
          }
        )
      }
    }
  }
}

private suspend fun loadModel(
  model: LocalModel,
  onModelSelected: (String, String) -> Unit
) {
  val app = ZeroCopyApp.instance
  val engine = app.engineManager.selectEngineForFormat(model.path)
  engine.config = SettingsManager.toConfig()
  engine.repeatPenalty = SettingsManager.toRepeatPenalty()
  engine.systemPrompt = SettingsManager.systemPrompt
  engine.mmprojPath = SettingsManager.mmprojPath
  withContext(Dispatchers.IO) {
    engine.loadModel(model.path)
  }.onSuccess {
    app.modelRepository.markUsed(model.id)
    onModelSelected(model.path, model.name)
  }.onFailure { e ->
    Log.e("ModelList", "Failed to load model: ${e.message}")
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
private fun ModelCard(
  model: LocalModel,
  isLoaded: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit = {}
) {
  val colors = currentPalette()
  val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick
      ),
    shape = RoundedCornerShape(12.dp),
    color = if (isLoaded) colors.CardLight else colors.Card
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.name,
            color = colors.Text,
            fontSize = 14.sp,
            fontWeight = if (isLoaded) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Spacer(Modifier.width(8.dp))
          EngineBadge(engine = model.engine)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
          if (model.lastUsed > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
              dateFormat.format(Date(model.lastUsed)),
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
          if (isLoaded) {
            Spacer(Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = colors.Accent2.copy(alpha = 0.2f)
            ) {
              Text(
                "LOADED",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 9.sp,
                color = colors.Accent2,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EngineBadge(engine: EngineType) {
  val colors = currentPalette()
  val (label, badgeColor) = when (engine) {
    EngineType.LLAMA_CPP -> "GGUF" to colors.Purple
    EngineType.MNN -> "MNN" to colors.Accent2
    EngineType.LITER_T -> "TFLite" to colors.Amber
  }
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = badgeColor.copy(alpha = 0.2f)
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      fontSize = 9.sp,
      color = badgeColor,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
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
