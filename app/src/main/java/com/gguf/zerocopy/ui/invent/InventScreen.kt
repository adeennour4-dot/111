package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.local.SettingsManager.ModelTokenConfig
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.roundToInt
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─── Palette ──────────────────────────────────────────────────────────────────
private val Cy = Color(0xFF00E5A0)
private val CyGlow = Color(0x6000E5A0)
private val Pr = Color(0xFF8B83FF)
private val Am = Color(0xFFFFB74D)
private val Rd = Color(0xFFFF6B6B)
private val Gy = Color(0xFF6A6A7A)

// ─── Log builder ──────────────────────────────────────────────────────────────
private data class ChatBubble(
    val role: String,
    val content: String,
    val phase: InventPhase,
    val isUser: Boolean = false,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val thinkingContent: String = ""
)

private fun buildChat(messages: List<InventMessage>): List<ChatBubble> {
    return messages.map { msg ->
        ChatBubble(
            role = msg.role,
            content = msg.content,
            phase = msg.phase,
            isUser = msg.role == "user",
            isError = msg.role == "system" && msg.content.contains("error", ignoreCase = true),
            thinkingContent = msg.thinkingContent
        )
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    researcherPath: String, researcherName: String,
    offlineMode: Boolean, sameModelMode: Boolean,
    reasoningEnabled: Boolean = true,
    onBack: () -> Unit,
    onModelsClick: () -> Unit,
    onNewSession: (() -> Unit)? = null,
    vm: InventViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf<Int?>(null) }
    var settingsRestrictRole by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val chats = remember(ui.messages) { buildChat(ui.messages) }

    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                try {
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "invent_attachments").also { it.mkdirs() }
                        val mime = context.contentResolver.getType(uri) ?: "*/*"
                        val ext = when {
                            mime.contains("text") || mime.contains("json") || mime.contains("kotlin") ||
                            mime.contains("python") || mime.contains("java") || mime.contains("xml") ||
                            mime.contains("javascript") || mime.contains("html") || mime.contains("css") ||
                            mime.contains("yaml") || mime.contains("toml") || mime.contains("md") ->
                                ".${mime.substringAfterLast('/')}"
                            mime.contains("pdf") -> ".pdf"
                            else -> ".bin"
                        }
                        val name = "att_${System.currentTimeMillis()}$ext"
                        val f = File(dir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { output -> input.copyTo(output) }
                        }
                        f
                    }
                    val content = withContext(Dispatchers.IO) {
                        if (file.length() < 50_000) file.readText()
                        else "[File too large: ${file.length()} bytes]"
                    }
                    vm.sendUserMessage("[Attached: ${uri.lastPathSegment}]\n\n$content")
                } catch (_: Exception) { }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                researcherPath, researcherName, offlineMode, sameModelMode,
                reasoningEnabled = reasoningEnabled)
        }
    }
    LaunchedEffect(chats.size) {
        if (chats.isNotEmpty()) listState.animateScrollToItem(chats.size - 1)
    }

    val phaseColors = mapOf(
        InventPhase.QUESTIONING to Cy,
        InventPhase.SEARCHING to Pr,
        InventPhase.PLANNING to Am,
        InventPhase.CONFIRMING to Cy,
        InventPhase.GENERATING to Cy,
        InventPhase.REPLANNING to Am,
        InventPhase.FINALIZING to Pr,
        InventPhase.DONE to Cy,
        InventPhase.DEBUGGING to Rd
    )
    val phaseColor = phaseColors[ui.phase] ?: Gy

    Box(Modifier.fillMaxSize().background(colors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Top Bar + Progress ──────────────────────────────────────────
            Column(Modifier.background(colors.Surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (vm.isBusyGenerating()) vm.setNavigateAway(true) else onBack()
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Phase badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(phaseColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(ui.phase.name.take(8), fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, color = phaseColor,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Project name
                    Text(
                        if (ui.projectName.isNotEmpty()) ui.projectName.take(20) else "New Project",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = colors.Text, fontFamily = FontFamily.Monospace
                    )
                    if (ui.totalTokensUsed > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("· ${ui.totalTokensUsed}t", fontSize = 9.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.weight(1f))
                    // Action buttons
                    if (ui.phase == InventPhase.DONE || ui.phase == InventPhase.DEBUGGING) {
                        HeaderBtn(Icons.Filled.FileDownload, "Export", Cy, onClick = { vm.exportProjectZip() })
                    }
                    // Thinking toggle
                    val thinkColor = if (ui.reasoningEnabled) colors.Accent2 else colors.Text3
                    TextButton(
                        onClick = { vm.toggleReasoning() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            if (ui.reasoningEnabled) "🧠" else "🧠",
                            fontSize = 12.sp,
                            color = thinkColor
                        )
                    }
                    HeaderBtn(Icons.Outlined.History, "Sessions", colors.Text2, onClick = { showSessions = true })
                    HeaderBtn(Icons.Filled.Settings, "Settings", colors.Text2, onClick = { showSettings = true })
                    HeaderBtn(Icons.Filled.Refresh, "Restart", Color.White, onClick = {
                        vm.restartConversation()
                        onNewSession?.invoke()
                    })
                }
                // Progress bar
                LinearProgressIndicator(
                    progress = { phaseProgress(ui.phase) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = phaseColor,
                    trackColor = colors.Border.copy(alpha = 0.15f)
                )
            }

            // ── Model status pills ──────────────────────────────────────────
            ModelPills(
                modelMode = ui.modelMode,
                plannerLoaded = ui.plannerLoaded,
                researcherLoaded = ui.researcherLoaded,
                coderLoaded = ui.coderLoaded,
                plannerName = ui.model1Name,
                researcherName = ui.researcherName,
                coderName = ui.model2Name,
                phase = ui.phase,
                onTap = { roleIdx ->
                    showModelPicker = when (ui.modelMode) {
                        ModelMode.SINGLE -> -1
                        ModelMode.DUAL -> if (roleIdx == 1) 1 else -2
                        ModelMode.TRIPLE -> roleIdx
                    }
                },
                colors = colors
            )

            // ── Phase hint banner ───────────────────────────────────────────
            if (ui.phase != InventPhase.DONE && ui.phase != InventPhase.DEBUGGING && chats.isEmpty()) {
                PhaseHint(ui.phase, colors)
            }

            // ── Chat scroll ─────────────────────────────────────────────────
            Box(Modifier.weight(1f)) {
                if (chats.isEmpty() && ui.streamingResponse.isEmpty() && ui.swapInfo.isEmpty() && ui.error.isEmpty()) {
                    EmptyState(ui.phase, phaseColor, colors)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Swap banner
                        if (ui.swapInfo.isNotEmpty()) {
                            item(key = "swap") {
                                StatusBanner(ui.swapInfo, Cy, colors)
                            }
                        }
                        // Error
                        if (ui.error.isNotEmpty()) {
                            item(key = "err") {
                                StatusBanner(ui.error, Rd, colors)
                            }
                        }
                        // Chat bubbles
                        itemsIndexed(chats, key = { i, _ -> "c_$i" }) { _, bubble ->
                            ChatBubbleCard(bubble, colors)
                        }
                        // Live streaming response (model currently writing)
                        if (ui.streamingResponse.isNotEmpty()) {
                            // Extract thinking from streaming response
                            val streamThink = Regex("<think>([\\s\\S]*?)(<\\/think>|$)").find(ui.streamingResponse)
                            val streamContent = ui.streamingResponse
                                .replace(Regex("<think>[\\s\\S]*?(<\\/think>|$)"), "").trim()
                            val thinkText = streamThink?.groupValues?.getOrNull(1)?.trim() ?: ""
                            item(key = "stream") {
                                ChatBubbleCard(
                                    ChatBubble(
                                        role = "model1",
                                        content = streamContent.ifEmpty { ui.streamingResponse },
                                        phase = InventPhase.QUESTIONING,
                                        isUser = false, isError = false,
                                        isStreaming = true,
                                        thinkingContent = thinkText
                                    ), colors
                                )
                            }
                        }
                        // File progress
                        if (ui.phase == InventPhase.GENERATING && ui.totalFiles > 0) {
                            item(key = "fprog") {
                                FileProgress(ui.currentFileIndex, ui.totalFiles, ui.currentFileName, Cy, colors)
                            }
                        }
                        // Sure buttons
                        // Done stats
                        if (ui.phase == InventPhase.DONE) {
                            item(key = "stats") {
                                DoneStats(ui.totalLines, ui.totalGeneratedBytes, ui.debugSessionCount, colors)
                            }
                        }
                    }
                }
            }

            // ── Empty / Input area ────────────────────────────────────────
            if (!ui.chatStarted && ui.phase == InventPhase.QUESTIONING) {
                // Loading state — model is preparing first message
                Surface(Modifier.fillMaxWidth(), color = colors.Surface, shadowElevation = 2.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Cy)
                        Spacer(Modifier.width(8.dp))
                        Text("Planner is thinking of a first question…", fontSize = 11.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                // Done button (visible after ~100 tokens of Q&A)
                if (ui.phase == InventPhase.QUESTIONING && ui.chatStarted && !ui.isGenerating
                    && ui.conversationDepth >= 400) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End) {
                        Surface(
                            onClick = vm::onDonePressed,
                            shape = RoundedCornerShape(8.dp),
                            color = Cy.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Cy)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Check, "Done", tint = Cy, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Done Gathering Info", fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, color = Cy,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                InputArea(
                    inputText = inputText,
                    onTextChange = { inputText = it },
                    onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendUserMessage(inputText)
                            inputText = ""
                        }
                    },
                    phase = ui.phase,
                    totalTokens = ui.totalTokensUsed,
                    isGenerating = ui.isGenerating,
                    colors = colors
                )
            }
        }

        // ── Dialogs ─────────────────────────────────────────────────────────
        if (showSessions) {
            SessionPopup(
                sessions = ui.sessions, sessionId = ui.sessionId,
                onSwitch = { vm.switchToSession(it); showSessions = false },
                onDelete = { vm.deleteSessionById(it) },
                onDismiss = { showSessions = false },
                colors = colors, vm = vm
            )
        }
        if (showSettings) {
            SettingsPopup2(
                onDismiss = { showSettings = false; settingsRestrictRole = -1 },
                colors = colors,
                model1Path = model1Path, model2Path = model2Path, researcherPath = researcherPath,
                modelMode = ui.modelMode, restrictRole = settingsRestrictRole,
                onReload = { vm.reloadInventModel() },
                reasoningEnabled = ui.reasoningEnabled,
                onToggleReasoning = { vm.toggleReasoning() }
            )
        }
        showModelPicker?.let { roleIdx ->
            ModelPickerSheet(
                roleIdx = roleIdx,
                onDismiss = { showModelPicker = null },
                onSelect = { path, name, useAll ->
                    when (roleIdx) {
                        -1 -> { vm.selectModelTab(0, path, name, useAll); vm.selectModelTab(1, path, name, useAll); vm.selectModelTab(2, path, name, useAll) }
                        -2 -> { vm.selectModelTab(0, path, name, false); vm.selectModelTab(2, path, name, false); settingsRestrictRole = 0 }
                        0  -> { vm.selectModelTab(0, path, name, false); settingsRestrictRole = 0 }
                        1  -> { vm.selectModelTab(1, path, name, false); settingsRestrictRole = 1 }
                        2  -> { vm.selectModelTab(2, path, name, false); settingsRestrictRole = 2 }
                    }
                    showModelPicker = null; showSettings = true
                },
                colors = colors
            )
        }
        // Navigation guard
        if (ui.showNavigateAwayDialog) {
            AlertDialog(
                onDismissRequest = { vm.setNavigateAway(false) },
                title = { Text("Generation in Progress", fontFamily = FontFamily.Monospace) },
                text = { Text("Files already generated will be saved. You can resume later.", fontFamily = FontFamily.Monospace) },
                confirmButton = { TextButton(onClick = { vm.setNavigateAway(false); onBack() }) { Text("Leave", fontFamily = FontFamily.Monospace) } },
                dismissButton = { TextButton(onClick = { vm.setNavigateAway(false) }) { Text("Stay", fontFamily = FontFamily.Monospace) } },
                containerColor = colors.Card
            )
        }
    }
}

