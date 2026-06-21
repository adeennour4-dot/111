package com.gguf.zerocopy.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.ui.theme.currentPalette

@Composable
fun ModelSelectionDialog(
  models: List<LocalModel>,
  onSelect: (LocalModel) -> Unit,
  onDismiss: () -> Unit
) {
  val colors = currentPalette()
  val loadedPath = if (ZeroCopyApp.instance.activeEngine.isLoaded)
    SettingsManager.lastModelPath.takeIf { it.isNotEmpty() } else null

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.Surface,
    shape = RoundedCornerShape(20.dp),
    title = {
      Text(
        "Select Model",
        fontWeight = FontWeight.Bold,
        color = colors.Text,
        fontSize = 18.sp
      )
    },
    text = {
      if (models.isEmpty()) {
        Text(
          "No models available.",
          color = colors.Text3,
          fontSize = 14.sp,
          modifier = Modifier.padding(vertical = 16.dp)
        )
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .verticalScroll(rememberScrollState())
        ) {
          models.forEachIndexed { index, model ->
            val isLoaded = loadedPath == model.path
            ModelRow(
              model = model,
              isLoaded = isLoaded,
              onClick = { onSelect(model) }
            )
            if (index < models.size - 1) {
              HorizontalDivider(
                color = colors.Border.copy(alpha = 0.3f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 12.dp)
              )
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = colors.Text2)
      }
    }
  )
}

@Composable
private fun ModelRow(
  model: LocalModel,
  isLoaded: Boolean = false,
  onClick: () -> Unit
) {
  val colors = currentPalette()

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(10.dp),
    color = if (isLoaded) colors.CardLight else colors.Surface
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.name,
            color = if (isLoaded) colors.Accent else colors.Text,
            fontSize = 14.sp,
            fontWeight = if (isLoaded) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Spacer(Modifier.width(8.dp))
          EngineBadge(label = if (model.path.endsWith(".gguf", ignoreCase = true)) "GGUF" else model.format.uppercase())
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.sizeFormatted,
            fontSize = 11.sp,
            color = colors.Text3,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            model.format.uppercase(),
            fontSize = 11.sp,
            color = colors.Accent,
            fontFamily = FontFamily.Monospace
          )
          if (isLoaded) {
            Spacer(Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = colors.Accent2.copy(alpha = 0.2f)
            ) {
              Text(
                "ACTIVE",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
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
private fun EngineBadge(label: String) {
  val colors = currentPalette()
  val badgeColor = colors.Purple
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
