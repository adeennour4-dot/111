package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.*
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
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import java.io.File

// ─── Colors ──────────────────────────────────────────────────────────────────
private val CyanGreen = Color(0xFF00E5A0)
private val GlowCyan = Color(0x6000E5A0)
private val PurpleAccent = Color(0xFF8B83FF)
private val AmberAccent = Color(0xFFFFB74D)
private val DimGray = Color(0xFF6A6A7A)

// ─── Log Entry model ─────────────────────────────────────────────────────────
private data class LogLine(
    val text: String,
    val tint: Color? = null,
    val isPhase: Boolean = false,
    val isError: Boolean = false,
    val indent: Int = 0,
    val tree: String = "" // "│ ", "├─ ", "└─ ", "  "
)

private fun buildLog(messages: List<InventMessage>, phase: InventPhase): List<LogLine> {
    val lines = mutableListOf<LogLine>()
    var lastPhase: InventPhase? = null
    for (msg in messages) {
        // Phase separator on change
        if (lastPhase != null && msg.phase != lastPhase) {
            lines.add(LogLine("", isPhase = true))
            lines.add(LogLine("  >>> ${msg.phase.name} <<<", isPhase = true))
            lines.add(LogLine("", isPhase = true))
        }
        lastPhase = msg.phase

        val roleLabel = when (msg.role) {
            "user" -> "YOU"
            "model1" -> "PLANNER"
            "model2" -> "CODER"
            "researcher" -> "RESEARCH"
            "system" -> "SYS"
            else -> msg.role.uppercase()
        }
        val roleColor = when (msg.role) {
            "user" -> CyanGreen
            "model1" -> AmberAccent
            "model2" -> CyanGreen
            "researcher" -> PurpleAccent
            "system" -> DimGray
            else -> null
        }
        val contentLines = msg.content.split("\n")
        contentLines.forEachIndexed { i, line ->
            val prefix = if (i == 0) "$roleLabel" else ""
            val treeChar = if (i == 0 && contentLines.size == 1) "├─ " else "│ "
            lines.add(LogLine(
                text = if (i == 0) "$roleLabel $line" else "   $line",
                tint = roleColor,
                indent = 0
            ))
        }
    }
    // Show phase if no messages yet
    if (lines.isEmpty()) {
        lines.add(LogLine("", isPhase = true))
        lines.add(LogLine("  >>> ${phase.name} <<<", isPhase = true))
        lines.add(LogLine("", isPhase = true))
    }
    return lines
}