// ─── Phase progress ───────────────────────────────────────────────────────────
private fun phaseProgress(phase: InventPhase) = when (phase) {
    InventPhase.QUESTIONING -> 0.12f
    InventPhase.SEARCHING -> 0.25f
    InventPhase.PLANNING -> 0.37f
    InventPhase.CONFIRMING -> 0.50f
    InventPhase.GENERATING -> 0.62f
    InventPhase.REPLANNING -> 0.62f
    InventPhase.FINALIZING -> 0.75f
    InventPhase.DONE -> 1.0f
    InventPhase.DEBUGGING -> 1.0f
}

// ─── Header button ────────────────────────────────────────────────────────────
@Composable
private fun HeaderBtn(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(phase: InventPhase, phaseColor: Color, colors: ZcPalette) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🧠", fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                when (phase) {
                    InventPhase.QUESTIONING -> "Describe your project idea below"
                    InventPhase.SEARCHING -> "Searching the web for relevant info…"
                    InventPhase.PLANNING -> "Building the project plan…"
                    InventPhase.CONFIRMING -> "Review the plan and confirm"
                    InventPhase.GENERATING -> "Writing code files…"
                    InventPhase.REPLANNING -> "Adjusting the plan…"
                    InventPhase.FINALIZING -> "Finalizing project…"
                    InventPhase.DONE -> "Project complete! Export or start fresh."
                    InventPhase.DEBUGGING -> "Debugging session active"
                },
                fontSize = 12.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ─── Phase hint ───────────────────────────────────────────────────────────────
