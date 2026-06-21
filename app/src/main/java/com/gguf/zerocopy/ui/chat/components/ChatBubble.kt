package com.gguf.zerocopy.ui.chat.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatBubble(
  content: String,
  role: MessageRole,
  timestamp: Long,
  tps: Float,
  tokens: Int,
  attachmentPath: String? = null,
  attachmentType: AttachmentType? = null,
  isLoading: Boolean = false,
  isStreaming: Boolean = false,
  isSpeaking: Boolean = false,
  onSpeak: (() -> Unit)? = null,
  thinkingContent: String? = null,
  showThinking: Boolean = false,
  onToggleThinking: () -> Unit = {},
  onCopy: () -> Unit = {},
  onDelete: () -> Unit = {},
  onRegenerate: (() -> Unit)? = null
) {
  val colors = currentPalette()
  val isUser = role == MessageRole.USER
  val timeStr = remember(timestamp) {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp),
      horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
      verticalAlignment = Alignment.Bottom
    ) {
      if (!isUser) {
        ModelAvatar()
        Spacer(Modifier.width(10.dp))
      }

      Column(
        modifier = Modifier.widthIn(max = 340.dp)
      ) {
        if (thinkingContent != null) {
          ThinkingChip(
            content = thinkingContent,
            isExpanded = showThinking,
            onToggle = onToggleThinking
          )
        }

        AnimatedVisibility(
          visible = true,
          enter = fadeIn(animationSpec = tween(250)) +
            scaleIn(animationSpec = tween(250), initialScale = 0.95f)
        ) {
          Surface(
            shape = RoundedCornerShape(
              topStart = if (isUser) 16.dp else 4.dp,
              topEnd = if (isUser) 4.dp else 16.dp,
              bottomStart = 16.dp,
              bottomEnd = 16.dp
            ),
            color = if (isUser) colors.Accent.copy(alpha = 0.85f) else colors.Card,
            tonalElevation = 0.dp,
            shadowElevation = if (isUser) 0.dp else 0.5.dp
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
              if (attachmentPath != null && attachmentType == AttachmentType.IMAGE) {
                val file = File(attachmentPath)
                if (file.exists()) {
                  val bitmap = remember(attachmentPath) {
                    BitmapFactory.decodeFile(attachmentPath)?.asImageBitmap()
                  }
                  bitmap?.let { bmp ->
                    androidx.compose.foundation.Image(
                      bitmap = bmp,
                      contentDescription = "Attached image",
                      modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 260.dp)
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                      contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(8.dp))
                  }
                }
              }

              if (isLoading) {
                PulsingDots(colors.Text3)
              } else {
                MarkdownText(
                  markdown = content,
                  fontSize = 14.sp,
                  modifier = Modifier.fillMaxWidth()
                )
                if (isStreaming) {
                  BlinkingCursor(colors.Accent)
                }
              }
            }
          }
        }

        Row(
          modifier = Modifier.padding(top = 3.dp, start = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = timeStr,
            fontSize = 10.sp,
            color = colors.Text3,
            fontFamily = FontFamily.Monospace
          )
          if (tps > 0f) {
            Text(
              text = "\u00b7 %.1f t/s \u00b7 %d tok".format(tps, tokens),
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
          if (onSpeak != null && !isLoading && !isUser) {
            IconButton(
              onClick = onSpeak,
              modifier = Modifier.size(20.dp)
            ) {
              Icon(
                if (isSpeaking) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                contentDescription = "Speak",
                tint = if (isSpeaking) colors.Accent else colors.Text3.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp)
              )
            }
          }
          Spacer(Modifier.weight(1f))
          if (onRegenerate != null) {
            IconButton(
              onClick = onRegenerate,
              modifier = Modifier.size(20.dp)
            ) {
              Icon(
                Icons.Filled.Refresh,
                contentDescription = "Regenerate",
                tint = colors.Text3.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp)
              )
            }
          }
          IconButton(
            onClick = onDelete,
            modifier = Modifier.size(20.dp)
          ) {
            Icon(
              Icons.Outlined.Delete,
              contentDescription = "Delete",
              tint = colors.Text3.copy(alpha = 0.6f),
              modifier = Modifier.size(13.dp)
            )
          }
        }
      }

      if (isUser) {
        Spacer(Modifier.width(10.dp))
        UserAvatar()
      }
    }
  }
}

@Composable
private fun ModelAvatar() {
  val colors = currentPalette()
  Box(
    modifier = Modifier
      .size(28.dp)
      .clip(CircleShape)
      .background(colors.Card),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = "ZC",
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      color = colors.Text2,
      fontFamily = FontFamily.Monospace
    )
  }
}

@Composable
private fun UserAvatar() {
  val colors = currentPalette()
  Box(
    modifier = Modifier
      .size(28.dp)
      .clip(CircleShape)
      .background(colors.Accent.copy(alpha = 0.15f)),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = "Y",
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      color = colors.Accent,
      fontFamily = FontFamily.Monospace
    )
  }
}

@Composable
private fun ThinkingChip(
  content: String,
  isExpanded: Boolean,
  onToggle: () -> Unit
) {
  val colors = currentPalette()
  Surface(
    onClick = onToggle,
    shape = RoundedCornerShape(12.dp),
    color = colors.ThinkBg,
    modifier = Modifier.padding(bottom = 4.dp)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        Icons.Outlined.Psychology,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = colors.Purple
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = if (isExpanded) "Thought" else "Thought",
        fontSize = 11.sp,
        color = colors.Purple,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace
      )
    }
    if (isExpanded) {
      AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
      ) {
        Surface(
          modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp),
          shape = RoundedCornerShape(8.dp),
          color = colors.Card
        ) {
          Text(
            text = content,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.Text2,
            modifier = Modifier.padding(10.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun PulsingDots(color: androidx.compose.ui.graphics.Color) {
  val infiniteTransition = rememberInfiniteTransition()
  val alpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(600),
      repeatMode = RepeatMode.Reverse
    )
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    repeat(3) { index ->
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(color.copy(alpha = alpha))
      )
      if (index < 2) Spacer(Modifier.width(3.dp))
    }
  }
}

@Composable
private fun BlinkingCursor(color: androidx.compose.ui.graphics.Color) {
  val infiniteTransition = rememberInfiniteTransition()
  val alpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(500),
      repeatMode = RepeatMode.Reverse
    )
  )
  Text(
    text = "\u258c",
    color = color.copy(alpha = alpha),
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold
  )
}
