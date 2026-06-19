package com.gguf.zerocopy.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.currentPalette

@Composable
fun ThinkingContent(
  content: String,
  isExpanded: Boolean,
  onToggle: () -> Unit
) {
  val colors = currentPalette()
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 6.dp),
    shape = RoundedCornerShape(10.dp),
    color = colors.ThinkBg,
    tonalElevation = 0.dp
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onToggle() }
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          Icons.Outlined.Psychology,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = colors.Purple
        )
        Spacer(Modifier.width(4.dp))
        Text(
          text = "Thought",
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          color = colors.Purple,
          modifier = Modifier.weight(1f)
        )
        Icon(
          if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          modifier = Modifier.size(16.dp),
          tint = colors.Text3
        )
      }
      AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically()
      ) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 10.dp),
          shape = RoundedCornerShape(8.dp),
          color = colors.Card,
          tonalElevation = 0.dp
        ) {
          Text(
            text = content,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.Text2,
            modifier = Modifier
              .fillMaxWidth()
              .padding(10.dp)
          )
        }
      }
    }
  }
}
