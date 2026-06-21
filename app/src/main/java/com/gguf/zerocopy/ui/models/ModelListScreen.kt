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
import com.gguf.zerocopy.ui.chat.components.DeleteConfirmDialog
import com.gguf.zerocopy.ui.chat.components.ModelDetailDialog
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
  val colors = currentPalette()
  val models by ZeroCopyApp.instance.modelRepository.models.collectAsState(initial = emptyList())
  var loading by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var modelToDelete by remember { mutableStateOf<LocalModel?>(null) }
  var modelToDetail by remember { mutableStateOf<LocalModel?>(null) }
  var longPressModel by remember { mutableStateOf<LocalModel?>(null) }
  var statusMsg by remember { mutableStateOf("") }
  var statusError by remember { mutableStateOf(false) }

  val scope = rememberCoroutineScope()
  val ggmlEngine = remember { GGMLEngine() }
  val isModelLoaded = ggmlEngine.isLoaded
  val loadedModelPath = if (isModelLoaded) SettingsManager.lastModelPath.takeIf { it.isNotEmpty() } else null

  fun loadAndSelect(model: LocalModel) {
    isLoading = true
    statusMsg = ""
    scope.launch {
      val cfg = SettingsManager.toConfig()
      val ok = withContext(Dispatchers.IO) {
        try {
          ggmlEngine.load(
            path = model.path,
            contextSize = cfg.nCtx,
            threads = cfg.nThreads,
            batchSize = cfg.nBatch,
            flashAttn = cfg.flashAttention,
            useMmap = true,
            useMlock = false,
            cacheTypeK = "q8_0",
            cacheTypeV = "q8_0",
            opOffload = false
          )
        } catch (e: Exception) {
          Log.e("ModelList", "load failed", e)
          false
        }
      }
      isLoading = false
      if (ok) {
        SettingsManager.lastModelPath = model.path
        SettingsManager.lastModelName = model.name
        ZeroCopyApp.instance.modelRepository.markUsed(model.id)
        onModelSelected(model.path, model.name)
      } else {
        statusMsg = "Failed to load model: ${model.name}"
        statusError = true
      }
    }
  }

  fun handleModelTap(model: LocalModel) {
    if (!model.path.endsWith(".gguf", ignoreCase = true)) {
      statusMsg = "Only GGUF models supported"
      statusError = true
      return
    }
    if (loadedModelPath == model.path && isModelLoaded) {
      scope.launch { ggmlEngine.unload() }
      return
    }
    loadAndSelect(model)
  }

  fun confirmDelete(model: LocalModel) {
    if (loadedModelPath == model.path) {
      scope.launch { ggmlEngine.unload() }
    }
    ZeroCopyApp.instance.modelRepository.deleteModel(model.id)
    modelToDelete = null
  }

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        loading = true
        scope.launch {
          ZeroCopyApp.instance.modelRepository.importUri(uri, getFileName(LocalContext.current, uri))
            .onSuccess { model ->
              loading = false
              loadAndSelect(model)
            }
            .onFailure { e ->
              loading = false
              statusMsg = "Import failed: ${e.message?.take(60)}"
              statusError = true
            }
        }
      }
    }
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
          IconButton(onClick = {
            ZeroCopyApp.instance.modelRepository.scanModels()
            statusMsg = "Scanned for models"
            statusError = false
          }) {
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
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (statusMsg.isNotEmpty()) {
            item {
              Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (statusError) colors.Red.copy(alpha = 0.15f) else colors.Accent.copy(alpha = 0.15f)
              ) {
                Text(
                  text = statusMsg,
                  modifier = Modifier.padding(12.dp),
                  fontSize = 12.sp,
                  color = if (statusError) colors.Red else colors.Accent,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
          if (loading || isLoading) {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.Accent, modifier = Modifier.size(24.dp))
              }
            }
          }
          items(models, key = { it.id }) { model ->
            ModelCard(
              model = model,
              isLoaded = loadedModelPath == model.path,
              onClick = { handleModelTap(model) },
              onLongClick = { longPressModel = model }
            )
          }
        }
      }
    }
  }

  if (modelToDelete != null) {
    DeleteConfirmDialog(
      name = modelToDelete!!.name,
      onConfirm = { confirmDelete(modelToDelete!!); modelToDelete = null },
      onDismiss = { modelToDelete = null }
    )
  }

  if (modelToDetail != null) {
    ModelDetailDialog(
      model = modelToDetail!!,
      onDismiss = { modelToDetail = null }
    )
  }

  if (longPressModel != null) {
    DropdownMenu(
      expanded = true,
      onDismissRequest = { longPressModel = null }
    ) {
      DropdownMenuItem(
        text = { Text("Details", fontSize = 13.sp) },
        onClick = {
          modelToDetail = longPressModel
          longPressModel = null
        }
      )
      DropdownMenuItem(
        text = { Text("Delete", color = colors.Red, fontSize = 13.sp) },
        onClick = {
          modelToDelete = longPressModel
          longPressModel = null
        }
      )
    }
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
          GGUFBadge()
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
private fun GGUFBadge() {
  val colors = currentPalette()
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = colors.Purple.copy(alpha = 0.2f)
  ) {
    Text(
      "GGUF",
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      fontSize = 9.sp,
      color = colors.Purple,
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