@Composable
private fun PhaseHint(phase: InventPhase, colors: ZcPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.Accent.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.12f))
    ) {
        Text(
            when (phase) {
                InventPhase.QUESTIONING -> "💬 Tell me what you want to build and I'll ask questions to refine the idea."
                InventPhase.SEARCHING -> "🔍 Looking up current best practices and APIs for your project."
                InventPhase.PLANNING -> "📋 Drafting a file-by-file implementation plan."
                InventPhase.CONFIRMING -> "✅ Review the plan. Tap 'Sure' to proceed or 'Not Sure' to adjust."
                InventPhase.GENERATING -> "⚙️  Writing your project files…"
                InventPhase.REPLANNING -> "🔄 Adjusting the plan based on your feedback."
                InventPhase.FINALIZING -> "📦 Generating README and build instructions."
                InventPhase.DONE -> "✅  Done! Export the zip or start a new project."
                InventPhase.DEBUGGING -> "🔧 Debug mode — describe the issue."
            },
            fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// ─── Model pills ──────────────────────────────────────────────────────────────
@Composable
private fun ModelPills(
    modelMode: ModelMode,
    plannerLoaded: Boolean, researcherLoaded: Boolean, coderLoaded: Boolean,
    plannerName: String, researcherName: String, coderName: String,
    phase: InventPhase, onTap: (Int) -> Unit, colors: ZcPalette
) {
    val pills = mutableListOf<Pair<Int, Triple<String, Boolean, String>>>()
    pills.add(0 to Triple("PLANNER", plannerLoaded, plannerName))
    if (modelMode != ModelMode.SINGLE) {
        pills.add(1 to Triple("RESEARCH", researcherLoaded, researcherName))
        pills.add(2 to Triple("CODER", coderLoaded, coderName))
    }
    val accents = listOf(Am, Pr, Cy)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        pills.forEach { (idx, info) ->
            val (label, loaded, name) = info
            val accent = accents[idx]
            val shortName = name.substringBeforeLast('.').take(10).ifEmpty { if (loaded) "ready" else "off" }
            Surface(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTap(idx) },
                color = if (loaded) accent.copy(alpha = 0.08f) else colors.Surface,
                border = BorderStroke(1.dp, if (loaded) accent.copy(0.3f) else colors.Border.copy(0.2f))
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (loaded) accent else colors.Text3.copy(0.3f)))
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        color = if (loaded) Color.White else colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                    Text(shortName, fontSize = 7.sp,
                        color = if (loaded) accent else colors.Text3.copy(0.4f),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─── Status banner ────────────────────────────────────────────────────────────
@Composable
private fun StatusBanner(text: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.06f), border = BorderStroke(1.dp, accent.copy(0.15f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (accent != Rd) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(text, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Chat bubble ──────────────────────────────────────────────────────────────
@Composable
private fun ChatBubbleCard(bubble: ChatBubble, colors: ZcPalette) {
    val roleColor = when (bubble.role) {
        "user" -> Cy
        "model1" -> Am
        "model2" -> Cy
        "researcher" -> Pr
        "system" -> Gy
        else -> colors.Text2
    }
    val roleLabel = when (bubble.role) {
        "user" -> "YOU"
        "model1" -> "PLANNER"
        "model2" -> "CODER"
        "researcher" -> "RESEARCH"
        "system" -> "SYS"
        else -> bubble.role.uppercase()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(roleColor))
            Spacer(Modifier.width(6.dp))
            Text(roleLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                color = roleColor, fontFamily = FontFamily.Monospace)
        }
        // Thinking block — collapsible reasoning from <think> tags
        if (bubble.thinkingContent.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.Accent2.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, colors.Accent2.copy(0.15f)),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠  Reasoning", fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.Accent2,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(if (expanded) "▲" else "▼", fontSize = 8.sp, color = colors.Text3)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(bubble.thinkingContent, fontSize = 9.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (bubble.isUser) Cy.copy(alpha = 0.04f) else colors.Surface,
            border = BorderStroke(1.dp, if (bubble.isUser) Cy.copy(0.08f) else colors.Border.copy(0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bubble.content,
                    fontSize = 11.sp, color = if (bubble.isError) Rd else colors.Text,
                    fontFamily = FontFamily.Monospace
                )
                if (bubble.isStreaming) {
                    StreamingCursor(roleColor)
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor(color: Color) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        // Infinite blink — cancelled automatically when composable leaves tree
        while (true) {
            alpha.animateTo(0f, animationSpec = tween(400))
            alpha.animateTo(1f, animationSpec = tween(400))
        }
    }
    Box(Modifier.width(2.dp).height(12.dp).background(color.copy(alpha = alpha.value)))
    {}
}

// ─── File progress ────────────────────────────────────────────────────────────
@Composable
private fun FileProgress(index: Int, total: Int, name: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.06f), border = BorderStroke(1.dp, accent.copy(0.15f))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Writing files…", fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text("${index + 1}/$total  $name", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── Sure buttons row ─────────────────────────────────────────────────────────
// ─── Done stats ───────────────────────────────────────────────────────────────
@Composable
private fun DoneStats(lines: Int, bytes: Long, debugCount: Int, colors: ZcPalette) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Cy.copy(0.15f))
    Text("📦  Lines: $lines  ·  Size: ${bytes / 1024}KB  ·  Debug sessions: $debugCount",
        fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp))
}

// ─── Input area ──────────────────────────────────────────────────────────────
@Composable
private fun InputArea(
    inputText: String, onTextChange: (String) -> Unit,
    onFilePick: () -> Unit, onSend: () -> Unit,
    phase: InventPhase, totalTokens: Int,
    isGenerating: Boolean = false,
    colors: ZcPalette
) {
    Surface(Modifier.fillMaxWidth().imePadding(), color = colors.Surface, shadowElevation = 2.dp) {
        Column {
            // Toolbar — only attach file, no think/search toggles
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniToggle(Icons.Outlined.AttachFile, "Attach", false, onFilePick, colors)
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Cy)
                    Spacer(Modifier.width(4.dp))
                }
                if (totalTokens > 0) {
                    Text("${totalTokens}t", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                }
            }
            // Input + Send
            Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    enabled = !isGenerating,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp, max = 72.dp),
                    singleLine = false,
                    placeholder = {
                        Text(
                            when {
                                isGenerating -> "Waiting for response…"
                                phase == InventPhase.QUESTIONING -> "Describe your project…"
                                phase == InventPhase.DONE -> "All done! Export or start new."
                                else -> ">  type here…"
                            }, fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cy.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = Cy
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank() && !isGenerating) onSend() }),
                    maxLines = 2
                )
                Spacer(Modifier.width(6.dp))
                val canSend = inputText.isNotBlank() && !isGenerating
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        .let { m ->
                            if (canSend) m.background(Brush.linearGradient(listOf(Cy, Cy.copy(0.6f))))
                            else m.background(colors.Border)
                        }
                        .clickable { if (canSend) onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (canSend) Color.Black else colors.Text3, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit, colors: ZcPalette) {
    Surface(
        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable { onClick() },
        color = if (active) Cy.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (active) Cy.copy(0.4f) else colors.Border.copy(0.3f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) Cy else colors.Text3, modifier = Modifier.size(11.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODEL PICKER SHEET
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun ModelPickerSheet(
    roleIdx: Int, onDismiss: () -> Unit,
    onSelect: (path: String, name: String, useForAll: Boolean) -> Unit,
    colors: ZcPalette
) {
    val app = com.gguf.zerocopy.ZeroCopyApp.instance
    val models by app.modelRepository.models.collectAsState()
    var useForAll by remember { mutableStateOf(false) }

    val roleLabel = when (roleIdx) {
        -2 -> "Planner + Coder"
        -1 -> "All Roles"
        0 -> "Planner"
        1 -> "Researcher"
        2 -> "Coder"
        else -> "Model"
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f).clickable {},
            shape = RoundedCornerShape(16.dp), color = colors.Card) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("$roleLabel Model", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().clickable { useForAll = !useForAll }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useForAll, onCheckedChange = { useForAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = Cy, checkmarkColor = Color.Black))
                    Text("Use for all roles", fontSize = 10.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                if (models.isEmpty()) {
                    Text("No models found. Import from Models tab.", fontSize = 10.sp,
                        color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(models) { m ->
                            Surface(Modifier.fillMaxWidth().clickable { onSelect(m.path, m.name, useForAll) },
                                shape = RoundedCornerShape(8.dp), color = colors.Surface,
                                border = BorderStroke(1.dp, colors.Border.copy(0.3f))) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, fontSize = 10.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            Text(m.format.uppercase(), fontSize = 7.sp, color = Cy, fontFamily = FontFamily.Monospace)
                                            Text(" · ${m.sizeFormatted}", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    Icon(Icons.Filled.PlayArrow, "Select", tint = Cy, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SESSION POPUP (simplified)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SessionPopup(
    sessions: List<SessionInfo>, sessionId: String,
    onSwitch: (String) -> Unit, onDelete: (String) -> Unit,
    onDismiss: () -> Unit, colors: ZcPalette, vm: InventViewModel
) {
    val context = LocalContext.current
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var selectedProjectName by remember { mutableStateOf("") }
    var selectedPhase by remember { mutableStateOf<InventPhase?>(null) }

    LaunchedEffect(selectedSession) {
        if (selectedSession != null) {
            val z = InventStorage.loadZcp(context, selectedSession!!)
            val st = InventStorage.loadSession(context, selectedSession!!)
            selectedFiles = z?.fileTree ?: emptyList()
            selectedProjectName = z?.projectName ?: ""
            selectedPhase = st?.phase
        } else { selectedFiles = emptyList(); selectedProjectName = ""; selectedPhase = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f).clickable {},
            shape = RoundedCornerShape(16.dp), color = colors.Card) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedSession != null) selectedSession = null else onDismiss() }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text(if (selectedSession != null) "Session Files" else "Sessions",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(6.dp))
                if (selectedSession != null) {
                    // Files view
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Cy.copy(alpha = 0.1f), border = BorderStroke(1.dp, Cy.copy(0.3f))) {
                        Row(Modifier.clickable { onSwitch(selectedSession!!) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Cy, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Continue ${selectedProjectName.ifEmpty { "Session" }}", fontSize = 10.sp, color = Cy,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (selectedPhase != null) Text(selectedPhase!!.name, fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(selectedFiles) { node ->
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
                                color = colors.Surface.copy(alpha = 0.5f)) {
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description, null,
                                        tint = if (node.isDir) Am else Cy, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(node.path, fontSize = 9.sp, color = colors.Text, fontFamily = FontFamily.Monospace,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (selectedFiles.isEmpty()) item { Text("No files yet", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(4.dp)) }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(sessions) { s ->
                            val isCurrent = s.id == sessionId
                            Surface(Modifier.fillMaxWidth().clickable { selectedSession = s.id },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) Cy.copy(alpha = 0.08f) else colors.Surface,
                                border = if (isCurrent) BorderStroke(1.dp, Cy.copy(0.3f)) else null) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.projectName, fontSize = 10.sp, color = colors.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                        Row {
                                            Text(s.phase.name, fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                            if (s.fileCount > 0) Text(" · ${s.fileCount} files", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    IconButton(onClick = { onSwitch(s.id) }, modifier = Modifier.size(22.dp)) {
                                        Icon(Icons.Filled.PlayArrow, "Switch", tint = Cy, modifier = Modifier.size(12.dp))
                                    }
                                    IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline, "Delete", tint = Rd.copy(0.5f), modifier = Modifier.size(10.dp))
                                    }
                                }
                            }
                        }
                        if (sessions.isEmpty()) item { Text("No saved sessions", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS POPUP (redesigned)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SettingsPopup2(
    onDismiss: () -> Unit, colors: ZcPalette,
    model1Path: String, model2Path: String, researcherPath: String,
    modelMode: ModelMode = ModelMode.TRIPLE, restrictRole: Int = -1,
    onReload: () -> Unit = {},
    reasoningEnabled: Boolean = false,
    onToggleReasoning: () -> Unit = {}
) {
    var settingsTab by remember { mutableIntStateOf(0) }

    val getCfg = { role: String -> SettingsManager.getInventModelConfig(role) }
    val plannerCfg = remember(model1Path) { getCfg("Planner") }
    val researcherCfg = remember(researcherPath) { getCfg("Researcher") }
    val coderCfg = remember(model2Path) { getCfg("Coder") }

    val allTabs = when (modelMode) {
        ModelMode.SINGLE -> listOf("Planner" to "Planner")
        ModelMode.DUAL -> listOf("Planner+Coder" to "Planner", "Researcher" to "Researcher")
        ModelMode.TRIPLE -> listOf("Planner" to "Planner", "Researcher" to "Researcher", "Coder" to "Coder")
    }
    val tabs = if (restrictRole < 0) allTabs else allTabs.filterIndexed { i, _ -> i == restrictRole }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f).clickable {},
            shape = RoundedCornerShape(16.dp), color = colors.Card) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Model Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(6.dp))
                // Tab chips
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    tabs.forEachIndexed { i, (label, _) ->
                        Surface(shape = RoundedCornerShape(6.dp),
                            color = if (settingsTab == i) Cy.copy(alpha = 0.12f) else colors.Surface,
                            border = BorderStroke(1.dp, if (settingsTab == i) Cy else colors.Border)) {
                            Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                                color = if (settingsTab == i) Cy else colors.Text3, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { settingsTab = i }.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Config area
                val (_, roleKey) = if (settingsTab < tabs.size) tabs[settingsTab] else ("Planner" to "Planner")
                val cfg = when (roleKey) {
                    "Planner" -> plannerCfg; "Researcher" -> researcherCfg; else -> coderCfg
                }
                val path = when (roleKey) {
                    "Planner" -> model1Path; "Researcher" -> researcherPath; else -> model2Path
                }
                ConfigSliders(role = roleKey, config = cfg, modelPath = path, colors)
                Spacer(Modifier.height(4.dp))
                // Thinking toggle
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠 Reasoning", fontSize = 9.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = reasoningEnabled,
                        onCheckedChange = { onToggleReasoning() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Cy, checkedThumbColor = colors.Bg,
                            uncheckedTrackColor = colors.Border, uncheckedThumbColor = colors.Text3
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { onReload(); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Cy)) {
                    Text("Confirm ✓", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun ConfigSliders(role: String, config: ModelTokenConfig?, modelPath: String, colors: ZcPalette) {
    val modelName = modelPath.substringAfterLast('/').substringAfterLast('\\').take(28)
    var ctx by remember { mutableStateOf(config?.ctx ?: 2048) }
    var maxNew by remember { mutableStateOf(config?.maxNew ?: 512) }
    var gpuLayers by remember { mutableStateOf(config?.gpuLayers ?: 0) }
    var temp by remember { mutableStateOf(config?.temperature ?: 0.7f) }
    var topP by remember { mutableStateOf(config?.topP ?: 0.9f) }

    fun save() {
        val base = config ?: ModelTokenConfig(ctx = 2048, maxNew = 512, gpuLayers = 0)
        SettingsManager.setInventModelConfig(role, base.copy(ctx = ctx, maxNew = maxNew, gpuLayers = gpuLayers, temperature = temp, topP = topP))
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
        Text(role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
        Text(modelName, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))

        // Context — label + number field
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Context", fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = ctx.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { ctx = n.coerceIn(512, 32768); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = RoundedCornerShape(6.dp)
            )
        }
        Slider(value = ctx.toFloat(), onValueChange = { ctx = it.roundToInt().coerceIn(512, 32768); save() },
            valueRange = 512f..32768f,
            modifier = Modifier.fillMaxWidth().height(18.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))

        // Max New — label + number field
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Max Tokens", fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = maxNew.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { maxNew = n.coerceIn(64, ctx - 64); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = RoundedCornerShape(6.dp)
            )
        }
        Slider(value = maxNew.toFloat(), onValueChange = { maxNew = it.roundToInt().coerceIn(64, ctx - 64); save() },
            valueRange = 64f..(ctx - 64).coerceAtLeast(128).toFloat(),
            modifier = Modifier.fillMaxWidth().height(18.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))

        SliderRow("GPU Layers", gpuLayers.toFloat(), -1f..200f, 201, { if (it.toInt() < 0) "All" else "${it.toInt()}" }, { gpuLayers = it.toInt(); save() }, colors)
        SliderRow("Temperature", temp, 0.0f..2.0f, 40, { "%.2f".format(it) }, { temp = it; save() }, colors)
        SliderRow("Top-P", topP, 0.0f..1.0f, 20, { "%.2f".format(it) }, { topP = it; save() }, colors)
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, format: (Float) -> String, onChange: (Float) -> Unit, colors: ZcPalette
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 8.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
            Text(format(value), fontSize = 8.sp, color = Cy, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().height(18.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))
    }
}
