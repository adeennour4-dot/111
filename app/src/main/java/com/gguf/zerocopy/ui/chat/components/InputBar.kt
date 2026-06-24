package com.gguf.zerocopy.ui.chat.components

import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
  onVoice: () -> Unit = {},
  onSpeak: () -> Unit = {},
  isSpeaking: Boolean = false,
  isInferring: Boolean,
  hasVision: Boolean = false,
  attachmentUris: List<Uri>,
  attachmentFileNames: List<String>,
  onRemoveAttachment: (Int) -> Unit,
  reasoningEnabled: Boolean,
  onToggleReasoning: () -> Unit,
  webSearchEnabled: Boolean = false,
  onToggleWebSearch: () -> Unit = {}
) {
  val colors = currentPalette()
  val context = LocalContext.current
  var prompt by remember { mutableStateOf("") }
  val hasAttachments = attachmentUris.isNotEmpty()

  Surface(
    color = colors.Surface,
    shadowElevation = 8.dp,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      if (hasAttachments) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          attachmentUris.forEachIndexed { idx, uri ->
            Box(modifier = Modifier.size(64.dp)) {
              val mime = context.contentResolver.getType(uri) ?: ""
              if (mime.startsWith("image/")) {
                val bitmap = remember(uri) {
                  try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                      BitmapFactory.decodeStream(stream)
                    }
                  } catch (_: Exception) {
                    null
                  }
                }
                bitmap?.let { bmp ->
                  androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                      .size(64.dp)
                      .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                  )
                }
              } else {
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isPdf = mimeType == "application/pdf"
                val icon = if (isPdf) Icons.Filled.Description else Icons.Filled.AttachFile
                Box(
                  modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp)),
                  contentAlignment = Alignment.Center
                ) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                      icon,
                      contentDescription = null,
                      tint = if (isPdf) colors.Purple else colors.Accent2,
                      modifier = Modifier.size(22.dp)
                    )
                    Text(
                      text = attachmentFileNames.getOrElse(idx) { "file" },
                      fontSize = 8.sp,
                      color = colors.Text3,
                      maxLines = 1
                    )
                  }
                }
              }
              IconButton(
                onClick = { onRemoveAttachment(idx) },
                modifier = Modifier
                  .size(18.dp)
                  .align(Alignment.TopEnd)
              ) {
                Icon(
                  Icons.Filled.Close,
                  contentDescription = "Remove",
                  tint = colors.Red,
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }
      }
      OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
          Text(
            text = "Type a message...",
            color = colors.Text3,
            fontSize = 14.sp
          )
        },
        enabled = !isInferring,
        minLines = 1,
        maxLines = 4,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Text,
          imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(
          onSend = {
            if (prompt.isNotBlank() && !isInferring) {
              val text = prompt
              prompt = ""
              onSend(text, attachmentUris, attachmentFileNames)
            }
          }
        ),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = colors.Accent.copy(alpha = 0.5f),
          unfocusedBorderColor = colors.Border,
          focusedContainerColor = colors.Card,
          unfocusedContainerColor = colors.Card,
          focusedTextColor = colors.Text,
          unfocusedTextColor = colors.Text,
          cursorColor = colors.Accent
        ),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
      )
      Spacer(Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (!isInferring && prompt.isEmpty()) {
          IconButton(
            onClick = onAttach,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(
              Icons.Filled.AttachFile,
              contentDescription = "Attach",
              tint = colors.Text2,
              modifier = Modifier.size(18.dp)
            )
          }
          if (hasVision) {
            IconButton(
              onClick = onCamera,
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                Icons.Filled.CameraAlt,
                contentDescription = "Camera",
                tint = colors.Accent,
                modifier = Modifier.size(18.dp)
              )
            }
          }
        }
        IconButton(
          onClick = onToggleReasoning,
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            if (reasoningEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
            contentDescription = "Reasoning",
            tint = if (reasoningEnabled) colors.Amber else colors.Text3,
            modifier = Modifier.size(18.dp)
          )
        }
        IconButton(
          onClick = onToggleWebSearch,
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            if (webSearchEnabled) Icons.Filled.Search else Icons.Outlined.Search,
            contentDescription = if (webSearchEnabled) "Web search on" else "Web search off",
            tint = if (webSearchEnabled) colors.Accent else colors.Text3,
            modifier = Modifier.size(18.dp)
          )
        }
        IconButton(
          onClick = onVoice,
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            Icons.Filled.Mic,
            contentDescription = "Voice input",
            tint = colors.Text2,
            modifier = Modifier.size(18.dp)
          )
        }
        IconButton(
          onClick = onSpeak,
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            if (isSpeaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
            contentDescription = if (isSpeaking) "Stop speaking" else "Read aloud",
            tint = if (isSpeaking) colors.Red else colors.Text2,
            modifier = Modifier.size(18.dp)
          )
        }
        Spacer(Modifier.weight(1f))
        if (isInferring) {
          FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
              containerColor = colors.Red,
              disabledContainerColor = colors.Card
            )
          ) {
            Icon(
              Icons.Filled.Stop,
              contentDescription = "Stop",
              tint = colors.Bg,
              modifier = Modifier.size(18.dp)
            )
          }
        } else {
          val canSend = prompt.isNotBlank()
          val sendGradient = remember {
            Brush.horizontalGradient(listOf(colors.GradientStart, colors.GradientEnd))
          }
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .then(
                if (canSend) Modifier.background(sendGradient) else Modifier.background(colors.Card)
              )
              .clickable(enabled = canSend) {
                val text = prompt
                prompt = ""
                onSend(text, attachmentUris, attachmentFileNames)
              },
            contentAlignment = Alignment.Center
          ) {
            Icon(
              Icons.AutoMirrored.Filled.Send,
              contentDescription = "Send",
              tint = if (canSend) colors.Bg else colors.Text3,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }
  }
}
