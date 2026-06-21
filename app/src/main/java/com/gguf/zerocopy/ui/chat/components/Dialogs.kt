package com.gguf.zerocopy.ui.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.domain.inference.InferenceEngine
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.launch

@Composable
fun ExportSessionDialog(
  onDismiss: () -> Unit,
  onShareText: () -> Unit,
  onShareJson: () -> Unit,
  onImport: (() -> Unit)? = null
) {
  val colors = currentPalette()
  var exportProgress by remember { mutableFloatStateOf(0f) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        text = "Session",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Text
      )
    },
    text = {
      Column {
        Text(
          text = "Choose an export format for sharing this conversation.",
          fontSize = 13.sp,
          color = colors.Text2
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
          progress = { exportProgress },
          modifier = Modifier.fillMaxWidth(),
          color = colors.Accent,
          trackColor = colors.Border
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
          onClick = {
            exportProgress = 1f
            onShareText()
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            Icons.Filled.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.Text2
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = "Share as Text",
            fontSize = 14.sp,
            color = colors.Text,
            modifier = Modifier.weight(1f)
          )
          if (exportProgress >= 1f) {
            Icon(
              Icons.Filled.Done,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = colors.Accent2
            )
          }
        }
        TextButton(
          onClick = {
            exportProgress = 1f
            onShareJson()
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            Icons.Filled.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.Text2
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = "Share as JSON",
            fontSize = 14.sp,
            color = colors.Text,
            modifier = Modifier.weight(1f)
          )
          if (exportProgress >= 1f) {
            Icon(
              Icons.Filled.Done,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = colors.Accent2
            )
          }
        }
        if (onImport != null) {
          Spacer(Modifier.height(8.dp))
          TextButton(
            onClick = {
              onDismiss()
              onImport()
            },
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Filled.FileUpload, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import Session", fontSize = 14.sp, color = colors.Text, modifier = Modifier.weight(1f))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "Done", color = colors.Accent, fontSize = 14.sp)
      }
    }
  )
}

@Composable
fun ModelSelectDialog(
  models: List<LocalModel>,
  onSelect: (LocalModel) -> Unit,
  onDismiss: () -> Unit
) {
  val colors = currentPalette()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        text = "Select Model",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Text
      )
    },
    text = {
      if (models.isEmpty()) {
        Text(
          text = "No models available. Import a model first.",
          fontSize = 13.sp,
          color = colors.Text3,
          modifier = Modifier.padding(vertical = 24.dp)
        )
      } else {
        LazyColumn(
          modifier = Modifier.height(300.dp)
        ) {
          items(models, key = { it.id }) { model ->
            Surface(
              onClick = { onSelect(model) },
              shape = RoundedCornerShape(12.dp),
              color = colors.Card,
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = model.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(Modifier.height(2.dp))
                  Row {
                    Text(
                      text = model.format.uppercase(),
                      fontSize = 10.sp,
                      fontFamily = FontFamily.Monospace,
                      color = colors.Accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                      text = model.sizeFormatted,
                      fontSize = 10.sp,
                      fontFamily = FontFamily.Monospace,
                      color = colors.Text3
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "Cancel", color = colors.Text2, fontSize = 14.sp)
      }
    }
  )
}

@Composable
fun ModelDeleteConfirmDialog(
  modelName: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  val colors = currentPalette()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        text = "Delete model?",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Text
      )
    },
    text = {
      Text(
        text = "Delete \"$modelName\"? This action cannot be undone. The model file will be permanently removed.",
        fontSize = 14.sp,
        color = colors.Text2
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = "Delete", color = colors.Red, fontSize = 14.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "Cancel", color = colors.Text2, fontSize = 14.sp)
      }
    }
  )
}

@Composable
fun DeleteConfirmDialog(
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  val colors = currentPalette()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        text = "Delete message?",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = colors.Text
      )
    },
    text = {
      Text(
        text = "This action cannot be undone. The message will be permanently removed.",
        fontSize = 14.sp,
        color = colors.Text2
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = "Delete", color = colors.Red, fontSize = 14.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "Cancel", color = colors.Text2, fontSize = 14.sp)
      }
    }
  )
}

@Composable
fun RagDocumentDialog(
  engine: InferenceEngine?,
  onDismiss: () -> Unit,
  onDocumentsChanged: () -> Unit,
  onManageLibrary: (() -> Unit)? = null
) {
  val colors = currentPalette()
  var docCount by remember { mutableIntStateOf(engine?.numDocuments ?: 0) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.List, null, tint = colors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          text = "RAG Documents",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = colors.Text
        )
      }
    },
    text = {
      Column {
        Text(
          text = "Ingest text documents for retrieval-augmented generation.",
          fontSize = 13.sp,
          color = colors.Text2
        )
        Spacer(Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Filled.Done, null, tint = if (docCount > 0) colors.Accent2 else colors.Text3, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(
            text = "$docCount document chunks indexed",
            fontSize = 13.sp,
            color = if (docCount > 0) colors.Text else colors.Text3,
            fontWeight = if (docCount > 0) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
          )
        }

        if (onManageLibrary != null) {
          Spacer(Modifier.height(16.dp))
          TextButton(
            onClick = {
              onDismiss()
              onManageLibrary()
            },
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Filled.OpenInNew, null, tint = colors.Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Manage full library", color = colors.Accent, fontSize = 13.sp)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "Done", color = colors.Accent, fontSize = 14.sp)
      }
    }
  )
}

fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
  var name = "unknown"
  context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
    if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
  }
  return name
}


