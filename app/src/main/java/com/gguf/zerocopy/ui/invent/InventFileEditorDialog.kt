package com.gguf.zerocopy.ui.invent
import com.gguf.zerocopy.ui.theme.ZcShape

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val EdBg = Color(0xFF0E0F14)
private val EdLine = Color(0xFF1E2230)
private val EdText = Color(0xFFDDE2F0)
private val EdAccent = Color(0xFF00E5A0)

/**
 * In-app file editor — a small code/text editor used to open project files
 * or create a new one. Save writes the (possibly renamed) file back via
 * [onSave] (file name, content).
 */
@Composable
fun InventFileEditorDialog(
    title: String,
    initialContent: String,
    onSave: (fileName: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialContent) }
    var fileName by remember { mutableStateOf(title) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ZcShape.Lg,
            color = EdBg,
            border = BorderStroke(0.2.dp, EdLine)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 620.dp)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✎ File editor", fontSize = 13.sp, color = EdAccent,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Close", tint = EdText, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Surface(shape = ZcShape.Sm, color = EdLine.copy(alpha = 0.6f)) {
                    BasicTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = EdText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = ZcShape.Sm,
                    color = Color(0xFF0A0B0F),
                    modifier = Modifier.weight(1f)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = EdText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                        cursorBrush = SolidColor(EdAccent),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(shape = ZcShape.Pill, onClick = onDismiss) {
                        Text("Cancel", fontSize = 12.sp, color = Color(0xFF8A93A8), fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { onSave(fileName, text); onDismiss() },
                        shape = ZcShape.Sm,
                        color = EdAccent.copy(alpha = 0.15f),
                        border = BorderStroke(0.2.dp, EdAccent)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Save, null, tint = EdAccent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Save", fontSize = 12.sp, color = EdAccent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
