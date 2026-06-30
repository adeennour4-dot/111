package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gguf.zerocopy.data.invent.FileNode
import com.gguf.zerocopy.data.invent.InventMessage
import com.gguf.zerocopy.data.invent.InventPhase
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import android.content.Intent
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventScreen(
    model1Path: String,
    model1Name: String,
    model2Path: String,
    model2Name: String,
    researcherPath: String,
    researcherName: String,
    offlineMode: Boolean,
    sameModelMode: Boolean,
    onBack: () -> Unit,
    onModelsClick: () -> Unit = onBack
) {
    val vm: InventViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                researcherPath, researcherName, offlineMode, sameModelMode)
        }
    }
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val displayName = when (ui.phase) {
                                    InventPhase.QUESTIONING -> "Let's Build"
                                    InventPhase.SEARCHING -> "Researching"
                                    InventPhase.PLANNING, InventPhase.CONFIRMING -> "Planning"
                                    InventPhase.GENERATING -> "Generating"
                                    InventPhase.DONE -> "Ready ✓"
                                    InventPhase.DEBUGGING -> "Fixing"
                                }
                                Text(displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace, color = colors.Text)
                                if (ui.projectName.isNotEmpty()) {
                                    Text("  /  ${ui.projectName.take(20)}", fontSize = 11.sp,
                                        color = colors.Text3, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Text("${phaseLabel(ui.phase)}  ·  ${ui.sessionId.take(6)}",
                                fontSize = 9.sp, color = colors.Accent,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
                        }
                        // Models button to go back & change models
                        IconButton(onClick = onModelsClick) {
                            Icon(Icons.Outlined.SmartToy, "Models", tint = colors.Accent2,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleSessionList() }) {
                        Icon(Icons.Outlined.FolderOpen, "Sessions", tint = colors.Text3)
                    }
                    // Token usage indicator
                    if (ui.totalTokensUsed > 0) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.Accent.copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("${ui.totalTokensUsed} tok", fontSize = 9.sp,
                                color = colors.Accent, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { vm.setShowDeleteConfirm(true) }) {
                        Icon(Icons.Outlined.DeleteOutline, "Clear", tint = colors.Text3)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding()) {

            Column(Modifier.fillMaxSize()) {

                // Session list drawer
                AnimatedVisibility(visible = ui.showSessionList) {
                    SessionListPanel(
                        sessions = ui.sessions,
                        currentId = ui.sessionId,
                        onSwitch = { vm.switchToSession(it) },
                        onDelete = { vm.deleteSessionById(it) },
                        onDismiss = { vm.toggleSessionList() },
                        colors = colors
                    )
                }

                // Offline banner
                AnimatedVisibility(visible = ui.offlineMode) {
                    Row(
                        Modifier.fillMaxWidth().background(colors.Amber.copy(alpha = 0.1f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.WifiOff, null, tint = colors.Amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Offline — results from model knowledge only", fontSize = 11.sp,
                            color = colors.Amber, fontFamily = FontFamily.Monospace)
                    }
                }

                // Loading banner
                AnimatedVisibility(visible = ui.swapInfo.isNotEmpty()) {
                    InventSwapBanner(ui.swapInfo, colors)
                }

                // Generation progress bar
                AnimatedVisibility(visible = ui.phase == InventPhase.GENERATING) {
                    GenerationProgressBar(
                        current = ui.currentFileIndex,
                        total = ui.totalFiles,
                        fileName = ui.currentFileName,
                        colors = colors
                    )
                }

                // Debug mode banner
                AnimatedVisibility(visible = ui.debugMode) {
                    DebugBanner(colors)
                }

                // Search progress
                AnimatedVisibility(visible = ui.phase == InventPhase.SEARCHING && ui.searchRound > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Search, null, tint = colors.Accent2, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search round ${ui.searchRound}", fontSize = 11.sp, color = colors.Accent2,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(Modifier.weight(1f).height(2.dp)
                            .clip(RoundedCornerShape(1.dp)), color = colors.Accent2, trackColor = colors.Border)
                    }
                }

                // Messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(ui.messages) { _, msg -> InventBubble(msg, colors) }

                    if (ui.isGenerating && ui.swapInfo.isEmpty() && ui.phase != InventPhase.GENERATING) {
                        item { InventThinkingDots(colors) }
                    }

                    // DONE: Stats + file tree + export + debug button
                    if (ui.phase == InventPhase.DONE && ui.fileTree.isNotEmpty()) {
                        item { StatsCard(ui, colors) }
                    }

                    if (ui.phase == InventPhase.DONE && ui.fileTree.isNotEmpty()) {
                        item { InventFileTreeCard(ui.fileTree, colors) }
                        item { InventExportCardWithDebug(
                            onExportZip = {
                                val zipFile = vm.exportProjectZip()
                                if (zipFile != null) {
                                    try {
                                        val uri = FileProvider.getUriForFile(context,
                                            "${context.packageName}.fileprovider", zipFile)
                                        context.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "application/zip"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Export Project as ZIP"))
                                    } catch (_: Exception) {}
                                }
                            },
                            onDebug = { vm.startDebugging() },
                            debugSessions = ui.debugSessionCount,
                            colors = colors
                        ) }
                    }

                    // Sure/Not sure buttons
                    if (ui.showSureButtons) {
                        item { InventSureButtons(onSure = { vm.onSure() }, onNotSure = { vm.onNotSure() }, colors) }
                    }
                }

                // Input bar — questioning phase
                AnimatedVisibility(visible = ui.phase == InventPhase.QUESTIONING) {
                    InventInputBar(
                        text = inputText, onTextChange = { inputText = it },
                        onSend = { if (inputText.isNotBlank()) { vm.sendUserMessage(inputText.trim()); inputText = "" } },
                        onSearch = { vm.onSearchButtonPressed() },
                        isGenerating = ui.isGenerating, colors = colors
                    )
                }

                // Input bar — debugging phase
                AnimatedVisibility(visible = ui.phase == InventPhase.DEBUGGING) {
                    DebugInputBar(
                        text = inputText, onTextChange = { inputText = it },
                        onSend = { if (inputText.isNotBlank()) { vm.sendUserMessage(inputText.trim()); inputText = "" } },
                        onExit = { vm.exitDebugging() },
                        isGenerating = ui.isGenerating, colors = colors
                    )
                }
            }

            // Delete dialog
            if (ui.showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { vm.setShowDeleteConfirm(false) },
                    title = { Text("Delete session?", color = colors.Text) },
                    text = { Text("Permanently deletes all session files. Cannot be undone.", color = colors.Text2) },
                    confirmButton = { TextButton(onClick = { vm.onDeleteConfirmed() }) { Text("Delete", color = colors.Red) } },
                    dismissButton = { TextButton(onClick = { vm.setShowDeleteConfirm(false) }) { Text("Cancel", color = colors.Text2) } },
                    containerColor = colors.Card
                )
            }

            // Error banner
            if (ui.error.isNotEmpty()) {
                Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = colors.Red.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, colors.Red.copy(alpha = 0.4f))) {
                        Text(ui.error, modifier = Modifier.padding(12.dp, 8.dp),
                            color = colors.Red, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ── New Components ────────────────────────────────────────────────────────────

@Composable
fun SessionListPanel(
    sessions: List<SessionInfo>,
    currentId: String,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    colors: ZcPalette
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.Card,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sessions", fontWeight = FontWeight.Bold, color = colors.Text,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Close", tint = colors.Text3, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text("No saved sessions", color = colors.Text3, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            } else {
                sessions.forEach { s ->
                    val isCurrent = s.id == currentId
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .then(if (!isCurrent) Modifier.clickable { onSwitch(s.id) } else Modifier),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) colors.Accent.copy(alpha = 0.08f) else colors.Surface,
                        border = if (isCurrent) BorderStroke(1.dp, colors.Accent.copy(0.3f)) else null
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.projectName, fontSize = 13.sp, color = colors.Text,
                                    fontFamily = FontFamily.Monospace, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                Row {
                                    Text(s.phase.name, fontSize = 10.sp, color = colors.Text3,
                                        fontFamily = FontFamily.Monospace)
                                    if (s.fileCount > 0) {
                                        Text(" · ${s.fileCount} files", fontSize = 10.sp,
                                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                            if (!isCurrent) {
                                IconButton(onClick = { onDelete(s.id) },
                                    modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.DeleteOutline, "Delete",
                                        tint = colors.Red.copy(0.6f), modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Text("current", fontSize = 10.sp, color = colors.Accent,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenerationProgressBar(
    current: Int,
    total: Int,
    fileName: String,
    colors: ZcPalette
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.Accent.copy(alpha = 0.05f)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Generating files…", fontSize = 11.sp, color = colors.Accent,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text("$current / $total", fontSize = 11.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) current.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = colors.Accent,
                trackColor = colors.Border
            )
            if (fileName.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("📄 $fileName", fontSize = 10.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun DebugBanner(colors: ZcPalette) {
    Row(
        Modifier.fillMaxWidth().background(colors.Amber.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.BugReport, null, tint = colors.Amber, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Debug mode — describe the bug and I'll fix it",
            fontSize = 11.sp, color = colors.Amber, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatsCard(ui: InventUiState, colors: ZcPalette) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.Card,
        border = BorderStroke(1.dp, colors.Accent.copy(0.2f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("📊  Project Stats", fontWeight = FontWeight.Bold, color = colors.Text,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("Files", "${ui.totalFiles}", colors.Accent, colors)
                StatItem("Lines", "${ui.totalLines}", colors.Accent2, colors)
                StatItem("Size", formatBytes(ui.totalGeneratedBytes), Color(0xFF00F090), colors)
                StatItem("Debug", "${ui.debugSessionCount}", colors.Amber, colors)
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color, colors: ZcPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun InventExportCardWithDebug(
    onExportZip: () -> Unit,
    onDebug: () -> Unit,
    debugSessions: Int,
    colors: ZcPalette
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.Accent.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Folder, null, tint = colors.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text("Project ready!", fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Export .zip or debug if something needs fixing",
                color = colors.Text3, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Export .ZIP
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(
                            listOf(colors.Accent.copy(0.3f), colors.Accent2.copy(0.3f))))
                        .border(BorderStroke(1.dp, colors.Accent.copy(0.5f)), RoundedCornerShape(10.dp))
                        .clickable { onExportZip() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, tint = colors.Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export .ZIP", color = colors.Accent, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                // Debug
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.Amber.copy(alpha = 0.1f))
                        .border(BorderStroke(1.dp, colors.Amber.copy(0.4f)), RoundedCornerShape(10.dp))
                        .clickable { onDebug() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BugReport, null, tint = colors.Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Debug", color = colors.Amber, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (debugSessions > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text("($debugSessions)", fontSize = 10.sp, color = colors.Amber.copy(0.6f),
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DebugInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onExit: () -> Unit,
    isGenerating: Boolean,
    colors: ZcPalette
) {
    Surface(
        color = colors.Surface,
        border = BorderStroke(1.dp, colors.Amber.copy(0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe the bug…", color = colors.Text3,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.Text, unfocusedTextColor = colors.Text,
                        focusedBorderColor = colors.Amber.copy(0.5f), unfocusedBorderColor = colors.Border,
                        cursorColor = colors.Amber),
                    shape = RoundedCornerShape(12.dp), maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (text.isNotBlank() && !isGenerating) colors.Amber
                                    else colors.Border)
                        .then(if (text.isNotBlank() && !isGenerating) Modifier.clickable { onSend() } else Modifier),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.Red.copy(alpha = 0.06f))
                    .border(BorderStroke(1.dp, colors.Red.copy(0.3f)), RoundedCornerShape(10.dp))
                    .then(if (!isGenerating) Modifier.clickable { onExit() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Close, null, tint = colors.Red, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Exit debug mode (project is done)",
                        color = colors.Red.copy(if (!isGenerating) 1f else 0.4f),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ── Existing Components (preserved) ───────────────────────────────────────────

@Composable
fun InventSwapBanner(info: String, colors: ZcPalette) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(
                listOf(colors.GradientStart.copy(0.08f), colors.GradientEnd.copy(0.08f))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = colors.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(info, fontSize = 12.sp, color = colors.Accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun InventBubble(msg: InventMessage, colors: ZcPalette) {
    if (msg.role == "system") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(msg.content, fontSize = 11.sp, color = colors.Text3,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
        }
        return
    }
    val isUser = msg.role == "user"
    val bgColor = when (msg.role) {
        "user" -> colors.UserBg; "model1" -> colors.Card; "model2" -> colors.Surface
        "researcher" -> colors.ThinkBg; else -> colors.Border.copy(0.3f)
    }
    val roleLabel = when (msg.role) {
        "model1" -> "⚙  Planner"; "model2" -> "💻  Coder"
        "researcher" -> "🔍  Researcher"; "user" -> "You"; else -> "System"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(roleLabel, fontSize = 10.sp, color = colors.Text3,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp, topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp),
            color = bgColor,
            border = if (!isUser) BorderStroke(1.dp, colors.Border) else null,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(msg.content, modifier = Modifier.padding(12.dp, 10.dp),
                color = colors.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp)
        }
    }
}

@Composable
fun InventSureButtons(onSure: () -> Unit, onNotSure: () -> Unit, colors: ZcPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        OutlinedButton(onClick = onNotSure, border = BorderStroke(1.dp, colors.Red.copy(0.5f)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Close, null, tint = colors.Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text("Not Sure", color = colors.Red, fontFamily = FontFamily.Monospace)
        }
        Box(
            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(
                    listOf(colors.GradientStart.copy(0.3f), colors.GradientEnd.copy(0.3f))))
                .border(BorderStroke(1.dp, colors.Accent2.copy(0.5f)), RoundedCornerShape(12.dp))
                .clickable { onSure() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = colors.Accent2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Sure", color = colors.Accent2,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun InventFileTreeCard(tree: List<FileNode>, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = colors.Card, border = BorderStroke(1.dp, colors.Accent.copy(0.3f))) {
        Column(Modifier.padding(14.dp)) {
            Text("📁  Project Structure", fontWeight = FontWeight.Bold, color = colors.Accent,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            tree.forEach { node ->
                val depth = node.path.count { it == '/' }
                Row(Modifier.padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (node.isDir) "📂" else "📄", fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(node.path.substringAfterLast("/"), fontSize = 12.sp,
                        color = if (node.isDir) colors.Accent else colors.Text2,
                        fontFamily = FontFamily.Monospace)
                    if (node.description.isNotEmpty()) {
                        Text("  // ${node.description}", fontSize = 10.sp, color = colors.Text3,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun InventInputBar(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit,
                   onSearch: () -> Unit, isGenerating: Boolean, colors: ZcPalette) {
    Surface(color = colors.Surface, border = BorderStroke(1.dp, colors.Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Answer…", color = colors.Text3,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.Text,
                        unfocusedTextColor = colors.Text, focusedBorderColor = colors.Accent.copy(0.5f),
                        unfocusedBorderColor = colors.Border, cursorColor = colors.Accent),
                    shape = RoundedCornerShape(12.dp), maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (text.isNotBlank() && !isGenerating)
                            Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))
                        else Brush.linearGradient(listOf(colors.Border, colors.Border)))
                        .then(if (text.isNotBlank() && !isGenerating) Modifier.clickable { onSend() } else Modifier),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(colors.Accent2.copy(alpha = 0.06f))
                    .border(BorderStroke(1.dp, colors.Accent2.copy(if (!isGenerating) 0.5f else 0.15f)),
                        RoundedCornerShape(10.dp))
                    .then(if (!isGenerating) Modifier.clickable { onSearch() } else Modifier),
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = colors.Accent2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Done talking — start search & plan", color = colors.Accent2.copy(
                        if (!isGenerating) 1f else 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun InventThinkingDots(colors: ZcPalette) {
    val inf = rememberInfiniteTransition(label = "dots")
    val offset by inf.animateFloat(initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart), label = "o")
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = if (offset.toInt() == i) 1f else 0.3f
            Box(Modifier.size(6.dp).clip(CircleShape).background(colors.Accent.copy(alpha = a)))
        }
    }
}

@Composable
fun InventPhaseIndicator(phase: InventPhase, active: Color, inactive: Color) {
    val phases = InventPhase.values()
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        phases.forEach { p ->
            Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (p.ordinal <= phase.ordinal) active else inactive))
        }
    }
}

fun phaseLabel(phase: InventPhase) = when (phase) {
    InventPhase.QUESTIONING -> "Gathering info"
    InventPhase.SEARCHING   -> "Researching"
    InventPhase.PLANNING    -> "Planning"
    InventPhase.CONFIRMING  -> "Review"
    InventPhase.GENERATING  -> "Generating code"
    InventPhase.DONE        -> "Done ✓"
    InventPhase.DEBUGGING   -> "Debugging"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
    bytes >= 1_000 -> "${"%.1f".format(bytes / 1_000.0)} KB"
    else -> "$bytes B"
}
