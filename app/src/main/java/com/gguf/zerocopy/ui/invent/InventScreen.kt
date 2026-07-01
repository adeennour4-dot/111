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
    var selectedTab by remember { mutableStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var showThinking by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var modelPickerRole by remember { mutableStateOf<Int?>(null) }
    var settingsTabToShow by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Phase → tab sync
    val phaseTab = when (ui.phase) {
        InventPhase.QUESTIONING, InventPhase.PLANNING,
        InventPhase.CONFIRMING, InventPhase.FINALIZING,
        InventPhase.DONE, InventPhase.DEBUGGING -> 0
        InventPhase.SEARCHING -> 1
        InventPhase.GENERATING, InventPhase.REPLANNING -> 2
    }
    LaunchedEffect(ui.phase) { selectedTab = phaseTab }

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
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty() && ui.messages.size > 1)
            listState.animateScrollToItem(ui.messages.size - 1)
    }

    // ── Phase colors ─────────────────────────────────────────────────
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Phase dot
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(phaseColor))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ui.projectName.isNotEmpty()) ui.projectName.take(16) else "Invent",
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
                    if (ui.phase == InventPhase.DONE) {
                        IconButton(
                            onClick = { vm.exportProjectZip() },
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                .background(CyanGreen.copy(alpha = 0.12f))
                        ) {
                            Icon(Icons.Filled.FileDownload, "Export", tint = CyanGreen, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = { vm.startNewSession { onModelsClick() } },
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(Icons.Filled.Add, "New", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {

                // ── Model Status Strip ──────────────────────────────────
                ModelStatusStrip(
                    selectedTab = selectedTab,
                    plannerLoaded = ui.plannerLoaded,
                    researcherLoaded = ui.researcherLoaded,
                    coderLoaded = ui.coderLoaded,
                    phase = ui.phase,
                    onTabClick = { tab -> selectedTab = tab; modelPickerRole = tab },
                    colors = colors
                )

                // ── Messages ───────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Phase banner
                    item(key = "phase_banner") {
                        PhaseBanner(ui.phase, phaseColor, colors)
                    }

                    // Swap/loading
                    if (ui.swapInfo.isNotEmpty()) {
                        item(key = "swap_info") {
                            SwapBanner(ui.swapInfo, colors)
                        }
                    }

                    // Error
                    if (ui.error.isNotEmpty()) {
                        item(key = "error_banner") {
                            ErrorBanner(ui.error, colors)
                        }
                    }

                    // Messages
                    itemsIndexed(ui.messages, key = { i, m -> "msg_${i}_${m.role}" }) { _, msg ->
                        InventChatBubble(msg, colors)
                    }

                    // Sure / Not Sure
                    if (ui.showSureButtons && ui.phase == InventPhase.CONFIRMING) {
                        item(key = "sure_buttons") {
                            SureButtons(onSure = vm::onSure, onNotSure = vm::onNotSure, colors = colors)
                        }
                    }
                }

                // ── Input Area ─────────────────────────────────────────
                InputArea(
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
                    onSettings = { showSettingsPopup = true },
                    onHistory = { showSessionPopup = true },
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
                SettingsPopup(
                    onDismiss = { showSettingsPopup = false; settingsTabToShow = -1 },
                    colors = colors,
                    model1Path = model1Path, model2Path = model2Path, researcherPath = researcherPath,
                    initialTab = settingsTabToShow.coerceAtLeast(0)
                )
            }
            if (modelPickerRole != null) {
                val roleLabel = when (modelPickerRole) { 0 -> "Planner"; 1 -> "Researcher"; 2 -> "Coder"; else -> "Model" }
                ModelPickerDialog(
                    roleLabel = roleLabel,
                    onDismiss = { modelPickerRole = null },
                    onSelect = { path, name, useAll ->
                        vm.selectModelTab(modelPickerRole ?: 0, path, name, useAll)
                        settingsTabToShow = modelPickerRole ?: 0; modelPickerRole = null; showSettingsPopup = true
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

// ─── Model Status Strip ───────────────────────────────────────────────────────
@Composable
fun ModelStatusStrip(
    selectedTab: Int,
    plannerLoaded: Boolean, researcherLoaded: Boolean, coderLoaded: Boolean,
    phase: InventPhase,
    onTabClick: (Int) -> Unit,
    colors: ZcPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(0 to "Planner", 1 to "Researcher", 2 to "Coder").forEach { (idx, label) ->
            val loaded = when (idx) { 0 -> plannerLoaded; 1 -> researcherLoaded; 2 -> coderLoaded; else -> false }
            val accent = when (idx) { 0 -> CyanGreen; 1 -> PurpleAccent; 2 -> CyanGreen; else -> CyanGreen }
            val isActive = when (idx) {
                0 -> phase in listOf(InventPhase.QUESTIONING, InventPhase.PLANNING, InventPhase.CONFIRMING, InventPhase.FINALIZING)
                1 -> phase == InventPhase.SEARCHING
                2 -> phase in listOf(InventPhase.GENERATING, InventPhase.REPLANNING)
                else -> false
            }
            Surface(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable { onTabClick(idx) },
                shape = RoundedCornerShape(12.dp),
                color = if (selectedTab == idx) accent.copy(alpha = 0.12f) else colors.Surface,
                border = BorderStroke(1.dp,
                    if (selectedTab == idx) accent.copy(alpha = 0.5f)
                    else if (loaded) accent.copy(alpha = 0.25f)
                    else colors.Border.copy(alpha = 0.3f))
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Status dot
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (loaded) accent else colors.Text3.copy(alpha = 0.4f)))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            color = if (loaded || selectedTab == idx) Color.White else colors.Text3,
                            fontFamily = FontFamily.Monospace)
                        Text(
                            if (loaded) "Ready" else if (isActive) "Active..." else "Off",
                            fontSize = 7.sp, color = if (loaded) accent else colors.Text3.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ─── Phase Banner ─────────────────────────────────────────────────────────────
@Composable
fun PhaseBanner(phase: InventPhase, phaseColor: Color, colors: ZcPalette) {
    val (icon, text) = when (phase) {
        InventPhase.QUESTIONING -> "⚡" to "Tell me what to build"
        InventPhase.SEARCHING -> "🔍" to "Researching..."
        InventPhase.PLANNING -> "📋" to "Planning architecture..."
        InventPhase.CONFIRMING -> "✅" to "Review & confirm the plan"
        InventPhase.REPLANNING -> "🔄" to "Resizing files..."
        InventPhase.GENERATING -> "⚙️" to "Generating code..."
        InventPhase.FINALIZING -> "📖" to "Reading project files..."
        InventPhase.DONE -> "🎉" to "Project complete!"
        InventPhase.DEBUGGING -> "🔧" to "Debugging..."
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = phaseColor.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, phaseColor.copy(alpha = 0.15f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Swap Banner ──────────────────────────────────────────────────────────────
@Composable
fun SwapBanner(text: String, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        color = CyanGreen.copy(alpha = 0.06f)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = CyanGreen)
            Spacer(Modifier.width(8.dp))
            Text(text, color = CyanGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Error Banner ─────────────────────────────────────────────────────────────
@Composable
fun ErrorBanner(text: String, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        color = colors.Red.copy(alpha = 0.1f), border = BorderStroke(1.dp, colors.Red.copy(0.3f))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = colors.Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = colors.Red, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Sure / Not Sure ──────────────────────────────────────────────────────────
@Composable
fun SureButtons(onSure: () -> Unit, onNotSure: () -> Unit, colors: ZcPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        OutlinedButton(onClick = onNotSure, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AmberAccent.copy(0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberAccent)) {
            Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
            Text("Not Sure", fontFamily = FontFamily.Monospace)
        }
        Button(onClick = onSure, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanGreen)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
            Text("Sure ✓", fontFamily = FontFamily.Monospace, color = Color.Black)
        }
    }
}

// ─── Chat Bubble ──────────────────────────────────────────────────────────────
@Composable
fun InventChatBubble(msg: InventMessage, colors: ZcPalette) {
    val isUser = msg.role == "user"
    val isModel2 = msg.role == "model2"
    val bg = when { isUser -> colors.UserBg; isModel2 -> CyanGreen.copy(alpha = 0.04f); msg.role == "system" -> colors.Accent.copy(alpha = 0.05f); else -> colors.Card }
    val border = when { isUser -> colors.Accent.copy(alpha = 0.25f); isModel2 -> CyanGreen.copy(alpha = 0.12f); else -> colors.Border.copy(alpha = 0.2f) }
    val label = if (!isUser && !isSystem(msg)) {
        val name = msg.role.uppercase().take(1) + msg.role.drop(1)
        val lblColor = if (isModel2) CyanGreen else colors.Accent
        @Composable { Text(name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = lblColor, fontFamily = FontFamily.Monospace) }
    } else null

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (isUser) 12.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 12.dp),
            color = bg, border = BorderStroke(1.dp, border)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (label != null) { label(); Spacer(Modifier.height(4.dp)) }
                Text(msg.content, fontSize = 12.sp, color = colors.Text,
                    fontFamily = FontFamily.Monospace, maxLines = 30, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun isSystem(msg: InventMessage) = msg.role == "system"

// ─── Input Area ──────────────────────────────────────────────────────────────
@Composable
fun InputArea(
    inputText: String, onTextChange: (String) -> Unit,
    showThinking: Boolean, showSearch: Boolean,
    onToggleThinking: () -> Unit, onToggleSearch: () -> Unit,
    onFilePick: () -> Unit, onSend: () -> Unit,
    onSettings: () -> Unit, onHistory: () -> Unit,
    phase: InventPhase, totalTokens: Int,
    colors: ZcPalette
) {
    Surface(Modifier.fillMaxWidth().imePadding(), color = colors.Surface) {
        Column {
            // Toolbar — row of icon toggles + token count
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple(Icons.Outlined.AttachFile, "File", false) { onFilePick() },
                    Triple(Icons.Outlined.Psychology, "Think", showThinking) { onToggleThinking() },
                    Triple(Icons.Outlined.Search, "Search", showSearch) { onToggleSearch() },
                ).forEach { (icon, label, active, onClick) ->
                    Surface(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { onClick() },
                        color = if (active) CyanGreen.copy(alpha = 0.12f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (active) CyanGreen.copy(alpha = 0.4f) else colors.Border.copy(alpha = 0.3f))
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(icon, label, tint = if (active) CyanGreen else colors.Text3, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                // Settings + History pills
                Surface(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { onSettings() },
                    color = colors.CardLight.copy(alpha = 0.4f), border = BorderStroke(1.dp, colors.Border.copy(0.2f))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, "Settings", tint = colors.Text2, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
                Surface(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { onHistory() },
                    color = colors.CardLight.copy(alpha = 0.4f), border = BorderStroke(1.dp, colors.Border.copy(0.2f))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.History, "History", tint = colors.Text2, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (totalTokens > 0) {
                    Text("${totalTokens}t", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                }
            }
            // Input row
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 100.dp),
                    singleLine = false,
                    placeholder = {
                        Text(
                            when {
                                phase == InventPhase.QUESTIONING -> "Describe your project..."
                                phase == InventPhase.DONE -> "Complete — export or new"
                                else -> "Message..."
                            }, fontSize = 13.sp, color = colors.Text3
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanGreen.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = CyanGreen
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = onSend),
                    maxLines = 4
                )
                Spacer(Modifier.width(6.dp))
                val canSend = inputText.isNotBlank()
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .let { mod -> if (canSend) mod.background(Brush.linearGradient(listOf(CyanGreen, CyanGreen.copy(0.6f))))
                            else mod.background(colors.Border) }
                        .clickable { if (canSend) onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (canSend) Color.Black else colors.Text3, modifier = Modifier.size(18.dp))
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
        Surface(Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.6f).clickable {},
            shape = RoundedCornerShape(24.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Select $roleLabel Model", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clickable { useForAll = !useForAll }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useForAll, onCheckedChange = { useForAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = CyanGreen, checkmarkColor = Color.Black))
                    Text("Use for all roles", fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                if (models.isEmpty()) {
                    Text("No models. Download from Models tab first.", fontSize = 12.sp,
                        color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(models) { m ->
                            Surface(Modifier.fillMaxWidth().clickable { onSelect(m.path, m.name, useForAll) },
                                shape = RoundedCornerShape(12.dp), color = colors.Surface,
                                border = BorderStroke(1.dp, colors.Border.copy(0.3f))) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, fontSize = 12.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            Text(m.format.uppercase(), fontSize = 9.sp, color = CyanGreen, fontFamily = FontFamily.Monospace)
                                            Text(" · ${m.sizeFormatted}", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    Icon(Icons.Filled.PlayArrow, "Select", tint = CyanGreen, modifier = Modifier.size(20.dp))
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
        Surface(Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.7f).clickable {},
            shape = RoundedCornerShape(24.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedSession != null) onBack() else onDismiss() }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text(if (selectedSession != null) "Session Files" else "Past Sessions",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                if (selectedSession != null) {
                    SessionFilesView(files = selectedFiles, projectName = selectedProjectName,
                        phase = selectedPhase, sessionId = selectedSession, colors = colors,
                        onSwitch = { onSwitch(selectedSession) }, onDismiss = onDismiss)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sessions) { s ->
                            val isCurrent = s.id == sessionId
                            Surface(Modifier.fillMaxWidth().clickable { onSelectSession(s.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) CyanGreen.copy(alpha = 0.08f) else colors.Surface,
                                border = if (isCurrent) BorderStroke(1.dp, CyanGreen.copy(0.3f)) else null) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.projectName, fontSize = 12.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                        Row {
                                            Text(s.phase.name, fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                            if (s.fileCount > 0) Text(" · ${s.fileCount} files", fontSize = 9.sp,
                                                color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    IconButton(onClick = { onSwitch(s.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.PlayArrow, "Switch", tint = CyanGreen, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline, "Delete", tint = colors.Red.copy(0.5f), modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        if (sessions.isEmpty()) item { Text("No saved sessions", fontSize = 12.sp, color = colors.Text3,
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
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            color = CyanGreen.copy(alpha = 0.1f), border = BorderStroke(1.dp, CyanGreen.copy(0.3f))) {
            Row(Modifier.clickable { onSwitch() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = CyanGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue ${projectName.ifEmpty { "Session" }}", fontSize = 12.sp, color = CyanGreen,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (phase != null) Text(phase.name, fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(files) { node -> FileRow(node, colors) }
            if (files.isEmpty()) item { Text("No files yet", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
fun FileRow(node: FileNode, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = colors.Surface.copy(alpha = 0.5f), border = BorderStroke(1.dp, colors.Border.copy(0.2f))) {
        Row(Modifier.padding(8.dp).clickable {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description, null,
                tint = if (node.isDir) AmberAccent else CyanGreen, modifier = Modifier.size(16.dp))
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
    model1Path: String, model2Path: String, researcherPath: String, initialTab: Int = 0) {
    var settingsTab by remember { mutableStateOf(initialTab) }

    val getCfg = { role: String, path: String ->
        if (SettingsManager.inventSyncWithMain) SettingsManager.getModelTokenConfig(path)
        else SettingsManager.getInventModelConfig(role) ?: SettingsManager.getModelTokenConfig(path)
    }
    val plannerCfg = remember(model1Path) { getCfg("Planner", model1Path) }
    val researcherCfg = remember(researcherPath) { getCfg("Researcher", researcherPath) }
    val coderCfg = remember(model2Path) { getCfg("Coder", model2Path) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.65f).fillMaxHeight(0.65f).clickable {},
            shape = RoundedCornerShape(24.dp), color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Model Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    listOf("Planner", "Researcher", "Coder").forEachIndexed { i, label ->
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = if (settingsTab == i) CyanGreen.copy(alpha = 0.12f) else colors.Surface,
                            border = BorderStroke(1.dp, if (settingsTab == i) CyanGreen else colors.Border)) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = if (settingsTab == i) CyanGreen else colors.Text3, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { settingsTab = i }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                val cfg = when (settingsTab) { 0 -> plannerCfg; 1 -> researcherCfg; 2 -> coderCfg; else -> null }
                val path = when (settingsTab) { 0 -> model1Path; 1 -> researcherPath; 2 -> model2Path; else -> "" }
                val role = when (settingsTab) { 0 -> "Planner"; 1 -> "Researcher"; 2 -> "Coder"; else -> "" }
                ModelConfigView(role = role, config = cfg, modelPath = path, colors = colors)
            }
        }
    }
}

@Composable
fun ModelConfigView(role: String, config: ModelTokenConfig?, modelPath: String, colors: ZcPalette) {
    val items = if (config != null) listOf(
        "Model" to modelPath.substringAfterLast('/').substringAfterLast('\\').take(28),
        "Context" to "${config.ctx}", "Max Tokens" to "${config.maxNew}", "GPU Layers" to "${config.gpuLayers}",
        "Temperature" to "${config.temperature ?: "global"}", "Top-P" to "${config.topP ?: "global"}",
        "Min-P" to "${config.minP ?: "global"}", "Top-K" to "${config.topK ?: "global"}",
        "Repeat" to "${config.repeatPenalty ?: "global"}", "Freq" to "${config.freqPenalty ?: "global"}",
        "Pres" to "${config.presPenalty ?: "global"}", "Seed" to "${config.seed ?: "global"}",
        "Flash" to "${if (config.flashAttention == true) "on" else if (config.flashAttention == false) "off" else "global"}",
        "Low RAM" to "${if (config.lowRamMode == true) "on" else if (config.lowRamMode == false) "off" else "global"}",
        "Threads" to "${config.threads ?: "global"}", "Batch" to "${config.nBatch ?: "global"}"
    ) else listOf("Model" to modelPath.substringAfterLast('/').substringAfterLast('\\').take(28),
        "Note" to "Using global defaults")

    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("$role Configuration", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.Text, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp)) }
        items(items) { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Text(v, fontSize = 10.sp, color = CyanGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
        }
        item { Spacer(Modifier.height(8.dp))
            Text("GGUF · TFLite · MNN", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace) }
    }
}
