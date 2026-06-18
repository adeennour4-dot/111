package com.gguf.zerocopy.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
      AssistChip(
        onClick = { onSelect(suggestion) },
        label = {
          Text(
            text = suggestion,
            maxLines = 1,
            fontSize = 12.sp
          )
        },
        shape = RoundedCornerShape(20.dp),
        colors = AssistChipDefaults.assistChipColors(
          containerColor = colors.CardLight,
          labelColor = colors.Text2
        )
      )
    }
  }
}
