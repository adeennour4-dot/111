package com.gguf.zerocopy.ui.models

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListScreen(
  onModelSelected: (String, String) -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val models by app.modelRepository.models.collectAsState()
  var loading by remember { mutableStateOf(false) }

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
        loading = true
        scope.launch {
          val result = app.modelRepository.importUri(uri, name)
          if (result.isSuccess) {
            val model = result.getOrThrow()
            app.engineManager.selectEngineForFormat(model.path)
            val loadResult = app.engineManager.getActiveEngine()?.loadModel(model.path)
            withContext(Dispatchers.Main) {
              loading = false
              if (loadResult?.isSuccess == true) {
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
        title = { Text("Your Models", fontWeight = FontWeight.Bold, color = ZcColors.Text) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = ZcColors.Text2) } },
        actions = {
          IconButton(onClick = {
              val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
              }
              filePicker.launch(intent)
            }) {
              Icon(Icons.Filled.Add, "Import", tint = ZcColors.Accent)
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = ZcColors.Bg)
        )
      },
      containerColor = ZcColors.Bg
    ) { pad ->
      Box(modifier = Modifier.padding(pad).fillMaxSize()) {
        if (models.isEmpty() && !loading) {
          Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ModelTraining, null, modifier = Modifier.size(48.dp), tint = ZcColors.Text3)
            Spacer(Modifier.height(16.dp))
            Text("No models imported", color = ZcColors.Text3, fontSize = 16.sp)
            Text("Tap + to add a model file", color = ZcColors.Text3, fontSize = 13.sp)
          }
        } else {
          LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(models, key = { it.id }) { model ->
              ModelCard(
                model = model,
                onClick = {
                  scope.launch {
                    app.engineManager.selectEngineForFormat(model.path)
                    val loadResult = app.engineManager.getActiveEngine()?.loadModel(model.path)
                    if (loadResult?.isSuccess == true) {
                      app.modelRepository.markUsed(model.id)
                      onModelSelected(model.path, model.name)
                    }
                  }
                },
                onDelete = { app.modelRepository.deleteModel(model.id) }
              )
            }
          }
        }

        if (loading) {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = ZcColors.Accent)
        }
      }
    }
  }

  @Composable
  fun ModelCard(
    model: com.gguf.zerocopy.data.repository.LocalModel,
    onClick: () -> Unit,
    onDelete: () -> Unit
  ) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
      shape = RoundedCornerShape(12.dp), color = ZcColors.CardLight
    ) {
      Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(model.name, color = ZcColors.Text, fontSize = 14.sp, maxLines = 1)
          Row {
            Text(model.format.uppercase(), fontSize = 10.sp, color = ZcColors.Accent, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(model.sizeFormatted, fontSize = 10.sp, color = ZcColors.Text3, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(model.engine.id, fontSize = 10.sp, color = ZcColors.Accent2, fontFamily = FontFamily.Monospace)
          }
        }
        IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Filled.Delete, "Delete", tint = ZcColors.Red, modifier = Modifier.size(18.dp))
        }
      }
    }

    if (showDeleteConfirm) {
      AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        containerColor = ZcColors.Card,
        title = { Text("Delete Model?", color = ZcColors.Text, fontSize = 16.sp) },
        text = { Text("Remove ${model.name} from device?", color = ZcColors.Text2, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete", color = ZcColors.Red) } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = ZcColors.Text2) } }
      )
    }
  }







