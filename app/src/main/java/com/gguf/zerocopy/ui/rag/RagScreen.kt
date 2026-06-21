package com.gguf.zerocopy.ui.rag

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.domain.ocr.PdfTextExtractor
import com.gguf.zerocopy.ui.models.ModelSelectionDialog
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagScreen(onBack: () -> Unit) {
    val colors = currentPalette()
    val app = ZeroCopyApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val engine = app.activeEngine
    val models by app.modelRepository.models.collectAsState(initial = emptyList())
    var docCount by remember { mutableIntStateOf(engine.numDocuments) }
    var isAdding by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    var ragTopK by remember { mutableIntStateOf(SettingsManager.ragTopK) }
    var ragMinScore by remember { mutableStateOf(SettingsManager.ragMinScore.toString()) }
    var ragChunkSize by remember { mutableIntStateOf(SettingsManager.ragChunkSize) }
    var ragOverlap by remember { mutableIntStateOf(SettingsManager.ragOverlap) }

    var useDedicatedEmbedding by remember { mutableStateOf(SettingsManager.useDedicatedEmbedding) }
    var embModelName by remember { mutableStateOf(SettingsManager.embeddingModelName) }
    var embModelPath by remember { mutableStateOf(SettingsManager.embeddingModelPath) }
    var showEmbeddingModelDialog by remember { mutableStateOf(false) }

    var documentNames by remember { mutableStateOf(listOf<String>()) }
    var documentTexts by remember { mutableStateOf(listOf<String>()) }

    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        isAdding = true
        scope.launch {
            var added = 0
            val addedNames = mutableListOf<String>()
            val addedTexts = mutableListOf<String>()
            val pdfExtractor = PdfTextExtractor(context)
            for (uri in uris) {
                try {
                    val name = getFileName(context, uri)
                    val mime = context.contentResolver.getType(uri) ?: ""
                    val text = when {
                        mime.startsWith("image/") -> {
                            pdfExtractor.extractImageText(uri) ?: ""
                        }
                        mime == "application/pdf" -> {
                            pdfExtractor.extractText(uri) ?: ""
                        }
                        else -> {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                BufferedReader(InputStreamReader(stream)).readText()
                            } ?: ""
                        }
                    }
                    if (text.length > 10_000_000) {
                        statusMsg = "Skipped ${name.take(30)}: file too large (>10MB)"
                        continue
                    }
                    if (text.isNotEmpty() && text.length > 50) {
                        val ok = engine.addDocument(text, name, ragChunkSize, ragOverlap)
                        if (ok) {
                            added++
                            addedNames.add(name)
                            addedTexts.add(text)
                        }
                    }
                } catch (e: Exception) {
                    statusMsg = "Error: ${e.message?.take(60)}"
                }
            }
            documentNames = documentNames + addedNames
            documentTexts = documentTexts + addedTexts
            docCount = engine.numDocuments
            isAdding = false
            statusMsg = if (added > 0) "Added $added document(s)" else ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG", fontWeight = FontWeight.Bold, color = colors.Text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Storage, null, tint = colors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Document Index", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.Text)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("Chunks indexed:", fontSize = 12.sp, color = colors.Text2)
                        Spacer(Modifier.width(6.dp))
                        Text("$docCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.Accent)
                    }
                    Spacer(Modifier.height(2.dp))
                    val embModel = SettingsManager.embeddingModelName
                    Row {
                        Text("Embedding model:", fontSize = 12.sp, color = colors.Text2)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (embModel.isNotEmpty()) embModel else "Same as main",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.Text
                        )
                    }
                }
            }

            // Embedding model card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Dedicated Embedding Model", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.Text)
                            Text(
                                if (useDedicatedEmbedding && embModelName.isNotEmpty()) embModelName else "Same as main model",
                                fontSize = 12.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = useDedicatedEmbedding,
                            onCheckedChange = {
                                useDedicatedEmbedding = it
                                SettingsManager.useDedicatedEmbedding = it
                                if (!it) {
                                    embModelPath = ""
                                    embModelName = ""
                                    SettingsManager.embeddingModelPath = ""
                                    SettingsManager.embeddingModelName = ""
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg)
                        )
                    }
                    if (useDedicatedEmbedding) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showEmbeddingModelDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.Accent2)
                        ) {
                            Text("Select Embedding Model", color = colors.Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (embModelPath.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                embModelPath = ""
                                embModelName = ""
                                useDedicatedEmbedding = false
                                SettingsManager.embeddingModelPath = ""
                                SettingsManager.embeddingModelName = ""
                                SettingsManager.useDedicatedEmbedding = false
                            }) {
                                Text("Clear", color = colors.Text3, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Add documents section
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Documents", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.Text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ingest text or markdown files for retrieval-augmented generation.",
                        fontSize = 12.sp, color = colors.Text3
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { docPicker.launch(arrayOf("text/plain", "text/markdown", "application/pdf", "image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent),
                        enabled = !isAdding
                    ) {
                        Icon(Icons.Filled.NoteAdd, null, tint = colors.Bg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAdding) "Processing..." else "Add Files", color = colors.Bg, fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Description, null, tint = colors.Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste Text", color = colors.Text, fontSize = 13.sp)
                    }

                    if (documentNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = colors.Border.copy(alpha = 0.3f))
                        Spacer(Modifier.height(6.dp))
                        documentNames.forEachIndexed { idx, name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text(
                                    name, fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        val newNames = documentNames.toMutableList()
                                        val newTexts = documentTexts.toMutableList()
                                        newNames.removeAt(idx)
                                        newTexts.removeAt(idx)
                                        documentNames = newNames
                                        documentTexts = newTexts
                                        scope.launch {
                                            engine.clearDocuments()
                                            newTexts.forEachIndexed { i, txt ->
                                                engine.addDocument(txt, newNames[i], ragChunkSize, ragOverlap)
                                            }
                                            docCount = engine.numDocuments
                                            statusMsg = "Deleted: $name"
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, null, tint = colors.Red, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    if (docCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                engine.clearDocuments()
                                docCount = 0
                                documentNames = emptyList()
                                documentTexts = emptyList()
                                statusMsg = "All documents cleared"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.Red.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Filled.Delete, null, tint = colors.Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear All", color = colors.Red, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Settings section
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.Text)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ragTopK.toString(),
                        onValueChange = { ragTopK = it.toIntOrNull()?.coerceIn(1, 20) ?: ragTopK },
                        label = { Text("Top-K Results", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = colors.Text)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ragMinScore,
                        onValueChange = { ragMinScore = it },
                        label = { Text("Min Similarity (0-1)", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = colors.Text)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ragChunkSize.toString(),
                        onValueChange = { ragChunkSize = it.toIntOrNull()?.coerceIn(64, 4096) ?: ragChunkSize },
                        label = { Text("Chunk Size", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = colors.Text)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ragOverlap.toString(),
                        onValueChange = { ragOverlap = it.toIntOrNull()?.coerceIn(0, 1024) ?: ragOverlap },
                        label = { Text("Chunk Overlap", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = colors.Text)
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            SettingsManager.ragTopK = ragTopK
                            SettingsManager.ragMinScore = ragMinScore.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.3f
                            SettingsManager.ragChunkSize = ragChunkSize
                            SettingsManager.ragOverlap = ragOverlap
                            engine.setRagParams(SettingsManager.ragTopK, SettingsManager.ragMinScore)
                            statusMsg = "Settings saved"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Accent2)
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.Bg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Settings", color = colors.Bg, fontSize = 13.sp)
                    }
                }
            }

            if (statusMsg.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (statusMsg.startsWith("Error")) colors.Red.copy(alpha = 0.15f) else colors.Accent2.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        statusMsg, modifier = Modifier.padding(12.dp), fontSize = 12.sp,
                        color = if (statusMsg.startsWith("Error")) colors.Red else colors.Accent2,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "RAG is only supported with GGUF models via the native engine.",
                fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
            )
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Paste Text", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.Text)
            },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text("Paste or type text to index...", fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = colors.Text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pasteText.isNotBlank()) {
                        scope.launch {
                            isAdding = true
                            val ok = engine.addDocument(pasteText, "pasted_text", ragChunkSize, ragOverlap)
                            if (ok) {
                                statusMsg = "Text added (${pasteText.length} chars)"
                                documentNames = documentNames + "pasted_text"
                                documentTexts = documentTexts + pasteText
                            }
                            pasteText = ""
                            docCount = engine.numDocuments
                            isAdding = false
                        }
                    }
                    showPasteDialog = false
                }) {
                    Text("Add", color = colors.Accent, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel", color = colors.Text2, fontSize = 14.sp)
                }
            }
        )
    }

    if (showEmbeddingModelDialog) {
        ModelSelectionDialog(
            models = models,
            onSelect = { model ->
                embModelPath = model.path
                embModelName = model.name
                SettingsManager.embeddingModelPath = model.path
                SettingsManager.embeddingModelName = model.name
                SettingsManager.useDedicatedEmbedding = true
                useDedicatedEmbedding = true
                showEmbeddingModelDialog = false
            },
            onDismiss = { showEmbeddingModelDialog = false }
        )
    }
}

private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}
