package com.gguf.zerocopy.ui.chat.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.components.GradientBubbleBox
import com.gguf.zerocopy.ui.theme.ZcShape
import com.gguf.zerocopy.ui.theme.currentPalette
import java.util.LinkedHashMap

@Composable
fun InputBar(
  onSend: (String, List<Uri>, List<String>) -> Unit,
  onStop: () -> Unit,
  isInferring: Boolean,
  enabled: Boolean,
  attachmentUris: List<Uri>,
  attachmentFileNames: List<String>,
  onRemoveAttachment: (Int) -> Unit
) {
  val colors = currentPalette()
  val context = LocalContext.current
  var prompt by remember { mutableStateOf("") }
  val hasAttachments = attachmentUris.isNotEmpty()

  // Bitmap cache with LRU eviction and cleanup on attachment changes
  val bitmapCache = remember { mutableStateOf(LinkedHashMap<Uri, Bitmap?>()) }
  val currentUris = remember(attachmentUris) { attachmentUris.toSet() }
  
  // Clean up cache entries for removed attachments
  DisposableEffect(currentUris) {
    onDispose {
      val toRemove = bitmapCache.value.keys.filter { it !in currentUris }
      toRemove.forEach { uri ->
        bitmapCache.value[uri]?.recycle()
        bitmapCache.value.remove(uri)
      }
    }
  }

  Surface(
    color = colors.Surface,
    shadowElevation = 0.dp
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        // bottom = 2dp so the bubble hugs the nav bar's gradient hairline
        .padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 2.dp)
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
                  bitmapCache.value.getOrPut(uri) {
                    try {
                      context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                      }
                    } catch (_: Exception) {
                      null
                    }
                  }
                }
                bitmap?.let { bmp ->
                  androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                      .size(64.dp)
                      .clip(ZcShape.Sm),
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
                    .clip(ZcShape.Sm),
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
      // Input styled as a chat bubble — gradient ring, circulating while generating
      GradientBubbleBox(
        circulating = isInferring,
        bubbleColor = colors.Card,
        shape = ZcShape.Xl,
        borderWidth = 1.5.dp
      ) {
        Row(
          modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.weight(1f),
            placeholder = {
              Text(
                text = if (enabled) "Type a message..." else "No model loaded",
                color = colors.Text3,
                fontSize = 14.sp
              )
            },
            enabled = enabled && !isInferring,
            minLines = 1,
            maxLines = 4,
            shape = ZcShape.Xl,
            keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Text,
              imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
              onSend = {
                if (prompt.isNotBlank() && !isInferring && enabled) {
                  val text = prompt
                  prompt = ""
                  onSend(text, attachmentUris, attachmentFileNames)
                }
              }
            ),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Color.Transparent,
              unfocusedBorderColor = Color.Transparent,
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
              focusedTextColor = colors.Text,
              unfocusedTextColor = colors.Text,
              cursorColor = colors.Accent
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
          )
          Spacer(Modifier.width(6.dp))
          if (isInferring) {
            // Stop orb
            Box(
              modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(colors.Red.copy(alpha = 0.16f))
                .border(0.2.dp, colors.Red.copy(alpha = 0.45f), CircleShape)
                .clickable { onStop() }
                .semantics {
                  contentDescription = "Stop generation"
                  role = androidx.compose.ui.semantics.Role.Button
                },
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Filled.Stop, "Stop", tint = colors.Red, modifier = Modifier.size(18.dp))
            }
          } else {
            // Send orb — identity gradient
            val canSend = enabled && prompt.isNotBlank()
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
                .border(0.2.dp, if (canSend) Color.Transparent else colors.Border, CircleShape)
                .clickable(enabled = canSend) {
                  val text = prompt
                  prompt = ""
                  onSend(text, attachmentUris, attachmentFileNames)
                }
                .semantics {
                  contentDescription = if (canSend) "Send message" else "Cannot send"
                  role = Role.Button
                  stateDescription = if (canSend) "Ready to send" else "Input required"
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
}