// ─── Main Screen ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    researcherPath: String, researcherName: String,
    offlineMode: Boolean, sameModelMode: Boolean,
    onBack: () -> Unit,
    onModelsClick: () -> Unit,
    vm: InventViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    var showSessionPopup by remember { mutableStateOf(false) }
    var showSettingsPopup by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var showThinking by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var modelPickerRole by remember { mutableStateOf<Int?>(null) }
    var settingsTabToShow by remember { mutableStateOf(-1) }
    var settingsRestrictRole by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Build log lines from messages
    val logLines = remember(ui.messages, ui.phase) { buildLog(ui.messages, ui.phase) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
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
                val file = File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                val content = if (file.length() < 50_000) file.readText()
                    else "[File too large: ${file.length()} bytes]"
                vm.sendUserMessage("[Attached: ${uri.lastPathSegment}]\n\n$content",
                    planWithSearch = showSearch, thinkTag = showThinking)
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                researcherPath, researcherName, offlineMode, sameModelMode)
        }
    }
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty())
            listState.animateScrollToItem(logLines.size - 1)
    }

    // Phase color
    val phaseColor = when (ui.phase) {
        InventPhase.QUESTIONING -> CyanGreen
        InventPhase.SEARCHING -> PurpleAccent
        InventPhase.PLANNING -> AmberAccent
        InventPhase.CONFIRMING -> CyanGreen
        InventPhase.GENERATING -> CyanGreen
        InventPhase.REPLANNING -> AmberAccent
        InventPhase.FINALIZING -> PurpleAccent
        InventPhase.DONE -> CyanGreen
        InventPhase.DEBUGGING -> Color(0xFFFF6B6B)
    }

    // Phase progress fraction
    val phaseProgress = when (ui.phase) {
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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(phaseColor))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (ui.projectName.isNotEmpty()) ui.projectName.take(20) else "INVENT",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = colors.Text, fontFamily = FontFamily.Monospace
                            )
                            if (ui.totalTokensUsed > 0) {
                                Spacer(Modifier.width(8.dp))
                                Text("· ${ui.totalTokensUsed}t", fontSize = 10.sp,
                                    color = colors.Text3, fontFamily = FontFamily.Monospace)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2, modifier = Modifier.size(20.dp))
                        }
                    },
                    actions = {
                        // Export button
                        if (ui.phase == InventPhase.DONE || ui.phase == InventPhase.DEBUGGING) {
                            IconButton(
                                onClick = { vm.exportProjectZip() },
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                    .background(CyanGreen.copy(alpha = 0.12f))
                            ) {
                                Icon(Icons.Filled.FileDownload, "Export", tint = CyanGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                        // History
                        IconButton(
                            onClick = { showSessionPopup = true },
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Outlined.History, "History", tint = colors.Text2, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(2.dp))
                        // Settings
                        IconButton(
                            onClick = { showSettingsPopup = true },
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Filled.Settings, "Settings", tint = colors.Text2, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(2.dp))
                        // Save session
                        IconButton(
                            onClick = { vm.saveCurrentSession() },
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Filled.Save, "Save", tint = colors.Text2, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(2.dp))
                        // New session
                        IconButton(
                            onClick = { vm.startNewSession { onModelsClick() } },
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Filled.Add, "New", tint = Color.White, modifier = Modifier.size(14.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
                )

                // ── Progress Bar ─────────────────────────────────────
                LinearProgressIndicator(
                    progress = { phaseProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = phaseColor,
                    trackColor = colors.Border.copy(alpha = 0.2f)
                )
            }
        },
        containerColor = colors.Bg
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {

                // ── Model Mode selector + Status ──────────────────────
                ModelModeRow(
                    modelMode = ui.modelMode,
                    onModeChange = { vm.setModelMode(it) },
                    colors = colors
                )
                ModelStatusRow(
                    plannerLoaded = ui.plannerLoaded,
                    researcherLoaded = ui.researcherLoaded,
                    coderLoaded = ui.coderLoaded,
                    plannerName = ui.model1Name,
                    researcherName = ui.researcherName,
                    coderName = ui.model2Name,
                    modelMode = ui.modelMode,
                    phase = ui.phase,
                    onTabClick = { role ->
                        // Mode-aware: in mode 1, all roles share one model
                        modelPickerRole = when (ui.modelMode) {
                            ModelMode.SINGLE -> -1
                            ModelMode.DUAL -> if (role == 1) 1 else -2
                            ModelMode.TRIPLE -> role
                        }
                    },
                    colors = colors
                )

                // ── Build Log ────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    // Swap/loading banner
                    if (ui.swapInfo.isNotEmpty()) {
                        item(key = "swap_info") {
                            SwapBanner(ui.swapInfo, colors)
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Error banner
                    if (ui.error.isNotEmpty()) {
                        item(key = "error_banner") {
                            ErrorBanner(ui.error, colors)
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Log entries
                    itemsIndexed(logLines, key = { i, _ -> "log_$i" }) { _, line ->
                        if (line.isPhase) {
                            // Phase separator
                            PhaseSeparator(line.text, phaseColor, colors)
                        } else if (line.isError) {
                            Text(line.text, fontSize = 11.sp,
                                color = colors.Red, fontFamily = FontFamily.Monospace)
                        } else {
                            val indentStr = "  ".repeat(line.indent)
                            val prefix = line.tree
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(indentStr, fontSize = 11.sp,
                                    color = colors.Text3, fontFamily = FontFamily.Monospace)
                                if (prefix.isNotEmpty()) {
                                    Text(prefix, fontSize = 11.sp,
                                        color = colors.Text3.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace)
                                }
                                Text(line.text, fontSize = 11.sp,
                                    color = line.tint ?: colors.Text,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // File generation progress
                    if (ui.phase == InventPhase.GENERATING && ui.totalFiles > 0) {
                        item(key = "file_progress") {
                            val progress = "  >> File ${ui.currentFileIndex + 1}/${ui.totalFiles}: ${ui.currentFileName}"
                            Text(progress, fontSize = 11.sp, color = CyanGreen,
                                fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Sure buttons
                    if (ui.showSureButtons && ui.phase == InventPhase.CONFIRMING) {
                        item(key = "sure_buttons") {
                            SureButtons(onSure = vm::onSure, onNotSure = vm::onNotSure, colors = colors)
                        }
                    }

                    // Stats when DONE
                    if (ui.phase == InventPhase.DONE) {
                        item(key = "stats") {
                            Spacer(Modifier.height(4.dp))
                            Text("  Lines: ${ui.totalLines}  |  Size: ${ui.totalGeneratedBytes / 1024}KB  |  Debug: ${ui.debugSessionCount}",
                                fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // ── Input Console ────────────────────────────────────
                InputConsole(
                    inputText = inputText,
                    onTextChange = { inputText = it },
                    showThinking = showThinking,
                    showSearch = showSearch,
                    onToggleThinking = { showThinking = !showThinking },
                    onToggleSearch = { showSearch = !showSearch },
                    onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendUserMessage(inputText,
                                planWithSearch = showSearch, thinkTag = showThinking)
                            inputText = ""
                        }
                    },
                    phase = ui.phase,
                    totalTokens = ui.totalTokensUsed,
                    colors = colors
                )
            }

            // ── Popups ────────────────────────────────────────────────
            if (showSessionPopup) {
                SessionPopup(
                    sessions = ui.sessions,
                    sessionId = ui.sessionId,
                    selectedSession = selectedSession,
                    onSelectSession = { selectedSession = it },
                    onSwitch = { vm.switchToSession(it); showSessionPopup = false; selectedSession = null },
                    onDelete = { vm.deleteSessionById(it) },
                    onBack = { selectedSession = null },
                    onDismiss = { showSessionPopup = false; selectedSession = null },
                    colors = colors, vm = vm
                )
            }
            if (showSettingsPopup) {
                val shownTab = when (ui.modelMode) {
                    ModelMode.SINGLE -> 0
                    ModelMode.DUAL -> if (settingsRestrictRole <= 0) 0 else 1
                    ModelMode.TRIPLE -> settingsRestrictRole.coerceIn(0, 2)
                }
                SettingsPopup(
                    onDismiss = { showSettingsPopup = false; settingsTabToShow = -1; settingsRestrictRole = -1 },
                    colors = colors,
                    model1Path = model1Path, model2Path = model2Path, researcherPath = researcherPath,
                    initialTab = shownTab,
                    modelMode = ui.modelMode,
                    restrictRole = settingsRestrictRole,
                    onReload = { vm.reloadInventModel() }
                )
            }
            if (modelPickerRole != null) {
                val roleIdx = modelPickerRole!!
                val roleLabel = when (roleIdx) {
                    -2 -> "Planner + Coder"
                    -1 -> "All Roles"
                    0 -> "Planner"
                    1 -> "Researcher"
                    2 -> "Coder"
                    else -> "Model"
                }
                ModelPickerDialog(
                    roleLabel = roleLabel,
                    onDismiss = { modelPickerRole = null },
                    onSelect = { path, name, useAll ->
                        when (roleIdx) {
                            -1 -> { // All roles (mode 1)
                                vm.selectModelTab(0, path, name, useAll)
                                vm.selectModelTab(1, path, name, useAll)
                                vm.selectModelTab(2, path, name, useAll)
                                settingsRestrictRole = -1
                            }
                            -2 -> { // Planner + Coder (mode 2)
                                vm.selectModelTab(0, path, name, false)
                                vm.selectModelTab(2, path, name, false)
                                settingsRestrictRole = 0
                            }
                            0 -> { // Planner (mode 3)
                                vm.selectModelTab(0, path, name, false)
                                settingsRestrictRole = 0
                            }
                            1 -> { // Researcher (mode 2 or 3)
                                vm.selectModelTab(1, path, name, false)
                                settingsRestrictRole = 1
                            }
                            2 -> { // Coder (mode 3)
                                vm.selectModelTab(2, path, name, false)
                                settingsRestrictRole = 2
                            }
                        }
                        modelPickerRole = null; showSettingsPopup = true
                    },
                    colors = colors
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

// ─── Phase Separator ──────────────────────────────────────────────────────────
@Composable
fun PhaseSeparator(text: String, phaseColor: Color, colors: ZcPalette) {
    val disp = text.removePrefix("  >>> ").removeSuffix(" <<<")
    if (disp.isEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp),
            color = phaseColor.copy(alpha = 0.2f), thickness = 1.dp)
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(phaseColor.copy(alpha = 0.2f)))
            Spacer(Modifier.width(8.dp))
            Text(disp, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = phaseColor, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(1.dp).background(phaseColor.copy(alpha = 0.2f)))
        }
    }
}

// ─── Model Mode Row ───────────────────────────────────────────────────────────
@Composable
fun ModelModeRow(
    modelMode: ModelMode,
    onModeChange: (ModelMode) -> Unit,
    colors: ZcPalette
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Text("Mode:", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        ModelMode.entries.forEach { mode ->
            val active = modelMode == mode
            Surface(
                modifier = Modifier.height(24.dp).clip(RoundedCornerShape(4.dp))
                    .clickable { onModeChange(mode) },
                color = if (active) CyanGreen.copy(alpha = 0.12f) else colors.Surface,
                border = BorderStroke(1.dp, if (active) CyanGreen.copy(0.4f) else colors.Border.copy(0.3f))
            ) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when (mode) { ModelMode.SINGLE -> "1"; ModelMode.DUAL -> "2"; ModelMode.TRIPLE -> "3" },
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (active) CyanGreen else colors.Text3,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

// ─── Model Status Row ─────────────────────────────────────────────────────────
@Composable
fun ModelStatusRow(
    plannerLoaded: Boolean, researcherLoaded: Boolean, coderLoaded: Boolean,
    plannerName: String, researcherName: String, coderName: String,
    modelMode: ModelMode,
    phase: InventPhase,
    onTabClick: (Int) -> Unit,
    colors: ZcPalette
) {
    val isActive = { role: Int ->
        when (role) {
            0 -> phase in listOf(InventPhase.QUESTIONING, InventPhase.PLANNING, InventPhase.CONFIRMING, InventPhase.FINALIZING)
            1 -> phase == InventPhase.SEARCHING
            2 -> phase in listOf(InventPhase.GENERATING, InventPhase.REPLANNING)
            else -> false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(0 to "PLANNER", 1 to "RESEARCH", 2 to "CODER").forEach { (idx, label) ->
            val loaded = when (idx) { 0 -> plannerLoaded; 1 -> researcherLoaded; 2 -> coderLoaded; else -> false }
            val accent = when (idx) { 0 -> AmberAccent; 1 -> PurpleAccent; 2 -> CyanGreen; else -> CyanGreen }
            val modelName = when (idx) { 0 -> plannerName; 1 -> researcherName; 2 -> coderName; else -> "" }
            val hidden = idx == 1 && modelMode == ModelMode.SINGLE ||
                         idx == 2 && modelMode == ModelMode.SINGLE
            if (!hidden) {
                Surface(
                    modifier = Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                        .clickable { onTabClick(idx) },
                    color = if (loaded) accent.copy(alpha = 0.08f) else colors.Surface,
                    border = BorderStroke(1.dp,
                        if (loaded) accent.copy(alpha = 0.3f) else colors.Border.copy(alpha = 0.2f))
                ) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (loaded) accent else colors.Text3.copy(alpha = 0.3f)))
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                                color = if (loaded) Color.White else colors.Text3,
                                fontFamily = FontFamily.Monospace)
                            Text(
                                modelName.substringBeforeLast('.').take(10).ifEmpty { if (loaded) "ready" else "off" },
                                fontSize = 6.sp,
                                color = if (loaded) accent else colors.Text3.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Swap Banner ──────────────────────────────────────────────────────────────
@Composable
fun SwapBanner(text: String, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
        color = CyanGreen.copy(alpha = 0.06f), border = BorderStroke(1.dp, CyanGreen.copy(0.15f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = CyanGreen)
            Spacer(Modifier.width(6.dp))
            Text(text, color = CyanGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Error Banner ─────────────────────────────────────────────────────────────
@Composable
fun ErrorBanner(text: String, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
        color = colors.Red.copy(alpha = 0.1f), border = BorderStroke(1.dp, colors.Red.copy(0.3f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = colors.Red, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = colors.Red, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Sure / Not Sure ──────────────────────────────────────────────────────────
@Composable
fun SureButtons(onSure: () -> Unit, onNotSure: () -> Unit, colors: ZcPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        OutlinedButton(onClick = onNotSure, shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AmberAccent.copy(0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberAccent)) {
            Text("Not Sure", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Button(onClick = onSure, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanGreen)) {
            Text("Sure ✓", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Black)
        }
    }
}

// ─── Toolbar Toggle ─────────────────────────────────────────────────────────
@Composable
private fun ToolbarToggle(
    icon: ImageVector, label: String, active: Boolean,
    onClick: () -> Unit, colors: ZcPalette
) {
    Surface(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).clickable { onClick() },
        color = if (active) CyanGreen.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (active) CyanGreen.copy(alpha = 0.4f) else colors.Border.copy(alpha = 0.3f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) CyanGreen else colors.Text3, modifier = Modifier.size(12.dp))
        }
    }
}

// ─── Input Console ──────────────────────────────────────────────────────────
@Composable
fun InputConsole(
    inputText: String, onTextChange: (String) -> Unit,
    showThinking: Boolean, showSearch: Boolean,
    onToggleThinking: () -> Unit, onToggleSearch: () -> Unit,
    onFilePick: () -> Unit, onSend: () -> Unit,
    phase: InventPhase, totalTokens: Int,
    colors: ZcPalette
) {
    Surface(Modifier.fillMaxWidth().imePadding(), color = colors.Surface,
        shadowElevation = 2.dp) {
        Column {
            // Toolbar row
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ToolbarToggle(Icons.Outlined.AttachFile, "File", false, onFilePick, colors)
                Spacer(Modifier.width(4.dp))
                ToolbarToggle(Icons.Outlined.Psychology, "Think", showThinking, onToggleThinking, colors)
                Spacer(Modifier.width(4.dp))
                ToolbarToggle(Icons.Outlined.Search, "Search", showSearch, onToggleSearch, colors)
                Spacer(Modifier.weight(1f))
                if (totalTokens > 0) {
                    Text("${totalTokens}t", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                }
            }
            // Input + Send
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp, max = 80.dp),
                    singleLine = false,
                    placeholder = {
                        Text(
                            when {
                                phase == InventPhase.QUESTIONING -> "Describe your project..."
                                phase == InventPhase.DONE -> "Build complete · export or new"
                                else -> ">"
                            }, fontSize = 12.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanGreen.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = CyanGreen
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    maxLines = 3
                )
                Spacer(Modifier.width(6.dp))
                val canSend = inputText.isNotBlank() || phase == InventPhase.QUESTIONING
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .let { mod -> if (canSend) mod.background(Brush.linearGradient(listOf(CyanGreen, CyanGreen.copy(0.6f))))
                            else mod.background(colors.Border) }
                        .clickable { if (canSend) onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (canSend) Color.Black else colors.Text3, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODEL PICKER
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun ModelPickerDialog(
    roleLabel: String, onDismiss: () -> Unit,
    onSelect: (path: String, name: String, useForAll: Boolean) -> Unit,
    colors: ZcPalette
) {
    val app = com.gguf.zerocopy.ZeroCopyApp.instance
    val models by app.modelRepository.models.collectAsState()
    var useForAll by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.8f).clickable {},
            shape = RoundedCornerShape(20.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Select $roleLabel Model", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clickable { useForAll = !useForAll }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useForAll, onCheckedChange = { useForAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = CyanGreen, checkmarkColor = Color.Black))
                    Text("Use for all roles", fontSize = 11.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                if (models.isEmpty()) {
                    Text("No models. Download from Models tab first.", fontSize = 11.sp,
                        color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(models) { m ->
                            Surface(Modifier.fillMaxWidth().clickable { onSelect(m.path, m.name, useForAll) },
                                shape = RoundedCornerShape(10.dp), color = colors.Surface,
                                border = BorderStroke(1.dp, colors.Border.copy(0.3f))) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, fontSize = 11.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            Text(m.format.uppercase(), fontSize = 8.sp, color = CyanGreen, fontFamily = FontFamily.Monospace)
                                            Text(" · ${m.sizeFormatted}", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    Icon(Icons.Filled.PlayArrow, "Select", tint = CyanGreen, modifier = Modifier.size(18.dp))
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
// SESSION POPUP
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SessionPopup(
    sessions: List<SessionInfo>, sessionId: String,
    selectedSession: String?, onSelectSession: (String?) -> Unit,
    onSwitch: (String) -> Unit, onDelete: (String) -> Unit,
    onBack: () -> Unit, onDismiss: () -> Unit,
    colors: ZcPalette, vm: InventViewModel
) {
    val context = LocalContext.current
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
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f).clickable {},
            shape = RoundedCornerShape(20.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedSession != null) onBack() else onDismiss() }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text(if (selectedSession != null) "Session Files" else "Sessions",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                if (selectedSession != null) {
                    SessionFilesView(files = selectedFiles, projectName = selectedProjectName,
                        phase = selectedPhase, sessionId = selectedSession, colors = colors,
                        onSwitch = { onSwitch(selectedSession) }, onDismiss = onDismiss)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sessions) { s ->
                            val isCurrent = s.id == sessionId
                            Surface(Modifier.fillMaxWidth().clickable { onSelectSession(s.id) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) CyanGreen.copy(alpha = 0.08f) else colors.Surface,
                                border = if (isCurrent) BorderStroke(1.dp, CyanGreen.copy(0.3f)) else null) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.projectName, fontSize = 11.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                        Row {
                                            Text(s.phase.name, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                            if (s.fileCount > 0) Text(" · ${s.fileCount} files", fontSize = 8.sp,
                                                color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    IconButton(onClick = { onSwitch(s.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.PlayArrow, "Switch", tint = CyanGreen, modifier = Modifier.size(14.dp))
                                    }
                                    IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(22.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline, "Delete", tint = colors.Red.copy(0.5f), modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                        if (sessions.isEmpty()) item { Text("No saved sessions", fontSize = 11.sp, color = colors.Text3,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionFilesView(files: List<FileNode>, projectName: String, phase: InventPhase?, sessionId: String,
    colors: ZcPalette, onSwitch: () -> Unit, onDismiss: () -> Unit = {}) {
    Column {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            color = CyanGreen.copy(alpha = 0.1f), border = BorderStroke(1.dp, CyanGreen.copy(0.3f))) {
            Row(Modifier.clickable { onSwitch() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = CyanGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue ${projectName.ifEmpty { "Session" }}", fontSize = 11.sp, color = CyanGreen,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (phase != null) Text(phase.name, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(files) { node -> FileRow(node, colors) }
            if (files.isEmpty()) item { Text("No files yet", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
fun FileRow(node: FileNode, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
        color = colors.Surface.copy(alpha = 0.5f), border = BorderStroke(1.dp, colors.Border.copy(0.2f))) {
        Row(Modifier.padding(8.dp).clickable {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description, null,
                tint = if (node.isDir) AmberAccent else CyanGreen, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(node.path, fontSize = 10.sp, color = colors.Text, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (node.description.isNotEmpty()) Text(node.description, fontSize = 8.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS POPUP
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsPopup(onDismiss: () -> Unit, colors: ZcPalette,
    model1Path: String, model2Path: String, researcherPath: String, initialTab: Int = 0,
    modelMode: ModelMode = ModelMode.TRIPLE, restrictRole: Int = -1,
    onReload: () -> Unit = {}) {
    var settingsTab by remember { mutableStateOf(initialTab) }

    val getCfg = { role: String, _: String ->
        SettingsManager.getInventModelConfig(role) ?: SettingsManager.getModelTokenConfig("")
    }
    val plannerCfg = remember(model1Path) { getCfg("Planner", model1Path) }
    val researcherCfg = remember(researcherPath) { getCfg("Researcher", researcherPath) }
    val coderCfg = remember(model2Path) { getCfg("Coder", model2Path) }

    // Tab definitions based on model mode — filtered by restrictRole
    val allTabDefs = when (modelMode) {
        ModelMode.SINGLE -> listOf("Planner" to "Planner", "Researcher" to "Researcher", "Coder" to "Coder")
        ModelMode.DUAL -> listOf("Planner+Coder" to "Planner", "Researcher" to "Researcher")
        ModelMode.TRIPLE -> listOf("Planner" to "Planner", "Researcher" to "Researcher", "Coder" to "Coder")
    }
    val tabDefs = if (restrictRole < 0 || modelMode == ModelMode.SINGLE) allTabDefs
        else allTabDefs.filterIndexed { i, _ -> i == restrictRole }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f).clickable {},
            shape = RoundedCornerShape(20.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Model Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    tabDefs.forEachIndexed { i, (label, _) ->
                        Surface(shape = RoundedCornerShape(8.dp),
                            color = if (settingsTab == i) CyanGreen.copy(alpha = 0.12f) else colors.Surface,
                            border = BorderStroke(1.dp, if (settingsTab == i) CyanGreen else colors.Border)) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = if (settingsTab == i) CyanGreen else colors.Text3, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { settingsTab = i }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                val (tabLabel, roleKey) = if (settingsTab < tabDefs.size) tabDefs[settingsTab] else ("Planner" to "Planner")
                val cfg = when (roleKey) {
                    "Planner" -> plannerCfg
                    "Researcher" -> researcherCfg
                    else -> coderCfg
                }
                val path = when (roleKey) {
                    "Planner" -> model1Path
                    "Researcher" -> researcherPath
                    else -> model2Path
                }
                ModelConfigView(role = roleKey, config = cfg, modelPath = path, colors = colors)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onReload(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGreen)
                ) {
                    Text("Confirm", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun ModelConfigView(role: String, config: ModelTokenConfig?, modelPath: String, colors: ZcPalette) {
    val modelName = modelPath.substringAfterLast('/').substringAfterLast('\\').take(28)
    var ctx by remember { mutableStateOf(config?.ctx ?: 2048) }
    var maxNew by remember { mutableStateOf(config?.maxNew ?: 512) }
    var gpuLayers by remember { mutableStateOf(config?.gpuLayers ?: 0) }
    var temperature by remember { mutableStateOf(config?.temperature ?: 0.7f) }
    var topP by remember { mutableStateOf(config?.topP ?: 0.9f) }

    fun save() {
        val existing = config ?: ModelTokenConfig(ctx = 2048, maxNew = 512, gpuLayers = 0)
        val updated = existing.copy(ctx = ctx, maxNew = maxNew, gpuLayers = gpuLayers,
            temperature = temperature, topP = topP)
        SettingsManager.setInventModelConfig(role, updated)
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text(role, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyanGreen, fontFamily = FontFamily.Monospace)
            Text(modelName, fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
        }
        item { SettingsSlider("Context Window", ctx.toFloat(), 256f..32768f, 127,
            { "${it.toInt()}" }, { ctx = it.toInt(); save() }, colors) }
        item { SettingsSlider("Max Tokens", maxNew.toFloat(), 64f..8192f, 127,
            { "${it.toInt()}" }, { maxNew = it.toInt(); save() }, colors) }
        item { SettingsSlider("GPU Layers", gpuLayers.toFloat(), -1f..200f, 201,
            { if (it.toInt() < 0) "All" else "${it.toInt()}" }, { gpuLayers = it.toInt(); save() }, colors) }
        item { SettingsSlider("Temperature", temperature, 0.0f..2.0f, 40,
            { "%.2f".format(it) }, { temperature = it; save() }, colors) }
        item { SettingsSlider("Top-P", topP, 0.0f..1.0f, 20,
            { "%.2f".format(it) }, { topP = it; save() }, colors) }
        item { Spacer(Modifier.height(6.dp)); Text("GGUF · TFLite · MNN", fontSize = 9.sp,
            color = colors.Text3, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
fun SettingsSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, format: (Float) -> String, onValueChange: (Float) -> Unit, colors: ZcPalette
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 9.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
            Text(format(value), fontSize = 9.sp, color = CyanGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = CyanGreen,
                activeTrackColor = CyanGreen, inactiveTrackColor = colors.Border.copy(alpha = 0.3f)))
    }
}
