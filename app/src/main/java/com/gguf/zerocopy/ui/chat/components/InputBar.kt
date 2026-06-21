package com.gguf.zerocopy.ui.chat.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.currentPalette

@Composable
fun InputBar(
  onSend: (String, List<Uri>, List<String>) -> Unit,
  onStop: () -> Unit,
  onCamera: () -> Unit,
  onAttach: () -> Unit,
  onVoiceInput: () -> Unit,
  isVoiceListening: Boolean = false,
  isInferring: Boolean,
  attachmentUris: List<Uri>,
  attachmentFileNames: List<String>,
  onRemoveAttachment: (Int) -> Unit,
  reasoningEnabled: Boolean,
  onToggleReasoning: () -> Unit,
  ragEnabled: Boolean = false,
  onToggleRag: () -> Unit = {},
  ragDocCount: Int = 0
) {
  val colors = currentPalette()
  val context = LocalContext.current
  var prompt by remember { mutableStateOf("") }
  val hasAttachments = attachmentUris.isNotEmpty()
  val sendScale by animateFloatAsState(
    targetValue = if (prompt.isNotBlank()) 1f else 0.92f,
    animationSpec = tween(200)
  )

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.Bg)
      .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    if (hasAttachments) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        attachmentUris.forEachIndexed { idx, uri ->
          Box(modifier = Modifier.size(56.dp)) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) {
              val bitmap = remember(uri) {
                try {
                  context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                  }
                } catch (_: Exception) { null }
              }
              bitmap?.let { bmp ->
                androidx.compose.foundation.Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = null,
                  modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                  contentScale = ContentScale.Crop
                )
              }
            } else {
              Box(
                modifier = Modifier
                  .size(56.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(colors.Card),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = attachmentFileNames.getOrElse(idx) { "file" }.take(8),
                  fontSize = 9.sp,
                  color = colors.Text3
                )
              }
            }
            IconButton(
              onClick = { onRemoveAttachment(idx) },
              modifier = Modifier
                .size(16.dp)
                .align(Alignment.TopEnd)
            ) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = colors.Red,
                modifier = Modifier.size(12.dp)
              )
            }
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom
    ) {
      OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.weight(1f),
        placeholder = {
          Text("Type a message...", color = colors.Text3.copy(alpha = 0.7f), fontSize = 14.sp)
        },
        enabled = !isInferring,
        minLines = 1,
        maxLines = 5,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Text,
          imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(
          onSend = {
            if (prompt.isNotBlank() && !isInferring) {
              val text = prompt; prompt = ""
              onSend(text, attachmentUris, attachmentFileNames)
            }
          }
        ),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = colors.Accent.copy(alpha = 0.6f),
          unfocusedBorderColor = colors.Border.copy(alpha = 0.25f),
          focusedContainerColor = colors.Card,
          unfocusedContainerColor = colors.Card,
          focusedTextColor = colors.Text,
          unfocusedTextColor = colors.Text,
          cursorColor = colors.Accent
        ),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 14.sp)
      )

      Spacer(Modifier.width(8.dp))

      if (isInferring) {
        Box(
          modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.Red)
            .clickable { onStop() },
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = colors.Bg, modifier = Modifier.size(18.dp))
        }
      } else {
        Box(
          modifier = Modifier
            .size(40.dp)
            .scale(sendScale)
            .clip(CircleShape)
            .background(if (prompt.isNotBlank()) colors.Accent else colors.Card)
            .clickable(enabled = prompt.isNotBlank()) {
              val text = prompt; prompt = ""
              onSend(text, attachmentUris, attachmentFileNames)
            },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = if (prompt.isNotBlank()) colors.Bg else colors.Text3.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      if (!isInferring && prompt.isEmpty()) {
        IconButton(onClick = onAttach, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Filled.AttachFile, "Attach", tint = colors.Text3.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onCamera, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Filled.CameraAlt, "Camera", tint = colors.Text3.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onVoiceInput, modifier = Modifier.size(32.dp)) {
          Icon(
            if (isVoiceListening) Icons.Filled.Mic else Icons.Outlined.Mic,
            "Voice input",
            tint = if (isVoiceListening) colors.Accent else colors.Text3.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
          )
        }
      }
      Spacer(Modifier.weight(1f))
      if (ragDocCount > 0) {
        IconButton(onClick = onToggleRag, modifier = Modifier.size(32.dp)) {
          Icon(
            if (ragEnabled) Icons.Filled.Search else Icons.Outlined.Search,
            contentDescription = "RAG",
            tint = if (ragEnabled) colors.Accent else colors.Text3.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
          )
        }
      }
      IconButton(onClick = onToggleReasoning, modifier = Modifier.size(32.dp)) {
        Icon(
          if (reasoningEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
          contentDescription = "Reasoning",
          tint = if (reasoningEnabled) colors.Amber else colors.Text3.copy(alpha = 0.7f),
          modifier = Modifier.size(16.dp)
        )
      }
    }
  }
}
