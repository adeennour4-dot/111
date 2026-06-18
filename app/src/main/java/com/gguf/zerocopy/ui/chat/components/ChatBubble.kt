package com.gguf.zerocopy.ui.chat.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
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
  thinkingContent: String? = null,
  showThinking: Boolean = false,
  onToggleThinking: () -> Unit = {},
  onCopy: () -> Unit = {},
  onDelete: () -> Unit = {},
  onRegenerate: (() -> Unit)? = null
) {
  val colors = currentPalette()
  val isUser = role == MessageRole.USER
  var showMenu by remember { mutableStateOf(false) }
  val timeStr = remember(timestamp) {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Bottom
  ) {
    if (!isUser) {
      Box(
        modifier = Modifier
          .size(26.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(
            Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))
          ),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "ZC",
          fontSize = 10.sp,
          color = colors.Bg,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace,
          textAlign = TextAlign.Center
        )
      }
      Spacer(Modifier.width(8.dp))
    }

    Box {
      Column(
        modifier = Modifier
          .widthIn(max = 320.dp)
          .animateContentSize()
      ) {
        if (thinkingContent != null && !showThinking) {
          Surface(
            onClick = onToggleThinking,
            shape = RoundedCornerShape(12.dp),
            color = colors.ThinkBg,
            modifier = Modifier
              .padding(bottom = 4.dp)
          ) {
            Row(
              modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colors.Purple
              )
              Spacer(Modifier.width(4.dp))
              Text(
                text = "Thought",
                fontSize = 10.sp,
                color = colors.Purple,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }

        if (thinkingContent != null && showThinking) {
          ThinkingContent(
            content = thinkingContent,
            isExpanded = true,
            onToggle = onToggleThinking
          )
        }

        Surface(
          modifier = Modifier
            .clip(
              RoundedCornerShape(
                topStart = if (isUser) 12.dp else 18.dp,
                topEnd = if (isUser) 18.dp else 12.dp,
                bottomStart = if (isUser) 4.dp else 18.dp,
                bottomEnd = if (isUser) 18.dp else 4.dp
              )
            )
            .combinedClickable(
              onClick = {},
              onLongClick = { showMenu = true }
            ),
          color = if (isUser) colors.UserBg else colors.Card,
          tonalElevation = if (isUser) 0.dp else 1.dp
        ) {
          Column(
            modifier = Modifier
              .padding(horizontal = 14.dp, vertical = 10.dp)
          ) {
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
              Text(
                text = content,
                fontSize = 14.sp,
                color = if (isUser) Color.White else colors.Text,
                lineHeight = 20.sp
              )
              if (isStreaming) {
                BlinkingCursor(colors.Accent)
              }
            }
          }
        }

        Row(
          modifier = Modifier
            .padding(
              start = if (isUser) 0.dp else 4.dp,
              end = if (isUser) 4.dp else 0.dp,
              top = 2.dp
            ),
          horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
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
              text = " \u00b7 %.1f t/s \u00b7 %d tok".format(tps, tokens),
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        if (onRegenerate != null && !isUser) {
          Row(
            modifier = Modifier
              .padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            IconButton(
              onClick = onRegenerate,
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                Icons.Filled.Refresh,
                contentDescription = "Regenerate response",
                tint = colors.Accent,
                modifier = Modifier.size(14.dp)
              )
            }
          }
        }
      }

      DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
      ) {
        DropdownMenuItem(
          text = {
            Text(text = "Copy", fontSize = 14.sp, color = colors.Text)
          },
          onClick = {
            onCopy()
            showMenu = false
          },
          leadingIcon = {
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = colors.Text2
            )
          }
        )
        DropdownMenuItem(
          text = {
            Text(text = "Delete", fontSize = 14.sp, color = colors.Red)
          },
          onClick = {
            onDelete()
            showMenu = false
          },
          leadingIcon = {
            Icon(
              Icons.Outlined.Delete,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = colors.Red
            )
          }
        )
      }
    }

    if (isUser) {
      Spacer(Modifier.width(8.dp))
      Box(
        modifier = Modifier
          .size(26.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(
            Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))
          ),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "Y",
          fontSize = 12.sp,
          color = colors.Bg,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

@Composable
private fun PulsingDots(color: Color) {
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
          .size(8.dp)
          .clip(CircleShape)
          .background(color.copy(alpha = alpha))
      )
      if (index < 2) Spacer(Modifier.width(4.dp))
    }
  }
}

@Composable
private fun BlinkingCursor(color: Color) {
  var visible by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    while (true) {
      visible = !visible
      delay(500)
    }
  }
  if (visible) {
    Text(
      text = "\u258c",
      color = color,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold
    )
  }
}
