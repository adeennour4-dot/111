package com.gguf.zerocopy.ui.invent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.invent.FileNode
import com.gguf.zerocopy.ui.theme.ZcPalette
import kotlinx.coroutines.launch

private val Cy = Color(0xFF00E5A0)
private val Am = Color(0xFFFFB74D)

@Composable
fun FilePanel(
    fileTree: List<FileNode>,
    colors: ZcPalette,
    vm: InventViewModel,
    onClose: () -> Unit
) {
    var selectedNode by remember { mutableStateOf<FileNode?>(null) }
    var fileContents by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedFileContent by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load file contents lazily
    LaunchedEffect(fileTree) {
        val contents = mutableMapOf<String, String>()
        for (node in fileTree) {
            if (!node.isDir && vm.getFileContent(node.path) != null) {
                contents[node.path] = vm.getFileContent(node.path)!!
            }
        }
        fileContents = contents
    }

    LaunchedEffect(selectedNode) {
        selectedNode?.let { node ->
            if (!node.isDir) {
                selectedFileContent = fileContents[node.path]
                    ?: vm.getFileContent(node.path)
                    ?: "// Loading..."
            }
        }
    }

    Surface(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight(),
        color = colors.Bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.Border.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Description, null, tint = Cy, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Project Files", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = colors.Text, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = colors.Text3, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = colors.Border.copy(alpha = 0.3f))

            if (selectedNode != null && !selectedNode!!.isDir) {
                // ── Code viewer ──
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedNode!!.path, fontSize = 9.sp, color = Cy,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { selectedNode = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text3, modifier = Modifier.size(12.dp))
                        }
                    }
                    // Action buttons: Copy, Debug, Redo
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Cy.copy(alpha = 0.1f),
                            modifier = Modifier.clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("code", selectedFileContent))
                            }
                        ) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ContentCopy, null, tint = Cy, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Copy", fontSize = 8.sp, color = Cy, fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (selectedFileContent.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Am.copy(alpha = 0.1f),
                                modifier = Modifier.clickable {
                                    vm.requestDebug(selectedNode!!.path, selectedFileContent)
                                }
                            ) {
                                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.BugReport, null, tint = Am, modifier = Modifier.size(10.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Debug", fontSize = 8.sp, color = Am, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // File content
                    if (selectedFileContent.isNotEmpty()) {
                        val scrollState = rememberScrollState()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .background(colors.CardLight, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                selectedFileContent,
                                fontSize = 8.sp,
                                color = colors.Text2,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Loading...", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else {
                // ── File tree view ──
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(fileTree) { node ->
                        val isSelected = selectedNode?.path == node.path
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedNode = if (isSelected && !node.isDir) node else {
                                        if (!node.isDir) node else null
                                    }
                                },
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) Cy.copy(alpha = 0.08f) else Color.Transparent
                        ) {
                            Row(
                                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    null,
                                    tint = if (node.isDir) Am else Cy,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        node.path.substringAfterLast('/'),
                                        fontSize = 9.sp,
                                        color = if (isSelected) Cy else colors.Text,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
