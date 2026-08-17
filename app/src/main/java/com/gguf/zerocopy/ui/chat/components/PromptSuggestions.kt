package com.gguf.zerocopy.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.currentPalette

@Composable
fun PromptSuggestions(
  suggestions: List<String>,
  onSelect: (String) -> Unit
) {
  val colors = currentPalette()
  LazyRow(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    items(suggestions) { suggestion ->
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = colors.CardLight,
        border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.4f)),
        modifier = Modifier.clickable { onSelect(suggestion) }
      ) {
        Text(
          text = suggestion,
          fontSize = 11.sp,
          fontFamily = FontFamily.SansSerif,
          color = colors.Text2,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
      }
    }
  }
}
